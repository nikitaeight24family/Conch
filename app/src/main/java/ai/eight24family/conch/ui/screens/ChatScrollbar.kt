package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * Thin scrollbar for the chat LazyColumn. Compose doesn't ship a
 * scrollbar; this draws a 3 dp vertical thumb on the right edge
 * proportional to viewport-vs-content. Hidden when the list isn't
 * scrollable. Auto-fades 800 ms after the user stops scrolling.
 */
@Composable
internal fun ChatScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    // **All scroll-state reads happen inside `drawBehind`** so each
    // scroll-frame update invalidates the draw layer (cheap GPU
    // redraw) instead of recomposing this composable (expensive
    // Compose graph re-walk). Previously the top-level reads of
    // `scrollState.value` + `isScrollInProgress` made the scrollbar
    // recompose 120×/sec during a swipe — and because it sat next
    // to the message Column it dragged the parent into its frame
    // budget too, dropping the visual rate to ~30fps.
    val thumbColor = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(3.dp)
            .drawBehind {
                val maxScroll = scrollState.maxValue
                if (maxScroll <= 0) return@drawBehind
                val viewportH = size.height
                val totalH = viewportH + maxScroll
                val visibleFrac = (viewportH / totalH).coerceIn(0f, 1f)
                if (visibleFrac >= 1f) return@drawBehind
                val pos = scrollState.value
                val topFrac = (pos.toFloat() / totalH).coerceIn(0f, 1f - visibleFrac)
                val thumbHeight = (viewportH * visibleFrac).coerceAtLeast(60f)
                val thumbY = viewportH * topFrac
                val alpha = if (scrollState.isScrollInProgress) 0.55f else 0.18f
                drawRect(
                    color = thumbColor.copy(alpha = alpha),
                    topLeft = Offset(0f, thumbY),
                    size = Size(size.width, thumbHeight),
                )
            },
    )
}
