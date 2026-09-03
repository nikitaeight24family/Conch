package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.agent.ServerStats
import ai.eight24family.conch.agent.ServerStatsProbe
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.SecurityKeyTransport
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.ssh.securitykey.SkSigner
import ai.eight24family.conch.util.ErrorMessages
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One server's **management page** (Server detail). The Servers tab is now
 * pure infrastructure management — tap a server → this page; agents and chats
 * live in their own tabs. This VM owns everything about ONE server entry:
 *
 *  - the [Server] itself, observed by id so an Edit reflects live;
 *  - [connected] — live SSH transport state (drives the dot + Connect/Disconnect);
 *  - [stats] / [probing] — live host load, probed ONLY while connected (no fresh
 *    handshake / SK touch just to render — same rule as the old HostInfoSheet);
 *  - the **connect / SK-touch flow** moved here verbatim from ServersViewModel.
 *    The Servers LIST no longer connects on tap; only this page connects, and
 *    only when the user explicitly hits Connect or Terminal — so no row tap can
 *    ever demand a key just to look around ("Checking server…" dead-end gone).
 *  - [disconnect] and [delete].
 */
class ServerDetailViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val repo = ServiceLocator.serverRepository
    private val serverId: String = savedStateHandle.get<String>("serverId").orEmpty()

    /** The one server, observed so Edit reflects without leaving the page. */
    val server: StateFlow<Server?> = repo.observeServers()
        .map { list -> list.firstOrNull { it.id == serverId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _connected = MutableStateFlow(ServiceLocator.sshConnectionPool.peek(serverId) != null)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** User wants this server connected but the transport is down right now
     *  (network blip / FIDO needs a re-tap). Drives the amber dot + the
     *  "reconnect" button label so a dropped session reads as recoverable. */
    private val _reconnectPending = MutableStateFlow(false)
    val reconnectPending: StateFlow<Boolean> = _reconnectPending.asStateFlow()

    private val _stats = MutableStateFlow<ServerStats?>(null)
    val stats: StateFlow<ServerStats?> = _stats.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    init {
        // Connection dot: pool.peek is lock-free; tick so a silently-dropped
        // transport darkens the dot without a row tap.
        viewModelScope.launch {
            while (true) {
                val live = ServiceLocator.sshConnectionPool.peek(serverId) != null
                val held = ServiceLocator.sshConnectionPool.userHeldIds.value.contains(serverId)
                _connected.value = live
                _reconnectPending.value = held && !live
                delay(2_000)
            }
        }
        // Live host stats — only while a pooled client exists. Never opens a
        // fresh handshake (SK touch cost would be insane for a status panel).
        viewModelScope.launch {
            val probe = ServerStatsProbe(ServiceLocator.sshClient)
            while (true) {
                val client = ServiceLocator.sshConnectionPool.peek(serverId)
                if (client == null) {
                    _stats.value = null
                    delay(2_000)
                    continue
                }
                _probing.value = true
                withContext(Dispatchers.IO) {
                    probe.probe { cmd ->
                        SilentlyTry.logged("Conch-ServerDetail", "exec host info probe") {
                            val sess = client.startSession()
                            try {
                                val proc = sess.exec(cmd)
                                val out = java.io.ByteArrayOutputStream()
                                // Bounded read: the deadline wraps the READ, not the join after it.
                                ai.eight24family.conch.ssh.BoundedExec.drain(
                                    proc, out,
                                    deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.INTERACTIVE_MS,
                                    maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.INTERACTIVE,
                                )
                                proc.join(20, java.util.concurrent.TimeUnit.SECONDS)
                                String(out.toByteArray(), Charsets.UTF_8)
                            } finally {
                                SilentlyTry.fired("Conch-ServerDetail", "close host info session") { sess.close() }
                            }
                        }
                    }.onSuccess { _stats.value = it }
                }
                _probing.value = false
                delay(5_000)
            }
        }
    }

    // ─────────────────────── connect (moved from ServersViewModel) ──────────
    //
    // Only THIS page connects now. Password/soft-key servers connect silently
    // when the user hits Connect; SK servers surface the touch dialog. On
    // success [connectedTo] emits — the screen uses it to route into Terminal
    // when the user tapped Terminal-while-offline.

    data class SkTouchRequest(
        val serverId: String,
        val serverName: String,
        val transport: SecurityKeyTransport,
        val application: String,
        val credentialIdBase64: String,
        val retry: Boolean = false,
    )

    private val _skTouchRequest = MutableStateFlow<SkTouchRequest?>(null)
    val skTouchRequest: StateFlow<SkTouchRequest?> = _skTouchRequest.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    /** Emits this server's id when its SSH transport just came up. */
    private val _connectedTo = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val connectedTo: SharedFlow<String> = _connectedTo.asSharedFlow()

    private var pendingServer: Server? = null
    private var pendingSecrets: ServerSecrets? = null

    /** Connect button / Terminal-while-offline entry point. Idempotent. */
    fun connect() {
        if (_connecting.value) return
        // Already alive? Emit success immediately (lets Terminal route through).
        if (ServiceLocator.sshConnectionPool.peek(serverId) != null) {
            _connected.value = true
            _connectedTo.tryEmit(serverId)
            return
        }
        _connecting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val srv = repo.getById(serverId)
                if (srv == null) {
                    _connectError.value = "Server not found"
                    _connecting.value = false
                    return@launch
                }
                val secrets = repo.getSecrets(serverId)
                val skPrimary = secrets.skKeys.firstOrNull()
                if (skPrimary != null) {
                    // SEAMLESS FIRST: if a device key is enrolled, reconnect
                    // SILENTLY over it — NO physical key tap. A still-valid,
                    // on-server device key must NEVER re-prompt the physical key.
                    // Mirrors the picker's refresh path; this connect() was the one
                    // place that skipped it and always demanded a tap.
                    val eph = runCatching {
                        ServiceLocator.sshConnectionPool.userConnectEphemeral(srv)
                    }.getOrNull()
                    if (eph != null) {
                        _connected.value = true
                        _connectedTo.tryEmit(serverId)
                        _connecting.value = false
                        return@launch
                    }
                    val info = skPrimary.securityInfo
                    if (info == null) {
                        _connectError.value = "Security-key row missing handle — re-add the key in Keychain."
                        _connecting.value = false
                        return@launch
                    }
                    pendingServer = srv
                    pendingSecrets = secrets
                    _skTouchRequest.value = SkTouchRequest(
                        serverId = serverId,
                        serverName = srv.name,
                        transport = SecurityKeyTransport.EITHER,
                        application = info.application,
                        credentialIdBase64 = info.credentialIdBase64,
                    )
                    // _connecting stays true; the dialog now owns in-flight state.
                } else {
                    try {
                        ServiceLocator.sshConnectionPool.userConnect(srv, secrets, null)
                        _connected.value = true
                        _connectedTo.tryEmit(serverId)
                    } catch (t: Throwable) {
                        // Humanized, WHOLE: the dialog already titles itself
                        // "Connect failed", and take(120) cut the host-key
                        // sentence off at "Expect" — right before the
                        // fingerprints and the way out (owner's phone,
                        // 2026-08-31).
                        _connectError.value = ErrorMessages.humanize(t)
                    } finally {
                        _connecting.value = false
                    }
                }
            } catch (t: Throwable) {
                _connectError.value = ErrorMessages.humanize(t)
                _connecting.value = false
            }
        }
    }

    /** SkInlineTouchDialog produced a working signer → run the real connect. */
    suspend fun runConnectWithSigner(signer: SkSigner) {
        val srv = pendingServer
        val secrets = pendingSecrets
        if (srv == null || secrets == null) {
            _skTouchRequest.value = null
            _connecting.value = false
            return
        }
        try {
            withContext(Dispatchers.IO) {
                ServiceLocator.sshConnectionPool.userConnect(srv, secrets, signer)
            }
            _connected.value = true
            _connectedTo.tryEmit(srv.id)
        } catch (t: Throwable) {
            val msg = (t.message ?: "").lowercase()
            // Connect already landed but the dialog's scope died at the tail —
            // it WAS a success if the pool now holds a live transport.
            if (ServiceLocator.sshConnectionPool.peek(srv.id) != null) {
                _connected.value = true
                _connectedTo.tryEmit(srv.id)
                _skTouchRequest.value = null
                _connecting.value = false
                pendingServer = null
                pendingSecrets = null
                return
            }
            val isUserAbort = listOf(
                "user pressed cancel",
                "user cancelled",
                "user canceled",
                "dialog dismissed",
                "dialog cancelled",
                "dialog canceled",
                "opening keychain",
                "no nfc tap detected",
                "never reported credentials",
                "left the composition",
            ).any { it in msg }
            val isTagFumble = !isUserAbort && listOf(
                "taglost",
                "tag lost",
                "tag is out of date",
                "pin token request failed",
                "ctap reset",
            ).any { it in msg }
            when {
                isUserAbort -> { /* silent — no error, no retry */ }
                isTagFumble -> {
                    // Re-throw so the dialog's own state machine drives a
                    // visible retry (auto-bumps attempt → fresh signer + re-arm).
                    ServiceLocator.cachedSkDialogSigner = null
                    throw t
                }
                else -> {
                    _connectError.value = ErrorMessages.humanize(t)
                }
            }
        }
        _skTouchRequest.value = null
        _connecting.value = false
        pendingServer = null
        pendingSecrets = null
    }

    /** SK dialog cancel — abort the pending connect. */
    fun cancelConnect() {
        _skTouchRequest.value = null
        _connecting.value = false
        pendingServer = null
        pendingSecrets = null
    }

    fun consumeConnectError() {
        _connectError.value = null
    }

    /** Drop the live transport + any held sessions/shell for this server. */
    /** Set this server's accent colour (`#RRGGBB`), or roll a fresh random one
     *  when [hex] is null. The colour is what identifies the server by eye
     *  across the app, so it writes through a single-field repository update —
     *  no form round-trip, nothing else in the row can be clobbered. */
    fun setColorHex(hex: String?) {
        viewModelScope.launch {
            SilentlyTry.fired("Conch-ServerDetail", "save accent colour") {
                val next = hex ?: ai.eight24family.conch.ui.theme.ServerAccent.randomHex(
                    repo.observeServers().first()
                        .filter { it.id != serverId }
                        .map { it.colorHex },
                )
                repo.updateColorHex(serverId, next)
            }
        }
    }

    /** Rename the row — the phone's page uses this instead of the full edit
     * form. */
    fun rename(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        viewModelScope.launch {
            SilentlyTry.fired("Conch-ServerDetail", "rename server") {
                repo.rename(serverId, trimmed)
            }
        }
    }

    fun disconnect() {
        ServiceLocator.sshConnectionPool.userDisconnect(serverId)
        ServiceLocator.agentSessions.closeAllForServer(serverId)
        ServiceLocator.terminalSessions.closeForServer(serverId)
        _connected.value = false
        // Explicit user disconnect drops the intent — NOT a "pending reconnect".
        _reconnectPending.value = false
        _stats.value = null
    }

    // ─────────────────────── seamless reconnect (per-server) ───────────────
    // Moved out of global app Settings — it's a property of the server. Toggle,
    // lifetime, and the enrolled device key (fingerprint + live expiry + remove)
    // all live here now.
    private val prefs = ServiceLocator.preferences
    private val pool = ServiceLocator.sshConnectionPool

    /** Seamless reconnect ON for THIS server? */
    val seamlessEnabled: StateFlow<Boolean> = prefs.seamlessServers
        .map { serverId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** This server's device-key lifetime (days, default 7). */
    val seamlessDays: StateFlow<Int> = prefs.seamlessDaysByServer
        .map { (it[serverId] ?: 7).coerceIn(1, 30) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 7)

    /** This server's enrolled device key (public fingerprint + expiry), or null. */
    private val _deviceKey = MutableStateFlow<DeviceKeyEntry?>(null)
    val deviceKey: StateFlow<DeviceKeyEntry?> = _deviceKey.asStateFlow()

    /** Seamless reconnect only applies to FIDO/security-key servers (the
     *  "leave the key at home" workflow). Password/plain-key servers reconnect
     *  silently anyway, so the section is hidden for them. */
    private val _isSkServer = MutableStateFlow(false)
    val isSkServer: StateFlow<Boolean> = _isSkServer.asStateFlow()

    init {
        viewModelScope.launch {
            _isSkServer.value = withContext(Dispatchers.IO) {
                SilentlyTry.logged("Conch-ServerDetail", "load secrets for sk check") {
                    repo.getSecrets(serverId).skKeys.isNotEmpty()
                } ?: false
            }
            // One-time migration: a device key already exists (minted under the
            // old GLOBAL seamless setting) → treat this server as seamless-ON so
            // the per-server toggle reflects reality and the key keeps refreshing.
            if (ai.eight24family.conch.ssh.EphemeralSshKey.exists(serverId) &&
                serverId !in prefs.seamlessServers.first()
            ) {
                prefs.setSeamlessForServer(serverId, true)
            }
            refreshDeviceKey()
        }
        // Auto-refresh after a fresh connect: when this server transitions
        // INTO `userHeldIds` we know a userConnect just landed and the pool's
        // `maybeEnrollEphemeralAsync` is running. The enroll completes ~1s
        // later — re-read the local key state then so the "device key" row
        // stops saying "not created yet — connect & tap once" once it has
        // actually been minted+written. (Was bug: user tapped connect, FIDO
        // succeeded, enroll wrote the key, but the UI sat on its initial
        // null read forever — `refreshDeviceKey` only fired once in init.)
        // ⚠ THE EDGE IS NOT ENOUGH, AND THAT IS WHY THE ROW LIED.
        //
        // `wasHeld` starts from the CURRENT value, so when the screen is opened on
        // a server that is ALREADY held — which is what happens right after
        // tapping connect and signing in — the false→true transition never
        // arrives, this refresh never runs, and the row keeps showing its FIRST
        // read: the one taken before the enroll had written the key. The user then
        // sees "not created yet — connect & tap once" over a key that exists, and
        // the same screen tells them to do what they just did.
        //
        // So: keep the edge (it is the fast path right after an enroll) AND poll
        // while the answer can still change. The poll stops the moment a key is
        // found, so a server that genuinely has none costs one cheap local read
        // every few seconds while its screen is open, and nothing after.
        viewModelScope.launch {
            var tries = 0
            while (tries < DEVICE_KEY_RECHECK_TRIES) {
                delay(2_500)
                if (deviceKey.value != null) return@launch
                if (serverId !in pool.userHeldIds.value) { tries++; continue }
                tries++
                refreshDeviceKey()
            }
        }
        viewModelScope.launch {
            var wasHeld = serverId in pool.userHeldIds.value
            pool.userHeldIds.collect { ids ->
                val isHeldNow = serverId in ids
                if (isHeldNow && !wasHeld) {
                    // Wait past the async enroll, then refresh. 2s covers
                    // typical RTT (~600ms) + the bash strip+append round-trip.
                    delay(2_000)
                    refreshDeviceKey()
                }
                wasHeld = isHeldNow
            }
        }
    }

    fun refreshDeviceKey() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!ai.eight24family.conch.ssh.EphemeralSshKey.exists(serverId)) {
                    _deviceKey.value = null
                    return@withContext
                }
                val fp = ai.eight24family.conch.ssh.EphemeralSshKey.fingerprint(serverId) ?: "—"
                val expiry = prefs.deviceKeyExpiry.first()[serverId]
                _deviceKey.value = DeviceKeyEntry(serverId, server.value?.name ?: "server", fp, expiry)
            }
        }
    }

    /** Toggle seamless reconnect for this server. ON → mint + enroll the device
     *  key now (if connected; else on next connect); OFF → revoke it (strip from
     *  the server + delete locally + drop the silent hold). */
    /**
     * Forget this server's pinned host key — the next connect re-pins whatever
     * it meets (trust on first use).
     *
     * This is the ONLY way out of a host-key mismatch, which is a hard refusal:
     * the pool won't connect and the reconnect ladder deliberately won't retry.
     * The user reaches it after being told the key changed, so the screen must
     * make them confirm — "the server was rebuilt" and "someone is in the
     * middle" look identical from here, and only they know which it is.
     */
    fun forgetHostKey() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.forgetKnownHostKey(serverId) }
        }
    }

    fun setSeamless(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setSeamlessForServer(serverId, enabled)
            val srv = server.value ?: withContext(Dispatchers.IO) { repo.getById(serverId) }
            if (srv != null) {
                if (enabled) pool.enrollSeamlessForServer(srv) else pool.revokeDeviceKey(srv)
            }
            // Mint/revoke is async — reflect it shortly after.
            delay(if (enabled) 1500 else 400)
            refreshDeviceKey()
        }
    }

    fun setSeamlessDays(days: Int) {
        viewModelScope.launch {
            prefs.setSeamlessDaysForServer(serverId, days)
            // Apply NOW: re-mint the device key so its server-side expiry-time (and
            // the "expires in …" countdown) reflects the freshly-picked lifetime
            // instead of waiting for the next reconnect. enrollSeamlessForServer
            // rewrites only our own authorized_keys line and no-ops when seamless
            // is off / there's no live connection (it re-mints on the next touch).
            val srv = server.value ?: withContext(Dispatchers.IO) { repo.getById(serverId) }
            if (srv != null && seamlessEnabled.value) {
                pool.enrollSeamlessForServer(srv)
                delay(1500)
                refreshDeviceKey()
            }
        }
    }

    /** Revoke the current device key (strip from server + delete locally). Keeps
     *  the toggle ON — a later FIDO connect re-mints a fresh one. */
    fun removeDeviceKey() {
        viewModelScope.launch {
            val srv = server.value ?: withContext(Dispatchers.IO) { repo.getById(serverId) }
            if (srv != null) pool.revokeDeviceKey(srv)
            _deviceKey.value = null
        }
    }

    // ─────────────────────── phone bridge (conch-bridge on this server) ─────
    // Install / remove the conch-bridge helper on THIS server straight from the
    // server page, with a one-line log of what happened. No SSH by hand.
    /** Installed bridge version on this server; "?" = installed but unversioned
     *  (old copy); null = not installed (or not yet checked — see bridgeChecked). */
    private val _installedVersion = MutableStateFlow<String?>(null)
    val installedVersion: StateFlow<String?> = _installedVersion.asStateFlow()
    private val _bridgeChecked = MutableStateFlow(false)
    val bridgeChecked: StateFlow<Boolean> = _bridgeChecked.asStateFlow()
    private val _bridgeBusy = MutableStateFlow(false)
    val bridgeBusy: StateFlow<Boolean> = _bridgeBusy.asStateFlow()
    private val _bridgeLog = MutableStateFlow<String?>(null)
    val bridgeLog: StateFlow<String?> = _bridgeLog.asStateFlow()
    /** Version this app would install. */
    val bridgeAvailableVersion: String = ai.eight24family.conch.diagnostics.BridgeInstaller.bundledVersion

    /** Probe installed-state + version (meaningful only while connected).
     *  A connected server must ALWAYS resolve — a transient exec hiccup must
     *  never strand the UI on "checking…" with a dead, disabled button (the
     *  kind of "Checking server… dead-end" we already killed elsewhere). So:
     *  retry a couple of times, and if we still can't read it while connected,
     *  fall back to "not installed" — the button then works, Install is
     *  idempotent, and a successful Install re-checks and corrects the state. */
    /** The device's own row: nothing here is gated on a connection the owner
     *  would have to make, because there is none to make. */
    val isThisDevice: Boolean = serverId == ai.eight24family.conch.linux.LinuxSsh.SERVER_ID

    fun checkBridge() {
        viewModelScope.launch {
            // ⛔ ON THE OWN DEVICE, LOOKING AT THE PAGE IS ENOUGH. The rule that
            // a page must not dial protects someone's SERVER from a connection
            // they didn't ask for; this machine is the phone in their hand, and
            // leaving it down only produced "connect to this server to manage
            // the bridge" about the device showing the sentence (2026-09-03).
            if (isThisDevice) ServiceLocator.sshConnectionPool.ensureOwnDeviceUp()
            repeat(3) {
                val s = ai.eight24family.conch.diagnostics.BridgeInstaller.status(serverId)
                if (s != null) {
                    _installedVersion.value = if (s.installed) (s.version ?: "?") else null
                    _bridgeChecked.value = true
                    return@launch
                }
                // No live transport → leave unchecked; the screen shows
                // "connect…" and disables the button until reconnect.
                if (ServiceLocator.sshConnectionPool.peek(serverId) == null) return@launch
                delay(800)
            }
            _installedVersion.value = null
            _bridgeChecked.value = true
        }
    }

    fun installBridge() {
        if (_bridgeBusy.value) return
        viewModelScope.launch {
            _bridgeBusy.value = true
            val r = ai.eight24family.conch.diagnostics.BridgeInstaller.install(serverId)
            _bridgeLog.value = r.log
            _bridgeBusy.value = false
            if (r.success) checkBridge()
        }
    }

    fun removeBridge() {
        if (_bridgeBusy.value) return
        viewModelScope.launch {
            _bridgeBusy.value = true
            val r = ai.eight24family.conch.diagnostics.BridgeInstaller.uninstall(serverId)
            _bridgeLog.value = r.log
            _bridgeBusy.value = false
            if (r.success) checkBridge()
        }
    }

    fun clearBridgeLog() { _bridgeLog.value = null }

    /** Delete this server (credentials + sessions). Caller pops the page. */
    fun delete() {
        viewModelScope.launch { repo.delete(serverId) }
    }
    private companion object {
        /** How many times the device-key row re-reads its store while the screen
         *  is open. Enough to outlast an enroll that is slower than usual (a bad
         *  link, a sleepy box); it stops early the moment a key appears. */
        private const val DEVICE_KEY_RECHECK_TRIES = 8
    }

}
