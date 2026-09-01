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
    /** The phone itself — opened from its own row in the list, never added. */
    onAddLinux: () -> Unit = {},
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
            // ⚠ THE `[ + linux ]` BUTTON IS GONE, AND ITS JOB MOVED UP INTO THE
            // LIST. A button that adds a thing there can only ever be one of is a
            // door to a room you are already standing in: the phone is not
            // something you acquire, it is the machine in your hand, so it is
            // always ON the list — as [LocalMachineRow], first row, saying what
            // it actually is right now. Installing is what tapping that row leads
            // to, once, and after that the row is a machine like the others.
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 72.dp),
            ) {
            Surface(
                onClick = onAddServer,
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
        }
    ) { padding ->
        ai.eight24family.conch.ui.window.WideContentColumn {
        // ⛔ ONE LIST, ALWAYS — the empty case is an ITEM IN IT, not a different
        // screen. The old shape swapped the whole body out when `servers` was
        // empty, which would have hidden the local machine from precisely the
        // person this feature exists for: someone with no server and no computer,
        // whose list is empty on purpose and who nonetheless owns a machine
        // (owner, 2026-08-30). The one row he has must be there on first launch.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // Bottom inset clears the floating glass bar overlaying this tab.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item { BatteryWhitelistBanner() }
            // ⛔ ONLY UNTIL IT IS A MACHINE. Once the environment is installed it
            // has an ordinary row in the list below, and drawing this one too
            // would put the same phone on the list twice — one of them behaving
            // like a server and one not, which is the difference that must not
            // exist (owner, 2026-08-31).
            if (servers.none { it.id == ai.eight24family.conch.linux.LinuxSsh.SERVER_ID }) {
                item { LocalMachineRow(onClick = onAddLinux) }
            }
            if (servers.isEmpty()) {
                item { EmptyState(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp)) }
            } else {
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

/**
 * **This phone, as one of the machines.**
 *
 * It carries the same three parts as a [ServerRow] — name, subtitle, live dot —
 * because it is the same kind of thing and lying about that would be the only
 * reason to draw it differently. What the subtitle says is the one place the
 * three states must not be blurred:
 *
 * | state | subtitle | dot |
 * |---|---|---|
 * | installed | what the distribution calls itself, and its size | ● live |
 * | not installed | an offer to set it up | ○ |
 * | shell out of reach | says so, and does NOT offer to install | ○ |
 *
 * The last row is why [LinuxEnv.Presence] exists. An install that is merely
 * unreachable is still an install; inviting the owner to make a second one on
 * top of it is how you lose the first.
 */
@Composable
private fun LocalMachineRow(onClick: () -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface

    val snap by ai.eight24family.conch.linux.LinuxEnv.snapshot.collectAsState()
    // Re-asked every time the tab is entered, because arming the bridge or
    // installing from the Linux page both happen elsewhere. The snapshot means
    // the row paints the previous answer while this runs, so nothing flickers.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        ai.eight24family.conch.linux.LinuxEnv.refresh()
    }

    val installed = snap.presence == ai.eight24family.conch.linux.LinuxEnv.Presence.INSTALLED
    val subtitle = ai.eight24family.conch.linux.LinuxEnv.subtitle(snap)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .handCursor()
            .clickable { onClick() }
            .semantics(mergeDescendants = true) {
                stateDescription = if (installed) "installed" else "not installed"
            }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("❯ ", color = cyan, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                "this device",
                color = fg,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = true)
            )
            ai.eight24family.conch.ui.components.ConnectionDot(connected = installed)
            Text("›", color = dim, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "  $subtitle",
            color = dim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
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
                color = ai.eight24family.conch.ui.theme.serverNameColor(
                    serverId = server.id, serverName = server.name, fallback = fg,
                ),
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

/**
 * Agent logos on a server row, just left of the connection dot — one per agent
 * that is READY on that server: installed AND signed in, the same condition the
 * picker prints `[ ready ]` for.
 *
 * It used to show every INSTALLED agent and grey out the ones without an
 * account, which put a row of dim marks on a server where nothing could actually
 * be opened. At ten agents that reads as clutter rather than information. A logo
 * here now means one thing: tap this server and that agent will work.
 *
 * All ten are eligible — the list walks [Agent.entries], so a newly added CLI
 * appears here the moment it is set up, with no edit to this screen.
 */
@Composable
private fun AgentLogos(statuses: Map<Agent, AgentStatus>?) {
    val ready = Agent.entries.filter {
        val s = statuses?.get(it)
        s?.installed == true && s.loggedIn
    }
    if (ready.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        ready.forEach { agent ->
            Image(
                painter = painterResource(
                    ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent].iconRes,
                ),
                contentDescription = agent.displayName,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}
