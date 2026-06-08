package ai.eight24family.conch.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Small connection dot (●/○) used in the home-screen server list and
 * also next to the server name on the agent-picker / sessions-list
 * top bars. Single source of truth so the glyph never drifts visually
 * between screens.
 *
 *  - `connected = true`   → cyan filled ● ("there's a live pooled SSH")
 *  - `pending = true` (and not connected) → amber ◐ ("the user WANTS this
 *    server connected but the transport is down right now — network blip /
 *    FIDO needs a re-tap; it's recoverable, not forgotten")
 *  - otherwise            → outline-coloured ○ ("no live SSH; next op on
 *    this server will need a fresh handshake / SK touch")
 */
@Composable
fun ConnectionDot(connected: Boolean, pending: Boolean = false) {
    val tint: Color = when {
        connected -> MaterialTheme.colorScheme.primary
        pending -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    val glyph = when {
        connected -> "  ●"
        pending -> "  ◐"
        else -> "  ○"
    }
    val description = when {
        connected -> "connected"
        pending -> "reconnect pending"
        else -> "not connected"
    }
    Text(
        text = glyph,
        color = tint,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        // Replace the literal "filled circle"/"open circle" reading with a
        // meaningful state description for screen readers (WCAG 1.1.1).
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    )
}

/**
 * Tiny indeterminate spinner sized to fit unobtrusively in a top-bar
 * actions slot. Used while a probe / discovery is in flight — replaces
 * the old fullscreen "probing…" / "loading…" rows that hid the rest
 * of the screen behind something the user didn't ask to see.
 *
 * Default tint is outline-grey — caller can pass a brighter colour
 * (e.g. tertiary amber) when there's a coloured semantic on top of
 * "still working" (the prefetch ring already does this for us with
 * its own glyph; this spinner is the neutral generic).
 */
@Composable
fun TopBarSpinner(
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = "loading",
) {
    val desc = contentDescription
    CircularProgressIndicator(
        modifier = modifier
            .padding(horizontal = 6.dp)
            .size(16.dp)
            .semantics { this.contentDescription = desc },
        strokeWidth = 2.dp,
        color = tint,
    )
}
