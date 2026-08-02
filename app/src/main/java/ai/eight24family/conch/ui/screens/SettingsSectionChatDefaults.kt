package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel

@Composable
internal fun SettingsSectionChatDefaults(vm: SettingsViewModel) {
    val approvalMode by vm.approvalMode.collectAsState()
    val showApprovalIcon by vm.showApprovalInChatBar.collectAsState()
    // NB: there is no global "default agent" — the agent is chosen per server
    // when you add it (AddServerScreen) and per visit in the agent picker.
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRow(
                icon = Icons.Filled.Shield,
                title = "Default approval mode",
                subtitle = "The default for agents you haven't set individually — each agent keeps its own, and you override it live from the shield in a chat's top bar. SAFE asks first · AUTO auto-approves edits · YOLO bypasses everything.",
            ) {}
            PillPicker(
                options = AgentApprovalMode.entries.toList(),
                selected = approvalMode,
                label = { it.name.lowercase() },
                onPick = { vm.setApprovalMode(it) }
            )
        }
        SettingsRow(
            icon = Icons.Filled.Shield,
            title = "Approval shield in chat",
            subtitle = "Show the approval-mode icon in the chat top bar. Turn off once you've picked your level and don't want to see or change it per chat.",
        ) {
            androidx.compose.material3.Switch(
                checked = showApprovalIcon,
                onCheckedChange = { vm.setShowApprovalInChatBar(it) },
            )
        }
    }
}
