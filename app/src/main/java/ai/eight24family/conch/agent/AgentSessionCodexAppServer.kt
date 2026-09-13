package ai.eight24family.conch.agent

import ai.eight24family.conch.ssh.startStreamSession

import ai.eight24family.conch.agent.codex.CodexAppServerEvents
import ai.eight24family.conch.agent.codex.CodexAppServerWire
import ai.eight24family.conch.agent.codex.CodexAppServerWire.str
import ai.eight24family.conch.agent.codex.CodexMessageParser
import ai.eight24family.conch.agent.codex.CodexThreadLock
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    /** Raised once when the relay had to end a terminal's copy of this
     *  session — the UI turns it into the one-time "here is the clean path"
     *  dialog. See [HandoffAdvice]. */
    private val onHandoffAdvice: (HandoffAdvice) -> Unit = {},
) {
    private val tag = "Conch-CodexApp"

    /**
     * ON — the terminal half is measured (2026-09-12).
     *
     * `codex --remote ws://127.0.0.1:<port>` really does attach: with the TUI
     * up, `ss` showed an ESTABLISHED pair against the brain, and the rollout fd
     * was held by the BRAIN's own pid (90693), not by the TUI. That is the
     * proof the whole design rests on — clients of one app-server do not
     * contend for the writer; only a SECOND app-server does, which is exactly
     * what a bare `codex` starts and what this hook prevents.
     *
     * The earlier "it never attaches" reading was a measurement error: the TUI
     * stalls on its own interactive update prompt, and `ss` was sampled after
     * the session had already been killed.
     */
    private val TERMINAL_HOOK_ENABLED = true

    @Volatile private var procSession: Session? = null
    @Volatile private var procCmd: Session.Command? = null
    @Volatile private var procAlive = false
    /** authPrep the live process was launched with — the ONLY launch
     *  param that still forces a restart (everything else is per-turn). */
    @Volatile private var launchedAuthPrep: String? = null
    /**
     * Set when [ensureReady] found the thread REFUSED because another process
     * holds codex's writer lock, and could not clear it (the holder is a
     * person's terminal). Read and cleared by the turn entry points.
     *
     * It deliberately does NOT set [broken]. `broken` means "this codex is
     * too old for app-server, ride `codex exec` forever", and the exec path
     * reads any resume refusal as a DEAD session: it drops the resume id and
     * re-sends the prompt into a brand-new empty thread. A busy thread going
     * down that road is exactly the doubling bug (2026-09-09). Busy is
     * temporary - the next send must try the SAME thread again.
     */
    @Volatile private var threadBusy: List<SessionHolder.Holder>? = null

    /**
     * What this server's codex can do about sharing one app-server (see
     * [ai.eight24family.conch.agent.codex.CodexDaemon]). Probed once per live
     * process, kept for the advice dialog even when the daemon is not usable —
     * "your server runs 0.80.0" is the difference between an instruction he can
     * follow and a shrug.
     */
    @Volatile private var daemonSupport: ai.eight24family.conch.agent.codex.CodexDaemon.Support? = null

    /** Did the LAST launch actually ride the daemon? Drives the one retry when
     *  a proxied channel fails to hand back an `initialize` — a shared brain
     *  that will not answer must degrade to the private one, not to nothing. */
    @Volatile private var launchedViaDaemon = false

    /** Set when a proxied launch failed its handshake: stop trying for the
     *  life of this session object rather than alternating forever. */
    @Volatile private var daemonRefused = false

    /**
     * THE SHARED BRAIN'S TRANSPORT, when this chat rides one.
     *
     * A direct-tcpip channel over the SSH connection we already hold, to
     * `127.0.0.1:<port>` on the server, carrying a WebSocket that carries the
     * ordinary app-server JSON-RPC. MEASURED 2026-09-12: two clients of one
     * such server resume the SAME thread, with no writer conflict — which stdio
     * could never do, and which is the entire reason this exists
     * ([ai.eight24family.conch.agent.codex.CodexWsBrain]).
     */
    @Volatile private var wsSession: Session? = null
    @Volatile private var wsCmd: Session.Command? = null
    @Volatile private var wsOut: java.io.OutputStream? = null
    @Volatile private var wsPort: Int? = null

    /** True while the WebSocket is the live channel: writes frame instead of
     *  writing a line, teardown closes a channel instead of a process, and the
     *  relay must never reap the server on the other end of it. */
    private val onSharedBrain: Boolean get() = wsCmd != null

    /**
     * Pending "hand the thread back" timer. Cancelled when a turn starts,
     * re-armed when one finishes. See [armIdleRelease].
     */
    private var idleReleaseJob: Job? = null

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

    /** The JSON-RPC `error` a request came back with, kept until its own
     *  [rpcDetailed] collects it. A refusal used to be logged and thrown away
     *  (`complete(null)`), so the code deciding what to do next could not tell
     *  "someone else has this thread open" from "this thread is gone" — and
     *  guessed the destructive one. Keyed by request id, never a shared field:
     *  a turn and an interrupt are in flight concurrently. */
    private val rpcErrors = java.util.concurrent.ConcurrentHashMap<Long, String>()

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
            // The phone's own brain must be SERVING before codex dials
            // loopback — the channel twin of the exec path's ensure. First,
            // because it is the slow half (weight load), and idempotent when
            // the right model is already warm. On this row the channel is
            // ALWAYS loopback-bound (see the launch), so with no explicit pick
            // yet the first downloaded model serves rather than nothing.
            // A CLOUD model on the phone needs codex's own sign-in. Without it
            // codex retries api.openai.com forever and the chat shows a lying
            // "waiting for network" (owner picked GPT-5.6-Luna, 2026-09-01,
            // NO_AUTH measured). Say the real thing once and hand the prompt
            // back instead of burning retries.
            if (server.id == ai.eight24family.conch.linux.LinuxSsh.SERVER_ID &&
                !wantsLocalProvider() && !cloudAuthPresent()
            ) {
                history.emitMsg(
                    AgentMessage.Error(
                        UUID.randomUUID().toString(),
                        "Codex isn't signed in on this device — open the agents panel to " +
                            "log in, or pick one of this device's local models",
                    ),
                )
                onPromptUndelivered(text)
                onStateChange(SessionState.Running)
                return@withContext true
            }
            if (server.id == ai.eight24family.conch.linux.LinuxSsh.SERVER_ID && wantsLocalProvider()) {
                val m = getModelOverride()
                    ?.takeIf { it.startsWith(ai.eight24family.conch.linux.LocalLlm.MODEL_ARG_PREFIX) }
                    ?.removePrefix(ai.eight24family.conch.linux.LocalLlm.MODEL_ARG_PREFIX)
                    ?.let { ai.eight24family.conch.linux.LocalLlm.byId(it) }
                    ?: ai.eight24family.conch.linux.LocalLlm.CATALOG.firstOrNull {
                        // ⛔ NOT AN EMBEDDER. "No pick yet, so serve the first
                        // ready model" would hand codex a model with no chat
                        // head the moment the search model is downloaded, and
                        // the turn would answer nothing at all.
                        it.isBrain &&
                            ai.eight24family.conch.linux.LocalLlm.status(it) is
                            ai.eight24family.conch.linux.LocalLlm.Status.Ready
                    }
                m?.let {
                    SilentlyTry.logged(tag, "start local engine") {
                        ai.eight24family.conch.linux.LocalLlmEngine.start(
                            it,
                            // The chat's effort pick doubles as the local
                            // thinking switch — launch state for the engine.
                            thinking = getReasoningOverride() == ai.eight24family.conch.linux.LocalLlm.EFFORT_THINKING,
                        )
                    }
                }
            }
            // This turn owns the thread until it ends; the release timer is
            // re-armed from the `finally` below.
            idleReleaseJob?.cancel()
            if (!ensureReady(userInitiated = true)) {
                // Returning false here reruns the prompt through the one-shot
                // `codex exec` path, and THAT path reads a resume refusal as a
                // dead session: it drops the resume id and answers in a new
                // empty thread. For a thread that is merely BUSY that is the
                // doubling bug, so this branch stops before it.
                //
                // The prompt is handed back undelivered (the ViewModel
                // re-buffers it, so freeing the lock and sending again costs
                // the user nothing) and the chat keeps its resume id, so the
                // next attempt resumes the REAL session.
                threadBusy?.let { holders ->
                    threadBusy = null
                    // Stable id: the row IS the take-over button (see
                    // CodexThreadLock.TAKEOVER_MARKER_ID), and hitting the
                    // lock twice cannot stack two of them.
                    history.emitMsg(
                        AgentMessage.EventNote(
                            id = CodexThreadLock.TAKEOVER_MARKER_ID,
                            label = CodexThreadLock.ttyHolderNote(holders),
                            tone = AgentMessage.EventNote.Tone.WARN,
                        ),
                    )
                    onPromptUndelivered(text)
                    // ⛔ NOT Failed — a HELD session is not a transport failure.
                    //
                    // Failed here is caught by the VM's reconnect rescue
                    // (state==Failed → rescueIfPoolLive → retry → re-deliver the
                    // parked prompt → ensureReady → same writer conflict →
                    // Failed again), a 3 s loop that relaunched `codex
                    // app-server` forever, spamming "starting codex · resuming
                    // session" and "remoteControl status changed" and burying the
                    // one takeover row under them (owner 2026-09-12). It never
                    // reached turn/start, so it burned no model quota — but it
                    // looked exactly like the app spending it, and it made the
                    // handoff impossible to complete. The session is live and
                    // resumable; it is waiting for ONE deliberate takeover tap,
                    // exactly like Claude's held-by-tty branch
                    // (AgentSessionPersistentStream.runTurn). Park at Running so
                    // nothing auto-retries and the chat stays interactive; the
                    // prompt is back in the composer for the post-takeover send.
                    onStateChange(SessionState.Running)
                    return@withContext true
                }
                return@withContext false
            }
            val tid = threadId ?: run { broken = true; return@withContext false }
            val done = CompletableDeferred<Boolean>()
            turnDone = done
            turnIn = 0; turnOut = 0; turnCached = 0; turnReasoning = 0; turnDurationMs = null
            // Echo a model/effort change at turn start (parity with Claude). Only
            // on CHANGE — the first turn just records the baseline.
            val curModel = getModelOverride()?.takeIf { it.isNotBlank() }
            val curEffort = getReasoningOverride()?.takeIf { it.isNotBlank() }
            if (lastEchoModel != null && curModel != null && curModel != lastEchoModel)
                history.emitMsg(
                    CodexMessageParser.note(
                        "model · ${ai.eight24family.conch.linux.LocalLlm.cliModelName(curModel)}",
                        tone = AgentMessage.EventNote.Tone.INFO,
                    ),
                )
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
                    model = getModelOverride()?.takeIf { it.isNotBlank() }
                        ?.let(ai.eight24family.conch.linux.LocalLlm::cliModelName),
                    // Local levels (instant/thinking) are the ENGINE's launch
                    // switch, not a codex effort — codex validates its enum and
                    // would refuse the turn.
                    effort = getReasoningOverride()?.takeIf {
                        it.isNotBlank() && !ai.eight24family.conch.linux.LocalLlm.isLocalEffort(it)
                    },
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
            // The operation is over: start counting down to handing the
            // thread back, so the next client anywhere can take it.
            armIdleRelease()
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
            // A review is an operation the owner just asked for, so it claims
            // the thread the same way a turn does.
            if (!ensureReady(userInitiated = true)) {
                threadBusy?.let { holders ->
                    threadBusy = null
                    history.emitMsg(
                        AgentMessage.EventNote(
                            id = CodexThreadLock.TAKEOVER_MARKER_ID,
                            label = CodexThreadLock.ttyHolderNote(holders),
                            tone = AgentMessage.EventNote.Tone.WARN,
                        ),
                    )
                    // Held, not Failed — see the matching branch in runTurn:
                    // Failed loops through the VM's reconnect rescue. A review
                    // has no prompt to hand back, but the state rule is the same.
                    onStateChange(SessionState.Running)
                    return@withContext true
                }
                return@withContext false
            }
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
            // The operation is over: start counting down to handing the
            // thread back, so the next client anywhere can take it.
            armIdleRelease()
        }
    }

    /** Cached per channel process: does the phone's codex have its own cloud
     *  sign-in (~/.codex/auth.json)? Cleared on teardown so a fresh login is
     *  picked up by the next launch. */
    @Volatile private var cloudAuthCache: Boolean? = null

    private suspend fun cloudAuthPresent(): Boolean {
        cloudAuthCache?.let { return it }
        val client = sshLifecycle.liveClient() ?: return false
        val present = SilentlyTry.loggedOrElse(tag, "probe codex auth", false) {
            val sess = client.startSession()
            try {
                val cmd = sess.exec("test -f \$HOME/.codex/auth.json && echo YES")
                val out = cmd.inputStream.bufferedReader().readText()
                cmd.join(10, java.util.concurrent.TimeUnit.SECONDS)
                "YES" in out
            } finally {
                SilentlyTry.fired(tag, "close auth probe") { sess.close() }
            }
        }
        cloudAuthCache = present
        return present
    }

    /** True when THIS chat's current model rides the phone's own engine —
     *  decides the provider the channel must be launched with. No pick yet on
     *  the phone's row defaults to local (the first downloaded model serves). */
    private fun wantsLocalProvider(): Boolean {
        if (server.id != ai.eight24family.conch.linux.LinuxSsh.SERVER_ID) return false
        val m = getModelOverride()?.takeIf { it.isNotBlank() } ?: return true
        return m.startsWith(ai.eight24family.conch.linux.LocalLlm.MODEL_ARG_PREFIX)
    }

    /** Provider mode the live process was launched with — switching a phone
     *  chat between a local model and a cloud one (the picker offers both,
     *  2026-09-01) changes launch params, so it restarts the channel like an
     *  auth change does. A cloud pick against the baked loopback provider
     *  dialed 127.0.0.1 with gpt-5 and spun "waiting for network" forever
     *  (owner's screenshot). */
    @Volatile private var launchedLocalProvider: Boolean? = null

    /**
     * Process + handshake + thread open. True when a turn can be sent.
     *
     * [userInitiated] is the RELAY's authority: true only when the owner just
     * sent something into THIS chat (a turn, a review). That send is the claim —
     * it is what licenses ending a copy of the same session held in a terminal
     * on the server. A chat that merely opened, a limit-bar warm-up or any
     * background path passes false and still keeps hands off.
     */
    private suspend fun ensureReady(userInitiated: Boolean = false): Boolean {
        val authPrep = getAuthPrep()
        if (procAlive && launchedAuthPrep == authPrep && threadId != null &&
            launchedLocalProvider == wantsLocalProvider()
        ) return true
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
            // The phone's own model rides through Codex's custom-provider
            // flags, and the app-server carries provider config PER PROCESS —
            // so it is baked into the channel launch. On the phone's row the
            // provider follows the MODEL: local: → the loopback engine, a
            // cloud slug → codex's normal OpenAI provider (the picker offers
            // both since 2026-09-01). "No pick yet" counts as local, so the
            // async seeding race of 2026-08-31 (fast first send dialed
            // api.openai.com into a 401) stays closed; a provider-mode switch
            // restarts the channel via ensureReady's idempotency key.
            val wantsLocal = wantsLocalProvider()
            val localProvider =
                if (wantsLocal) ai.eight24family.conch.agent.codex.CodexSpec.localProviderArgs() else ""
            // The engine authenticates its port now (it is reachable by every
            // app on the device — see LocalApiAccess). The provider config
            // names OPENAI_API_KEY as its `env_key`; without this prefix the
            // whole local-agent path would 401 on its first turn.
            val localKey =
                if (wantsLocal) ai.eight24family.conch.agent.codex.CodexSpec.localKeyEnv() else ""
            // THE SHARED BRAIN, when this server's codex has one.
            //
            // Riding `codex app-server proxy` makes our channel a CLIENT of one
            // daemon instead of a second app-server — which is the only way his
            // terminal (`codex --remote unix://`) and the phone can hold the
            // same thread without one of them being ended. Everything about it
            // is conditional on what the binary says it supports, because his
            // own 824 box runs 0.80.0 and has none of it.
            //
            // ⛔ NOT for the phone's local-model row. Provider flags are baked
            // per app-server PROCESS (that is why they are on this command
            // line); a shared daemon cannot carry a per-chat provider, and
            // handing the loopback engine's chat to a cloud-configured daemon
            // is the 401 of 2026-08-31 all over again.
            // ⛔ THE SHARED BRAIN IS TRIED FIRST, AND ONLY FOR A CLOUD CHAT.
            //
            // One `codex app-server --listen ws://127.0.0.1:<port>` serves every
            // client on the box: this chat through the SSH channel, his terminal
            // through `codex --remote ws://…`. Because they are clients of ONE
            // process, they share the thread instead of fighting over it — so
            // nothing has to be ended, which is what the relay was a workaround
            // for.
            //
            // NOT for the phone's local-model row: provider flags are baked per
            // app-server PROCESS (that is why they are on the command line
            // below), and a shared server cannot carry a per-chat provider —
            // handing the loopback engine's chat to a cloud-configured server is
            // the 401 of 2026-08-31 again.
            val sharedBrain =
                if (wantsLocal || daemonRefused) null
                else SilentlyTry.logged(tag, "ensure shared codex brain") { ensureSharedBrain() }
            if (sharedBrain != null && openSharedBrain(client, sharedBrain.port)) {
                // ⛔ RELEASE THE EXEC SESSION WE NO LONGER NEED. sshd's
                // MaxSessions is 10 by default and the app already keeps
                // several channels per server (listings, usage, the bridge);
                // measured on the owner's box, every exec started failing with
                // "open failed" once they piled up.
                SilentlyTry.fired(tag, "close unused exec session") { sess.close() }
                procSession = null
                procCmd = null
                procAlive = true
                launchedAuthPrep = authPrep
                launchedLocalProvider = wantsLocal
                launchedViaDaemon = true
                wsPort = sharedBrain.port
                android.util.Log.i(tag, "riding the shared codex brain on 127.0.0.1:${sharedBrain.port}")
                // ⛔ WIRE THE TERMINAL NOW, NOT AFTER THE FIRST COLLISION.
                //
                // The brain holds each thread's rollout for as long as it lives
                // (measured: the rollout fd belongs to the app-server pid, and
                // it does not let go when a client disconnects). So a bare
                // `codex` in a terminal cannot open any thread this app has
                // touched — it gets "already has an active writer" and the
                // person is simply stuck. Hooking only after a relay would
                // leave exactly that gap between the first phone turn and the
                // first collision. The script is idempotent and rewrites its
                // own block, so running it per launch costs one exec.
                if (TERMINAL_HOOK_ENABLED) {
                    SilentlyTry.logged(tag, "wire terminal to the brain") {
                        installTerminalHook(sharedBrain.port)
                    }
                }
            } else {
                val launchCmd = "codex app-server$localProvider 2>/dev/null"
                // stderr DROPPED — app-server logs there and any line would
                // corrupt the stdout JSONL framing.
                val cmd = sess.exec(loginShell(authPrep + localKey + launchCmd))
                launchedViaDaemon = false
                procSession = sess
                procCmd = cmd
                procAlive = true
                launchedAuthPrep = authPrep
                launchedLocalProvider = wantsLocal
                startReader(cmd)
            }
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
            if (onSharedBrain) {
                // The brain answered the socket but not the protocol — a half
                // dead server, a version we cannot speak to. Stop sharing, do
                // NOT declare codex too old (that flips `broken`, and the exec
                // path reads a resume refusal as a dead session → the doubling
                // bug of 2026-09-09).
                android.util.Log.w(tag, "shared brain did not initialize — falling back to a private app-server")
                daemonRefused = true
                teardownProcess()
                return ensureReady(userInitiated)
            }
            if (launchedViaDaemon) {
                // The daemon answered `version` but not `initialize` — a stale
                // socket, a half-dead daemon, a version mismatch. That is a
                // reason to stop sharing a brain, NOT a reason to declare codex
                // too old and drop this chat onto the one-shot exec path (which
                // reads a resume refusal as a dead session and doubles it).
                android.util.Log.w(tag, "proxied app-server did not initialize — dropping the daemon path")
                daemonRefused = true
                launchedViaDaemon = false
                teardownProcess()
                return ensureReady(userInitiated)
            }
            android.util.Log.w(tag, "initialize failed — codex too old for app-server? falling back to exec")
            broken = true
            teardownProcess()
            return false
        }
        writeLine(CodexAppServerWire.encodeInitialized())

        // thread/start | thread/resume
        val rid = getResumeId()
        threadBusy = null
        var newThreadId: String?
        var openErr: String?
        openThread(rid).let { newThreadId = it.first; openErr = it.second }

        // "ALREADY HAS AN ACTIVE WRITER" IS BUSY, NOT GONE.
        //
        // codex 0.153 locks a thread to one writer. Read as a dead session,
        // that refusal made the app abandon the real thread and answer in a
        // fresh empty one - the owner's report of a new contextless session
        // instead of a continuation, reproduced on his server 2026-09-09
        // (refusal 18:33:11, ghost rollout 18:33:18). So: find out WHO holds
        // it before deciding anything, and never start a blank thread as a
        // consequence of a lock.
        if (newThreadId.isNullOrBlank() && rid != null &&
            CodexThreadLock.isWriterConflict(openErr)
        ) {
            val holders = probeThreadLock(rid)
            val ours = holders.filter { it.kind == SessionHolder.Kind.HEADLESS }
            // ⛔ ON THE DAEMON PATH, A HEADLESS HOLDER IS THE DAEMON ITSELF.
            //
            // The orphan-reaping below exists because a private `codex
            // app-server` of ours can outlive its SSH channel. Riding the shared
            // daemon makes that impossible — our channel is a short-lived
            // `proxy` client and the thread is held by the daemon, which is also
            // what HIS terminal (`codex --remote unix://`) is talking to.
            // Reaping it here would kill the shared brain and take his terminal
            // down with it: the exact opposite of why the daemon exists.
            //
            // So on this path a refusal means another CLIENT has the thread, and
            // the honest move is to say so rather than to end anything. A tty
            // holder is still a separate, private app-server (a plain `codex`)
            // and the relay below still applies to it.
            if ((launchedViaDaemon || onSharedBrain) && ours.isNotEmpty()) {
                android.util.Log.w(
                    tag,
                    "thread $rid held by ${ours.map { it.pid }} on the shared daemon — not reaping the brain",
                )
                threadBusy = holders
                teardownProcess()
                return false
            }
            if (holders.isNotEmpty() && ours.size == holders.size) {
                // Every holder is headless - an app-server of OURS that
                // outlived its SSH channel. Our garbage, our cleanup: reap it
                // and resume for real, with nothing on screen (the app fixes
                // what it can fix by itself).
                android.util.Log.i(
                    tag,
                    "thread $rid held by our own orphan(s) ${ours.map { it.pid }} - reaping, retrying resume",
                )
                reapHolders(ours.map { it.pid })
                openThread(rid).let { newThreadId = it.first; openErr = it.second }
            }
            if (newThreadId.isNullOrBlank() && userInitiated && holders.isNotEmpty()) {
                // THE RELAY. He sent this from here, so here is where the
                // session lives now — the other copy is ended for him, in the
                // SAME turn, and the prompt goes on to be delivered below.
                //
                // ⛔ Not a question, and not two gestures. "tap to take it over"
                // then "send again" costs three actions per alternation, and he
                // alternates phone / server message by message
                // (feedback_own_device_is_never_disconnected: the app takes on
                // the whole mechanic, it does not ask). The tap below survives
                // for the case this path FAILS.
                //
                // Safe because the claim is a send: nothing here fires on
                // opening a chat, on a limit warm-up, or on any timer — those
                // callers pass userInitiated=false and still keep hands off.
                // Nothing is lost either way: the thread is its rollout file,
                // and the resume immediately after reads it back whole.
                android.util.Log.i(
                    tag,
                    "thread $rid held by ${holders.map { it.pid }} - relay: ending them, resuming here",
                )
                reapHolders(holders.map { it.pid })
                openThread(rid).let { newThreadId = it.first; openErr = it.second }
                if (!newThreadId.isNullOrBlank()) {
                    history.emitMsg(
                        AgentMessage.EventNote(
                            id = CodexThreadLock.TAKEOVER_MARKER_ID,
                            label = CodexThreadLock.relayedNote(holders),
                            tone = AgentMessage.EventNote.Tone.INFO,
                        ),
                    )
                    // …and then FIX THE CAUSE, without asking. A relay means
                    // two clients are real on this box, so the shared brain is
                    // worth having: if this codex can host one, the terminal is
                    // wired to join it right here (one idempotent exec), and
                    // nothing is ever shown. The advice object goes up either
                    // way — the ViewModel owns the half this layer cannot do
                    // (updating the CLI) and is the one that decides whether a
                    // human has to be told anything at all.
                    val h = holders.firstOrNull { it.kind == SessionHolder.Kind.TTY }
                        ?: holders.first()
                    val sup = daemonSupport ?: probeDaemonSupport()
                    // ⛔ ONLY WHEN THE APP IS ITSELF ON THAT BRAIN. Wiring his
                    // terminal into a server the phone cannot reach would leave
                    // the phone unable to continue its own session — the dead
                    // end the earlier daemon attempt would have shipped.
                    //
                    // On the brain, this is the LAST relay this session will
                    // ever need: from now on his terminal joins as a second
                    // client instead of starting a rival app-server.
                    // ⛔ HOOK HELD BACK UNTIL THE TERMINAL SIDE IS PROVEN.
                    // The app's own half is measured working (it rides the
                    // brain and turns stream), but `codex --remote ws://…` was
                    // never seen to attach: the TUI stalls on its own
                    // interactive update prompt, and `--remote` is refused for
                    // `codex exec`, so there is no non-interactive way to check
                    // it. Writing a wrapper that sends his terminal somewhere
                    // unverified is the dead end this project already refused
                    // once — so the relay stays the mechanism until a real TUI
                    // attach is observed.
                    val hook = if (TERMINAL_HOOK_ENABLED) wsPort?.let { installTerminalHook(it) } else null
                    onHandoffAdvice(
                        HandoffAdvice(
                            cli = "codex",
                            where = h.stream.ifBlank { "a terminal" },
                            pid = h.pid,
                            cliVersion = sup?.version.orEmpty(),
                            // "Shared" means THIS CHAT IS ON THE BRAIN — the
                            // only state in which wiring his terminal to it is
                            // safe, and the only one that makes future relays
                            // unnecessary.
                            sharedBrain = onSharedBrain,
                            hookOutcome = hook,
                        ),
                    )
                }
            }
            if (newThreadId.isNullOrBlank()) {
                // The relay could not clear it (or there was nothing to clear
                // and codex still refuses). Hands off, and the chat STAYS on
                // this thread - the takeover row is the way out.
                android.util.Log.w(tag, "thread $rid busy: holders=$holders")
                threadBusy = holders
                teardownProcess()
                return false
            }
        }

        if (newThreadId.isNullOrBlank()) {
            android.util.Log.w(
                tag,
                "thread/${if (rid != null) "resume" else "start"} failed " +
                    "(rid=$rid, err=${openErr?.take(200)}) - falling back to exec",
            )
            broken = true
            teardownProcess()
            return false
        }
        threadId = newThreadId
        if (rid == null) setResumeId(newThreadId!!)
        android.util.Log.d(tag, "thread ready id=$newThreadId resumed=${rid != null}")
        return true
    }

    /** One `thread/resume` (or `thread/start` when there is nothing to
     *  resume) -> (thread id, refusal text). Split out so the writer-lock
     *  recovery can run it a second time after clearing the holder. */
    private suspend fun openThread(rid: String?): Pair<String?, String?> {
        val reqId = reqCounter.incrementAndGet()
        val (resp, err) = rpcDetailed(
            reqId,
            if (rid != null) {
                CodexAppServerWire.encodeThreadResume(
                    reqId, rid,
                    model = getModelOverride()?.takeIf { it.isNotBlank() }
                        ?.let(ai.eight24family.conch.linux.LocalLlm::cliModelName),
                    cwd = cwdSnapshot(),
                    approval = getApprovalMode(),
                )
            } else {
                CodexAppServerWire.encodeThreadStart(
                    reqId,
                    model = getModelOverride()?.takeIf { it.isNotBlank() }
                        ?.let(ai.eight24family.conch.linux.LocalLlm::cliModelName),
                    cwd = cwdSnapshot(),
                    approval = getApprovalMode(),
                )
            },
            timeoutMs = 30_000,
        )
        val id = resp?.let {
            SilentlyTry.logged(tag, "read thread id") { it["thread"]?.jsonObject?.str("id") }
        }
        return id to err
    }

    /**
     * Ask this server's codex what it supports, once per live session object.
     * Null (probe could not run) is never cached as "no" — same rule as
     * [SessionHolder.Probe.Unreachable].
     */
    private suspend fun probeDaemonSupport(): ai.eight24family.conch.agent.codex.CodexDaemon.Support? {
        daemonSupport?.let { return it }
        val raw = sshLifecycle.execOnLive(
            loginShell(ai.eight24family.conch.agent.codex.CodexDaemon.probeScript()),
        )
        val parsed = ai.eight24family.conch.agent.codex.CodexDaemon.parseProbe(raw)
        if (parsed != null) {
            daemonSupport = parsed
            android.util.Log.i(tag, "codex support: $parsed")
        }
        return parsed
    }

    /**
     * True when a daemon is up and ours to talk to. Starts it if it is not —
     * idempotent, silent, and PROVEN by `daemon version` rather than hoped for
     * (a start that failed would otherwise become a channel that never speaks).
     */
    private suspend fun daemonReady(): Boolean {
        val sup = probeDaemonSupport() ?: return false
        if (!sup.sharedBrain) return false
        val raw = sshLifecycle.execOnLive(
            loginShell(ai.eight24family.conch.agent.codex.CodexDaemon.startScript()),
        )
        val up = ai.eight24family.conch.agent.codex.CodexDaemon.parseStart(raw)
        if (!up) android.util.Log.w(tag, "codex app-server daemon would not start — private app-server it is")
        return up
    }

    /**
     * Make sure a shared app-server is listening on the server's loopback, and
     * say on which port. Idempotent — an already-running one is REUSED, which
     * is the entire point: his terminal may already be attached to it.
     */
    private suspend fun ensureSharedBrain(): ai.eight24family.conch.agent.codex.CodexWsBrain.Brain? {
        val raw = sshLifecycle.execOnLive(
            loginShell(ai.eight24family.conch.agent.codex.CodexWsBrain.ensureScript()),
        )
        val brain = ai.eight24family.conch.agent.codex.CodexWsBrain.parseEnsure(raw)
        if (brain == null) {
            android.util.Log.w(tag, "no shared brain (old codex, no port, or start failed) — private app-server")
        } else if (brain.startedNow) {
            android.util.Log.i(tag, "started the shared codex brain on port ${brain.port}")
        }
        return brain
    }

    /**
     * Open the WebSocket to that brain over the SSH connection we already hold.
     *
     * ⛔ A DIRECT CHANNEL, NOT A FORWARDED PORT. Listening on the PHONE would
     * expose the whole session protocol to every other app on the device; this
     * way the bytes never leave the encrypted SSH channel and nothing on the
     * device is listening at all.
     */
    private suspend fun openSharedBrain(client: net.schmizz.sshj.SSHClient, port: Int): Boolean = try {
        val sess = client.startStreamSession()
        val cmd = sess.exec(
            loginShell(ai.eight24family.conch.agent.codex.CodexWsBrain.bridgeCommand(port)),
        )
        val out = cmd.outputStream
        // ⛔ THE HANDSHAKE MUST BE ABLE TO GIVE UP.
        //
        // It reads the HTTP upgrade byte by byte off a blocking stream. If the
        // port is held by something that is NOT an app-server (any other
        // service that accepts and stays silent), or the brain is wedged, that
        // read never returns — and the whole turn hangs instead of falling back
        // to a private app-server. A cancelled coroutine cannot unblock a
        // socket read either, so the timeout must CLOSE the channel: that is
        // what wakes the reader with an exception.
        val shook = scope.async(Dispatchers.IO) {
            ai.eight24family.conch.agent.codex.CodexWsFrames.handshake(
                cmd.inputStream, out, "127.0.0.1", port,
            )
            true
        }
        val ok = withTimeoutOrNull(BRAIN_HANDSHAKE_TIMEOUT_MS) { shook.await() } == true
        if (!ok) {
            android.util.Log.w(tag, "shared brain did not answer the upgrade in time — private app-server")
            SilentlyTry.fired(tag, "close stalled brain channel") { sess.close() }
            shook.cancel()
            return false
        }
        wsSession = sess
        wsCmd = cmd
        wsOut = out
        startSharedBrainReader(cmd)
        true
    } catch (t: Throwable) {
        android.util.Log.w(
            tag,
            "shared brain unreachable on 127.0.0.1:$port: ${t.javaClass.simpleName}: ${t.message}",
        )
        SilentlyTry.fired(tag, "close failed brain channel") { wsSession?.close() }
        wsSession = null
        wsCmd = null
        wsOut = null
        false
    }

    /** Frames in, JSON-RPC lines out — the same [handleIncoming] the stdio
     *  reader feeds, so nothing downstream knows which transport it rode. */
    private fun startSharedBrainReader(cmd: Session.Command) {
        readerJob = scope.launch {
            try {
                val input = java.io.DataInputStream(java.io.BufferedInputStream(cmd.inputStream))
                while (true) {
                    val text = ai.eight24family.conch.agent.codex.CodexWsFrames.readText(input, cmd.outputStream)
                        ?: break
                    for (line in text.lineSequence()) {
                        if (line.isNotBlank()) handleLine(line)
                    }
                }
                android.util.Log.w(
                    tag,
                    "shared brain closed the socket (bridge exit=${SilentlyTry.logged(tag, "brain exit") { cmd.exitStatus }})",
                )
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.w(tag, "shared brain reader died: ${t.message}")
                }
            } finally {
                onReaderEnd("shared-brain reader ended")
            }
        }
    }

    /**
     * Re-ask after the app has UPDATED codex on the server — the cached answer
     * is from the old binary, and the whole point of the update was to change
     * it. Returns the fresh support, so the caller can say what actually
     * happened instead of "try again".
     */
    suspend fun refreshSharedBrainSupport(): Boolean {
        daemonSupport = null
        daemonRefused = false
        // The question is not "which flags does the binary list" but "can a
        // shared app-server actually be had on this box" — so ask by doing.
        return ensureSharedBrain() != null
    }

    /** Last support answer without asking the server again — for UI that is
     *  deciding which button to show. */
    fun sharedBrainSupport(): ai.eight24family.conch.agent.codex.CodexDaemon.Support? = daemonSupport

    /**
     * Teach the server account's shell to join the daemon, so a plain `codex`
     * typed in a terminal becomes a client instead of a second app-server.
     * Returns the CLI's own word for what happened (`added:<rc>`, `present`,
     * `unsupported:<shell>`, `failed`) or null when the exec did not run.
     */
    suspend fun installTerminalHook(port: Int): String? {
        val raw = sshLifecycle.execOnLive(
            loginShell(ai.eight24family.conch.agent.codex.CodexWsBrain.installHookScript(port)),
        )
        val outcome = ai.eight24family.conch.agent.codex.CodexWsBrain.parseHook(raw)
        android.util.Log.i(tag, "terminal hook -> $outcome")
        // ONE line, and only when something actually changed. The app wrote to
        // a file in his account: silence there would be the app hiding a change
        // it made, and a second line on every later handoff would be noise.
        if (outcome?.startsWith("added") == true) {
            history.emitMsg(
                AgentMessage.EventNote(
                    id = "conch-codex-terminal-joined",
                    label = "this server's terminal now joins this session instead of taking it over " +
                        "— a plain `codex` there attaches to the same app-server as the phone",
                    tone = AgentMessage.EventNote.Tone.INFO,
                ),
            )
        }
        return outcome
    }

    /** Who has this thread's rollout open, over the live SSH channel.
     *  Empty when nobody does, or when the probe itself could not run - the
     *  caller treats both as "cannot prove it is ours", which keeps the
     *  destructive branch closed. */
    private suspend fun probeThreadLock(rid: String): List<SessionHolder.Holder> {
        if (!CodexThreadLock.isSafeThreadId(rid)) return emptyList()
        val raw = sshLifecycle.execOnLive(loginShell(CodexThreadLock.probeScript(rid)))
        val parsed = CodexThreadLock.parseProbe(raw)
        return (parsed as? SessionHolder.Probe.Held)?.holders ?: emptyList()
    }

    /**
     * TAKE THE THREAD BACK — the one thing the app cannot do by itself.
     *
     * The automatic recovery in [ensureReady] only ever reaps HEADLESS
     * holders, because those are orphans of ours. A terminal someone may be
     * sitting in is not ours to end on a timer, and codex offers no polite
     * ask: there is no thread-level release (`thread/unsubscribe` leaves the
     * writer exactly where it was, measured on 0.153.4), so the writer moves
     * only when a process ends. Hence a deliberate tap, and hence this is the
     * ONLY path that will end a tty holder.
     *
     * Nothing is lost by it: the thread's content is its rollout file on
     * disk, and the next resume reads it back in full.
     */
    suspend fun takeOverThread(): Boolean {
        val rid = getResumeId() ?: return false
        val holders = probeThreadLock(rid)
        if (holders.isEmpty()) {
            // Already free — the holder closed while the row sat on screen.
            history.emitMsg(
                AgentMessage.EventNote(
                    id = CodexThreadLock.TAKEOVER_MARKER_ID,
                    label = "session is free again — send to continue it",
                    tone = AgentMessage.EventNote.Tone.INFO,
                ),
            )
            return true
        }
        val pids = holders.map { it.pid }
        reapHolders(pids)
        val left = probeThreadLock(rid)
        val ok = left.isEmpty()
        history.emitMsg(
            AgentMessage.EventNote(
                id = CodexThreadLock.TAKEOVER_MARKER_ID,
                label = if (ok) CodexThreadLock.takenOverNote(pids)
                else CodexThreadLock.takeoverFailedNote(),
                tone = if (ok) AgentMessage.EventNote.Tone.INFO
                else AgentMessage.EventNote.Tone.WARN,
            ),
        )
        return ok
    }

    /** INT->TERM->KILL a headless holder through the one ladder the app has. */
    private suspend fun reapHolders(pids: List<Long>) {
        if (pids.isEmpty()) return
        val out = sshLifecycle.execOnLive(loginShell(RemoteTurnKiller.killPidsScript(pids)))
        android.util.Log.d(tag, "reapHolders $pids -> ${RemoteTurnKiller.parseOutcome(out)}")
    }

    /**
     * The account's plan windows, over the channel THAT IS ALREADY OPEN.
     *
     * ⛔ THIS IS THE WHOLE POINT. The out-of-band probe
     * ([UsageProbe.CODEX_LIVE_CMD]) has to LAUNCH an `app-server`, sit through
     * its bubblewrap/config warnings and shut it down again. Here the process
     * is already running and already initialized, so the windows are one
     * JSON-RPC round trip on a transport the turns themselves ride —
     * milliseconds, no new ssh channel, no new process. The owner watched the
     * old path for a minute and was right about why: the ssh session is open,
     * so the answer should be immediate (2026-09-12).
     *
     * NEVER LAUNCHES ANYTHING. A chat with no live app-server returns null and
     * the caller falls back to the probe — asking to start one would turn the
     * free read into exactly the expensive one it exists to replace.
     */
    suspend fun fetchRateLimitsLive(): JsonObject? = withContext(Dispatchers.IO) {
        if (broken || !procAlive) return@withContext null
        val id = reqCounter.incrementAndGet()
        rpc(id, CodexAppServerWire.encodeRateLimitsRead(id), RATE_LIMITS_TIMEOUT_MS)
    }

    /** Send one request and await its JSON-RPC response. Null on write
     *  failure, timeout, or an error response (logged). [id] MUST be the
     *  exact id stamped into [line] — passed explicitly, a counter read
     *  here would race a concurrent interrupt's increment. */
    private suspend fun rpc(id: Long, line: String, timeoutMs: Long): JsonObject? =
        rpcDetailed(id, line, timeoutMs).first

    /** [rpc], plus the server's `error` text when it refused. Callers that
     *  must ACT on the reason (thread open — busy vs. gone) use this one. */
    private suspend fun rpcDetailed(
        id: Long,
        line: String,
        timeoutMs: Long,
    ): Pair<JsonObject?, String?> {
        val deferred = CompletableDeferred<JsonObject?>()
        pendingResponses[id] = deferred
        if (!writeLine(line)) {
            pendingResponses.remove(id)
            rpcErrors.remove(id)
            return null to null
        }
        val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingResponses.remove(id)
        return resp to rpcErrors.remove(id)
    }

    private fun startReader(cmd: Session.Command) {
        readerJob = scope.launch {
            try {
                BufferedReader(InputStreamReader(cmd.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) handleLine(line)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(tag, "reader died: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                onReaderEnd("reader EOF — app-server gone")
            }
        }
    }

    /**
     * One JSON-RPC line from whichever transport delivered it — stdio from a
     * private app-server, or a WebSocket text frame from the shared brain. Split
     * out so the two readers cannot drift: a response that completes a pending
     * call on one transport and not the other would hang a turn forever, with
     * nothing in the log to say which half was wrong.
     */
    private suspend fun handleLine(line: String) {
        when (val msg = CodexAppServerWire.parseLine(line)) {
            is CodexAppServerWire.Incoming.Response -> {
                val id = msg.id
                if (id != null) {
                    if (msg.error != null) {
                        android.util.Log.w(tag, "rpc error for #$id: ${msg.error.toString().take(200)}")
                        // KEEP the text — see [rpcErrors].
                        rpcErrors[id] = msg.error.toString()
                        pendingResponses.remove(id)?.complete(null)
                    } else {
                        pendingResponses.remove(id)?.complete(msg.result ?: JsonObject(emptyMap()))
                    }
                }
            }
            is CodexAppServerWire.Incoming.ServerReq -> handleServerRequest(msg)
            is CodexAppServerWire.Incoming.Notification -> handleNotification(msg.method, msg.params)
            null -> android.util.Log.d(tag, "non-rpc line: ${line.take(160)}")
        }
    }

    /** The channel is gone, whichever kind it was: nobody is left to answer, so
     *  every waiter is released rather than left to time out one by one. */
    private fun onReaderEnd(why: String) {
        android.util.Log.w(tag, why)
        procAlive = false
        threadId = null
        pendingServerReqs.keys.toList().forEach { retireServerReq(it) }
        pendingResponses.values.forEach { it.complete(null) }
        pendingResponses.clear()
        rpcErrors.clear()
        turnDone?.complete(false)
    }

    private fun handleNotification(method: String, params: JsonObject) {
        when (method) {
            "item/agentMessage/delta" -> {
                // Same version drift as the item shape (see CodexAppServerEvents):
                // a renamed param here means the bubble never streams and the
                // answer only lands when the item completes. Accept both
                // spellings; a `return` on a miss is a silently dropped answer.
                val turnId = params.str("turnId") ?: params.str("turn_id") ?: return
                val itemId = params.str("itemId") ?: params.str("item_id") ?: return
                val delta = params.str("delta")
                    ?: params["delta"]?.let { d ->
                        SilentlyTry.logged(tag, "delta obj") { d.jsonObject.str("text") }
                    }
                    ?: return
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

            // The server PUSHES the account's windows down this channel
            // whenever they move, so the freshest numbers in the app cost
            // nothing at all: no request, no ssh command, no second process.
            // This line sat in the discard bucket below while the limit bar
            // paid eleven seconds of hard-coded sleeps in a SECOND `codex
            // app-server` to ask for what was already being handed to us.
            "account/rateLimits/updated" ->
                ai.eight24family.conch.agent.UsageProbe
                    .rememberCodexPush(server.id, params.toString())

            // Per-chunk delta spam / bookkeeping with no chat-row value.
            // The completed items and tailored branches above carry it all.
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta",
            "item/reasoning/summaryPartAdded", "item/commandExecution/outputDelta",
            "item/fileChange/outputDelta", "item/fileChange/patchUpdated",
            "item/plan/delta", "turn/diff/updated", "turn/moderationMetadata",
            "rawResponseItem/completed", "thread/started", "thread/status/changed",
            "thread/name/updated", "thread/settings/updated",
            "account/updated",
            "mcpServer/startupStatus/updated", "fs/changed", "skills/changed",
            "item/autoApprovalReview/started", "item/autoApprovalReview/completed",
            "hook/started", "hook/completed",
            // codex's OWN remote-control feature toggling itself — an internal
            // control-plane event, nothing the user drives. It fell through to
            // the generic "surface it" bucket below and printed "remoteControl
            // status changed · disabled" on EVERY app-server launch; stacked by
            // the resume loop it filled the whole screen (owner 2026-09-12).
            "remoteControl/status/changed", "remoteControl/status/updated",
            -> Unit

            "warning", "guardianWarning", "configWarning", "deprecationNotice" -> {
                val text = params.str("summary") ?: params.str("message") ?: method
                // Codex warns it has no metadata for any model outside its own
                // catalog and falls back BY ITSELF — by design for a local
                // model (there is nothing to look up), nothing to act on for
                // any other. Never a line in the chat (same rule in
                // CodexAppServerEvents.mapError, which carries the copy the
                // error notification uses).
                if (text.startsWith("Model metadata for")) return
                // Codex-internal migration note on thread/resume (paginated
                // threads) — self-directed, acts on nothing the user controls,
                // and it printed in red on every resumed local chat.
                if (text.startsWith("Full-history hydration is deprecated")) return
                // Another self-directed housekeeping note (skills list got
                // trimmed to fit ITS budget) — nothing the user can act on,
                // and it printed red on every turn of a skills-heavy host.
                if (text.startsWith("Skill descriptions were shortened")) return
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
        wsOut?.let { out ->
            return try {
                ai.eight24family.conch.agent.codex.CodexWsFrames.writeText(out, line)
                true
            } catch (t: Throwable) {
                android.util.Log.w(tag, "shared-brain write failed: ${t.message}")
                false
            }
        }
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

    /**
     * HAND THE THREAD BACK WHEN NOBODY HERE IS USING IT.
     *
     * codex allows ONE writer per thread and offers no way to give it up
     * short of ending the process — measured 2026-09-09 on 0.153.4:
     * `thread/unsubscribe` answers `"unsubscribed"` and the thread STAYS in
     * `thread/loaded/list`, with a second opener still refused; closing the
     * process releases it instantly (app-server exits on stdin EOF). So the
     * lock is a consequence of how long we keep the process parked, and
     * nothing else.
     *
     * Which means a chat left open on the phone used to lock the owner out of
     * his own session everywhere else, for as long as the app felt like
     * holding it. Continuing on the server, then back on the phone, has to
     * work without anyone recreating anything — so the process is kept only
     * while it is EARNING its keep.
     *
     * The arithmetic, measured on the owner's server:
     *   • resume inside a live process ....... 12–86 ms
     *   • cold: launch + initialize + resume .. 1.8–2.3 s
     *     plus the MCP servers coming back up . +2–4 s (they restart with it)
     *   • holding it costs every other client .. 100% of the time
     * A burst of turns has gaps of seconds, so parking the process across a
     * burst is worth ~5 s per turn; the gaps BETWEEN bursts are minutes to
     * hours, where the same parking buys nothing and blocks everything.
     * [IDLE_RELEASE_MS] is where those two facts meet.
     *
     * ⚠ A RUNNING TURN IS NEVER RELEASED, foreground or not. "Send a task,
     * pocket the phone, the agent keeps working" is a shipped promise (see
     * AppForeground's own warning); the writer is exactly what a running turn
     * needs. Backgrounding only shortens the wait for an IDLE thread — the
     * user walking to their desk is the clearest possible signal that the
     * phone is done with it.
     */
    private fun armIdleRelease() {
        idleReleaseJob?.cancel()
        if (getResumeId() == null) return
        idleReleaseJob = scope.launch {
            var waited = 0L
            while (waited < IDLE_RELEASE_MS) {
                kotlinx.coroutines.delay(IDLE_TICK_MS)
                // Someone else already tore it down, or a new turn claimed it
                // — that turn's own completion re-arms this.
                if (!procAlive) return@launch
                if (turnDone != null) return@launch
                // Left the phone: hand it over now, don't sit out the timer.
                if (!ai.eight24family.conch.util.AppForeground.isForeground) break
                waited += IDLE_TICK_MS
            }
            if (turnDone == null && procAlive) releaseForHandoff()
        }
    }

    /**
     * Close the process and PROVE the thread came free.
     *
     * [teardownProcess] closes stdin and the channel, and app-server exits on
     * EOF — but a channel that dies without a clean close (a phone changing
     * networks) leaves the remote process alive and still holding the writer:
     * that is where the orphans this class reaps in [ensureReady] come from.
     * A release that only HOPES is worse than none, because the owner would
     * find the session locked with nothing on screen saying why. So the same
     * probe answers the question, and anything of ours still standing is
     * reaped.
     */
    private suspend fun releaseForHandoff() {
        val rid = getResumeId()
        android.util.Log.d(tag, "releasing thread $rid for handoff")
        teardownProcess()
        if (rid == null) return
        // ⛔ NOTHING TO RELEASE ON THE SHARED DAEMON, AND NOTHING SAFE TO REAP.
        //
        // Our channel there was a `proxy` client; closing it above already gave
        // the thread back as far as we are concerned. The headless holder that
        // remains is the DAEMON — the very process his terminal is attached to.
        // Reaping it is how a "polite handoff" would end his session.
        if (launchedViaDaemon) {
            android.util.Log.d(tag, "handoff release on the daemon path — proxy closed, brain left alone")
            return
        }
        val leftovers = probeThreadLock(rid).filter { it.kind == SessionHolder.Kind.HEADLESS }
        if (leftovers.isEmpty()) return
        // ⛔ DO NOT REAP A PROCESS THE USER JUST STARTED.
        //
        // The probe is a round trip to the server, and a send landing inside
        // it relaunches the channel — whose app-server is HEADLESS and holds
        // this very thread, so it matches the filter above perfectly. Killing
        // it would break the turn we were trying to protect. Cancelling this
        // job (runTurn does) already unwinds at the probe's suspension point;
        // this is the explicit belt: if the channel is alive again, the thread
        // is legitimately claimed and there is nothing here to clean up.
        if (procAlive || turnDone != null) {
            android.util.Log.d(tag, "handoff release overtaken by a new turn — leaving $rid claimed")
            return
        }
        android.util.Log.w(tag, "channel closed but ${leftovers.map { it.pid }} still hold $rid — reaping")
        reapHolders(leftovers.map { it.pid })
    }

    fun teardownProcess() {
        idleReleaseJob?.cancel()
        idleReleaseJob = null
        readerJob?.cancel()
        readerJob = null
        // A login can happen between launches — never trust a stale verdict.
        cloudAuthCache = null
        // ⛔ CLOSING OUR CLIENT MUST NOT TOUCH THE BRAIN. The server on the
        // other end is shared with his terminal; we hang up, it stays.
        wsSession?.let { c ->
            SilentlyTry.fired(tag, "close shared-brain channel") { c.close() }
        }
        wsSession = null
        wsCmd = null
        wsOut = null
        wsPort = null
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

        /**
         * Ceiling on the live rate-limit read. It is a CEILING, not a wait —
         * the answer comes back off a running, initialized process, so the
         * normal case is milliseconds. Short on purpose: if the channel is
         * wedged the bar must fall through to the probe, not hold the open.
         */
        private const val RATE_LIMITS_TIMEOUT_MS = 3_000L

        /**
         * How long an IDLE thread stays parked here before the writer is
         * handed back. See [armIdleRelease] for the measured numbers behind
         * the choice: a burst of turns (gaps of seconds) keeps its ~5 s-a-turn
         * warm path, and the long gaps between bursts stop locking every other
         * client out. Backgrounding the app cuts the wait short.
         */
        /** How long the shared brain has to answer the WebSocket upgrade before
         *  the chat gives up on it and launches a private app-server. Short on
         *  purpose: this sits in front of every cold turn. */
        private const val BRAIN_HANDSHAKE_TIMEOUT_MS = 8_000L

        private const val IDLE_RELEASE_MS = 120_000L
        private const val IDLE_TICK_MS = 5_000L
    }
}
