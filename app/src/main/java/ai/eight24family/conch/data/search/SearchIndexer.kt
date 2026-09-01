package ai.eight24family.conch.data.search

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.data.HistoryCache
import ai.eight24family.conch.util.SilentlyTry
import ai.eight24family.conch.util.Tracing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the FTS index in sync with [HistoryCache].
 *
 * **Indexed unit (v3): one parsed [AgentMessage].** Each indexed row
 * holds the message's user-visible text body, its stable msgId, and
 * its ordinal (position in chat). When the user taps a search hit,
 * ChatScreen has everything it needs to anchor its LazyListState to
 * the right item — `initialFirstVisibleItemIndex = ordinal` — and
 * land at the matched message with no scroll motion. This is the
 * Telegram pattern.
 *
 * Two operating modes:
 *  - [reconcile]    — diff cache vs index, add new sessions, drop
 *                     missing ones, re-index changed ones. Called
 *                     once at app start and on demand.
 *  - [indexSession] — surgical: re-index a single sessionId. Called
 *                     by HistoryCache write paths (append / save /
 *                     mergeServer) so the index stays warm without
 *                     a full sweep.
 */
class SearchIndexer(
    private val db: SearchDatabase,
    private val cache: HistoryCache,
    private val scope: CoroutineScope,
) {
    companion object {
        /** Bump when the indexing LOGIC changes (what searchableBody
         *  returns, role rules). A session whose stored indexerVersion
         *  differs is re-indexed even if its cache bytes are unchanged.
         *  v2: started indexing tool calls / outputs / system messages.
         *  v3: stamp sessionMtime for newest-first result ordering.
         *  v4: force re-index to run the orphan root-cause diagnostic.
         *  v5: force re-index to dump the oversized-line head for inspection.
         *  v6: oversized lines now index a truncated preview (was skipped). */
        const val INDEXER_VERSION = 6

        /** Cap on a single tool-call / tool-output / system body we index.
         *  Commands can dump megabytes; we only need enough to find the
         *  match, and external-content FTS stores this body on flash. */
        const val MAX_NONCHAT_CHARS = 16_000
    }

    data class Progress(
        val done: Int,
        val total: Int,
        val running: Boolean,
    ) {
        val fraction: Float
            get() = if (total > 0) done.toFloat() / total.toFloat() else 0f
    }

    private val _progress = MutableStateFlow(Progress(done = 0, total = 0, running = false))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var reconcileJob: Job? = null

    fun reconcile(): Job {
        reconcileJob?.let { if (it.isActive) return it }
        val job = scope.launch(Dispatchers.IO) {
            Tracing.section(Tracing.Names.RECONCILE_SCAN) {
                try {
                    val dao = db.searchDao()
                    val cacheIds = cache.listSessionIds().toSet()
                    var indexed = dao.allSessionStates().associateBy { it.sessionId }
                    val metaRows = dao.countAllMeta()
                    android.util.Log.i(
                        "Conch-Indexer",
                        "reconcile start: cacheIds=${cacheIds.size} indexed=${indexed.size} ftsMeta=$metaRows"
                    )

                    // Self-heal phantom index: session_state says N sessions
                    // are indexed, but the FTS content table is empty (or
                    // near-empty). This happens when a schema migration /
                    // recreation dropped the FTS + meta tables but left
                    // session_state intact — reconcile then trusts the stale
                    // state, skips every "indexed" session, and search
                    // silently returns nothing. THE bug. Detect the
                    // inconsistency and force a clean full rebuild from
                    // HistoryCache (the index is derived data — safe to
                    // wipe). One-shot: after rebuild ftsMeta > 0, won't fire
                    // again.
                    if (indexed.isNotEmpty() && metaRows == 0L) {
                        android.util.Log.w(
                            "Conch-Indexer",
                            "PHANTOM INDEX: ${indexed.size} session_state rows but 0 FTS rows " +
                                "— wiping state + rebuilding all from cache"
                        )
                        dao.clearSessionStates()
                        dao.clearMeta()
                        dao.clearFts()
                        indexed = emptyMap()
                    }

                    for (gone in indexed.keys - cacheIds) {
                        dropSession(gone)
                    }

                    val toIndex = mutableListOf<String>()
                    for (sid in cacheIds) {
                        val state = indexed[sid]
                        // Closed immediately — we only need the size to
                        // decide if a re-index is needed; the actual byte
                        // walk happens in indexSessionInternal.
                        val snap = cache.load(sid) ?: continue
                        val remaining = snap.buffer.remaining()
                        snap.close()
                        // Re-index if: never indexed, bytes changed, OR the
                        // indexing logic changed since (e.g. we now index
                        // tool/system content the old pass skipped).
                        if (state == null ||
                            state.sourceBytes != remaining ||
                            state.indexerVersion != INDEXER_VERSION ||
                            // Self-heal orphans: a session indexed WITHOUT a
                            // resolvable server (serverId null) is retried every
                            // reconcile, so the moment its owner becomes known
                            // (its server gets listed → durable sidecar written)
                            // the next reconcile re-stamps it — no version bump.
                            // Self-limiting: once serverId is set it stops being
                            // retried.
                            state.serverId == null
                        ) {
                            toIndex += sid
                        }
                    }
                    android.util.Log.i(
                        "Conch-Indexer",
                        "reconcile toIndex=${toIndex.size} sessions"
                    )
                    _progress.value = Progress(done = 0, total = toIndex.size, running = true)
                    for ((i, sid) in toIndex.withIndex()) {
                        // One pathological session must never crash the whole
                        // app. Catch EVERYTHING (incl OutOfMemoryError from a
                        // monster rollout) → log + skip → keep indexing the
                        // rest. Was a crash-loop: a 543 MB session OOM'd the
                        // indexer coroutine every launch.
                        try {
                            indexSessionInternal(sid)
                        } catch (t: Throwable) {
                            android.util.Log.w(
                                "Conch-Indexer",
                                "index sid=${sid.take(8)} failed — skipped: ${t.javaClass.simpleName} ${t.message}"
                            )
                        }
                        _progress.value = Progress(done = i + 1, total = toIndex.size, running = true)
                    }
                    android.util.Log.i(
                        "Conch-Indexer",
                        "reconcile done: processed ${toIndex.size} sessions; " +
                            "fts meta rows now = ${dao.countAllMeta()}"
                    )
                } catch (t: Throwable) {
                    android.util.Log.e("Conch-Indexer", "reconcile failed", t)
                } finally {
                    _progress.value = _progress.value.copy(running = false)
                }
            }
        }
        reconcileJob = job
        return job
    }

    fun indexSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                indexSessionInternal(sessionId)
            } catch (t: Throwable) {
                android.util.Log.w(
                    "Conch-Indexer",
                    "index sid=${sessionId.take(8)} failed — skipped: ${t.javaClass.simpleName} ${t.message}"
                )
            }
        }
    }

    private suspend fun dropSession(sid: String) {
        val dao = db.searchDao()
        // External-content FTS4 (v8): deleting from meta fires the
        // Room-generated BEFORE DELETE trigger which also removes the
        // FTS index rows.
        dao.deleteMetaBySession(sid)
        dao.deleteSessionState(sid)
    }

    /** Parse + index one session.
     *
     *  Agent selection: try [ai.eight24family.conch.data.SessionsCache]
     *  first — the same lookup AppNav uses when opening a chat from a
     *  search hit. Whatever agent the session is FILED UNDER there is
     *  the agent ChatViewModel will use, so msg IDs round-trip
     *  correctly. */
    private suspend fun indexSessionInternal(sid: String) = Tracing.section(
        Tracing.Names.INDEX_SESSION
    ) {
        val dao = db.searchDao()
        val snap = cache.load(sid) ?: run {
            dropSession(sid)
            return@section
        }
        snap.use {
            val buffer = snap.buffer
            val bytesLen = buffer.remaining()
            val state = dao.sessionState(sid)
            // Up-to-date only if bytes AND indexing logic match.
            if (state != null &&
                state.sourceBytes == bytesLen &&
                state.indexerVersion == INDEXER_VERSION
            ) {
                // …but if the ONLY thing missing is the owner (serverId null — it
                // was an orphan when first indexed), re-resolve it now. Its server
                // may have been listed / its history.jsonl harvested since, so the
                // sidecar now exists. Backfill JUST the state row — no re-parse /
                // re-insert of FTS (cheap). Without this, reconcile's serverId==null
                // retry added the session to toIndex forever but this early-return
                // no-op'd it → "toIndex=18" churned every pass and the server label
                // never landed in the index.
                if (state.serverId == null) {
                    val owner = resolveOwner(sid)
                    if (owner != null) {
                        dao.upsertSessionState(
                            state.copy(
                                serverId = owner.serverId,
                                path = owner.path,
                                sessionMtime = owner.mtime ?: state.sessionMtime,
                                indexedAt = System.currentTimeMillis(),
                            )
                        )
                        android.util.Log.d(
                            "Conch-Indexer",
                            "backfilled owner sid=${sid.take(8)} → server=${owner.serverId.take(8)}",
                        )
                    }
                }
                return@use
            }

            withContext(Dispatchers.IO) {
                // Prefer the authoritative owner from SessionsCache — its
                // msg IDs round-trip exactly through the chat parser, so a
                // search-hit → chat navigation lands on the right message.
                //
                // BUT SessionsCache is only populated for servers with a live
                // (or recently-cached) connection. SK servers that haven't
                // been tap-connected this run have an empty cache, so owner
                // resolution returns null — and the whole session never got
                // indexed, making its chats unsearchable until the user
                // connects. That's the bug: the user's main (SK) chats were
                // silently skipped forever.
                //
                // Fallback: when the cache can't resolve, DETECT the agent
                // from the JSONL's structural markers (codex thread.started
                // / claude assistant-wrapper / gemini message shape). The
                // body text indexes correctly either way; only the msg-id
                // round-trip degrades to the ordinal anchor (which works
                // for every agent anyway). Far better than not indexing.
                // Resolve the FULL owner (serverId + agent + path) from
                // SessionsCache when possible — that's what lets a search hit
                // navigate. Fall back to content-detection for the AGENT only
                // (serverId/path stay null: we know what kind of chat it is,
                // so the icon shows, but not which server, so it can't open
                // until that server is seen again).
                val owner = resolveOwner(sid)
                val ownerAgent = owner?.agent ?: detectAgentFromContent(buffer)
                if (owner == null) {
                    // Indexed without a resolvable server yet — id-only log (no
                    // content). Self-heals: the serverId==null reconcile retry
                    // re-stamps it the moment its server is listed (durable
                    // sidecar / fresh SessionsCache).
                    android.util.Log.d(
                        "Conch-Indexer",
                        "orphan-at-index sid=${sid.take(8)} agent=${ownerAgent ?: "?"} — awaiting its server's next listing"
                    )
                }
                if (ownerAgent == null) {
                    android.util.Log.d(
                        "Conch-Indexer",
                        "skip sid=${sid.take(8)} — agent unresolved (cache miss + no JSONL markers)"
                    )
                    return@withContext
                }

                dao.deleteMetaBySession(sid)

                val parsed = parseWithAgent(buffer, ownerAgent) ?: run {
                    android.util.Log.w(
                        "Conch-Indexer",
                        "parse failed for sid=${sid.take(8)} owner=$ownerAgent bytes=$bytesLen"
                    )
                    dao.upsertSessionState(
                        SessionIndexState(
                            sessionId = sid,
                            indexedAt = System.currentTimeMillis(),
                            sourceBytes = bytesLen,
                            agent = ownerAgent.name,
                            serverId = owner?.serverId,
                            path = owner?.path,
                            indexerVersion = INDEXER_VERSION,
                            sessionMtime = owner?.mtime ?: snap.cachedAt,
                        )
                    )
                    return@withContext
                }
                val messages = parsed.messages
                val preview = extractPreview(messages)

                db.runInTransaction(Runnable {
                    kotlinx.coroutines.runBlocking {
                        messages.forEachIndexed { ordinal, msg ->
                            val text = searchableBody(msg) ?: return@forEachIndexed
                            if (text.isBlank()) return@forEachIndexed
                            // user / assistant = chat (default list); everything
                            // else (System, ToolUse, ToolResult, Error, Result)
                            // = "system", shown only behind the "+N system"
                            // toggle so tool noise doesn't bury real chat hits.
                            val role = when (msg) {
                                is AgentMessage.UserText -> "user"
                                is AgentMessage.AssistantText -> "assistant"
                                else -> "system"
                            }
                            // External-content FTS4: only the meta row is
                            // inserted explicitly. Room's AFTER INSERT
                            // trigger picks the content column up and
                            // writes the matching FTS posting list.
                            dao.insertMeta(
                                FtsLineMeta(
                                    rowid = 0L,
                                    sessionId = sid,
                                    msgId = msg.id,
                                    ordinal = ordinal,
                                    role = role,
                                    sessionPreview = preview,
                                    content = text,
                                )
                            )
                        }
                        dao.upsertSessionState(
                            SessionIndexState(
                                sessionId = sid,
                                indexedAt = System.currentTimeMillis(),
                                sourceBytes = bytesLen,
                                agent = ownerAgent.name,
                                serverId = owner?.serverId,
                                path = owner?.path,
                                // These two were silently defaulting to 0 on
                                // the SUCCESS path (the parse-failed path above
                                // set them) — two consequences: (1) version 0
                                // never matches INDEXER_VERSION so EVERY launch
                                // re-indexed EVERY session ("toIndex=86" every
                                // cold start); (2) sessionMtime 0 made the
                                // "newest first" search sort a no-op (all rows
                                // tied at 0). Stamp both, same as parse-failed.
                                indexerVersion = INDEXER_VERSION,
                                sessionMtime = owner?.mtime ?: snap.cachedAt,
                            )
                        )
                    }
                })
            }
        }
    }

    /** Full owner of a session — serverId + agent + on-disk path —
     *  resolved by scanning [ai.eight24family.conch.data.SessionsCache]
     *  across every (server, agent) pair. Returns null if the session id
     *  isn't in any cache (caller then content-detects the agent and
     *  leaves server/path null). */
    private data class ResolvedOwner(
        val serverId: String,
        val agent: Agent,
        val path: String?,
        /** Session's server-side mtime (RemoteSession.lastActiveAt) used to
         *  sort search results newest-first. Null when resolved from the
         *  durable sidecar (which doesn't store mtime) — caller then falls
         *  back to the cache file's own mtime (snap.cachedAt). */
        val mtime: Long?,
    )

    private suspend fun resolveOwner(sid: String): ResolvedOwner? {
        val locator = ai.eight24family.conch.di.ServiceLocator
        val servers = SilentlyTry.logged("Conch-Indexer", "observe servers for owner") {
            locator.serverRepository.observeServers().first()
        } ?: emptyList()
        val sessionsCache = locator.sessionsCache
        for (s in servers) {
            for (agent in Agent.entries) {
                val snap = sessionsCache.load(s.id, agent)
                val match = snap.sessions.firstOrNull { it.id == sid }
                if (match != null) return ResolvedOwner(s.id, agent, match.path, match.lastActiveAt)
            }
        }
        // Fallback: the durable owner sidecar HistoryCache wrote when this
        // session was first discovered/cached. SessionsCache is volatile in
        // practice — a later sweep overwrites a (server,agent) entry with a
        // newer/shorter `ls`, aging older sessions out of it — but the
        // per-session sidecar persists. This is PARITY with the tap-time
        // resolver in AppNav (which already consults the sidecar): without it
        // the index stamped serverId=null, so the row showed "no server" AND,
        // for sessions SessionsCache had also forgotten, the tap was a no-op.
        locator.historyCache.owner(sid)?.let { o ->
            android.util.Log.d(
                "Conch-Indexer",
                "owner via SIDECAR sid=${sid.take(8)} server=${o.serverId.take(8)} agent=${o.agent}"
            )
            return ResolvedOwner(o.serverId, o.agent, o.path, o.lastActiveAt.takeIf { it > 0L })
        }
        android.util.Log.d(
            "Conch-Indexer",
            "owner UNRESOLVED sid=${sid.take(8)} — no SessionsCache match, no sidecar (true orphan)"
        )
        return null
    }

    /** Fallback agent detection from the JSONL's own structural markers,
     *  used when [resolveOwnerAgent] can't (SessionsCache cold — e.g. SK
     *  server not connected this run). Reads only the first ~16 KB — the
     *  distinguishing event types appear in the opening lines of every
     *  session. Distinct, non-overlapping signatures per CLI:
     *
     *   - Codex: `thread.started` / `turn.started` / `item.completed` /
     *     `response_item` / `session_meta` / `event_msg` — none of which
     *     Claude or Gemini ever emit.
     *   - Claude: top-level `"type":"assistant"` wrapper (its assistant
     *     turns) — Codex/Gemini use `agent_message` / `message` instead.
     *   - Gemini: `"type":"message"` + `"type":"init"` with `session_id`
     *     and NO Claude/Codex markers.
     *
     *  Returns null only if the head matches nothing (truly unknown
     *  shape) — then the caller skips, same as before. */
    private fun detectAgentFromContent(buffer: java.nio.ByteBuffer): Agent? {
        val dup = buffer.duplicate().apply { rewind() }
        val head = ByteArray(minOf(dup.remaining(), 16 * 1024))
        dup.get(head)
        val s = String(head, Charsets.UTF_8)
        return when {
            // Grok updates.jsonl: ACP session/update records — no top-level
            // "type" at all, so this can't shadow anyone.
            s.contains("\"method\":\"session/update\"") ||
                s.contains("\"method\":\"_x.ai/session/update\"") ||
                s.contains("\"sessionUpdate\":\"") -> Agent.GROK
            // Copilot events.jsonl: namespaced dotted types — checked BEFORE
            // Claude's broad `"type":"user"`+`"message"` net.
            s.contains("\"type\":\"session.start\"") ||
                s.contains("\"type\":\"user.message\"") ||
                s.contains("\"type\":\"assistant.message\"") ||
                s.contains("\"type\":\"session.mcp_servers_loaded\"") -> Agent.COPILOT
            // Qwen's persisted records carry `provenance` — a field no other
            // agent writes — and Gemini-shaped `parts` inside a Claude-shaped
            // envelope. Checked before Claude's broad `"type":"user"` net.
            s.contains("\"provenance\":\"real_user\"") ||
                s.contains("\"provenance\":\"assistant_output\"") -> Agent.QWEN
            // opencode and Crush keep sessions in SQLite; what reaches the
            // indexer is their EXPORT document, recognisable by its own keys.
            s.contains("\"sessionID\":\"ses_") -> Agent.OPENCODE
            s.contains("\"tool_call_id\"") && s.contains("\"parts\"") -> Agent.CRUSH
            // Continue keeps one plain-JSON file per session; `editorState`
            // beside a history entry is its own, and nobody else's.
            s.contains("\"editorState\"") ||
                (s.contains("\"history\"") && s.contains("\"sessionId\"")) -> Agent.CONTINUE
            s.contains("\"type\":\"thread.started\"") ||
                s.contains("\"type\":\"turn.started\"") ||
                s.contains("\"type\":\"turn.completed\"") ||
                s.contains("\"type\":\"item.completed\"") ||
                s.contains("\"type\":\"item.started\"") ||
                s.contains("\"type\":\"response_item\"") ||
                s.contains("\"type\":\"session_meta\"") ||
                s.contains("\"type\":\"event_msg\"") -> Agent.CODEX
            s.contains("\"type\":\"assistant\"") ||
                s.contains("\"type\":\"user\"") && s.contains("\"message\"") -> Agent.CLAUDE
            s.contains("\"type\":\"message\"") || s.contains("\"type\":\"init\"") -> Agent.GEMINI
            else -> null
        }
    }

    /** Parse JSONL bytes with a SPECIFIC agent's spec — no auto-detect
     *  loop, no Claude-grabs-everything risk. */
    private fun parseWithAgent(buffer: java.nio.ByteBuffer, agent: Agent): ParsedSession? {
        if (!buffer.hasRemaining()) return null
        // Stream line-by-line — a whole-buffer Charset.decode() allocates a
        // CharBuffer sized to the entire session (~57 MB for a 28 MB chat),
        // which OOM-killed the indexer the moment "load all" pulled in a big
        // session (same crash class as the chat-open path, 2026-05-29).
        val spec = AgentSpecRegistry[agent]
        val out = mutableListOf<AgentMessage>()
        var turnSeq = 0
        ai.eight24family.conch.util.JsonlUtils.forEachLine(
            buffer,
            onOversize = { head, total ->
                // Index a truncated placeholder for a >16 MB line instead of
                // the whole blob — searchable preview, role=system, no OOM.
                // Same handling as the display parser (universal, all agents).
                out += AgentMessage.ToolResult(
                    id = "oversized-$total-${out.size}",
                    toolUseId = "",
                    output = ai.eight24family.conch.util.JsonlUtils.oversizedPreview(head, total),
                    isError = false,
                )
            },
        ) { line ->
            if (line.isNotBlank()) {
                if (line.contains("\"type\":\"turn.started\"") ||
                    line.contains("\"type\":\"thread.started\"")
                ) {
                    turnSeq++
                }
                val turnTag = if (turnSeq == 0) "" else "t${turnSeq}_"
                SilentlyTry.nullOnError { spec.parseStreamLine(line, turnTag) }
                    ?.let { out += it }
            }
        }
        val deduped = out.distinctBy { it.id }
        return ParsedSession(agent, deduped)
    }

    private data class ParsedSession(val agent: Agent, val messages: List<AgentMessage>)

    /** Text body to index for one [AgentMessage]. Only UserText /
     *  AssistantText contribute — that's what users actually search
     *  for. */
    private fun searchableBody(m: AgentMessage): String? = when (m) {
        is AgentMessage.UserText -> m.text
        is AgentMessage.AssistantText -> m.text
        // Everything below is non-chat content the user explicitly wanted
        // searchable too: tool calls (update_plan, bash, file edits…), their
        // outputs, and system payloads. These index under role="system" (see
        // the role mapping in indexSessionInternal) so they stay behind the
        // "+N system" toggle instead of flooding the chat-message results.
        //
        // Tool input/output is capped — a single command can dump megabytes
        // and we don't want to double that onto flash (external-content FTS
        // stores the body in fts_line_meta.content). 16 KB is plenty to make
        // the match findable; the chat itself holds the full text.
        is AgentMessage.ToolUse -> "${m.toolName} ${m.input}".take(MAX_NONCHAT_CHARS)
        is AgentMessage.ToolResult -> m.output.take(MAX_NONCHAT_CHARS)
        is AgentMessage.System -> m.raw.take(MAX_NONCHAT_CHARS)
        is AgentMessage.Error -> m.text
        is AgentMessage.Result -> m.text
        // Subagent work IS content — a Task fan-out can be most of a session's
        // research. It never shows as a chat row, but it must stay findable.
        is AgentMessage.SubagentActivity ->
            m.text?.take(MAX_NONCHAT_CHARS)
        // Raw = one-line "· event" chrome markers + stderr leaks;
        // PermissionRequest = transient prompt. Neither is worth indexing.
        else -> null
    }

    /** First non-trivial user prompt — chat label in hit rows. */
    private fun extractPreview(messages: List<AgentMessage>): String {
        val systemPrefixes = listOf(
            "This session is being continued",
            "[Request interrupted by user",
            "<system-reminder>",
            "Caveat:",
        )
        for (m in messages) {
            if (m !is AgentMessage.UserText) continue
            val t = m.text
            if (t.isBlank()) continue
            if (systemPrefixes.any { t.startsWith(it, ignoreCase = true) }) continue
            return t.lineSequence().firstOrNull { it.isNotBlank() }
                .orEmpty().trim().take(80)
        }
        return ""
    }
}
