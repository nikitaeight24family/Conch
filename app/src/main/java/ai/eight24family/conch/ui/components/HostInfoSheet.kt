package ai.eight24family.conch.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.ServerStats
import ai.eight24family.conch.agent.ServerStatsProbe
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Bottom sheet for the host card. Shows:
 *
 *  - **Identity**: name, address, auth method, default agent, host
 *    fingerprint
 *  - **System**: hostname, OS, kernel + arch, uptime, load average
 *  - **Live load** (when the SSH pool has a live client for this
 *    server): CPU %, temperature, memory used/total, disk used/total,
 *    network throughput, SSH latency. Auto-refreshes every 5 s while
 *    the sheet is open.
 *
 * The live stats section is skipped silently when there's no pooled
 * SSH client (e.g. user hasn't tap-to-connected an SK server yet) —
 * we never spin up a fresh handshake just to render this sheet
 * because the SK touch cost would be insane for a tap-to-see panel.
 *
 * Previously a thin "metadata only" sheet was used in non-chat
 * surfaces, and a separate rich `ServerStatsSheet` lived in
 * `ChatScreen.kt`. User complained because the rich one was only
 * reachable from chat — this unification puts everything live +
 * accessible everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostInfoSheet(
    server: Server,
    onDismiss: () -> Unit,
    /** When non-null, a "[ open terminal ]" action appears at the top of the
     *  sheet → dismiss + open a real interactive shell on this host. Wired by
     *  every surface that shows the sheet (agent picker, sessions). */
    onOpenTerminal: (() -> Unit)? = null,
) {
    // Live-stats probe runs in a coroutine + flow-pattern: we hold
    // local state, refresh every 5 s while the sheet is composed.
    var stats by remember { mutableStateOf<ServerStats?>(null) }
    var probing by remember { mutableStateOf(false) }
    val pool = ServiceLocator.sshConnectionPool
    val liveClient by remember(server.id) {
        // peek() is a lock-free snapshot — safe to read every recomposition.
        mutableStateOf(pool.peek(server.id))
    }
    LaunchedEffect(server.id) {
        // Only probe when there's already a live SSH client. Skip
        // entirely otherwise — no `_acquire`, no fresh handshake.
        val client = pool.peek(server.id) ?: return@LaunchedEffect
        val probe = ServerStatsProbe(ServiceLocator.sshClient)
        while (true) {
            probing = true
            withContext(Dispatchers.IO) {
                probe.probe { cmd ->
                    SilentlyTry.logged("SshAi-HostInfo", "exec host info probe") {
                        val sess = client.startSession()
                        try {
                            val proc = sess.exec(cmd)
                            val out = java.io.ByteArrayOutputStream()
                            proc.inputStream.copyTo(out)
                            proc.join(20, java.util.concurrent.TimeUnit.SECONDS)
                            String(out.toByteArray(), Charsets.UTF_8)
                        } finally { SilentlyTry.fired("SshAi-HostInfo", "close host info session") { sess.close() } }
                    }
                }.onSuccess { stats = it }
            }
            probing = false
            delay(5_000)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Identity ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("// host", Modifier.weight(2f))
                onOpenTerminal?.let { open ->
                    OutlinedButton(
                        onClick = { onDismiss(); open() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("terminal", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            InfoRow("name", server.name)
            InfoRow("address", "${server.username}@${server.host}:${server.port}")
            InfoRow("auth", server.authMethod.name.lowercase())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "default agent",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 1.dp).weight(0.5f, fill = true),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = true),
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            ai.eight24family.conch.agent.spec.AgentSpecRegistry[server.agent].iconRes,
                        ),
                        contentDescription = server.agent.cliCommand,
                        tint = androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.size(14.dp).padding(end = 6.dp),
                    )
                    Text(
                        server.agent.cliCommand,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            server.knownHostKey?.takeIf { it.isNotBlank() }?.let { fp ->
                InfoRow("host fingerprint", fp.take(48))
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // ── System ──
            SectionTitle("// system")
            val systemRowVisible = stats != null
            if (!systemRowVisible) {
                InfoRow(
                    "status",
                    if (liveClient == null) "not connected · open a chat to probe"
                    else if (probing) "probing..." else "no data yet",
                )
            }
            stats?.hostname?.let { InfoRow("hostname", it) }
            stats?.osPretty?.let { InfoRow("os", it) }
            stats?.let { s ->
                val kernelArch = listOfNotNull(s.kernel, s.arch).joinToString(" · ")
                if (kernelArch.isNotBlank()) InfoRow("kernel", kernelArch)
            }
            stats?.uptime?.let { InfoRow("uptime", it) }
            stats?.let { s ->
                val parts = listOfNotNull(s.loadAvg1m, s.loadAvg5m, s.loadAvg15m)
                if (parts.isNotEmpty()) {
                    InfoRow(
                        "load",
                        parts.joinToString(" · ") { "%.2f".format(it) } +
                            " (1m · 5m · 15m)",
                    )
                }
            }

            // ── Live load ── (only when stats came back)
            if (stats != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                SectionTitle(
                    "// live  ·  ${if (probing) "refreshing..." else "every 5s"}",
                )
                val s = stats!!
                InfoRow(
                    "cpu",
                    listOfNotNull(
                        s.cpuPercent?.let { "$it%" },
                        s.cpuCount?.let { "$it cpu${if (it == 1) "" else "s"}" },
                        s.cpuTempC?.let { "%.0f°C".format(it) },
                    ).joinToString(" · ").ifBlank { "—" },
                )
                InfoRow(
                    "memory",
                    if (s.memUsedMb != null && s.memTotalMb != null) {
                        "${s.memUsedMb} / ${s.memTotalMb} MiB" +
                            (s.memUsedPercent()?.let { " · $it%" } ?: "")
                    } else "—",
                )
                InfoRow(
                    "disk /",
                    if (s.diskUsedHuman != null && s.diskTotalHuman != null) {
                        "${s.diskUsedHuman} / ${s.diskTotalHuman}" +
                            (s.diskUsedPercent?.let { " · $it%" } ?: "")
                    } else "—",
                )
                if (s.netRxBps != null && s.netTxBps != null) {
                    InfoRow(
                        "network",
                        "↓ ${humanBps(s.netRxBps)} · ↑ ${humanBps(s.netTxBps)}",
                    )
                }
                s.sshLatencyMs?.let {
                    InfoRow("ssh rtt", "${it} ms")
                }
            }
        }
    }
}

private fun humanBps(bytesPerSec: Long): String {
    if (bytesPerSec < 1024) return "${bytesPerSec} B/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024) return "%.0f KiB/s".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MiB/s".format(mb)
    return "%.1f GiB/s".format(mb / 1024.0)
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
