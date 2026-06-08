package ai.eight24family.conch.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the connection state for chats opened by tapping a global-search hit.
 *
 * UX contract: tapping a global-search hit MUST land the user in the chat —
 * never pop them back to the server list because they're not connected. The
 * chat surface paints fine from local cache (HistoryCache lives in filesDir),
 * and the user came here to READ the match they found.
 *
 * Connection comes up opportunistically:
 *  - Password / software-key servers (no FIDO touch) — connect silently in the background.
 *  - SK-keyed servers — show our standard touch dialog. If the user cancels, we DON'T
 *    eject; they stay in read-only mode and can tap the chip later to retry.
 *
 * The orchestration lives in [ChatViewModel.beginSearchOpenedConnect] (it needs SSH
 * pool + server repo + the SK signer flow); this helper just owns the StateFlow.
 */
class ChatViewModelSearchConn {
    // Hidden = render NOTHING: the chat opened already-connected, or it came up
    // SILENTLY via the device key on open. Showing "connected" in that case was
    // pure noise. The visible states are only for genuinely-offline (Idle → "tap
    // to connect") and for an EXPLICIT user connect (Connecting/Connected
    // feedback after a tap/send).
    enum class State { Hidden, Idle, Connecting, Connected, Failed }

    private val _state = MutableStateFlow(State.Hidden)
    val state: StateFlow<State> = _state.asStateFlow()

    fun set(value: State) {
        _state.value = value
    }

    fun get(): State = _state.value
}
