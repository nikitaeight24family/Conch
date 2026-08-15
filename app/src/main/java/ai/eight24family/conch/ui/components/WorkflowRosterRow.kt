package ai.eight24family.conch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.ui.viewmodel.ChatViewModel

private fun elapsed(sec: Long): String =
    if (sec >= 60) "${sec / 60}m${sec % 60}s" else "${sec}s"

/**
 * Live background-workflow rows — Conch's answer to the CLI footer's
 * «hikari-web-review · 32/35 agents done · 6m 21s». One line per running
 * ultracode Workflow; counts polled from the workflow journal on the server
 * (see [ChatViewModel.liveWorkflows]). Hidden when nothing is running.
 */
@Composable
fun WorkflowRosterRow(workflows: List<ChatViewModel.LiveWorkflow>, modifier: Modifier = Modifier) {
    if (workflows.isEmpty()) return
    val accent = MaterialTheme.colorScheme.tertiary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        for (w in workflows) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "⚙ ",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    w.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val tail = buildString {
                    append("  ")
                    // «32/35 agents» — only when we have a total; a fresh run
                    // that hasn't spawned yet just shows the elapsed.
                    if (w.total > 0) append("${w.done}/${w.total} agents · ")
                    append(elapsed(w.elapsedSec))
                }
                Text(
                    tail,
                    style = MaterialTheme.typography.labelSmall,
                    color = dim,
                    maxLines = 1,
                )
            }
        }
    }
}
