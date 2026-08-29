package ai.eight24family.conch.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.SubagentRun

/**
 * The subagent roster — Conch's answer to the CLI footer's
 * "← 1 agent · ↓ to manage" plus its expandable list:
 *
 * ```
 * ▾ 5 agents · 2 live · 3m17s · 246k · opus · general-purpose
 *   ● Translate terminal+common to zh              2m48s · 49.4k
 *     ↳ Grep · 4 tools
 *   ○ Translate landing/verify/forgot              3m17s · 51.0k
 *   ✕ Clan wars audit                                        8s
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
 * WHAT THIS COMPOSABLE OWNS: colour, the fill, and the two levels of
 * expansion. WHAT IT DOES NOT: any decision about which field appears where —
 * that is [layoutRoster] / [headline] / [summarize], pure and tested, and the
 * reasoning for the whole layout lives in `SubagentRosterDisplay.kt`.
 *
 * ── THREE LEVELS, EACH ANSWERING ONE QUESTION ──
 *
 *  • collapsed  — how many, how many alive, how long, what it cost, on what.
 *  • expanded   — per agent: alive? doing what? costing how much (text AND a
 *                 comparative fill)?
 *  • row tapped — that agent's type, model, tools, share of the fan-out, and
 *                 its own words.
 *
 * Collapsed by default: a wide fan-out (the user has run twenty at once) would
 * otherwise eat the whole screen on a phone.
 *
 * ── COLOUR IS A THREE-TIER HIERARCHY, NOT DECORATION ──
 *
 * Now
 *
 *  1. `onSurface`        — the TASK. What the agent is doing is the row.
 *  2. `onSurfaceVariant` — the identity column (only what the header did not
 *                          hoist) and the live `↳` state line.
 *  3. `outline`          — the metrics text. Dim on purpose: the FILL below
 *                          already carries the comparison, so the digits are
 *                          for confirming, not scanning.
 *
 * The glyph is the one exception — it carries state colour (tertiary alive /
 * outline done / error dead) because state is the only thing that gets to
 * shout.
 *
 * ── THE ROW FILL IS A LAW, NOT A GRADIENT ──
 *
 * A horizontal bar drawn behind each row, width = that agent's tokens against
 * the fan-out's biggest spender. Its ONLY meaning is token share; nothing else
 * in Conch draws a row-background fill, so the moment the user sees one they
 * know what it measures. It never encodes status, never animates, and is
 * always the same hue as the row's state glyph at [FILL_ALPHA_LIVE] /
 * [FILL_ALPHA_DONE] — an agent cannot look expensive because it failed, or
 * cheap because it finished.
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
    // Which agents the user has opened. A SET, not a single key: on a fan-out
    // the interesting comparison is between two agents' summaries, and closing
    // one to read the next would make that comparison impossible.
    var openRows by remember { mutableStateOf(emptySet<String>()) }

    val summary = summarize(roster, backgroundTasks)
    val rows = layoutRoster(roster, summary)

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text(
            text = headline(summary, expanded),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        )

        if (!expanded) return@Column

        // ⛔ THE COMPOSER OUTRANKS THIS PANEL. The roster is PINNED above the
        // prompt (not a list item), so an expanded fan-out grows the pinned block
        // directly: 13 agents at two lines each, stacked on top of an expanded
        // usage panel, pushed the input row clean off the bottom of the screen —
        // the message list holds the only `weight`, so it collapses to nothing and
        // everything below simply overflows. Bound it to a fraction of the screen
        // and let the roster scroll inside itself; a fan-out can be any size, the
        // screen cannot.
        val maxRosterHeight = LocalConfiguration.current.screenHeightDp.dp * ROSTER_MAX_SCREEN_FRACTION
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxRosterHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            for (r in rows) {
                val stateColor: Color = when (r.state) {
                    RowState.FAILED -> MaterialTheme.colorScheme.error
                    RowState.DONE -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.tertiary
                }
                val open = r.key in openRows
                val fillAlpha = if (r.state == RowState.DONE) FILL_ALPHA_DONE else FILL_ALPHA_LIVE
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            openRows = if (open) openRows - r.key else openRows + r.key
                        }
                        // THE FILL. Drawn behind the row and its sub-line together,
                        // so one agent reads as one block of cost. Width is read
                        // inside drawBehind — a draw-time subscription, so a
                        // progress tick that moves the bar invalidates the GPU
                        // layer instead of recomposing the whole panel (the same
                        // rule AgentThinkingRow follows).
                        .drawBehind {
                            if (r.share <= 0f) return@drawBehind
                            drawRect(
                                color = stateColor.copy(alpha = fillAlpha),
                                size = Size(size.width * r.share, size.height),
                            )
                        }
                        .padding(start = 10.dp, top = 1.dp, bottom = 1.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = r.glyph + " ",
                            style = MaterialTheme.typography.labelSmall,
                            color = stateColor,
                        )
                        r.identity?.let {
                            // Only reached on a MIXED fan-out — on a uniform one the
                            // header said it once and these characters go to the task.
                            Text(
                                text = "$it  ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = r.task,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = true),
                        )
                        if (r.metrics.isNotEmpty()) {
                            Text(
                                text = "  ${r.metrics}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (r.state == RowState.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                maxLines = 1,
                            )
                        }
                    }
                    // STATE line — never a result (see rule 2 in the display file).
                    r.sub?.let {
                        Text(
                            text = "↳ $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (r.state == RowState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                    if (open) {
                        // The agent's full identity and cost, spelled out — the row
                        // has no width for this and the header speaks for the whole
                        // fan-out, so a tap is the only honest place for it.
                        Text(
                            text = r.detailMeta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 14.dp, top = 1.dp),
                        )
                        // Its own words. WRAPPED, not ellipsized: the user opened
                        // this row precisely to read them, and four lines of an
                        // agent's conclusion is what the old one-line `↳` was
                        // failing to be.
                        r.detailText?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (r.state == RowState.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 14.dp, top = 1.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Alpha of the token-share fill. Two values only: a live agent's cost is the
 *  one still moving, so it gets the readable tint; a finished agent stays on
 *  the panel for reference and must not out-shout it. Both are low enough that
 *  the text on top keeps full contrast in either theme. */
/** How much of the screen an expanded roster may claim before it starts
 *  scrolling inside itself. Chosen against the other pinned panel: the usage
 *  bar is capped at 0.28, so even both open at once leave the prompt row and a
 *  slice of the conversation on screen. */
private const val ROSTER_MAX_SCREEN_FRACTION = 0.38f

private const val FILL_ALPHA_LIVE = 0.14f
private const val FILL_ALPHA_DONE = 0.07f
