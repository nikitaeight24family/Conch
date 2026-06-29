package ai.eight24family.conch.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.diagnostics.BridgePresence

/**
 * The single 📱 glyph used EVERYWHERE — home list, per-server list, and the chat
 * title strip — so the surfaces can never disagree (PHONE-GLYPH-CONSISTENT-1).
 *
 * • [BridgePresence.LIVE] → COLORED (primary): bridge reachable + Shizuku live.
 * • [BridgePresence.IDLE] → DIM: was wired but offline now. •
 * [BridgePresence.NONE] → nothing emitted (no node, no padding).
 */
@Composable
fun PhoneBridgeGlyph(
    presence: BridgePresence,
    modifier: Modifier = Modifier,
    size: Dp = 13.dp,
) {
    if (presence == BridgePresence.NONE) return
    val live = presence == BridgePresence.LIVE
    Icon(
        imageVector = Icons.Filled.PhoneAndroid,
        contentDescription = if (live) "phone connected to this session"
        else "phone was connected to this session (now offline)",
        tint = if (live) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        modifier = modifier.size(size),
    )
}
