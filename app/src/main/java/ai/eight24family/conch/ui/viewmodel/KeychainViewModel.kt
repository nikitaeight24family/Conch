package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.data.SshKeyRepository
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.SecurityKeyTransport
import ai.eight24family.conch.domain.SshKey
import ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier
import ai.eight24family.conch.ssh.securitykey.SecurityKeyRegistrar
import com.yubico.yubikit.core.YubiKeyDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class KeychainViewModel : ViewModel() {

    private val repo = ServiceLocator.sshKeyRepository

    val keys: StateFlow<List<SshKey>> = repo.observeKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    sealed interface ImportState {
        data object Idle : ImportState
        data object Working : ImportState
        data class Success(val key: SshKey) : ImportState
        data object NeedsPassphrase : ImportState
        data object WrongPassphrase : ImportState
        data class Failed(val reason: String) : ImportState
    }
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun generate(name: String, comment: String) {
        viewModelScope.launch { repo.generateEd25519(name, comment) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun rename(id: String, newName: String) {
        viewModelScope.launch { repo.rename(id, newName) }
    }

    fun import(name: String, pem: String, passphrase: String?, comment: String) {
        _importState.value = ImportState.Working
        viewModelScope.launch {
            val outcome = runCatching {
                repo.importKey(name, pem, passphrase, comment)
            }.getOrElse {
                _importState.value = ImportState.Failed(it.message ?: "import failed")
                return@launch
            }
            _importState.value = when (outcome) {
                is SshKeyRepository.ImportOutcome.Ok -> ImportState.Success(outcome.key)
                SshKeyRepository.ImportOutcome.EncryptedNoPassphrase -> ImportState.NeedsPassphrase
                SshKeyRepository.ImportOutcome.WrongPassphrase -> ImportState.WrongPassphrase
                is SshKeyRepository.ImportOutcome.Unsupported ->
                    ImportState.Failed("unsupported / not a private key (${outcome.reason})")
            }
        }
    }

    fun clearImportState() {
        _importState.value = ImportState.Idle
    }

    // ──────── Security keys (FIDO2 / hardware tokens) ────────

    /**
     * State machine for both the Discover (enumerateCredentials) and
     * Register (makeCredential) flows. Stages mirror the ones the
     * touch dialog actually surfaces:
     *
     *   Idle → AwaitingTap → AwaitingPin → Importing → Saved
     *                                            └→ PinNotSet | WrongPin |
     *                                               PinBlocked | NoResidentCredentials |
     *                                               Failed
     *
     * Discovery's Saved.keys is N≥1 (whatever resident creds exist).
     * Register's Saved.keys is exactly 1 (the freshly minted credential).
     */
    sealed interface AddSkState {
        data object Idle : AddSkState
        data class AwaitingTap(val transport: SecurityKeyTransport) : AddSkState
        data class AwaitingPin(val transport: SecurityKeyTransport) : AddSkState
        data class Importing(val transport: SecurityKeyTransport) : AddSkState
        data class Saved(val keys: List<SshKey>) : AddSkState
        data object PinNotSet : AddSkState
        data object PinNotSupported : AddSkState
        data class WrongPin(val attemptsLeft: Int?) : AddSkState
        data object PinBlocked : AddSkState
        data object NoResidentCredentials : AddSkState
        data class Failed(val reason: String) : AddSkState
    }
    private val _addSkState = MutableStateFlow<AddSkState>(AddSkState.Idle)
    val addSkState: StateFlow<AddSkState> = _addSkState.asStateFlow()

    @Volatile private var activeAddSkJob: kotlinx.coroutines.Job? = null

    private val _pendingPin =
        kotlinx.coroutines.flow.MutableStateFlow<kotlinx.coroutines.CompletableDeferred<CharArray?>?>(null)

    /** Discover (enumerate) every resident SSH credential on the
     *  presented token and import each into the keychain. Used by the
     *  "Find existing keys on this token" Keychain action. */
    fun importSecurityKeyResidentCredentials(
        transport: SecurityKeyTransport,
        activity: android.app.Activity?,
    ) {
        runOnDevice(transport, activity, SecurityKeyNotifier.Reason.REGISTER) { device, pinProvider ->
            ServiceLocator.securityKeyRegistrar.importResidentCredentials(device, pinProvider)
        }
    }

    /** Register a brand-new resident SSH credential on the presented
     *  token via CTAP `makeCredential`. [displayName] is used both as
     *  the SshKey row name and as the credential's user.displayName so
     *  desktop tools (`ykman fido credentials list`) show the same. */
    fun registerNewSecurityKey(
        transport: SecurityKeyTransport,
        activity: android.app.Activity?,
        displayName: String,
    ) {
        runOnDevice(transport, activity, SecurityKeyNotifier.Reason.REGISTER) { device, pinProvider ->
            ServiceLocator.securityKeyRegistrar.registerNewCredential(device, pinProvider, displayName)
        }
    }

    /**
     * Shared driver for both Discover and Register flows. Handles the
     * USB / NFC / EITHER transport plumbing, PIN provider bridge, and
     * mapping registrar [SecurityKeyRegistrar.Outcome] back into
     * [AddSkState]. Differences between flows are entirely in the
     * passed-in [op] lambda.
     */
    private fun runOnDevice(
        transport: SecurityKeyTransport,
        activity: android.app.Activity?,
        notifierReason: SecurityKeyNotifier.Reason,
        op: (YubiKeyDevice, () -> CharArray?) -> SecurityKeyRegistrar.Outcome,
    ) {
        activeAddSkJob?.cancel()
        android.util.Log.d("SshAi-SK-VM", "runOnDevice transport=$transport reason=$notifierReason")
        _addSkState.value = AddSkState.AwaitingTap(transport)
        SecurityKeyNotifier.post(
            context = ServiceLocator.appContext,
            reason = notifierReason,
            transport = transport,
        )
        activeAddSkJob = viewModelScope.launch {
            val pinProvider: () -> CharArray? = pp@{
                val deferred = kotlinx.coroutines.CompletableDeferred<CharArray?>()
                _pendingPin.value = deferred
                _addSkState.value = AddSkState.AwaitingPin(transport)
                val chars = kotlinx.coroutines.runBlocking { deferred.await() }
                _pendingPin.value = null
                _addSkState.value = AddSkState.Importing(transport)
                chars
            }
            val outcome: SecurityKeyRegistrar.Outcome = withContext(Dispatchers.IO) {
                runCatching {
                    when (transport) {
                        SecurityKeyTransport.USB -> {
                            val device = ServiceLocator.securityKeyManager
                                .awaitUsb(timeoutMs = Long.MAX_VALUE)
                                ?: return@runCatching SecurityKeyRegistrar.Outcome.Failed("no USB token detected")
                            op(device, pinProvider)
                        }
                        SecurityKeyTransport.NFC -> {
                            val act = requireNotNull(activity) {
                                "NFC requires an activity for reader-mode dispatch"
                            }
                            ServiceLocator.securityKeyManager.withNfc(
                                activity = act,
                                timeoutMs = Long.MAX_VALUE,
                            ) { device -> op(device, pinProvider) }
                                ?: SecurityKeyRegistrar.Outcome.Failed("no NFC tap detected")
                        }
                        SecurityKeyTransport.EITHER -> {
                            val act = activity
                            coroutineScope {
                                val usbDef = async {
                                    ServiceLocator.securityKeyManager.awaitUsb(timeoutMs = Long.MAX_VALUE)
                                }
                                val nfcDef = async<SecurityKeyRegistrar.Outcome?> {
                                    if (act == null) {
                                        awaitCancellation()
                                    } else {
                                        ServiceLocator.securityKeyManager.withNfc(
                                            activity = act,
                                            timeoutMs = Long.MAX_VALUE,
                                        ) { device -> op(device, pinProvider) }
                                    }
                                }
                                select<SecurityKeyRegistrar.Outcome> {
                                    usbDef.onAwait { device ->
                                        nfcDef.cancel()
                                        if (device != null) op(device, pinProvider)
                                        else SecurityKeyRegistrar.Outcome.Failed("no USB token detected")
                                    }
                                    nfcDef.onAwait { outcome ->
                                        usbDef.cancel()
                                        outcome ?: SecurityKeyRegistrar.Outcome.Failed("no NFC tap detected")
                                    }
                                }
                            }
                        }
                    }
                }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    android.util.Log.e("SshAi-SK-VM", "import threw", it)
                    SecurityKeyRegistrar.Outcome.Failed(it.message ?: "import failed: ${it.javaClass.simpleName}")
                }
            }
            android.util.Log.d("SshAi-SK-VM", "outcome=${outcome::class.simpleName}")
            SecurityKeyNotifier.cancel(ServiceLocator.appContext)
            _addSkState.value = when (outcome) {
                is SecurityKeyRegistrar.Outcome.Ok -> {
                    val saved = outcome.imported.map { cred ->
                        repo.addSecurityKey(
                            nameHint = cred.displayName ?: "security key",
                            cred = cred,
                            transport = transport,
                        )
                    }
                    AddSkState.Saved(saved)
                }
                SecurityKeyRegistrar.Outcome.PinNotSet -> AddSkState.PinNotSet
                SecurityKeyRegistrar.Outcome.PinNotSupported -> AddSkState.PinNotSupported
                is SecurityKeyRegistrar.Outcome.WrongPin -> AddSkState.WrongPin(outcome.attemptsLeft)
                SecurityKeyRegistrar.Outcome.PinBlocked -> AddSkState.PinBlocked
                SecurityKeyRegistrar.Outcome.NoResidentCredentials -> AddSkState.NoResidentCredentials
                is SecurityKeyRegistrar.Outcome.Failed -> AddSkState.Failed(outcome.reason)
            }
        }
    }

    fun submitPin(pin: String) {
        val deferred = _pendingPin.value ?: return
        deferred.complete(pin.toCharArray())
    }

    fun cancelPin() {
        _pendingPin.value?.complete(null)
    }

    fun clearAddSkState() {
        _pendingPin.value?.complete(null)
        activeAddSkJob?.cancel()
        activeAddSkJob = null
        SecurityKeyNotifier.cancel(ServiceLocator.appContext)
        _addSkState.value = AddSkState.Idle
    }
}
