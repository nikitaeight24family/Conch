package ai.eight24family.conch.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.SubagentRun

/** "36.6k" / "980" — the CLI's own compact token shorthand. */
private fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}

private fun elapsed(sec: Long?): String? = sec?.let {
    if (it >= 60) "${it / 60}m${it % 60}s" else "${it}s"
}

/**
 * The subagent roster — Conch's answer to the CLI footer's
 * "← 1 agent · ↓ to manage" plus its expandable list:
 *
 * ```
 * ● general-purpose  Inventory HPAF gateway core   49s · 36.6k
 * ```
 *
 * Collapsed by default to a single count line, because a wide fan-out (the
 * user has run eight at once) would otherwise eat the whole screen on a phone.
 * Tap to expand.
 */
@Composable
fun SubagentRosterRow(roster: List<SubagentRun>, modifier: Modifier = Modifier) {
    if (roster.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val running = roster.count { !it.done }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(
            text = buildString {
                append(if (expanded) "▾ " else "▸ ")
                // Count RUNNING agents, like the CLI's "← 1 agent"; the done
                // ones stay listed but must not inflate the live number.
                append(if (running > 0) "$running agent${if (running == 1) "" else "s"}" else "agents")
                if (running != roster.size) append(" · ${roster.size} total")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )

        if (!expanded) return@Column

        for (a in roster) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 1.dp),
            ) {
                Text(
                    text = if (a.done) "○ " else "● ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (a.done) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
                Text(
                    text = listOfNotNull(a.type, a.task).joinToString("  ").ifBlank { "agent" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true),
                )
                val tail = listOfNotNull(
                    elapsed(a.elapsedSeconds),
                    a.tokens.takeIf { it > 0 }?.let { compactTokens(it) },
                ).joinToString(" · ")
                if (tail.isNotEmpty()) {
                    Text(
                        text = "  $tail",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
