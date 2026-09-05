package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
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
    // @Volatile: WRITTEN on the reader coroutine (the CLI's session_id arrives
    // in its system/init) and READ on the turn coroutine when building the
    // command. A stale null read there launches with NO `--resume`, and the CLI
    // then starts a WHOLE NEW session file — surfacing as a duplicate row with
    // the same auto-title (user, 2026-07-27).
    @Volatile private var resumeId: String? = null
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
    /** Internal so the home list can gate its synthetic rows: only a chat
     *  born WITHOUT a session may mint one — a resumed chat always has its
     *  cached row already, and the id the CLI announces on resume is not a
     *  file (see HomeSessionsViewModel's live-session block). */
    internal val bornNew: Boolean = (resumeId == null)

    /** Cached shell prefix that forces the session's chosen auth method
     *  (resolved async from [ai.eight24family.conch.data.AuthMethodStore]).
     *  "" = no method chosen = launch byte-identical to before. */
    @Volatile private var authPrep: String = ""

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Epoch ms when the CURRENT app-driven turn entered Working; 0 when no
     * turn is running. PROCESS-scoped on purpose: the chat VM dies with the
     * screen, and a re-entered mid-turn chat used to restart the visible
     * elapsed timer from the adoption moment. */
    @Volatile var turnStartedAtMs: Long = 0L
        private set

    init {
        scope.launch {
            var prev: SessionState = SessionState.Idle
            state.collect { st ->
                if (st is SessionState.Working && prev !is SessionState.Working) {
                    turnStartedAtMs = System.currentTimeMillis()
                } else if (st !is SessionState.Working && prev is SessionState.Working) {
                    turnStartedAtMs = 0L
                }
                prev = st
            }
        }
    }

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
        setResumeId = { newId -> val was = resumeId; resumeId = newId; forkOnce = false; onResumeIdAssigned(newId, was) },
        dropResumeId = { resumeId = null },
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
        setResumeId = { newId -> val was = resumeId; resumeId = newId; forkOnce = false; onResumeIdAssigned(newId, was) },
        cwdSnapshot = { cwdSnapshot },
        getModelOverride = { modelOverride },
        getReasoningOverride = { reasoningEffortOverride },
        getApprovalMode = { approvalMode },
        loginShell = ::loginShell,
        getAuthPrep = { authPrep },
        getForkOnce = { forkOnce },
        onPromptUndelivered = { text -> undeliveredPrompts.add(text) },
        onThinkingTokens = { n -> liveThinkingTokens.value = n },
        onInitState = { st ->
            // Publish into the spec-level globals FIRST (default model/key,
            // unavailable set, effort ladder) so any observer of the flow —
            // the VM's catalog adopter included — reads warm values.
            ai.eight24family.conch.agent.claude.ClaudeSpec.adoptInitState(st, server.id)
            _claudeInitState.value = st
        },
        onLoopWakeup = { input ->
            _loopArmed.value = input?.let { LoopWatch.read(it, System.currentTimeMillis()) }
        },
        onLoopCron = { input -> _loopArmed.value = LoopWatch.readCron(input) },
        onLoopNotArmed = { _loopNotArmed.value = System.currentTimeMillis() },
    )

    /** A `/loop` armed by the CLI in THIS live process — null when no loop is
     *  running. Live-only on purpose: pending wakeups die with the process, so
     *  a chip resurrected from cached history would promise a loop that isn't
     *  there. See [LoopWatch]. */
    private val _loopArmed = MutableStateFlow<LoopWatch.Armed?>(null)
    val loopArmed: StateFlow<LoopWatch.Armed?> = _loopArmed.asStateFlow()

    /** The loop is over — the process is gone, or the user stopped it. */
    internal fun clearLoop() { _loopArmed.value = null }

    /** Timestamp of the last `/loop …` turn that ended having scheduled
     *  nothing. The chat surfaces it once; 0 = never happened. */
    private val _loopNotArmed = MutableStateFlow(0L)
    val loopNotArmed: StateFlow<Long> = _loopNotArmed.asStateFlow()

    /** The CLI's own `initialize` handshake data (models / commands /
     *  subagents / account), republished on every persistent launch. Null
     *  until the first handshake lands (or on the one-shot fallback). */
    private val _claudeInitState =
        MutableStateFlow<ai.eight24family.conch.agent.claude.ClaudeInitState?>(null)
    internal val claudeInitState: StateFlow<ai.eight24family.conch.agent.claude.ClaudeInitState?> =
        _claudeInitState.asStateFlow()

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
        setResumeId = { newId -> val was = resumeId; resumeId = newId; forkOnce = false; onResumeIdAssigned(newId, was) },
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
        setResumeId = { newId -> val was = resumeId; resumeId = newId; forkOnce = false; onResumeIdAssigned(newId, was) },
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

    private val fileTransfer = AgentSessionFileTransfer(sshLifecycle, _state)

    /** Agent-side session id captured from the first stream-json system event. */
    val agentSessionId: String? get() = resumeId

    /** See [AgentSessionSshLifecycle.isAlive]. */
    fun isAlive(): Boolean = sshLifecycle.isAlive()

    /**
     * Can this session still take a prompt AT ALL?
     *
     * ⚠ THE ONE QUESTION EVERY DRAIN MUST ASK BEFORE IT CLAIMS A MESSAGE.
     *
     * [send] hands the text to [promptQueue], whose drainer runs on [scope]. If
     * that scope is cancelled the enqueue still succeeds, `scope.launch` returns
     * an already-cancelled Job, the drainer body never runs, and NOTHING throws —
     * the prompt is added to a deque nobody will ever read. The chat bubble is
     * emitted from inside the drainer too, so the message does not even appear.
     *
     * And the scope IS cancelled on a routine failure: `start()`'s catch calls
     * `close()` (a missing agent binary, a rejected host key, a transport that
     * dies during the handshake), while `AgentSessionManager.openOrGet` returns
     * the object regardless and the ViewModel caches it. So a chat can hold a
     * session that looks present, reports `Failed`, and silently eats everything
     * handed to it — which is how a queue of the user's follow-ups could be
     * claimed, have its crash-insurance drafts deleted, and vanish with no row,
     * no bubble and no error (2026-08-18 audit).
     *
     * Cheap and honest: it asks the scope itself, not a state enum.
     */
    fun canAcceptSend(): Boolean =
        scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    /** True while a CLI process for this chat is actually running, so the
     *  conversation is still warm in its memory. Ask THIS — not [isAlive] —
     *  before telling the user anything about what the next turn will cost:
     *  a transport can be alive (rebuilt by the pool) with no process behind
     *  it, and that turn re-reads the whole session file. */
    fun hasLiveCliProcess(): Boolean = persistentStream.processAlive

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
                SilentlyTry.fired("Conch-AgentSession", "enrich welcome header") {
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
            Result.success(Unit)
        } catch (t: Throwable) {
            _state.value = SessionState.Failed(
                ai.eight24family.conch.util.ErrorMessages.humanize(t, context = "bootstrap")
            )
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
            SilentlyTry.fired("Conch-Auth", "resolve session auth method") {
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
    private fun onResumeIdAssigned(newId: String?, previousId: String? = null) {
        // ⛔ A CHANGED ID NEVER RETIRES THE PREVIOUS ID'S ROW. This used to call
        // sessionsCache.removeRow(previousId) on the theory that "the CLI moved
        // this conversation to a new file" — but the CLI never renames a resumed
        // session (proven on 2.1.220/.258/.260, every mode), so the only real
        // id changes are `--fork-session` and `/clear`, and in BOTH the previous
        // file stays a live, separate conversation whose row must remain. The
        // "ghost row whose file was already gone" that motivated the removal was
        // the /context probe's throwaway copy (UsageProbe.CLAUDE_CONTEXT_CMD),
        // fixed at the source. Adopting the new id is still right: the CLI
        // writes there from now on (2026-07-27).
        //
        // A brand-new session just got its id — that's brand-new activity right
        // now. Stamp it so the new chat appears at the top of the list with
        // today's time immediately, instead of waiting for a listing sweep.
        // ⚠ ONLY FOR A CHAT THAT WAS BORN NEW. Stamping activity for whatever
        // id the CLI announces marks it "recently active", and SessionsCache
        // carries recently-active rows across a listing that doesn't mention
        // them — so a resumed chat must not stamp ids it did not create.
        if (bornNew) newId?.let { rid ->
            ai.eight24family.conch.di.ServiceLocator.sessionActivity.observeLocal(server.id, rid)
        }
        scope.launch {
            SilentlyTry.fired("Conch-Auth", "bind new session to auth method") {
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
        // PATH preamble — the SHARED one (RemoteEnv), which knows every
        // mainstream install home: npm-prefix/official installer
        // (~/.local/bin), nvm, Homebrew on macOS, volta, bun, pnpm, asdf,
        // snap, fnm. A hand-rolled copy here had drifted to a subset once
        // already. RemoteEnv.portable keeps the whole thing runnable on
        // servers with NO bash (Alpine/BusyBox, stock BSD) — Play users
        // bring whatever server they have (2026-08-17).
        val prep = RemoteEnv.PATH_PREAMBLE_INLINE
        return RemoteEnv.portable("bash -lc ${shellEscape(prep + cmd)}")
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
        val tag = "Conch-Turn"
        // A new turn supersedes any rewind: the mirror may speak freely again.
        rewindSuppressed = emptySet()
        android.util.Log.d(
            tag,
            "send text=${text.length}B images=${imagePaths.size} agent=${server.agent} resume=$resumeId " +
                "state=${_state.value} sshConnected=${sshLifecycle.liveClient()?.isConnected} " +
                "scopeActive=${scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true}"
        )
        // The user is sending a NEW message — if a question card is still open
        // (they chose not to answer it; there may be no "skip" option, esp. on a
        // read-only mirrored question), auto-dismiss it. For a LIVE one, deny the
        // pending control so the current turn ends cleanly; either way no Error,
        // the agent just continues with this message (user, 2026-06-26).
        cancelPendingQuestions()

        // ⛔ A DEAD SCOPE MUST NOT EAT THE PROMPT.
        //
        // Belt for [canAcceptSend]'s brace: every caller is supposed to ask
        // first, but if one ever forgets, the failure mode is invisible —
        // `enqueue` succeeds, `scope.launch` hands back an already-cancelled Job,
        // the drainer (which is also what emits the chat bubble) never runs, and
        // the text is gone without one log line. Route it to the SAME undelivered
        // store a turn aborted by a dead transport uses, so the ViewModel's exit
        // path persists it as a draft and the user gets their words back instead
        // of silence.
        if (!canAcceptSend()) {
            android.util.Log.e(
                tag,
                "send REFUSED: session scope is cancelled — parking ${text.length}B as undelivered",
            )
            undeliveredPrompts.add(text)
            return
        }

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
     * Control-channel capabilities the CLI already exposes and the app was not
     * asking for. All null/false when this chat is not on a persistent Claude
     * stream — the callers say so rather than pretending.
     */
    suspend fun workspaceDiffText(): String? =
        if (usePersistent()) persistentStream.workspaceDiff() else null

    suspend fun stopTask(taskId: String): Boolean =
        if (usePersistent()) persistentStream.stopTask(taskId) else false

    suspend fun backgroundRunningTasks(toolUseId: String? = null): Boolean =
        if (usePersistent()) persistentStream.backgroundTasks(toolUseId) else false

    suspend fun sessionCostText(): String? =
        if (usePersistent()) persistentStream.sessionCost() else null

    suspend fun planText(): String? =
        if (usePersistent()) persistentStream.plan() else null

    suspend fun cliVersion(): String? =
        if (usePersistent()) persistentStream.binaryVersion() else null

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
    fun redeliver(text: String, imagePaths: List<String> = emptyList()) {
        android.util.Log.d("Conch-Turn", "redeliver (echo-free) text=${text.length} images=${imagePaths.size} resume=$resumeId")
        promptQueue.markSent(text, resumeId)
        // emitOnStart=false: the row is already on screen (carried across a
        // reconnect, OR shown optimistically by the ViewModel for a mid-turn
        // send). Emitting again would double the row.
        promptQueue.enqueue(text, imagePaths, emitOnStart = false)
    }

    /**
     * Cancel the in-flight turn. See [AgentSessionSshLifecycle.cancelCurrent]
     * for the three-step signal ladder and rationale. Also drops every
     * prompt still queued behind the in-flight one (Stop means "halt
     * everything", not "skip just this turn").
     */
    /**
     * Fork on the next launch: inherit this conversation, write to a NEW id
     * (`--fork-session`). Set when a chat is opened as a fork of another; the
     * CLI answers with a fresh session_id on its first `system init`, and
     * [setResumeId] clears the flag there — so the fork is forked exactly once
     * and every later launch is an ordinary `--resume` of its own session.
     */
    @Volatile var forkOnce: Boolean = false

    /** True while the prompt-queue drainer is mid-turn. The VM parks new sends
     * in its VISIBLE outbox then, instead of handing them to the invisible
     * internal queue — a message queued behind a wedged turn simply vanished
     * from the UI. */
    val drainerBusy: Boolean get() = promptQueue.drainerJob?.isActive == true

    /** Stop a `/loop` that is asleep between ticks. Stop-the-turn can't: there
     *  is no turn. The CLI drops its pending wakeups on an interrupt. */
    /**
     * Restart the CLI process for this chat.
     *
     * Everything the CLI reads only at startup — MCP servers from `.mcp.json`,
     * a new binary after an update, changed settings — is invisible to a
     * process that is already running, and ours deliberately outlives every
     * turn. Until now the only way to get a fresh one was to disconnect the
     * whole server or kill the app.
     *
     * The conversation is untouched: the next send relaunches with `--resume`
     * over the same prefix the prompt cache already holds. A turn in flight is
     * ended, same as Stop.
     */
    fun restartCli() {
        clearLoop()
        if (usePersistent()) persistentStream.cancelTurn() else cancelCurrent()
        scope.launch { persistentStream.teardownProcess() }
    }

    fun stopLoop() {
        clearLoop()
        if (usePersistent()) persistentStream.cancelIdleLoop() else cancelCurrent()
    }

    /** [force] — Stop is aimed at a turn running in OUR OWN persistent process
     *  that the app's turn tracking has lost (reopened mid-turn: procAlive but
     *  state desynced off Working). Escalates to tearing our process down on
     *  `procAlive` alone, so Stop can't no-op into the external pgrep kill (which
     *  redelivers). See [ChatViewModel.stopCurrent]. */
    fun cancelCurrent(force: Boolean = false) {
        clearLoop()
        // Stop = "cancel current turn AND drop everything queued behind
        // it". Cancelling just the in-flight turn while letting the
        // drainer roll to the next queued prompt would feel weird —
        // the user tapped Stop because they wanted everything to halt.
        promptQueue.clearQueue()
        if (usePersistent()) {
            // Real protocol interrupt — the CLI aborts the turn and emits
            // its result; the stream escalates to a process kill if the
            // interrupt isn't honored within a grace window.
            persistentStream.cancelTurn(force)
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

    /** @param onFailure the reason, for the attachment chip — NOT the transcript. */
    suspend fun uploadFile(
        bytes: ByteArray,
        displayName: String,
        onProgress: (Float) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ): String? = fileTransfer.uploadFile(bytes, displayName, onProgress, onFailure)

    /** Streaming upload (large files) — see [AgentSessionFileTransfer.uploadStream]. */
    suspend fun uploadStream(
        open: () -> java.io.InputStream,
        total: Long,
        displayName: String,
        onProgress: (Float) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ): String? = fileTransfer.uploadStream(open, total, displayName, onProgress, onFailure)

    suspend fun downloadFile(
        remotePath: String,
        sink: java.io.OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome = fileTransfer.downloadFile(remotePath, sink, onProgress)

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

    /** True iff a live control_request (AskUserQuestion / permission) is pending on
     *  the persistent stream, i.e. the turn is BLOCKED on the user. The tail-poll's
     *  turn-state detector reads this for WAITING-FOR-USER (never hits the JSONL). */
    fun hasPendingControl(): Boolean = persistentStream.hasPendingControl()

    // ── Live control-channel operations (Claude persistent stream only;
    //    every one degrades to false/null on the one-shot fallback, and the
    //    caller's existing restart/probe path takes over unchanged) ────────

    /** Apply a model pick to the RUNNING session via `set_model` — no process
     *  restart, no session re-read. False → not applied (dead channel / CLI
     *  refused); the modelOverride the caller set still lands via the launch
     *  params on the next turn. */
    suspend fun applyModelLive(model: String?): Boolean =
        usePersistent() && persistentStream.trySetModel(model)

    /** Apply an effort pick live via `set_max_thinking_tokens` (levels with a
     *  budget mapping only — xhigh/ultracode still restart). */
    suspend fun applyReasoningLive(effort: String?): Boolean =
        usePersistent() && persistentStream.trySetReasoning(
            effort,
            ai.eight24family.conch.agent.claude.ClaudeSpec.thinkingBudget(effort),
        )

    /** Apply an approval-mode change live via `set_permission_mode`. */
    suspend fun applyApprovalLive(
        mode: ai.eight24family.conch.data.prefs.AgentApprovalMode,
    ): Boolean = usePersistent() && persistentStream.trySetPermissionMode(mode)

    /** `/context` numbers for this live session over the control channel —
     *  null when the channel is down (caller falls back to the copy-probe). */
    suspend fun fetchContextUsageLive(): kotlinx.serialization.json.JsonObject? =
        if (usePersistent()) persistentStream.getContextUsage() else null

    /** Plan-limit windows from the CLI's own cache over the control channel. */
    suspend fun fetchUsageLive(): kotlinx.serialization.json.JsonObject? =
        if (usePersistent()) persistentStream.getUsage() else null

    /**
     * Server-side file search for @-mentions.
     *
     * PRIMARY: the CLI's own index over the control channel. VERIFIED LIVE
     * (2.1.220, 2026-08-02) that this answers an EMPTY query with a real
     * ranked list but returns NOTHING for any typed prefix in headless mode —
     * the index is populated by the interactive TUI's file watcher, which our
     * launches never run. Shipping only the primary would have meant a strip
     * that appears once on "@" and then goes blank the moment the user types,
     * i.e. a feature that silently does nothing.
     *
     * FALLBACK: one bounded listing of the session's cwd, filtered by the
     * token server-side. Runs ONLY when the CLI's index came back empty for a
     * non-empty query, so the better source always wins where it works.
     */
    suspend fun fileSuggestions(query: String): List<String>? {
        if (!usePersistent()) return null
        val fromCli = persistentStream.fileSuggestions(query)
        if (query.isBlank()) return fromCli
        if (!fromCli.isNullOrEmpty()) return fromCli
        // Token is user input heading for a shell — keep it to path-ish
        // characters and let shellEscape handle the rest.
        val token = query.take(64)
        if (token.any { it.isWhitespace() }) return fromCli
        val cwd = cwdSnapshot?.takeIf { it.isNotBlank() } ?: return fromCli
        val script = "cd " + shellEscape(cwd) + " 2>/dev/null || exit 0; " +
            "{ command -v rg >/dev/null 2>&1 && rg --files --hidden -g '!.git' 2>/dev/null " +
            "|| find . -type f -not -path '*/.git/*' -not -path '*/node_modules/*' 2>/dev/null " +
            "| sed 's|^\\./||'; } | grep -iF -- " + shellEscape(token) + " | head -8"
        val out = SilentlyTry.logged("Conch-Turn", "file suggestion fallback") {
            execOnLive(loginShell(script))
        }
        val hits = out?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("bash:") }
            ?.take(8)
            ?.toList()
            .orEmpty()
        return if (hits.isEmpty()) fromCli else hits
    }

    /** Rename this session's title on the server (shows in `claude --resume`). */
    suspend fun renameSession(title: String): Boolean =
        usePersistent() && persistentStream.renameSession(title)

    /**
     * Rewind the CONVERSATION to just before the user turn [recordUuid],
     * then drop that turn and everything after it from the on-screen history.
     *
     * Safe and reversible in the sense that matters: nothing on disk changes
     * (files are a SEPARATE, confirmed step — see [rewindFiles]), and the
     * discarded branch stays in the server's transcript, it is just no longer
     * the active chain.
     */
    suspend fun rewindConversation(
        recordUuid: String,
    ): RewindResult {
        if (!usePersistent()) {
            return RewindResult(false, error = "no live session")
        }
        val res = persistentStream.rewindConversation(recordUuid)
        if (res.ok) {
            val dropped = historyMod.truncateFromUserRecord(
                res.targetMessageUuid ?: recordUuid,
                fallbackText = res.prefillText,
            )
            // ⚠ THE MIRROR WILL TRY TO PUT IT BACK. The tail-poll parses only
            // the NEW bytes of the session file, and that window does not
            // contain the `last-prompt` back-pointer that proves the branch was
            // abandoned — so the chain filter cannot see it and the discarded
            // turn is appended straight back onto the chat we just truncated
            // (measured on device: "dropped 7 row(s)", row back seconds later).
            // Remember what we removed and refuse it until the next send.
            // Normalised, like every other body comparison here: the row we
            // dropped came from the display, the row the mirror tries to put
            // back comes from the file, and those two are not byte-identical.
            rewindSuppressed = dropped.mapNotNullTo(HashSet()) { m ->
                when (m) {
                    is AgentMessage.UserText -> userBodyKey(m.text)
                    is AgentMessage.AssistantText -> m.text.trim()
                    else -> null
                }?.takeIf { it.isNotEmpty() }
            }
            android.util.Log.i(
                "Conch-Turn",
                "rewind: dropped ${dropped.size} row(s) at $recordUuid, " +
                    "suppressing ${rewindSuppressed.size} body(ies) until next send",
            )
        }
        return res
    }

    /**
     * Find the JSONL record uuid of the LAST user turn whose text matches
     * [text] — the rewind anchor for a row that has none.
     *
     * Why this exists: a bubble is rendered optimistically the instant it is
     * sent, and the file echo that carries the uuid is DROPPED as a duplicate;
     * a reopened-but-still-alive session is never re-read from the file
     * either. Depending on the stamp arriving made rewind unavailable on
     * exactly the messages a user most wants to undo — the ones they just
     * sent. Asking the server for the anchor makes the gesture deterministic.
     *
     * Bounded: reads the tail of the session file, matches on the exact
     * trimmed text, returns the LAST match (repeated prompts → the newest).
     */
    suspend fun resolveUserRecordUuid(text: String): String? {
        val rid = resumeId ?: return null
        if (!Regex("^[a-fA-F0-9-]{16,40}$").matches(rid)) return null
        val body = userBodyKey(text).ifBlank { return null }
        val script = "f=\$(ls \$HOME/.claude/projects/*/" + shellEscape(rid) + "'.jsonl' 2>/dev/null | head -1); " +
            "[ -n \"\$f\" ] || exit 0; tail -c 2000000 \"\$f\" | grep '\"type\":\"user\"' | tail -200"
        val out = SilentlyTry.logged("Conch-Turn", "resolve rewind anchor") {
            execOnLive(loginShell(script))
        } ?: return null
        // Parse app-side (TURN-STATE-IS-LOCAL-1: never depend on jq being on
        // the box). Last match wins.
        var found: String? = null
        for (line in out.lineSequence()) {
            if (!line.contains("\"type\":\"user\"")) continue
            val msgs = AgentSpecRegistry[server.agent].parseStreamLine(line)
            val u = msgs.filterIsInstance<AgentMessage.UserText>()
                .firstOrNull { userBodyKey(it.text) == body } ?: continue
            found = u.recordUuid ?: found
        }
        return found
    }

    /** Bodies of rows a rewind just removed. The file mirror re-parses only
     *  fresh bytes and would re-append them; cleared on the next send, when
     *  the conversation has genuinely moved on. */
    @Volatile private var rewindSuppressed: Set<String> = emptySet()

    /** True when [text] belongs to a turn the user just rewound away. */
    fun isSuppressedByRewind(text: String): Boolean =
        rewindSuppressed.isNotEmpty() &&
            (text.trim() in rewindSuppressed || userBodyKey(text) in rewindSuppressed)

    /** Files-only rewind for the turn [recordUuid]. [dryRun] reports what
     *  WOULD change and touches nothing. */
    suspend fun rewindFiles(
        recordUuid: String,
        dryRun: Boolean,
    ): FileRewindResult {
        if (!usePersistent()) {
            return FileRewindResult(false, error = "no live session")
        }
        return persistentStream.rewindFiles(recordUuid, dryRun)
    }

    /** The tail-poll detected this session's LIVE turn is stuck: [state] is
     *  Working but the authoritative session file says the turn ended and is
     *  frozen. The persistent reader wedged (e.g. a `conch-bridge` loopback tool)
     *  and never saw `result`. Complete + tear down the wedged persistent reader
     *  (Claude control channel), then make sure we leave Working regardless of
     *  channel — the file is authoritative that the turn is done. */
    fun reconcileStuckTurn() {
        if (_state.value !is SessionState.Working) return
        persistentStream.reconcileStuckTurn()
        // Safety net for channels without an active persistent turn (the clean
        // path above no-ops): the file proved the turn ended, so don't stay stuck.
        if (_state.value is SessionState.Working) _state.value = SessionState.Running
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

    /** Teach an optimistic user bubble the JSONL record uuid its dropped file
     *  echo carried — the handle rewind is addressed by. See
     *  [AgentSessionHistory.stampUserRecordUuid]. */
    fun stampUserRecordUuid(text: String, recordUuid: String): Boolean =
        historyMod.stampUserRecordUuid(text, recordUuid)
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
