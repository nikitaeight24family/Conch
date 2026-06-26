package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Tail-poll loop for a remote JSONL session file.
 *
 * Catches up the local cache to the server's view, then polls every few seconds for
 * external growth (e.g. the user typed on their PC). Growth that happens during OUR
 * own turn (state == Working) is silently consumed — those bytes are streaming in via
 * runOneShot's stdout collector and re-parsing them would duplicate every assistant /
 * tool message.
 *
 * Owns:
 *  - [remoteActive] — unix-millis of last EXTERNAL growth (drives the "● remote session
 *    active" banner).
 *  - [remoteFileOpen] — whether anything on the server still has the JSONL open for
 *    writing (drives the working-spinner; flips off cleanly the moment the process dies).
 *  - [tailBackgrounded] — whether the chat is currently backgrounded (drives the
 *    exponential back-off in [pickPollInterval]).
 *
 * Stateless helpers:
 *  - [parseJsonl] (ByteBuffer + ByteArray overloads), [parseJsonlText] — JSONL → AgentMessage list.
 *  - [appendDeduped] — defensive merge into the AgentSession's history.
 *  - [pickPollInterval] — adaptive cadence (working/foreground/background).
 *  - [statSize], [statSizeAndAgentAlive], [fetchTail] — SSH probes via `execOnLive`.
 *
 * The orchestrating `tailPoll(...)` runs as a single launched coroutine per session
 * — the ChatViewModel kicks one off from `pollerJobs[localId]`.
 *
 * See ChatViewModel.kt prior to extraction for the original inline comments.
 */
internal class ChatViewModelTailPoll(
    /** Read-only accessor — true while the chat is backgrounded; null
     *  while foreground.  Used to drive [pickPollInterval]. */
    private val backgroundedSince: () -> Long?,
) {
    /**
     * Unix-millis timestamp of the most recent EXTERNAL growth seen on the remote
     * session's JSONL file.
     */
    private val _remoteActive = MutableStateFlow<Long?>(null)
    val remoteActive: StateFlow<Long?> = _remoteActive.asStateFlow()

    /**
     * True when *something on the server* still has the remote session JSONL open for
     * writing — typically the `claude --print` process for an in-flight turn.
     */
    private val _remoteFileOpen = MutableStateFlow(false)
    val remoteFileOpen: StateFlow<Boolean> = _remoteFileOpen.asStateFlow()

    fun setRemoteFileOpen(value: Boolean) {
        _remoteFileOpen.value = value
    }

    /**
     * Epoch-ms start of the turn currently in flight (the `user` event's
     * timestamp parsed from the session file), or null when no turn is running.
     * For app-driven turns it's our own just-written `user` event, which is the
     * correct start too.
     */
    private val _remoteTurnStartMs = MutableStateFlow<Long?>(null)
    val remoteTurnStartMs: StateFlow<Long?> = _remoteTurnStartMs.asStateFlow()

    /** True only in the THINKING phase (model generating — last event is `user`),
     *  false during a tool run. Gates the working-row's «with X effort» suffix to
     *  match the CLI, which drops it while a tool is executing. */
    private val _remoteThinking = MutableStateFlow(false)
    val remoteThinking: StateFlow<Boolean> = _remoteThinking.asStateFlow()

    /**
     * True when the turn has been "thinking" (awaiting the assistant) with the
     * session file FROZEN for an unusually long time — almost certainly the
     * agent is BLOCKED on a console-side prompt (an AskUserQuestion the model
     * asked, which Claude keeps in RAM and never writes to the file until it's
     * answered). The app can't show that pending question (it's not on disk), so
     * instead of a fake "thinking 27m" spinner it tells the user to go answer in
     * their server session. Never fires for a running tool (that's legit long
     * work).
     */
    private val _remoteWaitingForInput = MutableStateFlow(false)
    val remoteWaitingForInput: StateFlow<Boolean> = _remoteWaitingForInput.asStateFlow()

    /** Cumulative output tokens of the in-flight MIRRORED turn (summed from the
     *  file's assistant usage), 0 when idle / app-driven. Feeds the working-row's
     *  «↓ N tokens» when there's no live thinking_tokens feed. */
    private val _remoteTokens = MutableStateFlow(0L)
    val remoteTokens: StateFlow<Long> = _remoteTokens.asStateFlow()

    /**
     * Catch up on bytes added to the remote session file since [initialOffset], then
     * poll every few seconds for further growth.
     */
    suspend fun tailPoll(
        s: AgentSession,
        agent: Agent,
        sessionId: String,
        path: String,
        initialOffset: Long,
    ) {
        val cache = ServiceLocator.historyCache
        var lastOffset = initialOffset

        // Wait for SSH to be Running so execOnLive doesn't fall back to a fresh handshake.
        var waited = 0
        while (s.state.value !is SessionState.Running &&
            s.state.value !is SessionState.Working &&
            waited < 15_000
        ) {
            delay(200); waited += 200
        }
        if (s.state.value is SessionState.Failed || s.state.value is SessionState.Closed) return

        // Initial spinner state = our own turn OR the file's last event says a
        // turn is in flight (the poll loop refines this every tick). The
        // last-event signal (not pgrep, not growth) catches a console agent
        // mid-think the instant the chat opens.
        // Truncate detection + merge-not-wipe.
        val pre = statSizeAndAgentAlive(s, path, sessionId)
        val preSize = pre.size
        _remoteFileOpen.value = (s.state.value is SessionState.Working) || pre.inFlight
        _remoteTurnStartMs.value = pre.turnStartMs
        _remoteThinking.value = pre.thinking
        _remoteTokens.value = pre.tokens
        if (preSize != null && preSize < lastOffset) {
            android.util.Log.w(
                "SshAi-Tail",
                "compact detected sid=${sessionId.take(8)} cachedOffset=$lastOffset serverSize=$preSize — merging cache (NOT touching live history)",
            )
            val serverFull = fetchTail(s, path, 0L) ?: ByteArray(0)
            val merged = cache.mergeServer(sessionId, serverFull)
            if (merged != null) cache.save(sessionId, merged) // null = local too large to merge; keep file
            lastOffset = preSize.toLong()
            android.util.Log.i(
                "SshAi-Tail",
                "compact merged sid=${sessionId.take(8)} mergedBytes=${merged?.size ?: -1} newOffset=$lastOffset",
            )
        }

        // ── Catch-up pass ──
        val tailBytes = fetchTail(s, path, lastOffset) ?: ByteArray(0)
        android.util.Log.i(
            "SshAi-Tail",
            "catch-up sid=${sessionId.take(8)} lastOffset=$lastOffset tailBytes=${tailBytes.size} state=${s.state.value::class.simpleName}",
        )
        if (tailBytes.isNotEmpty()) {
            val safe = trimToLastNewline(tailBytes)
            if (safe.isNotEmpty()) {
                val parsed = parseJsonl(safe, agent)
                val added: Int
                if (lastOffset == 0L && s.history.value.isEmpty()) {
                    s.loadHistory(parsed)
                    cache.save(sessionId, safe)
                    added = parsed.size
                } else {
                    added = appendDeduped(s, parsed)
                    cache.append(sessionId, safe)
                }
                lastOffset += safe.size.toLong()
                android.util.Log.i(
                    "SshAi-Tail",
                    "catch-up parsed=${parsed.size} added=$added newOffset=$lastOffset history=${s.history.value.size}",
                )
            }
        } else if (tailBytes.isEmpty() && lastOffset > 0) {
            val serverSize = statSizeAndAgentAlive(s, path, sessionId).size
            android.util.Log.w(
                "SshAi-Tail",
                "catch-up EMPTY sid=${sessionId.take(8)} lastOffset=$lastOffset serverSize=$serverSize " +
                    (if (serverSize != null && serverSize > lastOffset)
                        "STALE: server has ${serverSize - lastOffset}B unread (fetchTail failed?)"
                    else
                        "cache up-to-date (or server smaller)")
            )
        }

        // ── Poll loop ──
        var lastSeenWorking = false
        // Whether WE drove the turn last tick (s.state == Working). DISTINCT from
        // lastSeenWorking, which is the SPINNER state — and the spinner is true for
        // a MIRRORED turn too (inFlight). Using the spinner for the "is this our
        // turn" gate made mirror growth look "ours" and skip the history append, so
        // a console-driven turn never updated the chat.
        var lastCurWorking = false
        var idleTicks = 0
        // Per-turn token accumulator: monotonic within a turn (keyed on the
        // protected turn-start), so a transient 0 from the probe never makes the
        // «↓ tokens» counter flicker; reset when the turn changes or work ends.
        var tokenTurnKey: Long? = null
        var tokenAccum = 0L
        while (true) {
            val bgSince = backgroundedSince()
            val bgFor = if (bgSince != null) System.currentTimeMillis() - bgSince else 0L
            val interval = pickPollInterval(
                isWorking = lastSeenWorking,
                bgForMs = bgFor,
                idleTicks = idleTicks,
            )
            delay(interval)
            val curState = s.state.value
            if (curState is SessionState.Failed || curState is SessionState.Closed) return
            val curWorking = curState is SessionState.Working
            val probe = statSizeAndAgentAlive(s, path, sessionId)
            val size = probe.size
            val inFlight = probe.inFlight
            val turnStart = probe.turnStartMs
            val thinking = probe.thinking
            if (size == null) {
                // Transport hiccup — DON'T flip the spinner; keep prior state.
                idleTicks++
                continue
            }
            // Mid-poll compact detection — same merge-not-wipe policy as the catch-up pass.
            if (size < lastOffset) {
                android.util.Log.w(
                    "SshAi-Tail",
                    "compact mid-poll sid=${sessionId.take(8)} cachedOffset=$lastOffset serverSize=$size — merging cache only",
                )
                val serverFull = fetchTail(s, path, 0L) ?: ByteArray(0)
                val merged = cache.mergeServer(sessionId, serverFull)
                if (merged != null) cache.save(sessionId, merged) // null = local too large to merge; keep file
                lastOffset = size
                idleTicks = 0
                lastSeenWorking = curWorking
                lastCurWorking = curWorking
                _remoteFileOpen.value = curWorking
                continue
            }
            val grew = size > lastOffset
            var sawTerminal = false
            // Did the growth carry actual CONVERSATIONAL content (assistant text /
            // tool / user), or just bookkeeping (a post-turn `summary`/recap, a
            // snapshot)? A recap event arrives SECONDS AFTER the turn stops and
            // grows the file — without this gate it re-lit the spinner.
            var grewConversational = false
            if (grew) {
                val bytes = fetchTail(s, path, lastOffset)
                if (bytes != null && bytes.isNotEmpty()) {
                    val safe = trimToLastNewline(bytes)
                    if (safe.isNotEmpty()) {
                        val parsed = parseJsonl(safe, agent)
                        grewConversational = parsed.any {
                            it is AgentMessage.AssistantText || it is AgentMessage.ToolUse ||
                                it is AgentMessage.ToolResult || it is AgentMessage.UserText
                        }
                        // "Ours" = WE are driving this turn (app-driven; the stream
                        // collector already appends, so the poll must not double-add).
                        // Keyed on curWorking + one grace tick (lastCurWorking), NOT
                        // the spinner — a MIRRORED turn must always append here.
                        val isOurs = curWorking || lastCurWorking
                        if (!isOurs) {
                            val added = appendDeduped(s, parsed)
                            if (added > 0) {
                                _remoteActive.value = System.currentTimeMillis()
                            }
                        }
                        // TURN-TERMINAL = a `result` OR an `error` event in the
                        // grown bytes. The model-unavailable / overloaded notices
                        // parse to AgentMessage.Error (NOT Result), so keying only
                        // on Result missed them → the post-turn growth (our own
                        // result landing) re-lit the spinner one tick later, then
                        // it dropped → the flicker (stop appears/disappears/
                        // appears, user 2026-06-13).
                        sawTerminal = parsed.any {
                            it is AgentMessage.Result || it is AgentMessage.Error
                        }
                        cache.append(sessionId, safe)
                        lastOffset += safe.size.toLong()
                    }
                }
                idleTicks = 0  // growth → poll fast
            } else {
                idleTicks++
            }
            // WORKING = our own in-flight turn (curWorking — authoritative
            // for app-driven persistent/one-shot turns) OR the session file's
            // LAST event says a turn is in flight ([inFlight] from the last
            // event type — survives the long silent THINK phase that growth
            // can't see). `grew &&!sawTerminal` is kept as a supplement for
            // the streaming phase. This is what makes the spinner + fast poll
            // track an external console agent through its 3-minute think, and
            // stop the instant it writes its final assistant reply.
            val working = curWorking || inFlight || (grew && grewConversational && !sawTerminal)
            // Keep polling fast while a turn is in flight, even with no growth
            // (the think phase), so we notice the reply within ~5 s.
            if (inFlight) idleTicks = 0
            _remoteFileOpen.value = working
            // Turn-start for the working timer: a fresh user-event timestamp when
            // we have one, else KEEP the prior value through a `tool` phase (so the
            // clock doesn't reset each Bash); cleared when work ends.
            _remoteTurnStartMs.value = if (working) (turnStart ?: _remoteTurnStartMs.value) else null
            // Effort suffix shows only while actually thinking, not mid-tool.
            _remoteThinking.value = working && thinking
            // Tokens: monotonic per turn. Keyed on the PROTECTED turn-start (which
            // holds steady even when a long tool chain evicts the prompt from the
            // jq window), so within one turn the counter only ever climbs and never
            // snaps to 0; it resets on a new turn or when work ends (audit).
            if (!working) {
                tokenTurnKey = null
                tokenAccum = 0L
            } else {
                val key = _remoteTurnStartMs.value
                if (key != tokenTurnKey) { tokenTurnKey = key; tokenAccum = probe.tokens }
                else tokenAccum = maxOf(tokenAccum, probe.tokens)
            }
            _remoteTokens.value = tokenAccum
            // Thinking but the FILE'S BEEN FROZEN too long ⇒ almost certainly
            // blocked on a console-side prompt (AskUserQuestion / permission — held
            // in RAM, never on disk). Tell the user to answer on the server instead
            // of faking a spinner. Frozen duration is computed on the SERVER
            // (date +%s − mtime) so phone↔server clock skew can't trip it early or
            // suppress it (audit, 2026-06-14). Gated on `thinking` so a running tool
            // (which keeps writing → frozen≈0) never trips it.
            _remoteWaitingForInput.value = working && thinking &&
                probe.frozenForMs != null && probe.frozenForMs > STALL_FOR_INPUT_MS
            lastSeenWorking = working
            lastCurWorking = curWorking
        }
    }

    /**
     * Compute the next poll delay based on what's actually happening:
     *   • turn in flight (`isWorking`) → fast 5 s
     *   • backgrounded ≥ 5 min        → 60 s
     *   • backgrounded < 5 min        → 30 s
     *   • foreground + idle ticks     → gentle back-off, capped at 2× (10 s)
     *     so a session driven from the console/another device is mirrored
     *     promptly — the old 6× (30 s) foreground tier was the 30-40 s lag
     *     before the spinner + new text showed up (user, 2026-06-13).
     */
    fun pickPollInterval(isWorking: Boolean, bgForMs: Long, idleTicks: Int): Long {
        val dataSaver = SilentlyTry.loggedOrElse("SshAi-Chat", "read data saver pref", false) {
            runBlocking { ServiceLocator.preferences.dataSaverEnabled.first() }
        }
        val k = if (dataSaver) 6L else 1L
        return when {
            isWorking -> POLL_INTERVAL_MS * (if (dataSaver) 3L else 1L)
            bgForMs >= BG_DEEP_AFTER_MS -> POLL_INTERVAL_BG_DEEP_MS * k
            bgForMs > 0 -> POLL_INTERVAL_BACKGROUND_MS * k
            // Foreground idle: cap at 2× (≈10 s). NEVER the old 30 s — a
            // foreground chat must catch external growth within ~10 s.
            idleTicks >= 3 -> POLL_INTERVAL_MS * 2 * k
            else -> POLL_INTERVAL_MS * k
        }
    }

    /**
     * Append [incoming] to the session's history, skipping any whose id is already
     * there. Returns the count actually added.
     */
    fun appendDeduped(s: AgentSession, incoming: List<AgentMessage>): Int {
        if (incoming.isEmpty()) return 0
        val hist = s.history.value
        val existing = hist.asSequence().map { it.id }.toHashSet()
        // Trimmed bodies of user prompts ALREADY shown (the optimistic copy added
        // in send()). Drop a JSONL echo of one of them even if the 60s
        // wasRecentlySent window has lapsed — that window expires during a slow
        // reconnect (key prompt + delay), which is exactly when showed up TWICE.
        // Legit repeats stay correct: every send() adds its OWN optimistic copy
        // first, so the on-screen count always matches the number of sends.
        val shownUserTexts = hist.asSequence()
            .filterIsInstance<AgentMessage.UserText>()
            .map { it.text.trim() }
            .toHashSet()
        // Content signatures of rows already shown whose ID is NOT stable across the
        // two emission paths. The SAME logical AskUserQuestion is tagged
        // "ask-<reqId>" when the live persistent stream renders it (answerable card)
        // and "<toolu_…>" when the tail-poll parses the CLI's JSONL for that same
        // turn (read-only card) — id-only dedup can't see they're the same, so the
        // poll re-appended a card the stream already showed → the identical-twice
        // card the user hit. Collapse by question CONTENT: the live answerable card
        // wins (it's already in history), the file's read-only duplicate is dropped.
        // AssistantText/ToolUse are deliberately NOT here — their ids
        // (message.id#block / toolu_) ARE stable across both paths, so id-dedup
        // already catches them and content-dedup would risk dropping a legitimately
        // repeated reply or fighting the streaming upsert.
        val shownSig = hist.asSequence().mapNotNull(::contentDedupSig).toHashSet()
        val fresh = incoming.filter {
            if (it.id in existing) return@filter false
            if (it is AgentMessage.UserText)
                return@filter !(s.wasRecentlySent(it.text) || it.text.trim() in shownUserTexts)
            val sig = contentDedupSig(it)
            sig == null || sig !in shownSig
        }
        if (fresh.isNotEmpty()) s.appendMessages(fresh)
        return fresh.size
    }

    /** Stable content signature for the one row type whose id differs between the
     *  live-stream and file-mirror paths (AskUserQuestion: "ask-<reqId>" vs the
     *  file's "<toolu_…>"). Everything else dedups by id (ids are stable across both
     *  paths there) → null. Keyed on the question/header/option text so the live and
     *  mirrored copies of the SAME question collapse regardless of which id made them. */
    private fun contentDedupSig(m: AgentMessage): String? = when (m) {
        is AgentMessage.AskUserQuestion ->
            "q|" + m.questions.joinToString("|##|") { q ->
                q.header + "|>|" + q.question + "|>|" +
                    q.options.joinToString("|.|") { it.label }
            }
        else -> null
    }

    suspend fun statSize(s: AgentSession, path: String): Long? {
        val inner = "stat -c %s ${shQuote(path)} 2>/dev/null || stat -f %z ${shQuote(path)} 2>/dev/null"
        val out = s.execOnLive("bash -lc " + shQuote(inner)) ?: return null
        return out.trim().lineSequence().firstOrNull { it.isNotBlank() }?.toLongOrNull()
    }

    /**
     * Returns (file size, turn-in-flight). One SSH exec per tick: `stat` for
     * size + the LAST event's head for in-flight detection.
     *
     * IN-FLIGHT (a turn is being worked on RIGHT NOW) is read from the
     * session file's LAST event — NOT from growth/pgrep, both of which fail
     * during a long think: the file is silent while the model reasons (no
     * writes for minutes), and pgrep can't tell our idle persistent process
     * from a working one (verified on the server, 2026-06-13). The file's
     * last event IS the truth: `"type":"user"` = the turn is awaiting the
     * assistant's reply (the model is thinking) → working;
     * `"type":"assistant"` carrying a `tool_use` = a tool is running →
     * working; a plain assistant message / result = the turn is done.
     */
    /** One poll's read of the session file's tail state. */
    data class PollProbe(
        /** File size in bytes, null on a transport hiccup. */
        val size: Long?,
        /** A turn is in flight (model thinking OR a tool is running). */
        val inFlight: Boolean,
        /** Turn-start epoch ms — the last user PROMPT (not a tool_result), so the
         *  timer spans the whole turn like the CLI, not since the last tool. */
        val turnStartMs: Long?,
        /** THINKING phase specifically (last event is `user` — model generating),
         *  as opposed to a tool running. Gates the «with X effort» suffix. */
        val thinking: Boolean,
        /** Cumulative output tokens THIS turn — sum of distinct assistant
         *  messages' output_tokens since the turn-start (matches the CLI's
         *  «↓ N tokens» for a mirrored session, where there's no live feed). */
        val tokens: Long,
        /** File last-modified epoch ms (server clock). Kept for logging. */
        val mtimeMs: Long?,
        /** How long the file has been FROZEN, in ms, computed ENTIRELY on the
         *  server (`date +%s` − mtime) so phone↔server clock skew can't make the
         *  "waiting for a console answer" hint fire instantly or never (audit,
         *  2026-06-14). null when the stat/date read failed. */
        val frozenForMs: Long?,
    )

    suspend fun statSizeAndAgentAlive(
        s: AgentSession,
        path: String,
        sessionId: String,
    ): PollProbe {
        val q = shQuote(path)
        // jq IS on this server. Emit ONE compact TSV record per conversational line
        // (top-level type — never a nested one): type, isMeta, is-tool-result,
        // is-tool-use, output_tokens, message-id, timestamp. From these we derive
        // EVERYTHING the status row needs, accurately, for a MIRRORED session: •
        // inFlight — last event is a `user` (awaiting assistant) or an `assistant`
        // with a tool_use (a tool is running, file frozen). • thinking — last event is
        // a `user` (model about to generate). • turnStart — the last user PROMPT (NOT
        // a tool_result), so the timer spans the whole turn like the CLI, not since
        // the last tool. • tokens — sum of DISTINCT assistant messages' output_tokens
        // since the turn-start (dedup streaming partials by msg id), matching the
        // CLI's «↓ N tokens». `stat -c %s,%Y` also gives mtime → "frozen" duration for
        // the wait hint.
        val rec = "select(.type==\"user\" or .type==\"assistant\") | [.type, " +
            "((.isMeta // false)|tostring), " +
            "(((.message.content // [])|if type==\"array\" then any(.[]?; .type==\"tool_result\") else false end)|tostring), " +
            "(((.message.content // [])|if type==\"array\" then any(.[]?; .type==\"tool_use\") else false end)|tostring), " +
            "((.message.usage.output_tokens // 0)|tostring), (.message.id // \"\"), (.timestamp // \"\")] | @tsv"
        // `date +%s` (server clock) on its own line right after stat → frozen
        // duration is computed server-side, never mixing the phone clock (audit).
        // tail -n 400 (was 200): a long tool chain emits 2 lines per round, and at
        // 200 lines the turn's user PROMPT scrolled out of the window → turn-start
        // lost and the «↓ tokens» counter collapsed to 0 mid-turn (audit, 2026-06-14).
        val inner = "stat -c %s,%Y $q 2>/dev/null || stat -f %z,%m $q 2>/dev/null; date +%s; echo ---; " +
            "tail -n 400 $q 2>/dev/null | jq -rc '$rec' 2>/dev/null"
        val out = s.execOnLive("bash -lc " + shQuote(inner)) ?: return PollProbe(null, false, null, false, 0L, null, null)
        val (statPart, recPart) = out.split("---", limit = 2).let {
            it[0] to (it.getOrNull(1).orEmpty())
        }
        val statLines = statPart.lineSequence().filter { it.isNotBlank() }.toList()
        val statFields = statLines.firstOrNull()?.split(',')
        val size = statFields?.getOrNull(0)?.toLongOrNull()
        val mtimeSec = statFields?.getOrNull(1)?.toLongOrNull()
        val mtimeMs = mtimeSec?.let { it * 1000 }
        val serverNowSec = statLines.getOrNull(1)?.trim()?.toLongOrNull()
        // Both ends are the server's own clock → skew-proof, and still correct on
        // open (real frozen time, not "since the app noticed").
        val frozenForMs = if (mtimeSec != null && serverNowSec != null)
            ((serverNowSec - mtimeSec) * 1000).coerceAtLeast(0L) else null
        // Field indices: 0 type, 1 isMeta, 2 tool_result, 3 tool_use, 4 out_tokens, 5 id, 6 ts.
        val recs = recPart.lineSequence().map { it.split('\t') }.filter { it.size >= 7 }.toList()
        if (recs.isEmpty()) return PollProbe(size, false, null, false, 0L, mtimeMs, frozenForMs)
        val last = recs.last()
        val inFlight = last[0] == "user" || (last[0] == "assistant" && last[3] == "true")
        val thinking = last[0] == "user"
        // Turn start = the most recent user PROMPT (tool_result excluded; a /loop
        // re-prompt counts even though it's isMeta).
        val startIdx = recs.indexOfLast { it[0] == "user" && it[2] != "true" }
        val turnStartMs = if (startIdx >= 0) recs[startIdx][6].takeIf { it.isNotBlank() }?.let { ts ->
            ai.eight24family.conch.util.SilentlyTry.logged("SshAi-Tail", "parse turn-start ts") {
                java.time.Instant.parse(ts).toEpochMilli()
            }
        } else null
        // Cumulative output tokens this turn: assistant messages after the turn
        // start, deduped by message id (streaming partials repeat the id), max per
        // id. When the prompt scrolled out of the window (startIdx<0) sum the whole
        // window — it's all one giant turn anyway — so the count keeps climbing
        // instead of snapping to 0. Blank-id records key on a UNIQUE index, never
        // the timestamp, so two distinct id-less records can't merge (audit).
        val tokenStart = if (startIdx >= 0) startIdx + 1 else 0
        val tokens = recs.drop(tokenStart).filter { it[0] == "assistant" }
            .withIndex()
            .groupBy { (i, r) -> r[5].ifBlank { "##idx$i" } }
            .values.sumOf { g -> g.maxOf { (_, r) -> r[4].toLongOrNull() ?: 0L } }
        ai.eight24family.conch.util.Logx.d("SshAi-Tail") {
            "statSize=$size last=${last[0]} inFlight=$inFlight thinking=$thinking turnStartMs=$turnStartMs tokens=$tokens frozenMs=$frozenForMs sid=${sessionId.take(8)}"
        }
        return PollProbe(size, inFlight, turnStartMs, thinking, tokens, mtimeMs, frozenForMs)
    }

    suspend fun fetchTail(s: AgentSession, path: String, fromOffset: Long): ByteArray? {
        val inner = if (fromOffset <= 0L) {
            "cat ${shQuote(path)}"
        } else {
            "tail -c +${fromOffset + 1} ${shQuote(path)}"
        }
        val out = s.execOnLive("bash -lc " + shQuote(inner)) ?: return null
        return out.toByteArray(Charsets.UTF_8)
    }

    fun trimToLastNewline(bytes: ByteArray): ByteArray =
        ai.eight24family.conch.util.JsonlUtils.trimToLastNewline(bytes)

    /** Buffer-flavoured overload — streams the (mmap) `ByteBuffer`
     *  line-by-line through UTF-8 without ever materialising the whole
     *  session as one `String`. */
    fun parseJsonl(buffer: java.nio.ByteBuffer, agent: Agent): List<AgentMessage> {
        if (!buffer.hasRemaining()) return emptyList()
        // Per-line decode, NOT a whole-buffer Charset.decode(). The latter
        // allocates a CharBuffer sized to the entire session (UTF-8 → up to
        // 1 char/byte → 2 bytes/char), so a ~28 MB chat forced a ~57 MB
        // transient that OOM-killed the app the instant such a session was
        // opened — once "load all" started caching big sessions (crash at
        // this line, 2026-05-29). forEachLine bounds the allocation to one
        // line.
        val spec = AgentSpecRegistry[agent]
        val out = mutableListOf<AgentMessage>()
        var turnSeq = 0
        ai.eight24family.conch.util.JsonlUtils.forEachLine(
            buffer,
            onOversize = { head, total ->
                // A single >16 MB line (e.g. a 475 MB runaway tool dump) can't
                // be parsed/rendered whole — show it as a truncated tool output
                // in its place so the session stays complete and nothing
                // silently vanishes. Universal across agents.
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
                spec.parseStreamLine(line, turnTag).forEach { out += it }
            }
        }
        return out.distinctBy { it.id }
    }

    fun parseJsonl(bytes: ByteArray, agent: Agent): List<AgentMessage> {
        if (bytes.isEmpty()) return emptyList()
        return parseJsonlText(String(bytes, Charsets.UTF_8), agent)
    }

    /**
     * Shared parse path. `distinctBy { it.id }` defends against Claude's JSONL legitimately
     * repeating `toolu_xxx` ids (LazyColumn crashes on duplicate keys). Turn-tag bump on
     * `turn.started` / `thread.started` keeps codex's per-turn item ids unique.
     */
    fun parseJsonlText(text: String, agent: Agent): List<AgentMessage> {
        if (text.isEmpty()) return emptyList()
        val spec = AgentSpecRegistry[agent]
        val out = mutableListOf<AgentMessage>()
        var turnSeq = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (line.contains("\"type\":\"turn.started\"") ||
                line.contains("\"type\":\"thread.started\"")
            ) {
                turnSeq++
            }
            val turnTag = if (turnSeq == 0) "" else "t${turnSeq}_"
            spec.parseStreamLine(line, turnTag).forEach { out += it }
        }
        return out.distinctBy { it.id }
    }

    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    companion object {
        /** Cadence of the remote-tail poller. 5 s feels alive without churning data. */
        const val POLL_INTERVAL_MS: Long = 5_000L
        /** Background poll cadence — chat-might-come-back-soon. */
        const val POLL_INTERVAL_BACKGROUND_MS: Long = 30_000L
        /** Deep-background poll cadence — chat-isn't-coming-back-soon. */
        const val POLL_INTERVAL_BG_DEEP_MS: Long = 60_000L
        const val BG_DEEP_AFTER_MS: Long = 5 * 60_000L
        /** "Thinking" with the file frozen this long ⇒ probably blocked on a
         *  console-side prompt (AskUserQuestion in RAM). 3 min is well past any
         *  real think; the hint is soft ("possibly waiting"), so a rare long
         *  genuine think only shows a benign nudge. */
        const val STALL_FOR_INPUT_MS: Long = 3 * 60_000L
    }
}
