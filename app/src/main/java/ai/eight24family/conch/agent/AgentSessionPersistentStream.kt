package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.claude.ClaudeControlWire
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * PERSISTENT bidirectional stream-json channel to the agent CLI — the
 * transport the official Agent SDK uses (`--input-format stream-json
 * --output-format stream-json`, no `--print`). One long-lived process
 * per chat instead of one process per turn:
 *
 *  - user turns are JSON lines written to the live stdin;
 *  - the CLI's `control_request`s come back on stdout interleaved with
 *    the usual stream-json events: `can_use_tool` (live permission
 *    prompts in SAFE/AUTO mode + AskUserQuestion option picking) is
 *    surfaced as chat cards and answered with `control_response`s;
 *  - Stop sends a real `interrupt` control request instead of killing
 *    the process;
 *  - mid-chat model/effort/approval changes restart the process with
 *    `--resume` (cheap, transparent — the session lives server-side).
 *
 * This is what makes the app interactive-capable at parity with the
 * CLI's own TUI. Wire shapes live in [ClaudeControlWire]; verified
 * against CLI 2.1.170's embedded schemas + the Agent SDK sources.
 *
 * Failure discipline (mirrors [AgentSessionRunOneShot]):
 *  - transport dead before/during a turn → prompt handed back via
 *    [onPromptUndelivered] + `Failed("disconnected")` → the existing
 *    silent auto-reconnect machinery takes over;
 *  - the persistent LAUNCH itself failing (ancient CLI without
 *    `--input-format`, etc.) → [broken] = true and the caller falls
 *    back to the proven one-shot path for the rest of the session —
 *    silently (auto-fix invariant).
 */
