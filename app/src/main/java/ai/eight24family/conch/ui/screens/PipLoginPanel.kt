package ai.eight24family.conch.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.eight24family.conch.ui.viewmodel.AgentPickerViewModel.LoginRequest

/**
 * Picture-in-Picture panel for an in-flight OAuth login. OAuth forces the user
 * out to the browser; PiP keeps this activity resumed (so the SSH login process
 * survives) and this panel tells them exactly what to do next. Text entry isn't
 * reliable inside a PiP window (the IME can't show over it), so the call to
 * action is "tap to return and paste" — tapping the window expands the app back
 * to the full code-entry dialog (which now survives the resume).
 */
@Composable
fun PipLoginPanel(login: LoginRequest) {
    val accent = MaterialTheme.colorScheme.primary
    val pretty = when (login.agent.name) {
        "GEMINI" -> "Gemini"
        "CLAUDE" -> "Claude Code"
        "CODEX" -> "Codex CLI"
        else -> login.agent.name.lowercase().replaceFirstChar { it.uppercase() }
    }
    val message = when {
        login.submitted -> "Finishing sign-in…"
        login.awaitingPaste && login.callbackMode ->
            "Copy the URL your browser landed on, then tap to return and paste it."
        login.awaitingPaste ->
            "Copy the code from your browser, then tap to return and paste it."
        login.url != null -> "Sign in in your browser — then tap to return."
        else -> login.rawTail.ifBlank { "Starting…" }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Pulsing key dot — cyberpunk "working" beat, cheap to animate.
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "pulseAlpha",
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(pulse)
                    .clip(CircleShape)
                    .background(accent),
            )
            Text(
                text = "$pretty · signing in",
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
            )
        }
    }
}
