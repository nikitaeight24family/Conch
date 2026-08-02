package ai.eight24family.conch.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Compact key+waves widget shown in the top-right corner of the PIN
 * entry dialog. When [animated], radiates concentric rings around the
 * key icon (reminds the user the physical key MUST stay against the
 * antenna while they type). When [animated]=false, freezes to a static
 * key icon with a dimmed colour — used when the watcher has detected
 * the key was lifted, signalling "absent" alongside the "Put the key
 * back" copy in the body.
 */
@Composable
internal fun PinPadKeyIndicator(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val tint = if (animated) primary else dim
    val infinite = rememberInfiniteTransition(label = "pin-key-ripple")
    val rippleProgress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple",
    )
    Box(
        modifier = modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (animated) {
            Canvas(modifier = Modifier.size(40.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                val maxRadius = size.minDimension / 2f
                for (i in 0..1) {
                    val phase = (rippleProgress + i / 2f) % 1f
                    val radius = phase * maxRadius
                    val alpha = (1f - phase).coerceIn(0f, 1f) * 0.6f
                    drawCircle(
                        color = primary.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 1.8f),
                    )
                }
            }
        }
        ai.eight24family.conch.ui.components.SecurityKeyIcon(
            modifier = Modifier.size(width = 24.dp, height = 12.dp),
            tint = tint,
            showTouchDot = true,
        )
    }
}

@Composable
internal fun SkInlineWaitingRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(2.dp),
            strokeWidth = 2.dp,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 3x4 numeric keypad for FIDO2 PIN entry. We deliberately avoid the
 * system IME for two reasons:
 *   1. The system keyboard for password-typed fields keeps switching to
 *      letter mode on some OEM ROMs, which is hostile for a numeric PIN.
 *   2. Showing our own pad makes the affordance unambiguous — user
 *      knows this is the credential PIN, not a server password.
 *
 * Layout: dotted-bullet display row on top, then `1 2 3 / 4 5 6 / 7 8 9 /
 * ⌫ 0 ✓`. The check button calls [onSubmit] (treated as "Continue" by
 * the parent dialog). Backspace deletes the last char.
 */
@Composable
internal fun PinPad(
    pin: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    /** When false (e.g. the key was lifted mid-PIN), the pad is greyed and
     * ignores all taps — the user must re-attach the key before typing.
     * Stops PIN digits going into a cancelled flow. */
    enabled: Boolean = true,
) {
    // FIDO2 PIN on YubiKey: 4–8 digits (hardware limit). Other vendors
    // are similar; 8 covers everyone in practice. We auto-submit on the
    // 8th digit so the user never has to reach for ✓.
    val maxLen = 8
    // Auto-submit when the PIN hits maxLen. LaunchedEffect on `pin` so a
    // single transition fires the callback exactly once and a fresh PIN
    // entry (after wrong-PIN, say) doesn't re-fire stale submissions.
    LaunchedEffect(pin, enabled) {
        if (enabled && pin.length == maxLen) onSubmit()
    }
    // Standard phone-keypad layout (4 rows × 3 columns) on every device
    // size. Old 3×4 landscape variant was confusing — users muscle-
    // memory the 1-2-3 / 4-5-6 / 7-8-9 / *-0-# grid from every phone
    // dial pad they've touched since 2007. The dialog itself caps at
    // 360dp wide (see Surface widthIn above), so the 3-column pad
    // stays compact in DeX / tablet windows without smearing into a
    // 4-wide pancake. Landscape just tightens spacing/heights a bit
    // to fit the full 4 rows inside the narrower viewport.
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val buttonHeight = if (isLandscape) 40.dp else 52.dp
    val rowGap = if (isLandscape) 4.dp else 8.dp
    val colGap = if (isLandscape) 6.dp else 8.dp
    val dotsPadding = if (isLandscape) 2.dp else 6.dp

    Column(
        verticalArrangement = Arrangement.spacedBy(rowGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Bullet display — shows ●●●● for entered digits.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = dotsPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (pin.isEmpty()) "•••• " else "•".repeat(pin.length),
                style = if (isLandscape) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.headlineSmall,
                color = if (pin.isEmpty()) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.primary,
            )
        }
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫", "0", "✓"),
        )
        val haptic = ai.eight24family.conch.ui.haptic.LocalSshAiHaptics.current
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(colGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (label in row) {
                    androidx.compose.material3.OutlinedButton(
                        enabled = enabled,
                        onClick = {
                            // Per-key haptic: gentle Tick for digits
                            // (you'll press many in a row), Tap for
                            // the action buttons (discrete, weightier).
                            // Without this the PIN entry feels dead —
                            // the user is staring at the dialog
                            // wondering if the press registered.
                            haptic.perform(
                                if (label == "✓" || label == "⌫")
                                    ai.eight24family.conch.ui.haptic.SshAiHaptic.Tap
                                else
                                    ai.eight24family.conch.ui.haptic.SshAiHaptic.Tick
                            )
                            when (label) {
                                "⌫" -> if (pin.isNotEmpty()) onChange(pin.dropLast(1))
                                "✓" -> onSubmit()
                                else -> if (pin.length < maxLen) onChange(pin + label)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(buttonHeight),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            label,
                            style = if (isLandscape) MaterialTheme.typography.titleSmall
                                    else MaterialTheme.typography.titleMedium,
                            color = when (label) {
                                "✓" -> MaterialTheme.colorScheme.primary
                                "⌫" -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
