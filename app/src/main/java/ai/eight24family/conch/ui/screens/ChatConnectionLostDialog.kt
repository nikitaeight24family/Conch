package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Modal shown when the live SSH transport dies mid-chat. Auto-retry is
 * driven by the caller (a separate LaunchedEffect bumps [attempt]). Back
 * dismiss = leave the chat (the only escape that doesn't depend on the
 * retry succeeding).
 */
@Composable
internal fun ConnectionLostDialog(attempt: Int, onLeave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLeave,  // hardware back leaves chat
        title = {
            Text(
                "// connection lost",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Reaching the server… ${if (attempt > 1) "(attempt $attempt)" else ""}",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "// hit Back to leave the chat.",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        // Surface visible activity instead of an empty confirmButton
        // slot — user now sees a small spinner + "Reconnecting…" so the
        // dialog doesn't read as frozen.
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "  Reconnecting…",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
