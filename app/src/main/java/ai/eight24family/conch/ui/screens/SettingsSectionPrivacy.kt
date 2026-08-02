package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel

@Composable
internal fun SettingsSectionPrivacy(vm: SettingsViewModel) {
    val crashReportingEnabled by vm.crashReportingEnabled.collectAsState()
    var confirmErase by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsRow(
            icon = Icons.Filled.BugReport,
            title = "Crash reporting",
            subtitle = "Anonymized crash + usage events. No messages, no hostnames, no user IDs. Takes effect next launch.",
        ) {
            androidx.compose.material3.Switch(
                checked = crashReportingEnabled,
                onCheckedChange = { vm.setCrashReportingEnabled(it) },
            )
        }
        SettingsRow(
            icon = Icons.Filled.DeleteForever,
            title = "Delete all data",
            subtitle = "Servers, SSH keys, sessions, caches, preferences. Cannot be undone.",
            danger = true,
            onClick = { confirmErase = true },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
        if (confirmErase) {
            AlertDialog(
                onDismissRequest = { confirmErase = false },
                title = { Text("Delete all data?") },
                text = {
                    Text("Servers, SSH keys, sessions, caches, preferences — all gone. This cannot be undone. The app will restart immediately.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmErase = false
                        vm.eraseAllData()
                    }) {
                        Text(
                            "Delete everything",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmErase = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
