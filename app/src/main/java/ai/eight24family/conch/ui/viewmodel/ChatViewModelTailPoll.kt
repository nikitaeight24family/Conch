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
    private val RECONCILE_STUCK_MS = RECONCILE_STUCK_GRACE_MS
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
        // When the file first said "turn terminal" — our own fallback clock for
        // the stuck-turn reconcile when the server's mtime can't be read.
        var terminalSeenAtMs: Long? = null
        // Has the session file been WRITTEN since our turn began? The terminal
        // record of the PREVIOUS turn is still sitting at the end of the file
        // when we start a new one, and it has been frozen for however long the
        // chat was idle — so without this latch the very first poll tick after a
        // send satisfies every reconcile conjunct and force-completes a turn the
        // CLI has not even started answering. That was invisible while the remote
        // projection was returning nothing (turnComplete was permanently false);
        // projecting locally makes it reachable, so the gate has to exist.
        var sawGrowthThisTurn = false

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
        val preFrozenMs = pre.frozenForMs
        // Spinner at frame zero comes from OUR state only; the file's own verdict
        // needs the record window, which is seeded below once the cache is
        // caught up (a chat opened cold has nothing to project until then).
        _remoteFileOpen.value = s.state.value is SessionState.Working
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
                // Free local repair first — identical reasoning to the mid-poll
                // branch: our own sdk-cli→cli rewrite is deterministic, so replay
                // it on the cache instead of pulling the whole session down just
                // to re-adopt it. Exact size match only; anything else falls
                // through to the authoritative path below.
                val repairedOpen = cache.rewriteEntrypointTags(sessionId)
                if (repairedOpen != null && repairedOpen == preSize) {
                    lastOffset = preSize
                    android.util.Log.i(
                        "SshAi-Tail",
                        "shrink open sid=${sessionId.take(8)} — repaired locally, no transfer",
                    )
                } else {
                val serverFull = fetchTail(s, path, 0L) ?: ByteArray(0)
                // Same benign-shrink guard as the mid-poll branch: a rewrite that
                // kept every id (our sdk-cli→cli entrypoint fix) must re-adopt the
                // server verbatim, NOT run the lossy/offset-desyncing mergeServer.
                if (serverFull.isNotEmpty() && cache.serverContainsAllLocal(sessionId, serverFull)) {
                    // TRIM to a whole line before storing. `serverFull` is a raw
                    // cat of a file the CLI may be mid-write on, so its tail can
                    // be a partial line — and HistoryCache's contract is that
                    // saved bytes always end on a newline, because lastOffset is
                    // derived from the saved length. Storing it untrimmed makes
                    // the offset point into the middle of a record and the next
                    // append() glues the rest of that line onto itself.
                    val safeFull = trimToLastNewline(serverFull)
                    cache.save(sessionId, safeFull)
                    lastOffset = safeFull.size.toLong()
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
        }

        // ── Catch-up pass ── Giant file, from-scratch (no/stale cache) → STREAM
        // it into the cache (RAM-flat) and load history from the mmap'd cache
        // buffer instead of the String path that OOM'd. Only the full from-zero
        // read; incremental growth stays on the cheap inline path below. ⚠ MUST
        // FALL THROUGH ON FAILURE. streamFullToCache returns null on a missing
        // pooled client, a refused channel, or a gzip stream that never
        // materialises, and 0 on an empty read. The `else` used to hang off the
        // big-file predicate, so any of those simply SKIPPED the catch-up: no
        // history loaded, lastOffset still 0, chat opens EMPTY and looks dead.
        // Adding `| gzip -c` to that path made the failure modes strictly more
        // likely, which is what turned a latent hole into a visible one. Now a
        // failed stream degrades to the ordinary fetch instead of silently doing
        // nothing.
        var streamedOk = false
        if (lastOffset == 0L && s.history.value.isEmpty() && (preSize ?: 0L) > BIG_FILE_STREAM_BYTES) {
            val written = streamFullToCache(s, sessionId, path)
            if (written != null && written > 0) {
                cache.load(sessionId)?.use { snap -> s.loadHistory(parseJsonl(snap.buffer, agent)) }
                lastOffset = written
                streamedOk = true
                android.util.Log.i(
                    "SshAi-Tail",
                    "catch-up STREAMED sid=${sessionId.take(8)} bytes=$written history=${s.history.value.size}",
                )
            } else {
                android.util.Log.w(
                    "SshAi-Tail",
                    "catch-up STREAM FAILED sid=${sessionId.take(8)} written=$written — " +
                        "falling back to the plain fetch so the chat still paints",
                )
            }
        }
        if (!streamedOk) {
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

        // ── Turn-state record window (LOCAL projection) ──
        // The authoritative "is a turn running?" signal, derived on the phone from
        // the bytes we already downloaded — NOT from `jq` on the server.
        //
        // The remote jq path was a silent single point of failure: on a host where
        // jq is missing from the non-interactive `bash -lc` PATH, or built without
        // oniguruma so the program's `gsub` is an undefined function, jq prints
        // nothing and exits. Records came back empty, every signal read false —
        // including `turnComplete`, which the stuck-turn reconcile is gated on — so
        // the ONE safety net that clears a wedged spinner could never fire. Measured
        // on the user's own host: `recs=0` on every single tick for the whole
        // session while `stat` on the same command line parsed fine, and the
        // thinking indicator ran forever (2026-07-29).
        //
        // Kept INCREMENTAL on purpose: re-projecting 400 lines every 5 s costs
        // 7-80 ms per tick on a desktop (measured against real 40-64 MB sessions),
        // several times that on a phone. Growth is projected once, appended, and
        // the window trimmed — so a frozen file, which is exactly the stuck-turn
        // case, costs nothing at all.
        val spec = AgentSpecRegistry[agent]
        val recWindow = ArrayDeque<List<String>>()
        fun trimWindow() { while (recWindow.size > TURN_RECORD_WINDOW) recWindow.removeFirst() }
        fun reseedWindow() {
            recWindow.clear()
            recWindow.addAll(spec.projectTurnStateRecords(cache.tailLines(sessionId).asSequence()))
            trimWindow()
        }
        fun growWindow(newBytes: ByteArray) {
            if (newBytes.isEmpty()) return
            val lines = String(newBytes, Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
            recWindow.addAll(spec.projectTurnStateRecords(lines))
            trimWindow()
        }
        reseedWindow()
        // The file's OWN verdict, now that the window exists: catches a turn that
        // the CLI (or another device) started while this chat was closed, the
        // instant it opens — the same thing the old remote projection did on its
        // pre-probe, minus the round trip.
        val preSig = spec.inferTurnState(recWindow.toList(), preFrozenMs)
        android.util.Log.i(
            "SshAi-Tail",
            "turn-state window seeded sid=${sessionId.take(8)} agent=$agent records=${recWindow.size} " +
                "inFlight=${preSig.inFlight} complete=${preSig.turnComplete}",
        )
        // ⚠ IN FLIGHT NEEDS A HEARTBEAT, NOT A LEFTOVER. The window's verdict is
        // "the last record looks unfinished" — true forever for a turn whose
        // writer died: the process was killed, the app restarted, the work went
        // to the CLI's own daemon. Nothing then clears it, and the chat sat on
        // "Imagining… 39m55s" with the server showing no writes for five
        // minutes and the app's own poller not even running (user, 2026-08-04).
        // A turn that is genuinely running touches its file; one that has not
        // been touched in a minute is not running, whatever the last record
        // looks like.
        if (preSig.inFlight && (preFrozenMs ?: Long.MAX_VALUE) < STALE_TURN_MS) {
            _remoteFileOpen.value = true
        }
        _remoteTurnStartMs.value = preSig.turnStartMs
        _remoteThinking.value = preSig.thinking
        _remoteTokens.value = preSig.tokens

        // ── Poll loop ──
        var lastSeenWorking = false
        // Whether WE drove the turn last tick (s.state == Working). DISTINCT from
        // lastSeenWorking, which is the SPINNER state — and the spinner is true for
        // a MIRRORED turn too (inFlight). Using the spinner for the "is this our
        // turn" gate made mirror growth look "ours" and skip the history append, so
        // a console-driven turn never updated the chat.
        var lastCurWorking = false
        // TRUE when the value we last published came from a LIVE control in OUR
        // RAM. Such a banner must die with its control even if every stat below
        // fails — that is the 2026-07-29 stall, where the transport wedged for 30s
        // and the assignment at the bottom of this loop never ran again.
        var waitingFromControl = false
        // Did WE drive the turn that wrote the file's tail? A frozen "thinking" on
        // our own tail is our dead or killed process — never a question sitting in
        // another client's RAM.
        var fileTailIsOurs = false
        var idleTicks = 0
        // Consecutive stat failures (transport down) — drives the extra
        // backoff sleep in the size==null branch; reset on any success.
        var statFailStreak = 0
        // Per-turn token accumulator: monotonic within a turn (keyed on the
        // protected turn-start), so a transient 0 from the probe never makes the
        // «↓ tokens» counter flicker; reset when the turn changes or work ends.
        var tokenTurnKey: Long? = null
        var tokenAccum = 0L
        // NO POLLER, NO BANNER. The loop also leaves via `return` (Failed /
        // Closed) and via job cancellation when the chat closes — states in which
        // nothing left in the app could ever clear this flag.
        try {
        while (true) {
            val bgSince = backgroundedSince()
            val bgFor = if (bgSince != null) System.currentTimeMillis() - bgSince else 0L
            val interval = pickPollInterval(
                isWorking = lastSeenWorking,
                bgForMs = bgFor,
                idleTicks = idleTicks,
            )
            // INTERRUPTIBLE sleep. The interval is chosen from the state as it
            // was at the TOP of the tick, and a turn that starts two seconds
            // into a 30 s sleep used to go unnoticed for the remaining
            // twenty-eight — with the persistent reader wedged, the tail-poll IS
            // the delivery path, so that latency is what the user sees between
            // the agent finishing and the words appearing. Sleep in short slices
            // and break out the moment our own turn starts or ends. Costs one
            // in-memory StateFlow read per second; zero bytes. Slice size: 1 s
            // in the foreground (the turn-edge must cut the sleep short while
            // someone is watching), but BACKGROUNDED the 1 s slice was pure
            // battery burn — at the 60 s deep-background cadence the coroutine
            // still woke 60×/min per open chat with nobody looking. A 5 s slice
            // keeps background turn-edge latency bounded (queued sends / bridge
            // turns still get noticed) at 1/5 the wakeups.
            val sliceMs = if (bgSince != null) TURN_EDGE_CHECK_BG_MS else TURN_EDGE_CHECK_MS
            val wakeAt = System.currentTimeMillis() + interval
            while (System.currentTimeMillis() < wakeAt) {
                val st = s.state.value
                if (st is SessionState.Failed || st is SessionState.Closed) return
                if ((st is SessionState.Working) != lastCurWorking) break
                delay(minOf(sliceMs, wakeAt - System.currentTimeMillis()).coerceAtLeast(1L))
            }
            val curState = s.state.value
            if (curState is SessionState.Failed || curState is SessionState.Closed) return
            val curWorking = curState is SessionState.Working
            // Reset on BOTH edges: a mirrored turn must not inherit the latch
            // from one of ours, or vice versa.
            if (curWorking != lastCurWorking) sawGrowthThisTurn = false
            if (curWorking) fileTailIsOurs = true
            val stat = statSizeAndAgentAlive(s, agent, path, sessionId)
            val size = stat.size
            if (size == null) {
                // Transport hiccup — DON'T flip the spinner; keep prior state.
                // BUT pendingControl() is an in-RAM map read, and a banner WE
                // raised from a live control must not outlive that control just
                // because stat failed. Without this, a wedged transport strands
                // "waiting for your answer" with nothing left in the app able to
                // clear it (user, 2026-07-29).
                if (waitingFromControl && !pendingControl()) {
                    _remoteWaitingForInput.value = false
                    waitingFromControl = false
                }
                idleTicks++
                // Transport-down BACKOFF. The `isWorking` fast lane bypasses the
                // idle-tick backoff, so a chat stuck "Working" on a dead
                // transport kept stat'ing every 5 s forever — pure radio burn
                // that can't possibly deliver anything. Grow extra sleep with
                // each consecutive failure (5 s per miss, capped +30 s); one
                // successful stat resets it below.
                statFailStreak++
                delay((statFailStreak.coerceAtMost(6)) * 5_000L)
                continue
            }
            statFailStreak = 0
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
                // FREE REPAIR FIRST. The overwhelmingly common shrink is our own
                // listSessionsScript flipping "entrypoint":"sdk-cli"→"cli"
                // (−4 bytes/tag) — a deterministic substitution we can replay on
                // the cached copy for zero bytes. Downloading the whole file to
                // re-adopt it was costing a FULL file per shrink, and since the
                // CLI writes fresh sdk-cli tags every turn this repeated forever:
                // 3 GB pulled in ~4 hours on a 102 MB session (user, 2026-07-23).
                // Only an EXACT size match proves the local copy now equals the
                // server byte-for-byte; anything else falls through to the
                // authoritative download path, so a real compaction is unaffected.
                val repaired = cache.rewriteEntrypointTags(sessionId)
                if (repaired != null && repaired == size) {
                    android.util.Log.i(
                        "SshAi-Tail",
                        "shrink mid-poll sid=${sessionId.take(8)} $lastOffset→$size — repaired locally, no transfer",
                    )
                    lastOffset = size
                    reseedWindow()
                    idleTicks = 0
                    lastSeenWorking = curWorking
                    lastCurWorking = curWorking
                    _remoteFileOpen.value = curWorking
                    continue
                }
                // A GIANT file must stream, exactly like the open path does. This
                // branch used to call fetchTail(0) unconditionally, materialising
                // the whole rollout in RAM — the very OOM the 4 MB guard exists to
                // prevent, just on the mid-poll side where nobody added it.
                if (size > BIG_FILE_STREAM_BYTES) {
                    val written = streamFullToCache(s, sessionId, path)
                    if (written != null && written > 0) {
                        cache.load(sessionId)?.use { snap -> s.loadHistory(parseJsonl(snap.buffer, agent)) }
                        lastOffset = written
                        reseedWindow()
                        android.util.Log.i(
                            "SshAi-Tail",
                            "shrink mid-poll sid=${sessionId.take(8)} $lastOffset→$size — STREAMED (${written}B)",
                        )
                        idleTicks = 0
                        lastSeenWorking = curWorking
                        lastCurWorking = curWorking
                        _remoteFileOpen.value = curWorking
                        continue
                    }
                    android.util.Log.w(
                        "SshAi-Tail",
                        "shrink mid-poll sid=${sessionId.take(8)} stream failed — falling back to in-memory fetch",
                    )
                }
                val serverFull = fetchTail(s, path, 0L) ?: ByteArray(0)
                if (serverFull.isNotEmpty() && cache.serverContainsAllLocal(sessionId, serverFull)) {
                    android.util.Log.i(
                        "SshAi-Tail",
                        "shrink mid-poll sid=${sessionId.take(8)} $lastOffset→$size — benign rewrite, re-adopting server verbatim",
                    )
                    // Same complete-line contract as the open path.
                    val safeFull = trimToLastNewline(serverFull)
                    cache.save(sessionId, safeFull)
                    lastOffset = safeFull.size.toLong()
                } else {
                    android.util.Log.w(
                        "SshAi-Tail",
                        "compact mid-poll sid=${sessionId.take(8)} cachedOffset=$lastOffset serverSize=$size — real compaction, merging keep-local",
                    )
                    val merged = cache.mergeServer(sessionId, serverFull)
                    if (merged != null) cache.save(sessionId, merged) // null = local too large to merge; keep file
                    lastOffset = size
                }
                // The cache was rewritten wholesale — the incremental record
                // window no longer describes it.
                reseedWindow()
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
                                // Someone else is writing this file now, so the
                                // frozen-think fallback becomes meaningful again.
                                fileTailIsOurs = false
                            }
                        }
                        cache.append(sessionId, safe)
                        // Project ONLY the new bytes into the turn-state window —
                        // same records the remote jq used to return, for zero
                        // extra traffic and no dependency on the host's toolchain.
                        growWindow(safe)
                        // Proof this turn actually reached the file.
                        if (curWorking) sawGrowthThisTurn = true
                        lastOffset += safe.size.toLong()
                    }
                }
                idleTicks = 0  // growth → poll fast
            } else {
                idleTicks++
            }
            // ── TURN VERDICT, from the LOCAL window ──
            // Computed HERE, after the fetch above folded this tick's growth into
            // the window, so a mirrored turn lights the spinner on the SAME tick
            // its bytes land — not one poll interval later. `frozenForMs` still
            // comes from the server (its own clock at both ends, so it stays
            // skew-proof); everything else is derived from bytes we already hold.
            val sig = spec.inferTurnState(recWindow.toList(), stat.frozenForMs)
            val probe = stat.copy(
                inFlight = sig.inFlight,
                turnStartMs = sig.turnStartMs,
                thinking = sig.thinking,
                tokens = sig.tokens,
                waitingForUser = sig.waitingForUser,
                turnComplete = sig.turnComplete,
            )
            // Same heartbeat rule as the seed: no writes for a minute means the
            // turn is over, however unfinished the last record looks.
            val inFlight = probe.inFlight &&
                (stat.frozenForMs ?: Long.MAX_VALUE) < STALE_TURN_MS
            val turnStart = probe.turnStartMs
            val thinking = probe.thinking
            ai.eight24family.conch.util.Logx.d("SshAi-Tail") {
                "turn sid=${sessionId.take(8)} agent=$agent recs=${recWindow.size} " +
                    "inFlight=$inFlight complete=${probe.turnComplete} thinking=$thinking " +
                    "tokens=${probe.tokens} frozenMs=${stat.frozenForMs} size=$size"
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
            // blocked on the user, invisible to the file). ⚠ The freshness gate must
            // not be able to DISABLE the safety net. It used to read `frozenForMs!=
            // null && frozenForMs >= GRACE`, and frozenForMs is null whenever the
            // stat probe can't produce BOTH size and mtime — on such a host the
            // whole reconcile silently never fired and a finished turn span forever
            // (user, 2026-07-29: reply complete, token/cost row rendered, spinner
            // still going). When the server clock is unreadable, fall back to OUR
            // OWN elapsed since the file first reported the turn terminal: same
            // grace, no dependency on parsing the remote clock.
            val stuckSinceMs = probe.frozenForMs ?: run {
                val first = terminalSeenAtMs ?: System.currentTimeMillis().also { terminalSeenAtMs = it }
                System.currentTimeMillis() - first
            }
            if (!probe.turnComplete) terminalSeenAtMs = null
            val liveStuck = shouldReconcileStuckTurn(
                curWorking = curWorking,
                sawGrowthThisTurn = sawGrowthThisTurn,
                turnComplete = probe.turnComplete,
                pendingCtl = pendingCtl,
                waitingForUser = probe.waitingForUser,
                stuckSinceMs = stuckSinceMs,
            )
            if (liveStuck) {
                android.util.Log.w(
                    "SshAi-Tail",
                    "live turn stuck: file done + ${stuckSinceMs}ms " +
                        "(frozen=${probe.frozenForMs ?: "n/a"}, growth=$sawGrowthThisTurn) " +
                        "but state=Working — reconciling",
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
            waitingFromControl = pendingCtl
            _remoteWaitingForInput.value = waitingForInput(
                pendingCtl = pendingCtl,
                fileWaiting = probe.waitingForUser,
                fileTailIsOurs = fileTailIsOurs,
                inFlight = inFlight,
                thinking = thinking,
                frozenForMs = probe.frozenForMs,
            )
            lastSeenWorking = working
            lastCurWorking = curWorking
        }
        } finally {
            _remoteWaitingForInput.value = false
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
        val k = if (dataSaverCached()) 6L else 1L
        return when {
            // A TURN IS RUNNING — full speed, data saver or not. The multiplier
            // was sized when this probe shipped a `tail -n 400 | jq` projection
            // back on every tick (tens of KB); it is now `stat` + `date`, about
            // 250 bytes of command and reply. Throttling that 3× saves the user
            // roughly 40 bytes a second and costs them up to half a minute of
            // staring at a spinner after the answer already exists. Data saver
            // means "don't spend my money", not "make the app unusable"; what it
            // must throttle is the FETCH of file bytes, which is already
            // proportional to real growth.
            isWorking -> POLL_INTERVAL_MS
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
        // first, so the on-screen count always matches the number of sends. ⚠
        // Keyed by [userBodyKey], NOT `text.trim()`. The file's copy of a prompt
        // is not byte-identical to what we sent — Codex appends its own `<image
        // name=… path=…>` block, and a CLI re-rendering history prefixes `❯ ` and
        // hard-wraps. Under exact equality those echoes were judged NEW and
        // appended here, i.e. BELOW the reply they had already received: the
        // duplicate stuck at the bottom of the chat (a month of reports, fixed
        // 2026-08-06).
        val shownUserTexts = hist.asSequence()
            .filterIsInstance<AgentMessage.UserText>()
            .map { ai.eight24family.conch.agent.userBodyKey(it.text) }
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
            // A turn the user just rewound away must not come back from the
            // file (the delta window can't prove it was abandoned).
            val body = when (it) {
                is AgentMessage.UserText -> it.text
                is AgentMessage.AssistantText -> it.text
                else -> null
            }
            if (body != null && s.isSuppressedByRewind(body)) return@filter false
            // Dedup a user prompt's JSONL echo against the OPTIMISTIC copy that
            // ALREADY made it into history (shownUserTexts) — NOT against
            // `wasRecentlySent`. legit repeats keep their N optimistic copies) AND
            // restores the bubble whenever the optimistic copy is missing.
            if (it is AgentMessage.UserText) {
                val isEcho = ai.eight24family.conch.agent.userBodyKey(it.text) in shownUserTexts
                // The echo is dropped (the optimistic bubble already shows it),
                // but it carries something the optimistic copy never had: the
                // JSONL record uuid, which is the ONLY handle rewind can use.
                // Hand it to the row on screen before discarding the echo, or
                // the messages the user just sent stay un-rewindable until the
                // chat is reopened.
                if (isEcho && it.recordUuid != null) {
                    s.stampUserRecordUuid(it.text, it.recordUuid)
                }
                return@filter !isEcho
            }
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
        val inFlight: Boolean = false,
        /** Turn-start epoch ms — the last user PROMPT (not a tool_result), so the
         *  timer spans the whole turn like the CLI, not since the last tool. */
        val turnStartMs: Long? = null,
        /** THINKING phase specifically (last event is `user` — model generating),
         *  as opposed to a tool running. Gates the «with X effort» suffix. */
        val thinking: Boolean = false,
        /** Cumulative output tokens THIS turn — sum of distinct assistant
         *  messages' output_tokens since the turn-start (matches the CLI's
         *  «↓ N tokens» for a mirrored session, where there's no live feed). */
        val tokens: Long = 0L,
        /** File last-modified epoch ms (server clock). Kept for logging. */
        val mtimeMs: Long? = null,
        /** How long the file has been FROZEN, in ms, computed ENTIRELY on the
         *  server (`date +%s` − mtime) so phone↔server clock skew can't make the
         *  "waiting for a console answer" hint fire instantly or never (audit,
         *  2026-06-14). null when the stat/date read failed. */
        val frozenForMs: Long? = null,
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
        // SIZE + MTIME ONLY. The turn-state RECORDS are no longer projected here:
        // they used to ride a `tail -n 400 | jq` on the far end, which (a) made the
        // single most important signal in the app depend on `jq` being installed
        // AND regex-capable on the user's box — when it isn't, jq prints nothing,
        // every signal reads false, and the stuck-turn reconcile can never fire —
        // and (b) shipped up to ~80 KB of projection back every 5 s on top of the
        // file bytes we were already downloading. Both gone: the poll loop projects
        // the same records locally from the cache. `stat -c %s,%Y` gives size+mtime,
        // `date +%s` is the server clock → skew-proof "frozen" duration.
        // ⚠ The stat line MUST be unambiguous. Both stats print nothing when the
        // file is gone or unreadable (a stale path from the never-pruned owner
        // sidecar, a session deleted server-side), and the next line — `date +%s`
        // — then slid into first place and was read as the SIZE. That made a
        // missing file look like a ~1.8 GB one: the `size == null` guard never
        // fired, every tick saw "grew", the poll never backed off, and the chat
        // sat there alive-but-empty. Sentinel + a shape check on the pair.
        val inner = "if [ -r $q ]; then stat -c %s,%Y $q 2>/dev/null || " +
            "stat -f %z,%m $q 2>/dev/null; else echo SSHAI_NOFILE; fi; date +%s; echo ---;"
        val out = s.execOnLive("bash -lc " + shQuote(inner)) ?: return PollProbe(size = null)
        val statPart = out.substringBefore("---")
        val statLines = statPart.lineSequence().filter { it.isNotBlank() }.toList()
        // Accept ONLY a real "<size>,<mtime>" pair. `date +%s` carries no comma,
        // so it can never be mistaken for a stat result again — belt and braces
        // next to the sentinel, since a BSD/BusyBox host could still surprise us.
        val statFields = statLines.firstOrNull()
            ?.takeIf { it != "SSHAI_NOFILE" }
            ?.split(',')
            ?.takeIf { it.size == 2 && it[0].trim().toLongOrNull() != null && it[1].trim().toLongOrNull() != null }
        val size = statFields?.getOrNull(0)?.trim()?.toLongOrNull()
        val mtimeSec = statFields?.getOrNull(1)?.trim()?.toLongOrNull()
        val mtimeMs = mtimeSec?.let { it * 1000 }
        val serverNowSec = statLines.getOrNull(1)?.trim()?.toLongOrNull()
        // Both ends are the server's own clock → skew-proof, and still correct on
        // open (real frozen time, not "since the app noticed").
        val frozenForMs = if (mtimeSec != null && serverNowSec != null)
            ((serverNowSec - mtimeSec) * 1000).coerceAtLeast(0L) else null
        // Turn signals are filled in by the poll loop from the LOCAL record
        // window; this probe answers only "how big is it and when did it last
        // change". A caller that just wants the size gets it without paying for
        // any projection at all.
        return PollProbe(
            size = size,
            mtimeMs = mtimeMs,
            frozenForMs = frozenForMs,
        )
    }

    suspend fun fetchTail(s: AgentSession, path: String, fromOffset: Long): ByteArray? {
        val inner = if (fromOffset <= 0L) {
            "cat ${shQuote(path)}"
        } else {
            "tail -c +${fromOffset + 1} ${shQuote(path)}"
        }
        // COMPRESS ON THE WIRE. Session JSONL is highly repetitive text and
        // gzips ~10x; a full open of a 100 MB rollout used to put 100 MB across
        // the link. On mobile data that is real money — it ate a whole monthly
        // quota (user, 2026-07-23). base64 is needed only because execOnLive
        // hands back a String and raw gzip bytes would be mangled by UTF-8
        // decoding; its +33% is dwarfed by what gzip removes.
        //
        // `base64 -w0` is GNU; if a host ships a BusyBox/BSD base64, or has no
        // gzip, the command fails or the payload won't decode — so we FALL BACK
        // to the old plain path rather than lose the chat.
        val gz = s.execOnLive("bash -lc " + shQuote("$inner | gzip -c | base64 -w0"))
        if (!gz.isNullOrBlank()) {
            gunzipBase64(gz)?.let { plain ->
                // Account every transfer so the next traffic question is answered
                // with numbers, not an estimate. Debug-only (R8 strips Logx.d).
                ai.eight24family.conch.util.Logx.d("SshAi-Tail") {
                    "fetch offset=$fromOffset wire=${gz.length}B inflated=${plain.size}B " +
                        "ratio=${plain.size / gz.length.coerceAtLeast(1)}x"
                }
                return plain
            }
        }
        val out = s.execOnLive("bash -lc " + shQuote(inner)) ?: return null
        return out.toByteArray(Charsets.UTF_8)
    }

    /** base64 → gzip → bytes. Null when the payload isn't what we asked for. */
    private fun gunzipBase64(b64: String): ByteArray? =
        SilentlyTry.loggedOrElse("SshAi-Tail", "gunzip base64 tail", null) {
            val raw = android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT)
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(raw)).use { it.readBytes() }
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
        // Same wire-compression story as fetchTail, minus the base64: this path
        // owns a raw byte stream, so gzip rides it directly. A full re-adopt of
        // a 100 MB rollout drops to ~10 MB on the link. RAM stays flat — we
        // inflate streaming, never materialising the file.
        val cmd = "bash -lc " + shQuote("cat ${shQuote(path)} | gzip -c")
        return SilentlyTry.loggedOrElse("SshAi-Tail", "stream full file to cache", null) {
            val sess = client.startSession()
            try {
                val proc = sess.exec(cmd)
                val n = ServiceLocator.historyCache.saveFromStream(
                    sessionId,
                    java.util.zip.GZIPInputStream(proc.inputStream),
                )
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
        // A rewound branch is still physically in the file (the CLI forks the
        // chain instead of truncating), so a linear read shows the discarded
        // turn next to its replacement and the rewind looks like it did
        // nothing. Resolve the active chain first.
        //
        // ⚠ NEVER materialise the lines to do this: a 100 MB rollout is
        // exactly what this file's per-line decode exists to survive (a
        // whole-buffer transient OOM-killed the app, 2026-05-29). Pass 1 is
        // O(1) memory and answers "was there ever a rewind?"; only then does
        // pass 2 build the uuid→parent map.
        val offChain = if (agent == Agent.CLAUDE) {
            val detector = ai.eight24family.conch.agent.claude.ClaudeChainFilter.RewindDetector()
            ai.eight24family.conch.util.JsonlUtils.forEachLine(
                buffer.duplicate(), onOversize = { _, _ -> },
            ) { detector.feed(it) }
            if (detector.found) {
                val resolver = ai.eight24family.conch.agent.claude.ClaudeChainFilter.ChainResolver()
                ai.eight24family.conch.util.JsonlUtils.forEachLine(
                    buffer.duplicate(), onOversize = { _, _ -> },
                ) { resolver.feed(it) }
                resolver.result()
            } else emptySet()
        } else emptySet()
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
            if (line.isNotBlank() &&
                !ai.eight24family.conch.agent.claude.ClaudeChainFilter.isOffChain(line, offChain)
            ) {
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
        // See the buffer overload: a rewind FORKS the transcript instead of
        // truncating it, so the abandoned branch must be resolved away.
        val chain = ai.eight24family.conch.agent.claude.ClaudeChainFilter
        val offChain = if (agent == Agent.CLAUDE && chain.hasRewind(text.lineSequence())) {
            chain.offChainUuids(text.lineSequence())
        } else emptySet()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (chain.isOffChain(line, offChain)) continue
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
        /**
         * May we force-complete a live turn from the session file's verdict?
         *
         * Every conjunct is load-bearing:
         *  - [curWorking] — we think a turn of OURS is running;
         *  - [sawGrowthThisTurn] — the file has been written SINCE that turn
         *    began. Without it the previous turn's terminal record, frozen for
         *    however long the chat sat idle, force-completes a turn the CLI
         *    hasn't started answering yet: a warm process gets `turnDone` and a
         *    teardown mid-reply, a cold one loses its spinner and Stop button
         *    while the agent works. `Working` is set BEFORE the process is even
         *    launched, so the window is seconds wide;
         *  - [turnComplete] — a REAL terminal stop_reason, never the staleness
         *    fallback, so a long silent research turn is never torn down;
         *  - not [pendingCtl] / not [waitingForUser] — the turn is legitimately
         *    blocked on the human, which the file cannot see;
         *  - [stuckSinceMs] past the grace, so a normal end-of-turn race doesn't
         *    trip it.
         *
         * Extracted as a pure function purely so it can be tested — it lives
         * inside a 300-line suspend fun otherwise, which is why it had none.
         */
        /** See [RECONCILE_STUCK_MS]. */
        internal const val RECONCILE_STUCK_GRACE_MS = 6_000L

        /**
         * Should the chat say the agent is BLOCKED ON A HUMAN ANSWER?
         *
         * Extracted pure so it can be tested, same reason as
         * [shouldReconcileStuckTurn].
         *
         *  - [pendingCtl] — WE hold the live control. Authoritative, no timeout.
         *  - [fileWaiting] — the FILE records a blocked turn (per-agent signal).
         *  - the frozen-think fallback is a WEAK guess for a MIRRORED session
         *    whose question lives in ANOTHER client's RAM, where we cannot see it.
         *
         * [fileTailIsOurs] is the gate that was missing. The old code fed the
         * fallback `(curWorking && !liveStuck) || inFlight`, which is TRUE for our
         * own turn — flatly contradicting the comment two lines above it. So after
         * we KILLED a turn, the file's last row stayed a `user` record with no
         * interrupt marker, inFlight and thinking stayed true for twelve minutes,
         * the file froze because nothing was writing, and the app told the user to
         * go answer a question that did not exist (2026-07-29).
         */
        internal fun waitingForInput(
            pendingCtl: Boolean,
            fileWaiting: Boolean,
            fileTailIsOurs: Boolean,
            inFlight: Boolean,
            thinking: Boolean,
            frozenForMs: Long?,
        ): Boolean = pendingCtl || fileWaiting ||
            (!fileTailIsOurs && inFlight && thinking &&
                frozenForMs != null && frozenForMs > STALL_FOR_INPUT_MS)

        internal fun shouldReconcileStuckTurn(
            curWorking: Boolean,
            sawGrowthThisTurn: Boolean,
            turnComplete: Boolean,
            pendingCtl: Boolean,
            waitingForUser: Boolean,
            stuckSinceMs: Long,
        ): Boolean = curWorking && sawGrowthThisTurn && turnComplete &&
            !pendingCtl && !waitingForUser && stuckSinceMs >= RECONCILE_STUCK_GRACE_MS

        /** A turn whose file has not been written for this long is not running.
         *  Long enough that a slow tool call cannot trip it, short enough that a
         *  dead writer's spinner does not outlive the turn by half an hour. */
        private const val STALE_TURN_MS = 60_000L

        /** How many projected turn-state records to keep. A long tool chain emits
         *  ~2 lines per round; 200 lost the turn-start at scale (audit
         *  2026-06-14), so 400 — the same window the remote `tail -n 400` used. */
        const val TURN_RECORD_WINDOW: Int = 400

        /** How often the interruptible sleep re-checks for a turn edge. */
        const val TURN_EDGE_CHECK_MS: Long = 1_000L

        /** Same re-check, BACKGROUNDED — 5× coarser: nobody is watching, the
         *  1 s slice was 60 CPU wakeups/min per open chat at the deep tier. */
        const val TURN_EDGE_CHECK_BG_MS: Long = 5_000L

        /** Data-saver pref, cached 30 s. [pickPollInterval] runs EVERY poll
         *  tick of EVERY open chat and used to `runBlocking`-read DataStore
         *  each time — a disk read + thread block just to learn a flag that
         *  changes a few times a year. Process-wide on purpose (the pref is
         *  global); 30 s staleness on a poll-interval multiplier is invisible. */
        @Volatile private var dataSaverCacheValue = false
        @Volatile private var dataSaverCacheAtMs = 0L
        private fun dataSaverCached(): Boolean {
            val now = System.currentTimeMillis()
            if (now - dataSaverCacheAtMs < 30_000L) return dataSaverCacheValue
            val v = SilentlyTry.loggedOrElse("SshAi-Chat", "read data saver pref", false) {
                runBlocking { ServiceLocator.preferences.dataSaverEnabled.first() }
            }
            dataSaverCacheValue = v
            dataSaverCacheAtMs = now
            return v
        }

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
