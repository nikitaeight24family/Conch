package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.data.prefs.AgentApprovalMode

/**
 * Topbar shield icon + dropdown menu for approval-mode selection.
 * Three modes (SAFE/AUTO/YOLO) plus a "let agent drop its own limits"
 * shortcut that sends a per-CLI prompt to relax the agent's own
 * approval config and continue the in-flight task.
 */
@Composable
internal fun ApprovalShield(
    mode: AgentApprovalMode,
    menuOpen: Boolean,
    onToggle: () -> Unit,
    onPick: (AgentApprovalMode) -> Unit,
    onAskAgentToRelax: () -> Unit,
    /**
     * Hide the icon button and only render the dropdown menu when invoked
     * from inside the overflow `⋮` menu — there the anchor is the overflow
     * icon itself, we don't want a redundant shield button beside it.
     */
    showAnchorIcon: Boolean = true,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val (icon, tint) = when (mode) {
        AgentApprovalMode.SAFE -> Icons.Outlined.Shield to outline
        AgentApprovalMode.AUTO -> Icons.Filled.Shield to cyan
        AgentApprovalMode.YOLO -> Icons.Filled.Bolt to tertiary
    }
    // UX-1: the self-escalation shortcut is destructive (it rewrites the agent's
    // OWN approval config ON THE SERVER so it never asks again), so gate it behind
    // an explicit confirm instead of firing on a single dropdown tap.
    var confirmRelax by remember { mutableStateOf(false) }
    Box {
        if (showAnchorIcon) {
            IconButton(onClick = onToggle) {
                Icon(icon, contentDescription = "approval mode: ${mode.name.lowercase()}", tint = tint)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onToggle) {
            ApprovalRow(
                mode = AgentApprovalMode.SAFE,
                title = "safe",
                subtitle = "CLI defaults · tool writes may stall in headless",
                icon = Icons.Outlined.Shield,
                selected = mode == AgentApprovalMode.SAFE,
                onPick = onPick,
            )
            ApprovalRow(
                mode = AgentApprovalMode.AUTO,
                title = "auto",
                subtitle = "auto-approve edits · escalate on failure",
                icon = Icons.Filled.Shield,
                selected = mode == AgentApprovalMode.AUTO,
                onPick = onPick,
            )
            ApprovalRow(
                mode = AgentApprovalMode.YOLO,
                title = "yolo",
                subtitle = "bypass sandbox · no approvals · trusted hosts only",
                icon = Icons.Filled.Bolt,
                selected = mode == AgentApprovalMode.YOLO,
                onPick = onPick,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            // Sends a per-CLI prompt that asks the agent to relax its own
            // approval config (claude → settings.json, codex → config.toml,
            // gemini → settings.json) and then continue whatever it was
            // doing. Useful when you're in the middle of a task and the
            // agent stalls asking for approval — one tap lets it unblock
            // itself permanently.
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                },
                text = {
                    Column {
                        Text(
                            "let agent drop its own limits",
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "writes to the CLI's config file + resumes the current task",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                onClick = {
                    onToggle()           // close the dropdown
                    confirmRelax = true  // …then ask for explicit confirmation
                },
            )
        }
    }

    if (confirmRelax) {
        AlertDialog(
            onDismissRequest = { confirmRelax = false },
            icon = {
                Icon(
                    Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            },
            title = { Text("Drop the agent's own limits?") },
            text = {
                Text(
                    "This tells the agent to rewrite its OWN approval config on the server " +
                        "(claude settings.json · codex config.toml · gemini settings.json) so it " +
                        "stops asking for approval — permanently, for every future turn in this " +
                        "CLI, not just now — then resume the current task. Only do this on a host " +
                        "you trust.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRelax = false
                    onAskAgentToRelax()
                }) {
                    Text("Drop limits", color = MaterialTheme.colorScheme.tertiary)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRelax = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ApprovalRow(
    mode: AgentApprovalMode,
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onPick: (AgentApprovalMode) -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val accent = when (mode) {
        AgentApprovalMode.SAFE -> outline
        AgentApprovalMode.AUTO -> cyan
        AgentApprovalMode.YOLO -> tertiary
    }
    DropdownMenuItem(
        leadingIcon = { Icon(icon, contentDescription = title, tint = accent) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (selected) {
                        Spacer(modifier = Modifier.size(6.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "selected",
                            tint = accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    subtitle,
                    color = outline,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        onClick = { onPick(mode) },
    )
}
