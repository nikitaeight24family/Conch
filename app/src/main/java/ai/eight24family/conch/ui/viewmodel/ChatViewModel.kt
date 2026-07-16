package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.RemoteSession
import ai.eight24family.conch.agent.UsageProbe
import ai.eight24family.conch.agent.UsageReport
import ai.eight24family.conch.agent.ServerStats
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.analytics.Telemetry
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])

    /** Agent chosen on the picker screen — overrides any stale value on the Server record. */
    private val initialAgent: Agent? = savedStateHandle.get<String>("agent")
        ?.let { SilentlyTry.logged("SshAi-Chat", "parse initial agent") { Agent.valueOf(it) } }

    /** Optional remote (CLI-managed) session id to attach via `--resume`. */
    private val initialResumeId: String? = savedStateHandle.get<String>("resume")?.takeIf { it.isNotBlank() }

    /** Optional path to the saved session file on the server, used to replay history. */
    private val initialResumePath: String? = savedStateHandle.get<String>("path")?.takeIf { it.isNotBlank() }

    /** Pre-known model name from the tapped session row (RemoteSession.model).
     *  When present, ChatViewModel uses it as the initial value for
     *  [_sessionInitialModel] so the topbar shows the real model from
     *  frame zero — no "codex" / "Opus 4.7" / loading-spinner flicker
     *  while we wait for the sessions list to load and openRemoteSession()
     *  to run.
     */
    private val initialSessionModel: String? = savedStateHandle.get<String>("model")?.takeIf { it.isNotBlank() }

    /** Pre-known reasoning effort from the tapped session row
     *  (`RemoteSession.reasoning`, parsed from JSONL header). Used as
     *  the initial value for [_sessionInitialReasoning] so the topbar's
     *  reasoning sub-label shows the actual effort this chat ran on,
     *  not whatever the user's config.toml currently has globally.
     */
    private val initialSessionReasoning: String? = savedStateHandle.get<String>("reasoning")?.takeIf { it.isNotBlank() }

    /** Search-result highlight query: when this chat was opened by
     *  tapping a global-search hit, ChatScreen reads this on first
     *  composition and paints a highlight span over the match. Null
     *  on the normal session-row tap path. */
    val initialSearchQuery: String? = savedStateHandle.get<String>("q")?.takeIf { it.isNotBlank() }

    /** Stable id of the matched AgentMessage — Telegram-pattern anchor.
     *  ChatScreen looks up the ord via `messages.indexOfFirst { it.id == mid }`
     *  once the cached JSONL has been parsed. Using a stable id (not a
     *  positional ordinal) means parsing differences between the index-time
     *  pass and the chat-open pass can never point at the wrong message.
     *  Null on normal opens. */
    val initialMatchMsgId: String? = savedStateHandle.get<String>("mid")?.takeIf { it.isNotBlank() }

    /** Position of the matched AgentMessage in the parsed list. This
     *  is the PRIMARY navigation anchor: deterministic for any agent
     *  given identical (agent spec, JSONL bytes), so it works for
     *  Codex/Gemini where [initialMatchMsgId] is a random UUID that
     *  cannot survive across parses. Claude can still verify via the
     *  stable msgId; Codex/Gemini just trust the ordinal. */
    val initialMatchOrdinal: Int = savedStateHandle.get<String>("ord")?.toIntOrNull() ?: -1

    /** Char offset of the EXACT occurrence the user tapped, inside the
     *  matched message's body text. Computed at search time from the
     *  FTS4 `offsets()` byte offset → UTF-16 char offset conversion, so
     *  no URL marshaling of fragile substrings — just a single int
     *  travels through the route. ChatScreen uses this for BOTH the
     *  highlight occurrence and the scroll-centre target.
     *  -1 = no occurrence specified (normal open, or search-opened
     *  without a specific occurrence). */
    val initialMatchCharOffset: Int = (savedStateHandle.get<String>("off")?.toIntOrNull() ?: -1)
        .also {
            android.util.Log.d(
                "SshAi-Hl",
                "VM init: q='${savedStateHandle.get<String>("q")}' mid='${savedStateHandle.get<String>("mid")}' ord=${savedStateHandle.get<String>("ord")} off=$it"
            )
        }


    // ──────── Search-opened connect state ────────
    /**
     * UX contract: tapping a global-search hit MUST land the user in
     * the chat — never pop them back to the server list because they're
     * not connected. The chat surface paints fine from local cache
     * (HistoryCache lives in filesDir), and the user came here to READ
     * the match they found.
     *
     * Connection comes up opportunistically:
     *  - Password / software-key servers (no FIDO touch) — connect
     *    silently in the background.
     *  - SK-keyed servers — show our standard touch dialog. If the user
     *    cancels, we DON'T eject; they stay in read-only mode and can
     *    tap the chip later to retry.
     *
     * State machine + StateFlow live in [ChatViewModelSearchConn]; the orchestration
     * coroutine stays here because it needs the SSH pool, server repo, SK signer
     * flow, and access to [startNewChat] + [refreshSessions].
     */
    private val searchConnCoord = ChatViewModelSearchConn()
    val searchOpenConnState: StateFlow<ChatViewModelSearchConn.State> get() = searchConnCoord.state

    /** Live "are we connected to this chat's server right now" — drives the dot
     * next to the server name in the top bar. Reactive: `userHeldIds` is updated
     * by EVERY connect path (userConnect FIDO, userConnectEphemeral silent
     * device-key, connectAllSeamlessSilently) AND by userDisconnect +
     * pruneDeadUserHeld — so the dot tracks the real transport with no polling.
     * Seeded from peek() so a chat opened on an already-held server is green at
     * frame zero instead of flashing offline. */
    val connected: StateFlow<Boolean> =
        ServiceLocator.sshConnectionPool.userHeldIds
            .map { serverId in it }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                ServiceLocator.sshConnectionPool.peek(serverId) != null,
            )

    /** Kick off an opportunistic connect for the chat's server. Idempotent —
     *  no-op when a connect is already in flight or the pool is live.
     *
     * Any failure (cancel, server unreachable, malformed SK row, …) just
     * flips back to Idle. The chip then reads "offline · tap to connect" —
     * never an angry red "connect failed". User explicitly pushed back: */
    fun beginSearchOpenedConnect(silent: Boolean = false) {
        if (searchConnCoord.get() == ChatViewModelSearchConn.State.Connecting) return
        if (ServiceLocator.sshConnectionPool.peek(serverId) != null) {
            // Already connected: on a silent open show NOTHING (Hidden); only an
            // explicit tap/send wants the "connected" confirmation.
            searchConnCoord.set(
                if (silent) ChatViewModelSearchConn.State.Hidden
                else ChatViewModelSearchConn.State.Connected
            )
            return
        }
        searchConnCoord.set(ChatViewModelSearchConn.State.Connecting)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val server = repo.getById(serverId) ?: run {
                    searchConnCoord.set(ChatViewModelSearchConn.State.Idle)
                    return@launch
                }
                val secrets = repo.getSecrets(serverId)
                val skPrimary = secrets.skKeys.firstOrNull()
                if (skPrimary != null) {
                    // Seamless reconnect: if the opt-in hardware device key is
                    // enrolled, reconnect SILENTLY first — NO tap. This is the
                    // "switched Wi-Fi → mobile data, then wrote in chat — don't
                    // re-ask for the key" path. Only fall back to the FIDO touch
                    // when seamless is off / no device key / it fails. Mirrors
                    // the pool's own reconnectHeldOnNetworkChange gate (line ~361).
                    val silentUp = ai.eight24family.conch.ssh.EphemeralSshKey.exists(serverId) &&
                        ServiceLocator.sshConnectionPool.userConnectEphemeral(server) != null
                    if (silentUp) {
                        android.util.Log.d("SshAi-Chat", "search/open connect: silent device-key reconnect, no tap")
                    } else if (silent) {
                        // On-OPEN auto-connect must never force a FIDO touch (key on
                        // send/tap, not on open). The device key didn't bring it up,
                        // so leave the chip at an honest "offline · tap to connect"
                        // — the user taps (or sends) to do the touch.
                        searchConnCoord.set(ChatViewModelSearchConn.State.Idle)
                        return@launch
                    } else {
                        val info = skPrimary.securityInfo ?: run {
                            searchConnCoord.set(ChatViewModelSearchConn.State.Idle)
                            return@launch
                        }
                        val signer = awaitSkSignerFromUi(info)
                        if (signer == null) {
                            searchConnCoord.set(ChatViewModelSearchConn.State.Idle)
                            return@launch
                        }
                        ServiceLocator.sshConnectionPool.userConnect(server, secrets, signer)
                        markSkOpDone()
                    }
                } else {
                    ServiceLocator.sshConnectionPool.userConnect(server, secrets, null)
                }
                // Silent (on-open) connect → stay invisible; explicit tap/send →
                // show "connected" confirmation.
                searchConnCoord.set(
                    if (silent) ChatViewModelSearchConn.State.Hidden
                    else ChatViewModelSearchConn.State.Connected
                )
                // Pool is up now. The chat opened in offline-readonly
                // mode so startNewChat's earlier invocation skipped the
                // AgentSession bootstrap. Re-run it so sends start
                // working without the user having to back out + re-
                // enter the chat. startNewChat will re-check the
                // offline guard, see the pool is live, and proceed
                // through the normal session-start path.
                val agent = _currentAgent.value
                startNewChat(
                    agent = agent,
                    resumeIdParam = initialResumeId,
                    resumeFilePath = initialResumePath,
                    // Carry the read-only display (incl. the optimistic message the
                    // user just sent) so the rebuilt session keeps it on screen
                    // instead of momentarily blanking to the stale cache.
                    seedMessages = _messagesBySession.value[_localSessionId.value],
                )
                refreshSessions()
            } catch (t: Throwable) {
                // Clear the SK touch dialog so a failed tap — e.g. TagLost (key
                // lifted too early during the CTAP enumerate) — doesn't leave it
                // STUCK on "tap your key" with dead taps: the connect coroutine has
                // ended, so further taps complete an already-finished deferred and
                // do nothing. No-op for non-SK servers. The user re-sends to retry
                // with a fresh tap.
                cancelSkTouch()
                // Any failure quietly flips back to Idle. Chip says
                // "offline · tap to connect" — user knows they cancelled
                // or that something went sideways; the red error variant
                // was noise.
                searchConnCoord.set(ChatViewModelSearchConn.State.Idle)
                android.util.Log.w(
                    "SshAi-Chat",
                    "search-opened auto-connect ended: ${t.javaClass.simpleName}: ${t.message?.take(160)}"
                )
            }
        }
    }

    /** User dismissed the "connected" chip after fade. No-op state
     *  transition just so the chip can collapse without retriggering. */
    fun acknowledgeConnectedChip() {
        if (searchConnCoord.get() == ChatViewModelSearchConn.State.Connected) {
            // Keep state as Connected (we still are); the chip's own
            // fade-out is controlled by a timer in ChatScreen.
        }
    }

    private val repo = ServiceLocator.serverRepository
    private val sessionsManager = ServiceLocator.agentSessions
    private val discovery = ServiceLocator.sessionDiscovery
    private val uploadCache = ServiceLocator.uploadCache

    private val _server = MutableStateFlow<Server?>(null)
    val server: StateFlow<Server?> = _server.asStateFlow()
    /** Cached server name for frame-zero topbar render, before [server] loads
     *  from Room. Null on the very first open of a server (then Room fills it). */
    val cachedServerName: String? get() = cachedServerNameFor(serverId)

    // Initialise with the agent that was passed in via SavedStateHandle —
    // that's the agent the user picked on AgentPicker or whose Sessions
    // list they tapped. Defaulting to Agent.CLAUDE caused the topbar to
    // render the claude label ('Opus 4.7') on the very first frame of
    // every codex/gemini chat, then flicker to the right agent the
    // moment the init coroutine ran.
    private val _currentAgent = MutableStateFlow(initialAgent ?: Agent.CLAUDE)
    val currentAgent: StateFlow<Agent> = _currentAgent.asStateFlow()

    /** The current agent's Claude run-state block LINE (the specific honest reason
     *  a turn won't run — "No active Claude subscription…", "trial ended", "Usage
     *  limit reached — resets …", "sign-in expired" …), or null when it can run.
     *  Reactive off the status cache so the chat reflects it the instant a probe
     *  detects it. A blocked account must look blocked on EVERY surface — this
     *  drives the chat banner text, the disabled send, and the hidden (meaningless)
     *  usage bar, not just the agent-picker row. */
    val claudeBlockLine: StateFlow<String?> =
        kotlinx.coroutines.flow.combine(
            _currentAgent,
            ServiceLocator.agentStatusCache.observeStatuses(serverId),
        ) { agent, statuses ->
            val st = statuses[agent]
            // For rate-limit states the datum is an ISO `resets_at` — render it as
            // a LOCAL clock time ("10:30 AM") in the user's own zone, not a raw
            // ISO or the CLI's foreign-zone string (user 2026-07-16).
            val data = st?.claudeStateData
            val display = if (data != null &&
                (st.claudeState == ai.eight24family.conch.agent.ClaudeRunState.RATE_LIMITED ||
                    st.claudeState == ai.eight24family.conch.agent.ClaudeRunState.NEAR_LIMIT)
            ) {
                ai.eight24family.conch.agent.parseIsoInstant(data)
                    ?.let { ai.eight24family.conch.agent.usageResetClock(it) } ?: data
            } else data
            st?.claudeState?.takeIf { it.isBlocked }?.lineWith(display)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val codeBlocked: StateFlow<Boolean> =
        claudeBlockLine.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The current Claude account's plan tier ("Max"/"Pro"/"Pro trial"/"Free")
     *  for the limits-sheet header, or null when unknowable (inference-only
     *  setup-token, non-Claude agent). */
    val claudePlan: StateFlow<String?> =
        kotlinx.coroutines.flow.combine(
            _currentAgent,
            ServiceLocator.agentStatusCache.observeStatuses(serverId),
        ) { agent, statuses ->
            if (agent != Agent.CLAUDE) null else statuses[agent]?.claudePlan
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Local id of the AgentSession we're currently displaying. Public so
     *  other screens (subagents browser) can re-attach to the live SSH
     *  session via `ServiceLocator.agentSessions.get(...)`. */
    private val _localSessionId = MutableStateFlow<String?>(null)
    val localSessionId: StateFlow<String?> = _localSessionId.asStateFlow()

    /** Remote (CLI-managed) session id we're attached to via --resume. Null = new chat. */
    private val _resumeId = MutableStateFlow<String?>(null)
    val resumeId: StateFlow<String?> = _resumeId.asStateFlow()

    /** True when opening a resumed session found NOTHING to show — no local
     * cache AND the server file is gone/unreachable (e.g. Claude compacted /
     * deleted the rollout; `stat` returns nothing). Without this the chat
     * hangs on "// loading…" FOREVER. Drives a clear "session unavailable"
     * state instead of an eternal spinner. Reset on every open; */
    private val _loadCameBackEmpty = MutableStateFlow(false)
    val loadCameBackEmpty: StateFlow<Boolean> = _loadCameBackEmpty.asStateFlow()

    /** Id of the message the user is parked on (first visible) when NOT at the
     *  bottom; null = at the bottom. Pushed from the chat's scroll-settle so the
     *  PiP window can render the SAME line the user minimized on, instead of
     *  jerking to the latest reply. */
    private val _readingAnchorMsgId = MutableStateFlow<String?>(null)
    val readingAnchorMsgId: StateFlow<String?> = _readingAnchorMsgId.asStateFlow()
    /** Pixel offset of the anchor message's top above the viewport top, captured
     *  together with the id. Lives in the VM (not just the composition) so a PiP
     *  minimize→expand — which DISPOSES the chat's scroll controller and all its
     *  rememberSaveable state — can restore the EXACT reading position on expand
     *  instead of snapping to the first message. */
    private val _readingAnchorOffset = MutableStateFlow(0)
    val readingAnchorOffset: StateFlow<Int> = _readingAnchorOffset.asStateFlow()
    fun setReadingAnchor(msgId: String?, offset: Int = 0) {
        _readingAnchorMsgId.value = msgId
        _readingAnchorOffset.value = offset
    }

    init {
        // Updatable session cache: the moment a chat learns its resume id (a NEW
        // chat just created on the phone gets one after its first turn), upsert
        // it into SessionsCache so it shows in the sessions list and STAYS —
        // instead of vanishing once its live AgentSession is reaped and only
        // reappearing on the next server listing.
        viewModelScope.launch {
            var last: String? = null
            _resumeId.collect { rid ->
                if (rid == null || rid == last) return@collect
                last = rid
                val agent = _currentAgent.value
                val sid = _localSessionId.value
                val preview = (sid?.let { _messagesBySession.value[it] }
                    ?.firstOrNull { it is AgentMessage.UserText } as? AgentMessage.UserText)
                    ?.text?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.take(200)
                    .orEmpty()
                SilentlyTry.fired("SshAi-SessCache", "upsert created session") {
                    ServiceLocator.sessionsCache.upsert(
                        serverId, agent,
                        RemoteSession(
                            id = rid,
                            path = "",
                            agent = agent,
                            lastActiveAt = System.currentTimeMillis() / 1000L,
                            preview = preview,
                        ),
                    )
                }
            }
        }
    }

    private val _remoteSessions = MutableStateFlow<List<RemoteSession>>(emptyList())
    val remoteSessions: StateFlow<List<RemoteSession>> = _remoteSessions.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _messagesBySession = MutableStateFlow<Map<String, List<AgentMessage>>>(emptyMap())
    val messages: StateFlow<List<AgentMessage>> = combine(_localSessionId, _messagesBySession) { id, byId ->
        val raw = if (id == null) emptyList() else byId[id] ?: emptyList()
        // Drop Claude Code's internal "No response requested." no-op marker — it's
        // written after an interrupt or a tool-only / no-op turn and is NOT a real
        // reply. Plumbing, not conversation. Noise filter. CRITICAL: NEVER match a
        // topical substring against conversation (AssistantText/UserText) — a chat
        // ABOUT permissions or bubblewrap legitimately contains those words, and a
        // substring filter wiped most of such a chat (user 2026-06-30). So: plumbing
        // warnings are matched ONLY on their own non-conversation types
        // (EventNote/Error), and the one annotation that DOES arrive as a bubble
        // (Claude's image coordinate hint) is matched by its EXACT shape, which no
        // real message has.
        val deNoised = raw.filterNot { m ->
            when (m) {
                is AgentMessage.AssistantText -> {
                    val t = m.text.trim()
                    // Claude's internal "No response requested." no-op marker.
                    t.trimEnd('.').trim() == "No response requested" ||
                        // Image coordinate-mapping annotation — a hint for the MODEL.
                        (t.startsWith("[Image:") && t.contains("map to original image", ignoreCase = true))
                }
                is AgentMessage.UserText -> {
                    val t = m.text.trim()
                    t.startsWith("[Image:") && t.contains("map to original image", ignoreCase = true)
                }
                // Plumbing NOTES only — codex's degraded-sandbox bubblewrap warning
                // and Claude Code's "Ignoring N permissions.allow entries" startup
                // warning. Matched on the note/error types, never on conversation.
                is AgentMessage.EventNote ->
                    m.label.contains("bubblewrap", ignoreCase = true) ||
                        m.label.contains("permissions.allow entries", ignoreCase = true)
                is AgentMessage.Error ->
                    m.text.contains("bubblewrap", ignoreCase = true) ||
                        m.text.contains("permissions.allow entries", ignoreCase = true)
                else -> false
            }
        }
        val shown = hideBridgeHandshake(deNoised)
        // Pure reorder of the one header row; nothing else moves.
        val wi = shown.indexOfFirst { it is AgentMessage.System && it.subtype == "welcome" }
        if (wi > 0) listOf(shown[wi]) + shown.filterIndexed { i, _ -> i != wi } else shown
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Phone-bridge plumbing is invisible: the WHOLE handshake turn — the injected
     * prompt, the `conch-bridge ping` Bash call, the `pong` result, the agent's task
     * line — is hidden; only a single clean "phone connected" row remains once the
     * ready token lands. Range filter: drop everything from the (hidden) handshake
     * prompt up to & including the ready token, replacing the token with the clean
     * row. While the handshake is still in flight (no token yet), hide it entirely —
     * nothing shows until the phone confirms. Filters at DISPLAY time, so it holds
     * across reconnect/reload regardless of stream-vs-file sourcing. */
    private fun hideBridgeHandshake(msgs: List<AgentMessage>): List<AgentMessage> {
        if (msgs.isEmpty()) return msgs
        val startIdx = msgs.indexOfFirst {
            it is AgentMessage.UserText &&
                it.text.trimStart().startsWith("I've connected my phone to this server")
        }
        if (startIdx < 0) return msgs   // no bridge handshake in this list
        val tokenIdx = ((startIdx + 1) until msgs.size).firstOrNull {
            val m = msgs[it]
            m is AgentMessage.AssistantText && isBridgeReadyToken(m.text)
        } ?: -1
        // CRITICAL: do NOT hide the rest of the conversation. The old code dropped
        // everything after the handshake prompt here → the whole chat vanished and
        // it showed a FAKE ramping %. Show the real messages untouched; no fake
        // progress, no eaten history.
        if (tokenIdx < 0) return msgs
        // Clean handshake completed (the codeword landed) — collapse the whole
        // handshake block (prompt → ping/pong → token) to one "phone connected"
        // row, keeping everything before and after intact.
        val out = ArrayList<AgentMessage>(msgs.size)
        out.addAll(msgs.subList(0, startIdx))
        out += AgentMessage.System(id = msgs[tokenIdx].id, subtype = "bridge_connected", raw = "")
        out.addAll(msgs.subList(tokenIdx + 1, msgs.size))
        return out
    }

    private fun isBridgeReadyToken(text: String): Boolean {
        val body = text.trim().lines().filter { it.isNotBlank() }
            .joinToString("\n").trim().trim('`', '"', ' ')
        return body == BRIDGE_READY_TOKEN
    }

    private val _stateBySession = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val state: StateFlow<SessionState> = combine(_localSessionId, _stateBySession) { id, byId ->
        if (id == null) SessionState.Idle else byId[id] ?: SessionState.Idle
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Idle)

    /** Live reasoning-token count of the in-flight turn (null = no
     *  reasoning running). Renders as a transient row above the spinner. */
    private val _thinkingTokensBySession = MutableStateFlow<Map<String, Long?>>(emptyMap())
    val liveThinkingTokens: StateFlow<Long?> =
        combine(_localSessionId, _thinkingTokensBySession) { id, byId ->
            if (id == null) null else byId[id]
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _activeAgents = MutableStateFlow<Set<Agent>>(emptySet())
    val activeAgents: StateFlow<Set<Agent>> = _activeAgents.asStateFlow()

    private val activeSessions = mutableMapOf<String, AgentSession>()
    private val collectorJobs = mutableMapOf<String, Job>()
    private val pollerJobs = mutableMapOf<String, Job>()
    private val sessionAgentMap = mutableMapOf<String, Agent>()
    /** Remote JSONL path per local session id — needed on retry to re-attach the poller. */
    private val sessionPathMap = mutableMapOf<String, String>()

    /**
     * Tail-poll loop coordinator. Owns:
     *  - [remoteActive] — last EXTERNAL growth timestamp.
     *  - [remoteFileOpen] — whether the JSONL file is still open for write.
     *  - parseJsonl / appendDeduped / fetchTail / statSize* / pickPollInterval helpers.
     *
     * Constructed lazily so `_tailBackgrounded` is initialised first. The actual
     * `tailPoll(...)` invocation is launched from `startNewChat` further down.
     */
    private val tailPollCoord by lazy {
        ChatViewModelTailPoll(
            backgroundedSince = { backgroundedSince },
            streamLastFedMs = { sid -> lastStreamUpdate[sid] },
            // The currently-displayed session's live pending question/permission —
            // authoritative WAITING-FOR-USER signal (never reaches the JSONL).
            pendingControl = { activeSessions[_localSessionId.value]?.hasPendingControl() ?: false },
        )
    }
    val remoteActive: StateFlow<Long?> get() = tailPollCoord.remoteActive
    val remoteFileOpen: StateFlow<Boolean> get() = tailPollCoord.remoteFileOpen

    /** Epoch-ms start of the in-flight turn (the file's `user`-event timestamp),
     * or null when idle. The working-status timer syncs to this so a MIRRORED
     * console turn's elapsed matches the console. */
    val remoteTurnStartMs: StateFlow<Long?> get() = tailPollCoord.remoteTurnStartMs

    /** True only in the THINKING phase (not mid-tool) — gates the working-row's
     *  «with X effort» suffix to match the CLI. */
    val remoteThinking: StateFlow<Boolean> get() = tailPollCoord.remoteThinking

    /** True when a mirrored turn has been "thinking" with the file frozen too
     *  long — almost certainly blocked on a console-side question. The working
     *  row shows "answer on the server" instead of a fake spinner. */
    val remoteWaitingForInput: StateFlow<Boolean> get() = tailPollCoord.remoteWaitingForInput

    /** Cumulative output tokens of the in-flight mirrored turn (from the file's
     *  assistant usage) — the «↓ N tokens» source when there's no live feed. */
    val remoteTokens: StateFlow<Long> get() = tailPollCoord.remoteTokens

    /** The reasoning effort the session is actually running at — same resolution
     *  chain the topbar uses (explicit pick → session mirror → probe). Surfaced
     *  to the working-status row so it can show "· <effort> effort" like the CLI
     *  («thinking with xhigh effort»). Raw value (e.g. "xhigh"), matching the
     *  CLI's lowercase wording. */
    val activeReasoningEffort: StateFlow<String?> by lazy {
        kotlinx.coroutines.flow.combine(
            selectedReasoning, observedReasoning, sessionInitialReasoning, defaultReasoning,
        ) { sel, obs, init, def ->
            sel?.takeIf { it.isNotBlank() }
                ?: obs?.takeIf { it.isNotBlank() }
                ?: init?.takeIf { it.isNotBlank() }
                ?: def?.takeIf { it.isNotBlank() }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }

    // ──────── Hardware security key touch request ────────
    // Owned by `ChatViewModelSkTouch`. The public API below remains on ChatViewModel
    // (UI subscribers + the SK race-condition fix detailed in INVARIANTS).
    private val skTouchCoord = ChatViewModelSkTouch(
        serverLabel = { _server.value?.name ?: _server.value?.host },
    )

    /** Set when this VM is about to open an SSH session against a server keyed to
     *  a FIDO security-key row. The ChatScreen renders a "Touch your security key"
     *  dialog whenever this is non-null. */
    val skTouchRequest: StateFlow<ChatSkTouchRequest?>
        get() = skTouchCoord.skTouchRequest

    /** Called by the touch dialog when it has a signer (USB or NFC). For NFC this
     *  must be invoked synchronously inside the yubikit callback so the IsoDep
     *  handle stays live. */
    fun provideSkSignerForChatOpen(signer: ai.eight24family.conch.ssh.securitykey.SkSigner) {
        skTouchCoord.provideSkSignerForChatOpen(signer)
    }

    /** Suspend until the chat's pre-connect coroutine is done with the signer
     *  (i.e. agentSession.start() has returned). The dialog uses this to keep
     *  the NFC callback alive while sshj's userauth fires. */
    suspend fun awaitSkOpDone() {
        skTouchCoord.awaitSkOpDone()
    }

    fun cancelSkTouch() {
        skTouchCoord.cancelSkTouch()
    }

    private suspend fun awaitSkSignerFromUi(
        info: ai.eight24family.conch.domain.SshKeySecurityInfo,
    ): ai.eight24family.conch.ssh.securitykey.SkSigner? = skTouchCoord.awaitSkSignerFromUi(info)

    /** Released after the SSH handshake completes — see [ChatViewModelSkTouch.markSkOpDone]. */
    private fun markSkOpDone() {
        skTouchCoord.markSkOpDone()
    }

    // ──────── Phone bridge (Shizuku) — connect THIS chat's session ────────
    // Paperclip → "Connect phone to server". The PHONE connects to a SESSION,
    // not a server: a wired session shows a small phone glyph in the sessions
    // list. Explicit + per-session — we NEVER write the bridge uninvited.
    //
    // Flow (Shizuku must be set up on the phone first — else bounce to Settings):
    //   • bridge NOT on the server → install flow (Confirm dialog → install).
    //   • bridge already installed → NO dialog: just drop the how-to prompt into
    //     the chat; this session now "has the phone".
    //   • installed but OUTDATED   → same as installed, PLUS a tiny "update
    //     available in Server settings" notice (we never force-update from chat).
    enum class BridgeStep { None, NeedSettings, Confirm, Installing, Done, Failed }
    private val _bridgeStep = MutableStateFlow(BridgeStep.None)
    val bridgeStep: StateFlow<BridgeStep> = _bridgeStep.asStateFlow()
    /** One-line log of what the install did on the server (or why it failed),
     *  shown in the install dialog. */
    private val _bridgeLog = MutableStateFlow("")
    val bridgeLog: StateFlow<String> = _bridgeLog.asStateFlow()
    /** "vX → vY" when the server's bridge is older than the one this app ships;
     *  null otherwise. Drives the tiny "update in Server settings" banner. */
    private val _bridgeUpdateNotice = MutableStateFlow<String?>(null)
    val bridgeUpdateNotice: StateFlow<String?> = _bridgeUpdateNotice.asStateFlow()
    /** SEC-1: non-null when installing the bridge onto a higher-risk host (root@
     *  / shared box). Surfaced as a red caution line in the install dialog so the
     *  user knows code-exec on that host = adb-level control of this phone. */
    private val _bridgeHostWarning = MutableStateFlow<String?>(null)
    val bridgeHostWarning: StateFlow<String?> = _bridgeHostWarning.asStateFlow()
    /** 2s heartbeat so [bridgePresence] re-evaluates [BridgeHealth.isAlive] over
     *  time and dims once the bridge poller stops (app backgrounded / SSH down). */
    private val bridgeHealthTicker: kotlinx.coroutines.flow.Flow<Unit> =
        kotlinx.coroutines.flow.flow { while (true) { emit(Unit); kotlinx.coroutines.delay(2_000) } }

    /** Tri-state 📱 for the chat TITLE strip (NONE/IDLE/LIVE) — same glyph and
     * logic the session LISTS use, so opening a wired chat shows the phone right
     * where the list did. Two honest layers (PHONE-GLYPH-SHIZUKU-2): wired → at
     * least IDLE (dim) and stays even when the phone is offline; LIVE (colored)
     * only while the channel polls (BridgeHealth) AND Shizuku is granted RIGHT NOW.
     * `connected` is kept as a recompute trigger so the glyph flips promptly on
     * connect/disconnect. */
    val bridgePresence: StateFlow<ai.eight24family.conch.diagnostics.BridgePresence> = combine(
        connected,
        _resumeId,
        ServiceLocator.preferences.phoneBridgeSessions,
        bridgeHealthTicker,
    ) { _, rid, wired, _ ->
        ai.eight24family.conch.diagnostics.bridgePresenceOf(
            rid != null && "$serverId:$rid" in wired, serverId)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        ai.eight24family.conch.diagnostics.BridgePresence.NONE,
    )

    /** Paperclip → "Connect phone to server". Shizuku-gated, then branches on
     *  whether the bridge is already on the server (see the section comment). */
    fun connectPhoneToServer() {
        _bridgeLog.value = ""
        if (!ai.eight24family.conch.diagnostics.ShizukuShell.available()) {
            _bridgeStep.value = BridgeStep.NeedSettings
            return
        }
        viewModelScope.launch {
            val status = ai.eight24family.conch.diagnostics.BridgeInstaller.status(serverId)
            if (status == null || !status.installed) {
                // Not installed (or couldn't probe) → confirm + install.
                _bridgeHostWarning.value = hostRiskWarning()
                _bridgeStep.value = BridgeStep.Confirm
                return@launch
            }
            // Already installed → no dialog; the session just starts using it.
            val avail = ai.eight24family.conch.diagnostics.BridgeInstaller.bundledVersion
            val cur = status.version
            _bridgeUpdateNotice.value =
                if (cur != null && cur != "?" && avail != "?" && cur != avail) "v$cur → v$avail" else null
            activateBridgeForThisChat()
        }
    }

    /** SEC-1: caution copy when the bridge would land on a root@ host (code-exec
     *  there = adb-level control of this phone). null for non-root sessions. */
    private suspend fun hostRiskWarning(): String? {
        val server = repo.getById(serverId) ?: return null
        return if (server.username.trim().equals("root", ignoreCase = true)) {
            "This is a root@ session on ${server.host}. Anything that can run code as " +
                "root there — a buggy service, a malicious dependency, a prompt-injected " +
                "agent — can then drive this phone at adb level through the bridge. Only " +
                "connect on a host you fully control. You can also keep \"Run shell from " +
                "server\" off in Settings → Security."
        } else null
    }

    fun dismissBridge() {
        _bridgeStep.value = BridgeStep.None
        _bridgeLog.value = ""
        _bridgeHostWarning.value = null
    }

    fun dismissBridgeUpdateNotice() { _bridgeUpdateNotice.value = null }

    /** Drop the how-to prompt into the chat and mark this session phone-wired. */
    private fun activateBridgeForThisChat() {
        // The wired flag is set later, when the bridge handshake CONFIRMS (see the
        // bridge_connected collector) — not here on the tap, so neither the home
        // 📱 nor the in-chat glyph lights before the server actually answered.
        send(BRIDGE_HOWTO_PROMPT)
    }

    /** Confirmed: install conch-bridge on THIS chat's server, surface the
     *  one-line install log, and ONLY on success drop the how-to prompt + flag
     *  this session as phone-wired. */
    fun confirmInstallBridge() {
        viewModelScope.launch {
            _bridgeStep.value = BridgeStep.Installing
            val r = ai.eight24family.conch.diagnostics.BridgeInstaller.install(serverId)
            _bridgeLog.value = r.log
            if (r.success) {
                _bridgeStep.value = BridgeStep.Done
                activateBridgeForThisChat()
            } else {
                _bridgeStep.value = BridgeStep.Failed
            }
        }
    }

    private val BRIDGE_HOWTO_PROMPT = """
        I've connected my phone to this server. There's a CLI at ~/.local/bin/conch-bridge
        that runs things on my phone over this SSH link (the Conch app polls a request
        directory ~every 2s and executes via Shizuku at adb-shell level).

        FIRST, run `conch-bridge ping`. Success prints `pong` and exits 0. If it doesn't,
        triage by EXIT CODE — do not assume the phone is dead:
          • exit 0  → connected, proceed.
          • exit 2  → timeout: Conch isn't polling. Ask me to bring the Conch app to the
            foreground (polling pauses when it's backgrounded), then retry.
          • exit 3  → phone got the request but reported an error (e.g. Shizuku not
            granted). Read the `phone reported error:` text on stderr.
          • any other non-zero, especially exit 1 with little or no output → this is almost
            certainly a bug in the WRAPPER SCRIPT, not the phone. Re-run
            `bash -x ~/.local/bin/conch-bridge ping` to find the failing line.

        Commands:
          • conch-bridge shell '<cmd>' — any adb-shell-level command (e.g. 'pm list packages',
            'dumpsys battery'). stdout → stdout; exit code + stderr come back on the bridge's
            own `[bridge] {...}` stderr line.
          • conch-bridge logs [--lines N] [--filter GLOB] [--level V|D|I|W|E] [--tier shizuku|own]
            — recent logcat.
          • conch-bridge screenshot — capture the screen.
          • conch-bridge ping — connectivity check (expect `pong`).

        The phone must stay in the foreground for the bridge to respond (~2s poll).
        Use the bridge whenever you need to inspect or act on my phone.

        HANDSHAKE — do this FIRST and exactly: run `conch-bridge ping`. If it prints
        `pong`, reply with ONLY this token on its own line and NOTHING else:
        CONCH_BRIDGE_READY
        The app hides this whole setup exchange and shows a small phone indicator
        instead, so don't write anything else in that reply — just the token. If
        ping does NOT succeed, skip the token and tell me plainly what went wrong.
    """.trimIndent()

    /** The agent replies with ONLY this token once the bridge handshake succeeds
     *  (see BRIDGE_HOWTO_PROMPT). The app hides the prompt + this reply and shows a
     *  clean "phone connected" row + the usage-bar phone glyph instead of dumping
     *  the scary prompt and a long connection answer into the chat. */
    private val BRIDGE_READY_TOKEN = "CONCH_BRIDGE_READY"

    init {
        // Persist the phone-wired flag against THIS chat's resume id (the id the
        // sessions list keys rows on) — but ONLY once the bridge handshake actually
        // CONFIRMED (a "bridge_connected" row landed), NOT on the connect tap. This
        // is what makes the home 📱 honest AND identical to the in-chat glyph (both
        // = wired && SSH): the list never claims "connected" before the server
        // answered.
        viewModelScope.launch {
            combine(
                messages.map { msgs -> msgs.any { it is AgentMessage.System && it.subtype == "bridge_connected" } },
                _resumeId,
            ) { confirmed, rid -> confirmed to rid }
                .collect { (confirmed, rid) ->
                    if (confirmed && rid != null) {
                        ServiceLocator.preferences.setPhoneBridgeSession(serverId, rid, true)
                    }
                }
        }
    }

    // ──────── File downloads (paths in agent replies) ────────
    // Pipeline owned by `ChatViewModelDownloads`. Public API + data classes remain
    // on ChatViewModel for source-compat with ChatDownloadDisk / ChatScreenFileOpen.
    private val downloadsCoord by lazy {
        ChatViewModelDownloads(
            scope = viewModelScope,
            serverId = serverId,
            currentLocalSessionId = { _localSessionId.value },
            activeSessionFor = { sid -> activeSessions[sid] },
        )
    }

    /** Per-path download state. */
    val downloads: StateFlow<Map<String, DownloadStatus>> get() = downloadsCoord.downloads

    /** Per-path remote file existence cache. */
    val fileExists: StateFlow<Map<String, Boolean>> get() = downloadsCoord.fileExists

    /** Remote file sizes parallel to [fileExists]. */
    val fileSizes: StateFlow<Map<String, Long>> get() = downloadsCoord.fileSizes

    /** Kick off an async existence probe for [path]. Idempotent. */
    fun checkFileExists(path: String) = downloadsCoord.checkFileExists(path)

    sealed interface DownloadStatus {
        /** Still streaming. `progress` is in `[0f..1f]` or negative when size is unknown. */
        data class Downloading(val progress: Float) : DownloadStatus
        /** Saved successfully. `localUri` is openable via the system viewer.
         *  `sizeBytes` is the local file's byte size — surfaced next to
         *  the disk icon so the user can tell apart a 200-byte config
         *  from a 50 MB log without opening it. */
        data class Done(
            val localUri: android.net.Uri,
            val displayLocation: String,
            val sizeBytes: Long,
        ) : DownloadStatus
        data class Failed(val reason: String) : DownloadStatus
    }

    // ──────── Pending send buffer (session-not-ready-yet) ────────
    /**
     * Texts the user tapped send on while the AgentSession was still
     * bootstrapping. Drained in order the moment the session reaches
     * `Running`. If a buffered send sits here for more than
     * [BUFFER_TIMEOUT_MS], it's dropped and the text is emitted on
     * [returnedText] so ChatScreen can put it back into the input box
     * (so the user never silently loses what they typed).
     *
     * Public mostly so the prompt-bar hint can say "queued — will send
     * when session is up" when there's something in flight.
     */
    private data class PendingSend(
        val id: String,
        val text: String,
        val queuedAt: Long,
        /** Uploaded image paths to send structurally with this buffered turn. */
        val imagePaths: List<String> = emptyList(),
    )
    private val _pending = MutableStateFlow<List<PendingSend>>(emptyList())
    val hasPending: StateFlow<Boolean> = _pending
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Messages the user sent WHILE a turn was already running. We hold them HERE
     * instead of handing them to the CLI immediately — the agent would queue them
     * internally, invisibly and with no way to take one back. Shown as a visible
     * queue above the prompt bar (each with a cancel ✕) and sent IN ORDER, one per
     * turn, by [drainOutbox] the moment the current reply finishes. User:.
     */
    data class QueuedMessage(
        val id: String,
        /** Full prompt sent to the agent on drain (may include the "Attached
         *  …at: <path>" prose so the model can read the files). */
        val text: String,
        /** Clean user text shown in the queue row (no attach-paths boilerplate). */
        val displayText: String,
        val imagePaths: List<String>,
        /** Raw bytes of attached images, for tiny thumbnails in the queue row. */
        val thumbs: List<ByteArray>,
        val queuedAt: Long,
    )
    private val _outbox = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queuedMessages: StateFlow<List<QueuedMessage>> = _outbox.asStateFlow()

    // ── Per-chat input draft ── Persist whatever the user typed but didn't send,
    // so leaving the chat never throws it away. Keyed by the chat's resume id
    // (stable) or its local id for a brand-new chat. Only send/explicit-delete
    // clears it — never an auto-wipe.
    private fun draftChatId(): String? = _resumeId.value ?: _localSessionId.value
    private var draftSaveJob: kotlinx.coroutines.Job? = null

    /** Restore the saved input draft for THIS chat (called once on chat open). */
    suspend fun loadInputDraft(): String =
        draftChatId()?.let { id ->
            SilentlyTry.nullOnError { ServiceLocator.preferences.inputDraftOnce(id) }
        }.orEmpty()

    /** Persist the current input text (debounced). Never auto-clears. */
    fun saveInputDraft(text: String) {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            val id = draftChatId() ?: return@launch
            SilentlyTry.fired("SshAi-Chat", "save input draft") {
                ServiceLocator.preferences.setInputDraft(id, text)
            }
        }
    }

    /** Drop the input draft — the text was sent / consumed. */
    fun clearInputDraft() {
        draftSaveJob?.cancel()
        viewModelScope.launch {
            val id = draftChatId() ?: return@launch
            SilentlyTry.fired("SshAi-Chat", "clear input draft") {
                ServiceLocator.preferences.setInputDraft(id, "")
            }
        }
    }

    /** Drop a still-queued message before it's sent (the ✕ on its queue row). */
    fun cancelQueued(id: String) {
        _outbox.update { lst -> lst.filterNot { it.id == id } }
    }

    /** Turn finished → send the NEXT queued message (if any). Sending it starts a
     *  new turn, so the rest stay queued+cancelable and drain one-by-one as each
     *  turn ends. */
    private fun drainOutbox(s: AgentSession) {
        val next = _outbox.value.firstOrNull() ?: return
        _outbox.update { it.drop(1) }
        viewModelScope.launch {
            s.send(next.text, next.imagePaths)
            val newId = s.agentSessionId
            if (newId != null && _resumeId.value != newId) {
                _resumeId.value = newId
                refreshSessions()
            }
        }
    }

    /** Prompts a turn ABORTED on because the SSH transport was dead (set by
     *  [retry] from the dying session's [AgentSession.consumeUndelivered]).
     *  Re-delivered ECHO-FREE by the init {} state-watcher the moment the
     *  rebuilt session reaches Running — the bubble is already carried, so we
     *  re-send the text to the CLI without re-rendering it. Main-thread only
     *  (retry + the watcher both run on viewModelScope's Main dispatcher). */
    private var pendingRedelivery: List<String> = emptyList()

    /** Emits the original text whenever a buffered send had to be returned
     *  to the input box (timeout reached without the session coming up). */
    private val _returnedText = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8
    )
    val returnedText: SharedFlow<String> get() = _returnedText

    /** One-shot event: open a downloaded file in our built-in text
     *  viewer. */
    data class OpenInViewerRequest(
        val uri: android.net.Uri,
        val filename: String,
        val serverId: String,
        val remotePath: String,
    )
    val openInViewer: SharedFlow<OpenInViewerRequest> get() = downloadsCoord.openInViewer

    /** One-shot event: open a downloaded file via the system chooser
     *  (`ACTION_VIEW`). */
    data class OpenExternallyRequest(val uri: android.net.Uri, val mime: String)
    val openExternally: SharedFlow<OpenExternallyRequest> get() = downloadsCoord.openExternally

    /** One-shot event: hand the file to the system share sheet (`ACTION_SEND`). */
    data class ShareRequest(val uri: android.net.Uri, val mime: String, val filename: String)
    val shareFile: SharedFlow<ShareRequest> get() = downloadsCoord.shareFile

    /** One-shot event: ask the user to choose internal-vs-external for this
     *  file's extension. */
    data class OpenFilePromptRequest(
        val uri: android.net.Uri,
        val filename: String,
        val mime: String,
        val extension: String,
        val sizeBytes: Long,
        val remotePath: String,
    )
    val openFilePrompt: SharedFlow<OpenFilePromptRequest> get() = downloadsCoord.openFilePrompt

    /**
     * Entry point invoked by the disk-icon click after a download has completed.
     * Routes to internal viewer / external chooser / share — or to the chooser
     * bottom-sheet if no preference is remembered.
     */
    fun openDownloadedFile(
        uri: android.net.Uri,
        remotePath: String,
        mime: String,
        sizeBytes: Long,
    ) = downloadsCoord.openDownloadedFile(uri, remotePath, mime, sizeBytes)

    /** Persist the user's "where to open .ext files" choice. */
    fun rememberOpenFileChoice(extension: String, choice: String) =
        downloadsCoord.rememberOpenFileChoice(extension, choice)

    /** Inline image rendering: chat exchanges PATHS, not bytes — render the
     *  actual picture by streaming the file into memory + decoding. */
    internal val inlineImages: StateFlow<Map<String, ChatViewModelDownloads.InlineImage>>
        get() = downloadsCoord.inlineImages
    fun loadInlineImage(remotePath: String) = downloadsCoord.loadInlineImage(remotePath)

    /** Path of the image currently open in the full-screen annotator overlay
     *  (null = closed). The overlay reads the decoded bitmap from [inlineImages]. */
    private val _fullScreenImage = MutableStateFlow<String?>(null)
    val fullScreenImage: StateFlow<String?> = _fullScreenImage.asStateFlow()
    fun openImageViewer(remotePath: String) { _fullScreenImage.value = remotePath }
    fun closeImageViewer() { _fullScreenImage.value = null }

    /**
     * Tail-poll cadence is driven by three knobs, chosen by the loop in
     * [tailPoll] every iteration:
     *  • foreground + agent Working → fast (catch the stream as it grows)
     *  • foreground + idle          → exponential back-off (5 → 10 → 30 s)
     *  • backgrounded < 5 min       → 30 s (chat-might-come-back-soon)
     *  • backgrounded ≥ 5 min       → 60 s (chat-is-not-on-screen-anyway)
     *
     * SSH socket itself is kept alive via sshj's 30 s keepalive in all
     * cases — pausing the poller doesn't disconnect the session.
     */
    private val _tailBackgrounded = MutableStateFlow(false)
    val tailBackgrounded: StateFlow<Boolean> get() = _tailBackgrounded
    /** Unix-millis when ChatScreen went background, null while foreground. */
    @Volatile private var backgroundedSince: Long? = null
    fun setTailBackgrounded(backgrounded: Boolean) {
        val cameToForeground = !backgrounded && backgroundedSince != null
        _tailBackgrounded.value = backgrounded
        backgroundedSince = if (backgrounded) System.currentTimeMillis() else null
        // Back to the chat → re-read the plan limit AT ONCE (don't wait for the
        // 30s foreground tick). The account-wide 5h/weekly window may have moved
        // while we were away, so the bar the user looks at is current the moment
        // they return, not a stale snapshot (user, 2026-07-03).
        if (cameToForeground) refreshUsage()
    }

    val enterSends: StateFlow<Boolean> = ServiceLocator.preferences.enterSends
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ──── Staged attachments (images and files) waiting to be sent ────
    // Owned by `ChatViewModelAttachments`.
    private val attachmentsCoord by lazy {
        ChatViewModelAttachments(
            scope = viewModelScope,
            serverId = serverId,
            uploadCache = uploadCache,
            currentLocalSessionId = { _localSessionId.value },
            activeSessionFor = { sid -> activeSessions[sid] },
        )
    }
    val attachments: StateFlow<List<StagedAttachment>> get() = attachmentsCoord.attachments
    /** True while at least one attachment is mid-upload — disables Send. */
    val anyUploading: StateFlow<Boolean> get() = attachmentsCoord.anyUploading

    // ──── Selected CLI model (`--model` override) ────
    // Logic + per-chat / per-agent state owned by `ChatViewModelModels`.
    private val modelsCoord by lazy {
        ChatViewModelModels(
            scope = viewModelScope,
            currentAgent = _currentAgent,
            resumeId = _resumeId,
            messages = messages,
            initialSessionModel = initialSessionModel,
            initialSessionReasoning = initialSessionReasoning,
        )
    }

    val selectedModel: StateFlow<String?> get() = modelsCoord.selectedModel
    val selectedReasoning: StateFlow<String?> get() = modelsCoord.selectedReasoning
    val reasoningCatalog: StateFlow<Map<String, ai.eight24family.conch.agent.spec.ModelReasoningInfo>>
        get() = modelsCoord.reasoningCatalog

    // ──── Approval / sandbox mode (SAFE / AUTO / YOLO) ────
    // PER-AGENT: each CLI has its own approval flags/semantics, so the mode is
    // keyed by the chat's current agent — picking YOLO in a Claude chat must not
    // flip Codex. Reactive to switchAgent via flatMapLatest.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val approvalMode: StateFlow<ai.eight24family.conch.data.prefs.AgentApprovalMode> =
        _currentAgent
            .flatMapLatest { agent -> ServiceLocator.preferences.approvalModeFor(agent.name) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                ai.eight24family.conch.data.prefs.AgentApprovalMode.YOLO,
            )

    fun setApprovalMode(mode: ai.eight24family.conch.data.prefs.AgentApprovalMode) {
        Telemetry.approvalModeChanged(mode)
        val agent = _currentAgent.value
        viewModelScope.launch {
            ServiceLocator.preferences.setApprovalModeFor(agent.name, mode)
            // Apply to the active session so the next send uses the new flag.
            _localSessionId.value?.let { sid ->
                activeSessions[sid]?.approvalMode = mode
            }
        }
    }

    /** Settings toggle: whether the approval shield is shown in the chat top
     *  bar. Off = the user set their level once and doesn't want to see it. */
    val showApprovalInChatBar: StateFlow<Boolean> =
        ServiceLocator.preferences.showApprovalInChatBar
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Model that the agent actually reports in its `system` init event for the most
     *  recent turn. Single source of truth for "what the agent is actually using right
     *  now"; never lie with a hardcoded fallback. */
    val observedModel: StateFlow<String?> get() = modelsCoord.observedModel

    /** Reasoning effort the session actually runs at, mirrored from the session
     *  file (e.g. `ultra_effort_enter` → "ultracode"). Topbar effort label
     *  prefers this over the stale PTY probe — never a hardcoded default. */
    val observedReasoning: StateFlow<String?> get() = modelsCoord.observedReasoning

    /** Claude's auto-generated session title (`ai-title`) — topbar title source,
     *  preferred over the first user message. */
    val observedTitle: StateFlow<String?> get() = modelsCoord.observedTitle

    /** Most-recently-reported working dir from any system event. Used by slash
     *  commands like /diff, /init, /memory that operate on the cwd. */
    val observedCwd: StateFlow<String?> get() = modelsCoord.observedCwd

    // ──── Slash-command modal state (one open at a time) ────
    private val _modal = MutableStateFlow<ChatModal?>(null)
    val modal: StateFlow<ChatModal?> = _modal.asStateFlow()
    fun dismissModal() { _modal.value = null }

    // ──── Custom slash commands discovered from ~/.claude/commands ────
    // Owned by ChatViewModelSlash; exposed below via delegate.

    // ──── Stream-stall watchdog + auto-reconnect on dropped SSH ────
    // Owned by `ChatViewModelReconnect` — see that file. The public StateFlows
    // / properties below remain part of ChatViewModel's surface for UI subscribers.
    private val reconnectCoord by lazy { ChatViewModelReconnect(viewModelScope) { retry() } }
    /**
     * Local session id whose assistant stream has been silent for longer than
     * [STREAM_STALL_TIMEOUT_MS] while still in `SessionState.Working`.
     * Null = no stall detected. Drives a "Stream paused — tap to retry" pill
     * under the AssistantLine spinner so the user has an escape hatch when
     * the SSH transport hangs mid-turn instead of an indefinite spinner loop.
     */
    val streamStalled: StateFlow<String?> get() = reconnectCoord.streamStalled

    /** sessionId → epoch millis of the last fresh content/state delta we saw. */
    private val lastStreamUpdate: MutableMap<String, Long> get() = reconnectCoord.lastStreamUpdate

    val reconnecting: StateFlow<Boolean> get() = reconnectCoord.reconnecting

    val reconnectAttempt: StateFlow<Int> get() = reconnectCoord.reconnectAttempt

    // ──── Server stats (refreshed on demand from the stats sheet) ────
    // Owned by `ChatViewModelStats`.
    private val statsCoord by lazy {
        ChatViewModelStats(
            scope = viewModelScope,
            serverId = serverId,
            repo = repo,
            server = { _server.value },
            liveSession = { activeSessions[_localSessionId.value] },
        )
    }
    val serverStats: StateFlow<ServerStats?> get() = statsCoord.serverStats
    val statsLoading: StateFlow<Boolean> get() = statsCoord.statsLoading

    // Slash dispatcher — DECLARED HERE (before the init{} blocks below) so its
    // lazy delegate is created before init's startNewChat launches the async
    // probeCustomCommands. If declared later, that coroutine can outrun the
    // delegate's construction and crash with `Lazy.getValue() on a null object`
    // (it did once a slow-constructing flow was added). Public slash API +
    // probeCustomCommands stay further down.
    private val slashCoord by lazy {
        ChatViewModelSlash(
            scope = viewModelScope,
            serverId = serverId,
            currentAgent = { _currentAgent.value },
            currentLocalSessionId = { _localSessionId.value },
            sessionAccess = { activeSessions[_localSessionId.value] },
            observedCwd = { observedCwd.value },
            setModal = { _modal.value = it },
            postSendUpdate = { newId ->
                if (newId != null && _resumeId.value != newId) {
                    _resumeId.value = newId
                    refreshSessions()
                }
            },
            newSession = { newSession() },
        )
    }

    // ──── Available model display names (probed from claude cli.js) ────
    // All owned by `ChatViewModelModels`.
    val availableModels: StateFlow<Map<String, String>> get() = modelsCoord.availableModels
    val unavailableModelLabels: StateFlow<Set<String>> get() = modelsCoord.unavailableModelLabels
    val modelsProbing: StateFlow<Boolean> get() = modelsCoord.modelsProbing
    val defaultModel: StateFlow<String?> get() = modelsCoord.defaultModel
    val defaultReasoning: StateFlow<String?> get() = modelsCoord.defaultReasoning
    val sessionInitialModel: StateFlow<String?> get() = modelsCoord.sessionInitialModel
    val sessionInitialReasoning: StateFlow<String?> get() = modelsCoord.sessionInitialReasoning

    init {
        // Cold-start hydrate: spec model cache (Claude alias map, Codex slug map etc.)
        modelsCoord.hydrateFromCache()
    }

    // NB: NO auto-switch on model-unavailable. We MIRROR the model the
    // session actually runs (message.model pickup) and FOLLOW Anthropic if
    // THEY switch (the next real turn's message.model updates it). We never
    // IMPOSE a model the agent didn't pick — that both fought the user's
    // choice and ping-ponged Fable↔Opus. A dead model shows its card + a
    // greyed picker; the user picks a working one (matches Claude's own UI).

    /** Live probe of bundled model display names. Delegates to [modelsCoord]. */
    private suspend fun probeAvailableModels(session: AgentSession) =
        modelsCoord.probeAvailableModels(session)

    /**
     * The user tapped the model selector. Re-probe availability NOW (force,
     * bypassing the freshness gate) — models can be suspended mid-session
     * (Fable 5 export-control) and a long-lived connection would otherwise
     * show a stale list. The dropdown shows the cached list instantly; this
     * refreshes it in place when the probe lands. No-op if a probe is
     * already in flight (overlap guard) or no live session yet. */
    fun onModelPickerOpened() {
        if (modelsCoord.modelsProbing.value) return
        val sid = _localSessionId.value ?: return
        val s = activeSessions[sid] ?: return
        viewModelScope.launch(Dispatchers.IO) {
            modelsCoord.probeAvailableModels(s, force = true)
        }
    }


    fun refreshServerStats() = statsCoord.refresh()

    init {
        viewModelScope.launch {
            val s = repo.getById(serverId) ?: return@launch
            _server.value = s
            val pickedAgent = initialAgent ?: s.agent
            _currentAgent.value = pickedAgent
            startNewChat(
                agent = pickedAgent,
                resumeIdParam = initialResumeId,
                resumeFilePath = initialResumePath
            )
            refreshSessions()
        }
        // Backfill the per-chat model key on the null → non-null resumeId transition —
        // but ONLY for chats that started *without* a resumeId. See
        // [ChatViewModelModels.observeResumeIdForBackfill] for the full rationale.
        if (initialResumeId == null) {
            modelsCoord.observeResumeIdForBackfill { rid ->
                // Drop the (serverId, agent) draft slot — this chat now has a CLI
                // thread id, so any pending UserText either already drained into
                // `s.send()` or is about to. Leaving a draft around would resurrect
                // those texts on the next "+ new session" tap.
                SilentlyTry.fired("SshAi-Chat", "clear drafts after resumeId arrived") {
                    ServiceLocator.historyCache.clearDrafts(serverId, _currentAgent.value)
                }
            }
        }
        // Drain the pending-send buffer the moment the session reaches
        // Running. We don't wait for `_remoteFileOpen` to clear — Claude
        // itself queues prompts arriving mid-turn, so handing one to
        // `s.send` straight away is fine.
        viewModelScope.launch {
            state.collect { st ->
                if (st !is SessionState.Running) return@collect
                val sid = _localSessionId.value ?: return@collect
                val s = activeSessions[sid] ?: return@collect
                // 1. Echo-free re-delivery of prompts a dead-transport abort
                //    dropped. Their bubble is already on screen (carried across
                //    the reconnect + seeded into this session's history below),
                //    so push the text to the CLI again WITHOUT re-rendering it —
                //    re-rendering is what doubled the message in earlier builds.
                pendingRedelivery.let { redeliver ->
                    if (redeliver.isNotEmpty()) {
                        pendingRedelivery = emptyList()
                        android.util.Log.d(
                            "SshAi-Send",
                            "re-delivering ${redeliver.size} prompt(s) dropped on a dead transport",
                        )
                        // The optimistic bubble for an OFFLINE-first-send lives ONLY in
                        // the display list (_messagesBySession): that send had no
                        // AgentSession to emit into, and redeliver is echo-free. So the
                        // prompt is ABSENT from s.history when the JSONL echo lands —
                        // appendDeduped can't collapse it (shownUserTexts is read from
                        // s.history) and appendMessages puts the echo AFTER the reply →
                        // the "phantom" prompt pinned at the bottom that survives a
                        // re-entry and is replaced by the next send (user, 2026-06-30).
                        // Skip rows already in history — the reconnect-carry path already
                        // seeded them via histSeed, and re-adding would revive the
                        // double. Count-based per body (consume one display row per
                        // redeliver text) so genuine repeats keep N rows (LEGIT-REPEAT).
                        val histIds = s.history.value.mapTo(HashSet()) { it.id }
                        val unsynced = _messagesBySession.value[sid].orEmpty()
                            .filterIsInstance<AgentMessage.UserText>()
                            .filter { it.id !in histIds }
                            .toMutableList()
                        redeliver.forEach { t ->
                            unsynced.firstOrNull { it.text.trim() == t.trim() }?.let { row ->
                                unsynced.remove(row)
                                s.appendMessages(listOf(row))
                            }
                            s.redeliver(t)
                        }
                        val newId = s.agentSessionId
                        if (newId != null && _resumeId.value != newId) {
                            _resumeId.value = newId
                            refreshSessions()
                        }
                    }
                }
                // 2. Drain the offline-first-send buffer. Unlike (1) these were
                //    NOT yet rendered — s.send both renders AND delivers.
                if (_pending.value.isEmpty()) return@collect
                val toFlush = _pending.value
                _pending.value = emptyList()
                for (p in toFlush) {
                    s.send(p.text, p.imagePaths)
                    val newId = s.agentSessionId
                    if (newId != null && _resumeId.value != newId) {
                        _resumeId.value = newId
                        refreshSessions()
                    }
                    // Drained — drop this entry from the draft slot.
                    // If the CLI never came back with a session id
                    // (s.send threw / dropped silently) this still
                    // removes the draft so a future "+ new session"
                    // doesn't re-fire the same prompt unexpectedly.
                    SilentlyTry.fired("SshAi-Chat", "remove drained draft") {
                        ServiceLocator.historyCache.removeDraft(
                            serverId, _currentAgent.value, p.text
                        )
                    }
                }
            }
        }
        // Session-health watchers (stall watchdog + auto-reconnect) live
        // in the reconnect coordinator now — they're its concern, not the
        // orchestrator's. We hand it our state flows + the one side-effect
        // we own (clear the remote-active spinner on Running).
        reconnectCoord.installWatchdogs(
            state = state,
            stateBySession = _stateBySession,
            stallTimeoutMs = STREAM_STALL_TIMEOUT_MS,
            onRunning = { tailPollCoord.setRemoteFileOpen(false) },
        )
    }

    /**
     * Merge an incoming history snapshot into the display list while NEVER
     * dropping a UserText the user just sent that the snapshot hasn't caught up
     * to. Mirrors [ai.eight24family.conch.agent.AgentSessionHistory.loadHistory]'s
     * survivor rule (count-based per trimmed body) — but at the DISPLAY layer,
     * for the one path that bypasses loadHistory: a brand-new chat where the
     * user typed BEFORE the welcome banner. Once the JSONL echo lands, the
     * survivor's text is covered and it drops out, so there's no duplicate.
     */
    private fun preserveUnsyncedUserText(
        current: List<AgentMessage>,
        incoming: List<AgentMessage>,
    ): List<AgentMessage> {
        if (current.isEmpty()) return incoming
        val incomingIds = incoming.mapTo(HashSet()) { it.id }
        val incomingUserCounts = HashMap<String, Int>()
        for (m in incoming) if (m is AgentMessage.UserText) {
            val b = m.text.trim()
            incomingUserCounts[b] = (incomingUserCounts[b] ?: 0) + 1
        }
        val seen = HashMap<String, Int>()
        val survivors = ArrayList<AgentMessage>()
        for (m in current) {
            if (m !is AgentMessage.UserText) continue
            if (m.id in incomingIds) continue            // same message already incoming
            val b = m.text.trim()
            val n = seen[b] ?: 0
            seen[b] = n + 1
            if (n < (incomingUserCounts[b] ?: 0)) continue // a JSONL copy covers this one
            survivors.add(m)                              // un-synced optimistic prompt → keep
        }
        return if (survivors.isEmpty()) incoming else incoming + survivors
    }

    private fun startNewChat(
        agent: Agent,
        resumeIdParam: String? = null,
        resumeFilePath: String? = null,
        seedMessages: List<AgentMessage>? = null,
    ) {
        val localId = UUID.randomUUID().toString()
        sessionAgentMap[localId] = agent
        if (resumeFilePath != null) sessionPathMap[localId] = resumeFilePath
        _activeAgents.update { it + agent }
        _resumeId.value = resumeIdParam
        _loadCameBackEmpty.value = false
        Telemetry.chatSessionStarted(agent, isResume = resumeIdParam != null)
        // Reconnect carry-over (retry() passes the messages it was showing): paint
        // them immediately so the chat doesn't BLANK while the rebuilt session
        // reloads from cache/JSONL. DISPLAY-ONLY — AgentSession.history still
        // rebuilds from JSONL below, so no duplicate ids creep in.
        if (!seedMessages.isNullOrEmpty()) {
            _messagesBySession.update { it + (localId to seedMessages) }
        }

        // ── Cache hydrate: instant render before SSH/CLI bootstrap. ──
        // Parse the persisted JSONL bytes ONCE; the same parsed list is
        // pushed straight into _messagesBySession so the LazyColumn paints
        // immediately, AND seeded into AgentSession.history below so the
        // collector emission picks up the SAME references (no key churn,
        // no flicker as SSH catches up).
        //
        // Populate _messagesBySession[localId] BEFORE flipping _localSessionId
        // so the `messages` combine never momentarily emits an empty list.
        var cachedParsed: List<AgentMessage> = emptyList()
        var cachedBytesLen = 0L
        if (resumeIdParam != null) {
            ai.eight24family.conch.util.Logx.d("SshAi-HistCache") {
                val (total, uniq, bytes) = ServiceLocator.historyCache.duplicationStats(resumeIdParam)
                "dup-stats sid=${resumeIdParam.take(8)} lines=$total unique=$uniq " +
                    "dupes=${total - uniq} bytes=$bytes"
            }
        }
        if (resumeIdParam != null) {
            // `snap.buffer` is the mmap-backed view of the cached JSONL —
            // zero Java-heap copy. `trimToLastNewline(ByteBuffer)` returns
            // a zero-copy slice; `parseJsonl(ByteBuffer)` decodes through
            // the UTF-8 charset directly off the mapped pages.
            // `.use { }` (Durov #6): closes the mapping the moment we're
            // done with it instead of waiting for GC. parseJsonl
            // fully consumes the bytes synchronously into Kotlin Strings
            // so nothing after this block references the mmap region.
            ServiceLocator.historyCache.load(resumeIdParam)?.use { snap ->
                if (snap.buffer.hasRemaining()) {
                    // cachedBytesLen = the FULL trimmed file length — the tail-poll's
                    // initialOffset relies on this being TRUE EOF so `tail -c +N`
                    // resumes exactly. Cheap: trimToLastNewline scans back from the
                    // END for the last '\n' (a few bytes), NOT the whole file.
                    cachedBytesLen = ai.eight24family.conch.util.JsonlUtils
                        .trimToLastNewline(snap.buffer).remaining().toLong()
                    // DISPLAY parses only the recent TAIL. A 20 MB ultracode-workflow
                    // JSONL parsed whole FROZE the open — parseJsonl ran on the Main
                    // thread over all 20 MB and the chat hung on "// loading…" forever
                    // (user, 2026-06-13). The recent ~2 MB is all the visible
                    // conversation needs; the FULL file stays cached for search +
                    // tail-sync. A marker row tells the user earlier turns are hidden
                    // (honest — never silently "looks like everything loaded").
                    val win = ai.eight24family.conch.util.JsonlUtils
                        .tailSlice(snap.buffer, DISPLAY_TAIL_BYTES)
                    val t0 = System.currentTimeMillis()
                    val parsed = tailPollCoord.parseJsonl(win.slice, agent)
                    val parseMs = System.currentTimeMillis() - t0
                    cachedParsed = if (win.windowed) listOf(historyWindowMarker()) + parsed else parsed
                    // Timing on the OPEN path — openRemoteSession is NOT launched, so
                    // this hydrate parse runs on the MAIN thread. If parseMs is high on
                    // a big session, that's the "loaded slowly" jank → move off Main.
                    // (Dormant unless -PverboseLogs; enable to measure a slow open.)
                    ai.eight24family.conch.util.Logx.d("SshAi-Chat") {
                        "hydrate sid=${resumeIdParam.take(8)} windowed=${win.windowed} dropped=${win.droppedBytes}B " +
                            "msgs=${parsed.size} parseMs=$parseMs (full ${cachedBytesLen}B)"
                    }
                    // INSTANT model on entering the chat. The parsed cache
                    // carries the session's real model in the latest
                    // `model_observed` System row; seed it synchronously so
                    // the topbar is correct from frame zero instead of waiting
                    // for the live tail-poll to bring a fresh turn.
                    cachedParsed.asReversed().firstNotNullOfOrNull { msg ->
                        (msg as? AgentMessage.System)
                            ?.takeIf { it.subtype == "model_observed" }?.model
                    }?.let { modelsCoord.setSessionInitialModel(it) }
                    // Don't clobber a larger reconnect carry-over (seedMessages)
                    // with a SMALLER stale cache — that's what wiped the recent
                    // reply + the user's message on a network switch.
                    _messagesBySession.update { m ->
                        if ((m[localId]?.size ?: 0) > cachedParsed.size) m
                        else m + (localId to cachedParsed)
                    }
                }
            }
        }
        _localSessionId.value = localId
        // First-paint timing: cache-hit path is essentially instant.
        // We open + immediately finish a transaction so the dashboard
        // distribution shows the user-perceived latency from method
        // entry to UI-renderable state. The from_cache tag splits the
        // distribution into cache-warm vs cache-cold buckets.
        Telemetry.startChatFirstPaint(
            agent = agent,
            fromCache = cachedParsed.isNotEmpty(),
        )?.finish()

        // Search-opened read-only mode: cache is hydrated, _localSessionId is
        // set, the chat surface paints from the parsed JSONL. STOP here —
        // don't open an AgentSession. Without an SSH pool the agent's
        // `start()` would call ssh.execute → "security-key signer not
        // provided" → red ERR banner, which the user explicitly flagged. The
        // chip + beginSearchOpenedConnect path handles the actual auth; the
        // agent session only matters when the user wants to send, and they
        // have to go through the chip first. Cache-only sentinel = a search
        // hit on a session whose owning server we never recorded / the server
        // deleted: there's no real server to dial, so force read-only
        // unconditionally and paint from the cached JSONL (loaded by
        // sessionId, not serverId). Otherwise the normal rule: search-opened +
        // pool offline = read-only until the user connects via the chip.
        // Read-only-from-cache on open whenever the pool isn't ALREADY live.
        // Opening a session must never demand the key — you read from cache;
        // the key is only asked when you actually send (see send() below,
        // which connects on first send). If the pool IS live (you connected on
        // the Servers tap, or another chat is open) we bootstrap normally and
        // ride it — no touch. Cache-only sentinel is always read-only.
        val offlineReadOnly = serverId == ai.eight24family.conch.ui.navigation.Routes.CACHE_ONLY_SERVER_ID ||
            ServiceLocator.sshConnectionPool.peek(serverId) == null
        if (offlineReadOnly) {
            android.util.Log.d(
                "SshAi-Chat",
                "read-only open: cache hydrated ${cachedParsed.size} msgs, skipping AgentSession bootstrap (connect deferred to first send)"
            )
            // Instant seamless connect on OPEN, not just on first send: if this
            // server has a device key, bring the transport up SILENTLY right now
            // — no tap — so a logged-in server's chat is live immediately.
            // silent=true never forces a FIDO touch on open; with no device key
            // it stays an honest "offline · tap to connect". Skip the cache-only
            // sentinel — there's no real server.
            if (serverId != ai.eight24family.conch.ui.navigation.Routes.CACHE_ONLY_SERVER_ID) {
                beginSearchOpenedConnect(silent = true)
            }
            return
        }
        // Pool already live on open → NO chip at all (it's just connected, like
        // any normal open). It must not say "offline · tap to connect" (the chip
        // defaults to Hidden, not Idle, so it never flashes that), and it must
        // not flash a needless "connected" either. Hidden = silent.
        searchConnCoord.set(ChatViewModelSearchConn.State.Hidden)

        viewModelScope.launch {
            // ── Reuse-existing-session lookup ──
            // If the caller passed a `resumeIdParam`, it means they tapped a
            // session that the CLI has already created on the server (or that
            // we attached to before via --resume). Look in the
            // AgentSessionManager for an in-memory AgentSession that's
            // already running with that exact resumeId — if we find one,
            // adopt it instead of paying another SSH handshake AND, critically,
            // we keep its `_history` which holds any pending UserText from the
            // previous time the chat was opened. Without this, exiting the
            // chat and coming back lost anything you had typed while the SSH
            // was still bootstrapping.
            // Reuse path 1: the caller passed a resumeId, look for an
            // already-running session attached to that CLI thread id.
            // Reuse path 2 (issue #38): no resumeId — the caller asked
            // for a brand-new chat, but a previous "+ new session"
            // attempt on the same (serverId, agent) might still be
            // alive in the manager (user popped the chat off the back
            // stack while SSH was bootstrapping). Adopt it — its
            // `_history` holds the pending UserText the user typed.
            val existingAlive = if (resumeIdParam != null) {
                sessionsManager.findByResume(serverId, agent, resumeIdParam)
            } else {
                sessionsManager.findOrphanBrandNew(serverId, agent)
            }
            // ── Hardware security key pre-flight ──
            // If the server is keyed to a FIDO security-key row, we
            // need an SkSigner BEFORE openOrGet (because openOrGet
            // immediately calls AgentSession.start() which dials SSH
            // and runs `publickey` userauth on the way up). Surface a
            // touch request to the UI and suspend until it gives back
            // a ready-to-sign device. If the user dismisses the touch
            // dialog, the chat opens in a Failed state with a clear
            // message — they can retry from the chat header.
            //
            // We only do this when there isn't an already-alive session
            // for this resumeId (in which case we adopt that session
            // and its existing skSigner). Reconnects on the same
            // session refresh the signer at the openOrGet call.
            var pendingSkSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null
            // **Critical short-circuit.** If the pool already has an alive
            // SSHClient for this server (because the user did tap-to-connect
            // on the AgentPicker just now, or another chat tab is open),
            // the new AgentSession can ride that client via `pool.acquire`
            // — no fresh handshake, no userauth, no touch, no PIN. We
            // explicitly skip the awaitSkSignerFromUi path so the user
            // doesn't see a "tap your security key" dialog every time they
            // open a chat on a server they're already authenticated to.
            val existingPooled = ServiceLocator.sshConnectionPool.peek(serverId)
            if (existingAlive == null && existingPooled == null) {
                val server = repo.getById(serverId)
                val skKeyId = server?.sshKeyIds?.firstOrNull()
                if (skKeyId != null) {
                    val sshKey = ServiceLocator.sshKeyRepository.getById(skKeyId)
                    if (sshKey != null && (sshKey.type == ai.eight24family.conch.domain.SshKeyType.SK_ED25519 ||
                            sshKey.type == ai.eight24family.conch.domain.SshKeyType.SK_ECDSA_NISTP256)) {
                        val info = sshKey.securityInfo
                        if (info == null) {
                            _stateBySession.update {
                                it + (localId to SessionState.Failed(
                                    "Security-key row is missing handle/application — re-add the key."
                                ))
                            }
                            return@launch
                        }
                        pendingSkSigner = awaitSkSignerFromUi(info)
                            ?: run {
                                _stateBySession.update {
                                    it + (localId to SessionState.Failed(
                                        "Touch cancelled — no security key signer."
                                    ))
                                }
                                return@launch
                            }
                    }
                }
            }
            // Brand-new chat draft restore (issue #38). Only when we
            // ARE NOT adopting an existing orphan (its `_history`
            // already has the pending UserText) and there's no
            // resumeId (drafts only key on null-resumeId slots).
            // Restore each draft as a PendingSend so the drain
            // coroutine fires them the instant SSH reaches Running.
            if (existingAlive == null && resumeIdParam == null) {
                val drafts = SilentlyTry.logged("SshAi-Chat", "load drafts on chat start") {
                    ServiceLocator.historyCache.loadDrafts(serverId, agent)
                }.orEmpty()
                if (drafts.isNotEmpty()) {
                    val restored = drafts.map { txt ->
                        PendingSend(UUID.randomUUID().toString(), txt, System.currentTimeMillis())
                    }
                    _pending.update { it + restored }
                }
            }
            val s: AgentSession = existingAlive ?: (sessionsManager.openOrGet(
                serverId, agent, localId, resumeIdParam, skSigner = pendingSkSigner,
            ) ?: run {
                _stateBySession.update { it + (localId to SessionState.Failed("Server not found")) }
                if (pendingSkSigner != null) markSkOpDone()
                return@launch
            })
            // SSH is up + authenticated by now (openOrGet calls
            // AgentSession.start which runs openSshClient inline).
            // Release the touch dialog's NFC callback — the IsoDep
            // tag handle can die safely from here on, the persistent
            // sshClient holds the alive session. Idempotent if no
            // touch flow was active.
            if (pendingSkSigner != null) markSkOpDone()
            activeSessions[localId] = s
            // Apply the persisted `--model` selection to the freshly-opened session.
            // CLAUDE: pass `--model` ONLY on an explicit user pick. Native `claude
            // --resume` already keeps the session's model AND does Anthropic's own
            // fallback when it's unavailable — forcing `--model fable` on a session
            // whose configured default is the now-suspended Fable 5 hard-failed
            // every send ("No response requested") and made the user manually
            // switch, which is NOT native CLI behavior. No pick → no flag → the CLI
            // does its native thing and sends just work.
            //
            // CODEX/GEMINI: keep the session-model fallback — their resume
            // does NOT reliably preserve the model, so a chat that ran on
            // gpt-5.3-codex must re-pin it or it silently jumps to the
            // config.toml global.
            val isClaude = _currentAgent.value == ai.eight24family.conch.agent.Agent.CLAUDE
            val unavail = ai.eight24family.conch.agent.claude.claudeUnavailableLabels
            // For Claude, drop an explicit pick that's gone UNAVAILABLE (e.g. a stale
            // Fable 5 pick). Then, when there's no usable pick, default to claude's OWN
            // recommended AVAILABLE model — the FIRST non-greyed row of the live
            // `/model` menu (claude lists its recommended flagship first). Why we must
            // pass a model at all: the server's settings.json default can be a
            // suspended model (Fable 5), and claude does NOT silently fall back for
            // `--print` sessions — it runs the dead model and EVERY turn errors
            // "currently unavailable", so the chat AND the Connect-phone handshake
            // never start (verified: no --model → fable → is_error). This is GENERIC —
            // when Anthropic ships a new flagship or suspends/restores one, the probe
            // refreshes availableModels/unavailable and the app follows with ZERO code
            // changes. NO model name is hardcoded. Explicit available pick still wins.
            val claudePick = selectedModel.value?.takeIf { it.isNotBlank() }?.takeIf { p ->
                (modelsCoord.availableModels.value[p] ?: p) !in unavail
            }
            val claudeRecommended = modelsCoord.availableModels.value.entries
                .firstOrNull { (k, label) -> k != "default" && label !in unavail }?.key
            s.modelOverride = (if (isClaude) (claudePick ?: claudeRecommended)
                else (selectedModel.value ?: modelsCoord.currentSessionInitialModel()))
                ?.takeIf { it.isNotBlank() }
            s.reasoningEffortOverride = (selectedReasoning.value
                ?: if (isClaude) null else modelsCoord.currentSessionInitialReasoning())
                ?.takeIf { it.isNotBlank() }
            // Apply persisted approval/sandbox mode.
            s.approvalMode = approvalMode.value
            // Probe Claude's bundled model display names so we don't lie to
            // the user with hardcoded "Sonnet 4.6" that goes stale every release.
            launch(Dispatchers.IO) { probeAvailableModels(s) }
            // Discover user-defined slash commands.
            launch(Dispatchers.IO) { probeCustomCommands(s) }
            // Seed the usage bar with the account's current plan window so it
            // shows real numbers the moment the chat opens (the 5h/weekly
            // window is account-wide — non-zero even on a brand-new chat).
            refreshUsage()

            // Seed AgentSession.history before wiring collectors so the very
            // first emission carries the prior chat (not an empty list which
            // would flash an empty UI for one frame).
            //
            // CRITICAL: skip the seed-from-cache path entirely when we're
            // reusing an existing AgentSession (existingAlive != null) — its
            // `_history` is the freshest source of truth, including any
            // pending UserText from before the user popped the chat. Calling
            // s.loadHistory() here would overwrite that with a stale snapshot.
            if (existingAlive != null) {
                // Reusing — leave the in-memory history intact.
            } else if (cachedParsed.isNotEmpty()) {
                // Prefer the reconnect carry (seedMessages) when it's at least as
                // complete as the cache: it includes any message a dead-transport
                // abort DROPPED (which never reached the server JSONL/cache). By
                // seeding the SESSION history from it — not just the display list
                // — the collector keeps that message on screen through the
                // rebuild instead of blinking it out until echo-free re-delivery
                // lands. Normal open path (seedMessages null) uses the cache.
                val histSeed = if (!seedMessages.isNullOrEmpty() && seedMessages.size >= cachedParsed.size)
                    seedMessages else cachedParsed
                s.loadHistory(histSeed)
            } else if (resumeFilePath != null && resumeIdParam != null) {
                // The display only ever needs the last DISPLAY_TAIL_BYTES (the
                // full body is still fetched right after, for search + tail-poll
                // offsets). Best-effort: if the pool isn't live or the tail fetch
                // misses, we fall straight through to the unchanged full-fetch
                // below.
                run {
                    val tailClient = ServiceLocator.sshConnectionPool.peek(serverId) ?: return@run
                    val tailCmd = "bash -lc " + ai.eight24family.conch.agent.shellEscape(
                        "tail -c $DISPLAY_TAIL_BYTES -- " + ai.eight24family.conch.agent.shellEscape(resumeFilePath)
                    )
                    val tailRaw = withContext(Dispatchers.IO) {
                        SilentlyTry.logged("SshAi-Chat", "fetch session tail for fast paint") {
                            val sess = tailClient.startSession()
                            try {
                                val proc = sess.exec(tailCmd)
                                val out = java.io.ByteArrayOutputStream()
                                proc.inputStream.copyTo(out)
                                proc.join(20, java.util.concurrent.TimeUnit.SECONDS)
                                out.toByteArray()
                            } finally { SilentlyTry.fired("SshAi-Chat", "close tail session") { sess.close() } }
                        }
                    }
                    if (tailRaw == null || tailRaw.isEmpty()) return@run
                    // `tail -c N` returns exactly N bytes iff the file is larger →
                    // there ARE earlier turns (show the hidden-history marker) and
                    // the slab starts mid-line (drop the partial first line).
                    val windowed = tailRaw.size >= DISPLAY_TAIL_BYTES
                    val safe = if (windowed) {
                        val nl = tailRaw.indexOf('\n'.code.toByte())
                        if (nl in 0 until tailRaw.size - 1) tailRaw.copyOfRange(nl + 1, tailRaw.size) else tailRaw
                    } else tailPollCoord.trimToLastNewline(tailRaw)
                    val parsed = tailPollCoord.parseJsonl(safe, agent)
                    if (parsed.isNotEmpty()) {
                        val display = if (windowed) listOf(historyWindowMarker()) + parsed else parsed
                        s.loadHistory(display)
                        _messagesBySession.update { m ->
                            if ((m[localId]?.size ?: 0) > display.size) m else m + (localId to display)
                        }
                    }
                }
                // ── PHASE 2 — full body for the cache (unchanged) ──
                // No cache yet — fetch the full file. Prefer the
                // pooled SSHClient (already authenticated, free
                // channel) over a fresh `ssh.execute` handshake;
                // the latter wouldn't even work for SK servers
                // because it has no signer at this point.
                val server = _server.value
                if (server != null) {
                    val secrets = repo.getSecrets(serverId)
                    val pooled = ServiceLocator.sshConnectionPool.peek(serverId)
                    val raw: String? = if (pooled != null) {
                        discovery.fetchSessionContent(resumeFilePath) { cmd ->
                            withContext(Dispatchers.IO) {
                                SilentlyTry.logged("SshAi-Chat", "fetch session content for chat") {
                                    val sess = pooled.startSession()
                                    try {
                                        val proc = sess.exec(cmd)
                                        val out = java.io.ByteArrayOutputStream()
                                        proc.inputStream.copyTo(out)
                                        proc.join(60, java.util.concurrent.TimeUnit.SECONDS)
                                        String(out.toByteArray(), Charsets.UTF_8)
                                    } finally { SilentlyTry.fired("SshAi-Chat", "close fetch session") { sess.close() } }
                                }
                            }
                        }
                    } else {
                        // No live pool client. For non-SK this still
                        // works (password / soft-key auth), for SK
                        // it'll cleanly return null and we just
                        // render the empty chat — the SK touch
                        // dialog further down will fix the connection.
                        discovery.fetchSessionContent(server, secrets, resumeFilePath)
                    }
                    if (!raw.isNullOrBlank()) {
                        // Off the Main thread: cache the FULL body, then DISPLAY-parse
                        // only the recent tail from the mmap. Parsing a 20 MB first-open
                        // session on Main froze the chat; saving 20 MB on Main would too.
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            val bytes = raw.toByteArray(Charsets.UTF_8)
                            val safe = tailPollCoord.trimToLastNewline(bytes)
                            // FULL body cached first (search + tail-sync byte-offset
                            // contract depend on the whole file being present).
                            ServiceLocator.historyCache.save(resumeIdParam, safe)
                            cachedBytesLen = safe.size.toLong()
                            // Window the DISPLAY parse off the freshly-saved mmap — never
                            // decode 20 MB here. Falls back to the byte parse if the
                            // re-load races (cache should be present, just written).
                            val parsed = ServiceLocator.historyCache.load(resumeIdParam)?.use { snap ->
                                val win = ai.eight24family.conch.util.JsonlUtils
                                    .tailSlice(snap.buffer, DISPLAY_TAIL_BYTES)
                                val p = tailPollCoord.parseJsonl(win.slice, agent)
                                if (win.windowed) listOf(historyWindowMarker()) + p else p
                            } ?: tailPollCoord.parseJsonl(safe, agent)
                            s.loadHistory(parsed)
                            // Caching the body makes this session searchable — so it
                            // MUST also get a durable owner, or it becomes a serverless
                            // orphan in search (server-less row + empty/offline open).
                            // The prefetch sweep records owners for LISTED sessions; a
                            // chat opened directly (never swept) only hit this path, so
                            // record it here too.
                            ServiceLocator.historyCache.recordOwner(resumeIdParam, server.id, agent, resumeFilePath)
                        }
                    } else {
                        // No cache AND the server returned nothing — the rollout is
                        // gone/unreachable (Claude compacts/deletes them; `stat`
                        // returns nothing). Flag it so the UI shows "session
                        // unavailable" instead of hanging on "// loading…" forever
                        // (user, 2026-06-13). The tail-poll keeps running, so if the
                        // file reappears later, content lands and the flag is moot.
                        _loadCameBackEmpty.value = true
                    }
                } else {
                    // No resolvable server for a resumed session → nothing to fetch.
                    _loadCameBackEmpty.value = true
                }
            }

            collectorJobs[localId] = viewModelScope.launch {
                launch {
                    s.liveThinkingTokens.collect { n ->
                        _thinkingTokensBySession.update { it + (localId to n) }
                    }
                }
                launch {
                    s.state.collect { st ->
                        _stateBySession.update { it + (localId to st) }
                        // Every state transition counts as "something happened" —
                        // resets the stream-stall watchdog so it only fires on
                        // genuine quiescence inside Working.
                        lastStreamUpdate[localId] = System.currentTimeMillis()
                        if (st !is SessionState.Working && streamStalled.value == localId) {
                            reconnectCoord.setStreamStalled(null)
                        }
                    }
                }
                // Track which agent-session id we've already propagated so the
                // instant-appearance work below runs ONCE per session, not on
                // every history emit.
                var propagatedRid: String? = null
                launch {
                    s.history.collect { list ->
                        // Don't let a fresh chat's first emission (just the welcome
                        // banner) wipe a message the user typed BEFORE the welcome
                        // arrived: that offline-send bubble lives only in
                        // _messagesBySession (s.redeliver is echo-free), so a blind
                        // replace dropped it until the CLI echoed it back.
                        // preserveUnsyncedUserText keeps it until the JSONL echo
                        // covers its text, then it drops out — no duplicate.
                        _messagesBySession.update { m ->
                            m + (localId to preserveUnsyncedUserText(m[localId].orEmpty(), list))
                        }
                        // ── Resume propagation + instant list appearance ── The
                        // instant the CLI mints this session's id, push it to
                        // _resumeId so ANY rebuild (reconnect after a mid-turn
                        // transport drop) RESUMES the same server session instead
                        // of starting fresh — the root of (every drop spawned a
                        // new server session). AND upsert the row into
                        // SessionsCache + record its durable owner NOW, so it
                        // shows in the per-server session list immediately instead
                        // of waiting for the next server-listing sweep.
                        val rid = s.agentSessionId
                        if (rid != null && rid != propagatedRid) {
                            propagatedRid = rid
                            if (_resumeId.value != rid) _resumeId.value = rid
                            val firstUser = list.firstOrNull { it is AgentMessage.UserText }
                                ?.let { (it as AgentMessage.UserText).text }
                                ?.replace('\n', ' ')?.trim()?.take(140).orEmpty()
                            val nowSec = System.currentTimeMillis() / 1000
                            val row = ai.eight24family.conch.agent.RemoteSession(
                                id = rid,
                                path = sessionPathMap[localId].orEmpty(),
                                agent = agent,
                                lastActiveAt = nowSec,
                                preview = firstUser,
                                model = observedModel.value ?: sessionInitialModel.value,
                            )
                            viewModelScope.launch(Dispatchers.IO) {
                                ServiceLocator.sessionsCache.upsert(serverId, agent, row)
                                ServiceLocator.historyCache.recordOwner(
                                    rid, serverId, agent,
                                    sessionPathMap[localId], nowSec,
                                )
                            }
                        }
                        // Home "N new" badge: while this chat is on-screen, record
                        // how many messages the user has seen. When they leave and
                        // the agent keeps producing, HomeSessionsViewModel badges
                        // the delta (SessionSeenTracker, keyed by session/resume id).
                        (s.agentSessionId ?: _resumeId.value)?.let { rid ->
                            // Baseline on what the user actually SAW (the display list),
                            // not the raw s.history size — on the offline/redeliver path
                            // an optimistic prompt the user saw lives in the display
                            // before its echo reaches s.history, so basing on s.history
                            // made the echo's later arrival read as a "new message" on
                            // the home badge for the user's OWN prompt (user, 2026-06-30).
                            // Keyed to this collector's OWN localId — never the global
                            // _localSessionId (that was the round-1 cross-session bug).
                            val seenCount = _messagesBySession.value[localId]?.size ?: list.size
                            ai.eight24family.conch.agent.SessionSeenTracker.markSeen(rid, seenCount)
                        }
                        // Fresh chunk / new message landed — bump the
                        // watchdog clock and clear any active stall flag
                        // for this session.
                        lastStreamUpdate[localId] = System.currentTimeMillis()
                        if (streamStalled.value == localId) {
                            reconnectCoord.setStreamStalled(null)
                        }
                    }
                }
                // When a turn ends (Working → Running), proactively clear the
                // "remote file open" flag so the prompt bar's Stop button
                // flips back to Send immediately. Without this the button
                // stays as Stop until the next tail tick re-runs `pgrep` and
                // confirms the agent process is gone — up to ~5 s of stale UI.
                launch {
                    var prev: SessionState? = null
                    s.state.collect { st ->
                        if (prev is SessionState.Working && st !is SessionState.Working) {
                            tailPollCoord.setRemoteFileOpen(false)
                            // Turn finished → release the next queued message (sent
                            // mid-turn, held in the visible cancelable outbox).
                            drainOutbox(s)
                        }
                        prev = st
                    }
                }
            }

            // Tail-poll the remote JSONL: catch up since the snapshot, then
            // listen for external growth (e.g. the user typed on their PC).
            if (resumeIdParam != null && resumeFilePath != null) {
                pollerJobs[localId] = viewModelScope.launch(Dispatchers.IO) {
                    tailPollCoord.tailPoll(s, agent, resumeIdParam, resumeFilePath, cachedBytesLen)
                }
            }
        }
    }

    // tailPoll + parseJsonl + statSize* + fetchTail + pickPollInterval + appendDeduped
    // are now owned by `ChatViewModelTailPoll`. The constants below are kept for
    // backwards-compatibility with anything else that referenced them (currently nothing
    // in this file).

    fun refreshSessions() {
        val server = _server.value ?: return
        val agent = _currentAgent.value
        // `discovery.list(server, secrets, agent)` opens a FRESH ssh.execute,
        // which on SK servers throws "security-key signer not provided" — and
        // since list() swallows that and returns empty, it would WIPE the list
        // to "No sessions yet" on a server that actually has sessions. So ride
        // the already-authenticated POOLED client instead: no fresh handshake,
        // no extra touch, identical for SK and password servers. No pool ⇒
        // nothing to ride; skip and let the next connect refresh us
        // (beginSearchOpenedConnect re-calls refreshSessions on success).
        val pooled = ServiceLocator.sshConnectionPool.peek(serverId) ?: run {
            android.util.Log.d("SshAi-Chat", "refreshSessions: pool offline, skipping discovery.list")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _refreshing.value = true
            try {
                var execFailed = false
                val list = discovery.list(agent) { cmd ->
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        ai.eight24family.conch.util.SilentlyTry.logged("SshAi-Chat", "fetch sessions list (pooled)") {
                            val sess = pooled.startSession()
                            try {
                                val proc = sess.exec(cmd)
                                val out = java.io.ByteArrayOutputStream()
                                proc.inputStream.copyTo(out)
                                proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                                String(out.toByteArray(), Charsets.UTF_8)
                            } finally {
                                ai.eight24family.conch.util.SilentlyTry.fired("SshAi-Chat", "close list session") { sess.close() }
                            }
                        }.also { if (it == null) execFailed = true }
                    }
                }
                // Never overwrite with a failure-empty result — that's exactly
                // the clobber that rendered "No sessions yet" on a populated
                // server. Keep whatever the cache / prior refresh showed.
                if (execFailed) {
                    android.util.Log.w("SshAi-Chat", "refreshSessions: pooled exec failed — keeping current list")
                    return@launch
                }
                _remoteSessions.value = list
                ai.eight24family.conch.util.SilentlyTry.fired("SshAi-Chat", "record durable owners (chat refresh)") {
                    ServiceLocator.historyCache.recordOwners(server.id, agent, list)
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun send(text: String) {
        claudeBlockLine.value?.let { why ->
            // Blocked run-state (no subscription / trial ended / rate limited /
            // login expired …): the turn would just fail, so refuse and say WHY
            // in-chat with the specific reason. The UI also disables send + shows a
            // banner — this is the guard for the keyboard-action path and any
            // programmatic send.
            _localSessionId.value?.let { sid ->
                _messagesBySession.update { m ->
                    m + (sid to ((m[sid] ?: emptyList()) + AgentMessage.Error(
                        UUID.randomUUID().toString(), why.trim(),
                    )))
                }
            }
            return
        }
        val sid = _localSessionId.value ?: run {
            android.util.Log.w("SshAi-Send", "VM.send DROP: _localSessionId is null")
            return
        }
        val s = activeSessions[sid] ?: run {
            // Read-only open (offline): no AgentSession yet. Sending is the
            // FIRST action that needs SSH → connect NOW (key on send, not on
            // open). Attachments/slash on this very first offline send are
            // skipped — they work normally once connected.
            val t = text.trim()
            if (t.isEmpty() && attachmentsCoord.snapshot().isEmpty()) return
            if (t.isNotEmpty()) {
                // Show the message IMMEDIATELY (optimistic). The old path buffered
                // it INVISIBLY into _pending until the session came up ~8 s later
                // (after the FIDO/device-key connect), so the user's message
                // appeared to VANISH the moment they hit send — read as "it
                // deleted my message". We render it now, carry it into the
                // rebuilt session (seedMessages in beginSearchOpenedConnect), and
                // deliver it ECHO-FREE once connected (pendingRedelivery →
                // s.redeliver, no second bubble; JSONL echo stays deduped).
                _messagesBySession.update { m ->
                    m + (sid to ((m[sid] ?: emptyList()) +
                        AgentMessage.UserText(UUID.randomUUID().toString(), t)))
                }
                pendingRedelivery = pendingRedelivery + t
                if (_resumeId.value == null) {
                    SilentlyTry.fired("SshAi-Chat", "append draft on offline first-send") {
                        ServiceLocator.historyCache.appendDraft(serverId, _currentAgent.value, t)
                    }
                }
            }
            android.util.Log.d("SshAi-Send", "offline open — connecting on first send (message shown optimistically)")
            beginSearchOpenedConnect()
            return
        }
        val staged = attachmentsCoord.snapshot()
        val trimmed = text.trim()
        // Slash commands hijack the send path — never go to the model.
        if (staged.isEmpty() && trimmed.startsWith("/") && runSlash(trimmed)) return
        // Block while any are still uploading. Skip failed; only ready paths are appended.
        if (staged.any { it.status is UploadStatus.Uploading }) return
        val ready = staged.mapNotNull { att ->
            val st = att.status
            if (st is UploadStatus.Ready) att to st.remotePath else null
        }
        if (trimmed.isEmpty() && ready.isEmpty()) return

        // Image paths sent STRUCTURALLY to the agent (Codex localImage / Gemini
        // @-mention) so the model sees the pixels — distinct from the prose path
        // list in finalText, which is kept for the bubble's inline preview (audit
        // 2026-06-14). Non-image files stay prose-only (the agent reads them).
        val imagePaths = ready.filter { it.first.isImage }.map { it.second }

        // Show the user's own images INSTANTLY from the bytes we already have
        // (just uploaded) — pre-decode into the inline-image cache so the chat
        // renders them locally: no server round-trip, no spinner.
        ready.forEach { (att, p) -> if (att.isImage) downloadsCoord.preloadInlineImage(p, att.bytes) }

        attachmentsCoord.snapshotAndClear()
        val finalText = buildString {
            if (trimmed.isNotEmpty()) append(trimmed)
            if (ready.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                val images = ready.filter { it.first.isImage }
                val others = ready.filter { !it.first.isImage }
                if (images.isNotEmpty()) {
                    append("Attached image(s) at:\n")
                    images.forEach { (_, p) -> append("- ").append(p).append('\n') }
                }
                if (others.isNotEmpty()) {
                    if (images.isNotEmpty()) append('\n')
                    append("Attached file(s) at:\n")
                    others.forEach { (a, p) -> append("- ").append(p).append("  (").append(a.displayName).append(")\n") }
                }
                if (trimmed.isEmpty()) append("\nPlease look at them.")
            }
        }
        if (finalText.isBlank()) return

        val curState = _stateBySession.value[sid] ?: SessionState.Idle
        // A turn is already running → DON'T hand the message to the CLI now: the
        // agent would queue it internally, invisibly and uncancelably. Hold it in
        // the VISIBLE outbox (rendered above the prompt bar, each with a cancel ✕)
        // and let [drainOutbox] send it in order the moment this turn ends.
        if (curState is SessionState.Working) {
            _outbox.update {
                it + QueuedMessage(
                    id = UUID.randomUUID().toString(),
                    text = finalText,
                    displayText = trimmed,
                    imagePaths = imagePaths,
                    thumbs = ready.filter { r -> r.first.isImage }.map { r -> r.first.bytes },
                    queuedAt = System.currentTimeMillis(),
                )
            }
            return
        }
        // Buffer ONLY while SSH is bootstrapping; send straight through when the
        // session is up and idle.
        if (curState is SessionState.Running) {
            // Hot path — session is up, push straight to AgentSession.
            viewModelScope.launch {
                s.send(finalText, imagePaths)
                val newId = s.agentSessionId
                if (newId != null && _resumeId.value != newId) {
                    _resumeId.value = newId
                    refreshSessions()
                }
            }
        } else {
            // Session is bootstrapping (or just opened, or briefly
            // reconnecting). Buffer the text and let the state-watcher in
            // init {} drain it the moment we reach Running. The user's
            // message is NOT shown in the chat history yet — it's rendered
            // there only after `s.send()` runs, just like the hot path —
            // and if the session never comes up, the text returns to the
            // input box via `returnedText` rather than disappearing.
            val p = PendingSend(UUID.randomUUID().toString(), finalText, System.currentTimeMillis(), imagePaths)
            _pending.update { it + p }
            // Persist into the brand-new-chat draft slot (issue #38).
            // If the user pops the chat off the back stack right now,
            // this VM dies and `_pending` (in-memory only) goes with
            // it. The draft survives — next time a ChatViewModel
            // opens "+ new session" on the same (serverId, agent),
            // `startNewChat` restores this text into `_pending` so
            // the drain coroutine fires it the moment SSH reaches
            // Running. Skip when `_resumeId` is already set — the
            // chat has a CLI thread id, future reopens go through
            // the resume path which carries its own history.
            if (_resumeId.value == null) {
                SilentlyTry.fired("SshAi-Chat", "append draft on pending send") {
                    ServiceLocator.historyCache.appendDraft(
                        serverId, _currentAgent.value, finalText
                    )
                }
            }
            viewModelScope.launch {
                kotlinx.coroutines.delay(BUFFER_TIMEOUT_MS)
                val stillQueued = _pending.value.any { it.id == p.id }
                if (stillQueued) {
                    _pending.update { lst -> lst.filterNot { it.id == p.id } }
                    _returnedText.emit(p.text)
                    // Buffer timed out; the text is back in the input
                    // box via returnedText, so drop it from the draft
                    // slot — leaving it there would resurrect a stale
                    // prompt next time the user opens "+ new session".
                    if (_resumeId.value == null) {
                        SilentlyTry.fired("SshAi-Chat", "remove timed-out draft") {
                            ServiceLocator.historyCache.removeDraft(
                                serverId, _currentAgent.value, p.text
                            )
                        }
                    }
                }
            }
        }
    }

    fun addAttachment(bytes: ByteArray, displayName: String, mimeType: String?) =
        attachmentsCoord.addAttachment(bytes, displayName, mimeType)

    /** Large file already staged to a temp file — streamed up, no in-RAM copy. */
    fun addFileAttachment(file: java.io.File, displayName: String, mimeType: String?, sizeBytes: Long) =
        attachmentsCoord.addFileAttachment(file, displayName, mimeType, sizeBytes)

    fun removeAttachment(id: String) = attachmentsCoord.removeAttachment(id)

    fun clearAttachments() = attachmentsCoord.clearAttachments()

    fun setModel(model: String?) {
        modelsCoord.setModel(model) { trimmed ->
            _localSessionId.value?.let { sid ->
                activeSessions[sid]?.modelOverride = trimmed
            }
        }
    }

    /** Pin a reasoning-effort level (Codex's `low|medium|high|xhigh`, Claude's
     *  `low|medium|high|max`) to this chat. `null` clears the pin. Same isolation
     *  rules as [setModel]. */
    fun setReasoning(effort: String?) {
        modelsCoord.setReasoning(effort) { trimmed ->
            _localSessionId.value?.let { sid ->
                activeSessions[sid]?.reasoningEffortOverride = trimmed
            }
        }
    }

    /** Convenience: set model and reasoning in one shot. */
    fun setModelAndReasoning(model: String?, effort: String?) {
        setModel(model)
        setReasoning(effort)
    }

    /** Token totals + reported cost across the current chat. */
    val costStats: StateFlow<CostStats> = messages
        .map { list ->
            val cs = computeCostStats(list)
            // model_usage.contextWindow isn't in our message stream (we keep
            // only the result text), so contextMax is usually 0 → the
            // "Context window" row never showed. Derive a sane max: if the
            // context already exceeds the standard 200k window it MUST be a
            // 1M-context model; otherwise default to 200k.
            if (cs.contextMax > 0L || cs.contextTokens == 0L) cs
            else cs.copy(contextMax = if (cs.contextTokens > 200_000L) 1_000_000L else 200_000L)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CostStats())

    /** Full provider plan rate-limit report (all windows), fetched server-side
     *  — the credential never reaches the app (see [UsageProbe]). null when
     *  there's no machine-readable limit (API-key mode, Gemini, no live link). */
    private val _usage = MutableStateFlow<UsageReport?>(null)

    /** The whole report (all windows) for the tap-to-open limits sheet. */
    val usageReport: StateFlow<UsageReport?> = _usage.asStateFlow()

    /** Reset epoch (ms) the CLI itself reported when this account got
     *  rate-limited ("resets 8:30pm"). Authoritative when the server-side probe
     *  can't read the reset (inference-only tokens 403 the usage endpoint), so
     *  the bar shows the real reset instead of a stale "resets now". Set by the
     *  message watcher below; self-expires once past; a fresh <100% probe
     *  overrides it (account switched / limit cleared) — see [usageBar]. */
    private val _cliLimitReset = MutableStateFlow<Long?>(null)

    /**
     * The thin bar above the chat input (replaces the old static divider).
     * Shows the NEAREST plan window (accent fill + "14% · 3h"); else the API
     * spend Claude reports, or a raw token count; else empty so the bar renders
     * as the plain 1.dp divider. Tapping it opens the full breakdown (all
     * windows — weekly, per-model). Auto-picks without asking the auth method:
     * if the limit fetch yields data we're on a plan, else we fall to spend.
     */
    /** Ticks the usage-bar reset countdown live (every 30 s) off the absolute
     *  reset time, so it counts DOWN without a refetch — user 2026-06-14: the
     *  bar froze at "49m" while the desktop ticked to 14m because the string was
     *  baked at fetch time and only refreshed on open / turn-finish. */
    private val usageTicker = kotlinx.coroutines.flow.flow {
        while (true) { emit(Unit); kotlinx.coroutines.delay(30_000) }
    }

    val usageBar: StateFlow<UsageBarState> = combine(_usage, costStats, _cliLimitReset, usageTicker) { report, cost, cliReset, _ ->
        val now = System.currentTimeMillis()
        val primary = report?.primary
        // Authoritative CLI reset: when the account is rate-limited and the
        // server-side probe can't read the reset (inference-only token 403s the
        // usage endpoint), honour the reset the CLI printed ("resets 8:30pm").
        // A fresh probe proving we're back UNDER 100% (switched account / limit
        // cleared) overrides it, so a stale limit can't stick past its lift.
        val probeSaysClear = primary != null && primary.percent < 100
        if (cliReset != null && cliReset > now && !probeSaysClear) {
            // Rate-limited: show the reset as a LOCAL clock time ("resets 10:30 AM")
            // in the user's own zone — not the CLI's foreign-zone "8:30pm".
            val clock = ai.eight24family.conch.agent.usageResetClock(cliReset)
            return@combine UsageBarState(
                fill = 1f,
                label = "100% · $clock",
                filled = true,
                severity = 1f,
            )
        }
        when {
            primary != null -> {
                // At/over the limit → absolute local clock (when does it lift?);
                // below it → a live countdown ("how long until my window rolls").
                val reset =
                    if (primary.percent >= 100) primary.resetAtEpochMs?.let { ai.eight24family.conch.agent.usageResetClock(it) }.orEmpty()
                    else primary.resetTextLive(now)
                UsageBarState(
                    fill = primary.fraction,
                    label = "${primary.percent}%" + if (reset.isNotEmpty()) " · $reset" else "",
                    filled = true,
                    severity = primary.usedFraction,
                )
            }
            cost.totalCostUsd > 0.0 ->
                UsageBarState(label = "$" + String.format(java.util.Locale.US, "%.2f", cost.totalCostUsd))
            (cost.inputTokens + cost.outputTokens) > 0L ->
                UsageBarState(label = formatTokens(cost.inputTokens + cost.outputTokens) + " tok")
            else -> UsageBarState.EMPTY
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UsageBarState.EMPTY)

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    private var usageJob: Job? = null

    /** Re-read the plan windows from the provider (server-side). Cheap; called
     *  on chat open and when a turn finishes. Shows the cached value instantly
     *  so the bar is never empty on (re)open, and a failed refresh keeps the
     *  last good value instead of blanking the bar.
     *
     *  The server command itself is fast (~0.3s, ridden over the pooled SSH —
     *  measured 2026-06-27). The bar felt "slow to update" because the LIVE
     *  fetch on chat-open silently no-ops when the pooled connection isn't up
     *  YET (peek == null at that instant) — and then nothing refreshed it until
     *  the next turn finished. So: if live comes back null, keep retrying
     *  briefly as the connection comes online, instead of leaving a stale bar. */
    fun refreshUsage() {
        val agent = _currentAgent.value
        // Instant: last good value (warm from the sessions-list prefetch) so the
        // bar is already there on open, not popping in seconds later.
        UsageProbe.cached(serverId, agent)?.let { if (_usage.value == null) _usage.value = it }
        usageJob?.cancel()
        usageJob = viewModelScope.launch(Dispatchers.IO) {
            // FAST: cheap source paints within a few hundred ms (Codex rollout
            // snapshot with projected resets / Claude's cached value)...
            UsageProbe.fetch(serverId, agent, fast = true)?.let { _usage.value = it }
            // ...then LIVE refines. Retry on null (= no warm connection yet) up
            // to ~9s so the bar fills the moment the transport is ready; succeeds
            // on the first try in the common case (connection already warm).
            repeat(6) {
                UsageProbe.fetch(serverId, agent, fast = false)?.let { _usage.value = it; return@launch }
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    // ── Claude /context category breakdown (on-demand: fetched when the usage
    //    panel is expanded). Runs on a throwaway copy server-side — never
    //    touches the real session. Slow (~15-30s), so drives a loading state. ──
    private val _contextBreakdown = MutableStateFlow<List<ai.eight24family.conch.agent.ContextSegment>?>(null)
    val contextBreakdown: StateFlow<List<ai.eight24family.conch.agent.ContextSegment>?> = _contextBreakdown.asStateFlow()
    private val _contextLoading = MutableStateFlow(false)
    val contextLoading: StateFlow<Boolean> = _contextLoading.asStateFlow()

    fun fetchContextBreakdown() {
        if (_currentAgent.value != Agent.CLAUDE) return
        // Prefer the resume id; fall back to this chat's local session id (a
        // brand-new chat has no resume id yet, but its jsonl exists on disk).
        val rid = _resumeId.value ?: _localSessionId.value ?: return
        UsageProbe.cachedContext(rid)?.let { _contextBreakdown.value = it; return }
        if (_contextLoading.value) return
        _contextLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val r = UsageProbe.fetchContextBreakdown(serverId, rid)
            _contextBreakdown.value = r
            _contextLoading.value = false
        }
    }

    init {
        // Feed the usage bar the CLI's OWN rate-limit reset ("resets 8:30pm").
        // When an inference-only account is limited, the server-side probe can't
        // read the reset (usage endpoint 403s on scope user:inference), so the
        // bar would decay to a lying "resets now". The CLI prints the truth in
        // its turn output — parse it off the terminal Result/Error message. Only
        // Result/Error (a turn's failure text) are scanned, never assistant
        // replies, so a reply that merely mentions "resets 5pm" can't false-trip.
        viewModelScope.launch {
            messages.collect { msgs ->
                if (_currentAgent.value != Agent.CLAUDE) return@collect
                val now = System.currentTimeMillis()
                val zone = java.time.ZoneId.systemDefault()
                // Only the last few messages: a rate-limit Result/Error is the
                // turn's TERMINAL event, so it lives at the tail. Scanning the
                // tail (not all history) means an OLD limit that the user has
                // since moved past doesn't keep re-arming the bar.
                val reset = msgs.asReversed().asSequence().take(3).mapNotNull { m ->
                    val t = when (m) {
                        is AgentMessage.Result -> m.text
                        is AgentMessage.Error -> m.text
                        else -> null
                    }
                    ai.eight24family.conch.agent.RateLimitReset.parse(t, now, zone)
                }.firstOrNull { it > now }
                val last = msgs.lastOrNull()
                when {
                    reset != null -> _cliLimitReset.value = reset
                    // Forward progress past the limit — the user sent again or the
                    // agent replied — so we're no longer sitting on that failure.
                    // Drop it (a re-hit re-arms it) so a fresh turn / switched
                    // account isn't painted as still-limited.
                    last is AgentMessage.UserText || last is AgentMessage.AssistantText ->
                        _cliLimitReset.value = null
                }
            }
        }
        // Refresh the usage bar every time a turn finishes (Working → not).
        viewModelScope.launch {
            var wasWorking = false
            state.collect { st ->
                val working = st is SessionState.Working
                if (wasWorking && !working) {
                    refreshUsage()
                    // Anthropic's usage endpoint reflects a just-finished turn
                    // with a few seconds' lag, so the turn-end read often re-reads
                    // the OLD number. One delayed re-poll converges the bar quickly
                    // instead of leaving it frozen until the next turn.
                    launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(6_000)
                        UsageProbe.fetch(serverId, _currentAgent.value, fast = false)?.let { _usage.value = it }
                    }
                }
                wasWorking = working
            }
        }
        // Same refresh for a MIRRORED turn (driven from the CLI/another device):
        // it never flips OUR SessionState.Working, so the watcher above stays
        // silent and the bar froze at the last app-driven number while the CLI's
        // own display kept climbing. The tail-poll's remoteFileOpen is the
        // mirrored-turn "working" signal — refresh on its true→false edge, exactly
        // like our own turns. Overlap with the watcher above is harmless:
        // refreshUsage cancels the prior probe job, and the delayed re-poll just
        // converges the same value.
        viewModelScope.launch {
            var wasRemote = false
            tailPollCoord.remoteFileOpen.collect { remote ->
                if (wasRemote && !remote) {
                    refreshUsage()
                    launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(6_000)
                        UsageProbe.fetch(serverId, _currentAgent.value, fast = false)?.let { _usage.value = it }
                    }
                }
                wasRemote = remote
            }
        }
        // The 5h/weekly windows are ACCOUNT-WIDE: other sessions, the CLI, other
        // devices move them even when THIS chat is idle. Refreshing only on
        // turn-end left the bar frozen at a 2-hour-old number. So poll every 30s
        // while FOREGROUND (backgroundedSince == null); pause when backgrounded (no
        // one's looking, and it costs a poll). One ~0.3s server-side exec per tick.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (backgroundedSince == null) refreshUsage()
            }
        }
        // Prefetch the Claude /context breakdown the moment the chat has a
        // session id — so the usage panel's "Context window" row + detail toggle
        // are ready BEFORE the panel opens, instead of popping in ~2s late and
        // growing the panel over the chat. Guarded (Claude-only, cached, single
        // in-flight) so this collect is a no-op once it has run successfully.
        viewModelScope.launch {
            combine(_currentAgent, _resumeId, _localSessionId) { a, r, l ->
                a == Agent.CLAUDE && (r != null || l != null)
            }.collect { ready -> if (ready) fetchContextBreakdown() }
        }
        // Warm the server-name cache so re-opening this chat renders the server
        // name in the topbar at frame zero (vm.cachedServerName), not an empty
        // slot while Room loads.
        viewModelScope.launch {
            server.collect { it?.let { s -> rememberServerName(serverId, s.name) } }
        }
    }

    /** Stop the in-flight agent turn (kills the live exec channel) and ADVANCE to
     * the queue: the visible outbox is PRESERVED, so cancelling the turn (which
     * ends it → Working→Running) fires [drainOutbox] and the next queued message
     * starts right away. Stop = "halt what's running and go to the queued one".
     * To DISCARD a queued message instead of running it, use its ✕
     * ([cancelQueued]) — Stop no longer wipes the queue. */
    fun stopCurrent() {
        val sid = _localSessionId.value ?: return
        val s = activeSessions[sid] ?: return
        s.cancelCurrent()
        // Kill the working verb NOW. The verb shows on state==Working OR the mirror
        // poll's remoteFileOpen; cancelCurrent handles the app-driven state, but
        // the poll flag would keep the gerund up until the next tick re-evaluated
        // the file. Clear it optimistically; the poll re-lights only on genuine new
        // growth — e.g. the queued message's own turn starting via drainOutbox.
        tailPollCoord.setRemoteFileOpen(false)
    }

    /**
     * Pull a file the agent mentioned in its reply down to the phone.
     * Detection runs in the chat renderer (see `PathDetector`); this is
     * the action behind the disk icon.
     *
     * Save target picked at runtime by API level:
     *  - Android 10+: MediaStore Downloads (no permission required, file
     *    appears in `Files → Downloads → sshai/` on every phone).
     *  - Older: app-private Downloads dir (visible in Files but only the
     *    app can manage it). Avoids the WRITE_EXTERNAL_STORAGE prompt.
     *
     * Re-tap during a download is a no-op. Re-tap on a finished one
     * re-runs the SSH stream — the user explicitly asked for it again.
     */
    fun downloadFile(remotePath: String) = downloadsCoord.downloadFile(remotePath)

    // ───────────────────── Slash command dispatcher ─────────────────────
    // Logic owned by `ChatViewModelSlash`. The public API stays on ChatViewModel
    // (UI subscribers).
    /** Discovered slash commands (~/.claude/commands/, etc.). */
    val customCommands: StateFlow<List<SlashCommand>> get() = slashCoord.customCommands

    /** Memory editor state. */
    val memory: StateFlow<MemoryDocs> get() = slashCoord.memory

    fun runSlash(text: String): Boolean = slashCoord.runSlash(text)
    fun dispatchSlash(cmd: SlashCommand, args: String = "") = slashCoord.dispatchSlash(cmd, args)
    fun injectGitDiff() = slashCoord.injectGitDiff()
    fun openMemoryEditor() = slashCoord.openMemoryEditor()
    fun sendDisableApprovalsPrompt() = slashCoord.sendDisableApprovalsPrompt()
    fun sendInitPrompt() = slashCoord.sendInitPrompt()
    fun refreshMemory() = slashCoord.refreshMemory()
    fun saveMemory(scope: MemoryScope, contents: String) = slashCoord.saveMemory(scope, contents)
    private suspend fun probeCustomCommands(session: AgentSession) = slashCoord.probeCustomCommands(session)

    fun respondPermission(
        messageId: String,
        requestId: String,
        decision: ai.eight24family.conch.agent.PermissionDecision,
    ) {
        val sid = _localSessionId.value ?: return
        val s = activeSessions[sid] ?: return
        viewModelScope.launch { s.respondPermission(requestId, decision) }
    }

    /** Commit the user's picks for an AskUserQuestion card — routed to the
     *  live control channel (`control_response`), unblocking the turn. */
    fun respondQuestion(requestId: String, answers: Map<Int, List<String>>) {
        val sid = _localSessionId.value ?: return
        val s = activeSessions[sid] ?: return
        viewModelScope.launch { s.respondQuestion(requestId, answers) }
    }

    fun switchAgent(newAgent: Agent) {
        if (_currentAgent.value == newAgent) return
        viewModelScope.launch {
            repo.updateAgent(serverId, newAgent)
            _server.value = repo.getById(serverId)
            _currentAgent.value = newAgent
            // Drop stale per-agent model state — Claude's 'Opus 4.7' was leaking
            // into the Codex topbar because availableModels / defaultModel weren't
            // being cleared on agent switch.
            modelsCoord.resetOnAgentSwitch()
            startNewChat(newAgent)
            refreshSessions()
        }
    }

    /** Attach to an existing CLI session by its `--resume` id. */
    fun openRemoteSession(session: RemoteSession) {
        if (_resumeId.value == session.id) return
        // Seed the topbar's model display from the listing-time probe
        // BEFORE startNewChat triggers history load + SSH open. The
        // value lives until the next openRemoteSession / newSession,
        // by which point observedModel / defaultModel have taken
        // over.
        //
        // Only overwrite when the listing has an actual model name —
        // when it's null (e.g. older Codex JSONL where the model
        // didn't make it into the head -n 20 grep) we keep whatever
        // was passed in via SavedStateHandle from the tap-to-open
        // navigation, otherwise we'd downgrade a known model name
        // back to null and the topbar would flash to the agent
        // fallback ("codex") for one frame.
        if (!session.model.isNullOrBlank()) {
            modelsCoord.setSessionInitialModel(session.model)
        }
        if (!session.reasoning.isNullOrBlank()) {
            modelsCoord.setSessionInitialReasoning(session.reasoning)
        }
        startNewChat(_currentAgent.value, resumeIdParam = session.id, resumeFilePath = session.path)
    }

    /** Start a fresh CLI session, no --resume. */
    fun newSession() {
        modelsCoord.setSessionInitialModel(null)
        startNewChat(_currentAgent.value, resumeIdParam = null)
    }

    fun retry() {
        val sid = _localSessionId.value ?: return
        val agent = sessionAgentMap[sid] ?: _currentAgent.value
        val resume = _resumeId.value
        val path = sessionPathMap[sid]
        // Keep the messages we're currently showing so the rebuilt session can
        // re-seed them and the chat doesn't blank during the reconnect reload.
        val carry = _messagesBySession.value[sid]
        // Prompts a turn ABORTED on because the transport was already dead
        // (wrote a message right as the network switched → runOneShot ABORT →
        // it never reached the agent). Pull them BEFORE closing this session,
        // re-buffer below so the rebuilt+reconnected session re-delivers them.
        // Without this the message just sits there with no reply — the exact
        // bug.
        val undelivered = activeSessions[sid]?.consumeUndelivered().orEmpty()
        // Cancel the old session's collectors FIRST, then close — otherwise
        // close()'s Closed/empty emission can race the still-live collector and
        // blank the chat for a frame.
        collectorJobs.remove(sid)?.cancel()
        pollerJobs.remove(sid)?.cancel()
        sessionsManager.close(serverId, agent, sid)
        activeSessions.remove(sid)
        // DO NOT wipe _messagesBySession[sid]/_stateBySession[sid] here. The UI
        // still renders `sid`, but the rebuild (startNewChat) doesn't repaint
        // until AFTER the ~2-3s silent ephemeral reconnect below — wiping now
        // blanked for those seconds (the #2 bug, reported many times). Keep the
        // old slot on screen and drop it only AFTER the new session is seeded +
        // _localSessionId has flipped (post-startNewChat inside the launch), so
        // the swap is invisible. StatusLine already suppresses the stale
        // Failed("disconnected") meanwhile. Re-deliver the dropped prompt(s)
        // ECHO-FREE once the rebuilt session reaches Running. The bubble is
        // already on screen (carried in `carry` → seeded into BOTH the display
        // and the new session's history below), so re-delivery must NOT re-emit
        // it — re-emitting is exactly what doubled the message in earlier
        // builds. The init {} state-watcher drains [pendingRedelivery] via
        // s.redeliver() (enqueue + markSent, no emitMsg). Survives the rebuild
        // as a VM field. ACCUMULATE (don't overwrite): if a reconnect itself
        // fails and retry() runs again, the new session never ran a turn
        // (consumeUndelivered → empty), so this preserves the original dropped
        // prompt until a live session delivers it — at which point the drain
        // clears the list.
        pendingRedelivery = pendingRedelivery + undelivered
        viewModelScope.launch {
            // SEAMLESS reconnect: bring the SSH transport back SILENTLY via the
            // hardware device key (no FIDO tap) BEFORE re-opening the session.
            // Without this, startNewChat sees a dead pool (peek == null), opens
            // read-only, never reaches Running, _reconnecting never clears, and
            // the "// connection lost / Reconnecting…" spinner loops forever
            // (the exact bug after Wi-Fi → mobile data). Only when a device key
            // is enrolled (Seamless reconnect on); otherwise startNewChat behaves
            // as before. userConnectEphemeral never throws (returns null on fail).
            if (ServiceLocator.sshConnectionPool.peek(serverId) == null &&
                ai.eight24family.conch.ssh.EphemeralSshKey.exists(serverId)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repo.getById(serverId)?.let {
                        ServiceLocator.sshConnectionPool.userConnectEphemeral(it)
                    }
                }
            }
            startNewChat(agent, resumeIdParam = resume, resumeFilePath = path, seedMessages = carry)
            // startNewChat synchronously created the new localId, seeded `carry`
            // into it, and flipped _localSessionId — so the chat now renders the
            // rebuilt slot showing the SAME messages (invisible swap). Only NOW
            // is it safe to drop the old slot: no blank window, no map leak.
            if (_localSessionId.value != sid) {
                _messagesBySession.update { it - sid }
                _stateBySession.update { it - sid }
            }
        }
    }

    /**
     * Clear the stream-stalled flag so the UI hides the "Stream paused"
     * pill. Bumps lastStreamUpdate so the watchdog doesn't immediately
     * re-flag the same session, giving the user a clean ~30 s grace
     * window to see whether the transport unsticks on its own.
     *
     * TODO(1.1.0): once AgentSession grows a clean "cancel & restart
     * tail poller for the current resume id without tearing down the
     * SSH client" API, call it here so a stalled stream actually
     * resumes mid-turn. For now this just clears the flag and lets the
     * user re-send by tapping the input box (or hit the full reconnect
     * path via `retry()` from the topbar if the session is truly dead).
     */
    fun retryStream() {
        reconnectCoord.retryStream(_localSessionId.value)
    }

    /**
     * Marker row prepended to the DISPLAY list when the session was too big to
     * render whole and only the recent tail is shown. Honest: the user SEES that
     * earlier turns are hidden (never a silent "looks like everything loaded").
     * The full history stays cached on disk; this is display-only. Stable id so
     * re-hydrates / appends keep exactly one.
     */
    private fun historyWindowMarker(): AgentMessage =
        AgentMessage.EventNote(
            id = HISTORY_WINDOW_MARKER_ID,
            label = "↑ earlier history hidden — tap to load all",
            tone = AgentMessage.EventNote.Tone.DIM,
        )

    /**
     * Parse and show the ENTIRE cached session (no display window) — triggered by
     * tapping [historyWindowMarker]. The auto-open windows to the recent tail for
     * instant paint; this is the explicit "I want all of it" for reviewing e.g. a
     * long autonomous /loop run. Off the Main thread; seeds the session history
     * so the live collector keeps the full list (the marker drops out, no
     * duplicate).
     */
    fun loadFullHistory() {
        val localId = _localSessionId.value ?: return
        val resumeId = _resumeId.value ?: return
        val s = activeSessions[localId] ?: return
        val agent = sessionAgentMap[localId] ?: _currentAgent.value
        viewModelScope.launch(Dispatchers.Default) {
            val full = ServiceLocator.historyCache.load(resumeId)?.use { snap ->
                tailPollCoord.parseJsonl(
                    ai.eight24family.conch.util.JsonlUtils.trimToLastNewline(snap.buffer), agent,
                )
            }
            if (!full.isNullOrEmpty()) s.loadHistory(full)
        }
    }

    companion object {
        /** Public constant — referenced by ChatPromptBar / ChatScreenPromptHost. */
        const val MAX_ATTACHMENTS: Int = 10

        /** How many trailing bytes of a session JSONL the chat DISPLAY parses.
         *  The full file stays cached; only the visible conversation is bounded.
         *  ~2 MB covers hundreds of recent turns — enough to always include the
         *  latest model_observed/effort rows — while keeping the Main-thread
         *  parse sub-100 ms even on a 20 MB+ ultracode-workflow session. */
        private const val DISPLAY_TAIL_BYTES: Int = 2 * 1024 * 1024

        /** Stable id for [historyWindowMarker] — internal so the chat row renderer
         *  can recognise it and wire the tap-to-load-all action. */
        internal const val HISTORY_WINDOW_MARKER_ID = "history-window-marker"

        /** In-memory serverId→name cache so a re-opened chat shows the server
         *  name in the topbar from frame zero instead of an empty slot while
         *  Room loads. Warmed whenever a chat's server resolves. */
        private val serverNameMemory = java.util.concurrent.ConcurrentHashMap<String, String>()
        internal fun cachedServerNameFor(id: String): String? = serverNameMemory[id]
        internal fun rememberServerName(id: String, name: String?) {
            if (!name.isNullOrBlank()) serverNameMemory[id] = name
        }

        /**
         * How long a tapped-but-not-yet-sent message sits in the in-memory
         * buffer before we give up and put the text back into the input
         * field. 30 s covers a typical SSH handshake retry on a flaky
         * connection without making the user feel stuck.
         */
        private const val BUFFER_TIMEOUT_MS: Long = 30_000L

        /**
         * How long an assistant turn can sit in Working with no fresh
         * content or state delta before we surface a "Stream paused"
         * affordance. 30 s mirrors the buffer-send timeout — long enough
         * to ride out a real claude/codex "thinking" pause, short enough
         * that a network blip doesn't trap the user in an indefinite
         * spinner loop.
         */
        private const val STREAM_STALL_TIMEOUT_MS: Long = 30_000L
    }
}

// Top-level data classes (ChatModal, MemoryDocs, CostStats, StagedAttachment, UploadStatus)
// and helper functions (computeCostStats) live in ChatViewModelTypes.kt.
