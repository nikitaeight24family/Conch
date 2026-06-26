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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.SessionState

/**
 * Live "Compacting conversation…" row — parity with the CLI's own TUI
 * indicator. Driven by `system/status{status=compacting}` and replaced
 * in place (same message id, history upsert) by the `compact_boundary`
 * summary divider. Same draw-time-only animation discipline as
 * [AgentThinkingRow]: the trailing dots animate inside drawBehind, ZERO
 * recomposition while the (potentially minutes-long) compaction runs.
 */
@Composable
internal fun CompactingRow() {
    val accent = MaterialTheme.colorScheme.tertiary
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp, start = 4.dp),
    ) {
        Text(
            "✻ Compacting conversation",
            style = MaterialTheme.typography.bodySmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
        val infinite = rememberInfiniteTransition(label = "compacting")
        val phaseState = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        Spacer(
            modifier = Modifier
                .padding(start = 6.dp)
                .height(10.dp)
                .width(28.dp)
                .drawBehind {
                    val phase = phaseState.value
                    val dotRadius = 2.dp.toPx()
                    val spacing = 8.dp.toPx()
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
}

/**
 * Live reasoning-token counter — transient row rendered ABOVE the
 * working spinner while `system/thinking_tokens` events stream in;
 * removed (list item disappears) the moment the turn ends. The value
 * recomposes ~once per event, which is a single small Text — cheap.
 */
@Composable
internal fun ThinkingTokensRow(tokens: Long) {
    // Locale.US — the ru default formats "12,0k" with a comma.
    val label = if (tokens >= 1000) {
        "%.1fk".format(java.util.Locale.US, tokens / 1000.0)
    } else tokens.toString()
    Text(
        "✻ thinking · $label tokens",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp, start = 4.dp),
    )
}

/**
 * Live working-status row — a faithful re-creation of Claude Code's own
 * TUI status line («✢ Improvising… (28s · ↓ 3.6k tokens)»). The running
 * gerund and the glyph cycle are TUI flair the CLI renders client-side —
 * they are NOT transmitted in stream-json, so we reproduce the LOOK rather
 * than parse a non-existent field (user, 2026-06-13). What IS real: the
 * elapsed timer (from [startMs], the instant this turn's work began) and
 * the live token count ([thinkingTokens], the CLI's `thinking_tokens` /
 * cumulative reasoning feed). Replaces the old 3-dot spinner.
 *
 * One ~250 ms ticker drives glyph (every tick), gerund (every ~5 s) and
 * elapsed (whole seconds). Only THIS row recomposes — it's its own
 * LazyColumn item, so siblings/scroll are untouched.
 */
@Composable
internal fun WorkingStatusRow(
    startMs: Long,
    thinkingTokens: Long?,
    effort: String? = null,
    /** True only in the THINKING phase (the model is generating, last session
     *  event is a `user` turn). The CLI shows «… with xhigh effort» only then —
     *  during a tool run its status is just «Wibbling… (15m · 26.7k)» with no
     *  effort phrase (user, 2026-06-13). So the suffix is gated on this. */
    thinking: Boolean = false,
    /** True when a MIRRORED turn has been "thinking" with the file frozen too
     * long — the agent is almost certainly blocked on a console-side question
     * that the app can't see (it's in the CLI's RAM, not the file). Replaces the
     * misleading "thinking…" spinner with a clear "answer on the server" nudge.
     * */
    waitingForInput: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var now by remember { androidx.compose.runtime.mutableStateOf(startMs) }
    androidx.compose.runtime.LaunchedEffect(startMs) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(250)
        }
    }
    val elapsedS = if (startMs > 0L) ((now - startMs) / 1000L).coerceAtLeast(0L) else 0L
    // Blocked on a console-side prompt: drop the fake "thinking" spinner entirely
    // and tell the user where to answer. Still shows how long it's been waiting.
    if (waitingForInput) {
        val waitLabel = if (elapsedS >= 60L) "${elapsedS / 60L}m${elapsedS % 60L}s" else "${elapsedS}s"
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                "⏸ waiting for your answer ($waitLabel) — open the server session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        return
    }
    val glyph = WORK_GLYPHS[((now / 250L) % WORK_GLYPHS.size).toInt()]
    // ONE gerund per turn — picked from the turn-start, NOT re-rolled as time
    // passes. startMs is NOT actually constant within a turn: the feeder switches
    // it from the local fallback to the server-parsed prompt timestamp ~one poll
    // later, which flipped the word once per turn. Latch the FIRST positive start
    // we see (remember keyed on "have we anchored yet" holds it across that
    // switch) and reset only when the row leaves composition at turn end (audit,
    // 2026-06-14).
    val verbSeed = remember(startMs > 0L) { startMs }
    val verb = WORK_VERBS[(((verbSeed / 1000L) % WORK_VERBS.size + WORK_VERBS.size) % WORK_VERBS.size).toInt()]
    // Minutes like the CLI's «(1m13s · …)» — not a raw «104s». Compact (no space
    // inside «1m13s») so the whole line fits one row (user, 2026-06-14).
    val elapsedLabel = if (elapsedS >= 60L) "${elapsedS / 60L}m${elapsedS % 60L}s" else "${elapsedS}s"
    // Token count: the live thinking_tokens feed when WE drive the turn (a
    // mirrored console turn writes no running count to the file → omitted).
    val tokLabel = thinkingTokens?.takeIf { it > 0 }?.let {
        val k = if (it >= 1000) "%.1fk".format(java.util.Locale.US, it / 1000.0) else it.toString()
        " · ↓$k"
    }.orEmpty()
    // Effort ONLY while thinking (the CLI drops it mid-tool). Compact — just the
    // value; the full "effort" word lives in the topbar.
    val effortLabel = if (thinking) effort?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() else ""
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        // FIXED row height — the cycling glyphs (✶✻✽✢ vs the small `·`) have
        // different intrinsic heights, so an unconstrained row jittered vertically
        // when `·` appeared (user, 2026-06-13). Pin height + center → stable.
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(20.dp),
    ) {
        // Fixed-size glyph cell so the cycling symbols never shift layout (H or V).
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.width(13.dp).fillMaxHeight(),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(glyph, style = MaterialTheme.typography.bodySmall, color = accent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(4.dp))
        Text("$verb…", style = MaterialTheme.typography.bodySmall, color = accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
        // Gap after «…» before the stats — a Spacer, since a trailing space in a
        // Text gets trimmed.
        Spacer(Modifier.width(6.dp))
        Text(
            "$elapsedLabel$tokLabel$effortLabel",
            style = MaterialTheme.typography.bodySmall,
            color = dim,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Glyph cycle — same family Claude's TUI rotates through. */
private val WORK_GLYPHS = listOf("✶", "✻", "✽", "✢", "·", "✢", "✽", "✻")

/** Running gerunds — Claude Code's own spinner vocabulary (a subset). */
private val WORK_VERBS = listOf(
    "Working", "Thinking", "Pondering", "Brewing", "Conjuring", "Crafting",
    "Computing", "Synthesizing", "Cogitating", "Ruminating", "Noodling",
    "Percolating", "Marinating", "Simmering", "Mulling", "Imagining",
    "Ideating", "Inferring", "Forging", "Generating", "Improvising",
    "Tinkering", "Vibing", "Herding", "Manifesting", "Processing",
)

/**
 * "Agent is thinking" indicator row — legacy 3-dot spinner. The chat now
 * uses [WorkingStatusRow]; kept for any other caller.
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
