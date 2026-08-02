package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom-sheet snapshot of the SSH host's vitals. Auto-refresh cadence
 * (5 s) is driven by the caller — the sheet is presentational only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerStatsSheet(
    serverName: String,
    username: String = "",
    host: String,
    port: Int,
    observedModel: String?,
    stats: ai.eight24family.conch.agent.ServerStats?,
    loading: Boolean,
    onDismiss: () -> Unit,
    /** When non-null, a "[ open terminal ]" action appears → dismiss + open a
     *  real interactive shell on this host. */
    onOpenTerminal: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Server name on the left; a compact "terminal" button (~1/3 width)
            // on the right, level with the name.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    serverName.ifBlank { "—" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(2f),
                )
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
            Text(
                "// ${if (loading) "probing..." else "auto-refresh every 5s"}",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )

            StatRow("address", if (username.isNotBlank()) "$username@$host:$port" else "$host:$port")
            observedModel?.takeIf { it.isNotBlank() }?.let { StatRow("active model", it) }
            stats?.hostname?.takeIf { it.isNotBlank() }?.let { StatRow("hostname", it) }
            stats?.osPretty?.takeIf { it.isNotBlank() }?.let { StatRow("os", it) }
            StatRow(
                "ssh latency",
                stats?.sshLatencyMs?.let { "${it} ms" } ?: "—",
            )
            stats?.uptime?.takeIf { it.isNotBlank() }?.let { StatRow("uptime", it) }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // CPU as a single percent across all cores.
            val cpu = stats?.cpuPercent?.let {
                val tail = stats.cpuCount?.let { c -> " · ${c} cpu${if (c == 1) "" else "s"}" } ?: ""
                "$it%$tail"
            } ?: "—"
            StatRow("cpu", cpu)

            // Memory used / total + percent.
            val mem = stats?.let {
                val u = it.memUsedMb
                val t = it.memTotalMb
                val pct = it.memUsedPercent()
                if (u != null && t != null) "${u} / ${t} MiB" + (pct?.let { p -> " · $p%" } ?: "") else null
            } ?: "—"
            StatRow("memory", mem)

            // Disk root.
            val disk = stats?.let {
                if (it.diskUsedHuman != null && it.diskTotalHuman != null)
                    "${it.diskUsedHuman} / ${it.diskTotalHuman}" + (it.diskUsedPercent?.let { p -> " · $p%" } ?: "")
                else null
            } ?: "—"
            StatRow("disk /", disk)

            // Server-side network throughput on its own NIC (1-sec delta).
            val net = stats?.let {
                val rx = it.netRxBps
                val tx = it.netTxBps
                if (rx != null && tx != null) "↓ ${humanBytes(rx)}/s · ↑ ${humanBytes(tx)}/s" else null
            } ?: "—"
            StatRow("server net", net)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GiB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