internal class AgentSessionPersistentStream(
    private val server: ai.eight24family.conch.domain.Server,
    private val scope: CoroutineScope,
    private val sshLifecycle: AgentSessionSshLifecycle,
    private val history: AgentSessionHistory,
    private val onStateChange: (SessionState) -> Unit,
    private val getState: () -> SessionState,
    private val getResumeId: () -> String?,
    private val setResumeId: (String) -> Unit,
    private val cwdSnapshot: () -> String?,
    private val getModelOverride: () -> String?,
    private val getReasoningOverride: () -> String?,
    private val getApprovalMode: () -> ai.eight24family.conch.data.prefs.AgentApprovalMode,
    private val loginShell: (String) -> String,
    private val getAuthPrep: () -> String,
    private val onPromptUndelivered: (String) -> Unit,
    /** Live reasoning-token feed (`system/thinking_tokens` →
     *  estimated_tokens). null clears the row at turn end. */
    private val onThinkingTokens: (Long?) -> Unit = {},
) {
    private val tag = "SshAi-Persist"

    /** Everything that requires a process RESTART when changed. Captured
     *  at launch; compared at every turn start. */
    private data class LaunchParams(
        val model: String?,
        val reasoning: String?,
        val approval: ai.eight24family.conch.data.prefs.AgentApprovalMode,
        val authPrep: String,
        val cwd: String?,
    )

    @Volatile private var procSession: Session? = null
    @Volatile private var procCmd: Session.Command? = null
    @Volatile private var procAlive = false
    @Volatile private var launched: LaunchParams? = null
    private var readerJob: Job? = null

    /** Serialises every stdin write (turns, control responses). */
    private val writeLock = Any()

    /** Completed when the CURRENT turn's `result` event lands. */
    @Volatile private var turnDone: CompletableDeferred<Boolean>? = null

    /** True after a launch-level failure — the session permanently falls
     *  back to the one-shot path (checked by AgentSession's router). */
    @Volatile var broken = false
        private set

    /** request_id → the pending CLI→client control request (can_use_tool).
     *  Needed to echo the ORIGINAL tool input back on allow. */
    private val pendingControls =
        java.util.concurrent.ConcurrentHashMap<String, ClaudeControlWire.ControlRequest>()

    private val reqCounter = AtomicInteger(0)
    private var turnSeq = 0

    /**
     * Execute one turn over the persistent channel.
     * @return false ONLY on a launch-level failure with the prompt
     *  undelivered and [broken] set — the caller should re-run the text
     *  through the one-shot path. Every other outcome (success, turn
     *  error, transport death mid-turn) returns true having already done
     *  the same state/history bookkeeping the one-shot runner does.
     */
    suspend fun runTurn(text: String): Boolean = withContext(Dispatchers.IO) {
        val client = sshLifecycle.sshClient
        if (client == null || !client.isConnected) {
            android.util.Log.w(tag, "runTurn ABORT: transport down (client=${client != null})")
            onPromptUndelivered(text)
            onStateChange(SessionState.Failed("disconnected"))
            return@withContext true
        }
        onStateChange(SessionState.Working)
        try {
            backfillCwdIfNeeded()
            if (!ensureProcess()) {
                // Launch failed — hand the prompt back for the one-shot
                // fallback. broken is already set.
                return@withContext false
            }
            val done = CompletableDeferred<Boolean>()
            turnDone = done
            val line = AgentSpecRegistry[server.agent].encodeUserTurn(text)
            if (!writeLine(line)) {
                // stdin write failed → process/transport died between
                // ensureProcess and the write. Same silent-reconnect
                // semantics as the one-shot ABORT paths.
                android.util.Log.w(tag, "runTurn: stdin write failed — marking disconnected")
                teardownProcess()
                onPromptUndelivered(text)
                onStateChange(SessionState.Failed("disconnected"))
                return@withContext true
            }
            android.util.Log.d(tag, "turn sent (${text.length}B) resume=${getResumeId()}")
            val completed = withTimeoutOrNull(TURN_TIMEOUT_MS) { done.await() }
            if (completed == null) {
                android.util.Log.w(tag, "turn timed out after ${TURN_TIMEOUT_MS / 60000} min — interrupting")
                interrupt()
                history.emitMsg(
                    AgentMessage.Error(
                        UUID.randomUUID().toString(),
                        "${server.agent.cliCommand} turn timed out",
                    )
                )
            } else if (!completed && !sshLifecycle.userCancelled) {
                // Reader hit EOF mid-turn — process died without a result.
                android.util.Log.w(tag, "process died mid-turn — marking disconnected")
                onPromptUndelivered(text)
                onStateChange(SessionState.Failed("disconnected"))
                return@withContext true
            }
            true
        } finally {
            turnDone = null
            sshLifecycle.userCancelled = false
            onThinkingTokens(null) // turn over → drop the live thinking row
            history.flushStreamingBuffer()
            if (getState() == SessionState.Working) onStateChange(SessionState.Running)
        }
    }

    /** Same cwd backfill the one-shot runner performs — a resumed chat
     *  must `cd` into the directory its session was created in. */
    private suspend fun backfillCwdIfNeeded() {
        val rid = getResumeId() ?: return
        if (cwdSnapshot() != null) return
        val spec = AgentSpecRegistry[server.agent]
        val script = spec.cwdBackfillScript(rid) ?: return
        val raw = sshLifecycle.execOnLive("bash -lc " + shellEscape(script))
        val cwd = raw?.let {
            Regex("\"cwd\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.getOrNull(1)
        }
        if (!cwd.isNullOrBlank()) {
            history.emitMsg(
                AgentMessage.System(
                    id = UUID.randomUUID().toString(),
                    subtype = "cwd_backfill",
                    cwd = cwd,
                    sessionId = rid,
                    raw = "{\"backfilled\":true,\"cwd\":\"$cwd\"}",
                )
            )
        }
    }

    /** Start (or reuse) the persistent process. Restarts when launch
     *  params changed (model/effort/approval pick mid-chat). */
    private fun ensureProcess(): Boolean {
        val params = LaunchParams(
            model = getModelOverride()?.takeIf { it.isNotBlank() },
            reasoning = getReasoningOverride()?.takeIf { it.isNotBlank() },
            approval = getApprovalMode(),
            authPrep = getAuthPrep(),
            cwd = cwdSnapshot(),
        )
        if (procAlive && launched == params) return true
        if (procAlive) {
            android.util.Log.d(tag, "launch params changed → restarting persistent process")
        }
        teardownProcess()

        val client = sshLifecycle.sshClient ?: return false
        val spec = AgentSpecRegistry[server.agent]
        val inner = spec.buildPersistentCommand(
            ExecInput(
                text = "",
                resumeId = getResumeId(),
                model = params.model,
                approvalMode = params.approval,
                cwdSnapshot = params.cwd,
                reasoningEffort = params.reasoning,
            )
        ) ?: run {
            broken = true
            return false
        }
        val cdPrefix = params.cwd?.takeIf { it.isNotBlank() }
            ?.let { "cd ${shellEscape(it)} && " } ?: ""
        val full = loginShell(params.authPrep + cdPrefix + inner)
        return try {
            val sess = client.startSession()
            val cmd = sess.exec(full)
            procSession = sess
            procCmd = cmd
            procAlive = true
            launched = params
            turnSeq++
            startReader(cmd)
            android.util.Log.d(
                tag,
                "persistent process started resume=${getResumeId()} " +
                    "model=${params.model ?: "<default>"} approval=${params.approval}",
            )
            // initialize handshake — the Agent SDK ALWAYS sends this first.
            // It registers the session and (critically) declares which
            // dialog kinds we render; some control flows degrade / fail
            // closed without it. Fire-and-log: the reader prints the ack.
            val initId = "init-${reqCounter.incrementAndGet()}-${UUID.randomUUID().toString().take(8)}"
            writeLine(ClaudeControlWire.encodeInitialize(initId))
            true
        } catch (t: Throwable) {
            android.util.Log.w(tag, "persistent launch failed: ${t.message} — falling back to one-shot", t)
            broken = true
            teardownProcess()
            false
        }
    }

    /** Rolling tail of the last raw stdout lines — dumped on EOF so a
     *  mid-turn death (the "stdin write failed" + EOF loop, 2026-06-12)
     *  is diagnosable from `adb logcat -s SshAi-Persist` without guessing. */
    private val rawTail = ArrayDeque<String>()

    // ── Live local-history cache ──────────────────────────────────────
    // Persist the chat to the on-device HistoryCache AS IT STREAMS, so a
    // brand-new session is readable offline immediately and the
    // background prefetch's `size==0` gate SKIPS re-downloading it from
    // the server on every reconnect.
    @Volatile private var liveCacheRid: String? = null
    @Volatile private var liveCacheActive = false
    private val preIdLineBuffer = ArrayDeque<String>()

    private fun cacheRawLine(line: String) {
        // Skip partial-message deltas — they'd balloon the file; the final
        // full `assistant` event is appended like any other line.
        if (line.contains("\"stream_event\"")) return
        val rid = getResumeId()
        if (liveCacheRid == null && rid != null) {
            liveCacheRid = rid
            liveCacheActive = SilentlyTry.loggedOrElse(tag, "live-cache size gate", false) {
                ai.eight24family.conch.di.ServiceLocator.historyCache.size(rid) == 0L
            }
            if (liveCacheActive) {
                val flush = synchronized(preIdLineBuffer) { preIdLineBuffer.toList().also { preIdLineBuffer.clear() } }
                if (flush.isNotEmpty()) {
                    SilentlyTry.fired(tag, "flush pre-id live cache") {
                        ai.eight24family.conch.di.ServiceLocator.historyCache.append(
                            rid, (flush.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8),
                        )
                    }
                }
            } else {
                synchronized(preIdLineBuffer) { preIdLineBuffer.clear() }
            }
        }
        if (rid == null) {
            // Buffer until the id arrives (system/init), bounded.
            synchronized(preIdLineBuffer) {
                preIdLineBuffer.addLast(line)
                while (preIdLineBuffer.size > 400) preIdLineBuffer.removeFirst()
            }
            return
        }
        if (liveCacheActive) {
            SilentlyTry.fired(tag, "live-cache append line") {
                ai.eight24family.conch.di.ServiceLocator.historyCache.append(
                    rid, (line + "\n").toByteArray(Charsets.UTF_8),
                )
            }
        }
    }

    private fun startReader(cmd: Session.Command) {
        val myTag = "p${turnSeq}_"
        readerJob = scope.launch {
            val spec = AgentSpecRegistry[server.agent]
            try {
                BufferedReader(InputStreamReader(cmd.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        synchronized(rawTail) {
                            rawTail.addLast(line.take(200))
                            while (rawTail.size > 12) rawTail.removeFirst()
                        }
                        // Live reasoning counter — transient UI state, never
                        // a chat row. Exact-key match so estimated_tokens_delta
                        // can't shadow it.
                        if (line.contains("\"thinking_tokens\"")) {
                            THINKING_TOKENS_RX.find(line)?.groupValues?.get(1)?.toLongOrNull()
                                ?.let { onThinkingTokens(it) }
                        }
                        // Control traffic FIRST — it must never reach the
                        // history parser.
                        if (ClaudeControlWire.isControlLine(line)) {
                            handleControlRequest(line)
                            continue
                        }
                        ClaudeControlWire.parseCancelRequest(line)?.let { cancelled ->
                            retireControl(cancelled)
                            continue
                        }
                        if (line.startsWith("{") && line.contains("\"control_response\"")) {
                            // Ack for our own interrupt/etc. Nothing to render.
                            android.util.Log.d(tag, "control ack: ${line.take(160)}")
                            continue
                        }
                        for (msg in spec.parseStreamLine(line, myTag)) {
                            if (msg is AgentMessage.System && msg.sessionId != null && getResumeId() == null) {
                                setResumeId(msg.sessionId)
                            }
                            if (msg is AgentMessage.UserText) continue
                            history.emitMsg(msg)
                        }
                        // Persist to the on-device cache as we stream (gated to
                        // brand-new sessions — see cacheRawLine). Runs AFTER the
                        // parse so setResumeId has populated the id this turn.
                        cacheRawLine(line)
                        // TURN-END detection on the RAW event type, NOT the parsed
                        // message kind. The terminator in Claude stream-json is the
                        // top-level `result` event — for SUCCESS *and* for is_error
                        // results — plus a fatal top-level `error` (model
                        // unavailable / rate limit / overloaded give-up). The old
                        // gate keyed on `msg is AgentMessage.Result`, but the
                        // result-with-is_error and the error event both parse to
                        // AgentMessage.Error, so the turn NEVER completed on a
                        // limit / Fable-5-disabled error → spinner span forever,
                        // Stop stayed active (user, 2026-06-13). `api_retry` is a
                        // `system` event so mid-turn 529 retries are NOT caught
                        // here — the turn keeps running, correctly.
                        val rawType = ParserHelpers.quickType(line)
                        if (rawType == "result" || rawType == "error") {
                            ai.eight24family.conch.util.Logx.d(tag) { "turn-terminal type=$rawType: ${line.take(200)}" }
                            turnDone?.complete(true)
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(tag, "reader died: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                val tail = synchronized(rawTail) { rawTail.joinToString("  ⏎  ") }
                android.util.Log.w(tag, "reader EOF — process gone. last stdout: $tail")
                procAlive = false
                // Any cards still waiting for an answer are dead with the
                // process — freeze them so the user isn't tapping a void.
                pendingControls.keys.toList().forEach { retireControl(it) }
                turnDone?.complete(false)
            }
        }
    }

    private fun handleControlRequest(line: String) {
        val req = ClaudeControlWire.parseControlRequest(line) ?: return
        android.util.Log.d(tag, "control_request ${req.subtype} tool=${req.toolName} id=${req.requestId}")
        when (req.subtype) {
            "can_use_tool" -> {
                pendingControls[req.requestId] = req
                if (req.toolName == "AskUserQuestion") {
                    val questions = req.inputJson
                        ?.let { ClaudeControlWire.parseAskQuestions(it) }
                        .orEmpty()
                    if (questions.isEmpty()) {
                        // Malformed — allow with original input so the turn
                        // doesn't hang on a card we can't render.
                        respondRaw(
                            req.requestId,
                            ClaudeControlWire.encodeAllow(
                                req.requestId,
                                req.inputJson ?: kotlinx.serialization.json.buildJsonObject { },
                            ),
                        )
                        return
                    }
                    history.emitMsg(
                        AgentMessage.AskUserQuestion(
                            id = "ask-${req.requestId}",
                            requestId = req.requestId,
                            questions = questions,
                        )
                    )
                } else {
                    history.emitMsg(
                        AgentMessage.PermissionRequest(
                            id = "perm-${req.requestId}",
                            requestId = req.requestId,
                            toolName = req.toolName.orEmpty(),
                            description = req.toolName.orEmpty(),
                            input = req.inputJson?.toString().orEmpty(),
                            raw = req.raw,
                        )
                    )
                }
            }
            // Dialog kinds we don't render — answer "cancelled" instead of
            // hanging the turn (protocol contract for unrecognized kinds).
            "request_user_dialog", "elicitation" -> respondRaw(
                req.requestId, ClaudeControlWire.encodeDialogCancelled(req.requestId),
            )
            else -> android.util.Log.d(tag, "ignoring control subtype ${req.subtype}")
        }
    }

    /** A pending card's request was cancelled (turn aborted / process
     *  died) — freeze the UI so taps aren't writes into a void. */
    private fun retireControl(requestId: String) {
        val req = pendingControls.remove(requestId) ?: return
        if (req.toolName == "AskUserQuestion") {
            history.resolveQuestion(requestId, emptyMap())
        } else {
            history.resolvePermission(requestId, AgentMessage.PermissionRequest.Resolution.DENIED)
        }
    }

    /**
     * Allow/deny a live `can_use_tool`. Returns true when this stream
     * owned the request (else the caller's legacy path may apply).
     *
     * MUST hop to IO: the card's tap handler arrives on the MAIN thread,
     * and a synchronous SSH-socket write there throws
     * NetworkOnMainThreadException (message=null — the invisible
     * "stdin write failed: null" that broke the transport and looped the
     * question card, 2026-06-12).
     */
    suspend fun respondPermission(requestId: String, decision: PermissionDecision): Boolean =
        withContext(Dispatchers.IO) {
            val req = pendingControls.remove(requestId) ?: return@withContext false
            // Claude's can_use_tool allow is per-call — no session scope — so
            // ALLOW_SESSION behaves like ALLOW_ONCE (the card never offers the
            // session button for Claude anyway, canAllowSession=false).
            val line = if (decision != PermissionDecision.DENY) {
                ClaudeControlWire.encodeAllow(
                    requestId,
                    req.inputJson ?: kotlinx.serialization.json.buildJsonObject { },
                )
            } else {
                ClaudeControlWire.encodeDeny(requestId, "User denied this action from the mobile client.")
            }
            respondRaw(requestId, line)
            true
        }

    /** Answer an AskUserQuestion card. IO-hopped — see [respondPermission]. */
    suspend fun respondQuestion(requestId: String, answers: Map<Int, List<String>>): Boolean =
        withContext(Dispatchers.IO) {
            val req = pendingControls.remove(requestId) ?: return@withContext false
            val input = req.inputJson ?: return@withContext false
            respondRaw(requestId, ClaudeControlWire.encodeAskAnswers(requestId, input, answers))
            true
        }

    /**
     * The user typed a NEW message instead of answering a live AskUserQuestion.
     * DENY the pending tool control so the CLI's current turn ends cleanly (the
     * agent treats it as "user declined" and continues) and mark the turn
     * user-cancelled so the wind-down never surfaces as a turn-fail Error — the
     * agent just goes on to answer the new message (user, 2026-06-26). No-op when
     * nothing is pending (e.g. a read-only MIRRORED question has no control to
     * answer — its card is dismissed separately in history).
     */
    suspend fun cancelPendingQuestions(): Unit = withContext(Dispatchers.IO) {
        val ids = pendingControls.entries
            .filter { it.value.toolName == "AskUserQuestion" }
            .map { it.key }
        if (ids.isEmpty()) return@withContext
        sshLifecycle.userCancelled = true
        for (rid in ids) {
            pendingControls.remove(rid)
            respondRaw(rid, ClaudeControlWire.encodeDeny(rid, "User chose to keep going without answering."))
        }
    }

    private fun respondRaw(requestId: String, line: String) {
        if (!writeLine(line)) {
            android.util.Log.w(tag, "control response write failed for $requestId")
        }
    }

    /** Real mid-turn interrupt — the CLI aborts the turn and still emits
     *  a result, so the normal turn bookkeeping completes. Launched on
     *  the session's IO scope: callers include the MAIN-thread Stop
     *  button, and socket writes on main throw NetworkOnMainThread. */
    fun interrupt() {
        val id = "int-${reqCounter.incrementAndGet()}-${UUID.randomUUID().toString().take(8)}"
        scope.launch { writeLine(ClaudeControlWire.encodeInterrupt(id)) }
    }

    /**
     * Stop = interrupt + escalation. Marks the turn user-cancelled (so
     * EOF noise is swallowed), sends the protocol interrupt, and if the
     * turn is STILL running after a grace window, kills the whole
     * process — it restarts with `--resume` on the next send.
     */
    fun cancelTurn() {
        sshLifecycle.userCancelled = true
        interrupt()
        scope.launch {
            kotlinx.coroutines.delay(4_000)
            if (getState() == SessionState.Working && procAlive) {
                android.util.Log.w(tag, "interrupt not honored in 4s — killing persistent process")
                teardownProcess()
                turnDone?.complete(true)
                if (getState() == SessionState.Working) onStateChange(SessionState.Running)
            }
        }
    }

    private fun writeLine(line: String): Boolean = synchronized(writeLock) {
        val cmd = procCmd ?: run {
            android.util.Log.w(tag, "stdin write skipped: procCmd is null")
            return false
        }
        return try {
            cmd.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            cmd.outputStream.flush()
            true
        } catch (t: Throwable) {
            // Full exception identity — the bare `${t.message}` was null and
            // hid the real cause of the answer-write failure (2026-06-12).
            android.util.Log.w(
                tag,
                "stdin write failed: ${t.javaClass.name}: ${t.message} " +
                    "chanOpen=${procSession?.isOpen} connected=${sshLifecycle.sshClient?.isConnected} " +
                    "alive=$procAlive",
                t,
            )
            procAlive = false
            false
        }
    }

    /** Close stdin (graceful CLI exit: flush session file, then quit),
     *  then the channel. Safe to call repeatedly. */
    fun teardownProcess() {
        readerJob?.cancel()
        readerJob = null
        procCmd?.let { cmd ->
            SilentlyTry.fired(tag, "close persistent stdin") { cmd.outputStream.close() }
        }
        procSession?.let { s ->
            SilentlyTry.fired(tag, "close persistent channel") { s.close() }
        }
        procCmd = null
        procSession = null
        procAlive = false
        launched = null
        pendingControls.clear()
    }

    companion object {
        private const val TURN_TIMEOUT_MS = 15L * 60 * 1000

        /** Exact-key match: `"estimated_tokens":N` (NOT the
         *  `_delta` sibling, whose key string differs). */
        private val THINKING_TOKENS_RX = Regex("\"estimated_tokens\"\\s*:\\s*(\\d+)")
    }
}
