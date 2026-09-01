package ai.eight24family.conch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Code-like text block that copies its full content to the clipboard on a
 * single tap. Long-press still falls through to the surrounding
 * `SelectionContainer` so users can pick a partial substring the system
 * way.
 */
@Composable
fun CopyableCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = LocalTextStyle.current,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(8.dp),
    // Cap the rendered height. A multi-KB tool input/result rendered as ONE
    // un-bounded Text becomes a list item thousands of px tall — past Compose's
    // draw limit it reserves the height but draws BLANK, leaving half-screen
    // empty gaps in the chat (user's bug). Bounding it + internal scroll keeps
    // every item small (always drawn) while the full content stays readable by
    // scrolling the box. heightIn MUST precede verticalScroll so the scroller is
    // measured with a bounded (not infinite) max height inside the LazyColumn.
    maxHeight: Dp = 320.dp,
) {
    val clipboard = LocalClipboardManager.current
    // ⛔ DO NOT WRAP. Command output is column-aligned by spaces (free -m, ls
    // -la, ps, df) — soft-wrapping at the screen edge folds the columns into
    // an unreadable stack. Terminal fidelity instead: keep every line intact
    // and let the block scroll SIDEWAYS. A single pathologically long line
    // is capped for DISPLAY only (copy still uses the raw `text`) so it
    // can't blow the horizontal metric past Compose's draw limit.
    val display = remember(text) {
        text.lineSequence().joinToString("\n") { if (it.length > 4000) it.take(4000) + "…" else it }
    }
    val cyan = MaterialTheme.colorScheme.primary
    var flashed by remember { mutableStateOf(false) }
    val animatedBg by animateColorAsState(
        targetValue = if (flashed) cyan.copy(alpha = 0.20f) else background,
        animationSpec = tween(durationMillis = 250),
        label = "copy-flash"
    )

    LaunchedEffect(flashed) {
        if (flashed) {
            delay(900)
            flashed = false
        }
    }

    Box(
        modifier = modifier
            .background(animatedBg)
            .clickable {
                if (text.isNotEmpty()) {
                    clipboard.setText(AnnotatedString(text))
                    flashed = true
                }
            }
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        // Horizontal scroll + softWrap=false lives on the TEXT, so wide lines
        // keep their columns and pan sideways; the copied-badge stays pinned
        // to the visible corner (outside the scrollers).
        Text(
            text = display,
            color = textColor,
            style = style,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(contentPadding),
        )
        if (flashed) {
            Text(
                text = "✓ copied",
                color = cyan,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
