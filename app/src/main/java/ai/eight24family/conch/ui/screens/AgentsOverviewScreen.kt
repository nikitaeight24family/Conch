package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.ui.components.ConnectionDot
import ai.eight24family.conch.ui.components.SearchableScaffold
import ai.eight24family.conch.ui.viewmodel.AgentPickerViewModel
import ai.eight24family.conch.ui.viewmodel.AgentsOverviewViewModel

/**
 * **Agents** tab — every server, with its agents rendered by the SAME
 * [ServerAgentPanel] the per-server picker uses (live install/update log, login,
 * method switch, accounts, security-key touch-connect) — inline, per server, no
 * reinvented rows. Tapping a server header opens the full-screen picker (which
 * adds search + pull-to-refresh over the very same panel).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AgentsOverviewScreen(
    /** A READY agent row was tapped → open its chat. Install / update / login /
     *  method-switch are handled INLINE by the panel (no navigation). */
    onOpenChat: (serverId: String, agent: Agent) -> Unit,
    /** Tap a server header → its full-screen picker (search / pull-to-refresh). */
    onManageServer: (serverId: String) -> Unit = {},
    /** Security-key recovery actions surfaced from the inline touch dialog. */
    onOpenKeychainForDiscover: (serverId: String) -> Unit = {},
    onOpenKeychainForRegister: (serverId: String) -> Unit = {},
    onOpenChatFromSearch: (sessionId: String, msgId: String, ordinal: Int, query: String, charOffset: Int) -> Unit = { _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
    vm: AgentsOverviewViewModel = viewModel(),
) {
    val entries by vm.entries.collectAsState()
    val loadedOnce by vm.loadedOnce.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val refreshTick by vm.refreshTick.collectAsState()

    SearchableScaffold(
        title = {
            Text(
                "Conch ▌ agents",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = modifier,
        onPickHit = onOpenChatFromSearch,
    ) { padding ->
        ai.eight24family.conch.ui.window.WideContentColumn {
        // Pull-to-refresh over the WHOLE tab — every swipe re-probes all servers'
        // agent rows (each ServerSection turns [refreshTick] into a userTriggered
        // refresh on its own panel VM). The default circular indicator gives the
        // gesture visible feedback; LazyColumn provides the nested-scroll the
        // gesture hooks even when the list is short.
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refreshAll() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (entries.isEmpty() && loadedOnce) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    Text(
                        "// no servers configured",
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                ) {
                    items(entries, key = { it.server.id }) { entry ->
                        ServerSection(
                            entry = entry,
                            refreshTick = refreshTick,
                            onOpenChat = onOpenChat,
                            onManageServer = onManageServer,
                            onOpenKeychainForDiscover = onOpenKeychainForDiscover,
                            onOpenKeychainForRegister = onOpenKeychainForRegister,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    }
                }
            }
        }
        }
    }
}

/**
 * One server: header (name / user@host / live connection dot — tap opens the
 * full-screen picker) + the shared [ServerAgentPanel] driven by a per-server
 * [AgentPickerViewModel]. The panel handles everything inline.
 */
@Composable
private fun ServerSection(
    entry: AgentsOverviewViewModel.Entry,
    /** Bumped by the tab's pull-to-refresh → this panel re-probes (userTriggered).
     *  0 = initial composition; the panel VM already probes on its own init, so
     *  we skip it and only react to real swipes. */
    refreshTick: Int,
    onOpenChat: (serverId: String, agent: Agent) -> Unit,
    onManageServer: (serverId: String) -> Unit,
    onOpenKeychainForDiscover: (serverId: String) -> Unit,
    onOpenKeychainForRegister: (serverId: String) -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface
    // Per-server picker VM, keyed by serverId so it persists across the 2.5 s
    // reload + scroll. browse=true → renders from cache, never auto-connects
    // (no key prompt just to glance); tapping update/login connects on demand.
    val panelVm: AgentPickerViewModel = viewModel(
        key = entry.server.id,
        factory = AgentPickerViewModel.factory(entry.server.id, browse = true),
    )
    // Tab pull-to-refresh → re-probe THIS server's panel. Skip tick 0 (initial
    // composition — the VM already probes on init); only real swipes re-fire.
    // Gate on `connected`: a live server re-probes through the pool (no touch),
    // but firing userTriggered on a DISCONNECTED SK server would pop a FIDO
    // touch prompt — and a tab-wide pull hits EVERY panel, so ungated it would
    // cascade N simultaneous key prompts. Disconnected servers keep their cache
    // and refresh when the user actually opens/connects them.
    androidx.compose.runtime.LaunchedEffect(refreshTick) {
        // showBar=false: the tab's own pull indicator is the gesture feedback —
        // N panels each flashing their "refreshing…" bar is noise. The per-server
        // spinner next to the connection dot shows which panel is still probing.
        if (refreshTick > 0 && entry.connected) panelVm.refresh(userTriggered = true, showBar = false)
    }
    // Entering the tab (or a server coming online while it's open) SILENTLY
    // re-probes a stale panel. The panel VM outlives tab visits (keyed
    // viewModel), so without this the tab re-opened onto frozen cache: no
    // spinner, no re-check — a server logged out elsewhere kept showing "[
    // ready ]" forever. Age-gated so flipping between tabs doesn't hammer
    // the server with probes.
    androidx.compose.runtime.LaunchedEffect(entry.connected) {
        if (!entry.connected) return@LaunchedEffect
        if (panelVm.probing.value) return@LaunchedEffect  // VM init's own probe is running
        val age = System.currentTimeMillis() - (panelVm.lastCheckedAt.value ?: 0L)
        if (age > 5_000) panelVm.refresh(userTriggered = true, showBar = false)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onManageServer(entry.server.id) }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("❯ ", color = cyan, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.weight(1f, fill = true)) {
                Text(
                    entry.server.name,
                    color = ai.eight24family.conch.ui.theme.serverNameColor(
                        serverId = entry.server.id,
                        serverName = entry.server.name,
                        fallback = fg,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // user@host identity — a different SSH user = its own agents.
                Text(
                    "${entry.server.username}@${entry.server.host}:${entry.server.port}",
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Background probe in flight → a small quiet spinner NEXT TO the
            // connection dot (the user's asked-for affordance). Never a bar,
            // never blocks taps — rows stay interactive off the cached status.
            val panelProbing by panelVm.probing.collectAsState()
            if (panelProbing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = dim,
                )
            }
            // Disconnected → an explicit, immediate retry. The silent loop
            // already self-heals on its own cadence (30s, 10-min cooldown after
            // a refused auth), but a human must never have to wait it out: the
            // tap clears the cooldown and connects NOW.
            if (!entry.connected) {
                val connectLog by panelVm.connectLog.collectAsState()
                // The step trace sits LEFT of the button so a tap visibly does
                // something — "Connecting… → Authenticating… → Connected ✓" or a
                // concrete failure reason — instead of the old silent no-op.
                connectLog?.let {
                    Text(
                        it,
                        color = if (it.startsWith("Failed")) MaterialTheme.colorScheme.error else dim,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
                    )
                }
                Text(
                    "[ retry ]",
                    color = cyan,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clickable { panelVm.retryConnectNow() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            ConnectionDot(connected = entry.connected)
        }
        ServerAgentPanel(
            vm = panelVm,
            serverId = entry.server.id,
            browse = true,
            onPickAgent = { agent -> onOpenChat(entry.server.id, agent) },
            onOpenKeychainForDiscover = onOpenKeychainForDiscover,
            onOpenKeychainForRegister = onOpenKeychainForRegister,
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, bottom = 6.dp),
        )
    }
}
