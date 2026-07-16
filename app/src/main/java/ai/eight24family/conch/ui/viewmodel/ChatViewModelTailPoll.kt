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
    /** sessionId → epoch-ms of the last persistent-stream collector emission
     *  (from the reconnect coord). Lets the poll tell whether the stream is
     *  ACTIVELY feeding this turn: if it's been silent (subagent research, or a
     *  dropped stream), the poll mirrors the file growth itself instead of
     *  leaving the chat frozen. Defaults to "never fed" for tests. */
    private val streamLastFedMs: (sessionId: String) -> Long? = { null },
    /** True iff the CURRENTLY-displayed session's persistent stream holds a live
     *  control_request (AskUserQuestion / permission) blocked on the user. This is
     *  AUTHORITATIVE for WAITING-FOR-USER — the control never reaches the JSONL, so
     *  the file can't see it. Default false (tests / non-app-driven). */
    private val pendingControl: () -> Boolean = { false },
) {
    /** How long the stream may be silent before the poll takes over mirroring
     *  file growth during our own turn. Short enough to surface a research
     *  continuation fast; longer than a normal inter-event stream gap so a live
     *  fast turn stays stream-driven (no double-add). */
    private val STREAM_LAG_GRACE_MS = 8_000L
    /** Grace after a DEFINITIVE terminal completion (turnComplete) appears in the
     *  file while our live state is still Working, before we declare the live turn
     *  STUCK and reconcile it (the reader wedged on a loopback tool and missed
     *  `result`). Sized for the normal end_turn→`result` gap (~0.5–2s) so a healthy
     *  turn completes via the stream first; short enough a wedged turn clears in
     *  seconds, not the minutes the inactivity backstop would take. Safe to keep
     *  small now that the gate is turnComplete (a real terminal stop_reason), not
     *  the heuristic `!inFlight` — a running turn never trips it. */
    private val RECONCILE_STUCK_MS = 6_000L
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
        val pre = statSizeAndAgentAlive(s, agent, path, sessionId)
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
            if (preSize > BIG_FILE_STREAM_BYTES) {
                // Giant compacted file — STREAM it into the cache (RAM-flat) instead
                // of reading the whole thing into a String (the OOM crash). The
                // server is the post-compaction truth; live history is left intact
                // (same as the merge path), the offset jumps to the new size.
                val written = streamFullToCache(s, sessionId, path)
                lastOffset = written ?: preSize
                android.util.Log.i(
                    "SshAi-Tail",
                    "compact STREAMED sid=${sessionId.take(8)} bytes=$written newOffset=$lastOffset",
                )
            } else {
                val serverFull = fetchTail(s, path, 0L) ?: ByteArray(0)
                // Same benign-shrink guard as the mid-poll branch: a rewrite that
                // kept every id (our sdk-cli→cli entrypoint fix) must re-adopt the
                // server verbatim, NOT run the lossy/offset-desyncing mergeServer.
                if (serverFull.isNotEmpty() && cache.serverContainsAllLocal(sessionId, serverFull)) {
                    cache.save(sessionId, serverFull)
                    lastOffset = serverFull.size.toLong()
                    android.util.Log.i(
                        "SshAi-Tail",
                        "shrink open sid=${sessionId.take(8)} — benign rewrite, re-adopted server verbatim (${serverFull.size}B)",
                    )
                } else {
                    val merged = cache.mergeServer(sessionId, serverFull)
                    if (merged != null) cache.save(sessionId, merged) // null = local too large to merge; keep file
                    lastOffset = preSize.toLong()
                    android.util.Log.i(
                        "SshAi-Tail",
                        "compact merged sid=${sessionId.take(8)} mergedBytes=${merged?.size ?: -1} newOffset=$lastOffset",
                    )
                }
            }
        }

        // ── Catch-up pass ──
        // Giant file, from-scratch (no/stale cache) → STREAM it into the cache
        // (RAM-flat) and load history from the mmap'd cache buffer instead of the
        // String path that OOM'd. Only the full from-zero read; incremental growth
        // stays on the cheap inline path below.
        if (lastOffset == 0L && s.history.value.isEmpty() && (preSize ?: 0L) > BIG_FILE_STREAM_BYTES) {
            val written = streamFullToCache(s, sessionId, path)
            if (written != null && written > 0) {
                cache.load(sessionId)?.use { snap -> s.loadHistory(parseJsonl(snap.buffer, agent)) }
                lastOffset = written
                android.util.Log.i(
                    "SshAi-Tail",
                    "catch-up STREAMED sid=${sessionId.take(8)} bytes=$written history=${s.history.value.size}",
                )
            }
        } else {
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
            val serverSize = statSizeAndAgentAlive(s, agent, path, sessionId).size
            android.util.Log.w(
                "SshAi-Tail",
                "catch-up EMPTY sid=${sessionId.take(8)} lastOffset=$lastOffset serverSize=$serverSize " +
                    (if (serverSize != null && serverSize > lastOffset)
                        "STALE: server has ${serverSize - lastOffset}B unread (fetchTail failed?)"
                    else
                        "cache up-to-date (or server smaller)")
            )
        }
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
            val probe = statSizeAndAgentAlive(s, agent, path, sessionId)
            val size = probe.size
            val inFlight = probe.inFlight
            val turnStart = probe.turnStartMs
            val thinking = probe.thinking
            if (size == null) {
                // Transport hiccup — DON'T flip the spinner; keep prior state.
                idleTicks++
                continue
            }
            // Mid-poll SHRINK. A file that got SMALLER is EITHER a real Claude
            // auto-compaction (old turns dropped) OR a benign rewrite that kept all
            // content — most often our OWN listSessionsScript flipping
            // "entrypoint":"sdk-cli"→"cli" (−4 bytes/tag). Blindly running the
            // keep-local mergeServer on the benign case is what corrupted the chat:
            // it dedups on the BARE message.id (collapsing a turn's thinking/
            // tool_use lines), emits local-order-first, and desyncs cache length
            // from the server offset — surfacing later as "first message at the
            // bottom" + the topbar model flipping to the first map entry (Sonnet).
            // So distinguish by id-set containment: server still has every local id
            // ⇒ NOT a compaction ⇒ re-adopt server verbatim (authoritative +
            // complete, cache bytes == server bytes → offset invariant restored).
            if (size < lastOffset) {
                val serverFull = fetchTail(s, path, 0L) ?: ByteArray(0)
                if (serverFull.isNotEmpty() && cache.serverContainsAllLocal(sessionId, serverFull)) {
                    android.util.Log.i(
                        "SshAi-Tail",
                        "shrink mid-poll sid=${sessionId.take(8)} $lastOffset→$size — benign rewrite, re-adopting server verbatim",
                    )
                    cache.save(sessionId, serverFull)
                    lastOffset = serverFull.size.toLong()
                } else {
                    android.util.Log.w(
                        "SshAi-Tail",
                        "compact mid-poll sid=${sessionId.take(8)} cachedOffset=$lastOffset serverSize=$size — real compaction, merging keep-local",
                    )
                    val merged = cache.mergeServer(sessionId, serverFull)
                    if (merged != null) cache.save(sessionId, merged) // null = local too large to merge; keep file
                    lastOffset = size
                }
                idleTicks = 0
                lastSeenWorking = curWorking
                lastCurWorking = curWorking
                _remoteFileOpen.value = curWorking
                continue
            }
            val grew = size > lastOffset
            if (grew) {
                val bytes = fetchTail(s, path, lastOffset)
                if (bytes != null && bytes.isNotEmpty()) {
                    val safe = trimToLastNewline(bytes)
                    if (safe.isNotEmpty()) {
                        val parsed = parseJsonl(safe, agent)
                        // Mirror file growth into history. Growth during OUR own
                        // app-driven turn is normally left to the persistent stream's
                        // collector (don't double-add). BUT during a long research
                        // turn the stream goes SILENT (Agent/Task/Workflow subagents
                        // run out-of-band) while the JSONL keeps growing, and after a
                        // mid-research disconnect those bytes land in the file ONLY.
                        // So if the stream's been silent for a beat, mirror here too —
                        // appendDeduped's id/content dedup is the double-add backstop.
                        val genuinelyExternal = !(curWorking || lastCurWorking)
                        val streamSilent = streamLastFedMs(sessionId)
                            ?.let { System.currentTimeMillis() - it >= STREAM_LAG_GRACE_MS } ?: true
                        if (genuinelyExternal || streamSilent) {
                            val added = appendDeduped(s, parsed)
                            // "● remote session active" banner is for TRULY external
                            // growth only — not our own (stream-silent) turn.
                            if (added > 0 && genuinelyExternal) {
                                _remoteActive.value = System.currentTimeMillis()
                            }
                        }
                        cache.append(sessionId, safe)
                        lastOffset += safe.size.toLong()
                    }
                }
                idleTicks = 0  // growth → poll fast
            } else {
                idleTicks++
            }
            // AUTHORITATIVE waiting signal (no timeout). A live control_request the
            // agent raised — AskUserQuestion or a permission prompt — is held in the
            // persistent stream's RAM (pendingControls) and is NEVER written to the
            // JSONL, so the file-derived "working" is WRONG here: the agent is
            // BLOCKED on the human, not computing. This wins over fileWorking. The
            // answerable card is already in chat history; we just stop the spinner.
            val pendingCtl = pendingControl()
            // RECONCILE a STUCK live turn: our state says Working (curWorking) but
            // the authoritative file shows a DEFINITIVE terminal completion
            // (turnComplete — a real end_turn/stop_sequence/max_tokens) that has
            // been frozen past a short grace — the persistent reader wedged (a
            // `conch-bridge` loopback tool re-enters this same app and stalls stdout
            // delivery) and never saw the `result` event, so the live turn never
            // completed and the spinner span forever. Force it done + relaunch a
            // clean reader. Gated on turnComplete, NOT `!inFlight`: inFlight also
            // goes false on the 12-min stale-mid-stream fallback, and a long SILENT
            // research turn (subagents, file frozen 15+ min) would then be torn down
            // mid-flight, losing all output. turnComplete only trips on a real
            // terminal stop_reason, which a running turn never has. Skipped while a
            // control_request / file approval is pending (the turn is legitimately
            // blocked on the user, invisible to the file).
            val liveStuck = curWorking && probe.turnComplete && !pendingCtl &&
                !probe.waitingForUser &&
                probe.frozenForMs != null && probe.frozenForMs >= RECONCILE_STUCK_MS
            if (liveStuck) {
                android.util.Log.w(
                    "SshAi-Tail",
                    "live turn stuck: file done + frozen ${probe.frozenForMs}ms but state=Working — reconciling",
                )
                s.reconcileStuckTurn()
            }
            // WORKING is DEFINITIVE: our own in-flight turn (curWorking) OR the
            // per-agent [inFlight] read from the file tail (Claude stop_reason /
            // Codex task_started·complete / Gemini last record). The old
            // `(grew && grewConversational && !sawTerminal)` supplement was DROPPED:
            // it re-lit "working" for one ~5s tick when a post-turn poll consumed the
            // final assistant text BEFORE the terminal result landed in the same
            // window — the "agent stopped, then faked work ~5s, then stopped" ghost
            // (user, 2026-06-28). inFlight already covers the streaming phase, so the
            // supplement was redundant AND the source of the flicker. A reconciled
            // stuck turn also drops curWorking THIS tick so the spinner clears at once.
            val fileWorking = (curWorking && !liveStuck) || inFlight
            val working = fileWorking && !pendingCtl
            // Keep polling fast while a turn is in flight OR a question is pending
            // (the user might answer on the PC), so we notice the change within ~5s.
            if (inFlight || pendingCtl) idleTicks = 0
            _remoteFileOpen.value = working
            // Turn-start for the working timer: a fresh user-event timestamp when
            // we have one, else KEEP the prior value through a `tool` phase (so the
            // clock doesn't reset each Bash); cleared when work ends OR waiting.
            _remoteTurnStartMs.value = if (working) (turnStart ?: _remoteTurnStartMs.value) else null
            // Effort suffix shows only while actually thinking, not mid-tool/waiting.
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
            // AUTHORITATIVE when WE hold the pending control (app-driven, zero
            // timeout). The soft frozen heuristic is ONLY a fallback for a MIRRORED
            // session whose question lives in ANOTHER client's RAM (we can't see it,
            // so a long frozen think is the best weak hint) — never used when the
            // real signal is available.
            _remoteWaitingForInput.value = pendingCtl || probe.waitingForUser ||
                (!pendingCtl && fileWorking && thinking &&
                    probe.frozenForMs != null && probe.frozenForMs > STALL_FOR_INPUT_MS)
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
            // Dedup a user prompt's JSONL echo against the OPTIMISTIC copy that
            // ALREADY made it into history (shownUserTexts) — NOT against
            // `wasRecentlySent`. legit repeats keep their N optimistic copies) AND
            // restores the bubble whenever the optimistic copy is missing.
            if (it is AgentMessage.UserText)
                return@filter it.text.trim() !in shownUserTexts
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
        /** The turn is BLOCKED on a human answer that the session FILE records
         *  (a file-visible approval/question). Claude's live AskUserQuestion is
         *  NOT here — it never hits the file (detected via pendingControls). Set
         *  per-agent by [ai.eight24family.conch.agent.spec.AgentCliSpec.inferTurnState];
         *  default false. */
        val waitingForUser: Boolean = false,
        /** The file shows a DEFINITIVE terminal completion (Claude assistant
         *  stop_reason ∈ terminal). The ONLY safe gate for force-completing a stuck
         *  live turn — unlike [inFlight], it never flips on the 12-min stale
         *  fallback, so a long silent research turn is never torn down. Default false. */
        val turnComplete: Boolean = false,
    )

    suspend fun statSizeAndAgentAlive(
        s: AgentSession,
        agent: Agent,
        path: String,
        sessionId: String,
    ): PollProbe {
        val q = shQuote(path)
        // ── PER-AGENT turn-state ──
        // Each CLI's JSONL is shaped on a totally different axis — Claude carries
        // `stop_reason` on assistant events; Codex brackets turns with
        // `event_msg.payload.type` task_started/task_complete (NO stop_reason);
        // Gemini interleaves `$set` snapshots with top-level `user`/`gemini`
        // message records. A Claude-shaped probe matches ZERO lines in a Codex or
        // Gemini rollout. So the projection (`turnStateRecordJq`) AND the verdict
        // (`inferTurnState`) both live in the agent's spec; here we just run the
        // jq the spec hands us and feed the records back to it. `stat -c %s,%Y`
        // gives size+mtime, `date +%s` is the server clock → skew-proof "frozen"
        // duration. tail -n 400: a long tool chain emits ~2 lines/round; 200 lost
        // the turn-start at scale (audit 2026-06-14).
        val spec = AgentSpecRegistry[agent]
        val recJq = spec.turnStateRecordJq
        val inner = "stat -c %s,%Y $q 2>/dev/null || stat -f %z,%m $q 2>/dev/null; date +%s; echo ---;" +
            (if (recJq != null) " tail -n 400 $q 2>/dev/null | jq -rc '$recJq' 2>/dev/null" else "")
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
        // Generic split: every spec's jq emits tab-separated records (oldest→
        // newest), text fields already tab/newline-scrubbed. The spec owns the
        // field layout and the decision.
        val records = recPart.lineSequence().map { it.split('\t') }
            .filter { it.isNotEmpty() && it[0].isNotBlank() }.toList()
        val sig = spec.inferTurnState(records, frozenForMs)
        ai.eight24family.conch.util.Logx.d("SshAi-Tail") {
            "statSize=$size agent=$agent inFlight=${sig.inFlight} thinking=${sig.thinking} " +
                "waiting=${sig.waitingForUser} turnStartMs=${sig.turnStartMs} tokens=${sig.tokens} " +
                "frozenMs=$frozenForMs recs=${records.size} sid=${sessionId.take(8)}"
        }
        return PollProbe(
            size = size,
            inFlight = sig.inFlight,
            turnStartMs = sig.turnStartMs,
            thinking = sig.thinking,
            tokens = sig.tokens,
            mtimeMs = mtimeMs,
            frozenForMs = frozenForMs,
            waitingForUser = sig.waitingForUser,
            turnComplete = sig.turnComplete,
        )
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

    /**
     * Stream the WHOLE remote file straight into the session's cache file over the
     * pooled SSH client — RAM stays flat regardless of size. [fetchTail]'s
     * `execOnLive` path buffers the whole `cat` output into a ByteArray + String
     * (~4× the file), which OOM-crashed the app the moment a giant rollout was
     * caught up / compact-merged (user, 2026-06-28: a 16 MB session that had spiked
     * far larger). Returns bytes written, or null when there's no live pooled
     * client (caller keeps the small-file String path). Mirrors GlobalPrefetcher's
     * streaming body fetch.
     */
    private suspend fun streamFullToCache(s: AgentSession, sessionId: String, path: String): Long? {
        val client = ServiceLocator.sshConnectionPool.peek(s.server.id) ?: return null
        val cmd = "bash -lc " + shQuote("cat ${shQuote(path)}")
        return SilentlyTry.loggedOrElse("SshAi-Tail", "stream full file to cache", null) {
            val sess = client.startSession()
            try {
                val proc = sess.exec(cmd)
                val n = ServiceLocator.historyCache.saveFromStream(sessionId, proc.inputStream)
                proc.join(120, java.util.concurrent.TimeUnit.SECONDS)
                n
            } finally {
                SilentlyTry.fired("SshAi-Tail", "close stream session") { sess.close() }
            }
        }
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
        /** Above this, a full catch-up / compact read STREAMS the file into the
         *  cache (RAM-flat) instead of materialising it as a String — the latter
         *  OOM-crashed on a giant rollout. Small files stay on the fast inline path. */
        const val BIG_FILE_STREAM_BYTES: Long = 4_000_000L
    }
}
