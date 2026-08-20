package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentStatus
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.Server
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Orchestrator for the "pick an agent" screen.
 *
 * This class owns all UI-visible state ([StateFlow]s) and the public
 * `fun` surface the screen calls. The heavy lifting lives in four
 * cohesive helpers in the same package:
 *
 *  - [AgentPickerViewModelRefresh] — `refresh()` / probe / sk-touch
 *    request lifecycle.
 *  - [AgentPickerViewModelInstall] — `installAgent()` + multi-path
 *    bash bootstrap.
 *  - [AgentPickerViewModelApiKey] — login chooser + `submitApiKey()`.
 *  - [AgentPickerViewModelOAuth] — Claude `setup-token`, Codex moltbot
 *    callback, Gemini paste-code.
 *
 * The helpers receive the mutable backing of each [StateFlow] and only
 * write to it through the mutators; reads still go through public
 * accessors on this class. That keeps the screen's binding pattern
 * (`vm.foo.collectAsState()`) untouched.
 */
class AgentPickerViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    val serverId: String = checkNotNull(savedStateHandle["serverId"])
    /** Reached via the Agents bottom-tab: show cached statuses without auto-
     *  connecting / asking for the key. Explicit refresh still connects. */
    private val browse: Boolean = savedStateHandle.get<Boolean>("browse") ?: false
    private val repo  = ServiceLocator.serverRepository
    private val probeApi = ServiceLocator.agentStatusProbe
    private val cache = ServiceLocator.agentStatusCache

    private val _server = MutableStateFlow<Server?>(null)
    val server: StateFlow<Server?> = _server.asStateFlow()

    /** null = not initialised yet. Empty map = probed but nothing found. */
    private val _statuses = MutableStateFlow<Map<Agent, AgentStatus>?>(null)
    val statuses: StateFlow<Map<Agent, AgentStatus>?> = _statuses.asStateFlow()

    /** Browse mode + not connected: cache is shown but we deliberately did NOT
     *  connect (no key prompt). Drives a light "offline · tap to refresh"
     *  banner; tapping it (or pull-to-refresh) does the real connect. */
    private val _needsManualRefresh = MutableStateFlow(false)
    val needsManualRefresh: StateFlow<Boolean> = _needsManualRefresh.asStateFlow()

    // ── Auth-method switcher (long-press an agent) ──
    /** Which agent's method-switcher sheet is open (null = closed). */
    private val _methodSheetAgent = MutableStateFlow<Agent?>(null)
    val methodSheetAgent: StateFlow<Agent?> = _methodSheetAgent.asStateFlow()

    /** User-chosen active method per agent (key), overriding the probe's
     *  detected active. Loaded from AuthMethodStore; updated on switch. */
    private val _chosenMethods = MutableStateFlow<Map<Agent, String>>(emptyMap())
    val chosenMethods: StateFlow<Map<Agent, String>> = _chosenMethods.asStateFlow()

    init {
        viewModelScope.launch {
            val m = mutableMapOf<Agent, String>()
            for (a in Agent.entries) {
                ServiceLocator.authMethodStore.activeMethod(serverId, a)?.let { m[a] = it }
            }
            if (m.isNotEmpty()) _chosenMethods.value = m
        }
    }

    fun openMethodSheet(agent: Agent) {
        _methodSheetAgent.value = agent
        // A stale complaint from the previous visit must not greet the user.
        _accountOpError.value = null
        refreshSlots(agent)
    }
    fun closeMethodSheet() { _methodSheetAgent.value = null }

    // ── Multi-account credential slots (CredentialVault) ──
    private val _slots =
        MutableStateFlow<Map<Agent, List<ai.eight24family.conch.agent.CredentialVault.Slot>>>(emptyMap())
    val slots: StateFlow<Map<Agent, List<ai.eight24family.conch.agent.CredentialVault.Slot>>> =
        _slots.asStateFlow()

    private val _activeSlots = MutableStateFlow<Map<Agent, String>>(emptyMap())
    val activeSlots: StateFlow<Map<Agent, String>> = _activeSlots.asStateFlow()

    /** Slot id an account operation (remove / switch) is currently running on.
     * These are seconds of SSH round-trips, and the ✕ used to acknowledge the
     * tap with NOTHING — and a failed op (no transport → vault() is null) was
     * a silent no-op forever. The sheet shows a spinner on the busy row and
     * refuses double-taps; failures land in [accountOpError]. */
    private val _accountOpBusy = MutableStateFlow<String?>(null)
    val accountOpBusy: StateFlow<String?> = _accountOpBusy.asStateFlow()

    /** Why the last account operation did nothing — shown in the sheet until
     *  the next operation starts. Null = no complaint. */
    private val _accountOpError = MutableStateFlow<String?>(null)
    val accountOpError: StateFlow<String?> = _accountOpError.asStateFlow()

    /** Compact live-limits line per logged-in agent ("5h 28% · Weekly 3%"),
     * warmed off every status probe — the connection alone is enough to know
     * the limit, no chat entry required. Rides the same UsageProbe cache the
     * chat bar uses, so both surfaces always quote the same numbers. */
    private val _usageBrief = MutableStateFlow<Map<Agent, String>>(emptyMap())
    val usageBrief: StateFlow<Map<Agent, String>> = _usageBrief.asStateFlow()
    private val usageWarmAt = java.util.concurrent.ConcurrentHashMap<Agent, Long>()

    private fun rebuildUsageBrief(agent: Agent) {
        val rep = ai.eight24family.conch.agent.UsageProbe.cached(serverId, agent)
        val parts = rep?.windows?.filter { !it.perModel }?.take(2)
            ?.filter { it.percent >= 0 }?.map { w -> "${w.label} ${w.percent}%" }
            .orEmpty()
        _usageBrief.value =
            if (parts.isEmpty()) _usageBrief.value - agent
            else _usageBrief.value + (agent to parts.joinToString(" · "))
    }

    /** A vault bound to the live pooled SSH client, or null if not connected. */
    private fun vault(agent: Agent): ai.eight24family.conch.agent.CredentialVault? {
        val client = ServiceLocator.sshConnectionPool.peek(serverId) ?: return null
        return ai.eight24family.conch.agent.CredentialVault(agent) { cmd ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ai.eight24family.conch.util.SilentlyTry.logged("SshAi-AgentPicker", "vault exec") {
                    val sess = client.startSession()
                    try {
                        val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
                        val out = java.io.ByteArrayOutputStream()
                        proc.inputStream.copyTo(out)
                        proc.join(30, java.util.concurrent.TimeUnit.SECONDS)
                        String(out.toByteArray(), Charsets.UTF_8)
                    } finally {
                        ai.eight24family.conch.util.SilentlyTry.fired("SshAi-AgentPicker", "close vault session") { sess.close() }
                    }
                }
            }
        }
    }

    fun refreshSlots(agent: Agent) {
        // Instant: show the last-known accounts from the shared cache so the
        // sheet never flashes "No accounts yet" while a fresh listSlots
        // round-trips over SSH. We saved this account once — remember it; don't
        // re-detect from scratch and show it late every open.
        ai.eight24family.conch.agent.CredentialVault.cachedSlots(serverId, agent)?.let {
            _slots.value = _slots.value + (agent to it)
        }
        viewModelScope.launch {
            val v = vault(agent) ?: return@launch
            // null = exec failed (SSH hiccup) → keep the cached accounts shown.
            var list = v.listSlots() ?: return@launch
            // First time only: if logged in but nothing saved yet, snapshot the
            // CURRENT login so it shows as an account — no "save" button.
            if (list.isEmpty()) {
                val method = _statuses.value?.get(agent)?.activeMethod
                if (_statuses.value?.get(agent)?.loggedIn == true &&
                    ai.eight24family.conch.agent.CredentialVault.isSlottable(method)
                ) {
                    val id = v.captureLive(java.util.UUID.randomUUID().toString(), method!!, "Account 1")
                    if (id != null) {
                        ServiceLocator.authMethodStore.setActiveSlot(serverId, agent, id)
                        _activeSlots.value = _activeSlots.value + (agent to id)
                        list = v.listSlots() ?: list
                    }
                }
            }
            // Cache + publish the authoritative list (incl. genuinely-empty so
            // a real log-out reflects; transient SSH fails returned above).
            ai.eight24family.conch.agent.CredentialVault.cacheSlots(serverId, agent, list)
            _slots.value = _slots.value + (agent to list)
            ServiceLocator.authMethodStore.activeSlot(serverId, agent)?.let {
                _activeSlots.value = _activeSlots.value + (agent to it)
            }
        }
    }

    // ── Add-account flow (method picker → login → name) ──
    /** Agent for which an "add account" login is in progress (so a login
     *  success triggers the name prompt instead of being treated as a plain
     *  re-login). */
    private var pendingAddAccount: Agent? = null

    /** "[ + add account ]" — run the normal login flow (method picker +
     *  authorize); on success onLoginSuccess auto-captures it as the next
     *  ordinal (no name prompt). */
    fun addAccount(agent: Agent) {
        pendingAddAccount = agent
        _methodSheetAgent.value = null
        startLogin(agent)
    }

    /** Called by the login coordinators on a successful login. NEVER prompts for
     * a name (auto-assigns the next ordinal — the user renames later via the pencil
     * if they want) and NEVER opens the accounts sheet: the login just needs to
     * become a live, ready account as fast as possible. */
    suspend fun onLoginSuccess(agent: Agent) {
        val wasAddFlow = pendingAddAccount == agent
        pendingAddAccount = null
        // A different account just became live — whatever limits were remembered
        // belong to the previous one. Drop them; the run-state probe below (and
        // the next chat open) fetch THIS account's numbers.
        ai.eight24family.conch.agent.UsageProbe.forget(serverId, agent)
        val v = vault(agent) ?: return
        val existing = v.listSlots()
        val method = _statuses.value?.get(agent)?.activeMethod ?: "oauth"
        val slottable = ai.eight24family.conch.agent.CredentialVault.isSlottable(method)
        // Capture the just-logged-in account as a fresh slot when it's a NEW
        // one: an explicit "+ add account", or a plain [ log in ] on a server
        // that had no accounts yet (Account 1). A plain re-login of an
        // already-saved account does NOT spawn a duplicate.
        val shouldCapture = slottable && (wasAddFlow || existing?.isEmpty() == true)
        if (shouldCapture) {
            val n = (existing?.size ?: 0) + 1
            val id = v.captureLive(java.util.UUID.randomUUID().toString(), method, "Account $n")
            if (id != null) {
                ServiceLocator.authMethodStore.setActiveSlot(serverId, agent, id)
                _activeSlots.value = _activeSlots.value + (agent to id)
            }
        }
        refreshSlots(agent)
        // Show the concrete current step in the still-open sign-in window (small
        // log): now we're verifying the subscription.
        _loginRequest.value = _loginRequest.value?.let { cur ->
            cur.copy(submitted = true, rawTail = appendLoginStep(cur.rawTail, "Checking your subscription"))
        }
        // AWAIT the single-agent run-state probe INSIDE the login window — Claude's
        // subscription check rides the same probe. The caller (login coordinator)
        // holds the animation up until this returns, then closes it, so the row is
        // ALREADY [ ready ]/[ no subscription ] with NO post-close refresh spinner.
        refreshCoord.invalidateLiveAuth(agent)
        refreshCoord.reprobeAgentQuiet(agent).join()
    }

    // ── Rename an account (pencil) ──
    data class RenameReq(
        val agent: Agent,
        val slotId: String,
        val method: String,
        val createdAt: Long,
        val current: String,
    )
    private val _renamePrompt = MutableStateFlow<RenameReq?>(null)
    val renamePrompt: StateFlow<RenameReq?> = _renamePrompt.asStateFlow()

    fun openRename(agent: Agent, slot: ai.eight24family.conch.agent.CredentialVault.Slot) {
        _renamePrompt.value = RenameReq(agent, slot.id, slot.method, slot.createdAt, slot.label)
    }
    fun cancelRename() { _renamePrompt.value = null }
    fun confirmRename(name: String) {
        val r = _renamePrompt.value ?: return
        _renamePrompt.value = null
        viewModelScope.launch {
            vault(r.agent)?.rename(r.slotId, r.method, r.createdAt, name.trim().ifBlank { r.current })
            refreshSlots(r.agent)
        }
    }

    /** Snapshot the currently-logged-in account into a new named slot. */
    fun saveCurrentLogin(agent: Agent) {
        viewModelScope.launch {
            val v = vault(agent) ?: return@launch
            val method = _statuses.value?.get(agent)?.activeMethod ?: "oauth"
            val n = (_slots.value[agent]?.size ?: 0) + 1
            val slot = v.captureLive(java.util.UUID.randomUUID().toString(), method, "Account $n")
            if (slot != null) {
                ServiceLocator.authMethodStore.setActiveSlot(serverId, agent, slot)
                _activeSlots.value = _activeSlots.value + (agent to slot)
            }
            refreshSlots(agent)
        }
    }

    /** Switch the active account — copy this slot's creds into the CLI's live path. */
    fun activateSlot(agent: Agent, slotId: String) {
        if (_accountOpBusy.value != null) return
        _accountOpBusy.value = slotId
        _accountOpError.value = null
        viewModelScope.launch {
            try {
            val v = vault(agent) ?: run {
                _accountOpError.value = "No connection to this server — connect it, then switch."
                return@launch
            }
            if (v.activate(slotId)) {
                ServiceLocator.authMethodStore.setActiveSlot(serverId, agent, slotId)
                _activeSlots.value = _activeSlots.value + (agent to slotId)
                // The limits on record belong to the account we just switched
                // AWAY from — showing them under the new one is a lie.
                ai.eight24family.conch.agent.UsageProbe.forget(serverId, agent)
                // Live CLI processes still carry the OLD account in their env —
                // close them; the next send relaunches under the new one.
                ServiceLocator.agentSessions.closeAllFor(serverId, agent)
                reverifyAgentQuiet(agent)   // switched account → quiet single-agent re-check
            } else {
                _accountOpError.value = "Couldn't switch — the server didn't confirm. Check the connection and try again."
            }
            } finally { _accountOpBusy.value = null }
        }
    }

    /** Log out / forget a saved account (wipes the live creds too if active).
     * No full refresh — reflect exactly what's left: if the removed account was
     * active AND another remains, FAIL OVER to it (so the server stays usable and
     * we show THAT account's status); */
    fun removeSlot(agent: Agent, slotId: String) {
        if (_accountOpBusy.value != null) return
        _accountOpBusy.value = slotId
        _accountOpError.value = null
        viewModelScope.launch {
            try {
            val v = vault(agent) ?: run {
                _accountOpError.value = "No connection to this server — connect it, then remove the account."
                return@launch
            }
            val wasActive = ServiceLocator.authMethodStore.activeSlot(serverId, agent) == slotId
            if (!v.remove(slotId, clearLiveIfActive = wasActive)) {
                _accountOpError.value = "Couldn't remove — the server didn't confirm. Check the connection and try again."
                return@launch
            }
            if (wasActive) {
                // The removed account owned the remembered limits — drop them so
                // nothing (chat bar included) keeps quoting a deleted login.
                ai.eight24family.conch.agent.UsageProbe.forget(serverId, agent)
                // And its live CLI processes: they hold the removed credentials
                // in their env and keep running (a 4h20m zombie turn survived a
                // logout, 2026-08-18). vault.remove above also pkills headless
                // turns server-side; this closes OUR app-side sessions cleanly.
                ServiceLocator.agentSessions.closeAllFor(serverId, agent)
                // remove() already wiped the live creds → make another saved
                // account live, or go logged-out if none. NO probe afterwards —
                // we already KNOW the outcome, so set the row directly (user:).
                val next = v.listSlots().orEmpty().firstOrNull()
                if (next != null && v.activate(next.id)) {
                    ServiceLocator.authMethodStore.setActiveSlot(serverId, agent, next.id)
                    _activeSlots.value = _activeSlots.value + (agent to next.id)
                    applyLocalAuthStatus(agent, loggedIn = true)
                } else {
                    ServiceLocator.authMethodStore.setActiveSlot(serverId, agent, null)
                    _activeSlots.value = _activeSlots.value - agent
                    applyLocalAuthStatus(agent, loggedIn = false)
                }
            }
            // (Removing a NON-active slot leaves the live login untouched — the
            // row's status is already correct, nothing to change.)
            refreshSlots(agent)
            } finally { _accountOpBusy.value = null }
        }
    }

    /** Set [agent]'s row from what we KNOW after an account op, with NO network
     *  probe. `loggedIn=true` (failover to a saved account) → optimistically
     *  ready — its own creds are now live; a real block surfaces when the chat
     *  opens. `loggedIn=false` (no accounts left) → logged out ("[ log in ]").
     *  Writes both the in-memory row and the cache so chat/home agree. */
    private fun applyLocalAuthStatus(agent: Agent, loggedIn: Boolean) {
        val cur = _statuses.value ?: return
        val st = cur[agent] ?: return
        val updated = if (loggedIn) {
            st.copy(
                loggedIn = true,
                methods = st.methods.ifEmpty { setOf("oauth") },
                liveAuthPending = false,
                claudeState = if (agent == Agent.CLAUDE)
                    ai.eight24family.conch.agent.ClaudeRunState.OK else st.claudeState,
                claudeStateData = null,
            )
        } else {
            st.copy(
                loggedIn = false,
                methods = emptySet(),
                activeMethod = null,
                liveAuthPending = false,
                claudeState = null,
                claudeStateData = null,
            )
        }
        val merged = cur + (agent to updated)
        _statuses.value = merged
        viewModelScope.launch { ServiceLocator.agentStatusCache.save(serverId, merged) }
    }

    /** Switch the active auth method for [agent] on this server. Applies to
     *  NEW chats (bound at chat start); existing chats keep their own method. */
    fun setActiveMethod(agent: Agent, methodKey: String) {
        viewModelScope.launch {
            ServiceLocator.authMethodStore.setActiveMethod(serverId, agent, methodKey)
            _chosenMethods.value = _chosenMethods.value + (agent to methodKey)
        }
        _methodSheetAgent.value = null
    }

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    /** True ONLY while a user-triggered refresh (pull-to-refresh) is
     * in flight. The top "refreshing…" bar reads this, NOT [probing].
     * Background refreshes (init, ON_RESUME watchdog, post-login,
     * cache-miss prefill) keep this false — the corner spinner does
     * the talking. User asked for this explicitly:. */
    private val _userRefreshing = MutableStateFlow(false)
    val userRefreshing: StateFlow<Boolean> = _userRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Detailed connectivity diagnosis surfaced only when the pre-flight
     *  TCP probe fails. Drives `DiagnosisCard` in [AgentPickerScreen] —
     *  replaces the single-line `_error` with a structured explanation
     *  the user can act on (typo? server down? wrong port?). Null on
     *  the happy path; null while diagnostic is still running. */
    private val _diagnosis = MutableStateFlow<ai.eight24family.conch.ssh.ServerDiagnostics.Diagnosis?>(null)
    val diagnosis: StateFlow<ai.eight24family.conch.ssh.ServerDiagnostics.Diagnosis?> = _diagnosis.asStateFlow()

    /** Which agent is currently being installed via the pool client.
     *  Null when no install is in flight. Drives the per-row spinner +
     *  disables the other `[ install ]` buttons during one install.
     *
     *  Per user policy: there are EXACTLY TWO visible states per agent:
     *  `[ install ]` button (CLI missing) and "logged out" text (CLI
     *  present but unauthorised). Install failure does NOT surface an
     *  error — the button just stays put for retry. The intermediate
     *  spinner is the only "in-flight" UI. */
    // Agents installing/updating ON THIS SERVER, mirrored from the process-
    // scoped AgentInstallManager. SET-based → updates run in PARALLEL; the
    // mirror survives this screen being recreated, so re-entering the picker
    // mid-update shows the spinners still going.
    private val _installing = MutableStateFlow<Set<Agent>>(emptySet())
    val installing: StateFlow<Set<Agent>> = _installing.asStateFlow()

    /** Live stdout tail per currently-installing agent on this server. */
    private val _installOutput = MutableStateFlow<Map<Agent, String>>(emptyMap())
    val installOutput: StateFlow<Map<Agent, String>> = _installOutput.asStateFlow()

    /** What each in-flight agent is DOING (install/update/remove) — so the row
     *  badge says the right verb ("[ removing ]" not "[ installing ]"). */
    private val _installOp = MutableStateFlow<Map<Agent, ai.eight24family.conch.agent.InstallOp>>(emptyMap())
    val installOp: StateFlow<Map<Agent, ai.eight24family.conch.agent.InstallOp>> = _installOp.asStateFlow()

    /** Parse an [AgentInstallManager] key ("serverId AGENT") to an [Agent],
     *  but only for THIS server (else null). */
    private fun parseInstallKey(key: String): Agent? {
        if (key.substringBeforeLast(' ') != serverId) return null
        val name = key.substringAfterLast(' ')
        return Agent.entries.firstOrNull { it.name == name }
    }

    /** In-flight OAuth login session — drives a modal dialog showing
     *  the URL + code the user needs to enter in a browser. Cleared
     *  when the credential file appears server-side.
     *
     *  @property awaitingPaste true once the CLI has printed a "paste
     *    the code from the browser" prompt to stdout. The UI surfaces
     *    a second text field + Submit button; on submit we shovel the
     *    pasted code into the CLI's stdin via [submitOAuthCode]. This
     *    is the OOB device-code flow that Claude's setup-token uses
     *    (and Codex's `codex login --no-browser` — same shape). */
    data class LoginRequest(
        val agent: Agent,
        /** Which server this login belongs to. The flow behind the dialog is
         *  process-global ([activeLogin]) while a picker panel exists PER
         *  SERVER (the Agents overview embeds one VM per server) — so every
         *  panel used to render its own copy of the dialog and the TOPMOST
         *  one, owned by a VM that never started any login, swallowed the
         *  taps: submit hit its null stdin handle and did nothing (measured
         *  on the phone 2026-08-18, twice: prompt 21:24:10, submit 21:24:29,
         *  `stdin=NULL`). Panels must render the dialog ONLY for their own
         *  server. */
        val serverId: String,
        val url: String?,
        val code: String?,
        val rawTail: String,
        val awaitingPaste: Boolean = false,
        /** **moltbot pattern (Codex).** True when the dialog should ask
         *  the user to paste a *callback URL* (the full URL their
         *  browser landed on after sign-in, ending in
         *  `localhost:1455/auth/callback?code=…&state=…`) rather than
         *  a typed OAuth code. We then `curl` that URL on the server
         *  via the pooled SSH so the CLI's listener finally completes
         *  the exchange. */
        val callbackMode: Boolean = false,
        /** Set true the moment the user clicks Submit on the paste
         *  field. From this point on the dialog only shows a "wrapping
         *  up" spinner — the URL / open-in-browser button / paste
         *  field are all hidden. They were useful when the user still
         *  needed to act; once we have their input the screen would
         *  otherwise just have stale controls cluttering the view. */
        val submitted: Boolean = false,
        /** Set when the CLI itself refused the sign-in for a reason the user
         *  can't fix by retrying — e.g. Google's "We can't connect to Gemini
         *  Code Assist for individuals" (region / account not eligible for the
         *  free tier). The dialog then shows this message + an "[ use API key ]"
         *  affordance instead of a dead URL/paste field. */
        val fatalError: String? = null,
        /**
         * The flow is NOT progressing and will not on its own - it needs
         * something from the user first (today: a connection to this server).
         *
         * ⚠ It exists because the dialog's placeholder for "no url and no code
         * yet" is a SPINNER plus the words "Starting OAuth flow…", and that text
         * is a lie in this state: the login had already given up and written the
         * reason into [rawTail], while the screen kept promising progress
         * (2026-08-18). Distinct from [fatalError], which is a dead end offering
         * the API-key path; this one is retryable and says so.
         */
        val stalled: Boolean = false,
    )
    // Process-global (companion [activeLogin]) so MainActivity can see an
    // in-flight OAuth login WITHOUT a VM handle — it gates Picture-in-Picture
    // on browser-leave (the login proc must stay alive while the user is in the
    // browser) and renders the PiP login panel. There is only ever ONE login at
    // a time (a single SSH login proc), so a shared flow is correct; the login
    // coroutine lives in this VM's scope, so a VM teardown cancels it and the
    // finally clears the flow.
    private val _loginRequest = activeLogin
    val loginRequest: StateFlow<LoginRequest?> = _loginRequest.asStateFlow()

    /** When non-null, render the "API key or OAuth?" chooser sheet
     *  for this agent. Tapping a choice drives the next state. */
    private val _loginPicker = MutableStateFlow<Agent?>(null)
    val loginPicker: StateFlow<Agent?> = _loginPicker.asStateFlow()

    /** When non-null, render the API-key text input dialog. */
    private val _apiKeyEntry = MutableStateFlow<Agent?>(null)
    val apiKeyEntry: StateFlow<Agent?> = _apiKeyEntry.asStateFlow()

    /** Unix-millis of the LAST successful probe — drives the "checked X ago" UI string. */
    private val _lastCheckedAt = MutableStateFlow<Long?>(null)
    val lastCheckedAt: StateFlow<Long?> = _lastCheckedAt.asStateFlow()

    /** "WINDOWS" when the OS pre-probe identified a Windows OpenSSH server —
     *  the rows then say so instead of a misleading "not installed" (honest
     *  detection only; PowerShell discovery is out of scope, 2026-08-17). */
    val serverOs: StateFlow<String?> = ServiceLocator.agentStatusCache
        .observeServerOs(serverId)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /**
     * Surfaced when pull-to-refresh / first-open fires against a FIDO-keyed
     * server AND we don't have any alive client / AgentSession to ride.
     * Holds the credential metadata the screen needs to drive the touch
     * dialog. The dialog runs the actual sign + probe inline (USB / NFC
     * withNfc-callback) via [runProbeWithSigner]. Cleared by
     * [cancelSkRefresh] or automatically after a successful run.
     */
    data class SkTouchRequest(
        val transport: ai.eight24family.conch.domain.SecurityKeyTransport,
        val application: String,
        val credentialIdBase64: String,
        val serverName: String?,
    )
    private val _skTouchRequest = MutableStateFlow<SkTouchRequest?>(null)
    val skTouchRequest: StateFlow<SkTouchRequest?> = _skTouchRequest.asStateFlow()

    /**
     * True once we have *confirmed* SSH auth on this server — either via a
     * pre-existing pool client or a successful tap-to-connect cycle.
     * The screen blocks the agent grid behind this so the user can't
     * accidentally tap an agent while the SK touch dialog is up or after
     * a failed auth.
     */
    private val _authConfirmed = MutableStateFlow(false)
    val authConfirmed: StateFlow<Boolean> = _authConfirmed.asStateFlow()

    /** True if the underlying server uses a hardware security key — drives
     *  the "block agent list until auth confirmed" gate. Software-keyed
     *  servers don't need it: their auth is fire-and-forget at probe time. */
    // Pessimistic default: assume SK until we've actually read the
    // server's secrets and confirmed otherwise. User complaint:.
    // Defaulting to `true` keeps the list hidden until `refresh()`
    // either confirms auth or confirms the server isn't SK in the
    // first place.
    private val _isSkServer = MutableStateFlow(true)
    val isSkServer: StateFlow<Boolean> = _isSkServer.asStateFlow()

    /** True until the FIRST status probe completes after this VM was
     *  created on a freshly-established SSH transport. While false,
     *  the UI shows `[ checking ]` for every agent and disables row
     *  taps — cached statuses from a previous app run are stale.
     *
     * Initialised true ONLY when the pool's connection for this
     * server is older than 5 s when the VM mounts: in that case the
     * user is RETURNING to AgentPicker over an already-live SSH
     * (came back from a chat, for example) and the cached statuses
     * are trusted enough to render immediately — the background
     * refresh updates them silently. User's rule:. */
    private val _firstProbeDone = MutableStateFlow(
        run {
            val ts = ServiceLocator.sshConnectionPool.connectedAt(serverId) ?: return@run false
            (System.currentTimeMillis() - ts) > 5_000
        }
    )
    val firstProbeDone: StateFlow<Boolean> = _firstProbeDone.asStateFlow()

    // ─── Helpers ────────────────────────────────────────────────────

    private val refreshCoord = AgentPickerViewModelRefresh(
        scope = viewModelScope,
        serverId = serverId,
        repo = repo,
        probe = probeApi,
        cache = cache,
        serverMut = _server,
        statusesMut = _statuses,
        probingMut = _probing,
        userRefreshingMut = _userRefreshing,
        errorMut = _error,
        diagnosisMut = _diagnosis,
        lastCheckedAtMut = _lastCheckedAt,
        firstProbeDoneMut = _firstProbeDone,
        isSkServerMut = _isSkServer,
        authConfirmedMut = _authConfirmed,
        skTouchRequestMut = _skTouchRequest,
    )

    private val installCoord = AgentPickerViewModelInstall(
        serverId = serverId,
        statusesRead = { _statuses.value },
    )

    init {
        // Mirror the process-scoped install manager into this screen's
        // per-server flows: live progress + survives re-entry. The manager
        // owns execution (parallel, background) + the post-install cache
        // refresh; we just project its global state down to this server.
        viewModelScope.launch {
            var previousAgents = emptySet<Agent>()
            ai.eight24family.conch.agent.AgentInstallManager.installing.collect { keys ->
                val mapped = keys.mapNotNull(::parseInstallKey).toSet()
                _installing.value = mapped
                // When an agent LEAVES the in-flight set, AgentInstallManager just
                // finished its install + wrote the fresh status (with the new
                // version + cleared updateAvailable) to AgentStatusCache. The VM's
                // in-memory `_statuses` was the OLD value, so the row would keep
                // showing "X → Y · update" forever. Reload from cache now so the
                // row flips to "ready · <new>" without waiting for a refresh().
                val finished = previousAgents - mapped
                if (finished.isNotEmpty()) {
                    launch {
                        val cached = cache.load(serverId)
                        if (cached.statuses.isNotEmpty()) {
                            _statuses.value = cached.statuses
                            _lastCheckedAt.value = cached.lastCheckedAt
                        }
                    }
                }
                previousAgents = mapped
            }
        }
        viewModelScope.launch {
            ai.eight24family.conch.agent.AgentInstallManager.output.collect { m ->
                _installOutput.value = m.entries
                    .mapNotNull { (k, v) -> parseInstallKey(k)?.let { it to v } }
                    .toMap()
            }
        }
        viewModelScope.launch {
            ai.eight24family.conch.agent.AgentInstallManager.op.collect { m ->
                _installOp.value = m.entries
                    .mapNotNull { (k, v) -> parseInstallKey(k)?.let { it to v } }
                    .toMap()
            }
        }
        // Auto-clear the "offline · tap to refresh" banner + silently re-probe
        // the moment this server gets CONNECTED behind our back — e.g. the
        // process-wide connectAllSeamlessSilently brought it up via the device
        // key while we were sitting on the cached (browse) view. The user must
        // never have to tap "refresh" when the app could connect itself.
        // force=true bypasses the login gate
        viewModelScope.launch {
            var wasConnected = ServiceLocator.sshConnectionPool.peek(serverId) != null
            ServiceLocator.sshConnectionPool.userHeldIds.collect { ids ->
                val nowConnected = serverId in ids ||
                    ServiceLocator.sshConnectionPool.peek(serverId) != null
                if (nowConnected && !wasConnected) {
                    if (_needsManualRefresh.value) _needsManualRefresh.value = false
                    // A transport just came up SILENTLY (device key / another
                    // screen's reconnect). If a security-key touch prompt is
                    // still standing for this server, it lost the race and is
                    // now asking for a tap NOBODY needs — the notification
                    // «Authenticate to 824» while 824 was already connected
                    // (2026-08-18). Retire it; the refresh below rides the
                    // live transport.
                    if (_skTouchRequest.value != null) refreshCoord.cancelSkRefresh()
                    refreshCoord.refresh(userTriggered = false, force = true)
                }
                wasConnected = nowConnected
            }
        }
    }

    private val oauthCoord = AgentPickerViewModelOAuth(
        scope = viewModelScope,
        serverId = serverId,
        loginRequestMut = _loginRequest,
        // force=true: this fires as the login COMPLETES (creds written), so it
        // must bypass the "skip refresh during login" gate in refreshCoord.
        refresh = { user -> refreshCoord.refresh(user, force = true) },
        doInstall = { agent, force -> installCoord.doInstall(agent, force) },
        shellEscape = ::shellEscape,
        onLoginSuccess = ::onLoginSuccess,
    )

    private val apiKeyCoord = AgentPickerViewModelApiKey(
        scope = viewModelScope,
        serverId = serverId,
        loginPickerMut = _loginPicker,
        apiKeyEntryMut = _apiKeyEntry,
        // force=true: post-login completion refresh, bypasses the login gate.
        refresh = { user -> refreshCoord.refresh(user, force = true) },
        startOAuthLogin = { agent -> oauthCoord.startOAuthLogin(agent) },
        shellEscape = ::shellEscape,
        onLoginSuccess = ::onLoginSuccess,
    )

    init {
        // Limits ride the connection, not the chat: whenever a probe reports an
        // agent logged in, warm its usage (over the pooled SSH) and publish the
        // brief line for the row. Logged-out agents lose their line. 60s
        // staleness gate so overlapping refreshes don't stack fetches.
        viewModelScope.launch {
            _statuses.collect { m ->
                (m ?: return@collect).forEach { (agent, st) ->
                    if (!st.installed || !st.loggedIn) {
                        _usageBrief.value = _usageBrief.value - agent
                        return@forEach
                    }
                    rebuildUsageBrief(agent)
                    val now = System.currentTimeMillis()
                    if (now - (usageWarmAt[agent] ?: 0L) < 60_000) return@forEach
                    usageWarmAt[agent] = now
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        ai.eight24family.conch.agent.UsageProbe.fetch(serverId, agent, fast = false)
                        rebuildUsageBrief(agent)
                    }
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            _server.value = repo.getById(serverId)
            val cached = cache.load(serverId)
            if (cached.statuses.isNotEmpty()) {
                // Show the cached state AS-IS — no "checking" flash on re-entry.
                // The background refresh below silently re-confirms over SSH and
                // updates a row ONLY if its status actually changed (e.g. a login
                // that's since broken flips ready→"log in"). The user's rule:
                // re-entry must be invisible — last-known status stays put while
                // we re-verify in the background, never a disturbing "checking".
                _statuses.value = cached.statuses
                _lastCheckedAt.value = cached.lastCheckedAt
                // Cache hit ⇒ rows render with their real install /
                // login / update state immediately, clickable. The
                // background refresh below still re-confirms over SSH
                // and updates rows silently when results land. Without
                // this flip, the _firstProbeDone initializer (which
                // only trusts a >5s-old live pool) leaves every row
                // showing "checking" and disables taps on cold open
                // even though we have perfectly good cached data.
                _firstProbeDone.value = true
            }
            // Tap = connect. Normally fire refresh on entry — for SK servers
            // without a live pool client this surfaces the touch dialog and
            // (after the tap) holds the SSH alive for the session.
            //
            // EXCEPTION — browse mode (Agents bottom-tab) + not connected: do
            // NOT auto-connect. The user opened the tab to GLANCE at cached
            // statuses, not to authenticate. Show what we cached + an
            // "offline · tap to refresh" hint; the key is only asked when they
            // explicitly refresh.
            val connected = ServiceLocator.sshConnectionPool.peek(serverId) != null
            when {
                browse && !connected -> {
                    // Overview glance — show cache AS-IS, no probe, no auto-connect.
                    // Mark "probed" + non-null statuses so the UI doesn't sit on
                    // "Checking server…" / "[ checking ]" forever; empty cache ⇒
                    // empty rows. A connection coming up (connectAllSeamlessSilently
                    // → userHeldIds) silently re-probes via the collector above.
                    if (_statuses.value == null) _statuses.value = emptyMap()
                    _firstProbeDone.value = true
                    _needsManualRefresh.value = true
                }
                // Already connected → silent re-probe over the live SSH (pooled
                // path, no touch). Background refresh: never surfaces a key.
                connected -> refresh()
                // browse=false + offline = the explicit per-server connect screen,
                // reached ONLY after re-attaching a key in Keychain ("Tap =
                // connect"). userTriggered=true so the freshly-added FIDO key
                // surfaces the touch on entry. EVERY other way into the picker is
                // browse=true (the overview), which never auto-FIDOs — so a plain
                // "tap an agent" never asks for the key (refresh() gates the touch
                // behind userTriggered now).
                else -> refresh(userTriggered = true)
            }
        }
    }

    // ─── Public surface — thin forwarders to the helpers ────────────

    /**
     * Forced re-probe.
     *
     * @param userTriggered `true` when called from a deliberate user
     *  gesture (pull-to-refresh) — the "refreshing…" bar always
     *  shows so the gesture has visible feedback. `false` (default)
     *  for automatic refreshes (init, ON_RESUME watchdog, post-login)
     *  — the bar shows ONLY when the cache is cold (first-ever
     *  visit), otherwise the probe runs silently in the background.
     */
    fun refresh(userTriggered: Boolean = false, showBar: Boolean = userTriggered): Job {
        // An explicit refresh (pull-to-refresh / offline-banner tap) clears the
        // offline hint and is allowed to connect (and surface the key).
        if (userTriggered) _needsManualRefresh.value = false
        return refreshCoord.refresh(userTriggered, showBar = showBar)
    }

    /** An account was just added / removed / switched for [agent]. Re-verify
     *  it and hold its badge on "checking" until the real status is known —
     *  never leave a stale "ready" standing (user's rule). We drop the cached
     *  live-auth verdict (so it can't be trusted), mark only this agent
     *  pending, then force a probe. Other agents keep their cached state, so
     *  only this row visibly re-checks. */
    /** QUIET single-agent re-check after an account add / remove / switch: probes
     * ONLY [agent] over the already-live channel and updates just its row — no
     * "refreshing…" bar, no whole-server re-probe, no "[ checking ]" flash on the
     * other agents. Claude's run-state rides the same probe so [ ready ] lands as
     * soon as the subscription check answers. The account-op paths use this
     * instead of a whole-server refresh. */
    private fun reverifyAgentQuiet(agent: Agent) {
        refreshCoord.invalidateLiveAuth(agent)
        refreshCoord.reprobeAgentQuiet(agent)
    }

    suspend fun runProbeWithSigner(
        signer: ai.eight24family.conch.ssh.securitykey.SkSigner,
    ) = refreshCoord.runProbeWithSigner(signer)

    fun cancelSkRefresh() = refreshCoord.cancelSkRefresh()

    /**
     * Install / update [agent]. The install runs on a LIVE pool client. So when
     * the server isn't connected, we first try a SILENT device-key reconnect (no
     * touch); if that returns null (no key / server rejected it), we kick
     * refresh() which raises the touch dialog for SK servers / connects directly
     * for non-SK, then we wait for the transport and fire the install. From the
     * connected path (which the picker's auto-connect-on-entry guarantees) it's
     * the unchanged fast path: straight to AgentInstallManager.
     */
    fun installAgent(agent: Agent) =
        ensureConnectedThenRun(agent, "install") { installCoord.installAgent(agent) }

    /**
     * Uninstall [agent]'s CLI from the server (accounts sheet → "remove from
     * server"). Closes the sheet, then runs the uninstall on a LIVE pool
     * client — same connect-first escalation as [installAgent]. Chat history
     * under `~/.<agent>/` is preserved (see buildUninstallScript). The post-run
     * re-probe flips the row to `[ install ]`.
     */
    fun removeAgent(agent: Agent) {
        _methodSheetAgent.value = null
        // The CLI (and with it the login) is going away — its limits go too,
        // and so do its live sessions (their processes die with the binary).
        ai.eight24family.conch.agent.UsageProbe.forget(serverId, agent)
        ServiceLocator.agentSessions.closeAllFor(serverId, agent)
        ensureConnectedThenRun(agent, "uninstall") { installCoord.uninstallAgent(agent) }
    }

    /**
     * Run [action] (install / update / uninstall) against a LIVE pool client.
     * AgentInstallManager silently skips when there's no transport, so when
     * the server isn't connected we connect FIRST: silent device-key reconnect
     * (no touch) → else refresh() which raises the touch dialog (SK) /
     * connects directly (non-SK) → wait for the transport → run. From the
     * connected path (picker auto-connects on entry) it's the unchanged fast
     * path: straight through.
     */
    private fun ensureConnectedThenRun(agent: Agent, what: String, action: () -> Unit) {
        val tag = "SshAi-InstallTap"
        if (ServiceLocator.sshConnectionPool.peek(serverId) != null) {
            android.util.Log.d(tag, "$what(${agent.name}@$serverId) connected=true → run direct")
            action()
            return
        }
        android.util.Log.d(tag, "$what(${agent.name}@$serverId) connected=false → connect first")
        viewModelScope.launch {
            val server = _server.value
                ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.getById(serverId) }
            // 1. Silent device-key reconnect (seamless servers — no touch).
            if (server != null) {
                val live = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ServiceLocator.sshConnectionPool.userConnectEphemeral(server) }.getOrNull()
                }
                if (live != null) {
                    android.util.Log.d(tag, "  → ephemeral connected, running $what")
                    action()
                    return@launch
                }
            }
            // 2. No device key (or it failed) — let refresh() drive the real
            //    connect (SK → inline touch dialog; non-SK → direct userConnect).
            refresh(userTriggered = true)
            // 3. Wait up to ~2 min for the connect to land, then run.
            var waited = 0
            while (ServiceLocator.sshConnectionPool.peek(serverId) == null && waited < 120_000) {
                kotlinx.coroutines.delay(300); waited += 300
            }
            if (ServiceLocator.sshConnectionPool.peek(serverId) != null) {
                android.util.Log.d(tag, "  → connect landed after ${waited}ms, running $what")
                action()
            } else {
                android.util.Log.w(tag, "  → connect did NOT land within 120s, $what ABANDONED")
            }
        }
    }

    fun startLogin(agent: Agent) = apiKeyCoord.startLogin(agent)
    fun cancelLoginPicker() = apiKeyCoord.cancelLoginPicker()
    fun chooseApiKey() = apiKeyCoord.chooseApiKey()
    fun cancelApiKeyEntry() = apiKeyCoord.cancelApiKeyEntry()
    /** Dead-end an OAuth attempt straight into the API-key input (closes the
     *  OAuth login first). For the "Google declined Code Assist → use a key"
     *  affordance in the login dialog. */
    fun switchToApiKey(agent: Agent) { cancelLogin(); apiKeyCoord.openEntry(agent) }
    fun chooseOAuth() = apiKeyCoord.chooseOAuth()
    fun submitApiKey(agent: Agent, key: String) = apiKeyCoord.submitApiKey(agent, key)

    fun startOAuthLogin(agent: Agent) = oauthCoord.startOAuthLogin(agent)
    fun submitCodexCallback(raw: String) = oauthCoord.submitCodexCallback(raw)
    fun submitOAuthCode(code: String, manual: Boolean) = oauthCoord.submitOAuthCode(code, manual)
    fun cancelLogin() = oauthCoord.cancelLogin()

    private fun shellEscape(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    /** Persist the picked agent as the server's "last used" agent. */
    fun rememberAgent(agent: Agent) {
        viewModelScope.launch { repo.updateAgent(serverId, agent) }
    }

    companion object {
        /** Process-global in-flight OAuth login. Backs [loginRequest] for the
         *  active picker AND is read directly by MainActivity (PiP gating +
         *  the PiP login panel) without a ViewModel handle. Null = no login. */
        val activeLogin = MutableStateFlow<LoginRequest?>(null)

        /**
         * Build a VM for a specific server OFF a nav route — so the same
         * per-server panel can be hosted inline (the Agents overview embeds one
         * [AgentPickerViewModel] per server via `viewModel(key = serverId, …)`).
         * The VM reads only "serverId" + "browse" from the handle, so a
         * hand-built [SavedStateHandle] is all it needs.
         */
        fun factory(serverId: String, browse: Boolean): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AgentPickerViewModel(
                        SavedStateHandle(mapOf("serverId" to serverId, "browse" to browse)),
                    )
                }
            }
    }
}
