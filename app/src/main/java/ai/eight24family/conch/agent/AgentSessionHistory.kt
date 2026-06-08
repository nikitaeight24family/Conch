package ai.eight24family.conch.agent

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the per-session history list, its id→index lookup map, and the
 * streaming-emit batcher. Extracted from `AgentSession.kt` so the
 * surface invariants (see below) live in one place and the parent
 * class doesn't get re-introduced to data members it shouldn't be
 * mutating directly.
 *
 * ─── Invariants ────────────────────────────────────────────────────
 *
 * 1. [flushLock] is PER-INSTANCE (private val on this object).
 *    Cross-session writes never contend on a global lock.
 *
 * 2. **Streaming-emit batching atomicity.** [emitMsg] and
 *    [flushStreamingBuffer] mutate [streamingBuffer] AND
 *    [_history] / [historyIndex] under the SAME [flushLock]. The
 *    snapshot-then-clear pair inside the flush is one critical
 *    section — no concurrent `streamingBuffer[id] = msg` can land
 *    between the `HashMap(buffer)` and the `buffer.clear()` and get
 *    wiped.
 *
 * 3. Streaming AssistantText is buffered ~80 ms in [streamingBuffer]
 *    before flushing. Non-streaming types (UserText / Error / System /
 *    ToolUse / PermissionRequest) flush the buffer FIRST and then
 *    apply synchronously — preserves the relative order between a
 *    streaming reply and a subsequent tool call.
 *
 * 4. [PersistentList] structural sharing matters — never replace with
 *    `ArrayList`. [historyIndex] is a [PersistentMap] kept in
 *    lockstep; every write to `_history` MUST be paired with the
 *    matching write to `historyIndex` inside the same [flushLock]
 *    block.
 */
