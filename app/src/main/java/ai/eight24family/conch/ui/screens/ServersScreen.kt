package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ai.eight24family.conch.ui.window.handCursor
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentStatus
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.ui.components.BatteryWhitelistBanner
import ai.eight24family.conch.ui.viewmodel.ServersViewModel

/**
 * **Servers** tab — a flat list of your hosts/users. Pure infrastructure
 * management: a row shows its live-connection dot + cached agent badges and is
 * tappable to open the [ServerDetailScreen] (where connect / terminal / edit /
 * add-user / delete live). No connect-on-tap, no long-press menu, no status
 * bottom-sheet — all of that moved onto the detail page so the list stays a
 * dead-simple "pick a server to manage". Rows are sorted by host so a machine's
 * users (alice@host, bob@host) sit together.
 */
@Composable
fun ServersScreen(
    onAddServer: () -> Unit,
    onOpenServer: (String) -> Unit,
    /** Tap on a search-result row → that exact chat at the matched message. */
    onOpenChatFromSearch: (sessionId: String, msgId: String, ordinal: Int, query: String, charOffset: Int) -> Unit = { _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
    vm: ServersViewModel = viewModel()
) {
    val servers by vm.servers.collectAsState()
    val connectedIds by vm.connectedServerIds.collectAsState()
    val reconnectPendingIds by vm.reconnectPendingIds.collectAsState()
    val agentStatuses by vm.agentStatuses.collectAsState()

    ai.eight24family.conch.ui.components.SearchableScaffold(
        title = {
            Text(
                "Conch ▌ servers",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = modifier,
        onPickHit = onOpenChatFromSearch,
        floatingActionButton = {
            val cyan = MaterialTheme.colorScheme.primary
            Surface(
                onClick = onAddServer,
                // Lift above the floating glass tab bar so "+ add server" isn't
                // hidden behind it.
                modifier = Modifier.padding(bottom = 72.dp),
                shape = RectangleShape,
                color = MaterialTheme.colorScheme.background,
                contentColor = cyan,
                border = BorderStroke(1.dp, cyan),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    "[ + add server ]",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    ) { padding ->
        ai.eight24family.conch.ui.window.WideContentColumn {
        if (servers.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                BatteryWhitelistBanner()
                EmptyState(modifier = Modifier.fillMaxSize().padding(24.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                // Bottom inset clears the floating glass bar overlaying this tab.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item { BatteryWhitelistBanner() }
                val sorted = servers.sortedWith(
                    compareBy({ it.host.lowercase() }, { it.port }, { it.username.lowercase() })
                )
                items(sorted, key = { it.id }) { s ->
                    ServerRow(
                        server = s,
                        connected = s.id in connectedIds,
                        reconnectPending = s.id in reconnectPendingIds,
                        statuses = agentStatuses[s.id],
                        onClick = { onOpenServer(s.id) },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    val cyan = MaterialTheme.colorScheme.primary
    val magenta = MaterialTheme.colorScheme.secondary
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val versionLabel = remember {
        runCatching {
            "v" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        }.getOrElse { "v" + ai.eight24family.conch.BuildConfig.VERSION_NAME }
    }
    Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = magenta, fontWeight = FontWeight.Bold)) { append("Conch") }
                    withStyle(SpanStyle(color = dim)) { append(" ▌ ") }
                    withStyle(SpanStyle(color = cyan)) { append(versionLabel) }
                },
                style = MaterialTheme.typography.titleLarge
            )
            Text("// no servers configured", color = dim, style = MaterialTheme.typography.bodyMedium)
            Text("//", color = dim, style = MaterialTheme.typography.bodyMedium)
            Text(
                "// add a host. drive it with claude, codex or gemini",
                color = dim,
                style = MaterialTheme.typography.bodyMedium
            )
            Text("// from your phone, over ssh.", color = dim, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) { append("❯ ") }
                    withStyle(SpanStyle(color = fg)) { append("tap [+] to begin") }
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun ServerRow(
    server: Server,
    connected: Boolean,
    /** User wants it connected but transport is down → amber dot. */
    reconnectPending: Boolean = false,
    /** Per-agent install/auth snapshot → the coloured/grey CLI badges. */
    statuses: Map<Agent, AgentStatus>? = null,
    onClick: () -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .handCursor()
            .clickable { onClick() }
            // A11Y-1: one focus stop per server + announce the connection state
            // (the ConnectionDot is purely visual, so TalkBack had no way to read
            // connected/disconnected before).
            .semantics(mergeDescendants = true) {
                stateDescription = when {
                    connected -> "connected"
                    reconnectPending -> "reconnecting"
                    else -> "disconnected"
                }
            }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("❯ ", color = cyan, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                server.name,
                color = fg,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = true)
            )
            // Agent logos — coloured for logged-in CLIs, grey for installed-only.
            AgentLogos(statuses)
            ai.eight24family.conch.ui.components.ConnectionDot(connected = connected, pending = reconnectPending)
            Text("›", color = dim, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "  ${server.username}@${server.host}:${server.port}",
            color = dim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Agent logos on a server row, just left of the connection dot. Logged-in =
 *  FULL COLOUR; installed-but-not-authed = GREYSCALE + dimmed; not-installed
 *  omitted. Same vector logos as the agent picker. */
@Composable
private fun AgentLogos(statuses: Map<Agent, AgentStatus>?) {
    val installed = Agent.entries.filter { statuses?.get(it)?.installed == true }
    if (installed.isEmpty()) return
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        installed.forEach { agent ->
            val authed = statuses?.get(agent)?.loggedIn == true
            Image(
                painter = painterResource(
                    ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent].iconRes,
                ),
                contentDescription = agent.displayName,
                colorFilter = if (authed) null else grey,
                modifier = Modifier
                    .size(10.dp)
                    .then(if (authed) Modifier else Modifier.alpha(0.55f)),
            )
        }
    }
}
