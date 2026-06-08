package ai.eight24family.conch.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.SessionState

/**
 * "Agent is thinking" indicator row — sits at the bottom of the message
 * list while a turn is in flight and no assistant tokens have streamed in
 * yet. Cyberpunk-CLI feel: three blinking dots, staggered.
 */
@Composable
internal fun AgentThinkingRow() {
    val accent = MaterialTheme.colorScheme.primary
    val infinite = rememberInfiniteTransition(label = "thinking")
    // Hold the State<Float> directly (no `by` delegate) so that the
    // value read happens INSIDE `drawBehind` below — that's a draw-
    // time subscription, which invalidates the GPU layer only.
    // Reading `phase` at composition scope (`val phase by …`) would
    // make Compose recompose this whole composable AND its parent
    // 60-120 times/sec for the entire duration the agent is
    // "thinking" — every parent Column row gets re-evaluated, layout
    // passes pile up, scroll gestures stutter.
    val phaseState = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    // Single Spacer + drawBehind: ZERO Text widgets, ZERO Compose
    // recomposition while animating, GPU just redraws three circles
    // each frame. Width / height fixed so the parent Column never
    // re-measures during the animation either.
    Spacer(
        modifier = Modifier
            .padding(top = 6.dp, bottom = 12.dp, start = 4.dp)
            .height(14.dp)
            .width(36.dp)
            .drawBehind {
                val phase = phaseState.value
                val dotRadius = 2.5.dp.toPx()
                val spacing = 10.dp.toPx()
                for (i in 0..2) {
                    val a = ((phase - i).coerceIn(0f, 1f))
                        .let { if (it < 1f) it else 2f - it }
                        .coerceIn(0.15f, 1f)
                    drawCircle(
                        color = accent.copy(alpha = a),
                        radius = dotRadius,
                        center = Offset(
                            spacing * i + dotRadius,
                            size.height / 2,
                        ),
                    )
                }
            },
    )
}

@Composable
internal fun StatusLine(state: SessionState, reconnecting: Boolean = false) {
    // Quiet by default — the working indicator that used to live up here
    // moved to the bottom (right above the prompt bar) where the user is
    // actually looking. Only surface things the user has to act on now:
    // a hard failure (with reason) or an explicitly closed session.
    //
    // SEAMLESS: a silent device-key auto-reconnect must be INVISIBLE — the chat
    // just keeps showing its messages while the transport comes back
    // underneath. So: render nothing while reconnecting, and never render the
    // dedicated "disconnected" signal that DRIVES that reconnect (set by
    // AgentSessionRunOneShot on a dead transport) — it's not a user-facing
    // error, it's the trigger. That was the red "err disconnect".
    if (reconnecting) return
    val (label, color, progress) = when (state) {
        SessionState.Idle -> return
        is SessionState.Bootstrapping -> return
        SessionState.Running -> return
        SessionState.Working -> return
        is SessionState.Failed -> {
            if (state.reason == "disconnected") return
            Triple("ERR · ${state.reason}", MaterialTheme.colorScheme.error, false)
        }
        SessionState.Closed -> Triple("disconnected — tap ⟳", MaterialTheme.colorScheme.onSurfaceVariant, false)
    }
    Column {
        Text(
            text = "── $label",
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (progress) {
            LinearProgressIndicator(
                color = color,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
            )
        }
    }
}

/**
 * Working sparkle — single ✦ glyph that breathes (alpha 0.25 ↔ 1.0
 * over 1.4 s, ease-in-out) with a slow 8 s rotation. Cyberpunk
 * Anthropic-thinking vibe; minimal footprint so it can sit in the
 * empty gutter of the prompt bar without pushing anything around.
 * `infiniteTransition` is Compose-native (frame-clock-driven), so
 * it doesn't burn coroutines.
 */
@Composable
internal fun WorkingSparkle(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "working-sparkle")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1400,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 8000,
                easing = LinearEasing,
            ),
        ),
        label = "rotation",
    )
    Text(
        text = "✦",
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.graphicsLayer { rotationZ = rotation },
    )
}

/**
 * One-line status comment shown under the prompt bar. Returns null in
 * the happy path AND while the SSH session is bootstrapping — the user
 * shouldn't see "connecting…" / "idle…" because for them the chat is
 * already usable: they can type and send, the VM buffers everything
 * until the session is Running. Only surface things the user has to
 * actually see: a hard failure, a closed session that needs a tap to
 * reopen, or an in-flight attachment upload that's blocking send.
 */
internal fun promptBarStatusHint(
    state: SessionState,
    anyUploading: Boolean,
    reconnecting: Boolean,
    reconnectAttempt: Int,
    inputBlank: Boolean,
    attachmentsEmpty: Boolean,
    hasPending: Boolean = false,
): String? = when {
    // SEAMLESS: while the chat is silently auto-reconnecting (device key), show
    // NOTHING — no "queued", no "failed", no "disconnected". The reconnect must
    // be invisible.
    reconnecting -> null
    anyUploading -> "// uploading attachment(s)…"
    state is SessionState.Failed ->
        "// agent: failed — ${state.reason.take(60)} · pull-down to retry"
    state is SessionState.Closed -> "// agent: disconnected · pull-down to retry"
    // NO "queued" hint — buffered sends flush silently (user hated "QUED").
    // While the agent is working we no longer write a hint here — the
    // animated braille spinner above the prompt bar speaks for itself.
    else -> null
}
