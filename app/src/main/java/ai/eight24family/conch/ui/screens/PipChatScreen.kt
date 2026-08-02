package ai.eight24family.conch.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.SessionState

/**
 * **Picture-in-Picture rendering surface.**
 *
 * Design principle (after the first iteration was correctly called
 * out as bloated): only show what the user *can't get from anywhere
 * else* and *needs while glancing*.
 *
 * In a ~160dp wide window, every line of UI chrome takes a third of
 * the readable real estate. So:
 *
 *  - **No header.** User knows which server and agent they're on
 *    — they tapped into this chat 30 seconds ago. Wasting a row on
 *    "❯ 824 Server · claude" is just shouting metadata back at them.
 *  - **No echo of their own prompt.** They typed it; they don't
 *    need to read it again.
 *  - **No "N tokens" counter.** Tokens don't tell a user anything
 *    actionable. Real users don't price-shop by length.
 *  - **One status glyph in the corner.** That's the only thing
 *    they're glancing for: "is it still going, did it finish, did
 *    it fail." A single character in 12dp is enough. ✶ = generating,
 *    ✓ = done, ✕ = failed. Nothing for plain idle (no in-flight
 *    work, no reason to draw attention).
 *
 * The entire window is the assistant's most-recent reply. Auto-
 * scrolls to the bottom on every append so newly arriving tokens
 * are always visible.
 */
@Composable
fun PipChatScreen(session: AgentSession) {
    val state by session.state.collectAsState()
    val history by session.history.collectAsState()

    val scrollState = rememberScrollState()
    LaunchedEffect(history.size, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    // **Don't echo a stale reply during a new turn.** Find the last
    // user message — only an AssistantText that landed AFTER that
    // counts as "the current turn's reply". While the agent is Working
    // on a brand-new prompt, `history.last { it is AssistantText }`
    // would otherwise return the PREVIOUS turn's reply, leaving PiP
    // showing yesterday's wall of text while the user thinks "is it
    // even working?". Per user direction:.
    val lastUserIdx = history.indexOfLast { it is AgentMessage.UserText }
    val currentTurnAssistant = if (lastUserIdx >= 0) {
        // Walk forward from after the last user msg; take the last
        // AssistantText in that range. If none yet → null → thinking
        // animation.
        history.asSequence()
            .drop(lastUserIdx + 1)
            .lastOrNull { it is AgentMessage.AssistantText }
            as? AgentMessage.AssistantText
    } else {
        // No user message yet — fall back to whatever assistant text
        // exists (cold open showing the chat's resume context).
        history.lastOrNull { it is AgentMessage.AssistantText }
            as? AgentMessage.AssistantText
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Body — full window, just the agent's reply. Padding kept
        // minimal so the maximum number of characters fits.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(scrollState),
        ) {
            val text = currentTurnAssistant?.text.orEmpty()
            when {
                text.isNotBlank() -> {
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Trailing cursor when still streaming — gives the
                    // user a visual "tokens are landing" cue without a
                    // separate spinner row.
                    if (state == SessionState.Working) {
                        Text(
                            "▌",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                state is SessionState.Bootstrapping ||
                    state == SessionState.Working -> {
                    // No tokens yet — show a "thinking" animation.
                    // Three dots that pulse so the user knows the
                    // channel is alive and waiting for the agent's
                    // first byte.
                    ThinkingDots()
                }
                state is SessionState.Failed -> {
                    val reason = (state as SessionState.Failed).reason
                    Text(
                        reason.lineSequence().firstOrNull().orEmpty().take(120),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Plain Running / Idle / Closed with no reply yet —
                // empty body. The user came here looking for the
                // last reply; there isn't one. Showing "idle" or
                // "ready" would just be noise.
            }
        }

        // Single status glyph, top-right, 12sp — the only thing the
        // user is glancing for while in another app.
        StatusGlyph(
            state = state,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        )
    }
}

/**
 * Three-dot pulsing animation rendered while the agent is Bootstrapping
 * or Working with no tokens emitted yet. Visual replacement for the
 * earlier single-cursor "▌" — communicates "the agent is thinking"
 * rather than "the channel is alive", which is what the user actually
 * wants to know in PiP.
 *
 * Implementation: 3 dots, each alpha-modulates from 0.25 → 1.0 with a
 * 200ms phase offset between them. Loops forever via
 * `rememberInfiniteTransition`. Compositional cost is trivial — three
 * Float animations and three Text drawls.
 */
@Composable
private fun ThinkingDots() {
    // Same trick as ChatScreen's AgentThinkingRow: hold the State<Float>
    // and read .value INSIDE drawBehind so it's a draw-time
    // subscription (GPU redraw) and NOT a composition subscription
    // (60-120 recompositions/sec for a PiP window that's already
    // small enough to feel every dropped frame).
    val infinite = rememberInfiniteTransition(label = "pip-thinking")
    val phaseState = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pip-phase",
    )
    val accent = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.Spacer(
        modifier = Modifier
            .padding(start = 2.dp)
            .size(width = 44.dp, height = 18.dp)
            .drawBehind {
                val phase = phaseState.value
                val dotRadius = 3.dp.toPx()
                val spacing = 12.dp.toPx()
                for (i in 0..2) {
                    val a = ((phase - i).coerceIn(0f, 1f))
                        .let { if (it < 1f) it else 2f - it }
                        .coerceIn(0.15f, 1f)
                    drawCircle(
                        color = accent.copy(alpha = a),
                        radius = dotRadius,
                        center = androidx.compose.ui.geometry.Offset(
                            spacing * i + dotRadius,
                            size.height / 2,
                        ),
                    )
                }
            },
    )
}

@Composable
private fun StatusGlyph(state: SessionState, modifier: Modifier = Modifier) {
    val (glyph, color) = when (state) {
        SessionState.Working -> "✶" to MaterialTheme.colorScheme.primary
        is SessionState.Bootstrapping -> "·" to MaterialTheme.colorScheme.outline
        is SessionState.Failed -> "✕" to MaterialTheme.colorScheme.error
        // Running = the agent is alive and waiting for a turn.
        // After a turn completes this is the state we land in;
        // showing a checkmark for ~a moment helps the user spot
        // "ok the reply finished" while glancing. We can't easily
        // tell "just finished" vs "long-idle" from state alone, so
        // we err on the side of always showing ✓ for Running — the
        // worst case is a green tick stays up. Beats silence.
        SessionState.Running -> "✓" to MaterialTheme.colorScheme.tertiary
        SessionState.Idle, SessionState.Closed -> null to MaterialTheme.colorScheme.outline
    }
    if (glyph != null) {
        Text(
            glyph,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = modifier,
        )
    }
}
