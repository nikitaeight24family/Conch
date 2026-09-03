package ai.eight24family.conch.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Take me to where this is fixed."
 *
 * ⛔ THE WIZARD EXISTED; ONLY ONE SCREEN COULD REACH IT. Arming the phone bridge
 * is a real guided flow — two observed states, a button that opens Android's own
 * Developer options, a watcher that notices the pairing dialog and asks for the
 * six digits in a notification, and a poll that flips the screen to Ready by
 * itself. But the ONLY route into it was the chat's "connect phone" button
 * (ChatScreen's NeedSettings branch). Every other place that needs the shell —
 * the chat row that says the phone's Linux cannot start, the Linux page's
 * "phone shell not connected", a local model that has nowhere to run — printed
 * a sentence and stopped there. A dead end explaining itself is still a dead
 * end (owner, 2026-09-03: "the moment the app needs a connection it should lead
 * the user by the hand, not just fall over").
 *
 * So the entry is a request anyone can make, and the nav host answers it: set
 * the Phone-bridge category, go to the Settings tab, done. Deliberately NOT a
 * nav argument — Settings is a tab, and tab navigation restores saved state,
 * which drops arguments (the same reason [SettingsDeepLink] exists).
 *
 * A caller that wants the user brought BACK afterwards records that separately,
 * with [PhoneBridgeReturn] — the two are independent: this one is "get me
 * there", that one is "and finish what I asked for".
 */
object BridgeSetupRequest {

    private val _asked = MutableStateFlow(0)

    /** Increments on every ask, so two asks in a row are two events and not one
     *  boolean that was already true. */
    val asked: StateFlow<Int> = _asked.asStateFlow()

    fun ask() {
        _asked.value = _asked.value + 1
    }
}
