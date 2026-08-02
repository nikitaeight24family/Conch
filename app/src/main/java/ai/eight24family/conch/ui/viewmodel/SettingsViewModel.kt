package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.data.prefs.SkNotificationVisibility
import ai.eight24family.conch.data.prefs.ThemeMode
import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One enrolled device key, shown per-server in Settings → Connection.
 *  [fingerprint] is the PUBLIC-key SHA-256 (safe to display) so the user can
 *  see a key exists and which one; [serverId] drives the per-server revoke. */
data class DeviceKeyEntry(
    val serverId: String,
    val serverName: String,
    val fingerprint: String,
    /** Epoch-ms when the key expires server-side, or null if unknown. Drives the
     *  live countdown. */
    val expiresAtMs: Long?,
)

class SettingsViewModel : ViewModel() {

    private val prefs = ServiceLocator.preferences
    private val sessions = ServiceLocator.agentSessions
    private val repo = ServiceLocator.serverRepository

    val themeMode: StateFlow<ThemeMode> = prefs.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val enterSends: StateFlow<Boolean> = prefs.enterSends
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val accentHex: StateFlow<String> = prefs.accentHex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "#00E5FF")

    val customBgHex: StateFlow<String> = prefs.customBgHex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "#0A0F12")

    val customTextHex: StateFlow<String> = prefs.customTextHex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "#E6EDF3")

    val fontFamilyId: StateFlow<String> = prefs.fontFamilyId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    val highRefreshRateEnabled: StateFlow<Boolean> = prefs.highRefreshRateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val hapticsEnabled: StateFlow<Boolean> = prefs.hapticsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val appScale: StateFlow<Float> = prefs.appScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1.0f)

    val crashReportingEnabled: StateFlow<Boolean> = prefs.crashReportingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val approvalMode: StateFlow<AgentApprovalMode> = prefs.approvalMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentApprovalMode.YOLO)

    val sshConnectTimeoutSec: StateFlow<Int> = prefs.sshConnectTimeoutSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 15)

    val sshKeepaliveIntervalSec: StateFlow<Int> = prefs.sshKeepaliveIntervalSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 45)

    val dataSaverEnabled: StateFlow<Boolean> = prefs.dataSaverEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setDataSaverEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setDataSaverEnabled(enabled) }
    }

    // Seamless reconnect + device-key management moved to the per-server detail
    // page (ServerDetailViewModel) — it's a property of the SERVER, not the app.

    val skNotificationVisibility: StateFlow<SkNotificationVisibility> = prefs.skNotificationVisibility
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkNotificationVisibility.PRIVATE)

    val downloadsFolderDisplay: StateFlow<String?> = prefs.downloadsFolderDisplay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Persist a user-picked downloads folder. The caller must have
     * already taken a `persistableUriPermission` on the tree URI
     * (that step lives in the UI layer because it needs the
     * `Context`/`Activity` of the SAF intent flow).
     *
     * `display` is the human-readable label we surface in Settings
     * — usually derived from `DocumentFile.fromTreeUri(uri).name`.
     */
    fun setDownloadsFolder(uri: android.net.Uri?, display: String?) {
        viewModelScope.launch { prefs.setDownloadsFolder(uri, display) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setEnterSends(value: Boolean) {
        viewModelScope.launch { prefs.setEnterSends(value) }
    }

    fun setCustomBgHex(hex: String) {
        viewModelScope.launch { prefs.setCustomBgHex(hex) }
    }

    fun setCustomTextHex(hex: String) {
        viewModelScope.launch { prefs.setCustomTextHex(hex) }
    }

    fun setFontFamilyId(id: String) {
        viewModelScope.launch { prefs.setFontFamilyId(id) }
    }

    fun setAccentHex(hex: String) {
        viewModelScope.launch { prefs.setAccentHex(hex) }
    }

    fun setHighRefreshRateEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setHighRefreshRateEnabled(value) }
    }

    fun setHapticsEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setHapticsEnabled(value) }
    }

    fun setAppScale(value: Float) {
        viewModelScope.launch { prefs.setAppScale(value) }
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setCrashReportingEnabled(enabled) }
    }

    fun setApprovalMode(mode: AgentApprovalMode) {
        viewModelScope.launch { prefs.setApprovalMode(mode) }
    }

    val showApprovalInChatBar: StateFlow<Boolean> = prefs.showApprovalInChatBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setShowApprovalInChatBar(show: Boolean) {
        viewModelScope.launch { prefs.setShowApprovalInChatBar(show) }
    }

    fun setSshConnectTimeoutSec(seconds: Int) {
        viewModelScope.launch { prefs.setSshConnectTimeoutSec(seconds) }
    }

    fun setSshKeepaliveIntervalSec(seconds: Int) {
        viewModelScope.launch { prefs.setSshKeepaliveIntervalSec(seconds) }
    }

    fun setSkNotificationVisibility(value: SkNotificationVisibility) {
        viewModelScope.launch { prefs.setSkNotificationVisibility(value) }
    }

    /** SEC-1: whether the conch-bridge may run `shell` commands from the server. */
    val bridgeShellAllowed: StateFlow<Boolean> = prefs.bridgeShellAllowed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Whether the conch-bridge may record this phone's MIC from the server.
     *  Seeded false, not true — the seed is what the UI shows before the flow
     *  arrives, and a mic switch must never flash "on". */
    val bridgeAudioAllowed: StateFlow<Boolean> = prefs.bridgeAudioAllowed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setBridgeAudioAllowed(allowed: Boolean) {
        viewModelScope.launch { prefs.setBridgeAudioAllowed(allowed) }
    }

    fun setBridgeShellAllowed(allowed: Boolean) {
        viewModelScope.launch { prefs.setBridgeShellAllowed(allowed) }
    }

    /**
     * GDPR Art. 17 — wipes all locally persisted data and kills the
     * process. The OS restarts the app on next launch with empty
     * storage. See [ai.eight24family.conch.data.DataEraser] for the
     * exact list of locations cleared.
     */
    fun eraseAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            ai.eight24family.conch.data.DataEraser.eraseAll(
                ServiceLocator.appContext
            )
        }
    }

    fun disconnectAll() {
        sessions.closeAll()
    }
}
