package ai.eight24family.conch.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries the user back to the chat they were in when they went off to pair the
 * phone — and finishes the job they had already asked for.
 *
 * ⛔ THE WALK THIS REPLACES. Pairing starts in a chat ("connect phone"), but it
 * FINISHES three screens away, inside Android's own Developer options, in a
 * notification. When it succeeded, the app said nothing and the user was left
 * standing in Settings: back out, find Conch, find that chat, and press the same
 * button a second time to get the thing they had already asked for once (owner,
 * 2026-08-29). Every step of that is the app forgetting what it was doing.
 *
 * Two fields, because there are two distinct moments:
 *
 *  • [origin] — set when a chat sends the user off to pair. A memory, no more.
 *  • [pending] — set when the pairing SUCCEEDS. This is the live request: the
 *    nav host takes the user back to that chat, marks it [Request.navigated],
 *    and the chat then continues the connect on its own.
 *
 * Splitting them is what keeps the return from re-firing: whoever navigates does
 * not clear the request (the chat may not exist yet), and whoever acts on it does.
 *
 * ⚠ NOT persisted. If the process dies while the user is in Settings this is
 * lost, and the "Phone paired ✓" notification's own tap target is what gets them
 * back. That fallback is not decoration — it is the only path when the automatic
 * one is refused, and it must keep working.
 */
object PhoneBridgeReturn {

    /**
     * @param serverId which chat asked — the chat verifies this before it acts,
     *   so a return can never drive a chat the user did not start from.
     * @param route where to go if the chat is NOT still in the back stack
     *   (process death, or the user navigated away). Normally unused: popping
     *   back to the live entry keeps the ViewModel, and with it the transcript.
     */
    data class Request(
        val serverId: String,
        val route: String,
        val navigated: Boolean = false,
    )

    @Volatile
    private var origin: Request? = null

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** A chat is sending the user to Settings to pair. Remember where from. */
    fun rememberOrigin(serverId: String, route: String) {
        origin = Request(serverId, route)
    }

    /** Pairing worked. If a chat started it, ask to be taken back there. */
    fun paired() {
        origin?.let { _pending.value = it }
        origin = null
    }

    /** True if a chat is waiting to be returned to (used to decide whether it is
     *  worth trying to bring the app forward at all). */
    fun someoneIsWaiting(): Boolean = origin != null

    fun markNavigated() {
        _pending.value = _pending.value?.copy(navigated = true)
    }

    fun clear() {
        _pending.value = null
        origin = null
    }
}
