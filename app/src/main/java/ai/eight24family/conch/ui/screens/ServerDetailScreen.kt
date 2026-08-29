package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ai.eight24family.conch.agent.ServerStats
import ai.eight24family.conch.ui.components.ConnectionDot
import ai.eight24family.conch.ui.viewmodel.ServerDetailViewModel
import ai.eight24family.conch.ui.window.handCursor

/**
 * **Server detail** — the single management page for ONE server entry. The
 * Servers tab is pure infrastructure management now: tap a row → here. No
 * auto-connect, no FIDO touch until the user explicitly hits Connect /
 * Terminal. Replaces the old long-press dropdown menu AND the "Server status"
 * bottom sheet (its live host stats fold in under `// system`).
 *
 * Sections: header (address + Connect/Disconnect) · `// tools` (Terminal,
 * Activity log) · `// system` (auth/agent/fingerprint + live load) · `//
 * manage` (Edit, Add another user on this host, Delete).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    onBack: () -> Unit,
    onOpenTerminal: (serverId: String, serverName: String) -> Unit,
    onOpenActivityLog: (serverId: String, serverName: String) -> Unit,
    onEditServer: (serverId: String) -> Unit,
    onAddUserHere: (host: String, port: Int) -> Unit,
    vm: ServerDetailViewModel = viewModel(),
) {
    val server by vm.server.collectAsState()
    val connected by vm.connected.collectAsState()
    val reconnectPending by vm.reconnectPending.collectAsState()
    val connecting by vm.connecting.collectAsState()
    val stats by vm.stats.collectAsState()
    val probing by vm.probing.collectAsState()
    val skTouchReq by vm.skTouchRequest.collectAsState()
    val connectError by vm.connectError.collectAsState()
    val seamlessEnabled by vm.seamlessEnabled.collectAsState()
    val seamlessDays by vm.seamlessDays.collectAsState()
    val deviceKey by vm.deviceKey.collectAsState()
    val isSkServer by vm.isSkServer.collectAsState()
    val installedVersion by vm.installedVersion.collectAsState()
    val bridgeChecked by vm.bridgeChecked.collectAsState()
    val bridgeBusy by vm.bridgeBusy.collectAsState()
    val bridgeLog by vm.bridgeLog.collectAsState()

    val cyan = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val err = MaterialTheme.colorScheme.error

    var confirmDelete by remember { mutableStateOf(false) }
    var confirmForgetHostKey by remember { mutableStateOf(false) }
    var seamlessHelpOpen by remember { mutableStateOf(false) }
    // 1-Hz ticker for the device-key expiry countdown (only runs while seamless
    // is on, i.e. while the key row is visible).
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(seamlessEnabled) {
        while (seamlessEnabled) { now = System.currentTimeMillis(); delay(1000) }
    }
    // Refresh the device-key card whenever the section becomes visible.
    LaunchedEffect(isSkServer, seamlessEnabled) { if (isSkServer) vm.refreshDeviceKey() }
    // Probe the bridge state once the server is connected (the only time we can).
    LaunchedEffect(connected) { if (connected) vm.checkBridge() }
    // Set when the user taps Terminal while offline — we connect first, then
    // route into the shell once the transport is up (collected below).
    var pendingTerminal by remember { mutableStateOf(false) }

    val s = server

    LaunchedEffect(Unit) {
        vm.connectedTo.collect {
            if (pendingTerminal) {
                pendingTerminal = false
                onOpenTerminal(it, server?.name.orEmpty())
            }
        }
    }

    // SK touch dialog over the page — only ever shown after an explicit
    // Connect / Terminal tap on an SK server.
    skTouchReq?.let { req ->
        SkInlineTouchDialog(
            transport = req.transport,
            credentialIdBase64 = req.credentialIdBase64,
            application = req.application,
            onUsbSigner = { signer -> vm.runConnectWithSigner(signer) },
            onNfcSigner = { signer -> vm.runConnectWithSigner(signer) },
            onCancel = { pendingTerminal = false; vm.cancelConnect() },
            retry = req.retry,
        )
    }
    if (connectError != null) {
        AlertDialog(
            onDismissRequest = { vm.consumeConnectError() },
            title = { Text("Connect failed") },
            text = { Text(connectError!!) },
            confirmButton = { TextButton(onClick = { vm.consumeConnectError() }) { Text("OK") } },
        )
    }
    if (confirmDelete && s != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                // Only the name inside the quotes takes the server's accent.
                val accent = ai.eight24family.conch.ui.theme.serverNameColor(
                    serverId = s.id,
                    serverName = s.name,
                    fallback = androidx.compose.material3.LocalContentColor.current,
                )
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("Delete \"")
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(color = accent),
                        ) { append(s.name) }
                        append("\"?")
                    },
                )
            },
            text = { Text("Removes the server, its stored credentials, and all chat sessions tied to it. The remote machine isn't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete()
                    onBack()
                }) { Text("Delete", color = err) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmForgetHostKey && s != null) {
        AlertDialog(
            onDismissRequest = { confirmForgetHostKey = false },
            title = { Text("Forget this host key?") },
            // Says what it costs, not what it is. Forgetting is correct after
            // YOU rebuilt the machine; it is exactly what an attacker in the
            // middle needs you to do. Only the user knows which happened.
            text = {
                Text(
                    "The next connection to ${s.host} will trust whatever key it's offered and pin " +
                        "that instead. Do this if you rebuilt, moved or reinstalled the server. If the " +
                        "key changed on its own, forgetting it hides a machine-in-the-middle."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForgetHostKey = false
                    vm.forgetHostKey()
                }) { Text("Forget", color = err) }
            },
            dismissButton = { TextButton(onClick = { confirmForgetHostKey = false }) { Text("Cancel") } },
        )
    }
    if (seamlessHelpOpen) {
        SeamlessReconnectHelpDialog(onDismiss = { seamlessHelpOpen = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Text(
                        s?.name ?: "server",
                        style = MaterialTheme.typography.titleLarge,
                        color = ai.eight24family.conch.ui.theme.serverNameColor(
                            serverId = s?.id,
                            serverName = s?.name,
                            fallback = cyan,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(16.dp),
                            strokeWidth = 1.5.dp,
                            color = cyan,
                        )
                    } else {
                        Box(Modifier.padding(end = 16.dp)) {
                            ConnectionDot(connected = connected, pending = reconnectPending)
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ── Header: address + Connect/Disconnect ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${s.username}@${s.host}:${s.port}",
                    color = dim,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true),
                )
                Spacer(Modifier.width(10.dp))
                val connectLabel = when {
                    connecting -> "connecting…"
                    connected -> "disconnect"
                    reconnectPending -> "reconnect"
                    else -> "connect"
                }
                val btnColor = if (connected) err else cyan
                OutlinedButton(
                    onClick = {
                        if (connected) vm.disconnect() else if (!connecting) vm.connect()
                    },
                    enabled = !connecting,
                    shape = RectangleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, btnColor),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = btnColor),
                ) {
                    if (connected) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(connectLabel, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            // ── // tools ──
            SectionLabel("// tools")
            ActionRow(
                icon = Icons.Filled.Terminal,
                title = "Terminal",
                subtitle = "break-glass shell",
                onClick = {
                    if (connected) onOpenTerminal(s.id, s.name)
                    else { pendingTerminal = true; vm.connect() }
                },
            )
            ActionRow(
                icon = Icons.Filled.Info,
                title = "Activity log",
                subtitle = "recent operations on this server",
                onClick = { onOpenActivityLog(s.id, s.name) },
            )

            SectionDivider()

            // ── // appearance ──
            SectionLabel("// appearance")
            ServerColorRow(
                server = s,
                onPick = { vm.setColorHex(it) },
                onRandomize = { vm.setColorHex(null) },
            )

            SectionDivider()

            // ── // system (identity + live load; folds in the old status sheet) ──
            SectionLabel("// system")
            InfoRow("auth", s.authMethod.name.lowercase())
            // The pin, and the only way out of it. A host-key mismatch is a hard
            // refusal (the pool won't connect, the ladder won't retry), so the
            // screen that SHOWS the fingerprint has to be the screen that can
            // drop it — otherwise a server the user legitimately rebuilt is
            // unreachable from inside the app forever.
            s.knownHostKey?.takeIf { it.isNotBlank() }?.let { fp ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f, fill = true)) { InfoRow("fingerprint", fp.take(48)) }
                    TextButton(onClick = { confirmForgetHostKey = true }) {
                        Text(
                            "forget",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            HostStats(stats = stats, connected = connected, probing = probing)

            // ── // seamless reconnect (per-server: toggle + lifetime + device
            //    key + remove). Only for FIDO/security-key servers — the
            //    "leave your key at home" workflow. Moved here from app Settings:
            //    it's a property of the SERVER, not the app. ──
            if (isSkServer) {
                SectionDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "// seamless reconnect",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = true),
                    )
                    HelpBadge(onClick = { seamlessHelpOpen = true })
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = seamlessEnabled, onCheckedChange = { vm.setSeamless(it) })
                }
                if (seamlessEnabled) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Device key lifetime",
                        color = dim,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.size(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 3, 7).forEach { d ->
                            FilterChip(
                                selected = seamlessDays == d,
                                onClick = { vm.setSeamlessDays(d) },
                                label = { Text(if (d == 1) "1 day" else "$d days") },
                            )
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    val key = deviceKey
                    if (key != null) {
                        DeviceKeyRow(entry = key, now = now, onRemove = { vm.removeDeviceKey() })
                    } else {
                        InfoRow("device key", "not created yet — connect & tap once")
                    }
                }
            }

            // ── // phone bridge (one contextual button + version, log in the
            //    same gray line) ──
            SectionDivider()
            SectionLabel("// phone bridge")
            val avail = vm.bridgeAvailableVersion
            // The gray line doubles as the install/remove LOG once an op ran;
            // otherwise it's the version-aware status.
            val grayLine = bridgeLog ?: when {
                !connected -> "connect to this server to manage the bridge"
                bridgeBusy -> "working…"
                !bridgeChecked -> "checking…"
                installedVersion == null -> "not installed · latest v$avail"
                installedVersion == avail -> "installed · v$installedVersion · pair this phone in Settings → Phone bridge to use it"
                else -> "v$installedVersion → v$avail · update available"
            }
            Text(
                grayLine,
                color = dim,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            val btnLabel = when {
                installedVersion == null -> "Install"
                installedVersion != avail -> "Update"
                else -> "Remove"
            }
            OutlinedButton(
                onClick = {
                    vm.clearBridgeLog()
                    if (btnLabel == "Remove") vm.removeBridge() else vm.installBridge()
                },
                enabled = connected && !bridgeBusy && bridgeChecked,
                colors = if (btnLabel == "Remove")
                    ButtonDefaults.outlinedButtonColors(contentColor = err)
                else ButtonDefaults.outlinedButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(btnLabel) }

            SectionDivider()

            // ── // manage ──
            SectionLabel("// manage")
            ActionRow(
                icon = Icons.Filled.Edit,
                title = "Edit connection",
                subtitle = "host, port, user, key, default agent",
                onClick = { onEditServer(s.id) },
            )
            ActionRow(
                icon = Icons.Filled.PersonAdd,
                title = "Add another user on this host",
                subtitle = "a different SSH user = its own agents & sessions",
                onClick = { onAddUserHere(s.host, s.port) },
            )
            ActionRow(
                icon = Icons.Filled.Delete,
                title = "Delete server",
                subtitle = null,
                tint = err,
                onClick = { confirmDelete = true },
            )
            Spacer(Modifier.size(28.dp))
        }
    }
}

@Composable
private fun HostStats(stats: ServerStats?, connected: Boolean, probing: Boolean) {
    if (stats == null) {
        InfoRow(
            "status",
            if (!connected) "offline · tap connect to probe"
            else if (probing) "probing…" else "no data yet",
        )
        return
    }
    stats.hostname?.let { InfoRow("hostname", it) }
    stats.osPretty?.let { InfoRow("os", it) }
    run {
        val kernelArch = listOfNotNull(stats.kernel, stats.arch).joinToString(" · ")
        if (kernelArch.isNotBlank()) InfoRow("kernel", kernelArch)
    }
    stats.uptime?.let { InfoRow("uptime", it) }
    InfoRow(
        "cpu",
        listOfNotNull(
            stats.cpuPercent?.let { "$it%" },
            stats.cpuCount?.let { "$it cpu${if (it == 1) "" else "s"}" },
            stats.cpuTempC?.let { "%.0f°C".format(it) },
        ).joinToString(" · ").ifBlank { "—" },
    )
    InfoRow(
        "memory",
        if (stats.memUsedMb != null && stats.memTotalMb != null) {
            "${stats.memUsedMb} / ${stats.memTotalMb} MiB" + (stats.memUsedPercent()?.let { " · $it%" } ?: "")
        } else "—",
    )
    InfoRow(
        "disk /",
        if (stats.diskUsedHuman != null && stats.diskTotalHuman != null) {
            "${stats.diskUsedHuman} / ${stats.diskTotalHuman}" + (stats.diskUsedPercent?.let { " · $it%" } ?: "")
        } else "—",
    )
    if (stats.netRxBps != null && stats.netTxBps != null) {
        InfoRow("network", "↓ ${humanBps(stats.netRxBps)} · ↑ ${humanBps(stats.netTxBps)}")
    }
    stats.sshLatencyMs?.let { InfoRow("ssh rtt", "$it ms") }
}

private fun humanBps(bytesPerSec: Long): String {
    if (bytesPerSec < 1024) return "$bytesPerSec B/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024) return "%.0f KiB/s".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MiB/s".format(mb)
    return "%.1f GiB/s".format(mb / 1024.0)
}

/**
 * The server's accent colour — the swatch, its hex (editable), and a die to roll
 * a new random one. This colour is what the server's NAME is drawn in everywhere
 * in the app, so the row previews exactly that: the name itself, in the colour.
 *
 * The hex field commits only on a COMPLETE, parseable value, so typing "#1" mid-
 * edit never writes a colour and never rejects the keystroke — the field keeps
 * what the user typed and the swatch simply waits.
 */
