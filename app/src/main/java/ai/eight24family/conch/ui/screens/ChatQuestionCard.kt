package ai.eight24family.conch.ui.screens

import ai.eight24family.conch.agent.AgentMessage
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Interactive option picker for [AgentMessage.AskUserQuestion] — Claude
 * Code's AskUserQuestion tool, delivered over the persistent control
 * channel. Mirrors the CLI's own chip picker: per question a small
 * accent header, the question text, then one `[ label ]` row per option
 * with its description underneath.
 *
 * Interaction contract:
 *  - single question + single-select (the dominant case): one tap on an
 *    option answers immediately — no extra confirm step;
 *  - multiSelect or multiple questions: taps toggle/replace the
 *    selection; a `[ answer ]` action commits once EVERY question has at
 *    least one pick;
 *  - once [AgentMessage.AskUserQuestion.answers] is non-null the card is
 *    frozen: chosen options stay highlighted, taps are dead, a dim
 *    "answered" footer appears. The freeze comes from
 *    `AgentSessionHistory.resolveQuestion` (in-place upsert), so it
 *    survives recomposition and reopen-from-history.
 *
 * Same in-message-buttons pattern as `PermissionLine` — the user already
 * knows it from `[ allow ] / [ deny ]`.
 */
@Composable
internal fun QuestionCard(
    msg: AgentMessage.AskUserQuestion,
    onAnswer: (Map<Int, List<String>>) -> Unit,
) {
    val resolved = msg.answers != null
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    // Local picks while the card is live. Keyed by message id so a
    // different question card never inherits stale selections.
    val picked = remember(msg.id) { mutableStateMapOf<Int, List<String>>() }
    val singleTapMode = msg.questions.size == 1 && !msg.questions[0].multiSelect

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        msg.questions.forEachIndexed { qi, q ->
            if (qi > 0) Spacer(Modifier.height(10.dp))
            if (q.header.isNotBlank()) {
                Text(
                    "// ${q.header}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                q.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
            val chosenNow = msg.answers?.get(qi) ?: picked[qi].orEmpty()
            q.options.forEach { opt ->
                val isChosen = opt.label in chosenNow
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !resolved && !msg.readOnly) {
                            if (singleTapMode) {
                                // One tap = the answer. No confirm step.
                                onAnswer(mapOf(0 to listOf(opt.label)))
                                return@clickable
                            }
                            val cur = picked[qi].orEmpty()
                            picked[qi] = when {
                                q.multiSelect && isChosen -> cur - opt.label
                                q.multiSelect -> cur + opt.label
                                else -> listOf(opt.label)
                            }
                        }
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        if (isChosen) "[x] ${opt.label}" else "[ ] ${opt.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isChosen) accent else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (opt.description.isNotBlank()) {
                        Text(
                            opt.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = outline,
                            modifier = Modifier.padding(start = 22.dp),
                        )
                    }
                }
            }
        }
        when {
            // Mirror of a console-driven turn — the options are shown for reading,
            // but the answer has to be given in the CLI session that asked.
            msg.readOnly -> Text(
                "↪ answer this in your CLI session",
                style = MaterialTheme.typography.labelSmall,
                color = outline,
                modifier = Modifier.padding(top = 6.dp),
            )
            resolved -> Text(
                "answered",
                style = MaterialTheme.typography.labelSmall,
                color = outline,
                modifier = Modifier.padding(top = 6.dp),
            )
            !singleTapMode -> {
                val complete = msg.questions.indices.all { !picked[it].isNullOrEmpty() }
                Row {
                    TextButton(
                        onClick = {
                            if (complete) onAnswer(picked.mapValues { it.value.toList() })
                        },
                        enabled = complete,
                    ) {
                        Text(
                            "[ answer ]",
                            color = if (complete) accent else outline,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
