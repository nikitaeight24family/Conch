package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.ui.components.SearchableScaffold
import ai.eight24family.conch.ui.window.handCursor
import ai.eight24family.conch.ui.viewmodel.HomeSessionRow
import ai.eight24family.conch.ui.viewmodel.HomeSessionsViewModel

/**
 * Unified **Sessions** home — every cached session across all servers ×
 * agents, newest first, like a messenger's chat list. The app's start
 * destination. Tap a row → resume that chat. Global search across all indexed
 * sessions is built into the top bar (SearchableScaffold).
 */
@Composable
fun HomeSessionsScreen(
    onOpenChat: (serverId: String, agent: Agent, resumeId: String, path: String, model: String?, reasoning: String?) -> Unit,
    onOpenChatFromSearch: (sessionId: String, msgId: String, ordinal: Int, query: String, charOffset: Int) -> Unit = { _, _, _, _, _ -> },
    onAddServer: () -> Unit = {},
    modifier: Modifier = Modifier,
    vm: HomeSessionsViewModel = viewModel(),
) {
    val rows by vm.rows.collectAsState()
    val servers by vm.servers.collectAsState()
    val loadedOnce by vm.loadedOnce.collectAsState()
    val conn by vm.connectivity.collectAsState()

    val listState = rememberLazyListState()
    // Telegram-style: when a new/bumped session becomes #1, keep it in view IF
    // the user is at/near the top — otherwise a freshly-created session lands
    // just above the fold and they'd have to scroll up to find it (the bug).
    // Deep readers (scrolled down) are never yanked. firstVisibleItemIndex <= 1
    // because a single prepend shifts the parked-at-top user from 0 to 1.
    val topKey = rows.firstOrNull()?.let { it.serverId + "/" + it.session.agent.name + "/" + it.session.id }
    LaunchedEffect(topKey) {
        if (topKey != null && listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }

    SearchableScaffold(
        title = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "Conch ▌ sessions",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Live auto-connect progress, visible from launch on the screen
                // the user lands on — so connecting is obviously the app's own
                // doing, not something that starts when they open the Servers tab.
                if (conn.connecting > 0) {
                    Text(
                        "◌ connecting to ${conn.connecting} server${if (conn.connecting == 1) "" else "s"}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        modifier = modifier,
        onPickHit = onOpenChatFromSearch,
    ) { padding ->
        ai.eight24family.conch.ui.window.WideContentColumn {
        when {
            rows.isNotEmpty() -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    // Bottom inset so the last session scrolls clear of the
                    // floating glass bar (it overlays the list, not docks).
                    contentPadding = PaddingValues(top = 2.dp, bottom = 96.dp),
                ) {
                    items(rows, key = { it.serverId + "/" + it.session.agent.name + "/" + it.session.id }) { row ->
                        SwipeToRevealDelete(onDelete = { vm.deleteSession(row) }) {
                            SessionListItem(row = row, onClick = {
                                onOpenChat(
                                    row.serverId,
                                    row.session.agent,
                                    row.session.id,
                                    row.session.path,
                                    row.session.model,
                                    row.session.reasoning,
                                )
                            })
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    }
                }
            }
            // Loaded, but nothing to show → an honest empty state.
            loadedOnce -> EmptyHome(
                hasServers = servers.isNotEmpty(),
                onAddServer = onAddServer,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            // Not loaded yet → keep blank (cache read is near-instant).
            else -> Box(Modifier.fillMaxSize().padding(padding))
        }
        }
    }
}

@Composable
private fun SessionListItem(row: HomeSessionRow, onClick: () -> Unit) {
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface
    val message = row.session.preview.replace('\n', ' ').replace('\r', ' ').trim()
    val title = row.session.title?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.ifBlank { null }
    // Session NAME = the CLI's own title (Claude ai-title) when present, else the
    // first-message preview. Rendered as the row's ACCENT header.
    val name = (title ?: message).ifBlank { "session ${row.session.id.take(8)}" }
    // Last chat message — the dim messenger-style preview under the name, for
    // ALL agents (user picked: codex/all rows show the chat's last message
    // below). Hidden when it equals the name (e.g. a one-message session where
    // last == first == name), so it's never a pointless duplicate.
    val lastMessage = row.lastMessage?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
        ?.takeIf { it.isNotBlank() && it != name }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque bg so the swipe-revealed red delete doesn't bleed through.
            .background(MaterialTheme.colorScheme.background)
            .handCursor()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(AgentSpecRegistry[row.session.agent].iconRes),
            contentDescription = row.session.agent.displayName,
            colorFilter = null,
            modifier = Modifier.size(30.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            // Server — small, grey, insignificant, ABOVE the name. Breadcrumb
            // over the accent.
            Text(
                "${row.username}@${row.serverName}",
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Session name (Claude title ?: first message) — the accent.
                    name,
                    color = fg,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true),
                )
                // Agent busy in this session right now → spinner. Multiple
                // sessions can spin at once (agents run in parallel).
                if (row.working) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 6.dp).size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // "N new" badge: messages the agent produced in this session
                // while the user was elsewhere (SessionSeenTracker delta).
                if (row.unread > 0) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (row.unread > 99) "99+" else row.unread.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // Phone wired to this session via conch-bridge — small glyph.
                if (row.phoneConnected) {
                    Icon(
                        Icons.Filled.PhoneAndroid,
                        contentDescription = "phone connected to this session",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp).size(13.dp),
                    )
                }
                Text(
                    formatWhen(row.lastActiveMs),
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // Last chat message — dim messenger-style preview (all agents). Null
            // when it would just duplicate the name (1-message session).
            if (lastMessage != null) {
                Text(
                    lastMessage,
                    color = dim,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyHome(hasServers: Boolean, onAddServer: () -> Unit, modifier: Modifier) {
    val dim = MaterialTheme.colorScheme.outline
    val cyan = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onSurface
    Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hasServers) {
                Text("// no sessions yet", color = dim, style = MaterialTheme.typography.bodyMedium)
                Text("//", color = dim, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "// open an agent on a server and start a chat —",
                    color = dim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "// it'll show up here.",
                    color = dim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("// no servers configured", color = dim, style = MaterialTheme.typography.bodyMedium)
                Text("//", color = dim, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "// add a host, then drive it with claude, codex or gemini.",
                    color = dim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "❯ add a server",
                    color = cyan,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable { onAddServer() },
                )
            }
        }
    }
}

private fun formatWhen(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    // epochMillis comes straight from SessionActivityStore (millis end-to-end) —
    // no ×1000 dance, no seconds/millis mixups. Today → time only; yesterday →
    // "yesterday HH:mm"; this year → "d MMM"; older → "d MMM yyyy". No weekday
    // abbreviations.
    val ms = epochMillis
    val now = System.currentTimeMillis()
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val today0 = cal.timeInMillis
    val day = 24L * 60 * 60 * 1000
    val loc = java.util.Locale.getDefault()
    val nowYear = cal.get(java.util.Calendar.YEAR)
    val msYear = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
    val text = when {
        ms >= today0 -> java.text.SimpleDateFormat("HH:mm", loc).format(java.util.Date(ms))
        ms >= today0 - day ->
            "yesterday " + java.text.SimpleDateFormat("HH:mm", loc).format(java.util.Date(ms))
        msYear == nowYear -> java.text.SimpleDateFormat("d MMM", loc).format(java.util.Date(ms))
        else -> java.text.SimpleDateFormat("d MMM yyyy", loc).format(java.util.Date(ms))
    }
    return text
}
