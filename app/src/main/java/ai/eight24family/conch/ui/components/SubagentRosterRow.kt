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
import androidx.compose.ui.graphics.Color
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
 * Model label for an agent row: the tail of a resolved id is enough to tell
 * agents apart at a glance (`claude-sonnet-5` → `sonnet`), and the full id
 * would eat the whole line on a phone. An alias the CLI never resolved
 * ("sonnet", "inherit") is already short and passes through.
 */
private fun shortModel(model: String?): String? = model
    ?.takeIf { it.isNotBlank() }
    ?.removePrefix("claude-")
    ?.split('-')
    ?.firstOrNull { it.isNotBlank() }

/**
 * Status glyph. ● running, ○ finished cleanly, ✕ failed, ⊘ killed, ◌ queued,
 * ◑ paused. An agent with no status event yet reads as running, which is the
 * safe direction: a spinner that outstays its welcome is visible, an agent
 * silently marked done while it still burns tokens is not.
 */
private fun glyph(a: SubagentRun): String = when {
    a.status == "failed" || a.status == "cancelled" -> "✕ "
    a.status == "killed" -> "⊘ "
    a.status == "queued" -> "◌ "
    a.status == "paused" -> "◑ "
    a.done -> "○ "
    else -> "● "
}

/**
 * The subagent roster — Conch's answer to the CLI footer's
 * "← 1 agent · ↓ to manage" plus its expandable list:
 *
 * ```
 * ▾ 3 agents · 20 total · 1.2M
 *   ● general-purpose · sonnet  Inventory gateway core   49s · 6 tools · 36.6k
 *     ↳ Grep
 *   ○ Explore · haiku  Extract world geography          2m11s · 14 tools · 210k
 *     ↳ found 9 call sites across 4 files
 *   ✕ general-purpose  Clan wars audit                  8s
 *     ↳ tool limit exceeded
 * ```
 *
 * ⚠ EVERY FIELD HERE IS ONE THE CLI ACTUALLY REPORTS, verified by capturing a
 * live `--print --output-format stream-json` run against 2.1.220 rather than
 * guessed from docs: status/tokens/tool-count/duration come from
 * `task_progress` + `task_notification` (`usage{total_tokens, tool_uses,
 * duration_ms}`, `last_tool_name`, `summary`), the model from `resolvedModel` /
 * the agent's own `message.model`.
 *
 * Collapsed by default to a single count line, because a wide fan-out (the
 * user has run twenty at once) would otherwise eat the whole screen on a phone.
 * Tap to expand.
 */
@Composable
fun SubagentRosterRow(
    roster: List<SubagentRun>,
    /**
     * Background commands the agents are running. Reported HERE because that is
     * whose work it is: these events used to be rendered as chat rows, and a
     * fan-out's shell commands buried the conversation under `task · completed ·
     * Background command "…"` lines with nothing to act on (2026-08-18). The
     * session's OWN background command still gets its chat row.
     */
    backgroundTasks: Int = 0,
    modifier: Modifier = Modifier,
) {
    if (roster.isEmpty() && backgroundTasks == 0) return
    var expanded by remember { mutableStateOf(false) }
    val running = roster.count { !it.done }
    val totalTokens = roster.sumOf { it.tokens }

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
                // What the fan-out has cost so far, in the collapsed line —
                // the number the user asks for first and should not have to
                // expand twenty rows to add up by hand.
                if (totalTokens > 0) append(" · ${compactTokens(totalTokens)}")
                // The agents' background commands, as a count. One number here
                // replaces the run of task rows that used to fill the chat.
                if (backgroundTasks > 0) append(" · $backgroundTasks bg")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )

        if (!expanded) return@Column

        for (a in roster) {
            val failed = a.status == "failed" || a.status == "killed" || a.status == "cancelled"
            val glyphColor: Color = when {
                failed -> MaterialTheme.colorScheme.error
                a.done -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.tertiary
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 1.dp),
            ) {
                Text(
                    text = glyph(a),
                    style = MaterialTheme.typography.labelSmall,
                    color = glyphColor,
                )
                Text(
                    text = listOfNotNull(
                        // type · model reads as one identity, then the task.
                        listOfNotNull(a.type, shortModel(a.model)).joinToString(" · ")
                            .takeIf { it.isNotBlank() },
                        a.task,
                    ).joinToString("  ").ifBlank { "agent" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true),
                )
                val tail = listOfNotNull(
                    elapsed(a.elapsedSeconds),
                    a.toolUses?.takeIf { it > 0 }?.let { "$it tool${if (it == 1) "" else "s"}" },
                    a.tokens.takeIf { it > 0 }?.let { compactTokens(it) },
                    // Only worth a word when it isn't already obvious from the
                    // glyph: a running agent that got parked, or a backgrounded
                    // one that outlived the turn.
                    "queued".takeIf { a.status == "queued" },
                    "paused".takeIf { a.status == "paused" },
                    "bg".takeIf { a.backgrounded && !a.done },
                ).joinToString(" · ")
                if (tail.isNotEmpty()) {
                    Text(
                        text = "  $tail",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }
            // Second line, only when it says something the first one can't: why
            // it failed, what it concluded, or what it is running right now.
            // Never a placeholder — no sub-line beats an empty one.
            val sub = a.error?.takeIf { it.isNotBlank() }
                ?: a.summary?.takeIf { it.isNotBlank() && a.done }
                ?: a.lastTool?.takeIf { it.isNotBlank() && !a.done }
            if (sub != null) {
                Text(
                    text = "↳ ${sub.take(120)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (a.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
        }
    }
}
