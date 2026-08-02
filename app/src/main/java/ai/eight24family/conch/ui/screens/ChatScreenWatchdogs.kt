package ai.eight24family.conch.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.ui.viewmodel.ChatViewModel

/**
 * Eject-on-no-session watchdog.
 *
 * Hard rule (mirrors AgentPicker / Sessions): if there's no live SSH for
 * this server and no in-flight touch flow, the user has nothing to do
 * here — pop back to the server list. Long grace period (10 s) because
 * Chat takes a moment to establish its own pool acquire on first open;
 * we don't want to bounce mid-handshake.
 *
 * Chats opened from a global-search hit get to STAY even when not
 * connected: HistoryCache renders the conversation from local JSONL, the
 * user came here to read the match they found. Eject logic is suppressed
 * for that flow; the connect chip handles re-arm. For "normal" chats
 * (session-row tap, deep link, cold start) the eject still fires —
 * landing without SSH means the user navigated by accident and we send
 * them back to pick a server explicitly.
 */
@Composable
internal fun ChatEjectWatchdog(
    serverId: String,
    cameFromSearch: Boolean,
    vm: ChatViewModel,
    onBack: () -> Unit,
) {
    // EJECT DISABLED (2026-06-01). In the unified-sessions model EVERY chat
    // opens read-only from cache — no SSH and no FIDO touch on open. The key
    // is requested only on the first send (ChatViewModel.send →
    // beginSearchOpenedConnect). The old eject-on-no-SSH was exactly the bug —
    // the chat composed, found no live SSH, and immediately onBack()'d.
    //
    // (Kept as a no-op shell so callers / the param wiring stay unchanged.)
}

/**
 * Drive the tail-poll interval by the chat screen's foreground state:
 * foreground → 5 s, backgrounded → 30 s. Keeps SSH alive in both cases
 * (the socket itself is keepalived at the TCP layer); just don't burn
 * the radio every 5 s while the user is in the music app or browser.
 */
@Composable
internal fun ChatTailPollLifecycle(vm: ChatViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.setTailBackgrounded(true)
                Lifecycle.Event.ON_START -> vm.setTailBackgrounded(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
