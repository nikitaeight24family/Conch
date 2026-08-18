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
    /** This chat was opened as a FORK of [initialResumeId]: the CLI inherits
     *  that conversation and mints a new id for us, leaving the original
     *  untouched. One-shot — the session clears it the moment the new id
     *  lands. */
    private val openAsFork: Boolean = savedStateHandle.get<String>("fork") == "1"

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
    /** True while the in-flight connect is the SILENT on-open one (device key
     *  only, never a FIDO touch), and whether a stronger intent arrived while it
     *  was running. A send is a stronger intent than an open: it must not be
     *  swallowed by the "already connecting" guard and then dropped when the
     *  silent attempt gives up, which left the first message after opening an
     *  offline chat parked with nothing trying to deliver it. */
    @Volatile private var connectInFlightSilent = false
    @Volatile private var escalateAfterSilent = false

    /** Watches an offline-read-only chat for the transport coming up by ANY
     *  path, so it can upgrade itself to a live mirror. See
     *  [armOfflineUpgradeWatcher]. */
    private var offlineUpgradeJob: kotlinx.coroutines.Job? = null

    /**
     * A chat opened while the pool was down paints from cache and has NO
     * AgentSession and NO tail poller — a turn alive on the server lights
     * nothing. The on-open silent connect covers only the case where it
     * SUCCEEDS immediately (its success path re-runs startNewChat); when it
     * gives up — radio dead at open, or an SK server with no device key — the
     * chat used to stay read-only forever unless the user backed out and
     * re-entered (Workstream A #3, 2026-08-17). This watcher closes that:
     *
     *  - every few seconds it PEEKS the pool (in-memory, zero network) —
     *    catches a transport brought up by any other surface: the agent
     *    picker's tap-to-connect, another chat on the same server, the
     *    service watchdog's silent reconnect;
     *  - on a network-RETURN edge it re-fires the same silent connect the
     *    open did (device-key only — never a FIDO touch; the silent-dial
     *    backoff inside userConnectEphemeral still governs actual dials, so
     *    this can't feed fail2ban).
     *
     * The upgrade itself is beginSearchOpenedConnect's own success path
     * (startNewChat re-run → poller armed → fileWorking computed). The loop
     * only ever calls startNewChat directly when the pool is ALREADY live and
     * no connect is in flight — the "someone else connected" case, which has
     * no other trigger.
     */
    private fun armOfflineUpgradeWatcher() {
        offlineUpgradeJob?.cancel()
        offlineUpgradeJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var wasOnline = ai.eight24family.conch.util.NetworkCost.isOnline()
            val netJob = launch {
                ai.eight24family.conch.util.NetworkCost.online.collect { up ->
                    val cameBack = up && !wasOnline
                    wasOnline = up
                    if (cameBack &&
                        ServiceLocator.sshConnectionPool.peek(serverId) == null &&
                        !connectInFlightSilent
                    ) {
                        android.util.Log.d(
                            "SshAi-Chat",
                            "offline-upgrade watcher: network back — retrying silent connect",
                        )
                        beginSearchOpenedConnect(silent = true)
                    }
                }
            }
            try {
                // Exits via break (upgraded) or cancellation (delay is the
                // suspension point; onCleared / a fresh open cancels the job).
                while (true) {
                    kotlinx.coroutines.delay(3_000)
                    // Already upgraded (silent connect's own success path ran
                    // startNewChat, or the user sent and connected)? Done.
                    if (_localSessionId.value?.let { activeSessions[it] } != null) break
                    if (ServiceLocator.sshConnectionPool.peek(serverId) == null) continue
                    // Pool is live but nothing upgraded this chat — a connect
                    // that's still in flight will do its own re-run; otherwise
                    // it came up via another surface and we do it here.
                    if (searchConnCoord.get() == ChatViewModelSearchConn.State.Connecting) continue
                    android.util.Log.i(
                        "SshAi-Chat",
                        "offline-upgrade watcher: pool live — upgrading read-only chat to live mirror",
                    )
                    // Same call shape as beginSearchOpenedConnect's success path
                    // (which also runs on an IO coroutine): carry the read-only
                    // display so the rebuilt session doesn't blank to stale cache.
                    startNewChat(
                        agent = _currentAgent.value,
                        resumeIdParam = initialResumeId,
                        resumeFilePath = initialResumePath,
                        seedMessages = _messagesBySession.value[_localSessionId.value],
                    )
                    break
                }
            } finally {
                netJob.cancel()
            }
        }
    }

    fun beginSearchOpenedConnect(silent: Boolean = false) {
        if (searchConnCoord.get() == ChatViewModelSearchConn.State.Connecting) {
            if (!silent && connectInFlightSilent) escalateAfterSilent = true
            return
        }
        if (ServiceLocator.sshConnectionPool.peek(serverId) != null) {
            // Already connected: on a silent open show NOTHING (Hidden); only an
            // explicit tap/send wants the "connected" confirmation.
            searchConnCoord.set(
                if (silent) ChatViewModelSearchConn.State.Hidden
                else ChatViewModelSearchConn.State.Connected
            )
            return
        }
        connectInFlightSilent = silent
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
            } finally {
                connectInFlightSilent = false
                // The user sent (or tapped the chip) while the on-open silent
                // attempt was still running, and that attempt is allowed to give
                // up without asking for the key. Their intent isn't — run the
                // real connect now, which is what puts the queued message on the
                // wire instead of leaving it parked forever.
                if (silent && escalateAfterSilent) {
                    escalateAfterSilent = false
                    if (ServiceLocator.sshConnectionPool.peek(serverId) == null) {
                        android.util.Log.d(
                            "SshAi-Chat",
                            "silent connect gave up but a send is waiting — escalating to a real connect",
                        )
                        beginSearchOpenedConnect(silent = false)
                    }
                }
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
    /** Bumped exactly when a displayed limit's `resets_at` moment arrives, so
     *  [claudeBlockLine] re-evaluates and the banner falls the second the
     *  window actually resets. The status-cache flow alone only emits on
     *  WRITES — a chat sitting open across the reset kept the stale
     *  "Usage limit reached — resets 4:59 AM" (and the disabled send) for as
     *  long as no probe happened to land. */
    private val limitExpiryTick = MutableStateFlow(0L)
    @Volatile private var limitExpiryScheduledFor = 0L

    val claudeBlockLine: StateFlow<String?> =
        kotlinx.coroutines.flow.combine(
            _currentAgent,
            ServiceLocator.agentStatusCache.observeStatuses(serverId),
            limitExpiryTick,
        ) { agent, statuses, _ ->
            val st = statuses[agent]
            // A transient limit whose reset moment has passed is NOT a block —
            // the cache expires it at parse time, and this guard covers the
            // in-memory copy between the reset moment and the next cache read.
            val expired = ai.eight24family.conch.agent.ClaudeRunState.isExpired(
                st?.claudeState, st?.claudeStateData,
            )
            // For rate-limit states the datum is an ISO `resets_at` — render it as
            // a LOCAL clock time ("10:30 AM") in the user's own zone, not a raw
            // ISO or the CLI's foreign-zone string (user 2026-07-16).
            val data = st?.claudeStateData
            val isLimit = st?.claudeState == ai.eight24family.conch.agent.ClaudeRunState.RATE_LIMITED ||
                st?.claudeState == ai.eight24family.conch.agent.ClaudeRunState.NEAR_LIMIT
            if (isLimit && !expired) {
                // Arm a one-shot wakeup at the reset moment (+2 s of slack) so
                // the banner clears itself with no probe involved.
                ai.eight24family.conch.agent.parseIsoInstant(data)?.let { resetMs ->
                    if (limitExpiryScheduledFor != resetMs) {
                        limitExpiryScheduledFor = resetMs
                        viewModelScope.launch {
                            kotlinx.coroutines.delay((resetMs - System.currentTimeMillis() + 2_000).coerceAtLeast(0))
                            limitExpiryTick.value = resetMs
                        }
                    }
                }
            }
            val display = if (data != null && isLimit) {
                ai.eight24family.conch.agent.parseIsoInstant(data)
                    ?.let { ai.eight24family.conch.agent.usageResetClock(it) } ?: data
            } else data
            st?.claudeState?.takeIf { it.isBlocked && !expired }?.lineWith(display)
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
                // ⚠ SAME RULE AS THE OTHER WRITER: only a chat that started
                // WITHOUT a session may mint a row. On a resume the CLI
                // announces a fresh id per launch while still writing the old
                // rollout — none of those ids is a file, and a row for one can
                // never be cleared by a listing (the server has nothing to
                // report), so it outlives every delete. This collector was the
                // second source of exactly that (2026-08-03, after the first
                // was closed and the duplicate came straight back).
                if (initialResumeId != null) return@collect
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
        // real message has. Which background-task notes are THIS SESSION's, and
        // which belong to the agents. Computed once over the raw list — see
        // `foldTaskOwnership` for why the owner has to be inferred rather than read
        // off the wire.
        val ownership = ai.eight24family.conch.agent.foldTaskOwnership(raw)
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
                        m.label.contains("permissions.allow entries", ignoreCase = true) ||
                        // ── AGENTS' TASKS BELONG TO THE AGENTS ── A fan-out's
                        // background commands come down the SAME stream as the
                        // session's own (one task registry per session) and used
                        // to be rendered identically, so the conversation
                        // drowned in `task · completed · Background command "…"`
                        // rows the user had no reason to read. Keep the note
                        // only for a task this session itself started; the rest
                        // are accounted for in the agent panel.
                        (m.id.startsWith("sysevt-task-") && m.id !in ownership.ownTaskNoteIds)
                is AgentMessage.Error ->
                    m.text.contains("bubblewrap", ignoreCase = true) ||
                        m.text.contains("permissions.allow entries", ignoreCase = true)
                // Subagent bookkeeping feeds the roster, never the transcript.
                // Leaving it in the list made every record a real LazyColumn item
                // that renders zero height — but the list's 1.dp spacing is still
                // applied between items, so a fan-out injected a run of blank gaps
                // into the conversation.
                is AgentMessage.SubagentActivity -> true
                // The CLI's background-task SET (REPLACE semantics, fires on
                // every change). Drives the agent panel's count; never a row.
                is AgentMessage.BackgroundTasks -> true
                else -> false
            }
        }
        val shown = hideBridgeHandshake(deNoised)
        // Pure reorder of the one header row; nothing else moves.
        val wi = shown.indexOfFirst { it is AgentMessage.System && it.subtype == "welcome" }
        if (wi > 0) listOf(shown[wi]) + shown.filterIndexed { i, _ -> i != wi } else shown
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Live subagent roster for the open chat — the data behind the CLI's own
     * "← 1 agent · ↓ to manage" footer, which Conch previously showed nowhere
     * at all.
     *
     * Folded from the RAW per-session list, not from [messages]: subagent
     * activity is deliberately not a transcript row, so it never survives the
     * de-noising above.
     */
    val subagents: StateFlow<List<ai.eight24family.conch.agent.SubagentRun>> =
        combine(_localSessionId, _messagesBySession) { id, byId ->
            ai.eight24family.conch.agent.foldSubagents(
                if (id == null) emptyList() else byId[id].orEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Background commands the AGENTS are running right now.
     *
     * This is where the task rows that used to flood the transcript went. They
     * are the agents' work, so they are reported with the agents — one number in
     * the roster header, not one chat row per event per task.
     */
    val agentBackgroundTasks: StateFlow<Int> =
        combine(_localSessionId, _messagesBySession) { id, byId ->
            ai.eight24family.conch.agent.foldTaskOwnership(
                if (id == null) emptyList() else byId[id].orEmpty(),
            ).agentTaskCount
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** A live background WORKFLOW row (the ultracode `Workflow` tool). The
     *  CLI shows «name · N/M agents done · elapsed»; those live counts live in
     *  the workflow's own journal on the server, NOT in the session rollout, so
     *  [workflowPoller] reads them over the pooled SSH and publishes here. */
    data class LiveWorkflow(
        val runId: String,
        val name: String,
        val done: Int,
        val total: Int,
        val elapsedSec: Long,
        val finished: Boolean,
    )
    private val _liveWorkflows = MutableStateFlow<List<LiveWorkflow>>(emptyList())
    val liveWorkflows: StateFlow<List<LiveWorkflow>> = _liveWorkflows.asStateFlow()

    // ── Task board (the CLI's checklist) with a DURABLE name dictionary. ──
    // The fold sees only the display window; on long sessions the TaskCreate
    // rows scroll out and updates carry no subject — the board degraded to
    // "task #4". Every subject the fold ever learns is written to a
    // per-session sidecar and substituted back for windowed-out ids.
    private var taskNamesRid: String? = null
    private val taskNamesMem = HashMap<String, String>()

    private fun boardWithRememberedNames(
        rid: String?,
        rows: List<ai.eight24family.conch.ui.screens.TaskBoardRow>,
    ): List<ai.eight24family.conch.ui.screens.TaskBoardRow> {
        if (rid == null || rows.isEmpty()) return rows
        if (taskNamesRid != rid) {
            taskNamesRid = rid
            taskNamesMem.clear()
            taskNamesMem.putAll(
                SilentlyTry.loggedOrElse("SshAi-Chat", "load task names", emptyMap()) {
                    ServiceLocator.historyCache.taskNames(rid)
                },
            )
        }
        var learned = false
        for (r in rows) {
            if (!r.subject.startsWith("task #") && taskNamesMem[r.taskId] != r.subject) {
                taskNamesMem[r.taskId] = r.subject
                learned = true
            }
        }
        if (learned) {
            val snapshot = HashMap(taskNamesMem)
            viewModelScope.launch(Dispatchers.IO) {
                SilentlyTry.fired("SshAi-Chat", "persist task names") {
                    ServiceLocator.historyCache.recordTaskNames(rid, snapshot)
                }
            }
        }
        return rows.map { r ->
            if (r.subject.startsWith("task #")) {
                taskNamesMem[r.taskId]?.let { r.copy(subject = it) } ?: r
            } else r
        }
    }

    /** Descriptions of LIVE background tasks (CLI `task_started` rows without a
     * terminal status yet). Drives the pinned "waiting on background task"
     * line: a turn that parked its work in a background command leaves the
     * chat looking DEAD while the CLI legitimately sleeps until the task
     * notification. */
    val liveBgTasks: StateFlow<List<String>> =
        combine(_localSessionId, _messagesBySession) { id, byId ->
            val msgs = if (id == null) emptyList() else byId[id].orEmpty()
            // OUR OWN tasks only. This line exists to explain why a turn looks
            // dead — "the CLI is asleep waiting on the task it started". An
            // AGENT's background command explains nothing about the main turn,
            // and with a fan-out running it would pin a dozen of them.
            val own = ai.eight24family.conch.agent.foldTaskOwnership(msgs).ownTaskNoteIds
            val lastByTask = LinkedHashMap<String, String>()
            for (m in msgs) {
                if (m is AgentMessage.EventNote && m.id.startsWith("sysevt-task-") &&
                    m.id in own
                ) {
                    lastByTask[m.id] = m.label
                }
            }
            lastByTask.values
                .filterNot { BG_TASK_TERMINAL_RX.containsMatchIn(it) }
                .map { label ->
                    // "task · started · cd /home… && ./verify.sh · Bash" → the middle.
                    label.split(" · ").drop(2).joinToString(" · ").ifBlank { "background task" }
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val taskBoard: StateFlow<List<ai.eight24family.conch.ui.screens.TaskBoardRow>> =
        combine(_localSessionId, _messagesBySession, _resumeId) { id, byId, rid ->
            boardWithRememberedNames(
                rid,
                ai.eight24family.conch.ui.screens.foldTaskBoard(
                    if (id == null) emptyList() else byId[id].orEmpty(),
                ),
            )
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

    /** A `/loop` the CLI has armed in THIS chat — the model scheduled its own
     *  next turn and will spend tokens on it with no further input. Drives the
     *  countdown chip above the prompt bar; null = no loop running. */
    private val _loopBySession = MutableStateFlow<Map<String, ai.eight24family.conch.agent.LoopWatch.Armed?>>(emptyMap())
    val loopArmed: StateFlow<ai.eight24family.conch.agent.LoopWatch.Armed?> =
        combine(_localSessionId, _loopBySession) { id, byId ->
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
            // ⚠ Key mismatch, fixed: tailPoll passes the CLI RESUME id, but every
            // writer of lastStreamUpdate keys by the per-open localId (a fresh
            // UUID). The two are never equal, so this lookup always returned null
            // and the double-add guard it feeds was dead code. tailPoll runs for
            // the open chat, so resolve against that chat's local id.
            streamLastFedMs = { _ -> _localSessionId.value?.let { lastStreamUpdate[it] } },
            // The currently-displayed session's live pending question/permission —
            // authoritative WAITING-FOR-USER signal (never reaches the JSONL).
            pendingControl = { activeSessions[_localSessionId.value]?.hasPendingControl() ?: false },
        )
    }
    val remoteActive: StateFlow<Long?> get() = tailPollCoord.remoteActive
    val remoteFileOpen: StateFlow<Boolean> get() = tailPollCoord.remoteFileOpen

    /**
     * True when the next message will pay to re-send the whole conversation.
     *
     * The prompt cache lives at the provider and holds for about an hour: a
     * chat touched within that window is read back cheaply, one left overnight
     * is written again in full. Nothing in the app could see that — the user
     * only found out afterwards, from the limits (2026-08-03). Two conditions,
     * both cheap to know: no live CLI for this chat, and its last activity is
     * older than the hour.
     */
    val coldCacheRebuild: StateFlow<Boolean> =
        combine(_localSessionId, _resumeId, _stateBySession) { sid, rid, states ->
            // The PROCESS, not the transport. `isAlive()` answers "is there an
            // SSH link", and since the pool rebuilds links under us that can be
            // true with the CLI long gone — which is precisely the case whose
            // next turn re-reads and re-bills the whole session. Asking the
            // wrong one would silence this warning exactly when it is owed.
            val warm = sid != null && activeSessions[sid]?.hasLiveCliProcess() == true
            if (warm || rid == null) return@combine false
            val last = ServiceLocator.sessionActivity.lastActivity(serverId, rid)
            last > 0L && System.currentTimeMillis() - last > 60 * 60_000L
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Epoch-ms start of the in-flight turn (the file's `user`-event timestamp),
     * or null when idle. The working-status timer syncs to this so a MIRRORED
     * console turn's elapsed matches the console. */
    val remoteTurnStartMs: StateFlow<Long?> get() = tailPollCoord.remoteTurnStartMs

    /** True start of the APP-DRIVEN in-flight turn from the process-scoped
     *  AgentSession — survives this VM's death, so re-entering a mid-turn chat
     *  shows the real elapsed, not "since I peeked in". 0 when unknown. */
    fun sessionTurnStartMs(): Long =
        _localSessionId.value?.let { activeSessions[it]?.turnStartedAtMs } ?: 0L

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
            // ⚠ WHAT THE SESSION IS ACTUALLY RUNNING BEATS AN OLD PICK.
            // The pick used to win unconditionally, so a stale
            // `selected_reasoning_chat_<id>` displayed "low" over a session the
            // CLI was running at xhigh — and the same value went on to set the
            // thinking budget (user, 2026-08-02). The observation now wins
            // unless the user picked AFTER it, which is the same rule the model
            // chip follows (USER-PICK-BEATS-STALE-OBSERVATION-1).
            val pick = sel?.takeIf { it.isNotBlank() }
            val seen = obs?.takeIf { it.isNotBlank() }
            when {
                pick != null && (seen == null || reasoningPickIsNewer.value) -> pick
                seen != null -> seen
                else -> init?.takeIf { it.isNotBlank() } ?: def?.takeIf { it.isNotBlank() }
            }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }

    /** True while the user's effort pick is NEWER than the last effort the
     *  session reported — see [activeReasoningEffort]. Public for the topbar,
     *  which must apply the SAME rule: every surface that shows the effort has
     *  to agree, or one of them is lying (2026-08-02: the working row was fixed
     *  and the topbar kept printing the stale pick). */
    private val reasoningPickIsNewer: StateFlow<Boolean> get() = modelsCoord.reasoningPickIsNewer
    val reasoningPickIsNewerFlow: StateFlow<Boolean> get() = modelsCoord.reasoningPickIsNewer

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
    /** Non-null when a send was refused because a staged attachment never
     *  uploaded. Drives the one-line banner above the prompt bar.
     *
     *  This exists because the failure used to be invisible: `send()` dropped
     *  every `UploadStatus.Failed` attachment and posted the text anyway, so the
     *  user asked the model about a photo the model never received. The only clue
     *  was an error row in the transcript that named no file and blamed SSH. */
    private val _attachmentNotice = MutableStateFlow<String?>(null)
    val attachmentNotice: StateFlow<String?> = _attachmentNotice.asStateFlow()
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
            val stale = cur != null && cur != "?" && avail != "?" && cur != avail
            if (stale) {
                // REFRESH IT, don't just mention it. This used to only set a
                // notice, so a server kept running whatever script it was given
                // months ago and every verb added since failed as "unknown
                // subcommand" — the agent then tells the user it has no access to
                // something the app plainly supports (user, 2026-07-29: the mic).
                //
                // Safe to do without re-asking: it is OUR script, the write is
                // idempotent, the user already consented to the bridge on this
                // host, and no new capability is granted by the refresh itself —
                // the new verbs are gated by their own phone-side switches, which
                // stay off until the user flips them.
                val upd = ai.eight24family.conch.diagnostics.BridgeInstaller.install(serverId)
                android.util.Log.i(
                    "SshAi-BridgeInstall",
                    "auto-updated bridge on $serverId: v$cur → v$avail ok=${upd.success}",
                )
                _bridgeUpdateNotice.value = if (upd.success) "bridge updated v$cur → v$avail"
                else "bridge is v$cur, this app ships v$avail — update failed"
            } else {
                _bridgeUpdateNotice.value = null
            }
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

    // ──────── (There is ONE queue.) ────────
    // Sends parked while the session bootstraps used to live in a separate,
    // INVISIBLE `_pending` buffer with a 30 s timeout — the user saw only a
    // "N messages waiting to send" counter, could cancel nothing, and the
    // timeout teleported text back into the composer mid-thought. Every
    // undeliverable send now goes through the visible [_outbox] rows below
    // (text + ✕, drained on Running) — one queue, one behavior (2026-08-17).
    /** True while something is queued for this chat — the prompt-bar hint.
     *  `by lazy`: `_outbox` is declared further down this class. */
    val hasPending: StateFlow<Boolean> by lazy {
        _outbox.map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    }

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

    /**
     * THE QUEUE IS THE ONLY PLACE AN UNSENT MESSAGE MAY LIVE, AND IT OUTLIVES
     * THE SCREEN.
     *
     * Everything the user pressed send on but that never reached the CLI used to
     * sit in process memory only: back out of the chat, or let Android kill the
     * app, and the text was gone with no bubble, no queue row, nothing in the
     * transcript — the report (2026-08-04). Mirror the queue into prefs on every
     * change and read it back on open.
     *
     * Safe against doubling BY CONSTRUCTION: a queued message has no chat bubble
     * (the bubble is only ever emitted when a turn actually starts), so a
     * restored row is the message's ONE representation on screen.
     *
     * Keyed like the input draft — the resume id, falling back to the local id.
     * A brand-new chat's local id is minted per open, so its queue is not
     * restorable across a restart; that path is already covered by the
     * (server, agent) draft slot in [HistoryCache.appendDraft].
     */
    @Volatile private var lastQueueKey: String? = null
    @Volatile private var queueRestored = false

    /** Local session id whose first `Running` already kicked the queue — see the
     *  drain site for why this must fire exactly once per slot. */
    @Volatile private var outboxKickedFor: String? = null

    private fun observeOutboxForPersistence() {
        viewModelScope.launch {
            // The key MOVES: a brand-new chat is keyed by its local id until the
            // CLI mints a resume id, and a reconnect mints a fresh local id. So
            // watch the key sources too — writing only on outbox changes would
            // strand the queue under a name nothing reads again (and leave that
            // row in prefs forever).
            combine(_outbox, _resumeId, _localSessionId) { list, _, _ -> list }.collect { list ->
                val key = draftChatId() ?: return@collect
                val prev = lastQueueKey
                lastQueueKey = key
                SilentlyTry.fired("SshAi-Chat", "persist unsent queue") {
                    if (prev != null && prev != key) {
                        ServiceLocator.preferences.setUnsentQueue(prev, emptyList())
                    }
                    ServiceLocator.preferences.setUnsentQueue(
                        key,
                        list.map {
                            ai.eight24family.conch.data.prefs.AppPreferences.UnsentMessage(
                                it.text, it.displayText, it.imagePaths,
                            )
                        },
                    )
                }
            }
        }
    }

    /**
     * Read back messages a previous run parked and never delivered.
     *
     * ⚠ MUST COMPLETE BEFORE [observeOutboxForPersistence] STARTS. The writer
     * mirrors the (empty) in-memory queue the instant the chat gets a key, so
     * starting it first erases the very rows we came to read — a save-file that
     * deletes itself on load. Restore, then arm the writer; the init coroutine
     * runs them in that order and nothing else calls either.
     */
    private suspend fun restoreUnsentQueue() {
        if (queueRestored) return
        queueRestored = true
        val key = draftChatId() ?: return
        val parked = SilentlyTry.nullOnError {
            ServiceLocator.preferences.unsentQueueOnce(key)
        }.orEmpty()
        if (parked.isEmpty()) return
        android.util.Log.i(
            "SshAi-Send",
            "restoring ${parked.size} unsent message(s) parked for chat ${key.take(8)}",
        )
        _outbox.update { cur ->
            val have = cur.mapTo(HashSet()) { it.text }
            cur + parked.filter { it.text !in have }.map {
                QueuedMessage(
                    id = UUID.randomUUID().toString(),
                    text = it.text,
                    displayText = it.displayText,
                    imagePaths = it.imagePaths,
                    thumbs = emptyList(),
                    queuedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    /** Park [text] in the visible queue. The one funnel for "this could not be
     *  handed to the CLI" — nothing may `return` out of [send] leaving the text
     *  only in logcat. */
    private fun parkInOutbox(
        text: String,
        displayText: String,
        imagePaths: List<String> = emptyList(),
        thumbs: List<ByteArray> = emptyList(),
    ) {
        _outbox.update {
            it + QueuedMessage(
                id = UUID.randomUUID().toString(),
                text = text,
                displayText = displayText,
                imagePaths = imagePaths,
                thumbs = thumbs,
                queuedAt = System.currentTimeMillis(),
            )
        }
    }

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

    /** Last stuck-session rescue (epoch ms) — cooldown so a failing rebuild
     *  can't spin. See the rescue watcher in init. */
    @Volatile
    private var lastRescueMs = 0L

    /**
     * Watch connectivity and flush the outbox the moment the link is validated
     * again, so a prompt typed with no internet lands by itself — the user
     * shouldn't have to remember to press send again (user, 2026-07-27).
     */
    private fun observeConnectivityForOutbox() {
        viewModelScope.launch {
            ai.eight24family.conch.util.NetworkCost.online.collect { up ->
                if (!up) return@collect
                if (_outbox.value.isEmpty()) return@collect
                val sid = _localSessionId.value ?: return@collect
                val s = activeSessions[sid] ?: return@collect
                // Only drain into an idle session that is actually UP — a running
                // turn already has drainOutbox wired to its completion (draining
                // twice would send the same message on both paths), and a session
                // that is `Failed` / bootstrapping is not a place a message can
                // go. This guard used to read only "not Working", so `Failed`
                // passed it and the queue was drained into a corpse. Same rule as
                // `shouldReleaseQueue`; drainOutbox refuses a dead scope too.
                if (shouldReleaseQueue(
                        hasQueue = true,
                        working = _stateBySession.value[sid] is SessionState.Working,
                        drainerBusy = s.drainerBusy,
                        mirroredTurnOpen = tailPollCoord.remoteFileOpen.value,
                        sessionReady = _stateBySession.value[sid] is SessionState.Running,
                    )
                ) {
                    android.util.Log.i(
                        "SshAi-Turn",
                        "network back — draining ${_outbox.value.size} queued message(s)",
                    )
                    drainOutbox(s)
                }
            }
        }
    }

    /** Tapping ✕ on a queued row now RETURNS its text to the input box (user
     * 2026-08-11:) instead of discarding it — the same
     * never-lose-the-user's-words path a failed send uses. The row leaves the
     * queue; the text reappears in the composer to edit / resend / drop. */
    fun cancelQueued(id: String) {
        var removed: QueuedMessage? = null
        _outbox.update { lst ->
            removed = lst.firstOrNull { it.id == id }
            lst.filterNot { it.id == id }
        }
        removed?.let { row ->
            // The row may carry a crash-insurance draft — the user just
            // cancelled this text, so it must not resurrect in a composer later.
            SilentlyTry.fired("SshAi-Chat", "remove cancelled row draft") {
                ServiceLocator.historyCache.removeDraft(serverId, _currentAgent.value, row.text)
            }
            row.displayText.takeIf { it.isNotBlank() }?.let {
                viewModelScope.launch { _returnedText.emit(it) }
            }
        }
    }

    /** Turn finished (or the link returned) → send EVERYTHING queued as ONE
     * prompt. Combining the whole outbox into a single turn — instead of one
     * turn per row — is what the user wants: several follow-ups they typed
     * while the agent worked are a single instruction, not N separate turns
     * burning N times the context. Texts joined with a blank line, image paths
     * concatenated in order. ATOMIC take (compareAndSet loop) so a concurrent
     * caller (turn-completion vs the network-back drain) can never send the
     * same batch twice — the first to run claims and empties the queue, the
     * second sees it empty. */
    private fun drainOutbox(s: AgentSession) {
        // ⛔ NEVER CLAIM A MESSAGE A DEAD SESSION CANNOT SEND.
        //
        // This method DESTROYS the only copies of the user's text: it empties
        // `_outbox` (which `observeOutboxForPersistence` then mirrors as an empty
        // list into prefs) and deletes each row's crash-insurance draft, on the
        // strength of `s.send()` being about to deliver it. If the session's
        // scope is cancelled, `send` swallows the prompt in silence — no bubble,
        // no row, no error, and nothing left on disk to recover from. Three
        // copies gone in one breath, unrecoverably (2026-08-18 audit).
        //
        // A session in that state is routine, not exotic: `start()`'s catch
        // closes the session (cancelling its scope) on a missing agent binary or
        // a handshake failure, `openOrGet` hands the object back anyway, and the
        // ViewModel caches it. `Failed` then passes the edge-drains' only guard
        // ("not Working"), and the very next network-validated event drains into
        // the corpse.
        //
        // So the queue KEEPS the rows. They stay visible with their ✕, exactly
        // as the queue promises, and the reconnect ladder or the idle release
        // will hand them to a session that can actually take them.
        if (!s.canAcceptSend()) {
            android.util.Log.w(
                "SshAi-Turn",
                "drain refused: session can't accept a send (dead scope) — " +
                    "${_outbox.value.size} queued message(s) stay in the queue",
            )
            return
        }
        var claimed: List<QueuedMessage> = emptyList()
        _outbox.update { lst ->
            claimed = lst
            emptyList()
        }
        if (claimed.isEmpty()) return
        // Delivered — drop the crash-insurance drafts these rows carried, or
        // the next "+ new session" would offer already-sent text in the composer.
        for (row in claimed) {
            SilentlyTry.fired("SshAi-Chat", "remove drained row draft") {
                ServiceLocator.historyCache.removeDraft(serverId, _currentAgent.value, row.text)
            }
        }
        val combinedText = claimed.joinToString("\n\n") { it.text }
        val combinedImages = claimed.flatMap { it.imagePaths }
        viewModelScope.launch {
            s.send(combinedText, combinedImages)
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
    private val pendingRedelivery = MutableStateFlow<List<String>>(emptyList())


    /** The transport has been down CONTINUOUSLY for [UNREACHABLE_QUIET_MS] —
     *  the only connection state the chat surfaces as text. Reconnects are
     *  silent; the first minutes of a drop are silent (the queue rows and the
     *  server dot already say everything); one quiet line appears only when
     *  the server has been unreachable long enough that the user should know
     *  waiting might not help (server down / IP banned / DC gone). */
    val serverUnreachableLong: StateFlow<Boolean> by lazy {
        kotlinx.coroutines.flow.flow {
            var downSince = 0L
            while (true) {
                val lost = connectionLost.value
                val now = System.currentTimeMillis()
                // A blip resets the clock — the banner NEVER fires on a flapping
                // link, only on a continuous outage (see [unreachableBannerShown]).
                if (!lost) downSince = 0L else if (downSince == 0L) downSince = now
                emit(unreachableBannerShown(lost, downSince, now))
                kotlinx.coroutines.delay(15_000)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    }

    /** Honest transport state for the pinned line: the current session sits in
     * Failed / the reconnect ladder is running AND the pool has NO live
     * transport. The pool check is decisive — the reconnect flag stays true
     * until the SESSION reaches Running, which a mirrored/read-only session
     * never does, so the banner lied «reconnecting attempt 7» over a live dot.
     * `reconnectAttempt` is in the combine so each ladder tick re-reads pool
     * liveness — a one-shot read per emission, not a poll loop. */
    val connectionLost: StateFlow<Boolean> by lazy {
        combine(
            _localSessionId, _stateBySession,
            reconnectCoord.reconnecting, reconnectCoord.reconnectAttempt,
        ) { id, states, rec, _ ->
            val downSignal = rec || (id != null && states[id] is SessionState.Failed)
            downSignal && ServiceLocator.sshConnectionPool.peek(serverId) == null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    }

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
                val sess = activeSessions[sid] ?: return@let
                sess.approvalMode = mode
                // LIVE apply via set_permission_mode — the running process
                // switches modes in place. A refusal (e.g. bypassPermissions on
                // a session launched without the bypass flag) falls back to the
                // launch-params restart on the next turn.
                launch(Dispatchers.IO) {
                    if (sess.applyApprovalLive(mode)) {
                        android.util.Log.i("SshAi-Turn", "approval '$mode' applied live")
                    }
                }
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
     * Turn haptics — new-row ticks and the three-pulse end-of-answer buzz.
     *
     * Lives HERE, not in ChatScreen, because a haptic must not depend on the
     * chat being composed: the screen stops composing in Picture-in-Picture,
     * which is precisely when the user has no way to see the answer land. See
     * [ChatViewModelHaptics].
     */
    private val hapticsCoord by lazy {
        ChatViewModelHaptics(viewModelScope, ServiceLocator.haptics::perform).also {
            it.install(
                messages = messages,
                state = state,
                remoteWorking = remoteFileOpen,
            )
        }
    }
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
            notice = { msg -> _chatNotice.value = msg },
            // ⚠ THE SAME DOOR THE COMPOSER USES. A command dispatched straight
            // into AgentSession.send bypasses the VISIBLE outbox, so one sent
            // during a running turn was queued inside the CLI — invisibly and
            // uncancelably — and simply vanished from the screen. Routing it
            // here gives it the bubble, the queue row and the ✕ every other
            // message has.
            sendAsTurn = { line -> send(line, allowSlash = false) },
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

    /** Catalog entries kept only for label resolution — never offered as picks
     *  (scraper-era leftovers no CLI registry confirmed). */
    val hiddenModels: StateFlow<Set<String>> get() = modelsCoord.hiddenModels
    val unavailableModelLabels: StateFlow<Set<String>> get() = modelsCoord.unavailableModelLabels
    val modelsProbing: StateFlow<Boolean> get() = modelsCoord.modelsProbing
    val modelsStale: StateFlow<Boolean> get() = modelsCoord.modelsStale
    val observationNewerThanPick: StateFlow<Boolean> get() = modelsCoord.observationNewerThanPick
    val defaultModel: StateFlow<String?> get() = modelsCoord.defaultModel
    val defaultReasoning: StateFlow<String?> get() = modelsCoord.defaultReasoning
    val sessionInitialModel: StateFlow<String?> get() = modelsCoord.sessionInitialModel
    val sessionInitialReasoning: StateFlow<String?> get() = modelsCoord.sessionInitialReasoning

    init {
        // Cold-start hydrate: spec model cache (Claude alias map, Codex slug map etc.)
        modelsCoord.hydrateFromCache()
        // Arm turn haptics for the whole life of this chat. Touching the lazy
        // coordinator IS the arming — and it has to happen in an init block that
        // runs AFTER its declaration (Kotlin initialises in source order), which
        // is why this isn't up with the first init.
        hapticsCoord
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
    /** Guards the pooled (no-live-session) catalog probe — see below. */
    private val pooledProbeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun onModelPickerOpened() {
        if (modelsCoord.modelsProbing.value) return
        val s = _localSessionId.value?.let { activeSessions[it] }
        if (s != null) {
            viewModelScope.launch(Dispatchers.IO) {
                modelsCoord.probeAvailableModels(s, force = true)
            }
            return
        }
        // NO live session yet — a fresh chat before its first message. This used
        // to `return` here, so the picker on a new chat NEVER probed: it showed a
        // stale cached list and `claudeUnavailableLabels` stayed empty, meaning a
        // credit-gated model (Fable 5 · Requires usage credits) rendered as a
        // perfectly healthy row the user could pick — and the session would then
        // silently fall back (see MODEL-CREDIT-GATE-1). A fresh chat is exactly
        // when choosing the model still MATTERS, so it must probe too.
        //
        // Ride the pooled, already-authenticated client the same way the startup
        // warm-up does — `probeAndPersist` never initiates a handshake, so an SK
        // server can't be made to demand a FIDO touch from a mere picker tap.
        // Nothing to ride (not connected) → leave the cached list alone.
        val client = ServiceLocator.sshConnectionPool.peek(serverId) ?: return
        // ⚠ IN-FLIGHT GUARD. This probe spawns an interactive `claude` PTY on the
        // SHARED pooled client. modelsProbing (checked above) is only set by the
        // live-session path, so without this flag every re-open of the picker
        // stacked another PTY on the same transport — enough of them exhaust the
        // server's MaxSessions and then NOTHING else can open a channel, which
        // takes the whole chat down with it.
        if (!pooledProbeInFlight.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ai.eight24family.conch.data.ModelCatalogPrefetcher.probeAndPersist(
                    client, _currentAgent.value, serverId,
                )
            } finally {
                pooledProbeInFlight.set(false)
            }
        }
    }


    fun refreshServerStats() = statsCoord.refresh()

    init {
        observeConnectivityForOutbox()
        viewModelScope.launch {
            // ⚠ THIS COROUTINE MUST ALWAYS REACH startNewChat.
            //
            // It used to open with `repo.getById(serverId) ?: return@launch`, so
            // a chat whose server row didn't resolve — deleted server, the
            // cache-only sentinel, a repo read that came back empty — got NO
            // session slot at all. `_localSessionId` stayed null forever, which
            // made every send a silent `return` and left the transcript on
            // "// session unavailable". From the phone that is a chat that never
            // starts and eats what you type (user, 2026-08-04).
            //
            // The chat surface does not need a server: it paints from the cached
            // JSONL by session id. So bind what we CAN — the durable owner
            // sidecar still knows this session's agent even when the server row
            // is gone — and always open. With a slot in place, sends land in the
            // visible queue and the connect chip stays honest about being
            // offline.
            val s = repo.getById(serverId)
            _server.value = s
            val recordedAgent = if (s == null && initialResumeId != null) {
                withContext(Dispatchers.IO) {
                    ServiceLocator.historyCache.owner(initialResumeId)?.agent
                }
            } else null
            val pickedAgent = initialAgent ?: s?.agent ?: recordedAgent ?: Agent.CLAUDE
            if (s == null) {
                android.util.Log.w(
                    "SshAi-Chat",
                    "no server row for $serverId — opening read-only on agent=$pickedAgent " +
                        "(recorded=${recordedAgent != null})",
                )
            }
            _currentAgent.value = pickedAgent
            startNewChat(
                agent = pickedAgent,
                resumeIdParam = initialResumeId,
                resumeFilePath = initialResumePath,
                // Opening the chat route with no resume id IS "give me a new
                // conversation" — from the "+" on the sessions home, or from the
                // agent picker. Never adopt the previous one. (A cold restore
                // after process death also lands here, and there is no in-memory
                // orphan to adopt in that case anyway.)
                adoptExisting = initialResumeId != null,
            )
            // Read the parked queue BEFORE arming the writer — see the KDoc.
            restoreUnsentQueue()
            observeOutboxForPersistence()
            if (s != null) refreshSessions()
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
                // PIN THE MODEL THIS CHAT WAS BORN WITH.
                //
                // A new chat launches on the server's own default (the ✔ row of
                // `/model`). That default is a per-agent GLOBAL that any background
                // probe can rewrite — so with nothing stored for this chat, a later
                // probe changes what this chat resolves to, `ensureProcess` sees
                // "launch params changed" and restarts the CLI on a different model.
                //
                // That restart is not cosmetic: `--resume` makes Claude re-read the
                // WHOLE session file, and Anthropic's prompt cache is keyed per
                // model, so the entire history is re-billed as cache_creation
                // instead of cache_read — roughly ten times the price. On a session
                // whose turns read 871k cached tokens, one silent switch costs more
                // than a day of normal work.
                //
                // Writing the born-with model here makes the chat's model a FACT
                // instead of a re-derivation: from now on `claudePick` answers for
                // it and nothing but an explicit pick can move it.
                SilentlyTry.fired("SshAi-Models", "pin born-with model") {
                    val prefs = ServiceLocator.preferences
                    if (prefs.selectedModelForChat(rid).first().isNullOrBlank()) {
                        // What it was ACTUALLY born on — not only what we asked
                        // for. With no explicit pick we send no `--model` and the
                        // CLI applies its own default, and this pin used to read
                        // `modelOverride`, which is null exactly then: the most
                        // common chat of all — open, type, send — recorded no
                        // model at all, so it had nothing to keep and drifted with
                        // the server's default (user 2026-08-16). The session
                        // reports what it is running; take that.
                        val born = (
                            _localSessionId.value?.let { activeSessions[it]?.modelOverride }
                                ?: modelsCoord.observedModel.value
                            )?.takeIf { it.isNotBlank() }
                        if (born != null) {
                            prefs.setSelectedModelForChat(rid, born)
                            android.util.Log.i(
                                "SshAi-Models",
                                "new chat $rid pinned to its launch model '$born'",
                            )
                        }
                    }
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
                pendingRedelivery.value.let { redeliver ->
                    if (redeliver.isNotEmpty()) {
                        pendingRedelivery.value = emptyList()
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
                // 2. KICK-START the visible queue, exactly once per session slot.
                //    A message parked because the session wasn't up — or one
                //    restored from a previous run — has nothing else to wake it:
                //    [drainOutbox] otherwise only fires on a turn ENDING or the
                //    network returning, so a chat that opens straight into an
                //    idle session would show the row and sit on it forever.
                //
                //    ⚠ THE GATE IS NOT OPTIONAL. `Running` is a data object, so
                //    the flow emits once per transition — and the turn-END
                //    collector drains on that SAME Working→Running edge. Firing
                //    here unguarded meant two claims on one edge: message 2 went
                //    to the CLI while message 1's turn was starting, i.e. straight
                //    into the agent's own invisible queue, which is the exact
                //    thing this outbox exists to prevent. Gate on "the first
                //    Running this slot ever saw" — the bootstrap edge, where no
                //    turn is ending — and let turn-completion own the rest.
                if (outboxKickedFor != sid) {
                    outboxKickedFor = sid
                    if (_outbox.value.isNotEmpty()) drainOutbox(s)
                }
                // (3. There is no separate offline-first-send buffer anymore —
                //    bootstrap-parked sends are ordinary [_outbox] rows and
                //    were drained by (2) above. One queue, 2026-08-17.)
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

        // BACKGROUND-WORKFLOW POLLER. The CLI footer shows a live «name · N/M
        // agents done · elapsed» for an ultracode Workflow; those counts are in
        // the workflow's own journal on the server, not the session rollout —
        // so Conch showed nothing. Fold the transcript for
        // launched-but-unfinished workflows and, while any exist, poll each
        // journal over the pooled SSH (one cheap grep -c) and publish the
        // counts. Idle otherwise — no workflow, no polling.
        viewModelScope.launch {
            // runId -> (mtime when done==total was first seen stable). A run is
            // retired once done==total>0 AND the journal mtime hasn't advanced
            // for WF_SETTLE_MS — ground truth from the journal, not the fragile
            // completion-notification wording.
            val settledSince = HashMap<String, Long>()
            val lastMtime = HashMap<String, Long>()
            while (true) {
                val sid = _localSessionId.value
                val s = sid?.let { activeSessions[it] }
                val msgs = sid?.let { _messagesBySession.value[it] }.orEmpty()
                val runs = if (s == null || msgs.isEmpty()) emptyList()
                    else ai.eight24family.conch.agent.foldWorkflows(msgs)
                if (runs.isEmpty()) {
                    if (_liveWorkflows.value.isNotEmpty()) _liveWorkflows.value = emptyList()
                    settledSince.clear(); lastMtime.clear()
                    kotlinx.coroutines.delay(5_000)
                    continue
                }
                val sessionFile = sessionPathMap[sid] ?: _resumeId.value?.let {
                    SilentlyTry.logged("SshAi-Chat", "owner path for wf poll") {
                        ServiceLocator.historyCache.owner(it)?.path
                    }
                }
                if (sessionFile == null || s == null) { kotlinx.coroutines.delay(5_000); continue }
                val sessionDir = sessionFile.removeSuffix(".jsonl")
                val now = System.currentTimeMillis()
                val fresh = mutableListOf<LiveWorkflow>()
                for (run in runs) {
                    val p = pollWorkflowJournal(s, sessionDir, run.runId) ?: continue
                    val (done, total, mtime) = p
                    // Track journal growth; reset the settle clock whenever it grows.
                    if (mtime != lastMtime[run.runId]) { lastMtime[run.runId] = mtime; settledSince.remove(run.runId) }
                    val complete = total > 0 && done >= total
                    if (complete) settledSince.putIfAbsent(run.runId, now)
                    val retired = complete && (now - (settledSince[run.runId] ?: now) >= WF_SETTLE_MS)
                    if (retired) continue  // drop the row — the run is done and quiet
                    fresh += LiveWorkflow(
                        runId = run.runId,
                        name = run.name,
                        done = done,
                        total = total,
                        elapsedSec = ((now - run.startedAtMs) / 1000L).coerceAtLeast(0),
                        finished = complete,
                    )
                }
                _liveWorkflows.value = fresh
                kotlinx.coroutines.delay(WF_POLL_MS)
            }
        }

        // STUCK-SESSION RESCUE. A session can sit in Failed("disconnected")
        // while the pool transport is ACTUALLY LIVE — the reconnect ladder
        // clears only on a Running transition (a mirrored/read-only session
        // never makes), AND some Failed paths (a background-task process death,
        // a persistent-stream EOF) never even START the ladder, so nothing was
        // watching: the chat showed «failed — disconnected · pull-down to
        // retry» and «1 message waiting to send» over a live connection, with
        // ZERO reconnect activity in the log (user 2026-08-11, confirmed from
        // logcat). Watch BOTH triggers — a ladder tick OR the session entering
        // Failed — and whenever the pool is genuinely live, recover WITHOUT
        // waiting for the ladder: clear the flag, rebuild if there's no live
        // AgentSession, and drain the queue.
        fun rescueIfPoolLive(reason: String) {
            if (ServiceLocator.sshConnectionPool.peek(serverId) == null) return
            // Cooldown so a rebuild that itself fails can't spin: at most one
            // rescue per 15 s (the ladder still runs its own backoff underneath).
            val now = System.currentTimeMillis()
            if (now - lastRescueMs < 15_000L) return
            lastRescueMs = now
            android.util.Log.d("SshAi-Chat", "stuck session but pool is live ($reason) — clearing + draining")
            // Transport-level recovery only: the session has NOT said Running,
            // so the ladder's attempt counter must keep counting. Claiming
            // Running here reset the backoff to attempt=1 every cycle.
            reconnectCoord.onTransportRecovered()
            val sid = _localSessionId.value ?: return
            val s = activeSessions[sid]
            when {
                s == null || s.state.value is SessionState.Failed -> retry()
                _outbox.value.isNotEmpty() -> drainOutbox(s)
            }
        }
        viewModelScope.launch {
            reconnectCoord.reconnectAttempt.collect { attempt ->
                if (attempt > 0) rescueIfPoolLive("ladder tick $attempt")
            }
        }
        // The Failed-without-a-ladder path: watch the current session's state
        // directly. Debounced 3 s so a genuine brief drop still gets the normal
        // silent-reconnect first; if it's STILL Failed and the pool is live,
        // rescue. Re-checks every state change, so a queued send lands as soon
        // as the transport is confirmed up.
        viewModelScope.launch {
            state.collect { st ->
                if (st is SessionState.Failed) {
                    kotlinx.coroutines.delay(3_000)
                    if (state.value is SessionState.Failed) rescueIfPoolLive("session Failed, pool live")
                }
            }
        }
    }

    /**
     * Merge an incoming history snapshot into the display list while NEVER
     * dropping a UserText the user just sent that the snapshot hasn't caught up
     * to. This is the DISPLAY layer's use of the SAME rule the session history
     * runs — [ai.eight24family.conch.agent.mergeUnsyncedUserText] — because the
     * two lists are shown as one chat and a rule that disagrees with itself
     * across them is visible as a duplicate.
     *
     * It was a hand-written copy of that rule, which is how the two drifted and
     * how the phantom outlived a month of fixes. Do not re-inline it.
     *
     * @param isRewoundAway rows the session DELIBERATELY dropped (a rewind).
     *   The never-lose-the-user's-words rule must not fight an explicit undo:
     *   without this the display layer put the rewound prompt straight back, so
     *   the rewind removed 7 rows and the chat still showed them (measured on
     *   device, 2026-08-02).
     */
    private fun preserveUnsyncedUserText(
        current: List<AgentMessage>,
        incoming: List<AgentMessage>,
        isRewoundAway: (String) -> Boolean = { false },
    ): List<AgentMessage> =
        ai.eight24family.conch.agent.mergeUnsyncedUserText(current, incoming, isRewoundAway)

    private fun startNewChat(
        agent: Agent,
        resumeIdParam: String? = null,
        resumeFilePath: String? = null,
        seedMessages: List<AgentMessage>? = null,
        /**
         * True only for a REBUILD of a chat that already exists on screen —
         * `retry()`, the reconnect ladder, the pool-live rescue. Those must
         * re-attach to the very session they are rebuilding, so they keep the
         * in-memory adoption lookups.
         *
         * False when the USER asked for a new conversation ("+ new chat", the
         * chat route opened with no resume id). There, adopting the previous
         * brand-new session is precisely the bug: it re-opens the chat the user
         * just left instead of a fresh one, and if that one was wedged
         * mid-bootstrap the new chat inherits the hang. See
         * [ai.eight24family.conch.agent.AgentSessionManager.closeIfBrandNew].
         */
        adoptExisting: Boolean = true,
    ) {
        // Moving to a genuinely new conversation: the slot we are leaving can
        // never be reached again (nothing resumes a session with no CLI id, and
        // the orphan lookup that used to is exactly what we're refusing), so
        // close it here instead of leaving a `claude --print` and an SSH channel
        // running for nobody. Rebuild paths keep theirs — they re-adopt it.
        if (!adoptExisting) {
            val leaving = _localSessionId.value
            val leavingAgent = leaving?.let { sessionAgentMap[it] } ?: _currentAgent.value
            if (leaving != null) {
                SilentlyTry.fired("SshAi-Chat", "close abandoned brand-new session") {
                    sessionsManager.closeIfBrandNew(serverId, leavingAgent, leaving)
                }
            }
        }
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
                    // A TAIL-FIRST cache (base > 0) is missing its head even when
                    // the local file is under the display window — the marker
                    // must still say so, or a preloaded big session would look
                    // deceptively complete.
                    val headMissing = win.windowed ||
                        ServiceLocator.historyCache.baseOffset(resumeIdParam) > 0L
                    cachedParsed = if (headMissing) listOf(historyWindowMarker()) + parsed else parsed
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
                // The silent connect only upgrades this chat when it succeeds
                // RIGHT NOW. If it gives up (radio dead, SK server without a
                // device key), the watcher below keeps looking for the pool to
                // come up by any path and upgrades then — a live server-side
                // turn must light the spinner without the user re-entering the
                // chat (Workstream A #3).
                armOfflineUpgradeWatcher()
            }
            return
        }
        // A previous offline open of this chat may still have its upgrade
        // watcher running — this open IS the upgrade (or a fresh online open).
        offlineUpgradeJob?.cancel()
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
            // ⚠ Reuse path 2 is REFUSED for a user-initiated new chat, and the
            // orphan it would have adopted is reaped instead — see
            // `closeIfBrandNew` for why adopting it handed the user back the
            // previous chat (and, when that one was wedged, an unkillable hang).
            val existingAlive = when {
                resumeIdParam != null -> sessionsManager.findByResume(serverId, agent, resumeIdParam)
                adoptExisting -> sessionsManager.findOrphanBrandNew(serverId, agent)
                else -> null
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
            // Brand-new chat draft rescue (issue #38) — INTO THE COMPOSER,
            // NEVER onto the wire. This used to restore each parked text as a
            // PendingSend, which the drain coroutine AUTO-SENT the moment the
            // session reached Running. A new session starts EMPTY. The rescued
            // words go to the input box — read, edit, press send yourself — and
            // only once they are OFFERED (composer subscribed) is the store
            // cleared.
            if (existingAlive == null && resumeIdParam == null) {
                val drafts = SilentlyTry.logged("SshAi-Chat", "load drafts on chat start") {
                    ServiceLocator.historyCache.loadDrafts(serverId, agent)
                }.orEmpty()
                if (drafts.isNotEmpty()) {
                    viewModelScope.launch {
                        // Wait for the composer's collector; a chat abandoned
                        // before the UI attaches keeps the file for next time —
                        // clearing on a missed emit would BE the lost message.
                        val subscribed = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                            _returnedText.subscriptionCount.first { it > 0 }
                        } != null
                        if (!subscribed) return@launch
                        _returnedText.emit(drafts.joinToString("\n\n"))
                        android.util.Log.i(
                            "SshAi-Chat",
                            "restored ${drafts.size} undelivered draft(s) into the composer (never auto-sent)",
                        )
                        SilentlyTry.fired("SshAi-Chat", "clear drafts after composer restore") {
                            ServiceLocator.historyCache.clearDrafts(serverId, agent)
                        }
                    }
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
            // A forked chat must carry the flag BEFORE its first launch, or the
            // CLI would simply continue the original session and both chats
            // would write to one file.
            if (openAsFork && resumeIdParam != null) s.forkOnce = true
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
            // AN EXPLICIT PICK IS NEVER DISCARDED. This used to drop the user's
            // choice when its label appeared in `unavail` and silently fall
            // through to the "recommended" model — so picking Opus fifteen times
            // still ran Sonnet, with nothing on screen saying why (user,
            // 2026-07-27). `unavail` is populated from a session banner that
            // lives for the whole chat, so one stale notice permanently vetoed a
            // model the user kept choosing. If a pick really can't run, the CLI
            // says so and the fallback note now surfaces that — which is honest;
            // substituting a different model behind the user's back is not.
            // THE USER'S PICK IS LAW — so read it, do not race it.
            //
            // `selectedModel` is a stateIn whose initial value is null until the
            // first DataStore read lands. This line runs during chat open, so on
            // a cold open `.value` is still null, the chain falls through to a
            // DEFAULT, and a chat with `opus` written in its prefs gets launched
            // on whatever the server's default happens to be — sonnet (user,
            // 2026-07-29, with `selected_model_chat_8ce28eb6…=opus` sitting in
            // DataStore the whole time). Await the stored value.
            val storedPick = _resumeId.value?.let { rid ->
                SilentlyTry.logged("SshAi-Models", "read stored model pick") {
                    ServiceLocator.preferences.selectedModelForChat(rid).first()
                }
            }?.takeIf { it.isNotBlank() }
            val claudePick = (selectedModel.value ?: storedPick)?.takeIf { it.isNotBlank() }
            if (claudePick != null && (modelsCoord.availableModels.value[claudePick] ?: claudePick) in unavail) {
                android.util.Log.i(
                    "SshAi-Models",
                    "explicit pick '$claudePick' is flagged unavailable — honouring it anyway",
                )
            }
            // ⚠ WE DO NOT DICTATE THE MODEL OF AN EXISTING CONVERSATION.
            //
            // Every flip the user suffered was OURS: we passed `--model
            // <something>` on every launch, and whenever that something came from
            // a default/global/stale source the chat was forced onto another model
            // — which busts the per-model prompt cache, so the WHOLE conversation
            // is re-read and re-billed.
            //
            // So: for a chat that already exists, send `--model` ONLY when the
            // user picked one IN THIS CHAT. No pick ⇒ send nothing ⇒ the session
            // keeps whatever it was on. This deletes the entire class of leak —
            // no global, no probe, no catalog order can reach an existing chat.
            // A BRAND-NEW chat may still carry an explicit pick; with none, the
            // CLI applies its own default and we pin whatever it actually
            // started on (see "pin born-with model").
            // ⚠ AND THE PIN YIELDS TO THE SESSION ITSELF. A new chat is pinned to
            // the model it started on ("pin born-with model"), which is how a
            // chat gets a pick the user never made. If they then switch the
            // model INSIDE the CLI (`/model` in that very chat), our pin would
            // drag it back on the next send — the same forced flip, just with a
            // friendlier-looking source. So the pick only wins while it is
            // NEWER than what the session last reported; otherwise we send
            // nothing and the session keeps what it is on.
            val isExistingChat = resumeIdParam != null
            val claudeModels = modelsCoord.availableModels.value
            val pickBeatsSession = !modelsCoord.observationNewerThanPick.value
            // A SESSION KEEPS ITS OWN MODEL, whatever the server's default is
            // today. Sending nothing on resume delegates that to the CLI, which
            // is only safe while the default never moves: change the default on
            // the server and every old chat quietly continues on the new model —
            // a different price per turn, on a conversation the user never
            // agreed to move (user 2026-08-16). Warning about it would be asking
            // the user a question the app can answer itself, so instead we name
            // the session's OWN model explicitly.
            //
            // This is NOT the flip that used to cost limits: THAT was a global
            // pick leaking into an existing chat and switching it (Fable 5),
            // which forces the CLI to re-read the whole conversation. Naming the
            // model the session is already on is a no-op — same model, no
            // re-read — it just stops the answer depending on a server default.
            //
            // Order: an explicit pick that is newer than the session wins (the
            // user just chose); otherwise the session's own last observed model;
            // and ONLY if that model still exists — a withdrawn model (Fable 5's
            // suspension) hard-fails every send, so there we send nothing and
            // let the CLI do the provider's own fallback.
            val sessionOwnModel = modelsCoord.observedModel.value
                ?.takeIf { it.isNotBlank() && (claudeModels.isEmpty() || claudeModels.containsKey(it)) }
            s.modelOverride = if (isClaude) {
                claudePick?.takeIf { it.isNotBlank() && (!isExistingChat || pickBeatsSession) }
                    ?: sessionOwnModel?.takeIf { isExistingChat }
            } else {
                (selectedModel.value ?: modelsCoord.currentSessionInitialModel())
                    ?.takeIf { it.isNotBlank() }
            }
            if (isClaude) {
                android.util.Log.i(
                    "SshAi-Models",
                    "launch model resolve: pick=$claudePick existingChat=$isExistingChat " +
                        "pickBeatsSession=$pickBeatsSession " +
                        "catalog=${claudeModels.keys} → ${s.modelOverride ?: "<none — session keeps its own>"}",
                )
            }
            // ⚠ AND WE DO NOT DICTATE THE EFFORT EITHER — same evidence, same
            // law. Forcing MAX_THINKING_TOKENS onto a session that already has
            // its own /effort setting is how an xhigh conversation came to run
            // on the LOW budget (2026-08-02). A pick made in THIS chat is
            // honoured; otherwise we pass nothing and the CLI keeps its own.
            val effortPick = selectedReasoning.value?.takeIf { it.isNotBlank() }
            val observedEffort = modelsCoord.observedReasoning.value?.takeIf { it.isNotBlank() }
            s.reasoningEffortOverride = when {
                !isClaude -> (effortPick ?: modelsCoord.currentSessionInitialReasoning())
                    ?.takeIf { it.isNotBlank() }
                effortPick != null && (observedEffort == null || modelsCoord.reasoningPickIsNewer.value) -> effortPick
                else -> null
            }
            if (isClaude) {
                android.util.Log.i(
                    "SshAi-Models",
                    "launch effort resolve: pick=$effortPick observed=$observedEffort " +
                        "pickNewer=${modelsCoord.reasoningPickIsNewer.value} → " +
                        "${s.reasoningEffortOverride ?: "<none — session keeps its own>"}",
                )
            }
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
                // A live turn may have written to the server file since this
                // cache was saved — the current turn's PROMPT is then only on
                // the server, and the stale seed shows the reply streaming in
                // (via the live stream) with no prompt above it until the
                // tail-poll's catch-up lands a beat later. Own launch so it
                // never delays the collector/stream wiring below; stat-gated so
                // a current cache pays only one tiny stat; the compare is in
                // REMOTE coordinates (base + local len) so a tail-first cache
                // (Workstream C, base > 0) is not mistaken for "stale" on every
                // open. Skipped on a reconnect carry (seedMessages) — that
                // already holds the freshest display.
                if (resumeFilePath != null && resumeIdParam != null && seedMessages.isNullOrEmpty()) {
                    val base = ServiceLocator.historyCache.baseOffset(resumeIdParam)
                    val cachedLen = cachedBytesLen
                    viewModelScope.launch(Dispatchers.IO) {
                        val live = ServiceLocator.sshConnectionPool.peek(serverId) ?: return@launch
                        val serverSize = execPooledText(live, remoteSizeScript(resumeFilePath))
                            ?.trim()?.toLongOrNull()
                        if (serverAheadOfCache(serverSize, base, cachedLen)) {
                            android.util.Log.d(
                                "SshAi-Chat",
                                "cache stale on open (server=$serverSize base=$base cached=$cachedLen) — " +
                                    "refreshing display tail so the in-flight prompt shows with the answer",
                            )
                            paintTailFromServer(live, resumeFilePath, s, agent, localId)
                        }
                    }
                }
            } else if (resumeFilePath != null && resumeIdParam != null) {
                // The display only ever needs the last DISPLAY_TAIL_BYTES (the
                // full body is still fetched right after, for search + tail-poll
                // offsets). Best-effort: if the pool isn't live or the tail fetch
                // misses, we fall straight through to the unchanged full-fetch
                // below.
                ServiceLocator.sshConnectionPool.peek(serverId)?.let {
                    paintTailFromServer(it, resumeFilePath, s, agent, localId)
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
                                        val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
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
                    s.loopArmed.collect { armed ->
                        _loopBySession.update { it + (localId to armed) }
                    }
                }
                launch {
                    // A /loop that scheduled nothing looks exactly like a
                    // healthy one in the transcript. Say it plainly, once.
                    s.loopNotArmed.collect { at ->
                        if (at > 0L) _chatNotice.value =
                            "The loop wasn't armed — nothing was scheduled, so there is no next run. " +
                            "Send /loop again, or say the interval you want."
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
                launch {
                    // The persistent channel's initialize handshake carries the
                    // CLI's OWN model catalog / default / effort ladder — adopt
                    // it the moment it lands (and again on every process
                    // relaunch). For a live chat this replaces the catalog
                    // probe entirely: same data, zero extra round-trips.
                    s.claudeInitState.collect { st ->
                        if (st != null) {
                            modelsCoord.adoptClaudeInit(st, serverId)
                            // The same handshake carries the CLI's OWN commands
                            // and skills — 45 of them, previously invisible from
                            // the phone. Ours still win on a name collision.
                            slashCoord.setAgentCommands(
                                st.commands.map { c ->
                                    ai.eight24family.conch.agent.SlashCommand(
                                        name = c.name,
                                        description = c.description,
                                        kind = ai.eight24family.conch.agent.SlashCommandKind.AGENT_BUILTIN,
                                        acceptsArgs = c.argumentHint.isNotBlank(),
                                    )
                                }
                            )
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
                            m + (localId to preserveUnsyncedUserText(
                                m[localId].orEmpty(), list,
                                isRewoundAway = { t -> s.isSuppressedByRewind(t) },
                            ))
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
                            // ⚠ ONLY A CHAT THAT HAS NO ROW YET MAY MINT ONE.
                            // This upsert exists so a BRAND-NEW chat appears in
                            // the list at once instead of waiting for a sweep. A
                            // RESUMED chat already has its row — and the id the
                            // CLI announces on a resume is NOT necessarily a file:
                            // it reported 648b294a while still writing
                            // 00e74cc1.jsonl, and that phantom id, upserted here
                            // with a plausible size and a fresh timestamp, is the
                            // duplicate row the user kept deleting and kept
                            // getting back (2026-08-03). A listing can never clear
                            // it either — the server has no such file to report.
                            viewModelScope.launch(Dispatchers.IO) {
                                // ⚠ THE OWNER IS RECORDED FOR EVERY CHAT, ALWAYS.
                                // It is what ties a session id to its server and
                                // agent; without it a reopened chat has nowhere to
                                // load from and shows "session unavailable". The
                                // phantom-row guard below belongs to the LIST row
                                // only — putting it around this too emptied a live
                                // conversation on screen (2026-08-04).
                                ServiceLocator.historyCache.recordOwner(
                                    rid, serverId, agent,
                                    sessionPathMap[localId], nowSec,
                                )
                                // Only a chat that started WITHOUT a session may
                                // mint a list row: on a resume the CLI announces a
                                // fresh id per launch that is not a file.
                                if (initialResumeId == null) {
                                    ServiceLocator.sessionsCache.upsert(serverId, agent, row)
                                }
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
                            // Durable twin (bytes of the mirrored body) — this
                            // is what survives an app restart and clears the
                            // home badge + done-✓ when the user views the chat.
                            SilentlyTry.fired("SshAi-Chat", "stamp seen watermark") {
                                ServiceLocator.historyCache.markSeenBytes(
                                    rid, ServiceLocator.historyCache.size(rid),
                                )
                            }
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
                            // …and the message the user parked behind a compact,
                            // so choosing the cheap path actually sends it.
                            releaseAfterCompact()
                        }
                        prev = st
                    }
                }
            }

            // Tail-poll the remote JSONL: catch up since the snapshot, then
            // listen for external growth (e.g. the user typed on their PC).
            if (resumeIdParam != null && resumeFilePath != null) {
                // The poller speaks REMOTE offsets; a tail-first cache's local
                // length maps to remote only after adding its base origin
                // (0 for complete mirrors, so this is byte-identical for them).
                val initialOffset = ServiceLocator.historyCache.baseOffset(resumeIdParam) + cachedBytesLen
                pollerJobs[localId] = viewModelScope.launch(Dispatchers.IO) {
                    tailPollCoord.tailPoll(s, agent, resumeIdParam, resumeFilePath, initialOffset)
                }
            } else {
                // LATE POLLER ARM — a chat STARTED on the phone has no resume
                // id/path yet, and this branch used to simply not exist: such a
                // chat lived on the live stream ALONE. The moment the stream
                // wedged (Wi-Fi blink, half-open socket) there was no mirror at
                // all — the server kept working, the file kept growing, and the
                // open chat froze until the user exited and re-entered. Arm the
                // SAME poller as soon as the CLI mints the id and a listing
                // records the file's path (durable owner sidecar — populated by
                // refreshSessions after the first send and the 30 s re-list).
                viewModelScope.launch(Dispatchers.IO) {
                    // Both wait-loops exit via delay()'s own cancellation when
                    // the VM dies — no explicit isActive plumbing needed.
                    var rid: String? = null
                    while (rid == null) {
                        if (_localSessionId.value != localId) return@launch  // chat moved on
                        rid = _resumeId.value
                        if (rid == null) kotlinx.coroutines.delay(1_000)
                    }
                    val sid = rid
                    var path: String? = sessionPathMap[localId]
                    var tries = 0
                    while (path == null && tries < 60) {
                        if (_localSessionId.value != localId) return@launch
                        path = SilentlyTry.logged("SshAi-Chat", "resolve owner path for late poller") {
                            ServiceLocator.historyCache.owner(sid)?.path
                        }
                        if (path == null) { tries++; kotlinx.coroutines.delay(5_000) }
                    }
                    val p = path ?: run {
                        android.util.Log.w("SshAi-Chat", "late poller: no path for ${sid.take(8)} after ${tries}×5s — mirror stays stream-only")
                        return@launch
                    }
                    sessionPathMap[localId] = p
                    if (pollerJobs[localId] == null && _localSessionId.value == localId) {
                        android.util.Log.d("SshAi-Chat", "late poller armed for ${sid.take(8)} at $p")
                        pollerJobs[localId] = viewModelScope.launch(Dispatchers.IO) {
                            tailPollCoord.tailPoll(
                                s, agent, sid, p,
                                // Base is 0 for phone-born sessions today, but the
                                // offset contract is base + localLen everywhere.
                                ServiceLocator.historyCache.baseOffset(sid) +
                                    ServiceLocator.historyCache.size(sid),
                            )
                        }
                    }
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
                                val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
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

    /**
     * @param allowSlash false when the text IS the dispatch of a slash command
     *   (`/compact`, a skill, a user's own command file). Those are sent as a
     *   turn — the way the CLI runs them — and must not be re-intercepted here,
     *   which would dispatch them a second time.
     */
    fun send(text: String, allowSlash: Boolean = true) {
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
            // The chat has no session slot yet — its server row hadn't loaded, or
            // the whole init coroutine bailed because the server wasn't
            // resolvable. This used to be a bare `return` with a logcat line, so
            // the user's message was gone the instant they pressed send and
            // NOTHING on screen said so. Park it in the visible queue instead;
            // the drain fires as soon as a session reaches Running, and the row
            // carries a ✕ if they'd rather take it back.
            android.util.Log.w("SshAi-Send", "no session slot yet — parking the message in the queue")
            val t = text.trim()
            if (t.isNotEmpty()) parkInOutbox(t, t)
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
                pendingRedelivery.update { it + t }
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
        // The banner always says what to do next ("your next message starts a
        // fresh CLI", "the loop wasn't armed — send it again"). The moment you
        // send, it is describing the past, so it goes on its own instead of
        // waiting to be dismissed.
        _chatNotice.value = null
        val staged = attachmentsCoord.snapshot()
        val trimmed = text.trim()
        // Slash commands hijack the send path — never go to the model.
        if (allowSlash && staged.isEmpty() && trimmed.startsWith("/") && runSlash(trimmed)) return
        // Block while any are still uploading. Only ready paths are appended.
        if (staged.any { it.status is UploadStatus.Uploading }) return
        val ready = staged.mapNotNull { att ->
            val st = att.status
            if (st is UploadStatus.Ready) att to st.remotePath else null
        }
        // A failed upload used to be dropped SILENTLY here: the text went to the
        // model, the attachment did not, and nothing on screen said so — the only
        // hint was an error row in the transcript that named no file and lied
        // about the cause. Refuse the send instead, and leave the failed chips
        // where they are so the user can see which file and remove or re-attach
        // it. Sending a question about a photo that never arrived wastes a whole
        // turn and reads as the model ignoring him.
        val failed = staged.filter { it.status is UploadStatus.Failed }
        if (failed.isNotEmpty()) {
            val why = (failed.first().status as UploadStatus.Failed).reason
            _attachmentNotice.value = if (failed.size == 1) {
                "${failed.first().displayName} didn't upload ($why) — remove it or try again"
            } else {
                "${failed.size} attachments didn't upload ($why) — remove them or try again"
            }
            return
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

        // NO INTERNET → park it, don't lose it. Handing this to the SSH layer
        // offline just fails somewhere deep and the text is gone; instead say so
        // plainly in the chat and hold the message in the SAME visible outbox the
        // busy-turn path uses (rendered above the prompt bar, each row cancelable).
        // [onNetworkBack] drains it as soon as the link is validated again.
        if (!ai.eight24family.conch.util.NetworkCost.isOnline()) {
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
            // No error row. The queued message is a VISIBLE row above the
            // prompt bar with its text and a ✕ — that IS the notice.
            return
        }

        // A turn is already running (or the drainer is winding one down) → the
        // VISIBLE queue, like every other undeliverable send. The 2026-08-11
        // design handed it straight into the running session's own queue with an
        // instant bubble — the words were never lost, but the bubble welded
        // itself into the MIDDLE of the running turn's transcript and the message
        // became uncancellable the moment it was typed. A queue row keeps it
        // visible AND yours until the turn actually ends: text, ✕, editable by
        // cancel-and-retype.
        //
        // ⚠ ALSO the MIRRORED turn. Reopen the app into a session that is running
        // SERVER-SIDE and our state machine has not caught up — curState is
        // Running/Idle, NOT Working — but `remoteFileOpen` (the file mirror's "a
        // turn is in flight" signal) is true. Without this conjunct the send
        // skipped the queue and hit the hot `s.send()` below, which on a
        // persistent `--resume` stream INTERRUPTS the running server turn and
        // starts answering the new prompt from a COLD cache — a full history
        // re-send and re-bill. A running turn is a running turn whether WE
        // started it or only mirror it, and a mid-turn send is a visible queue
        // row either way. It drains on the mirrored turn-end edge (remoteFileOpen
        // true→false).
        val mirroredTurnOpen = tailPollCoord.remoteFileOpen.value
        if (shouldQueueSend(
                working = curState is SessionState.Working,
                runningWithBusyDrainer = curState is SessionState.Running && s.drainerBusy,
                mirroredTurnOpen = mirroredTurnOpen,
        )) {
            _outbox.update {
                it + QueuedMessage(
                    id = UUID.randomUUID().toString(),
                    text = finalText,
                    displayText = trimmed.ifEmpty { finalText },
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
            // Session is bootstrapping (or briefly reconnecting). The message
            // goes into the SAME visible queue every other undeliverable send
            // uses — a row above the prompt bar with its text and a ✕ — never
            // an invisible buffer. The old design parked it in `_pending`:
            // nothing on screen but a "N messages waiting to send" counter
            // with no way to see or cancel WHAT was waiting, plus a 30 s
            // timeout that yanked the text back into the composer
            // mid-thought. The init{} watcher drains the queue the moment the
            // session reaches Running; until then the row is visible and ✕
            // returns its text to the composer. No timeout — a visible row
            // waiting is honest.
            _outbox.update {
                it + QueuedMessage(
                    id = UUID.randomUUID().toString(),
                    text = finalText,
                    displayText = trimmed.ifEmpty { finalText },
                    imagePaths = imagePaths,
                    thumbs = ready.filter { r -> r.first.isImage }.map { r -> r.first.bytes },
                    queuedAt = System.currentTimeMillis(),
                )
            }
            // Crash insurance for a brand-new chat (issue #38): persist the
            // text so popping the chat mid-bootstrap doesn't lose it. It comes
            // back in the COMPOSER of the next "+ new session" — never
            // auto-sent (2026-08-17). Drained/cancelled rows clean it up.
            if (_resumeId.value == null) {
                SilentlyTry.fired("SshAi-Chat", "append draft on queued send") {
                    ServiceLocator.historyCache.appendDraft(
                        serverId, _currentAgent.value, finalText
                    )
                }
            }
        }
    }

    fun addAttachment(bytes: ByteArray, displayName: String, mimeType: String?) =
        attachmentsCoord.addAttachment(bytes, displayName, mimeType)

    /** Large file already staged to a temp file — streamed up, no in-RAM copy. */
    fun addFileAttachment(file: java.io.File, displayName: String, mimeType: String?, sizeBytes: Long) =
        attachmentsCoord.addFileAttachment(file, displayName, mimeType, sizeBytes)

    fun dismissAttachmentNotice() { _attachmentNotice.value = null }

    fun removeAttachment(id: String) {
        attachmentsCoord.removeAttachment(id)
        // The banner names a specific file; once it is gone the banner is stale.
        _attachmentNotice.value = null
    }

    fun clearAttachments() = attachmentsCoord.clearAttachments()

    fun setModel(model: String?) {
        modelsCoord.setModel(model) { trimmed ->
            _localSessionId.value?.let { sid ->
                val sess = activeSessions[sid] ?: return@let
                sess.modelOverride = trimmed
                // LIVE apply over the control channel (set_model) — the running
                // process swaps its model in place, no restart, no session
                // re-read. On failure the override above still lands via the
                // launch-params restart on the next turn (the old path).
                viewModelScope.launch(Dispatchers.IO) {
                    if (sess.applyModelLive(trimmed)) {
                        android.util.Log.i("SshAi-Models", "model '$trimmed' applied live via set_model")
                    }
                }
            }
        }
    }

    /** A model switch waiting for the user to accept the cache cost. */
    data class PendingModelSwitch(
        val model: String?,
        val label: String,
        /** Anthropic's dialog serves both; only the noun and the applied value
         *  differ. `effort` non-null means this is an effort change. */
        val effort: String? = null,
        val isEffort: Boolean = false,
    )

    private val _pendingModelSwitch = MutableStateFlow<PendingModelSwitch?>(null)
    val pendingModelSwitch: StateFlow<PendingModelSwitch?> = _pendingModelSwitch.asStateFlow()

    /** Output tokens when the user last accepted a switch — Anthropic's
     *  `cacheMissAckedAtOutputTokens`. See [ModelSwitchWarning]. */
    @Volatile private var cacheMissAckedAtOutputTokens: Long? = null

    /**
     * The picker calls THIS, not [setModel]. Switching model invalidates the
     * per-model prompt cache, so the whole history is re-read and re-billed on
     * the next message; the warning makes that cost visible BEFORE it is paid,
     * and fires exactly when Anthropic's own CLI shows it.
     */
    fun requestSetModel(model: String?, label: String) {
        val warn = ModelSwitchWarning.shouldWarn(
            next = model,
            current = modelsCoord.selectedModel.value
                ?: _localSessionId.value?.let { activeSessions[it]?.modelOverride },
            hasMessages = messages.value.any {
                it is AgentMessage.UserText || it is AgentMessage.AssistantText
            },
            outputTokens = costStats.value.outputTokens,
            ackedAtTokens = cacheMissAckedAtOutputTokens,
            resolve = { slug -> modelsCoord.availableModels.value[slug] ?: slug },
        )
        if (warn) _pendingModelSwitch.value = PendingModelSwitch(model, label)
        else setModel(model)
    }

    fun confirmModelSwitch() {
        val pending = _pendingModelSwitch.value ?: return
        cacheMissAckedAtOutputTokens = costStats.value.outputTokens
        _pendingModelSwitch.value = null
        if (pending.effort != null) setReasoning(pending.effort)
        if (!pending.isEffort) setModel(pending.model)
    }

    /**
     * Effort changes bust the cache exactly like model changes — Anthropic's own
     * dialog is the SAME component with "effort level" swapped in for "model",
     * and the gate this reuses is literally the one their effort slider calls.
     * Budget-mapped levels now apply LIVE over the control channel
     * (set_max_thinking_tokens); the cache-bust economics are the same either
     * way, so the warning stays.
     */
    fun requestSetReasoning(effort: String?, label: String) {
        val warn = ModelSwitchWarning.shouldWarn(
            next = effort,
            current = modelsCoord.selectedReasoning.value,
            hasMessages = messages.value.any {
                it is AgentMessage.UserText || it is AgentMessage.AssistantText
            },
            outputTokens = costStats.value.outputTokens,
            ackedAtTokens = cacheMissAckedAtOutputTokens,
        )
        if (warn) {
            _pendingModelSwitch.value =
                PendingModelSwitch(model = null, label = label, effort = effort, isEffort = true)
        } else setReasoning(effort)
    }

    fun cancelModelSwitch() { _pendingModelSwitch.value = null }

    /** Pin a reasoning-effort level (Codex's `low|medium|high|xhigh`, Claude's
     *  `low|medium|high|max`) to this chat. `null` clears the pin. Same isolation
     *  rules as [setModel]. */
    fun setReasoning(effort: String?) {
        modelsCoord.setReasoning(effort) { trimmed ->
            _localSessionId.value?.let { sid ->
                val sess = activeSessions[sid] ?: return@let
                sess.reasoningEffortOverride = trimmed
                // LIVE apply (set_max_thinking_tokens) for budget-mapped levels;
                // xhigh/ultracode still take the restart path on the next turn.
                viewModelScope.launch(Dispatchers.IO) {
                    if (sess.applyReasoningLive(trimmed)) {
                        android.util.Log.i("SshAi-Models", "effort '$trimmed' applied live")
                    }
                }
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
        // ⚠ "fresh" excludes CLI-cache reports: they carry fetchedAtEpochMs and
        // may be up to an hour old — a 40-min-old 82% must not un-declare a
        // limit the CLI hit 10 minutes ago. Live/curl reports (fetchedAt null)
        // are fresh by construction and keep clearing it.
        val probeSaysClear = primary != null && primary.percent < 100 &&
            report?.fetchedAtEpochMs == null
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
            // Claude with a LIVE control channel: `get_usage` over the running
            // process — the CLI's own numbers, free (no extra ssh channel, no
            // curl, no token handling). Rides the same channel the turns use.
            if (agent == Agent.CLAUDE) {
                val sess = _localSessionId.value?.let { activeSessions[it] }
                val payload = sess?.fetchUsageLive()
                if (payload != null) {
                    UsageProbe.reportFromControlPayload(payload.toString())?.let { rep ->
                        // Merge BEFORE displaying — remember() merges for the
                        // cache, but the displayed value must match it or the
                        // Fable row flaps for one refresh cycle.
                        val merged = UsageProbe.withPerModelCarryOver(serverId, agent, rep)
                        _usage.value = merged
                        UsageProbe.remember(serverId, agent, merged)
                        return@launch
                    }
                }
                // NO live process (idle chat — get_usage nulls on!procAlive):
                // the CLI's own persisted usage state in ~/.claude.json is the
                // same truth, mtime-gated so an unchanged file costs ~60 B.
                // This tier is what keeps an idle chat's bar honest.
                UsageProbe.fetchClaudeCliCache(serverId)?.let { rep ->
                    val merged = UsageProbe.withPerModelCarryOver(serverId, agent, rep)
                    _usage.value = merged
                    UsageProbe.remember(serverId, agent, merged)
                    return@launch
                }
            }
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
        // Instant paint from the cache; the live read below still runs so the
        // numbers refresh (the cached copy goes stale with every turn).
        UsageProbe.cachedContext(rid)?.let { _contextBreakdown.value = it }
        if (_contextLoading.value) return
        _contextLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            // LIVE channel first: `get_context_usage` asks the RUNNING process —
            // instant and exact, no second CLI spawn, no session copy. Falls
            // back to the legacy copy-probe for mirrored / one-shot sessions.
            val live = _localSessionId.value?.let { activeSessions[it] }
                ?.fetchContextUsageLive()
                ?.let { UsageProbe.contextFromControlPayload(it) }
                ?.takeIf { it.isNotEmpty() }
            if (live != null) {
                UsageProbe.rememberContext(rid, live)
                _contextBreakdown.value = live
                _contextLoading.value = false
                return@launch
            }
            // No live channel. The slow copy-probe only runs when NOTHING is on
            // screen — a cached breakdown beats a 15-30s CLI spawn.
            if (_contextBreakdown.value == null) {
                _contextBreakdown.value = UsageProbe.fetchContextBreakdown(serverId, rid)
            }
            _contextLoading.value = false
        }
    }

    // ── @-mention file suggestions (Claude control channel) ────────────────
    // Server-side fuzzy search over the CLI's own file index. Debounced; only
    // meaningful while the persistent channel is up — otherwise stays empty
    // and the prompt bar simply shows nothing.
    private val _fileSuggestions = MutableStateFlow<List<String>>(emptyList())
    val fileSuggestions: StateFlow<List<String>> = _fileSuggestions.asStateFlow()
    private var mentionJob: Job? = null

    /** Called by the prompt bar whenever the trailing @-token changes; null =
     *  no mention being typed → clears the strip. */
    fun updateMentionQuery(query: String?) {
        mentionJob?.cancel()
        if (query == null || _currentAgent.value != Agent.CLAUDE) {
            _fileSuggestions.value = emptyList()
            return
        }
        mentionJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(180) // debounce typing
            val sess = _localSessionId.value?.let { activeSessions[it] }
            val res = sess?.fileSuggestions(query)
            // A dead/refused channel yields null — keep the strip EMPTY, never
            // stale results for a different query.
            _fileSuggestions.value = res.orEmpty().take(8)
        }
    }

    // ── Session rename (rename_session over the control channel) ──────────
    /** Optimistic title override so the topbar/list reflect the new name
     *  immediately (the server-side listing catches up on its next sweep). */
    private val _renamedTitle = MutableStateFlow<String?>(null)
    val renamedTitle: StateFlow<String?> = _renamedTitle.asStateFlow()

    /** Rename the current session on the server. Claude-only (the CLI
     *  persists it as a custom-title transcript record; shows in
     *  `claude --resume` and our sessions list). */
    fun renameSession(title: String) {
        val trimmed = title.trim().take(140)
        if (trimmed.isBlank() || _currentAgent.value != Agent.CLAUDE) return
        viewModelScope.launch(Dispatchers.IO) {
            val sess = _localSessionId.value?.let { activeSessions[it] } ?: return@launch
            if (sess.renameSession(trimmed)) {
                _renamedTitle.value = trimmed
                android.util.Log.i("SshAi-Turn", "session renamed to '$trimmed'")
            } else {
                android.util.Log.w("SshAi-Turn", "rename_session failed (channel down or refused)")
            }
        }
    }

    /** One-line outcome notice for chat-level actions (rewind succeeded /
     *  failed). Deliberately a BANNER, not a chat row: it is app feedback
     *  about the user's own action, not part of the transcript, so it must
     *  not be replayed on every reopen. */
    private val _chatNotice = MutableStateFlow<String?>(null)
    val chatNotice: StateFlow<String?> = _chatNotice.asStateFlow()
    fun dismissChatNotice() { _chatNotice.value = null }

    // ── Compact ───────────────────────────────────────────────────────────
    /** Pending confirmation for a manual compaction: the context numbers as
     *  they stand, so the cost is on screen before the user agrees. */
    data class PendingCompact(val tokens: Long, val max: Long, val percent: Int)

    private val _pendingCompact = MutableStateFlow<PendingCompact?>(null)
    val pendingCompact: StateFlow<PendingCompact?> = _pendingCompact.asStateFlow()

    /** Chat menu → "compact conversation". Opens the confirmation; nothing is
     *  sent until [confirmCompact]. */
    fun requestCompact() {
        val cs = costStats.value
        val max = cs.contextMax.takeIf { it > 0L } ?: 200_000L
        _pendingCompact.value = PendingCompact(
            tokens = cs.contextTokens,
            max = max,
            percent = ((cs.contextTokens.toDouble() / max) * 100).toInt().coerceIn(0, 100),
        )
    }

    fun cancelCompact() { _pendingCompact.value = null }

    /** A message the user chose to send AFTER compacting. Released by the turn
     *  that follows the compact, so the cheap path is actually taken. */
    internal fun releaseAfterCompact() {
        val held = pendingAfterCompact ?: return
        pendingAfterCompact = null
        send(held)
    }

    /**
     * A send that is about to cost far more than it looks like it should.
     *
     * Shown BEFORE the message goes, because afterwards there is nothing to
     * decide — the limits are already spent. [text] is held here and sent only
     * if the user says so.
     */
    data class CostWarning(val text: String, val kind: Kind, val percent: Int) {
        enum class Kind { COLD_CACHE, RUNNING_ELSEWHERE }
    }

    /**
     * Is this conversation being driven by something that is NOT us?
     *
     * ⚠ ONE SOURCE FOR BOTH THE HINT AND THE DIALOG. The mirror only sees a
     * file growing; it cannot see whose writes those are. Our own turn writes
     * the same file, so "the file is busy" alone accused the user of running a
     * second agent while the app itself was mid-turn — on a session that had
     * never been touched from the server (2026-08-04). It requires all three:
     * the chat was opened from an EXISTING session, we hold no live session of
     * our own, and the file is being written anyway.
     */
    val runningElsewhere: StateFlow<Boolean> =
        combine(_localSessionId, remoteFileOpen, _stateBySession) { sid, remote, states ->
            if (initialResumeId == null || !remote) return@combine false
            val ours = sid != null &&
                (activeSessions[sid]?.isAlive() == true || states[sid] is SessionState.Working)
            !ours
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Would this send cost far more than it looks like it should?
     *
     * ⚠ A CHECK, NOT A GATE. The first version held the text inside the VM
     * until the user answered, so when the dialog did not appear the message
     * simply vanished (2026-08-04). The composer keeps its text now; this only
     * answers the question.
     *
     * ⚠ AND "SOMEWHERE ELSE" MEANS SOMEWHERE ELSE. The mirror cannot tell whose
     * writes it sees, so a file growing under OUR OWN live session counted as
     * another agent — and the warning appeared on a session created on the
     * phone that had never run on the server at all. It requires no live
     * session of ours, and a chat that was opened from an existing session.
     */
    fun warnBeforeSend(text: String): CostWarning? {
        if (text.isBlank() || text.startsWith("/") || costWarningAcknowledged) return null
        val sid = _localSessionId.value
        val ours = sid != null && activeSessions[sid]?.isAlive() == true
        val kind = when {
            coldCacheRebuild.value -> CostWarning.Kind.COLD_CACHE
            runningElsewhere.value -> CostWarning.Kind.RUNNING_ELSEWHERE
            else -> null
        } ?: return null
        return CostWarning(text, kind, contextPercent())
    }

    /** The user answered; do not ask again in this chat. */
    fun acknowledgeCostWarning() { costWarningAcknowledged = true }

    private val _costWarning = MutableStateFlow<CostWarning?>(null)
    val costWarning: StateFlow<CostWarning?> = _costWarning.asStateFlow()

    fun dismissCostWarning() { _costWarning.value = null }

    /** The user read it and wants to send anyway. */
    fun sendAnyway() {
        val held = _costWarning.value?.text ?: return
        _costWarning.value = null
        costWarningAcknowledged = true
        send(held)
    }

    /** The cheaper way out of the same situation: shrink the conversation
     *  first, then send. Nothing is lost from the transcript. */
    fun compactThenSend() {
        val held = _costWarning.value?.text ?: return
        _costWarning.value = null
        costWarningAcknowledged = true
        pendingAfterCompact = held
        send("/compact", allowSlash = false)
    }

    /** Set once the user has answered for this chat — the warning is a heads-up,
     *  not a toll booth on every message. */
    @Volatile private var costWarningAcknowledged = false
    @Volatile private var pendingAfterCompact: String? = null

    /** How much of the window this conversation already fills — the number the
     *  warning is really about, since that is what gets re-sent. */
    private fun contextPercent(): Int {
        val cs = costStats.value
        val max = cs.contextMax.takeIf { it > 0L } ?: 200_000L
        return ((cs.contextTokens.toDouble() / max) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Run the CLI's own compaction. VERIFIED over our channel (2026-08-03): a
     * plain `/compact` user turn makes the CLI emit `status` → `compact_boundary`
     * — the very events the chat already renders — and the following turn reads
     * a much smaller context. So this needs no new protocol, just the turn.
     */
    fun confirmCompact() {
        _pendingCompact.value = null
        viewModelScope.launch { send("/compact") }
    }

    // ── Rewind (/rewind) ──────────────────────────────────────────────────
    // Two SEPARATE steps, deliberately: the conversation rewind is reversible
    // (the discarded branch stays in the transcript, nothing on disk moves),
    // the FILE rewind overwrites the user's working tree and is therefore
    // never implicit — it always shows what it would change first.

    /** The rewind sheet's state for one user turn. */
    data class RewindTarget(
        val recordUuid: String,
        /** The prompt text, so the sheet can name what it rewinds to. */
        val preview: String,
        /** Dry-run result: what a FILE rewind would restore. null = not
         *  probed yet; canRewind=false carries the CLI's own reason. */
        val files: ai.eight24family.conch.agent.FileRewindResult? = null,
        val probing: Boolean = false,
    )

    private val _rewindTarget = MutableStateFlow<RewindTarget?>(null)
    val rewindTarget: StateFlow<RewindTarget?> = _rewindTarget.asStateFlow()

    /** Text the composer should adopt after a rewind (the rewound prompt,
     *  handed back for editing — same as the CLI). Consumed by the screen. */
    private val _rewindPrefill = MutableStateFlow<String?>(null)
    val rewindPrefill: StateFlow<String?> = _rewindPrefill.asStateFlow()
    fun consumeRewindPrefill() { _rewindPrefill.value = null }

    /**
     * Long-press on a user row → open the rewind sheet, resolve the anchor if
     * the row doesn't carry one, and dry-run what a FILE rewind would touch.
     *
     * [recordUuid] is null for a bubble the server hasn't echoed back to us
     * yet (every message the moment it is sent, and any row in a session that
     * was reopened while still alive). Rather than refuse the gesture on
     * exactly the turns a user most wants to undo, we ask the server for the
     * anchor. Only if THAT fails is there genuinely nothing to rewind to.
     */
    fun openRewind(recordUuid: String?, preview: String) {
        if (_currentAgent.value != Agent.CLAUDE) return
        val text = preview
        _rewindTarget.value = RewindTarget(recordUuid.orEmpty(), preview.take(120), probing = true)
        viewModelScope.launch(Dispatchers.IO) {
            val sess = _localSessionId.value?.let { activeSessions[it] }
            val anchor = recordUuid?.takeIf { it.isNotBlank() }
                ?: sess?.resolveUserRecordUuid(text)
            if (anchor.isNullOrBlank()) {
                _rewindTarget.value = null
                _chatNotice.value =
                    "Can't rewind to that message yet — the server hasn't recorded it"
                return@launch
            }
            _rewindTarget.update { cur ->
                cur?.copy(recordUuid = anchor)
            }
            val dry = sess?.rewindFiles(anchor, dryRun = true)
            _rewindTarget.update { cur ->
                if (cur?.recordUuid != anchor) cur else cur.copy(files = dry, probing = false)
            }
        }
    }

    fun dismissRewind() { _rewindTarget.value = null }

    /** Step 1: conversation only. Nothing on disk is touched. */
    fun rewindConversation() {
        val target = _rewindTarget.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val sess = _localSessionId.value?.let { activeSessions[it] }
            val res = sess?.rewindConversation(target.recordUuid)
            if (res?.ok == true) {
                _rewindPrefill.value = res.prefillText
                _rewindTarget.value = null
            } else {
                // Never silently swallow: the CLI's own reason ("turn running",
                // "target not found") is what tells the user what to do next.
                _chatNotice.value = "Rewind failed — ${res?.error ?: "no live session"}"
                _rewindTarget.update { it?.copy(probing = false) }
            }
        }
    }

    /** Step 2: FILES. Only ever reached from an explicit confirm on a sheet
     *  that already listed the files by name. */
    fun rewindFilesConfirmed() {
        val target = _rewindTarget.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val sess = _localSessionId.value?.let { activeSessions[it] }
            val res = sess?.rewindFiles(target.recordUuid, dryRun = false)
            if (res?.canRewind == true) {
                val n = target.files?.filesChanged?.size ?: 0
                val filesMsg = if (n > 0) "Restored $n file(s) to before this turn"
                    else "Files restored to before this turn"
                // Files are back; the conversation still holds the turn that
                // produced them, so rewind that too — otherwise the chat claims
                // work that no longer exists on disk.
                val conv = sess.rewindConversation(target.recordUuid)
                if (conv.ok) {
                    _rewindPrefill.value = conv.prefillText
                    _chatNotice.value = filesMsg
                } else {
                    // HALF-DONE, and the user must know WHICH half: the disk is
                    // rolled back but the transcript still contains the turn.
                    // Silently reporting success here would leave the chat
                    // describing work that no longer exists.
                    _chatNotice.value =
                        "$filesMsg — but the conversation was NOT rewound (${conv.error ?: "refused"})"
                }
                _rewindTarget.value = null
            } else {
                _chatNotice.value = "File rewind failed — ${res?.error ?: "no live session"}"
                _rewindTarget.update { it?.copy(probing = false) }
            }
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
        // THE USER'S PICK IS LAW — enforced continuously, not just at open.
        //
        // The pick lives in DataStore and loads asynchronously, and the launch
        // model is otherwise resolved once, at chat open, from whatever was known
        // then. That let a chat open on a default and stay there even though the
        // user's choice was sitting in prefs. Whenever the stored pick arrives or
        // changes, it overrides whatever the resolution chain guessed — no probe,
        // no default, no freshness sweep gets to move it afterwards.
        viewModelScope.launch {
            modelsCoord.selectedModel.collect { pick ->
                val chosen = pick?.takeIf { it.isNotBlank() } ?: return@collect
                val sid = _localSessionId.value ?: return@collect
                val sess = activeSessions[sid] ?: return@collect
                // ⚠ ONLY A PICK THAT STILL BEATS THE SESSION. This collector used
                // to re-apply the stored pick unconditionally, so everything the
                // launch resolution stopped doing came straight back in through
                // here a moment later — a stale pin would switch the running
                // conversation's model, bust its prompt cache and re-read the
                // whole thing. Same predicate as the launch: the pick wins while
                // it is newer than what the session last reported.
                if (modelsCoord.observationNewerThanPick.value) {
                    android.util.Log.i(
                        "SshAi-Models",
                        "stale pick '$chosen' NOT applied — the session's own model stands",
                    )
                    return@collect
                }
                if (sess.modelOverride != chosen) {
                    android.util.Log.i(
                        "SshAi-Models",
                        "explicit pick '$chosen' re-applied (was '${sess.modelOverride}')",
                    )
                    sess.modelOverride = chosen
                    // Push the late-arriving pick onto the RUNNING process too —
                    // without this the live session keeps its old model until the
                    // next launch-params restart.
                    launch(Dispatchers.IO) { sess.applyModelLive(chosen) }
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
                    // MIRRORED turn ended → release the visible queue. This is
                    // the edge the local Working→Running collector never sees
                    // (the turn ran in another process / on another device),
                    // and its absence is WHY mid-turn sends used to bypass the
                    // queue entirely (2026-08-11). With this drain the queue
                    // covers background turns too, so the bypass is gone and a
                    // message typed into a busy chat stays a cancelable row
                    // until the turn is genuinely over (2026-08-17).
                    val sid = _localSessionId.value
                    val sess = sid?.let { activeSessions[it] }
                    // Same rule as every other release: idle AND up. "Not
                    // Working" alone let a `Failed` session claim the queue.
                    if (sess != null && shouldReleaseQueue(
                            hasQueue = _outbox.value.isNotEmpty(),
                            working = _stateBySession.value[sid] is SessionState.Working,
                            drainerBusy = sess.drainerBusy,
                            // This IS the mirrored turn-end edge — the flag has
                            // just gone false, so it must not gate itself.
                            mirroredTurnOpen = false,
                            sessionReady = _stateBySession.value[sid] is SessionState.Running,
                        )
                    ) {
                        drainOutbox(sess)
                    }
                    launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(6_000)
                        UsageProbe.fetch(serverId, _currentAgent.value, fast = false)?.let { _usage.value = it }
                    }
                }
                wasRemote = remote
            }
        }
        // ── QUEUE RELEASE OF LAST RESORT ──
        //
        // Every other drain hangs off an EDGE: local Working→Running,
        // remoteFileOpen true→false, network-back, the once-per-slot kick. Each
        // is correct and each can be missed, and when one is missed the queue
        // just sits there — the user's follow-ups visible above the prompt bar,
        // never sent, with no way to make them go except retyping. Stop is the
        // easiest way to lose an edge: it force-clears `remoteFileOpen` while
        // the state is still Working (so the mirrored collector's edge is spent
        // on a moment when the guard rejects it), and STREAM_FORCE exists
        // precisely because our tracking can already have fallen off Working —
        // no edge left to fire at all.
        //
        // So: STATE, not edges. When the queue is non-empty and the session has
        // been genuinely idle for a moment, release it. That is the whole
        // condition, and it holds however the turn ended.
        //
        // ⚠ IDLE MUST BE SUSTAINED. Draining into a turn that is about to start
        // hands the prompt to the CLI's own invisible queue — the exact thing
        // this outbox exists to prevent (the 2026-08-11 bypass). `drainerBusy`
        // stays true for the whole of a turn WE launched even when the state
        // machine has desynced off Working, so the three-tick hold plus that
        // flag is what keeps this from firing mid-turn. drainOutbox's take is
        // atomic, so a real edge and this net can never both send the batch.
        viewModelScope.launch {
            var idleTicks = 0
            while (true) {
                kotlinx.coroutines.delay(500)
                val sid = _localSessionId.value
                val sess = sid?.let { activeSessions[it] }
                if (sess == null) { idleTicks = 0; continue }
                val idle = shouldReleaseQueue(
                    hasQueue = _outbox.value.isNotEmpty(),
                    working = _stateBySession.value[sid] is SessionState.Working,
                    drainerBusy = sess.drainerBusy,
                    mirroredTurnOpen = tailPollCoord.remoteFileOpen.value,
                    sessionReady = _stateBySession.value[sid] is SessionState.Running,
                )
                if (!idle) { idleTicks = 0; continue }
                if (++idleTicks < QUEUE_RELEASE_IDLE_TICKS) continue
                idleTicks = 0
                android.util.Log.i(
                    "SshAi-Chat",
                    "idle with ${_outbox.value.size} queued message(s) — releasing the queue",
                )
                drainOutbox(sess)
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
    /** Stop the armed `/loop`. Separate from [stopCurrent] because between
     *  ticks there is no turn to stop — see AgentSession.stopLoop. */
    /** Fresh CLI for this chat — picks up .mcp.json, a CLI update, changed
     *  settings. The conversation survives (`--resume` on the next send). */
    fun restartCli() {
        val sid = _localSessionId.value ?: return
        activeSessions[sid]?.restartCli()
        _chatNotice.value = "CLI restarted — your next message starts a fresh one and keeps this conversation."
    }

    fun stopLoop() {
        val sid = _localSessionId.value ?: return
        activeSessions[sid]?.stopLoop()
        _loopBySession.update { it + (sid to null) }
    }

    fun stopCurrent() {
        val sid = _localSessionId.value ?: return
        val s = activeSessions[sid]
        // The route is a pure decision (pinned by StopRouteTest). The bug this
        // fixes: an owned live process routed to the EXTERNAL pgrep kill, which
        // races our own supervision — it never sets `userCancelled`, so the
        // send-ack watchdog reads the death as a drop and REDELIVERS the
        // prompt. An owned process must NEVER take that route.
        when (stopRoute(
            sessionExists = s != null,
            ownsLiveProcess = s?.hasLiveCliProcess() == true,
            isWorking = s?.state?.value is SessionState.Working,
        )) {
            // Owned + our tracking still on the turn → interrupt + escalate.
            StopRoute.STREAM -> s?.cancelCurrent()
            // Owned but tracking desynced off Working (reopened mid-turn) →
            // force: the stream tears ITS OWN process down on procAlive alone,
            // cleanly, so Stop can't no-op into the external kill.
            StopRoute.STREAM_FORCE -> s?.cancelCurrent(force = true)
            // A one-shot turn WE run (no persistent process) → in-channel
            // INT→TERM→KILL ladder.
            StopRoute.ONESHOT -> s?.cancelCurrent()
            StopRoute.EXTERNAL_KILL -> {
                stopMirroredRemoteTurn()
                s?.cancelCurrent()
            }
        }
        // Kill the working verb NOW. The verb shows on state==Working OR the mirror
        // poll's remoteFileOpen; the branches above handle the app-driven state,
        // but the poll flag would keep the gerund up until the next tick
        // re-evaluated the file. Clear it optimistically; the poll re-lights only
        // on genuine new growth — e.g. the queued message's own turn starting via
        // drainOutbox.
        tailPollCoord.setRemoteFileOpen(false)
    }

    /** Read a workflow journal's live progress over the pooled SSH: returns
     *  (doneAgents, totalAgents) or null on any miss. One `grep -c` per side —
     *  the journal is append-only `{"type":"started"}` / `{"type":"result"}`
     *  lines (verified against a real run, 2026-08-14). Path is
     *  `<sessionDir>/subagents/workflows/<runId>/journal.jsonl`. */
    private suspend fun pollWorkflowJournal(
        s: AgentSession, sessionDir: String, runId: String,
    ): Triple<Int, Int, Long>? {
        val j = "$sessionDir/subagents/workflows/$runId/journal.jsonl"
        val esc = ai.eight24family.conch.agent.shellEscape(j)
        // done total mtime — one grep per side + a stat, all on the live channel.
        val inner = "j=$esc; if [ -f \"\$j\" ]; then " +
            "printf '%s %s %s' " +
            "\"\$(grep -c '\"type\":\"result\"' \"\$j\" 2>/dev/null || echo 0)\" " +
            "\"\$(grep -c '\"type\":\"started\"' \"\$j\" 2>/dev/null || echo 0)\" " +
            "\"\$(stat -c %Y \"\$j\" 2>/dev/null || stat -f %m \"\$j\" 2>/dev/null || echo 0)\"; fi"
        val out = s.execOnLive("bash -lc " + ai.eight24family.conch.agent.shellEscape(inner))
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = out.split(Regex("\\s+"))
        val done = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val total = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val mtime = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        return Triple(done, total, mtime)
    }

    /** See [stopCurrent] — kill the server-side CLI turn for this session id
     * Discovery + INT→TERM→KILL ladder + liveness verdict live in
     * [ai.eight24family.conch.agent.RemoteTurnKiller] (shared with the
     * zombie-kill path — the two copies had drifted). The verdict is then
     * CONFIRMED against the session file: kill -0 proves the pgrep'd processes
     * died, but a mirrored Codex app-server / Gemini ACP / console-minted REPL
     * turn carries no resume id in argv, so the only honest test for "did the
     * TURN stop" is whether anything still writes the JSONL. A button that
     * silently does nothing is banned, so every branch says what happened. */
    private fun stopMirroredRemoteTurn() {
        val rid = _resumeId.value
        val killer = ai.eight24family.conch.agent.RemoteTurnKiller
        // Shell-injected — accept only a UUID-shaped id.
        if (!killer.isKillableResumeId(rid)) {
            _chatNotice.value = "Nothing to stop — no running turn found."
            return
        }
        val client = ServiceLocator.sshConnectionPool.peek(serverId)
        if (client == null) {
            _chatNotice.value = "No live connection — can't reach the server-side turn."
            return
        }
        _chatNotice.value = "Stopping the server-side turn…"
        viewModelScope.launch(Dispatchers.IO) {
            val path = _localSessionId.value?.let { sessionPathMap[it] }
            val preSize = path?.let { execPooledText(client, remoteSizeScript(it))?.trim()?.toLongOrNull() }
            val out = execPooledText(client, killer.killScript(rid!!))
            when (val verdict = killer.parseOutcome(out)) {
                is ai.eight24family.conch.agent.RemoteTurnKiller.Outcome.Killed -> {
                    // Processes confirmed dead — repaint now; the poll would take
                    // a tick to notice the freeze. The delayed stat below is the
                    // second writer check only, never a spinner source.
                    tailPollCoord.setRemoteFileOpen(false)
                    _chatNotice.value = "Server-side turn stopped."
                    if (path != null) {
                        // The dying CLI legitimately flushes a final result record,
                        // so growth right after the kill proves nothing — wait out
                        // the flush, then require the file to have gone quiet.
                        kotlinx.coroutines.delay(2_500)
                        val mid = execPooledText(client, remoteSizeScript(path))?.trim()?.toLongOrNull()
                        kotlinx.coroutines.delay(2_000)
                        val post = execPooledText(client, remoteSizeScript(path))?.trim()?.toLongOrNull()
                        if (mid != null && post != null && post > mid) {
                            // Still being written — a writer pgrep can't see.
                            _chatNotice.value =
                                "The session is still being written by another process — stop it where it started."
                        }
                    }
                }
                is ai.eight24family.conch.agent.RemoteTurnKiller.Outcome.Survived ->
                    _chatNotice.value = "Couldn't stop the server-side turn — it's still running."
                ai.eight24family.conch.agent.RemoteTurnKiller.Outcome.NoneFound -> {
                    // Nothing on the server carries this resume id. For Claude
                    // that means the turn already ended; for Codex/Gemini the
                    // mirrored writer (app-server / ACP) never puts the id in
                    // argv, so we CANNOT kill it — say so instead of pretending.
                    val stillGrowing = if (path != null && preSize != null) {
                        val post = execPooledText(client, remoteSizeScript(path))?.trim()?.toLongOrNull()
                        post != null && post > preSize
                    } else false
                    _chatNotice.value = when {
                        stillGrowing && _currentAgent.value != Agent.CLAUDE ->
                            "Can't stop a mirrored ${_currentAgent.value.displayName} turn from here — stop it on the machine that started it."
                        stillGrowing ->
                            "Can't find the writer — it may be a console session; stop it where it started."
                        else -> "No running turn found on the server."
                    }
                }
                ai.eight24family.conch.agent.RemoteTurnKiller.Outcome.Unreachable ->
                    _chatNotice.value = "Couldn't reach the server to stop the turn."
            }
        }
    }

    /** Portable remote file-size probe (GNU stat → BSD stat → wc -c), used by
     *  the stop-confirmation reads above. */
    private fun remoteSizeScript(path: String): String {
        val q = ai.eight24family.conch.agent.shellEscape(path)
        return "stat -c %s $q 2>/dev/null || stat -f %z $q 2>/dev/null || wc -c < $q 2>/dev/null"
    }

    /**
     * Paint the chat from the server's CURRENT tail (last [DISPLAY_TAIL_BYTES]).
     * `loadHistory` MERGES (mergeUnsyncedUserText preserves unsynced rows), so
     * it is safe to call over a cache seed: it surfaces a live turn's PROMPT
     * that a stale cache does not hold yet, without doubling anything.
     */
    private suspend fun paintTailFromServer(
        tailClient: net.schmizz.sshj.SSHClient,
        path: String,
        s: ai.eight24family.conch.agent.AgentSession,
        agent: Agent,
        localId: String,
    ) {
        val tailCmd = ai.eight24family.conch.agent.RemoteEnv.portable(
            "bash -lc " + ai.eight24family.conch.agent.shellEscape(
                "tail -c $DISPLAY_TAIL_BYTES -- " + ai.eight24family.conch.agent.shellEscape(path)
            ),
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
        if (tailRaw == null || tailRaw.isEmpty()) return
        // `tail -c N` returns exactly N bytes iff the file is larger → earlier
        // turns exist (show the marker) and the slab starts mid-line (drop it).
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

    /** One pooled exec channel → stdout text, null on any failure. The stop
     *  path can't use execOnLive (there may be NO AgentSession at all for a
     *  mirrored turn), so it rides the pool client directly. */
    private fun execPooledText(client: net.schmizz.sshj.SSHClient, script: String): String? =
        SilentlyTry.logged("SshAi-Chat", "stop-path pooled exec") {
            val sess = client.startSession()
            try {
                val proc = sess.exec(
                    ai.eight24family.conch.agent.RemoteEnv.portable(
                        "bash -lc " + ai.eight24family.conch.agent.shellEscape(script),
                    ),
                )
                val text = proc.inputStream.bufferedReader().readText()
                proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                text
            } finally {
                SilentlyTry.fired("SshAi-Chat", "close stop session") { sess.close() }
            }
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

    /** The CLI's own commands and skills (from the initialize handshake). */
    val agentCommands: StateFlow<List<SlashCommand>> get() = slashCoord.agentCommands

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
            // Switching agent IS starting a new conversation, so it gets both
            // halves of the new-chat rule: never adopt the previous brand-new
            // orphan, and close the slot being abandoned. Without this the
            // CLAUDE→CODEX→CLAUDE round trip handed back the wedged Claude chat
            // it had left behind — and left its `claude --print`, SSH channel and
            // pool reference running in between, i.e. it MANUFACTURED the orphan
            // it then adopted.
            startNewChat(newAgent, adoptExisting = false)
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
        // Another conversation, so the brand-new slot we are leaving is closed
        // rather than abandoned (tapping a session from inside an unused new chat
        // used to leak it). Adoption is unaffected: with a resume id the lookup is
        // `findByResume`, which is exactly what should happen here.
        startNewChat(
            _currentAgent.value,
            resumeIdParam = session.id,
            resumeFilePath = session.path,
            adoptExisting = false,
        )
    }

    /** Start a fresh CLI session, no --resume. */
    fun newSession() {
        modelsCoord.setSessionInitialModel(null)
        // RE-READ THE DEFAULT FIRST. A new chat with no explicit pick is born on
        // whatever the CLI's default is at that moment — so the model the top bar
        // shows before the first send is a claim about the SERVER, and a cached
        // claim goes stale the moment the default is changed there. The catalog
        // probe is skipped while it is "fresh", which is right for the model list
        // (it rarely changes) and wrong for the default (it changes by hand).
        // Force it on the one action whose outcome depends on it: pressing + new
        // session. Async — the chat opens now, the chip corrects itself in under
        // a second, and the session's own `initialize` remains the final word.
        viewModelScope.launch {
            val s = _localSessionId.value?.let { activeSessions[it] } ?: return@launch
            SilentlyTry.fired("SshAi-Models", "refresh default model for new session") {
                modelsCoord.probeAvailableModels(s, force = true)
            }
        }
        // "+ new session" from inside a chat: a genuinely new conversation, so
        // the previous brand-new session is neither shown again nor left behind.
        startNewChat(_currentAgent.value, resumeIdParam = null, adoptExisting = false)
    }

    fun retry() {
        val sid = _localSessionId.value ?: return
        val agent = sessionAgentMap[sid] ?: _currentAgent.value
        val resume = _resumeId.value
        val path = sessionPathMap[sid]
        // ⚠ EVICT THE HALF-OPEN TRANSPORT FIRST. We only get here after a
        // Failed("disconnected") — the turn found the client dead. But sshj's
        // `isConnected` (what pool.peek trusts) stays TRUE on a half-open
        // socket (TCP up, peer gone), so peek() keeps handing back the corpse:
        // the connection dot stays lit, the reconnect below sees `peek != null`
        // and SKIPS the actual reconnect, startNewChat re-acquires the same dead
        // client, the next turn fails "disconnected" again — an infinite loop
        // that re-parses the whole (here 13 MB) session file every ~2 s, and
        // every send/upload dies "no connection" over a lit dot (confirmed from
        // logcat, 2026-08-12). Dropping the poisoned transport makes peek()
        // honest (dot goes dim) AND lets the reconnect open a FRESH socket.
        //
        // ⚠ ONLY IF IT IS ACTUALLY THE CORPSE. We arrive here 1-10 s after the
        // failure (the ladder's backoff), and the seamless ephemeral reconnect
        // or the service watchdog may already have rebuilt the transport in
        // between. Evicting THAT one turns the recovery into the bug: the
        // rebuilt session comes up on nothing, fails "disconnected", the ladder
        // fires again — the app killed a healthy connection every ~4 s and the
        // chat never reached Running, so the user's parked message had no edge
        // to leave on (2026-08-16). MIN_AGE keeps anything born after the
        // failure; a genuinely poisoned transport is older than that.
        SilentlyTry.fired("SshAi-Chat", "evict poisoned transport on retry") {
            ServiceLocator.sshConnectionPool.evictPoisoned(
                serverId,
                "turn failed disconnected while pooled client looked alive",
                minAgeMs = ai.eight24family.conch.ssh.SshConnectionPool.EVICT_MIN_AGE_MS,
            )
        }
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
        // …and the ones `close(sid)` CANNOT see. A chat that adopted its
        // session via findByResume keeps it in the manager under the key of the
        // chat that created it, so closing `sid` removed an empty slot while
        // the real object lived on — re-adoptable on the next rebuild, and
        // holding a pool reference nothing will ever release. That is what made
        // the reconnect loop self-perpetuating (four such leftovers were
        // rebinding onto the pool's transport per cycle, 2026-08-16). We are
        // rebuilding this chat from scratch: nothing attached to this resume id
        // may survive it.
        resume?.let { sessionsManager.closeStaleForResume(serverId, agent, it, keepChatId = sid) }
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
        pendingRedelivery.update { it + undelivered }
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
            // TAIL-FIRST cache: the head was never downloaded (base > 0), so
            // parsing the local file would "load all" into… the same tail the
            // user is already looking at. The marker tap IS the explicit ask
            // for the whole rollout — stream the full body (gzip, RAM-flat)
            // over the pooled client; the save resets the base to 0. With no
            // live client the tap honestly shows only what is cached (and the
            // marker stays, because the base is still > 0).
            if (ServiceLocator.historyCache.baseOffset(resumeId) > 0L) {
                val path = sessionPathMap[localId]
                if (path != null) {
                    withContext(Dispatchers.IO) {
                        tailPollCoord.streamFullToCache(s, resumeId, path)
                    }
                }
            }
            val full = ServiceLocator.historyCache.load(resumeId)?.use { snap ->
                tailPollCoord.parseJsonl(
                    ai.eight24family.conch.util.JsonlUtils.trimToLastNewline(snap.buffer), agent,
                )
            }
            if (!full.isNullOrEmpty()) s.loadHistory(full)
        }
    }

    override fun onCleared() {
        // The user is LEAVING the chat — everything currently in it was on
        // their screen. Baseline BOTH unread trackers on the FULL history
        // size, not the display-list size: the display hides some history
        // rows, so the per-emission stamp left a permanent positive delta and
        // the home badge showed messages the user had literally just read.
        // Post-exit arrivals still count: the baseline is the size at THIS
        // moment, not infinity.
        val sid = _localSessionId.value
        val s = sid?.let { activeSessions[it] }
        val rid = s?.agentSessionId ?: _resumeId.value
        if (rid != null) {
            // Only with a real history size — stamping 0 would RESET the
            // baseline and badge the entire chat as new.
            s?.history?.value?.size?.let { historySize ->
                ai.eight24family.conch.agent.SessionSeenTracker.markSeen(rid, historySize)
            }
            SilentlyTry.fired("SshAi-Chat", "stamp seen watermark on exit") {
                ServiceLocator.historyCache.markSeenBytes(
                    rid, ServiceLocator.historyCache.size(rid),
                )
            }
        }
        // ⚠ NEVER LOSE A MESSAGE THE USER ASKED TO SEND. `pendingRedelivery`
        // (undelivered prompts awaiting silent reconnect) lives ONLY in this
        // VM — a chat exited while the link was down took it to the grave.
        // Persist to the (server,agent) draft slot; the next "+ new session"
        // offers it in the COMPOSER (never auto-sent, 2026-08-17). The
        // VISIBLE outbox is NOT included here — it has its own per-chat
        // persistence (observeOutboxForPersistence), and bootstrap-parked
        // rows already wrote their own draft at queue time.
        val agent = _currentAgent.value
        val bodies = LinkedHashSet<String>().apply {
            pendingRedelivery.value.forEach { add(it) }
            s?.consumeUndelivered()?.forEach { add(it) }
        }
        for (b in bodies) {
            if (b.isBlank()) continue
            SilentlyTry.fired("SshAi-Chat", "persist queued send on exit") {
                ServiceLocator.historyCache.appendDraft(serverId, agent, b)
            }
        }
        // AFTER the drafts are on disk (order matters — the close discards the
        // session's in-memory history): a chat the user abandoned before the CLI
        // ever gave it an id is unreachable from now on. Nothing resumes it, and
        // the next "+ new chat" deliberately no longer adopts it, so leaving it
        // in the manager would keep a `claude --print` process, an SSH channel
        // and a pool reference alive for a screen that no longer exists — one per
        // abandoned new chat, until the server's session ceiling says no. A turn
        // still in flight is exempt (see closeIfBrandNew); the foreground service
        // is there to let it finish.
        if (sid != null) {
            SilentlyTry.fired("SshAi-Chat", "close brand-new session on exit") {
                sessionsManager.closeIfBrandNew(serverId, agent, sid)
            }
        }
        super.onCleared()
    }

    companion object {
        /** Public constant — referenced by ChatPromptBar / ChatScreenPromptHost. */
        const val MAX_ATTACHMENTS: Int = 10

        /** Workflow journal poll cadence + how long done==total must stay quiet
         *  (journal mtime frozen) before the row retires. */
        private const val WF_POLL_MS = 5_000L
        private const val WF_SETTLE_MS = 20_000L

        /** Terminal task-row labels — anything else counts as a LIVE background
         * task (drives [liveBgTasks]). Includes `stopped` and the CLI's «No
         * completion record was found…» wording: that row NEVER goes terminal
         * by status, so it pinned the ⏳ line forever. */
        private val BG_TASK_TERMINAL_RX =
            Regex("^task · (completed|failed|killed|interrupted|stopped)\\b|No completion record")

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
        /** How long the transport must be continuously down before the chat
         *  says anything about it in words. Below this: silent reconnect, the
         *  queue rows and the server dot carry the state. */
        internal const val UNREACHABLE_QUIET_MS: Long = 5 * 60_000L

        /**
         * The 5-minute quiet-banner gate, pure so the failure matrix can pin it
         * (Workstream E): the "server unreachable — retrying quietly" line shows
         * ONLY when the link is currently lost AND has been continuously lost
         * for at least [UNREACHABLE_QUIET_MS]. [downSince] is 0 whenever the
         * caller has seen the link up since — so a blip resets it and the banner
         * never fires on a flapping radio (INVARIANTS 2026-08-17: words about the
         * connection appear only when waiting might not help).
         */
        internal fun unreachableBannerShown(lost: Boolean, downSince: Long, now: Long): Boolean =
            lost && downSince != 0L && now - downSince >= UNREACHABLE_QUIET_MS

        /**
         * On open, is the server file genuinely AHEAD of what the cache holds —
         * i.e. a live turn wrote since the cache was saved, so the current
         * prompt lives only on the server and the display tail must be refreshed
         * (see [paintTailFromServer])? Verified fix for the reopen gap. The
         * compare is in REMOTE coordinates: a tail-first cache (Workstream C)
         * starts at [baseOffset] > 0, and comparing its short LOCAL length to
         * the full server size would wrongly read "stale" on every open —
         * `serverSize > baseOffset + cachedLen` is the correct, base-aware test.
         * A null server size (stat failed) ⇒ can't tell ⇒ don't refresh.
         */
        internal fun serverAheadOfCache(serverSize: Long?, baseOffset: Long, cachedLen: Long): Boolean =
            serverSize != null && serverSize > baseOffset + cachedLen

        /**
         * Does a fresh send go to the VISIBLE QUEUE instead of straight to the
         * CLI? Pure so it can be pinned (SendQueueGateTest). A send is queued
         * whenever a turn is in flight — OUR OWN ([working], or [running] while
         * the drainer winds one down) OR a MIRRORED one ([mirroredTurnOpen], the
         * file says a turn is running even though our state machine hasn't caught
         * up on reopen). The last conjunct is the fix for (user, 2026-08-17): a
         * hot send into a reopened live turn interrupts it and cold-restarts.
         */
        internal fun shouldQueueSend(
            working: Boolean,
            runningWithBusyDrainer: Boolean,
            mirroredTurnOpen: Boolean,
        ): Boolean = working || runningWithBusyDrainer || mirroredTurnOpen

        /**
         * How many consecutive idle polls (500 ms each) must pass before the
         * last-resort release fires. Three is long enough that the gap between
         * "turn ended" and "the next turn started" can't be mistaken for idle,
         * short enough that a missed edge costs the user ~1.5 s rather than
         * their whole queue.
         */
        internal const val QUEUE_RELEASE_IDLE_TICKS = 3

        /**
         * The EXACT INVERSE of [shouldQueueSend], plus "there is something to
         * send and the session can take it". Pure so the release rule is pinned
         * by a test instead of living only inside a polling loop: a queued
         * message must be released whenever the session is idle, no matter which
         * edge (local turn end, mirrored turn end, Stop, reconnect) got us
         * there — see the watcher for the failure this net closes.
         */
        internal fun shouldReleaseQueue(
            hasQueue: Boolean,
            working: Boolean,
            drainerBusy: Boolean,
            mirroredTurnOpen: Boolean,
            sessionReady: Boolean,
        ): Boolean = hasQueue && sessionReady &&
            !shouldQueueSend(
                working = working,
                runningWithBusyDrainer = drainerBusy,
                mirroredTurnOpen = mirroredTurnOpen,
            )

        /** How Stop reaches the running turn. */
        enum class StopRoute { STREAM, STREAM_FORCE, ONESHOT, EXTERNAL_KILL }

        /**
         * Where does Stop route the halt? Pure so it can be pinned
         * (StopRouteTest). THE INVARIANT: a turn running in a process WE own
         * (`ownsLiveProcess`) NEVER routes to [EXTERNAL_KILL] — that external
         * pgrep kill races our own supervision and, by not setting
         * `userCancelled`, makes the send-ack watchdog redeliver the prompt, so
         * Stop stopped for a beat and the turn resumed (user, 2026-08-17). Owned
         * processes stop through the stream (interrupt + teardown); [STREAM_FORCE]
         * covers the reopened-mid-turn desync where our tracking fell off
         * Working while the process kept writing. Only a session with NO live
         * process of ours — the orphan after a full restart that we merely mirror
         * — takes the external ladder.
         */
        internal fun stopRoute(
            sessionExists: Boolean,
            ownsLiveProcess: Boolean,
            isWorking: Boolean,
        ): StopRoute = when {
            !sessionExists -> StopRoute.EXTERNAL_KILL
            ownsLiveProcess && isWorking -> StopRoute.STREAM
            ownsLiveProcess -> StopRoute.STREAM_FORCE
            isWorking -> StopRoute.ONESHOT
            else -> StopRoute.EXTERNAL_KILL
        }

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
