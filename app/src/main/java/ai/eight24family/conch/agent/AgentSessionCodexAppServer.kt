package ai.eight24family.conch.agent

import ai.eight24family.conch.ssh.startStreamSession

import ai.eight24family.conch.agent.codex.CodexAppServerEvents
import ai.eight24family.conch.agent.codex.CodexAppServerWire
import ai.eight24family.conch.agent.codex.CodexAppServerWire.str
import ai.eight24family.conch.agent.codex.CodexMessageParser
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * PERSISTENT `codex app-server` channel — the Codex twin of
 * [AgentSessionPersistentStream] (Claude's control protocol). One
 * long-lived JSON-RPC process per chat:
 *
 *  - handshake: `initialize` (experimentalApi=true) → `initialized` →
 *    `thread/start` or `thread/resume` (the rollout id IS the thread id);
 *  - turns: `turn/start` with PER-TURN model / effort / approvalPolicy /
 *    cwd overrides — mid-chat picker changes need no process restart
 *    (only an auth-method change does);
 *  - server-initiated requests become live cards:
 *    `item/commandExecution/requestApproval` + `item/fileChange/
 *    requestApproval` → [AgentMessage.PermissionRequest],
 *    `item/tool/requestUserInput` → [AgentMessage.AskUserQuestion];
 *  - `contextCompaction` items drive the same animated CompactingRow the
 *    Claude path uses; `thread/tokenUsage/updated` feeds the live
 *    «thinking · N tokens» row; Stop is a real `turn/interrupt`.
 *
 * Wire shapes in [CodexAppServerWire] — verified against the INSTALLED
 * binary's generated TS bindings (0.139.0), not docs.
 *
 * Failure discipline mirrors the Claude stream: transport death →
 * undelivered prompt + `Failed("disconnected")` → silent auto-reconnect;
 * launch/handshake failure (codex too old for app-server v2) → [broken]
 * and the session permanently falls back to the proven `codex exec`
 * one-shot path — silently (auto-fix invariant).
 */
internal class AgentSessionCodexAppServer(
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
    private val onThinkingTokens: (Long?) -> Unit = {},
) {
    private val tag = "SshAi-CodexApp"

    @Volatile private var procSession: Session? = null
    @Volatile private var procCmd: Session.Command? = null
    @Volatile private var procAlive = false
    /** authPrep the live process was launched with — the ONLY launch
     *  param that still forces a restart (everything else is per-turn). */
    @Volatile private var launchedAuthPrep: String? = null
    /** Thread the live process has open (start/resume completed). */
    @Volatile private var threadId: String? = null
    @Volatile private var activeTurnId: String? = null
    private var readerJob: Job? = null

    private val writeLock = Any()
    private val reqCounter = AtomicLong(0)

    /** Our request id → deferred completed with the response object
     *  (`result` on success; `{__rpc_error__:…}` wrapper on error). */
    private val pendingResponses =
        java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<JsonObject?>>()

    /** Server request key → pending card bookkeeping. */
    private data class PendingServerReq(
        val idElement: JsonElement,
        val kind: Kind,
        /** requestUserInput: ordered (qid, hasOptions). */
        val questionIds: List<Pair<String, Boolean>> = emptyList(),
        /** permissions/requestApproval: the profile the agent ASKED for, echoed
         *  back verbatim on a grant. We never widen beyond what was requested. */
        val requestedProfile: kotlinx.serialization.json.JsonObject? = null,
    ) {
        enum class Kind { EXEC_APPROVAL, FILE_APPROVAL, USER_INPUT, PERMISSIONS_PROFILE }
    }

    private val pendingServerReqs =
        java.util.concurrent.ConcurrentHashMap<String, PendingServerReq>()

    @Volatile private var turnDone: CompletableDeferred<Boolean>? = null

    @Volatile var broken = false
        private set

    // Per-turn token accumulation (`thread/tokenUsage/updated`.last is the
    // usage of the latest API call; summing them over the turn gives the
    // turn's spend without inheriting the whole thread's history).
    @Volatile private var turnIn = 0L
    @Volatile private var turnOut = 0L
    @Volatile private var turnCached = 0L
    @Volatile private var turnReasoning = 0L
    @Volatile private var turnDurationMs: Long? = null

    /** The text of the turn currently in flight, kept so an interrupt that
     *  landed before codex took the prompt can hand it back instead of losing
     *  it. Cleared in runTurn's `finally` — a stale copy must never be
     *  redelivered on a LATER turn's abort. */
    @Volatile private var lastPromptText: String? = null

    // Last model/effort we echoed, so a mid-chat picker change surfaces a one-
    // line note (parity with Claude's live-effort display) instead of changing
    // silently — effort/model ARE sent per-turn but were invisible (roadmap #7).
    @Volatile private var lastEchoModel: String? = null
    @Volatile private var lastEchoEffort: String? = null

    /** Streaming agentMessage accumulation: itemKey → builder. */
    private val deltaBuffers = HashMap<String, StringBuilder>()

    /** Execute one turn. Same contract as the Claude stream's runTurn:
     *  false ONLY on launch-level failure ([broken] set, prompt NOT
     *  delivered) — caller reruns through the one-shot path. */
    suspend fun runTurn(text: String, imagePaths: List<String> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        val client = sshLifecycle.liveClient()
        if (client == null || !client.isConnected) {
            android.util.Log.w(tag, "runTurn ABORT: transport down")
            onPromptUndelivered(text)
            onStateChange(SessionState.Failed("disconnected"))
            return@withContext true
        }
        onStateChange(SessionState.Working)
        // Re-arm at turn start: an idle Stop leaves userCancelled=true with no
        // turn cleanup to reset it, and the stale flag makes THIS turn swallow
        // its own timeout / process-death error (zombie chat). See the same
        // guard in the Claude stream + one-shot runner.
        sshLifecycle.userCancelled = false
        try {
            if (!ensureReady()) return@withContext false
            val tid = threadId ?: run { broken = true; return@withContext false }
            val done = CompletableDeferred<Boolean>()
            turnDone = done
            turnIn = 0; turnOut = 0; turnCached = 0; turnReasoning = 0; turnDurationMs = null
            // Echo a model/effort change at turn start (parity with Claude). Only
            // on CHANGE — the first turn just records the baseline.
            val curModel = getModelOverride()?.takeIf { it.isNotBlank() }
            val curEffort = getReasoningOverride()?.takeIf { it.isNotBlank() }
            if (lastEchoModel != null && curModel != null && curModel != lastEchoModel)
                history.emitMsg(CodexMessageParser.note("model · $curModel", tone = AgentMessage.EventNote.Tone.INFO))
            if (lastEchoEffort != null && curEffort != null && curEffort != lastEchoEffort)
                history.emitMsg(CodexMessageParser.note("effort · $curEffort", tone = AgentMessage.EventNote.Tone.INFO))
            lastEchoModel = curModel ?: lastEchoModel
            lastEchoEffort = curEffort ?: lastEchoEffort
            // Hold the prompt for the duration of the turn — see the
            // interrupted-with-no-tokens branch in the turn/completed handler.
            lastPromptText = text
            val turnReqId = reqCounter.incrementAndGet()
            val resp = rpc(
                turnReqId,
                CodexAppServerWire.encodeTurnStart(
                    id = turnReqId,
                    threadId = tid,
                    text = text,
                    model = getModelOverride()?.takeIf { it.isNotBlank() },
                    effort = getReasoningOverride()?.takeIf { it.isNotBlank() },
                    approval = getApprovalMode(),
                    cwd = cwdSnapshot(),
                    imagePaths = imagePaths,
                ),
                timeoutMs = 30_000,
            )
            if (resp == null) {
                android.util.Log.w(tag, "turn/start failed or timed out — marking disconnected")
                teardownProcess()
                onPromptUndelivered(text)
                onStateChange(SessionState.Failed("disconnected"))
                return@withContext true
            }
            SilentlyTry.fired(tag, "read turn id") {
                activeTurnId = resp["turn"]?.jsonObject?.str("id") ?: activeTurnId
            }
            android.util.Log.d(tag, "turn started (${text.length}B) thread=$tid turn=$activeTurnId")
            val completed = withTimeoutOrNull(TURN_TIMEOUT_MS) { done.await() }
            if (completed == null && !sshLifecycle.userCancelled) {
                android.util.Log.w(tag, "turn timed out after ${TURN_TIMEOUT_MS / 60000} min — interrupting")
                interrupt()
                history.emitMsg(AgentMessage.Error(UUID.randomUUID().toString(), "codex turn timed out"))
            } else if (completed != null && !completed && !sshLifecycle.userCancelled) {
                // The app-server ANSWERED turn/start above (we are past the
                // `resp == null` branch and printed a turn id) — so it has the
                // prompt, in its own thread state, recoverable by resume. What
                // died with the process is the ANSWER. Re-sending here re-ran
                // the whole turn on every reconnect, which on a flapping link
                // is an unbounded loop of paid turns (2026-08-16, same defect
                // as the Claude persistent stream). Reconnect, don't re-send.
                android.util.Log.w(
                    tag,
                    "app-server died mid-turn after it acked the prompt — reconnect only, NOT re-sending",
                )
                onStateChange(SessionState.Failed("disconnected"))
                return@withContext true
            }
            true
        } finally {
            turnDone = null
            activeTurnId = null
            lastPromptText = null
            sshLifecycle.userCancelled = false
            onThinkingTokens(null)
            history.flushStreamingBuffer()
            if (getState() == SessionState.Working) onStateChange(SessionState.Running)
        }
    }

    /** Run a code review (`review/start`) on the current thread. Like a turn:
     *  review/start acks fast, the review streams as review-mode items, and the
     *  turn completes via turn/completed. [baseBranch] null/blank → uncommitted
     *  changes. Separate from [runTurn] on purpose — it's a distinct operation,
     *  not a chat prompt, and reusing the (tested) turn body would entangle the
     *  two; the lifecycle (ensureReady → rpc → await turnDone) is mirrored. */
    suspend fun runReview(baseBranch: String?): Boolean = withContext(Dispatchers.IO) {
        val client = sshLifecycle.liveClient()
        if (client == null || !client.isConnected) {
            android.util.Log.w(tag, "runReview ABORT: transport down")
            onStateChange(SessionState.Failed("disconnected"))
            return@withContext true
        }
        onStateChange(SessionState.Working)
        // Same stale-cancel re-arm as runTurn.
        sshLifecycle.userCancelled = false
        try {
            if (!ensureReady()) return@withContext false
            val tid = threadId ?: run { broken = true; return@withContext false }
            val done = CompletableDeferred<Boolean>()
            turnDone = done
            turnIn = 0; turnOut = 0; turnCached = 0; turnReasoning = 0; turnDurationMs = null
            val reqId = reqCounter.incrementAndGet()
            val resp = rpc(
                reqId,
                CodexAppServerWire.encodeReviewStart(reqId, tid, baseBranch, "inline"),
                timeoutMs = 30_000,
            )
            if (resp == null) {
                android.util.Log.w(tag, "review/start failed or timed out — marking disconnected")
                teardownProcess()
                onStateChange(SessionState.Failed("disconnected"))
                return@withContext true
            }
            SilentlyTry.fired(tag, "read review turn id") {
                activeTurnId = resp["turn"]?.jsonObject?.str("id") ?: activeTurnId
            }
            android.util.Log.d(tag, "review started thread=$tid base=${baseBranch ?: "<uncommitted>"} turn=$activeTurnId")
            val completed = withTimeoutOrNull(TURN_TIMEOUT_MS) { done.await() }
            if (completed == null && !sshLifecycle.userCancelled) {
                android.util.Log.w(tag, "review timed out — interrupting")
                interrupt()
                history.emitMsg(AgentMessage.Error(UUID.randomUUID().toString(), "codex review timed out"))
            } else if (completed != null && !completed && !sshLifecycle.userCancelled) {
                android.util.Log.w(tag, "app-server died mid-review — marking disconnected")
                onStateChange(SessionState.Failed("disconnected"))
                return@withContext true
            }
            true
        } finally {
            turnDone = null
            activeTurnId = null
            sshLifecycle.userCancelled = false
            onThinkingTokens(null)
            history.flushStreamingBuffer()
            if (getState() == SessionState.Working) onStateChange(SessionState.Running)
        }
    }

    /** Process + handshake + thread open. True when a turn can be sent. */
    private suspend fun ensureReady(): Boolean {
        val authPrep = getAuthPrep()
        if (procAlive && launchedAuthPrep == authPrep && threadId != null) return true
        // ⛔ A COLD START IS NOT "THE AGENT IS THINKING".
        //
        // Launching app-server and resuming a thread takes real time, and
        // until it finishes codex has not even RECEIVED the prompt.
        // MEASURED in the user rollout (2026-08-27): the gap between
        // task_started and the prompt being recorded was +7.85 s on the
        // first turn of a resumed thread, against +0.04..0.36 s once warm.
        // On screen those seconds were an ordinary working spinner, which
        // is exactly how long a person waits before pressing Stop — and a
        // Stop inside this window used to eat the message (see the
        // interrupted-with-no-tokens branch in the turn/completed handler).
        // One honest row costs nothing and names the wait.
        history.emitMsg(
            CodexMessageParser.note(
                if (getResumeId() != null) "starting codex · resuming session"
                else "starting codex",
                tone = AgentMessage.EventNote.Tone.INFO,
            ),
        )
        if (procAlive) android.util.Log.d(tag, "auth prep changed → restarting app-server")
        teardownProcess()

        val client = sshLifecycle.liveClient() ?: return false
        try {
            // autoExpand: the long-lived app-server JSON-RPC channel is read
            // continuously; protect it from receive-window starvation under
            // shared-transport contention (see [startStreamSession]).
            val sess = client.startStreamSession()
            // stderr DROPPED — app-server logs there and any line would
            // corrupt the stdout JSONL framing.
            val cmd = sess.exec(loginShell(authPrep + "codex app-server 2>/dev/null"))
            procSession = sess
            procCmd = cmd
            procAlive = true
            launchedAuthPrep = authPrep
            startReader(cmd)
        } catch (t: Throwable) {
            android.util.Log.w(tag, "app-server launch failed: ${t.message} — falling back to exec", t)
            broken = true
            teardownProcess()
            return false
        }

        // initialize → initialized
        val initId = reqCounter.incrementAndGet()
        val init = rpc(
            initId,
            CodexAppServerWire.encodeInitialize(initId, appVersion()),
            timeoutMs = 20_000,
        )
        if (init == null) {
            android.util.Log.w(tag, "initialize failed — codex too old for app-server? falling back to exec")
            broken = true
            teardownProcess()
            return false
        }
        writeLine(CodexAppServerWire.encodeInitialized())

        // thread/start | thread/resume
        val rid = getResumeId()
        val threadReqId = reqCounter.incrementAndGet()
        val resp = rpc(
            threadReqId,
            if (rid != null) {
                CodexAppServerWire.encodeThreadResume(
                    threadReqId, rid,
                    model = getModelOverride()?.takeIf { it.isNotBlank() },
                    cwd = cwdSnapshot(),
                    approval = getApprovalMode(),
                )
            } else {
                CodexAppServerWire.encodeThreadStart(
                    threadReqId,
                    model = getModelOverride()?.takeIf { it.isNotBlank() },
                    cwd = cwdSnapshot(),
                    approval = getApprovalMode(),
                )
            },
            timeoutMs = 30_000,
        )
        val newThreadId = resp?.let {
            SilentlyTry.logged(tag, "read thread id") { it["thread"]?.jsonObject?.str("id") }
        }
        if (newThreadId.isNullOrBlank()) {
            android.util.Log.w(
                tag,
                "thread/${if (rid != null) "resume" else "start"} failed (rid=$rid) — falling back to exec",
            )
            broken = true
            teardownProcess()
            return false
        }
        threadId = newThreadId
        if (rid == null) setResumeId(newThreadId)
        android.util.Log.d(tag, "thread ready id=$newThreadId resumed=${rid != null}")
        return true
    }

    /** Send one request and await its JSON-RPC response. Null on write
     *  failure, timeout, or an error response (logged). [id] MUST be the
     *  exact id stamped into [line] — passed explicitly, a counter read
     *  here would race a concurrent interrupt's increment. */
    private suspend fun rpc(id: Long, line: String, timeoutMs: Long): JsonObject? {
        val deferred = CompletableDeferred<JsonObject?>()
        pendingResponses[id] = deferred
        if (!writeLine(line)) {
            pendingResponses.remove(id)
            return null
        }
        val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingResponses.remove(id)
        return resp
    }

    private fun startReader(cmd: Session.Command) {
        readerJob = scope.launch {
            try {
                BufferedReader(InputStreamReader(cmd.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        when (val msg = CodexAppServerWire.parseLine(line)) {
                            is CodexAppServerWire.Incoming.Response -> {
                                val id = msg.id
                                if (id != null) {
                                    if (msg.error != null) {
                                        android.util.Log.w(tag, "rpc error for #$id: ${msg.error.toString().take(200)}")
                                        pendingResponses.remove(id)?.complete(null)
                                    } else {
                                        pendingResponses.remove(id)?.complete(msg.result ?: JsonObject(emptyMap()))
                                    }
                                }
                            }
                            is CodexAppServerWire.Incoming.ServerReq -> handleServerRequest(msg)
                            is CodexAppServerWire.Incoming.Notification -> handleNotification(msg.method, msg.params)
                            null -> android.util.Log.d(tag, "non-rpc stdout: ${line.take(160)}")
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(tag, "reader died: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                android.util.Log.w(tag, "reader EOF — app-server gone")
                procAlive = false
                threadId = null
                pendingServerReqs.keys.toList().forEach { retireServerReq(it) }
                pendingResponses.values.forEach { it.complete(null) }
                pendingResponses.clear()
                turnDone?.complete(false)
            }
        }
    }

    private fun handleNotification(method: String, params: JsonObject) {
        when (method) {
            "item/agentMessage/delta" -> {
                val turnId = params.str("turnId") ?: return
                val itemId = params.str("itemId") ?: return
                val delta = params.str("delta") ?: return
                val key = "codexapp_${turnId}_$itemId"
                val buf = synchronized(deltaBuffers) {
                    deltaBuffers.getOrPut(key) { StringBuilder() }.append(delta)
                }
                history.emitMsg(AgentMessage.AssistantText(key, buf.toString()))
            }
            "item/started", "item/completed" -> {
                val item = SilentlyTry.logged(tag, "notif item obj") { params["item"]?.jsonObject } ?: return
                val turnId = params.str("turnId") ?: activeTurnId ?: "t"
                if (method == "item/completed") {
                    synchronized(deltaBuffers) {
                        deltaBuffers.remove("codexapp_${turnId}_${item.str("id")}")
                    }
                }
                for (m in CodexAppServerEvents.mapItem(item, started = method == "item/started", turnId = turnId)) {
                    history.emitMsg(m)
                }
            }
            "turn/started" -> {
                SilentlyTry.fired(tag, "turn/started id") {
                    activeTurnId = params["turn"]?.jsonObject?.str("id") ?: activeTurnId
                }
            }
            "turn/completed" -> {
                val turn = SilentlyTry.logged(tag, "turn obj") { params["turn"]?.jsonObject }
                val status = turn?.str("status").orEmpty()
                turnDurationMs = turn?.str("durationMs")?.toLongOrNull()
                // ⛔ AN INTERRUPT THAT LANDED BEFORE THE MODEL RAN = THE PROMPT
                // NEVER EXISTED ANYWHERE, AND WE ARE THE ONLY ONES WHO HAVE IT.
                //
                // MEASURED in the user's own rollout (2026-08-27, codex thread
                // 01a03e35): `task_started` at 21:35:44.969, then straight to
                // `turn_aborted reason=interrupted` at 21:35:51.190 — 6.2 s, and
                // NO user_message record for that turn at all. Five
                // `task_started` against four `user_message` in the file: one
                // prompt is simply missing. Cold start costs 6-8 s before codex
                // records the prompt (measured: +7.85 s on the first turn of a
                // resumed thread, +0.04..0.36 s once warm), which is exactly how
                // long a person waits on a silent spinner before hitting Stop.
                //
                // runTurn's "reconnect, don't re-send" reasoning ("the
                // app-server acked turn/start, so it HAS the prompt, recoverable
                // by resume") does not hold here — the rollout proves it never
                // persisted it. Zero token accounting is the discriminator: a
                // real mid-answer Stop always has tokens; this has none.
                //
                // Hands it to the SAME undelivered path a dead transport uses,
                // which the VM turns back into the user's words (draft / retry).
                // NOT a re-send: auto-redelivery into a live turn is what
                // produced duplicate prompts once already (see the turnSeq
                // generation-fence note, 2026-07-31).
                if (status == "interrupted" && turnIn == 0L && turnOut == 0L) {
                    val lost = lastPromptText
                    android.util.Log.w(
                        tag,
                        "turn interrupted with no token usage — codex never took the prompt " +
                            "(${lost?.length ?: 0}B); handing it back instead of losing it",
                    )
                    if (!lost.isNullOrBlank()) {
                        onPromptUndelivered(lost)
                        history.emitMsg(
                            CodexMessageParser.note(
                                "prompt not delivered — stopped before codex received it",
                                tone = AgentMessage.EventNote.Tone.WARN,
                            ),
                        )
                    }
                }
                if (status == "failed") {
                    val errMsg = SilentlyTry.logged(tag, "turn error") {
                        turn?.get("error")?.jsonObject?.str("message")
                    } ?: "turn failed"
                    history.emitMsg(AgentMessage.Error(UUID.randomUUID().toString(), errMsg))
                } else {
                    emitTurnUsageNote(status)
                }
                turnDone?.complete(true)
            }
            "thread/tokenUsage/updated" -> {
                SilentlyTry.fired(tag, "token usage") {
                    val last = params["tokenUsage"]?.jsonObject?.get("last")?.jsonObject ?: return@fired
                    turnIn += last.long("inputTokens") ?: 0
                    turnOut += last.long("outputTokens") ?: 0
                    turnCached += last.long("cachedInputTokens") ?: 0
                    turnReasoning += last.long("reasoningOutputTokens") ?: 0
                    if (turnReasoning > 0) onThinkingTokens(turnReasoning)
                }
            }
            "turn/plan/updated" -> {
                val turnId = params.str("turnId") ?: activeTurnId ?: "t"
                for (m in CodexAppServerEvents.mapPlanUpdate(params, turnId)) history.emitMsg(m)
            }
            "error" -> for (m in CodexAppServerEvents.mapError(params)) history.emitMsg(m)
            "model/rerouted" -> for (m in CodexAppServerEvents.mapModelRerouted(params)) history.emitMsg(m)
            // Deprecated duplicate of the contextCompaction item lifecycle.
            "thread/compacted" -> history.emitMsg(
                AgentMessage.System(
                    id = "codexapp-compact-${params.str("turnId") ?: "x"}",
                    subtype = "compact_done",
                    raw = "✻ Context compacted",
                )
            )
            // The server resolved one of its own requests elsewhere (e.g.
            // turn aborted) — freeze the card so taps don't write into a void.
            "serverRequest/resolved" -> params.str("requestId")?.let { retireServerReq(it) }

            // Per-chunk delta spam / bookkeeping with no chat-row value.
            // The completed items and tailored branches above carry it all.
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta",
            "item/reasoning/summaryPartAdded", "item/commandExecution/outputDelta",
            "item/fileChange/outputDelta", "item/fileChange/patchUpdated",
            "item/plan/delta", "turn/diff/updated", "turn/moderationMetadata",
            "rawResponseItem/completed", "thread/started", "thread/status/changed",
            "thread/name/updated", "thread/settings/updated",
            "account/rateLimits/updated", "account/updated",
            "mcpServer/startupStatus/updated", "fs/changed", "skills/changed",
            "item/autoApprovalReview/started", "item/autoApprovalReview/completed",
            "hook/started", "hook/completed",
            -> Unit

            "warning", "guardianWarning", "configWarning", "deprecationNotice" -> {
                val text = params.str("summary") ?: params.str("message") ?: method
                history.emitMsg(CodexMessageParser.note(
                    text.take(140),
                    detail = params.str("details"),
                    tone = AgentMessage.EventNote.Tone.WARN,
                ))
            }

            // UNKNOWN notification — surface generically, never swallow.
            else -> history.emitMsg(CodexMessageParser.note(
                CodexMessageParser.genericLabel(method.replace('/', '_'), params),
                detail = CodexMessageParser.genericDetail(params),
            ))
        }
    }

    private fun handleServerRequest(req: CodexAppServerWire.Incoming.ServerReq) {
        val key = (req.id as? JsonPrimitive)?.contentOrNull ?: req.id.toString()
        android.util.Log.d(tag, "server request ${req.method} id=$key")
        when (req.method) {
            "item/commandExecution/requestApproval" -> {
                pendingServerReqs[key] = PendingServerReq(req.id, PendingServerReq.Kind.EXEC_APPROVAL)
                val command = req.params.str("command").orEmpty()
                val reason = req.params.str("reason")
                history.emitMsg(
                    AgentMessage.PermissionRequest(
                        id = "perm-codexapp-$key",
                        requestId = key,
                        toolName = "exec",
                        description = command.take(200).ifBlank { "run a command" },
                        input = listOfNotNull(command.takeIf { it.isNotBlank() }, reason)
                            .joinToString("\n"),
                        raw = req.params.toString(),
                        canAllowSession = true,   // codex supports acceptForSession
                    )
                )
            }
            "item/fileChange/requestApproval" -> {
                pendingServerReqs[key] = PendingServerReq(req.id, PendingServerReq.Kind.FILE_APPROVAL)
                val reason = req.params.str("reason")
                val grantRoot = req.params.str("grantRoot")
                history.emitMsg(
                    AgentMessage.PermissionRequest(
                        id = "perm-codexapp-$key",
                        requestId = key,
                        toolName = "file change",
                        description = reason ?: grantRoot?.let { "write under $it" } ?: "apply file changes",
                        input = listOfNotNull(reason, grantRoot?.let { "grant root: $it" })
                            .joinToString("\n"),
                        raw = req.params.toString(),
                        canAllowSession = true,   // codex supports acceptForSession
                    )
                )
            }
            "item/tool/requestUserInput" -> {
                val parsed = CodexAppServerWire.parseUserInputQuestions(req.params)
                val renderable = parsed.filter { it.second.options.isNotEmpty() }
                if (renderable.isEmpty()) {
                    // Free-form-only request we can't render (no option
                    // chips, no text input on the card) — answer empty so
                    // the turn proceeds instead of hanging.
                    writeLine(CodexAppServerWire.encodeUserInputAnswers(
                        req.id, parsed.associate { it.first to emptyList() },
                    ))
                    return
                }
                pendingServerReqs[key] = PendingServerReq(
                    req.id, PendingServerReq.Kind.USER_INPUT,
                    questionIds = parsed.map { it.first to it.second.options.isNotEmpty() },
                )
                history.emitMsg(
                    AgentMessage.AskUserQuestion(
                        id = "ask-codexapp-$key",
                        requestId = key,
                        questions = renderable.map { it.second },
                    )
                )
            }
            // THE AGENT ASKING FOR A WIDER SANDBOX. Verified contract
            // (generate-ts, 0.144.4): params carry `permissions{network,
            // fileSystem}` plus a `reason`, and the RESPONSE is a granted profile
            // with a scope of "turn" or "session" - not an accept/decline.
            //
            // This used to fall into the blanket -32601 below, which the CLI reads
            // as "this client cannot do that": the agent quietly lost a capability
            // it had asked for and the user never learned it had been asked. Extra
            // network or filesystem reach is the user's call, so it is a card; the
            // card's three answers map exactly onto the protocol's own vocabulary
            // (deny = empty profile, once = scope "turn", session = scope
            // "session").
            "item/permissions/requestApproval" -> {
                pendingServerReqs[key] = PendingServerReq(
                    req.id, PendingServerReq.Kind.PERMISSIONS_PROFILE,
                    requestedProfile = req.params["permissions"] as? kotlinx.serialization.json.JsonObject,
                )
                val reason = req.params.str("reason")
                val profile = req.params["permissions"] as? kotlinx.serialization.json.JsonObject
                val wants = buildList {
                    if (profile?.get("network") != null &&
                        profile["network"] != kotlinx.serialization.json.JsonNull
                    ) add("network access")
                    if (profile?.get("fileSystem") != null &&
                        profile["fileSystem"] != kotlinx.serialization.json.JsonNull
                    ) add("more of the filesystem")
                }.ifEmpty { listOf("wider sandbox permissions") }
                history.emitMsg(
                    AgentMessage.PermissionRequest(
                        id = "perm-codexperm-$key",
                        requestId = key,
                        toolName = "sandbox",
                        description = reason ?: "the agent is asking for ${wants.joinToString(" and ")}",
                        input = profile?.toString().orEmpty(),
                        raw = req.params.toString(),
                        // "session" is a real scope in this protocol, not an
                        // approximation of one.
                        canAllowSession = true,
                    )
                )
            }
            // MCP elicitation needs a form/url surface we don't have —
            // decline per protocol instead of hanging the turn.
            "mcpServer/elicitation/request" ->
                writeLine(CodexAppServerWire.encodeElicitationDecline(req.id))
            // Anything else (permissions profiles, dynamic tool calls,
            // attestation, token refresh) — refuse explicitly; an
            // unanswered server request would hang the turn forever.
            else -> writeLine(CodexAppServerWire.encodeErrorResponse(
                req.id, "Not supported by this client (${req.method})",
            ))
        }
    }

    private fun retireServerReq(key: String) {
        val req = pendingServerReqs.remove(key) ?: return
        when (req.kind) {
            PendingServerReq.Kind.USER_INPUT -> history.resolveQuestion(key, emptyMap())
            // Retiring an unanswered sandbox request = the narrowest possible
            // answer, never silence: an unanswered server request hangs the turn.
            PendingServerReq.Kind.PERMISSIONS_PROFILE ->
                writeLine(CodexAppServerWire.encodePermissionsGrant(req.idElement, null, "turn"))
            else -> history.resolvePermission(key, AgentMessage.PermissionRequest.Resolution.DENIED)
        }
    }

    /** Allow/deny a live approval card. True when this channel owned the
     *  request. IO-hopped: card taps arrive on MAIN, socket writes there
     *  throw NetworkOnMainThreadException. */
    suspend fun respondPermission(requestId: String, decision: PermissionDecision): Boolean =
        withContext(Dispatchers.IO) {
            val req = pendingServerReqs.remove(requestId) ?: return@withContext false
            // A sandbox-widening request is answered with a GRANTED PROFILE, not
            // with accept/decline - deny means an empty profile. Same three
            // buttons, different protocol underneath.
            if (req.kind == PendingServerReq.Kind.PERMISSIONS_PROFILE) {
                val granted = if (decision == PermissionDecision.DENY) null else req.requestedProfile
                val scope = if (decision == PermissionDecision.ALLOW_SESSION) "session" else "turn"
                writeLine(CodexAppServerWire.encodePermissionsGrant(req.idElement, granted, scope))
                return@withContext true
            }
            val wire = when (decision) {
                PermissionDecision.DENY -> "decline"
                PermissionDecision.ALLOW_ONCE -> "accept"
                PermissionDecision.ALLOW_SESSION -> "acceptForSession"
            }
            writeLine(CodexAppServerWire.encodeApprovalDecision(req.idElement, wire))
            true
        }

    /** Answer a requestUserInput card: question index → chosen labels.
     *  Questions the card didn't render (free-form) get empty answers. */
    suspend fun respondQuestion(requestId: String, answers: Map<Int, List<String>>): Boolean =
        withContext(Dispatchers.IO) {
            val req = pendingServerReqs.remove(requestId) ?: return@withContext false
            // The card rendered only option-questions, in original order —
            // re-derive the qid mapping the same way.
            val renderedQids = req.questionIds.filter { it.second }.map { it.first }
            val byQid = LinkedHashMap<String, List<String>>()
            req.questionIds.forEach { (qid, hasOptions) ->
                if (!hasOptions) byQid[qid] = emptyList()
            }
            renderedQids.forEachIndexed { idx, qid ->
                byQid[qid] = answers[idx].orEmpty()
            }
            writeLine(CodexAppServerWire.encodeUserInputAnswers(req.idElement, byQid))
            true
        }

    /** Real protocol interrupt. Launched on IO scope (Stop button = MAIN). */
    fun interrupt() {
        val tid = threadId ?: return
        val turn = activeTurnId ?: return
        scope.launch {
            writeLine(CodexAppServerWire.encodeTurnInterrupt(reqCounter.incrementAndGet(), tid, turn))
        }
    }

    /** Stop = interrupt + escalation to a process kill after a grace
     *  window (process restarts with thread/resume on the next send). */
    fun cancelTurn() {
        sshLifecycle.userCancelled = true
        interrupt()
        // ⚠ FENCE THE ESCALATION TO THE TURN STOP WAS AIMED AT.
        //
        // This used to escalate on `getState() == Working && procAlive` — "the
        // session is busy", not "the turn I was told to stop is still running".
        // Four seconds is long enough for the user to read the half-answer, hit
        // Stop and type the correction, and their NEW turn is what the timer
        // then killed: measured on the user's own device (2026-08-06) — Stop at
        // 13:41:43, a new turn started 13:41:44.8, "killing app-server" at
        // 13:41:47.5, app-server dead mid-turn. The Claude path was fenced for
        // exactly this on 2026-07-31 (`shouldEscalateKill`); the fix was never
        // carried across to Codex or Gemini, which is why Stop still eats the
        // next turn here.
        val target = turnDone
        scope.launch {
            kotlinx.coroutines.delay(4_000)
            val escalate = if (target != null) {
                AgentSessionPersistentStream.shouldEscalateKill(
                    sameTurn = turnDone === target,
                    victimDone = target.isCompleted,
                    working = getState() == SessionState.Working,
                    alive = procAlive,
                )
            } else {
                // Stop landed before any turn had a token to fence on — keep the
                // old session-level guarantee so Stop still un-sticks the UI.
                getState() == SessionState.Working && procAlive
            }
            if (!escalate) {
                android.util.Log.d(tag, "stop escalation skipped — the stopped turn is already over")
                return@launch
            }
            android.util.Log.w(tag, "interrupt not honored in 4s — killing app-server")
            teardownProcess()
            target?.complete(true)
            if (getState() == SessionState.Working) onStateChange(SessionState.Running)
        }
    }

    private fun emitTurnUsageNote(status: String) {
        val parts = listOfNotNull(
            turnIn.takeIf { it > 0 }?.let { "in ${CodexMessageParser.k(it + turnCached)}" },
            turnOut.takeIf { it > 0 }?.let { "out ${CodexMessageParser.k(it)}" },
            turnDurationMs?.let { "${it / 1000}s" },
        )
        if (status == "interrupted") {
            history.emitMsg(CodexMessageParser.note(
                "turn interrupted", tone = AgentMessage.EventNote.Tone.WARN,
            ))
        }
        if (parts.isNotEmpty()) {
            history.emitMsg(CodexMessageParser.note("tokens · ${parts.joinToString(" · ")}"))
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
            android.util.Log.w(
                tag,
                "stdin write failed: ${t.javaClass.name}: ${t.message} " +
                    "chanOpen=${procSession?.isOpen} connected=${sshLifecycle.liveClient()?.isConnected} alive=$procAlive",
                t,
            )
            procAlive = false
            false
        }
    }

    fun teardownProcess() {
        readerJob?.cancel()
        readerJob = null
        procCmd?.let { cmd ->
            SilentlyTry.fired(tag, "close app-server stdin") { cmd.outputStream.close() }
        }
        procSession?.let { s ->
            SilentlyTry.fired(tag, "close app-server channel") { s.close() }
        }
        procCmd = null
        procSession = null
        procAlive = false
        launchedAuthPrep = null
        threadId = null
        activeTurnId = null
        synchronized(deltaBuffers) { deltaBuffers.clear() }
        pendingServerReqs.clear()
    }

    private fun appVersion(): String =
        SilentlyTry.logged(tag, "read app version") {
            ai.eight24family.conch.BuildConfig.VERSION_NAME
        } ?: "0"

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    companion object {
        private const val TURN_TIMEOUT_MS = 15L * 60 * 1000
    }
}