internal class AgentSessionHistory(
    private val scope: CoroutineScope,
) {
    // PersistentList<T> extends List<T> — every consumer that just
    // iterates / indexes / calls `.size` / `.lastOrNull()` keeps working
    // verbatim. The point of switching off the bare List<AgentMessage>:
    // structural sharing. A non-streaming `applyToHistory` used to do
    // `list.toMutableList().also { it[i] = msg }` — that's an O(N)
    // copy of every reference for a one-element change. On a 1000-msg
    // session × ~100 non-streaming emits/turn that's ~100K reference
    // copies per turn just to mutate one cell. PersistentList.set(i, m)
    // is O(log32 N) — the 32-ary trie clones the path to the touched
    // leaf and aliases everything else.
    //
    // [historyIndex] keeps msgId → index in lockstep so `applyToHistory`
    // upserts and `appendDeduped`-style filters are O(1) per id instead
    // of repeating `indexOfLast { it.id == ... }` linear scans.
    // INVARIANT: every write to `_history` MUST be paired with an
    // identically-shaped write to `historyIndex` under [flushLock] (or
    // inside the same `_history.update { ... }` block) so consumers
    // never observe one out of sync with the other.
    private val _history = MutableStateFlow<PersistentList<AgentMessage>>(persistentListOf())
    val history: StateFlow<PersistentList<AgentMessage>> = _history.asStateFlow()

    /** id → index in [_history]. Maintained in lockstep with the list.
     *  ALL reads/writes serialise on [flushLock]; `@Volatile` ensures
     *  any happens-before-relation-establishing read sees the latest
     *  reference even if some lock-free fast path ever sneaks in. */
    @Volatile private var historyIndex: PersistentMap<String, Int> = persistentMapOf()

    // **Streaming-emit batching.** Claude/Codex deltas land 50-100×/sec
    // while a reply is being generated; emitting each one immediately
    // to `_history` triggered an equivalent recomposition rate of the
    // chat screen, which competed with touch event dispatch — user
    // could see the scroll gesture jank during streaming. We now
    // buffer rapid-fire AssistantText updates per-id and flush at
    // ~12 FPS (80 ms window). Markdown is already debounced 120 ms on
    // top of that, so user-visible cadence is ~8 FPS while a reply
    // grows — still feels live but main thread sees an order of
    // magnitude less recomposition work. Non-streaming message types
    // (UserText / Error / System / etc.) flush immediately so order
    // and feedback are preserved.
    // ─── Per-session write serialisation (Durov critique #7) ───
    //
    // [flushLock] is PER-INSTANCE — every AgentSessionHistory gets its
    // own `Any()` monitor. Across N sessions on N servers the write
    // paths run in parallel; the lock only serialises within the SAME
    // session's history. That's exactly the granularity we want: the
    // StateFlow snapshot a UI collector reads must reflect an atomic
    // apply, but session A's emit never blocks session B's.
    //
    // We don't go to actor / Channel / Mutex here because emitMsg is
    // synchronous w.r.t. its caller — parsing pipelines downstream of
    // the JSON reader want `history.value` to reflect the emit by the
    // time the next line is read. An async actor would silently
    // reorder that. Synchronized blocks give us the serialisation
    // without the suspend cascade.
    //
    // [streamingBuffer] is a plain HashMap (not ConcurrentHashMap) —
    // every read+write goes through [flushLock] so the CAS overhead
    // of the concurrent map was pure waste.
    private val streamingBuffer = HashMap<String, AgentMessage>()
    @Volatile private var flushJob: Job? = null
    private val flushLock = Any()

    /** Append-or-update by id. The old behaviour (always append) made
     *  `--include-partial-messages` streaming useless — every delta from
     *  Claude got a fresh AgentMessage and the chat showed N copies of
     *  the same answer growing one block at a time. Now we look for an
     *  existing message with the same id (Claude ships a stable
     *  `msg_xxx#blockIndex` for every assistant chunk in the same turn)
     *  and replace it in place, so the bubble grows live. */
    fun emitMsg(msg: AgentMessage) {
        if (msg is AgentMessage.AssistantText) {
            // Whole emit + flush-job-schedule must be in the same lock
            // as the flush drain below. Without it there's a window
            // between `HashMap(buffer)` and `buffer.clear()` where a
            // concurrent `streamingBuffer[id] = newMsg` would land —
            // then `clear()` would wipe it, and the message would be
            // gone from both buffer AND _history. Symptom: "last 1-2
            // assistant messages missing when I reopen the chat".
            synchronized(flushLock) {
                streamingBuffer[msg.id] = msg
                if (flushJob?.isActive != true) {
                    flushJob = scope.launch {
                        kotlinx.coroutines.delay(80L)
                        flushStreamingBuffer()
                    }
                }
            }
        } else {
            // Non-streaming type — flush any pending streams first
            // (preserves the order between a streaming AssistantText
            // and a subsequent ToolUse / PermissionRequest / Error),
            // then apply this message synchronously.
            flushStreamingBuffer()
            applyToHistory(msg)
        }
    }

    /** Drain [streamingBuffer] into `_history` in a single atomic
     *  update. Called every 80 ms during a streaming reply, plus
     *  whenever a non-streaming message arrives (to preserve
     *  ordering vs. the buffered streams).
     *
     *  Persistent-vector layout: each id we see in the snapshot is
     *  either a known position (look up via [historyIndex] for O(1))
     *  or new (append). We mutate via [PersistentList.builder] so a
     *  burst of N updates costs one snapshot at the end instead of N
     *  copies along the way — the builder shares structure with its
     *  source and only forks the nodes it touches.
     *
     *  Both `_history.value` and [historyIndex] are reassigned under
     *  [flushLock]; consumers downstream of `history` flow get a
     *  single emission that matches the new index. */
    fun flushStreamingBuffer() = ai.eight24family.conch.util.Tracing.section(
        ai.eight24family.conch.util.Tracing.Names.FLUSH_STREAMING
    ) {
        synchronized(flushLock) {
            if (streamingBuffer.isEmpty()) return@section
            val snapshot = HashMap(streamingBuffer)
            streamingBuffer.clear()

            val listBuilder = _history.value.builder()
            val indexBuilder = historyIndex.builder()
            for ((id, msg) in snapshot) {
                val existingIdx = indexBuilder[id]
                if (existingIdx != null) {
                    // Replace in-place — preserves position so the
                    // bubble updates live instead of jumping to the
                    // bottom of the chat.
                    listBuilder[existingIdx] = msg
                } else {
                    indexBuilder[id] = listBuilder.size
                    listBuilder.add(msg)
                }
            }
            _history.value = listBuilder.build()
            historyIndex = indexBuilder.build()
        }
    }

    /** Synchronous update for non-streaming message types.
     *
     *  Old behaviour: `list.indexOfLast { it.id == msg.id }` — O(N)
     *  scan over the full history every emit. With [historyIndex]
     *  it's now O(1) on the lookup; `PersistentList.set` is
     *  O(log32 N) when we hit. Adding to the end stays O(log32 N)
     *  for the persistent vector. */
    private fun applyToHistory(msg: AgentMessage) = ai.eight24family.conch.util.Tracing.section(
        ai.eight24family.conch.util.Tracing.Names.APPLY_TO_HISTORY
    ) {
        synchronized(flushLock) {
            val current = _history.value
            val idx = historyIndex[msg.id]
            if (idx != null) {
                // In-place replace — same id, same position. Index
                // map unchanged. The reference at `idx` gets swapped,
                // ~5 nodes on the trie path clone, everything else
                // stays aliased to the previous snapshot.
                _history.value = current.set(idx, msg)
            } else {
                // Append + record the new tail index in the map.
                _history.value = current.add(msg)
                historyIndex = historyIndex.put(msg.id, current.size)
            }
        }
    }

    /**
     * Replace history with the given list (used to replay a persisted
     * session). Deduplicates by id — Claude's JSONL legitimately
     * contains the same `tool_use` block referenced from both the
     * assistant message that produced it AND a subsequent system
     * event, and `LazyColumn` crashes hard on duplicate keys.
     */
    fun loadHistory(messages: List<AgentMessage>) {
        synchronized(flushLock) {
            // distinctBy preserves first-seen order so the new index
            // map's int values line up with positions in the rebuilt
            // list.
            val incoming = messages.distinctBy { it.id }

            // ── ROBUSTNESS GUARANTEE: loadHistory must NEVER drop a user's
            // just-sent prompt. ──────────────────────────────────────────
            // loadHistory REPLACES the whole list (used on every rebuild:
            // reconnect, read-only re-open, offline→connect, resume). The
            // incoming list comes from the server JSONL / local cache, which
            // LAGS by ~one turn — the prompt the user just sent (shown
            // optimistically with a local UUID id) hasn't been written there
            // yet. A blind replace silently deletes it → the recurring bug,
            // through a new path every time.
            //
            // So: a UserText in the CURRENT history that the incoming list
            // doesn't yet cover is an un-synced optimistic prompt — PRESERVE it.
            // Count-based per trimmed body (so two identical prompts both
            // survive, and a synced one isn't double-kept). Appended after the
            // incoming content — these are the most-recent messages. Once the
            // JSONL catches up, a later loadHistory sees the incoming copy and
            // the surplus naturally drops to zero. This is append/upsert/load's
            // shared invariant now: the user's words are sacred.
            val current = _history.value
            val incomingUserCounts = HashMap<String, Int>()
            for (m in incoming) if (m is AgentMessage.UserText) {
                val b = m.text.trim()
                incomingUserCounts[b] = (incomingUserCounts[b] ?: 0) + 1
            }
            val incomingIds = incoming.mapTo(HashSet()) { it.id }
            val seen = HashMap<String, Int>()
            val survivors = ArrayList<AgentMessage>()
            for (m in current) {
                if (m !is AgentMessage.UserText) continue
                if (m.id in incomingIds) continue            // same message already incoming
                val b = m.text.trim()
                val s = seen[b] ?: 0
                seen[b] = s + 1
                if (s < (incomingUserCounts[b] ?: 0)) continue // a JSONL copy covers this one
                survivors.add(m)                              // un-synced optimistic prompt → keep
            }

            val listBuilder = persistentListOf<AgentMessage>().builder()
            val indexBuilder = persistentMapOf<String, Int>().builder()
            for (m in incoming) {
                listBuilder.add(m)
                indexBuilder[m.id] = listBuilder.lastIndex
            }
            for (m in survivors) {
                if (indexBuilder.containsKey(m.id)) continue
                indexBuilder[m.id] = listBuilder.size
                listBuilder.add(m)
            }
            _history.value = listBuilder.build()
            historyIndex = indexBuilder.build()
            if (survivors.isNotEmpty()) {
                android.util.Log.d(
                    "SshAi-Hist",
                    "loadHistory: preserved ${survivors.size} un-synced user prompt(s) the cache/JSONL hadn't caught up to",
                )
            }
        }
    }

    /**
     * Append messages to history without touching what's already there.
     * Filters out anything whose id is already present (see
     * [loadHistory] for why duplicates show up in JSONL).
     *
     * Filtering is now O(M) (one hash lookup per incoming) instead of
     * O(M·N) (`existing = prev.map { it.id }.toHashSet()` rebuilt the
     * set every call); the index map already holds every known id.
     */
    fun appendMessages(messages: List<AgentMessage>) {
        if (messages.isEmpty()) return
        synchronized(flushLock) {
            val current = _history.value
            val listBuilder = current.builder()
            val indexBuilder = historyIndex.builder()
            for (m in messages) {
                if (indexBuilder.containsKey(m.id)) continue
                indexBuilder[m.id] = listBuilder.size
                listBuilder.add(m)
            }
            if (listBuilder.size != current.size) {
                _history.value = listBuilder.build()
                historyIndex = indexBuilder.build()
            }
        }
    }

    /**
     * Resolve any pending [AgentMessage.PermissionRequest] whose
     * `requestId` matches by setting its `resolved` field. We mutate
     * via a transient builder so any matching PermissionRequest gets
     * replaced in place (PersistentList.set is O(log32 N) per touched
     * cell). No id changes, no positional shifts → [historyIndex]
     * stays valid.
     */
    fun resolvePermission(
        requestId: String,
        resolution: AgentMessage.PermissionRequest.Resolution,
    ) {
        synchronized(flushLock) {
            val current = _history.value
            var changed = false
            val builder = current.builder()
            for (i in current.indices) {
                val m = current[i]
                if (m is AgentMessage.PermissionRequest && m.requestId == requestId) {
                    builder[i] = m.copy(resolved = resolution)
                    changed = true
                }
            }
            if (changed) _history.value = builder.build()
        }
    }
}