@Composable
private fun ServerColorRow(
    server: ai.eight24family.conch.domain.Server,
    onPick: (String) -> Unit,
    onRandomize: () -> Unit,
) {
    val accent = ai.eight24family.conch.ui.theme.serverNameColor(
        serverId = server.id, serverName = server.name,
        fallback = MaterialTheme.colorScheme.onSurface,
    )
    // Follow the stored value when it changes underneath us (the die, or an edit
    // on another screen), but never fight the user mid-typing.
    val stored = server.colorHex ?: ai.eight24family.conch.ui.theme.ServerAccent.toHex(accent)
    var text by androidx.compose.runtime.remember(stored) {
        androidx.compose.runtime.mutableStateOf(stored)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(accent, androidx.compose.foundation.shape.CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                ai.eight24family.conch.ui.theme.ServerAccent.parse(raw)?.let {
                    onPick(ai.eight24family.conch.ui.theme.ServerAccent.toHex(it))
                }
            },
            label = { Text("accent") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onRandomize,
            shape = RectangleShape,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) { Text("⁙", fontWeight = FontWeight.Bold) }
    }
    Text(
        server.name,
        color = accent,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
    Text(
        "// the server's name is shown in this colour throughout the app",
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .handCursor()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 14.dp).size(22.dp))
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(title, color = tint, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, color = dim, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("›", color = dim, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 1.dp).weight(0.5f, fill = true),
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
    }
}
