package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.analytics.Telemetry
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.ssh.FailureKind
import ai.eight24family.conch.ssh.SshClient
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface SessionState {
    data object Idle : SessionState
    data class Bootstrapping(val step: String) : SessionState
    data object Running : SessionState
    data object Working : SessionState
    data class Failed(val reason: String) : SessionState
    data object Closed : SessionState
}

/**
 * Per-chat CLI driver. Coordinates four cohesive subsystems extracted
 * into their own files in this package:
 *
 *  - [AgentSessionHistory]      — history list, id-index map, streaming
 *                                  emit batcher (the 80 ms flush window).
 *  - [AgentSessionPromptQueue]  — pending-prompts queue, mid-turn send
 *                                  semantics, recent-send dedupe.
 *  - [AgentSessionSshLifecycle] — pooled SSH client, in-flight exec
 *                                  channel, signal-ladder cancel.
 *  - [AgentSessionRunOneShot]   — per-turn execution: build command,
 *                                  parse JSONL stream, humanise exits.
 *
 * The public surface this class exposes (state / history / send /
 * close / cancelCurrent / isAlive / cwdSnapshot / agentSessionId /
 * loadHistory / appendMessages / uploadFile / downloadFile / …) is
 * deliberately unchanged from before the refactor — every caller in
 * the codebase keeps working verbatim.
 */
