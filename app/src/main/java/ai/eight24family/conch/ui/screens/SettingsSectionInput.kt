package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel

@Composable
internal fun SettingsSectionInput(vm: SettingsViewModel) {
    val enterSends by vm.enterSends.collectAsState()
    var shortcutsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.KeyboardReturn,
            title = "Send on Enter",
            subtitle = "When off, Enter inserts a newline; use the send button",
        ) {
            androidx.compose.material3.Switch(
                checked = enterSends,
                onCheckedChange = { vm.setEnterSends(it) },
            )
        }
        SettingsRow(
            icon = Icons.Filled.Keyboard,
            title = "Keyboard shortcuts",
            subtitle = "Hardware keyboard chords (Cmd ≡ Ctrl on Apple)",
            onClick = { shortcutsExpanded = !shortcutsExpanded },
        ) {
            Icon(
                if (shortcutsExpanded) Icons.Filled.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        if (shortcutsExpanded) {
            ai.eight24family.conch.ui.keyboard.DefaultShortcuts.All.forEach { (chord, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chord.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = true),
                    )
                }
            }
        }
    }
}
