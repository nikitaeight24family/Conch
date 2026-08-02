package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.RemoteSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SessionsDrawer(
    agentName: String,
    sessions: List<RemoteSession>,
    currentResumeId: String?,
    refreshing: Boolean,
    onSelect: (RemoteSession) -> Unit,
    onNew: () -> Unit,
    onRefresh: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MMM d HH:mm", Locale.getDefault()) }
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerShape = RectangleShape,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "═══ sessions",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        agentName.lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh sessions",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            TextButton(
                onClick = onNew,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.primary, RectangleShape),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    " new session",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (refreshing && sessions.isEmpty()) {
                LinearProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (sessions.isEmpty()) {
                Text(
                    "no sessions yet — start one\nand it'll show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        SessionRow(
                            session = s,
                            selected = s.id == currentResumeId,
                            fmt = fmt,
                            onSelect = { onSelect(s) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: RemoteSession,
    selected: Boolean,
    fmt: SimpleDateFormat,
    onSelect: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .drawBehind {
                if (selected) {
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, 0f),
                        size = Size(2.dp.toPx(), size.height),
                    )
                }
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            session.preview.ifBlank { session.id.take(8) },
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (session.lastActiveAt > 0)
                fmt.format(Date(session.lastActiveAt * 1000))
            else session.id.take(8),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
