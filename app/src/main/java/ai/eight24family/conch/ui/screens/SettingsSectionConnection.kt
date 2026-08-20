package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ai.eight24family.conch.ui.viewmodel.DeviceKeyEntry
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay

@Composable
internal fun SettingsSectionConnection(vm: SettingsViewModel) {
    val sshConnectTimeoutSec by vm.sshConnectTimeoutSec.collectAsState()
    val sshKeepaliveIntervalSec by vm.sshKeepaliveIntervalSec.collectAsState()
    val dataSaverEnabled by vm.dataSaverEnabled.collectAsState()
    var guardOpen by remember { mutableStateOf(false) }
    // Seamless reconnect moved to the per-server detail page (it's a property of
    // the SERVER, not the app). See ServerDetailScreen's `// seamless reconnect`.
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Data saver — single toggle, comprehensive effect documented in
        // the subtitle. Lives at top of Connection settings because
        // it's the biggest lever for mobile-data users.
        SettingsRow(
            icon = Icons.Filled.Cable,
            title = "Data saver",
            subtitle = "Aggressively cut SSH traffic — no background prefetch, slow poll, longer caches. Use on mobile data.",
        ) {
            Switch(
                checked = dataSaverEnabled,
                onCheckedChange = { vm.setDataSaverEnabled(it) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRow(
                icon = Icons.Filled.Timer,
                title = "Connection timeout",
                subtitle = "How long to wait for the SSH handshake",
            ) {
                Text(
                    "${sshConnectTimeoutSec}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = sshConnectTimeoutSec.toFloat(),
                onValueChange = { v ->
                    val stepped = (v / 5f).toInt() * 5
                    vm.setSshConnectTimeoutSec(stepped.coerceIn(5, 60))
                },
                valueRange = 5f..60f,
                steps = ((60 - 5) / 5) - 1,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRow(
                icon = Icons.Filled.Schedule,
                title = "Keep-alive interval",
                subtitle = "How often to ping the server to keep the connection alive",
            ) {
                Text(
                    "${sshKeepaliveIntervalSec}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = sshKeepaliveIntervalSec.toFloat(),
                onValueChange = { v ->
                    val stepped = (v / 15f).toInt() * 15
                    vm.setSshKeepaliveIntervalSec(stepped.coerceIn(15, 120))
                },
                valueRange = 15f..120f,
                steps = ((120 - 15) / 15) - 1,
            )
        }
        SettingsRow(
            icon = Icons.Filled.Power,
            title = "Keep SSH alive in background",
            subtitle = "Notifications, battery whitelist, vendor autostart",
            onClick = { guardOpen = true },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        if (guardOpen) {
            ConnectionGuardSheet(onDismiss = { guardOpen = false })
        }
    }
}

/**
 * Small circular "?" badge — opens the explanation dialog. Rendered as a
 * bordered text glyph (not a Material icon) so it can't break across
 * material-icons versions and keeps the cyberpunk-CLI cyan accent.
 */
@Composable
internal fun HelpBadge(onClick: () -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = cyan,
        border = BorderStroke(1.dp, cyan),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.size(24.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * "How seamless reconnect works" — styled to the app's CLI language: cyan
 * border, numbered steps, `[ ok ]` pill. Replaces the long settings subtitle.
 */
@Composable
internal fun SeamlessReconnectHelpDialog(onDismiss: () -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, cyan),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Autorenew,
                        contentDescription = null,
                        tint = cyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "Seamless reconnect",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "Keeps a security-key session alive across network changes (Wi-Fi ↔ cellular) without another tap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HelpStep("1", "You tap your security key once, as usual.")
                HelpStep("2", "The app enrolls a temporary device key on the server — hardware-backed, it never leaves your phone.")
                HelpStep("3", "When the network changes, it reconnects silently with that key — no tap, and the security key can stay at home.")
                Text(
                    "The device key self-expires after the lifetime you pick, and is removed the moment you switch this off. It only ever touches its own line in authorized_keys — your other keys are never modified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "While it's on, anyone holding your unlocked phone can reach the server — keep your screen lock on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cyan,
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Surface(
                        onClick = onDismiss,
                        shape = RectangleShape,
                        color = cyan.copy(alpha = 0.10f),
                        contentColor = cyan,
                        border = BorderStroke(1.dp, cyan),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Text(
                            "[ ok ]",
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpStep(n: String, text: String) {
    val cyan = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            n,
            style = MaterialTheme.typography.bodyMedium,
            color = cyan,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Format a remaining-time duration as a live ticking countdown:
 *  `2d 03:14:07` while > 1 day, else `03:14:07`. */
internal fun formatRemaining(ms: Long): String {
    if (ms <= 0L) return "expired"
    val totalSec = ms / 1000
    val d = totalSec / 86_400
    val h = (totalSec % 86_400) / 3_600
    val m = (totalSec % 3_600) / 60
    val s = totalSec % 60
    return if (d > 0) "%dd %02d:%02d:%02d".format(d, h, m, s)
    else "%02d:%02d:%02d".format(h, m, s)
}

/** One enrolled device key: server name + a partial public fingerprint (so the
 *  user can SEE a key exists + which one) and a per-server `[ remove ]` that
 *  strips it from THAT server only. */
@Composable
internal fun DeviceKeyRow(entry: DeviceKeyEntry, now: Long, onRemove: (String) -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    val err = MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, cyan.copy(alpha = 0.5f)),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.serverName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ai.eight24family.conch.ui.theme.serverNameColor(
                        serverId = entry.serverId,
                        serverName = entry.serverName,
                        fallback = MaterialTheme.colorScheme.onSurface,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    // Partial public fingerprint — proves a key exists + identifies
                    // it without dumping the full ~50-char string.
                    entry.fingerprint.take(24) + if (entry.fingerprint.length > 24) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = cyan,
                )
                val exp = entry.expiresAtMs
                Text(
                    if (exp != null) "on the server · expires in ${formatRemaining(exp - now)}"
                    else "on the server",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (exp != null && exp - now <= 0L) err
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                onClick = { onRemove(entry.serverId) },
                shape = RectangleShape,
                color = err.copy(alpha = 0.08f),
                contentColor = err,
                border = BorderStroke(1.dp, err),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Text(
                    "[ remove ]",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
