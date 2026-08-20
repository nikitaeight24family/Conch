package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.data.ServerActivityLog

/**
 * Per-server activity log viewer.
 *
 * Surfaces the same `ServerActivityLog` that [OperationsScreen] tells
 * the user "exists at server long-press → Activity log". One row per
 * recorded command, newest at top, in monospace so timestamps line up.
 *
 * Persisted across restarts (see [ServerActivityLog]); cleared only by
 * tapping the trash icon — wipes the stored log (memory + disk) for THIS
 * server, leaves other servers alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerActivityLogScreen(
    serverId: String,
    serverName: String,
    onBack: () -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val err = MaterialTheme.colorScheme.error

    val entries by ServerActivityLog.observe(serverId).collectAsState()
    // Newest first — easier to follow what just happened.
    val reversed = entries.asReversed()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Activity log", style = MaterialTheme.typography.titleMedium)
                        Text(
                            serverName.ifBlank { serverId.take(12) },
                            style = MaterialTheme.typography.labelSmall,
                            color = ai.eight24family.conch.ui.theme.serverNameColor(
                                serverId = serverId,
                                serverName = serverName,
                                fallback = outline,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { ServerActivityLog.clear(serverId) }) {
                        Icon(
                            Icons.Filled.DeleteForever,
                            contentDescription = "Clear log",
                            tint = err,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (reversed.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "// no activity yet",
                    color = outline,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "// last ${reversed.size} command${if (reversed.size == 1) "" else "s"} · saved on this device",
                        color = outline,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                items(reversed, key = { it.ts.toString() + it.command.hashCode() }) { e ->
                    EntryCard(e, cyan = cyan, tertiary = tertiary, outline = outline, onSurface = onSurface, err = err)
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    e: ServerActivityLog.Entry,
    cyan: Color,
    tertiary: Color,
    outline: Color,
    onSurface: Color,
    err: Color,
) {
    val categoryColor = when (e.category) {
        "run" -> cyan
        "install" -> tertiary
        "probe" -> outline
        "file" -> cyan
        "auth" -> tertiary
        else -> outline
    }
    val rcColor = when {
        e.exitCode == 0 -> cyan
        e.exitCode < 0 -> outline
        else -> err
    }
    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Header row: timestamp · category · rc · duration
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ts,
                color = outline,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "  ${e.category}",
                color = categoryColor,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "rc=${e.exitCode}",
                color = rcColor,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            e.durationMs?.let {
                Text(
                    "  ${it} ms",
                    color = outline,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        // Command itself — mono, wrap allowed for long lines.
        Text(
            e.command,
            color = onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        // Stdout tail if any.
        if (e.stdoutTail.isNotBlank()) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = outline, fontFamily = FontFamily.Monospace)) {
                        append("↳ ")
                    }
                    withStyle(SpanStyle(color = outline, fontFamily = FontFamily.Monospace)) {
                        append(e.stdoutTail.lines().joinToString(" ").take(280))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
