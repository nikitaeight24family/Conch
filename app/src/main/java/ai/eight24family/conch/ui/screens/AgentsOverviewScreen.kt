package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
        if (entries.isEmpty() && loadedOnce) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopStart) {
                Text(
                    "// no servers configured",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
            ) {
                items(entries, key = { it.server.id }) { entry ->
                    ServerSection(
                        entry = entry,
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

/**
 * One server: header (name / user@host / live connection dot — tap opens the
 * full-screen picker) + the shared [ServerAgentPanel] driven by a per-server
 * [AgentPickerViewModel]. The panel handles everything inline.
 */
@Composable
private fun ServerSection(
    entry: AgentsOverviewViewModel.Entry,
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
                    color = fg,
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
