package ai.eight24family.conch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Vector drawing of a generic USB-shape security key. We deliberately don't
 * use a stylized "old school" key (the [Icons.Default.VpnKey] padlock-key
 * silhouette) because the product is exclusively about hardware FIDO tokens
 * — drawing a brass house-key reads as "password vault", which we are not.
 *
 * Two concentric shapes:
 *   - **body**: a rounded rectangle representing the plastic enclosure
 *   - **connector**: a smaller rectangle on the right standing in for the
 *     USB-A / USB-C plug, with a hinted contact bar inside
 *
 * No vendor letter (no "Y", no "S", no logo) — generic on purpose.
 *
 * @param showTouchDot when true, draws a small ring on the body indicating
 *        the touch sensor. Useful for hero presentation; turn off for
 *        toolbar / row-leading icon use to keep small sizes legible.
 */
@Composable
fun SecurityKeyIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    showTouchDot: Boolean = false,
    contentDescription: String? = "security key",
) {
    val desc = contentDescription
    val a11yModifier = if (desc != null) {
        modifier.semantics { this.contentDescription = desc }
    } else {
        modifier
    }
    Canvas(modifier = a11yModifier) {
        val w = size.width
        val h = size.height
        // Stroke scales with icon height so the same component reads at
        // both 24dp (toolbar) and 64dp (hero).
        val stroke = h * 0.08f

        // ── BODY — rounded enclosure, ~70 % of total width ──
        val bodyW = w * 0.68f
        val bodyH = h * 0.50f
        val bodyR = h * 0.18f
        val bodyTop = (h - bodyH) / 2f
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(bodyR, bodyR),
            style = Stroke(width = stroke),
        )

        // ── USB connector — thinner rectangle just to the right ──
        val connW = w * 0.22f
        val connH = h * 0.34f
        val connTop = (h - connH) / 2f
        val connLeft = bodyW + (w * 0.04f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(connLeft, connTop),
            size = Size(connW, connH),
            cornerRadius = CornerRadius(h * 0.05f, h * 0.05f),
            style = Stroke(width = stroke),
        )
        // Inner contact bar — tiny rectangle inside the connector, reads
        // as "metal plug face" without any text labels.
        val barW = connW * 0.55f
        val barH = connH * 0.30f
        drawRect(
            color = tint,
            topLeft = Offset(
                connLeft + (connW - barW) / 2f,
                connTop + (connH - barH) / 2f,
            ),
            size = Size(barW, barH),
        )

        // ── Optional touch sensor dot (hero use only) ──
        if (showTouchDot) {
            drawCircle(
                color = tint,
                radius = h * 0.10f,
                center = Offset(bodyW * 0.55f, h / 2f),
                style = Stroke(width = stroke * 0.9f),
            )
        }
    }
}
