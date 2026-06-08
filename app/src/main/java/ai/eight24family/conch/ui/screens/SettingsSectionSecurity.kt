package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.data.prefs.SkNotificationVisibility
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel

@Composable
internal fun SettingsSectionSecurity(vm: SettingsViewModel, onOpenKeychain: () -> Unit) {
    val skNotificationVisibility by vm.skNotificationVisibility.collectAsState()
    val bridgeShellAllowed by vm.bridgeShellAllowed.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsRow(
            icon = Icons.Outlined.VpnKey,
            title = "SSH keys",
            subtitle = "Manage passwordless logins and hardware security keys",
            onClick = onOpenKeychain,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        // SEC-1: phone-side kill-switch for the bridge's most dangerous verb.
        // The bridge runs shell at adb level on this phone for whatever code is
        // running as your SSH user on the server — flip this off for servers you
        // don't fully trust (shared / root@ hosts). Logs & screenshots stay on.
        SettingsRow(
            icon = Icons.Filled.Terminal,
            title = "Run shell from server",
            subtitle = "Let a server agent run shell commands on this phone via the bridge. " +
                "Off = bridge can still read logs & take screenshots, but shell is refused.",
            onClick = { vm.setBridgeShellAllowed(!bridgeShellAllowed) },
        ) {
            Switch(
                checked = bridgeShellAllowed,
                onCheckedChange = { vm.setBridgeShellAllowed(it) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRow(
                icon = Icons.Filled.Visibility,
                title = "Lock-screen visibility",
                subtitle = "Public shows full text · private hides text · secret hides the notification",
            ) {}
            PillPicker(
                options = SkNotificationVisibility.entries.toList(),
                selected = skNotificationVisibility,
                label = { it.name.lowercase() },
                onPick = { vm.setSkNotificationVisibility(it) },
            )
        }
    }
}