class AgentSession(
    // `internal val` so PiP / lockscreen surfaces can read the
    // server/agent metadata without re-querying the repo (which would
    // need suspend / Context). The chat itself never reads these
    // directly — it uses Server / Agent from its own VM state.
    internal val server: Server,
    private val secrets: ServerSecrets,
    private val ssh: SshClient,
    private val chatSessionId: String,
    private var resumeId: String? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The server-assigned resume id once the first turn has produced one
     *  (null until then). Read-only — lets the Home list dedup an in-flight
     *  session against its cached row by the same id. */
    val currentResumeId: String? get() = resumeId

    /** True if this chat opened as a BRAND-NEW session (no resumeId at
     *  construction). New sessions adopt the agent's active auth method and
     *  bind to it once the CLI assigns their id. A RESUMED session only ever
     *  uses an EXISTING binding — never the current active — so switching the
     *  active method can't retro-break an old chat (a Codex ChatGPT rollout
     *  won't answer under API auth). Unbound resume ⇒ null ⇒ CLI default. */
    private val bornNew: Boolean = (resumeId == null)

    /** Cached shell prefix that forces the session's chosen auth method
     *  (resolved async from [ai.eight24family.conch.data.AuthMethodStore]).
     *  "" = no method chosen = launch byte-identical to before. */
    @Volatile private var authPrep: String = ""

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * LIVE reasoning-token counter for the in-flight turn — fed by the
     * CLI's `system/thinking_tokens` events (estimated_tokens), cleared
     * to null when the turn ends. Drives the transient «thinking · N
     * tokens» row above the working spinner; deliberately NOT a chat
     * message.
     */
    val liveThinkingTokens = MutableStateFlow<Long?>(null)

    // ─── Cohesive subsystems (see class kdoc) ─────────────────────
    private val historyMod = AgentSessionHistory(scope)
    val history: StateFlow<PersistentList<AgentMessage>> = historyMod.history

    private val sshLifecycle = AgentSessionSshLifecycle(server, secrets, ssh, scope)

    /** Prompts that a [AgentSessionRunOneShot] ABORTED on because the transport
     *  was dead — they never reached the agent. The ViewModel drains these in
     *  retry() (via [consumeUndelivered]) and re-buffers them so the silent
     *  reconnect re-delivers them. Fixes "wrote a message, switched network,
     *  no reply". CopyOnWrite: appended from the run coroutine, read on the
     *  VM's main thread. */
    private val undeliveredPrompts = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val runner = AgentSessionRunOneShot(
        server = server,
        scope = scope,
        sshLifecycle = sshLifecycle,
        history = historyMod,
        onStateChange = { newState ->
            val prev = _state.value
            _state.value = newState
            // A turn just finished generating (Working → Running) = the agent's
            // reply landed = the real "last message" time for this session.
            // Record it so the sessions list sorts/stamps by the reply, not just
            // the send. Safe (never fires on history replay — replay doesn't run
            // the turn state machine), unlike hooking appendMessages.
            if (prev is SessionState.Working && newState is SessionState.Running) {
                resumeId?.let { rid ->
                    ai.eight24family.conch.di.ServiceLocator.sessionActivity
                        .observeLocal(server.id, rid)
                }
            }
        },
        onPromptUndelivered = { text -> undeliveredPrompts.add(text) },
        getState = { _state.value },
        getResumeId = { resumeId },
        setResumeId = { newId -> resumeId = newId; onResumeIdAssigned(newId) },
        cwdSnapshot = { cwdSnapshot },
        getModelOverride = { modelOverride },
        getReasoningOverride = { reasoningEffortOverride },
        getApprovalMode = { approvalMode },
        loginShell = ::loginShell,
        getAuthPrep = { authPrep },
        onThinkingTokens = { n -> liveThinkingTokens.value = n },
    )

    /**
     * Persistent bidirectional channel (Agent SDK transport) for specs
     * that support the control protocol — live permission prompts,
     * AskUserQuestion option picking, real mid-turn interrupt. Falls
     * back to [runner]'s proven one-shot path when the launch fails
     * (older CLI) — silently, per the auto-fix invariant.
     */
    private val persistentStream = AgentSessionPersistentStream(
        server = server,
        scope = scope,
        sshLifecycle = sshLifecycle,
        history = historyMod,
        onStateChange = { newState ->
            val prev = _state.value
            _state.value = newState
            if (prev is SessionState.Working && newState is SessionState.Running) {
                resumeId?.let { rid ->
                    ai.eight24family.conch.di.ServiceLocator.sessionActivity
                        .observeLocal(server.id, rid)
                }
            }
        },
        getState = { _state.value },
        getResumeId = { resumeId },
        setResumeId = { newId -> resumeId = newId; onResumeIdAssigned(newId) },
        cwdSnapshot = { cwdSnapshot },
        getModelOverride = { modelOverride },
        getReasoningOverride = { reasoningEffortOverride },
        getApprovalMode = { approvalMode },
        loginShell = ::loginShell,
        getAuthPrep = { authPrep },
        onPromptUndelivered = { text -> undeliveredPrompts.add(text) },
        onThinkingTokens = { n -> liveThinkingTokens.value = n },
    )

    /**
     * Codex twin of [persistentStream]: a long-lived `codex app-server`
     * JSON-RPC channel (thread/turn API). Live approval cards,
     * request_user_input questions, real turn/interrupt, compaction
     * items, token-usage counters. Same silent fallback discipline —
     * launch/handshake failure (old codex) flips [AgentSessionCodexAppServer.broken]
     * and the session rides the proven `codex exec` one-shot path.
     * Per CLAUDE.md §3c the gate is EXPLICITLY per-agent.
     */
    private val codexAppServer = AgentSessionCodexAppServer(
        server = server,
        scope = scope,
        sshLifecycle = sshLifecycle,
        history = historyMod,
        onStateChange = { newState ->
            val prev = _state.value
            _state.value = newState
            if (prev is SessionState.Working && newState is SessionState.Running) {
                resumeId?.let { rid ->
                    ai.eight24family.conch.di.ServiceLocator.sessionActivity
                        .observeLocal(server.id, rid)
                }
            }
        },
        getState = { _state.value },
        getResumeId = { resumeId },
        setResumeId = { newId -> resumeId = newId; onResumeIdAssigned(newId) },
        cwdSnapshot = { cwdSnapshot },
        getModelOverride = { modelOverride },
        getReasoningOverride = { reasoningEffortOverride },
        getApprovalMode = { approvalMode },
        loginShell = ::loginShell,
        getAuthPrep = { authPrep },
        onPromptUndelivered = { text -> undeliveredPrompts.add(text) },
        onThinkingTokens = { n -> liveThinkingTokens.value = n },
    )

    /**
     * Gemini twin: a long-lived `gemini --experimental-acp` process
     * (Agent Client Protocol — the transport gemini's IDE integrations
     * use). Live permission cards, streamed chunks, real session/cancel.
     * Same silent [AgentSessionGeminiAcp.broken] fallback to the proven
     * one-shot `--print` path. Explicit per-agent gate (CLAUDE.md §3c).
     */
    private val geminiAcp = AgentSessionGeminiAcp(
        server = server,
        scope = scope,
        sshLifecycle = sshLifecycle,
        history = historyMod,
        onStateChange = { newState ->
            val prev = _state.value
            _state.value = newState
            if (prev is SessionState.Working && newState is SessionState.Running) {
                resumeId?.let { rid ->
                    ai.eight24family.conch.di.ServiceLocator.sessionActivity
                        .observeLocal(server.id, rid)
                }
            }
        },
        getState = { _state.value },
        getResumeId = { resumeId },
        setResumeId = { newId -> resumeId = newId; onResumeIdAssigned(newId) },
        cwdSnapshot = { cwdSnapshot },
        getModelOverride = { modelOverride },
        getApprovalMode = { approvalMode },
        loginShell = ::loginShell,
        getAuthPrep = { authPrep },
        onPromptUndelivered = { text -> undeliveredPrompts.add(text) },
    )

    /** True while this session's turns ride the persistent control
     *  channel (spec supports it AND it hasn't broken at launch). */
    private fun usePersistent(): Boolean =
        AgentSpecRegistry[server.agent].supportsControlProtocol && !persistentStream.broken

    /** Codex chats ride the app-server channel until it proves broken. */
    private fun useCodexAppServer(): Boolean =
        server.agent == Agent.CODEX && !codexAppServer.broken

    /** Gemini chats ride the ACP channel until it proves broken. */
    private fun useGeminiAcp(): Boolean =
        server.agent == Agent.GEMINI && !geminiAcp.broken

    /**
     * Public mirror of [usePersistent] for UI-side heuristics: on the
     * persistent channels (Claude control protocol, Codex app-server)
     * the remote CLI process LIVES BETWEEN turns, so pgrep-based "agent
     * process alive" signals must NOT be interpreted as "a turn is
     * running" (the spinner would never stop).
     */
    fun usesPersistentChannel(): Boolean =
        usePersistent() || useCodexAppServer() || useGeminiAcp()

    private val promptQueue = AgentSessionPromptQueue(
        scope = scope,
        runOneShot = { text, imagePaths ->
            if (usePersistent()) {
                // Claude persistent channel: images not wired yet (its Read tool
                // can open the uploaded path from the prose). Codex/Gemini get the
                // real pixels below.
                val delivered = persistentStream.runTurn(text)
                // Launch-level failure → silent permanent fallback to the
                // one-shot path for this session (auto-fix invariant).
                if (!delivered) runner.runOneShot(text)
            } else if (useCodexAppServer()) {
                val delivered = codexAppServer.runTurn(text, imagePaths)
                if (!delivered) runner.runOneShot(text)
            } else if (useGeminiAcp()) {
                val delivered = geminiAcp.runTurn(text, imagePaths)
                if (!delivered) runner.runOneShot(text)
            } else {
                runner.runOneShot(text)
            }
        },
        // The drainer calls this RIGHT BEFORE starting each turn. We add the
        // user's prompt to history HERE, not at send() time, so when two
        // prompts are queued back-to-back the second one appears AFTER the
        // first reply lands — proper chronological pairing instead of
        // [user1, user2, reply1, reply2].
        emitOnTurnStart = { text ->
            historyMod.emitMsg(AgentMessage.UserText(UUID.randomUUID().toString(), text))
        },
    )

    private val fileTransfer = AgentSessionFileTransfer(sshLifecycle, historyMod, _state)

    /** Agent-side session id captured from the first stream-json system event. */
    val agentSessionId: String? get() = resumeId

    /** See [AgentSessionSshLifecycle.isAlive]. */
    fun isAlive(): Boolean = sshLifecycle.isAlive()

    /** Most recent observed cwd, picked off any system event in history.
     *  Used by the subagents screen to know where to write project-scope
     *  agent files. Null if we haven't seen a system message with cwd yet. */
    val cwdSnapshot: String?
        get() = historyMod.history.value.asReversed().asSequence()
            .filterIsInstance<AgentMessage.System>()
            .mapNotNull { it.cwd }
            .firstOrNull()

    /** When set, passed as `--model X` to the next CLI invocation. */
    @Volatile var modelOverride: String? = null

    /** When set, passed as the spec's reasoning-effort flag to the
     *  next CLI invocation (`-c model_reasoning_effort` for Codex,
     *  `--effort` for Claude). Live like [modelOverride] — pick mid-
     *  conversation, the next turn picks it up. */
    @Volatile var reasoningEffortOverride: String? = null

    /**
     * How permissive the next CLI invocation should be. SAFE keeps the
     * CLI's strict defaults, AUTO auto-approves tool writes, YOLO bypasses
     * the sandbox entirely. Maps to per-CLI flags in `buildCommand`.
     */
    @Volatile var approvalMode: ai.eight24family.conch.data.prefs.AgentApprovalMode =
        ai.eight24family.conch.data.prefs.AgentApprovalMode.YOLO

    /**
     * Cached SK signer for the chat's server. Set by the chat-open path
     * before the first [start] / send so the publickey exchange can sign
     * without re-prompting. See [AgentSessionSshLifecycle.skSigner] for
     * details.
     */
    var skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner?
        get() = sshLifecycle.skSigner
        set(value) { sshLifecycle.skSigner = value }

    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        // Wrap the whole bootstrap in a Performance transaction so we can
        // see real-world handshake + CLI-check timing in Sentry. Spans are
        // sampled per `tracesSampleRate` in SshAiApp init.
        val bootstrapTx = Telemetry.startAgentBootstrap(server.agent)
        val handshakeTx = Telemetry.startSshHandshake(server.agent)
        try {
            _state.value = SessionState.Bootstrapping("connecting")
            // Emit the welcome banner IMMEDIATELY on a fresh chat — BEFORE the
            // connect + cwd/version probe. Previously it was emitted only AFTER
            // the probe (several seconds + an SK touch later), so if the user
            // typed a prompt during bootstrap their message landed ABOVE the
            // welcome and looked like it "fell into the void" before the header
            // even appeared. A fixed id makes the cwd/version enrichment below an
            // in-place upsert, so the banner stays the first row.
            val freshChat = resumeId == null && historyMod.history.value.isEmpty()
            if (freshChat) {
                historyMod.emitMsg(AgentMessage.System(
                    id = WELCOME_MSG_ID,
                    subtype = "welcome",
                    sessionId = null, model = null, cwd = null, version = null,
                    toolCount = 0, raw = "",
                ))
            }
            sshLifecycle.openSshClient()
            handshakeTx?.finish()
            _state.value = SessionState.Bootstrapping("checking ${server.agent.cliCommand}")
            val check = ssh.execute(server, secrets, loginShell("command -v ${server.agent.cliCommand} || echo MISSING"))
                .getOrNull().orEmpty()
            if (check.contains("MISSING")) {
                throw IllegalStateException("${server.agent.cliCommand} is not on PATH on the server. Install it (e.g. npm i -g ${server.agent.npmPackage}) and try again.")
            }
            _state.value = SessionState.Running
            // Enrich the welcome banner with the real cwd + CLI version. Same id
            // ⇒ in-place upsert: the row keeps its first position, just gains the
            // detail lines. (No-op if the user already started a turn — the
            // welcome stays put either way.)
            if (freshChat) {
                SilentlyTry.fired("SshAi-AgentSession", "enrich welcome header") {
                    val info = execOnLive(loginShell(
                        "echo --CWD--; pwd 2>/dev/null; echo --VER--; ${server.agent.cliCommand} --version 2>/dev/null | head -1"
                    )).orEmpty()
                    val cwd = info.substringAfter("--CWD--").substringBefore("--VER--").trim().lines().firstOrNull()
                    val version = info.substringAfter("--VER--").trim().lines().firstOrNull()
                    historyMod.emitMsg(AgentMessage.System(
                        id = WELCOME_MSG_ID,
                        subtype = "welcome",
                        sessionId = null,
                        model = null,
                        cwd = cwd,
                        version = version,
                        toolCount = 0,
                        raw = ""
                    ))
                }
            }
            bootstrapTx?.finish()
            Result.success(Unit)
        } catch (t: Throwable) {
            _state.value = SessionState.Failed(
                ai.eight24family.conch.util.ErrorMessages.humanize(t, context = "bootstrap")
            )
            handshakeTx?.finish(io.sentry.SpanStatus.INTERNAL_ERROR)
            bootstrapTx?.finish(io.sentry.SpanStatus.INTERNAL_ERROR)
            // Best-effort categorisation for telemetry. Mirror the
            // FailureKind taxonomy used by SshClient.testConnection.
            val kind = when {
                t.message?.contains("not on PATH", ignoreCase = true) == true -> FailureKind.OTHER
                t.message?.contains("authentication", ignoreCase = true) == true -> FailureKind.AUTH_PASSWORD_REJECTED
                t.message?.contains("auth failed", ignoreCase = true) == true -> FailureKind.AUTH_KEY_REJECTED
                t.message?.contains("UnknownHost", ignoreCase = true) == true ||
                    t.message?.contains("not resolved", ignoreCase = true) == true -> FailureKind.HOST_NOT_RESOLVED
                t.message?.contains("timeout", ignoreCase = true) == true -> FailureKind.TIMEOUT
                t.message?.contains("unreachable", ignoreCase = true) == true ||
                    t.message?.contains("connect", ignoreCase = true) == true -> FailureKind.NETWORK_UNREACHABLE
                else -> FailureKind.OTHER
            }
            Telemetry.connectionFailed(kind, server.agent)
            close()
            Result.failure(t)
        }
    }

    init {
        // Resolve this session's auth method into [authPrep] up front so the
        // first turn already launches under the right credentials.
        recomputeAuthPrep()
    }

    /** Resolve the method to apply: an explicit per-session binding wins;
     *  else (new chat only) the agent's active method; else none. */
    private fun recomputeAuthPrep() {
        scope.launch {
            SilentlyTry.fired("SshAi-Auth", "resolve session auth method") {
                val store = ai.eight24family.conch.di.ServiceLocator.authMethodStore
                val bound = store.sessionMethod(resumeId)
                val key = bound ?: if (bornNew) store.activeMethod(server.id, server.agent) else null
                authPrep = AuthSelector.prefix(server.agent, key)
            }
        }
    }

    /** The CLI just assigned this new session its id — bind it to the active
     *  method (once) so future resumes keep using it even if the user later
     *  switches the active method. Then refresh [authPrep]. */
    private fun onResumeIdAssigned(newId: String?) {
        // A brand-new session just got its id — that's brand-new activity right
        // now. Stamp it so the new chat appears at the top of the list with
        // today's time immediately, instead of waiting for a listing sweep.
        newId?.let { rid ->
            ai.eight24family.conch.di.ServiceLocator.sessionActivity.observeLocal(server.id, rid)
        }
        scope.launch {
            SilentlyTry.fired("SshAi-Auth", "bind new session to auth method") {
                val store = ai.eight24family.conch.di.ServiceLocator.authMethodStore
                if (bornNew && newId != null) {
                    store.activeMethod(server.id, server.agent)?.let { active ->
                        store.bindSessionIfAbsent(newId, active)
                    }
                }
                val bound = store.sessionMethod(newId)
                val key = bound ?: if (bornNew) store.activeMethod(server.id, server.agent) else null
                authPrep = AuthSelector.prefix(server.agent, key)
            }
        }
    }

    /**
     * Wrap a command so it runs in a login shell. Non-interactive SSH `exec`
     * channels do NOT source `~/.bashrc` / `~/.profile` by default, so anything
     * installed via nvm, asdf, or a custom npm prefix stays invisible. Login
     * shell wrapping makes the command see the same PATH the user gets after
     * `ssh user@host`.
     */
    private fun loginShell(cmd: String): String {
        // PATH preamble — same as AgentStatusProbe. Ensures the CLI
        // binaries Conch installed (via the official Claude installer,
        // npm with `prefix=~/.local`, nvm, or a tarball) are visible to
        // EVERY `bash -lc` we fire — not just the install verification
        // shell. Debian's default ~/.bashrc bails on non-interactive
        // shells, so nvm.sh and ~/.local/bin sourced there are
        // invisible without this prep.
        val prep = "export PATH=\"\$HOME/.local/bin:/usr/local/bin:\$PATH\"; " +
            "for nd in \$HOME/.nvm/versions/node/*/bin \$HOME/.local/node-*/bin; do " +
            "[ -d \"\$nd\" ] && export PATH=\"\$nd:\$PATH\"; done; " +
            "[ -s \"\$HOME/.nvm/nvm.sh\" ] && . \"\$HOME/.nvm/nvm.sh\" >/dev/null 2>&1; "
        return "bash -lc ${shellEscape(prep + cmd)}"
    }

    /** Returns true if [text] was just sent locally and is about to be
     *  replayed back to us by the JSONL tail — see
     *  [AgentSessionPromptQueue.wasRecentlySent]. */
    fun wasRecentlySent(text: String): Boolean = promptQueue.wasRecentlySent(text, resumeId)

    /** Pull (and clear) prompts that a turn ABORTED on because the transport
     *  was dead. Called by the VM in retry() BEFORE this session is closed, so
     *  the rebuilt+reconnected session can re-deliver them. */
    fun consumeUndelivered(): List<String> {
        if (undeliveredPrompts.isEmpty()) return emptyList()
        val out = undeliveredPrompts.toList()
        undeliveredPrompts.clear()
        return out
    }

    suspend fun send(text: String, imagePaths: List<String> = emptyList()) {
        val tag = "SshAi-Turn"
        android.util.Log.d(
            tag,
            "send text=${text.length}B images=${imagePaths.size} agent=${server.agent} resume=$resumeId " +
                "state=${_state.value} sshConnected=${sshLifecycle.sshClient?.isConnected} " +
                "scopeActive=${scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true}"
        )
        // The user is sending a NEW message — if a question card is still open
        // (they chose not to answer it; there may be no "skip" option, esp. on a
        // read-only mirrored question), auto-dismiss it. For a LIVE one, deny the
        // pending control so the current turn ends cleanly; either way no Error,
        // the agent just continues with this message (user, 2026-06-26).
        cancelPendingQuestions()

        // Remember this text so the JSONL tail's replay of the same prompt
        // (claude writes every user turn into the session log) doesn't show
        // up as a duplicate row a few seconds later. The UserText itself is
        // emitted by the queue drainer at TURN-START time — when this prompt
        // is FIRST in the queue, that's effectively right now; when a previous
        // turn is still running, it's deferred until that reply lands, so the
        // chat orders as [user1, reply1, user2, reply2] instead of all-users-
        // then-all-replies.
        promptQueue.markSent(text, resumeId)

        // Record local activity NOW so the row jumps to the top of the sessions
        // list (and stamps today's time) without waiting for the next server
        // mtime poll. Only meaningful once we have a resumeId — the first turn of
        // a brand-new session has none yet, but onResumeIdAssigned records it the
        // instant the CLI mints the id (a few hundred ms later, same turn).
        resumeId?.let { rid ->
            ai.eight24family.conch.di.ServiceLocator.sessionActivity
                .observeLocal(server.id, rid)
        }

        promptQueue.enqueue(text, imagePaths)
    }

    /**
     * Run a code review (Codex `review/start`) on the current thread. No-op for
     * non-Codex agents (the `/review` palette entry is gated to Codex anyway).
     * [baseBranch] blank → review the uncommitted changes ("before I push").
     */
    suspend fun startReview(baseBranch: String) {
        if (server.agent != Agent.CODEX) return
        codexAppServer.runReview(baseBranch.takeIf { it.isNotBlank() })
    }

    /**
     * Re-deliver a prompt to the CLI WITHOUT rendering its bubble. Used by the
     * ViewModel to retry a prompt that a previous turn ABORTED on because the
     * transport was dead (see [consumeUndelivered]). The message is already on
     * screen — it was rendered by the original [send] and carried across the
     * reconnect — so emitting it again would double the row. We only
     * [AgentSessionPromptQueue.markSent] it (so the JSONL echo stays deduped)
     * and [AgentSessionPromptQueue.enqueue] it for exec.
     */
    fun redeliver(text: String) {
        android.util.Log.d("SshAi-Turn", "redeliver (echo-free) text=${text.length} resume=$resumeId")
        promptQueue.markSent(text, resumeId)
        // emitOnStart=false: the row is already on screen from the original
        // send (this is the carry-across-reconnect path). emitting again
        // would double the row — the old bug.
        promptQueue.enqueue(text, emitOnStart = false)
    }

    /**
     * Cancel the in-flight turn. See [AgentSessionSshLifecycle.cancelCurrent]
     * for the three-step signal ladder and rationale. Also drops every
     * prompt still queued behind the in-flight one (Stop means "halt
     * everything", not "skip just this turn").
     */
    fun cancelCurrent() {
        // Stop = "cancel current turn AND drop everything queued behind
        // it". Cancelling just the in-flight turn while letting the
        // drainer roll to the next queued prompt would feel weird —
        // the user tapped Stop because they wanted everything to halt.
        promptQueue.clearQueue()
        if (usePersistent()) {
            // Real protocol interrupt — the CLI aborts the turn and emits
            // its result; the stream escalates to a process kill if the
            // interrupt isn't honored within a grace window.
            persistentStream.cancelTurn()
            return
        }
        if (useCodexAppServer()) {
            codexAppServer.cancelTurn()
            return
        }
        if (useGeminiAcp()) {
            geminiAcp.cancelTurn()
            return
        }
        sshLifecycle.cancelCurrent { runner.killZombieRemoteTurn() }
        if (_state.value is SessionState.Working) _state.value = SessionState.Running
    }

    // ──── File transfer & remote-file probes (delegated) ──────────

    /**
     * Outcome of a download attempt. `total` is `-1` when `stat` couldn't
     * report a byte count up front (FIFOs, /proc, etc.) — we still stream
     * but the progress bar is indeterminate.
     */
    sealed interface DownloadOutcome {
        data class Done(val bytes: Long) : DownloadOutcome
        data class Failed(val reason: String) : DownloadOutcome
    }

    /**
     * Remote-file fingerprint returned by [statRemoteFile]. Size +
     * SHA-256 together let the app decide "is this the same file we
     * already downloaded?" without false positives from same-name
     * different-content collisions (`/etc/config.toml` vs
     * `/home/u/config.toml`).
     */
    data class RemoteFileInfo(val sizeBytes: Long, val sha256: String)

    /**
     * Result of a stat probe — distinguishes "file confirmed gone"
     * from "we couldn't reach the server / probe blew up". Without
     * this distinction the caller can't tell whether to cache the
     * negative result or retry later.
     */
    sealed interface RemoteFileProbe {
        data class Exists(val info: RemoteFileInfo) : RemoteFileProbe
        object NotFound : RemoteFileProbe
        object ProbeError : RemoteFileProbe
    }

    suspend fun uploadFile(
        bytes: ByteArray,
        displayName: String,
        onProgress: (Float) -> Unit = {}
    ): String? = fileTransfer.uploadFile(bytes, displayName, onProgress)

    /** Streaming upload (large files) — see [AgentSessionFileTransfer.uploadStream]. */
    suspend fun uploadStream(
        open: () -> java.io.InputStream,
        total: Long,
        displayName: String,
        onProgress: (Float) -> Unit = {}
    ): String? = fileTransfer.uploadStream(open, total, displayName, onProgress)

    suspend fun downloadFile(
        remotePath: String,
        sink: java.io.OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome = fileTransfer.downloadFile(remotePath, sink, onProgress)

    suspend fun probeModelMenu(): String? = fileTransfer.probeModelMenu()

    suspend fun checkRemoteFileExists(remotePath: String): Boolean =
        fileTransfer.checkRemoteFileExists(remotePath)

    suspend fun statRemoteFile(remotePath: String): RemoteFileProbe =
        fileTransfer.statRemoteFile(remotePath)

    suspend fun statRemoteFileSize(remotePath: String): Long? =
        fileTransfer.statRemoteFileSize(remotePath)

    /**
     * Answer a live permission prompt. On the persistent channel this
     * writes the real `control_response` (allow with the original tool
     * input / deny with a reason) and the turn continues; on the legacy
     * one-shot path there is no live process to answer, so only the
     * bubble state updates.
     */
    suspend fun respondPermission(requestId: String, decision: PermissionDecision) {
        // Each channel only answers requests IT owns (own pending map) —
        // safe to offer to all; at most one writes to its stdin.
        persistentStream.respondPermission(requestId, decision)
        codexAppServer.respondPermission(requestId, decision)
        geminiAcp.respondPermission(requestId, decision)
        val resolution = if (decision == PermissionDecision.DENY)
            AgentMessage.PermissionRequest.Resolution.DENIED
        else
            AgentMessage.PermissionRequest.Resolution.ALLOWED
        historyMod.resolvePermission(requestId, resolution)
    }

    /** Answer an AskUserQuestion card: writes the control_response with
     *  the chosen labels and freezes the card with the picks. */
    suspend fun respondQuestion(requestId: String, answers: Map<Int, List<String>>) {
        persistentStream.respondQuestion(requestId, answers)
        codexAppServer.respondQuestion(requestId, answers)
        historyMod.resolveQuestion(requestId, answers)
    }

    /** User typed instead of answering: dismiss any open question card (live OR
     *  read-only mirrored) silently, and for a live one end its turn cleanly so
     *  no Error surfaces. Safe no-op when nothing is open. */
    suspend fun cancelPendingQuestions() {
        persistentStream.cancelPendingQuestions()
        historyMod.cancelUnresolvedQuestions()
    }

    fun close() {
        // Graceful CLI exit first (stdin EOF → it flushes the session
        // file), then the channel + pooled-client release.
        persistentStream.teardownProcess()
        codexAppServer.teardownProcess()
        geminiAcp.teardownProcess()
        sshLifecycle.close(promptQueue.drainerJob)
        if (_state.value !is SessionState.Failed) _state.value = SessionState.Closed
    }

    suspend fun terminate() = withContext(Dispatchers.IO) {
        // Per-message mode keeps no server-side state we own (Claude saves its
        // own session file). Remove resume pointer so a future open starts fresh.
        resumeId = null
        close()
    }

    /** See [AgentSessionSshLifecycle.execOnLive]. */
    suspend fun execOnLive(command: String): String? = sshLifecycle.execOnLive(command)

    /** See [AgentSessionSshLifecycle.execOnLiveWithStdin]. */
    suspend fun execOnLiveWithStdin(command: String, stdin: ByteArray): Boolean =
        sshLifecycle.execOnLiveWithStdin(command, stdin)

    /** Replace history with the given list (used to replay a persisted
     *  session). See [AgentSessionHistory.loadHistory] for dedupe rules. */
    fun loadHistory(messages: List<AgentMessage>) = historyMod.loadHistory(messages)

    /** Append-only update of history. See [AgentSessionHistory.appendMessages]. */
    fun appendMessages(messages: List<AgentMessage>) = historyMod.appendMessages(messages)
}

/** Stable id for the fresh-chat welcome banner. Fixed (not a random UUID) so
 *  the early emit and the later cwd/version enrichment upsert the SAME row. */
private const val WELCOME_MSG_ID = "welcome-banner"

internal fun shellEscape(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

/**
 * Like [shellEscape] but expands a leading `~/` to the server's `$HOME`
 * by emitting `"$HOME"/'<rest>'`. Single-quote escaping disables tilde
 * expansion (`'~/.ssh/foo'` is a literal name starting with `~`), so a
 * naive `shellEscape("~/foo")` would always make `[ -f ... ]` fail
 * even when the file exists. Bash sees the concatenation `"$HOME"/'rest'`
 * as one argument with $HOME expanded.
 */
internal fun shellEscapeRemotePath(value: String): String {
    if (value.startsWith("~/")) {
        return "\"\$HOME\"/" + shellEscape(value.removePrefix("~/"))
    }
    return shellEscape(value)
}
