package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.ui.viewmodel.ChatViewModel

/**
 * Rewind sheet for one user turn: two clearly separated actions.
 *
 *  - **conversation** — reversible in the sense that matters: the discarded
 *    branch stays in the server's transcript, nothing on disk moves.
 *  - **files** — overwrites the user's working tree, so it is NEVER implicit.
 *    The dry run runs BEFORE the sheet offers it, and the button only appears
 *    once we can name what would change. If the CLI says it cannot (no
 *    checkpoint for that turn, checkpointing disabled in their shell), we show
 *    ITS reason verbatim rather than a generic "unavailable".
 */
@Composable
internal fun ChatRewindSheet(
    target: ChatViewModel.RewindTarget,
    onDismiss: () -> Unit,
    onRewindConversation: () -> Unit,
    onRewindFiles: () -> Unit,
) {
    val files = target.files
    // "+ files" is offered ONLY when the turn actually touched something. A
    // turn that just talked reports canRewind=true with an empty file list, and
    // offering a red button that warns about overwriting the server to revert
    // "+0 / -0 lines" is alarm with no content (caught on device, 2026-08-02).
    val canFiles = files?.canRewind == true && files.filesChanged.isNotEmpty()
    val filesUnchanged = files?.canRewind == true && files.filesChanged.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rewind to this message") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "\"${target.preview}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "The conversation returns to just before this message. " +
                        "Its text goes back into the composer so you can edit and resend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(12.dp))
                when {
                    target.probing -> Text(
                        "// checking what this turn changed on disk…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    canFiles -> {
                        Text(
                            "Files changed by this turn",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        // Name every file. "Restore files" without saying which
                        // ones is asking for blind trust with his working tree.
                        files.filesChanged.take(12).forEach { p ->
                            Text(
                                "• " + if (p.length > 58) "…" + p.takeLast(57) else p,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (files.filesChanged.size > 12) {
                            Text(
                                "• +${files.filesChanged.size - 12} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "+${files.insertions} / -${files.deletions} lines would be reverted. " +
                                "This overwrites the files on your server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    filesUnchanged -> Text(
                        "This turn changed no files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    else -> Text(
                        "Files: " + (files?.error ?: "nothing recorded for this turn"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onRewindConversation) { Text("Conversation") }
                if (canFiles) {
                    TextButton(onClick = onRewindFiles) {
                        Text("+ files", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}
