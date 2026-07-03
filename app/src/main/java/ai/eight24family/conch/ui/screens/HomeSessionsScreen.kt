package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.ui.components.PhoneBridgeGlyph
import ai.eight24family.conch.ui.components.SearchableScaffold
import ai.eight24family.conch.ui.window.handCursor
import ai.eight24family.conch.ui.viewmodel.HomeSessionRow
import ai.eight24family.conch.ui.viewmodel.HomeSessionsViewModel
import kotlinx.coroutines.launch

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
    onNewChat: (serverId: String, agent: Agent) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    vm: HomeSessionsViewModel = viewModel(),
) {
    val rows by vm.rows.collectAsState()
    val servers by vm.servers.collectAsState()
    val loadedOnce by vm.loadedOnce.collectAsState()
    val conn by vm.connectivity.collectAsState()
    val usableByServer by vm.usableByServer.collectAsState()
    val filterName by vm.agentFilter.collectAsState()

    // Agents you can actually open a chat with = installed AND logged-in on at
    // least one server (a new session needs both). Enum order (Claude, Codex,
    // Gemini). This — NOT "agents that have sessions" — drives the chip bar: an
    // uninstalled / logged-out agent gets no chip, and a lone usable agent hides
    // the bar entirely. Existing cached sessions must NEVER be hidden because
    // the live login probe didn't mark an agent usable. A stale/flaky
    // codex/gemini auth check made ALL their sessions vanish — and with only
    // Claude "usable" the chip bar disappeared too (size<2), leaving NO way to
    // reveal them. So an agent that HAS cached sessions also counts toward the
    // bar/filter. Starting a NEW chat still needs a real login — the
    // newChatTargets/Pairs below stay strictly probe-gated.
    val agentsWithSessions = remember(rows) { rows.mapTo(HashSet()) { it.session.agent } }
    val usableAgents = remember(usableByServer, agentsWithSessions) {
        Agent.entries.filter { a -> usableByServer.values.any { a in it } || a in agentsWithSessions }
    }
    val barShown = usableAgents.size >= 2
    // Effective filter: a lone usable agent is forced (no bar); with ≥2 the
    // persisted pick applies (validated against the usable set; unknown → All);
    // before the status probe lands (empty) → All so nothing is hidden.
    val selectedAgent: Agent? = when {
        usableAgents.size == 1 -> usableAgents[0]
        barShown -> filterName?.let { n -> usableAgents.firstOrNull { it.name == n } }
        else -> null
    }
    val visibleRows = remember(rows, selectedAgent) {
        if (selectedAgent == null) rows else rows.filter { it.session.agent == selectedAgent }
    }
    // Servers where the focused agent is usable — the "new session" targets.
    // One → open directly; many → pick from a dropdown.
    val newChatTargets = remember(servers, usableByServer, selectedAgent) {
        val a = selectedAgent
        if (a == null) emptyList()
        else servers.filter { usableByServer[it.id]?.contains(a) == true }
    }
    // New-session targets as (server, agent) pairs. With a focused agent → that
    // agent's servers. Under "All" (no focus) the FAB STILL starts a chat: offer
    // every usable (server, agent) pair so a new chat is reachable from All too.
    val newChatPairs =
        remember(servers, usableByServer, selectedAgent, newChatTargets) {
            val a = selectedAgent
            if (a != null) newChatTargets.map { it to a }
            else servers.flatMap { s ->
                Agent.entries.filter { usableByServer[s.id]?.contains(it) == true }.map { s to it }
            }
        }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Telegram-style: when a new/bumped session becomes #1, keep it in view IF
    // the user is at/near the top — otherwise a freshly-created session lands
    // just above the fold and they'd have to scroll up to find it (the bug).
    // Deep readers (scrolled down) are never yanked. firstVisibleItemIndex <= 1
    // because a single prepend shifts the parked-at-top user from 0 to 1.
    val topKey = visibleRows.firstOrNull()?.let { it.serverId + "/" + it.session.agent.name + "/" + it.session.id }
    LaunchedEffect(topKey) {
        if (topKey != null && listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }
    // "Back to top" appears only once you're past the 5th session (deep enough
    // that scrolling back is a chore), hidden before that.
    val showToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 5 } }
    // Server-picker dropdown for "new session" when >1 server qualifies.
    var serverMenuOpen by remember { mutableStateOf(false) }
    // Bumped on every filter-chip tap. The filter switch is synchronous (in-memory),
    // so when this LaunchedEffect fires the new filter's rows are already laid out —
    // scrollToItem(0) then lands at the ABSOLUTE top, overriding LazyColumn's
    // key-based position preservation that otherwise kept the previous filter's
    // offset.
    var scrollTopTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(scrollTopTrigger) {
        if (scrollTopTrigger > 0) listState.scrollToItem(0)
    }

    SearchableScaffold(
        title = {
            Column {
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
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    rows.isNotEmpty() -> {
                        Column(Modifier.fillMaxSize()) {
                            // Filter chips — only when ≥2 agents are usable.
                            if (barShown) {
                                AgentFilterChips(
                                    agents = usableAgents,
                                    counts = rows.groupingBy { it.session.agent }.eachCount(),
                                    total = rows.size,
                                    selected = selectedAgent,
                                    onSelect = {
                                        vm.setAgentFilter(it?.name)
                                        // Bump the trigger → a LaunchedEffect scrolls
                                        // the list to the very top AFTER the new
                                        // filter's content is laid out (see below).
                                        scrollTopTrigger++
                                    },
                                )
                            }
                            if (visibleRows.isEmpty()) {
                                // A usable agent with no sessions yet — invite one.
                                Text(
                                    "// no ${selectedAgent?.displayName ?: ""} sessions yet — tap ＋ to start one",
                                    color = MaterialTheme.colorScheme.outline,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(20.dp),
                                )
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    // Bottom inset so the last session scrolls clear
                                    // of the floating glass bar (it overlays the list).
                                    contentPadding = PaddingValues(top = 2.dp, bottom = 96.dp),
                                ) {
                                    items(visibleRows, key = { it.serverId + "/" + it.session.agent.name + "/" + it.session.id }) { row ->
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
                        }
                    }
                    // Loaded, but nothing to show → an honest empty state.
                    loadedOnce -> EmptyHome(
                        hasServers = servers.isNotEmpty(),
                        onAddServer = onAddServer,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Not loaded yet → keep blank (cache read is near-instant).
                    else -> Box(Modifier.fillMaxSize())
                }

                // ── Floating actions, stacked bottom-end above the nav bar ──
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 104.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (showToTop) {
                        SmallFloatingActionButton(
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Back to top")
                        }
                    }
                    // New session — shown whenever a chat CAN be started (a focused
                    // agent's server, OR — under "All" — any usable server×agent
                    // pair). One target → open directly; many → pick from a menu.
                    if (newChatPairs.isNotEmpty()) {
                        val primary = MaterialTheme.colorScheme.primary
                        // Menu labels: many agents in play (the "All" case) → lead
                        // with the agent; one agent, many servers → show the server.
                        val multiAgent = newChatPairs.distinctBy { it.second }.size > 1
                        val multiServer = newChatPairs.distinctBy { it.first.id }.size > 1
                        Box {
                            // Compose-new-chat button: a translucent cyber disc with a
                            // chat-bubble-＋ glyph — reads as "new conversation", not
                            // "attach a file" (a bare ＋ looked like add-photo). ~15%
                            // smaller than the stock 56dp FAB + semi-transparent.
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(primary.copy(alpha = 0.16f), CircleShape)
                                    .border(1.5.dp, primary.copy(alpha = 0.8f), CircleShape)
                                    .clip(CircleShape)
                                    .handCursor()
                                    .clickable {
                                        if (newChatPairs.size == 1) {
                                            onNewChat(newChatPairs[0].first.id, newChatPairs[0].second)
                                        } else {
                                            serverMenuOpen = true
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.AddComment,
                                    contentDescription = "New session",
                                    tint = primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            // >1 target → pick which (server and/or agent).
                            DropdownMenu(expanded = serverMenuOpen, onDismissRequest = { serverMenuOpen = false }) {
                                for ((s, a) in newChatPairs) {
                                    val label = when {
                                        multiAgent && multiServer -> "${a.displayName} · ${s.name}"
                                        multiAgent -> a.displayName
                                        else -> "${s.username}@${s.name}"
                                    }
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            serverMenuOpen = false
                                            onNewChat(s.id, a)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Horizontally-scrollable agent filter: "All" + one chip per USABLE agent
 *  (installed + logged-in). Lets the user surface Codex/Gemini history that the
 *  recency sort otherwise buries under a burst of recent Claude activity, and
 *  keeps the tapped chip in view (LazyRow auto-scrolls to the selection). */
@Composable
private fun AgentFilterChips(
    agents: List<Agent>,
    counts: Map<Agent, Int>,
    total: Int,
    selected: Agent?,
    onSelect: (Agent?) -> Unit,
) {
    val state = rememberLazyListState()
    // Bring the active chip on-screen — tapping "Codex" when it sits off the
    // right edge now scrolls it into view. Index 0 = "All", then one per
    // agent.
    val selIndex = if (selected == null) 0 else agents.indexOf(selected) + 1
    LaunchedEffect(selIndex, agents.size) {
        if (selIndex in 0..agents.size) state.animateScrollToItem(selIndex)
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            AgentChip("All", total, null, selected == null) { onSelect(null) }
        }
        items(agents) { a ->
            AgentChip(a.displayName, counts[a] ?: 0, AgentSpecRegistry[a].iconRes, selected == a) { onSelect(a) }
        }
    }
}

@Composable
private fun AgentChip(
    label: String,
    count: Int,
    iconRes: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val bg = if (selected) primary.copy(alpha = 0.18f) else Color.Transparent
    val borderC = if (selected) primary else dim.copy(alpha = 0.5f)
    val fg = if (selected) primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .border(1.dp, borderC, RoundedCornerShape(50))
            .handCursor()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            count.toString(),
            color = if (selected) primary else dim,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
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
    // Unsent draft — shown inline as "Draft: …" IN PLACE of the preview (user
    // wanted the text itself, not a pencil that just hints "editable").
    val draft = row.draftText?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.ifBlank { null }
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
                // Phone wired to this session via conch-bridge — tri-state glyph
                // (colored live / dim offline / absent never), shared with the
                // per-server list and the chat title.
                PhoneBridgeGlyph(
                    row.phoneGlyph,
                    modifier = Modifier.padding(end = 6.dp),
                    size = 13.dp,
                )
                Text(
                    formatWhen(row.lastActiveMs),
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // Subtitle: an unsent DRAFT wins (accent "Draft: …"), else the dim
            // messenger-style last-message preview (all agents).
            if (draft != null) {
                Text(
                    "Draft: $draft",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Right-aligned: the left edge is for agent replies; the user's
                    // own (unsent) text reads as "mine" on the right (chat metaphor).
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                )
            } else if (lastMessage != null) {
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
