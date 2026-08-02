package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import ai.eight24family.conch.ui.viewmodel.ChatViewModelSearchConn
import kotlinx.coroutines.delay

/**
 * Connection-state chip rendered at the top of the chat when the user
 * arrived via a global-search hit. Four states; each gets its own copy,
 * colour, and tap-behaviour. After Connected the chip auto-fades 1.5 s
 * later — once we're online there's no value in keeping the strip
 * present indefinitely.
 */
@Composable
internal fun SearchOpenConnChip(
    state: ChatViewModelSearchConn.State,
    onTap: () -> Unit,
) {
    // Hidden = connected-on-open or silent device-key connect → no chip at all
    // (no "connected" flash on a server you're already logged into).
    if (state == ChatViewModelSearchConn.State.Hidden) return
    var showAfterConnected by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        if (state == ChatViewModelSearchConn.State.Connected) {
            delay(1_500L)
            showAfterConnected = false
        } else {
            // Any non-Connected transition makes the chip visible again
            // — e.g. transport dropped → reconnect needed.
            showAfterConnected = true
        }
    }
    if (state == ChatViewModelSearchConn.State.Connected && !showAfterConnected) return

    val amber = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val red = Color(0xFFE57373)
    val green = Color(0xFF4ADE80)

    val (label, dotColor, tappable) = when (state) {
        ChatViewModelSearchConn.State.Idle ->
            Triple("offline · tap to connect", outline, true)
        ChatViewModelSearchConn.State.Connecting ->
            Triple("connecting…", amber, false)
        ChatViewModelSearchConn.State.Connected ->
            Triple("connected", green, false)
        ChatViewModelSearchConn.State.Failed ->
            Triple("connect failed · tap to retry", red, true)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(dotColor.copy(alpha = 0.07f))
            .let { if (tappable) it.clickable { onTap() } else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The leading dot mirrors the home-screen ConnectionDot
        // convention (●  cyan = connected, dim = not). Coloured per
        // state so the strip reads at a glance even without text.
        Text(
            "●  ",
            color = dotColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            color = dotColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
