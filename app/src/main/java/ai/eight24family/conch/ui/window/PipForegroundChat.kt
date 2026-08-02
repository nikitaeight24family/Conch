package ai.eight24family.conch.ui.window

import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bridges "which chat is on screen" to the Picture-in-Picture overlay.
 *
 * The PiP overlay is drawn by MainActivity.Root (it must survive the PiP swap,
 * which short-circuits ChatScreen). Root has no nav context, so without this it
 * used `AgentSessionManager.findMostRecentlyActive()` — a recency guess whose
 * `session.history` can be a DIFFERENT conversation (or a resumed session with
 * different messages) than the one the user is actually looking at. That's the
 * "PiP shows god-knows-what message" bug.
 *
 * ChatScreen publishes its [ChatViewModel] here while it's the foreground
 * destination (and clears it on dispose); the overlay renders THAT chat's
 * messages + reading anchor, so the floating window is always the same
 * conversation the user just minimized. Falls back to the recency guess only
 * when no chat is foreground (e.g. PiP entered from the Settings tab while a
 * background turn runs).
 */
object PipForegroundChat {
    val current = MutableStateFlow<ChatViewModel?>(null)
}
