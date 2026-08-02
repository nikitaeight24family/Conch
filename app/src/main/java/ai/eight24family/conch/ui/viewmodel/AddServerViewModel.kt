package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.AuthMethod
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.domain.SshKey
import ai.eight24family.conch.ssh.ConnectResult
import ai.eight24family.conch.ssh.FailureKind
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val fingerprint: String) : TestState
    data class UnknownHost(val fingerprint: String, val keyType: String) : TestState
    data class HostKeyMismatch(val expected: String, val actual: String) : TestState
    data class Failure(val reason: String, val kind: FailureKind) : TestState
}

data class AddServerForm(
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    /** Which CLI this server drives (Claude / Codex / Gemini). The user picks
     *  it when adding the server (no more silent Claude default); shown in the
     *  host-info sheet. Editing a server pre-fills it from the saved value. */
    val agent: Agent? = null,
    /** Flat list of ssh_key ids enrolled for this server. Order is
     *  cosmetic — sshj walks every entry sending pubkey-only test
     *  packets and the server picks. Empty for a fresh KEY-auth form. */
    val sshKeyIds: List<String> = emptyList(),
    /** Non-null when editing an existing row. */
    val editingId: String? = null,
)

class AddServerViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    private val repo = ServiceLocator.serverRepository
    private val keys = ServiceLocator.sshKeyRepository
    private val ssh = ServiceLocator.sshClient

    /**
     * `null` means "Room hasn't responded yet" — distinct from
     * `emptyList()` ("zero keys exist"). The screen's orphan-cleanup
     * `LaunchedEffect` must skip when this is null, otherwise it
     * mistakes the loading-state for "the user really has no keys"
     * and wipes the enrolled list on every Edit-server entry. That's
     * the bug behind "edited the server, hit Save, now it doesn't
     * ask for a key because sshKeyIds got nuked silently".
     */
    val availableKeys: StateFlow<List<SshKey>?> = keys.observeKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _form = MutableStateFlow(loadInitialForm())
    val form: StateFlow<AddServerForm> = _form.asStateFlow()

    val isEditing: Boolean get() = _form.value.editingId != null

    init {
        val editId = savedStateHandle.get<String>("serverId")
        if (editId != null && _form.value.editingId == null) {
            viewModelScope.launch {
                val server = repo.getById(editId) ?: return@launch
                _form.update {
                    it.copy(
                        name = server.name,
                        host = server.host,
                        port = server.port,
                        user = server.username,
                        authMethod = server.authMethod,
                        // No agent pre-select even when editing — the user picks it
                        // explicitly (no imposed default, per their ask).
                        sshKeyIds = server.sshKeyIds,
                        editingId = server.id,
                    )
                }
                persistForm()
            }
        }
    }

    fun updateName(v: String)       = mutate { it.copy(name = v) }
    fun updateHost(v: String)       = mutate { it.copy(host = v) }
    fun updatePort(v: Int)          = mutate { it.copy(port = v) }
    fun updateUser(v: String)       = mutate { it.copy(user = v) }
    fun updateAuthMethod(v: AuthMethod) = mutate { it.copy(authMethod = v) }
    fun updateAgent(v: Agent)           = mutate { it.copy(agent = v) }
    fun updatePassword(v: String) {
        _form.update { it.copy(password = v) }
    }

    /** Toggle a key in or out of the enrolled list. */
    fun toggleKey(keyId: String) = mutate {
        val cur = it.sshKeyIds
        val updated = if (keyId in cur) cur - keyId else cur + keyId
        it.copy(sshKeyIds = updated)
    }

    private fun mutate(transform: (AddServerForm) -> AddServerForm) {
        _form.update { transform(it) }
        persistForm()
    }

    private fun persistForm() {
        val f = _form.value
        savedStateHandle["form_name"] = f.name
        savedStateHandle["form_host"] = f.host
        savedStateHandle["form_port"] = f.port
        savedStateHandle["form_user"] = f.user
        savedStateHandle["form_auth"] = f.authMethod.name
        savedStateHandle["form_agent"] = f.agent?.name
        savedStateHandle["form_keyIds"] = f.sshKeyIds.joinToString(",")
    }

    private fun loadInitialForm(): AddServerForm = AddServerForm(
        name = savedStateHandle["form_name"] ?: "",
        // Prefill host/port from the "add user on this host" route args
        // (Routes.addUser) when there's no in-progress form yet.
        host = savedStateHandle["form_host"] ?: savedStateHandle.get<String>("host") ?: "",
        port = savedStateHandle["form_port"] ?: savedStateHandle.get<String>("port")?.toIntOrNull() ?: 22,
        user = savedStateHandle["form_user"] ?: "",
        authMethod = (savedStateHandle.get<String>("form_auth")
            ?.let { SilentlyTry.logged("SshAi-AddServer", "parse auth method") { AuthMethod.valueOf(it) } })
            ?: AuthMethod.PASSWORD,
        password = "",
        // No default agent — the user must pick one (no more silent Claude
        // default). Null until picked / pre-filled from a saved edit.
        agent = savedStateHandle.get<String>("form_agent")
            ?.let { SilentlyTry.logged("SshAi-AddServer", "parse agent") { Agent.valueOf(it) } },
        sshKeyIds = (savedStateHandle.get<String>("form_keyIds") ?: "")
            .split(",")
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } },
    )

    fun test() = run(_form.value, persist = false)
    fun save() {
        // Normalise the host BEFORE running so a pasted `ssh://root@host:22`
        // doesn't end up as a literal hostname that DNS can't resolve.
        // Also picks up a port from `host:port` suffix if the port field
        // hadn't been touched yet (still on its 22 default).
        val cur = _form.value
        val normalised = ai.eight24family.conch.ssh.HostNormalizer.normalize(cur.host)
        if (normalised.host != cur.host || (normalised.port != null && normalised.port != cur.port)) {
            _form.update { it.copy(host = normalised.host, port = normalised.port ?: it.port) }
            persistForm()
        }
        run(_form.value, persist = true)
    }

    fun reset() { _state.value = TestState.Idle }

    private val _state = MutableStateFlow<TestState>(TestState.Idle)
    val state: StateFlow<TestState> = _state.asStateFlow()

    /** Carries the selected SK key's credential while the touch dialog should
     *  be shown for a "Test connection" on a physical key. */
    data class SkTestTouch(
        val credentialIdBase64: String,
        val application: String,
        val transport: ai.eight24family.conch.domain.SecurityKeyTransport,
    )
    private val _skTouch = MutableStateFlow<SkTestTouch?>(null)
    val skTouch: StateFlow<SkTestTouch?> = _skTouch.asStateFlow()

    /** What the touch dialog's tap should do, set in run() right before the
     *  dialog appears, consumed by [testWithSigner]:
     *   - persist=true  (Save without a warm Test connection): connect (tap) +
     *     hold (login) → pop + background gather.
     *   - persist=false (Test): connect (tap) and KEEP the connection warm under
     *     [sessionServerId] so a later Save reuses it with NO second tap.
     *  Held outside _saved because the screen pops on _saved — we keep the form
     *  + dialog alive until the tap completes. */
    private data class SkTouchOp(val server: Server, val persist: Boolean)
    @Volatile private var skTouchOp: SkTouchOp? = null

    /** Stable id for THIS add/edit session. Edit → the real server id; a new
     *  server → a UUID generated up front, so a Test can open a live connection
     *  under it and a later Save persists the SAME id, reusing that warm
     *  connection (no second tap) and never duplicating the row. */
    private val sessionServerId: String =
        savedStateHandle.get<String>("serverId") ?: java.util.UUID.randomUUID().toString()

    /** Non-null while a Test holds a live connection open under [sessionServerId]
     *  (kept warm so Save reuses it). [onCleared] releases it if the user leaves
     *  without saving; Save nulls it once the connection becomes user-held. */
    @Volatile private var heldConnectionId: String? = null

    /** Invoked by the touch dialog as its op-job the moment the user taps — MUST
     *  be `suspend` and run INLINE so the dialog keeps the NFC reader / CTAP
     *  session armed for exactly as long as this suspends.
     *
     *  Both branches reuse the SAME proven SSH path the chat/home use (the pool
     *  seeds the holder, the dialog's concurrent enumerate matches the touched
     *  token's credential, the pool REBUILDS the matched signer so getAssertion
     *  uses the credId actually on the token — no CTAP 0x2e even if the user
     *  taps the backup key). They differ only in what they do with the open
     *  connection:
     *   - Save → [SshConnectionPool.userConnect]: holds it as a user-intent
     *     connection (login, home dot lit, GlobalPrefetcher gathers) + pops.
     *   - Test → [SshConnectionPool.acquire]: opens it and KEEPS it warm
     *     ([heldConnectionId]) so Save can reuse it with no second tap; the
     *     banner says "connected". [onCleared] drops it if the user leaves. */
    suspend fun testWithSigner(signer: ai.eight24family.conch.ssh.securitykey.SkSigner) {
        val op = skTouchOp ?: run {
            _skTouch.value = null
            _state.value = TestState.Failure(
                "Couldn't start — reopen the form and try again.",
                FailureKind.OTHER,
            )
            return
        }
        _state.value = TestState.Running
        // editingId may be null on a fresh add, so resolveSecrets builds the
        // ServerSecrets from the form's ticked keys (offering EVERY selected SK
        // key). The pool seeds candidateCredIds from these, so whichever
        // credential the touched token holds gets matched.
        val secrets = resolveSecrets(_form.value)
        val pool = ServiceLocator.sshConnectionPool
        if (op.persist) {
            try {
                pool.userConnect(op.server, secrets, signer)
                _skTouch.value = null
                skTouchOp = null
                _state.value = TestState.Success("connected with your security key")
                // Saved AND logged in — the pool holds the user-intent connection
                // (home dot lit; GlobalPrefetcher kicks the agent probe + session
                // download). _saved pops the screen.
                _saved.value = op.server
            } catch (t: Throwable) {
                _skTouch.value = null
                _state.value = TestState.Failure(
                    ai.eight24family.conch.util.ErrorMessages.humanize(t),
                    FailureKind.AUTH_KEY_REJECTED,
                )
            }
        } else {
            // Test: open over the pool (full enumerate + rebuild, so any ticked
            // key works) and KEEP it — acquire holds a refcount but never marks
            // userHeld, so there's no dot / persistence / background sweep yet.
            // The warm connection lets Save skip the second tap; onCleared
            // releases it if the user walks away.
            try {
                pool.acquire(op.server, secrets, signer)
                heldConnectionId = op.server.id
                _skTouch.value = null
                skTouchOp = null
                _state.value = TestState.Success("connected — your security key works on this server")
            } catch (t: Throwable) {
                _skTouch.value = null
                skTouchOp = null
                _state.value = TestState.Failure(
                    ai.eight24family.conch.util.ErrorMessages.humanize(t),
                    FailureKind.AUTH_KEY_REJECTED,
                )
            }
        }
    }

    /** User dismissed the touch dialog without tapping. Clears the pending op +
     *  resets the banner. A connection a PRIOR successful Test left warm stays
     *  open (so a later Save can still reuse it); [onCleared] drops it on exit. */
    fun cancelSkTest() {
        skTouchOp = null
        _skTouch.value = null
        _state.value = TestState.Idle
    }

    /** The "touch your security key" prompt. ALWAYS arms BOTH transports — the
     *  transport is a physical contact channel, not a property of the credential
     *  (the value stored on the key is often USB-only from how it was registered,
     *  which would arm just the USB reader and silently swallow an NFC tap).
     *  Mirrors the agent-picker / chat SkTouchRequest (hardcode EITHER). */
    private fun skTouchPrompt(info: ai.eight24family.conch.domain.SshKeySecurityInfo) = SkTestTouch(
        credentialIdBase64 = info.credentialIdBase64,
        application = info.application,
        transport = ai.eight24family.conch.domain.SecurityKeyTransport.EITHER,
    )

    override fun onCleared() {
        super.onCleared()
        // Left the add-server screen. If a Test left a connection warm but the
        // user never Saved, drop it now. (Save nulls heldConnectionId after
        // promoting the connection to user-held, so this only fires for an
        // uncommitted test.) User:. release() closes a socket → off the main
        // thread (this codebase is ANR-sensitive); a throwaway daemon is fine for
        // one-shot cleanup.
        val id = heldConnectionId ?: return
        heldConnectionId = null
        kotlin.concurrent.thread(isDaemon = true, name = "SshAi-AddServer-release") {
            SilentlyTry.fired("SshAi-AddServer", "release warm test connection") {
                ServiceLocator.sshConnectionPool.release(id)
            }
        }
    }

    private val _saved = MutableStateFlow<Server?>(null)
    val saved: StateFlow<Server?> = _saved.asStateFlow()

    private fun run(form: AddServerForm, persist: Boolean) {
        if (!hasMandatoryFields(form)) {
            _state.value = TestState.Failure("Fill host and user", FailureKind.OTHER)
            return
        }
        if (!hasUsableCredentials(form)) {
            _state.value = TestState.Failure(
                if (form.authMethod == AuthMethod.PASSWORD) "Enter password"
                else "Pick or generate an SSH key",
                FailureKind.OTHER
            )
            return
        }
        _state.value = TestState.Running
        viewModelScope.launch {
            val server = form.toServer()
            val firstKey = form.sshKeyIds.firstOrNull()?.let { keys.getById(it) }
            val skSelected = form.authMethod == AuthMethod.KEY && firstKey != null && (
                firstKey.type == ai.eight24family.conch.domain.SshKeyType.SK_ED25519 ||
                    firstKey.type == ai.eight24family.conch.domain.SshKeyType.SK_ECDSA_NISTP256
            )
            if (skSelected) {
                val info = firstKey?.securityInfo
                if (info == null) {
                    _state.value = TestState.Failure(
                        "This security key has no stored credential — re-add it in the Keychain.",
                        FailureKind.OTHER,
                    )
                    return@launch
                }
                val pool = ServiceLocator.sshConnectionPool
                // A prior Test opened a live connection under sessionServerId and
                // we kept it warm — reuse it instead of asking for a SECOND tap.
                // then peek() is null → warm=false → we fall back to a fresh tap.
                val warm = heldConnectionId == sessionServerId && pool.peek(sessionServerId) != null
                if (persist) {
                    // SAVE — persist under the SAME id used for the warm/test
                    // connection (idempotent: re-Save upserts, never duplicates).
                    val saved = repo.save(
                        server.copy(id = sessionServerId),
                        password = null,
                        leaveSecretsAlone = true,
                    )
                    if (warm) {
                        // Promote the already-open connection to a user-held one
                        // (login + dot + background gather), then drop our test
                        // ref so refcounts balance. No second touch.
                        ServiceLocator.sshConnectionPool.userConnect(saved, resolveSecrets(form), null)
                        ServiceLocator.sshConnectionPool.release(sessionServerId)
                        heldConnectionId = null
                        _state.value = TestState.Success("connected")
                        _saved.value = saved
                    } else {
                        // Saved without a warm connection → tap to connect.
                        skTouchOp = SkTouchOp(saved, persist = true)
                        _skTouch.value = skTouchPrompt(info)
                    }
                } else {
                    // TEST — if already warm, reaffirm without another tap.
                    if (warm) {
                        _state.value = TestState.Success("connected — your security key works on this server")
                    } else {
                        skTouchOp = SkTouchOp(server.copy(id = sessionServerId), persist = false)
                        _skTouch.value = skTouchPrompt(info)
                    }
                }
                return@launch
            }
            val secrets = resolveSecrets(form)
            val r = ssh.testConnection(server, secrets)
            val fp = when (r) {
                is ConnectResult.Success -> r.fingerprint
                is ConnectResult.UnknownHost -> r.fingerprint
                is ConnectResult.HostKeyMismatch -> {
                    _state.value = TestState.HostKeyMismatch(r.expected, r.actual)
                    return@launch
                }
                is ConnectResult.Failure -> {
                    _state.value = TestState.Failure(r.reason, r.kind)
                    return@launch
                }
            }
            if (!persist) {
                _state.value = when (r) {
                    is ConnectResult.UnknownHost -> TestState.UnknownHost(r.fingerprint, r.keyType)
                    is ConnectResult.Success -> TestState.Success(r.fingerprint)
                    is ConnectResult.HostKeyMismatch -> TestState.HostKeyMismatch(r.expected, r.actual)
                    is ConnectResult.Failure -> TestState.Failure(r.reason, r.kind)
                }
                return@launch
            }
            val withFp = server.copy(knownHostKey = fp)
            val leaveSecretsAlone = form.editingId != null &&
                form.authMethod == AuthMethod.PASSWORD &&
                form.password.isBlank()
            val saved = repo.save(
                withFp,
                password = form.password.takeIf { form.authMethod == AuthMethod.PASSWORD },
                leaveSecretsAlone = leaveSecretsAlone,
            )
            _saved.value = saved
            _state.value = TestState.Success(fp)
        }
    }

    private suspend fun resolveSecrets(form: AddServerForm): ServerSecrets {
        return when (form.authMethod) {
            AuthMethod.PASSWORD -> {
                val effective = if (form.password.isNotBlank()) {
                    form.password
                } else if (form.editingId != null) {
                    repo.getSecrets(form.editingId).password.orEmpty()
                } else {
                    ""
                }
                ServerSecrets(password = effective)
            }
            AuthMethod.KEY -> {
                val keyId = form.sshKeyIds.firstOrNull() ?: return ServerSecrets()
                if (form.editingId != null) {
                    return repo.getSecrets(form.editingId)
                }
                val key = keys.getById(keyId)
                if (key != null && (key.type == ai.eight24family.conch.domain.SshKeyType.SK_ED25519 ||
                        key.type == ai.eight24family.conch.domain.SshKeyType.SK_ECDSA_NISTP256)) {
                    // Offer EVERY selected security key — the server accepts ONE
                    // of them, and handing it only the FIRST fails ("server
                    // rejected every authentication method") when it wants
                    // another. The signer enumerates the token and matches
                    // whichever the server picks. (The chat connect already
                    // offers all of them — that's why login worked there.)
                    val skKeys = form.sshKeyIds.mapNotNull { keys.getById(it) }.filter {
                        it.type == ai.eight24family.conch.domain.SshKeyType.SK_ED25519 ||
                            it.type == ai.eight24family.conch.domain.SshKeyType.SK_ECDSA_NISTP256
                    }
                    return ServerSecrets(skKeys = skKeys.ifEmpty { listOf(key) })
                }
                val keySec = keys.loadSecret(keyId) ?: return ServerSecrets()
                ServerSecrets(privateKeyPem = keySec.privateKeyPem, keyPassphrase = keySec.passphrase)
            }
        }
    }

    private suspend fun AddServerForm.toServer(): Server {
        val existing = editingId?.let { repo.getById(it) }
        return Server(
            id = editingId ?: "",
            name = name.ifBlank { host },
            host = host,
            port = port,
            username = user,
            authMethod = authMethod,
            knownHostKey = existing?.knownHostKey,
            // Agent isn't picked on this form anymore — it's chosen per-chat
            // (Agents tab). Preserve the existing server's agent when editing;
            // default a brand-new server to Claude (the user switches freely).
            agent = existing?.agent ?: agent ?: Agent.CLAUDE,
            sshKeyIds = if (authMethod == AuthMethod.KEY) sshKeyIds else emptyList(),
        )
    }
}

internal fun hasMandatoryFields(form: AddServerForm): Boolean =
    form.host.isNotBlank() && form.user.isNotBlank()

internal fun hasUsableCredentials(form: AddServerForm): Boolean = when (form.authMethod) {
    AuthMethod.PASSWORD -> form.password.isNotBlank() || form.editingId != null
    AuthMethod.KEY -> form.sshKeyIds.isNotEmpty()
}
