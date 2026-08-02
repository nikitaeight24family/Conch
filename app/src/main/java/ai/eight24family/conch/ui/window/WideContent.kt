package ai.eight24family.conch.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * UX-6: centre tab-screen content at a comfortable reading width on wide
 * windows (landscape phone, tablet, foldable, DeX). Before this, the list /
 * settings screens left-aligned their (already width-capped) content, leaving
 * a wide empty band on the right that read as an accidental "portrait layout
 * stretched edge to edge". Now the content sits in a centred column and the
 * surplus width is symmetric margin — it looks intentional.
 *
 * No-op on a normal portrait phone: when the window is narrower than [maxWidth]
 * the inner box just fills the width, exactly as before. This is the low-risk
 * readability fix, NOT the Expanded-width two-pane / master-detail workstream
 * (that stays separate — see AppScaffold's notes about the removed rail + the
 * planned three-pane).
 *
 * Scaffold insets are deliberately NOT consumed here: callers keep applying the
 * Scaffold's `padding` to their own content, so status-bar / top-bar / nav
 * insets are unchanged. This wrapper only governs horizontal placement + the
 * maximum content width.
 */
@Composable
fun WideContentColumn(
    maxWidth: Dp = 720.dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize()) {
            content()
        }
    }
}
