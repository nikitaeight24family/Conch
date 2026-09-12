package ai.eight24family.conch.ui.screens

import ai.eight24family.conch.agent.HandoffAdvice
import ai.eight24family.conch.agent.codex.CodexDaemon
import ai.eight24family.conch.ui.components.CopyableCodeBlock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WHAT THE APP COULD NOT FIX BY ITSELF — and exactly what to type to finish it.
 *
 * ⛔ NOT AN OFFER, AND NO BUTTON THAT DOES WORK. The owner's rule (2026-09-12):
 * By the time this renders, the app has already started the shared app-server,
 * updated that server's codex through the installer cascade, and tried to write
 * the shell hook. What is on screen is the part that is impossible from here: a
 * shell Conch cannot edit, a package manager that refused, or a CLI with no
 * shared mode at all (Claude).
 *
 * Three sections: what happened (one line — the transcript already carries the
 * record), why it is not perfect yet, and the exact manual step. A command
 * appears ONLY here, where it is the honest answer rather than an excuse, and
 * it is tap-to-copy because nobody retypes a command off a phone screen.
 *
 * Nothing is a template: every value comes from that server's own binary and
 * its own shell.
 */
@Composable
internal fun SessionHandoffDialog(
    advice: HandoffAdvice,
    onDismiss: (dontShowAgain: Boolean) -> Unit,
) {
    var suppress by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
    val isCodex = advice.cli == "codex"
    val pidPart = if (advice.pid > 0) ", pid ${advice.pid}" else ""

    AlertDialog(
        onDismissRequest = { onDismiss(suppress) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A hairline of accent instead of an icon: the chat's own visual
                // language, and it does not read as an alarm — the session WAS
                // carried on; only the setup is short of perfect.
                Box(
                    Modifier
                        .width(3.dp)
                        .height(20.dp)
                        .background(scheme.primary, RoundedCornerShape(2.dp)),
                )
                Text(
                    "  one step left on this server",
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Section(label = "WHAT HAPPENED", scheme = scheme) {
                    Body(
                        "This session was open in a ${advice.cli} terminal on the server " +
                            "(${advice.where}$pidPart). Conch carried it on here and ended that " +
                            "copy — ${advice.cli} allows one writer and hands it over only when a " +
                            "process exits. The transcript lost nothing; what that terminal had " +
                            "typed but not sent is gone.",
                        scheme,
                    )
                }
                Section(label = "WHY IT IS NOT AUTOMATIC YET", scheme = scheme) {
                    Body(reasonLine(advice), scheme)
                }
                Section(label = "DO THIS ONCE", scheme = scheme, labelColor = scheme.primary) {
                    when {
                        isCodex && advice.sharedBrain -> {
                            Body(
                                "Conch already runs the shared app-server on that machine; only " +
                                    "your shell could not be wired to it. Start the terminal side " +
                                    "with:",
                                scheme,
                            )
                            CopyableCodeBlock(
                                text = CodexDaemon.TERMINAL_COMMAND,
                                modifier = Modifier.fillMaxWidth(),
                                style = mono,
                            )
                            Body(
                                "Then both sides hold the same session and neither ends the other. " +
                                    "A plain `codex` starts a second app-server — that is the one " +
                                    "that gets ended.",
                                scheme,
                            )
                        }

                        isCodex -> {
                            Body(
                                "Update codex on that machine and everything else is automatic — " +
                                    "Conch starts the shared app-server and wires the terminal to " +
                                    "it by itself:",
                                scheme,
                            )
                            CopyableCodeBlock(
                                text = CodexDaemon.UPDATE_COMMAND,
                                modifier = Modifier.fillMaxWidth(),
                                style = mono,
                            )
                            Body(
                                "Installed another way? `brew upgrade codex`, your distro's package " +
                                    "manager, or `codex update` — whichever put it there. Until " +
                                    "then Conch keeps carrying the session over, which costs that " +
                                    "terminal's unsent input each time.",
                                scheme,
                            )
                        }

                        else -> {
                            Body(
                                "Claude has no shared mode to switch on — one process owns a " +
                                    "session at a time, and a second one forks the history instead " +
                                    "of refusing. Conch keeps handing the session over for you; to " +
                                    "lose nothing at all, leave the terminal session (Ctrl-D) " +
                                    "before you carry on here.",
                                scheme,
                            )
                            Body(
                                "The other direction needs nothing: background Conch and the phone " +
                                    "releases the session after 90 seconds, so that terminal " +
                                    "resumes it cleanly.",
                                scheme,
                            )
                        }
                    }
                }
                HorizontalDivider(color = scheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = suppress,
                            onValueChange = { suppress = it },
                            role = Role.Checkbox,
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = suppress,
                        // null = the whole ROW is the toggle (the label is the
                        // easiest thing to hit on a phone), and Compose then
                        // treats the box as decoration for accessibility.
                        onCheckedChange = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "  Don't show this again",
                        color = scheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(suppress) }) {
                Text("Got it", fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

/**
 * The honest one-liner for why the app stopped short. Every branch is an
 * outcome the server actually reported — never a generic "something failed".
 */
private fun reasonLine(advice: HandoffAdvice): String = when {
    advice.cli != "codex" ->
        "Claude ships no daemon and no socket to join, so there is nothing here for Conch to set up."

    !advice.sharedBrain && advice.cliVersion.isNotBlank() ->
        "That server runs codex ${advice.cliVersion}, which has no shared app-server. Conch tried to " +
            "update it and could not — usually no package manager it can reach, or an install that " +
            "needs a password."

    !advice.sharedBrain ->
        "That server's codex has no shared app-server, and Conch could not update it from here."

    advice.unsupportedShell != null ->
        "The shared app-server is running, but that account uses ${advice.unsupportedShell}, which " +
            "Conch does not know how to configure."

    else ->
        "The shared app-server is running, but Conch could not write that account's shell config — " +
            "usually a read-only home directory."
}

@Composable
private fun Section(
    label: String,
    scheme: ColorScheme,
    labelColor: Color = scheme.outline,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            color = labelColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        content()
    }
}

@Composable
private fun Body(text: String, scheme: ColorScheme) {
    Text(
        text,
        color = scheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}
