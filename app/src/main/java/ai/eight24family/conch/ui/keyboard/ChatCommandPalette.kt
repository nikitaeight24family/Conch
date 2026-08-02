package ai.eight24family.conch.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ai.eight24family.conch.agent.RemoteSession
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.SlashCommands
import ai.eight24family.conch.util.SilentlyTry

/**
 * `Ctrl+K` overlay — quick search across the current chat's remote
 * sessions and every available slash command (built-in + user-defined).
 *
 * Why this exists: in DeX with a keyboard, leaving home row to tap the
 * Sessions list or the slash menu is friction. With Ctrl+K the user can
 * type "depl" and jump to the chat about deploys, or type "/init" and
 * pre-fill the slash command — without ever lifting their fingers.
 *
 * **Phase 5.2** added full keyboard-only operation:
 *  - ↓ / ↑ navigate the merged sessions+commands list.
 *  - Enter activates the highlighted row.
 *  - The highlighted row gets a `primaryContainer`-tinted background.
 *  - `LazyListState.animateScrollToItem` keeps the highlighted row in
 *    view when navigating past the visible area.
 *  - The arrow handlers are installed via `onPreviewKeyEvent` on the
 *    outer Surface so they fire BEFORE the focused search TextField
 *    consumes the keys for its own cursor movement.
 *
 * Out of scope (Phase 5.3 if it becomes friction):
 *  - Fuzzy matching beyond `contains` (Sublime-style abbreviations).
 *  - "Recently used" weighting.
 *  - Cross-server jump (currently only the current server's sessions).
 */
@Composable
fun ChatCommandPalette(
    sessions: List<RemoteSession>,
    customCommands: List<SlashCommand>,
    onPickSession: (RemoteSession) -> Unit,
    onPickSlashCommand: (SlashCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        var query by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            // Autofocus the search field so the user can type
            // immediately after Ctrl+K. Without this, the dialog
            // opens and the user has to tap the field first.
            focusRequester.requestFocus()
        }

        val q = query.trim().lowercase()
        val filteredSessions = remember(q, sessions) {
            if (q.isBlank()) sessions.take(10)
            else sessions.filter { it.preview.lowercase().contains(q) }.take(10)
        }
        val allCommands = remember(customCommands) {
            SlashCommands.BUILT_IN + customCommands
        }
        val filteredCommands = remember(q, allCommands) {
            if (q.isBlank()) allCommands
            else allCommands.filter {
                it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q)
            }
        }

        // Phase 5.2: flat selectable index over [sessions then commands].
        // Reset to 0 when the filter result-set changes so the user's
        // arrow-position doesn't dangle past the end of a now-shorter
        // list.
        var selectedIndex by remember { mutableIntStateOf(0) }
        val selectableCount = filteredSessions.size + filteredCommands.size
        LaunchedEffect(filteredSessions, filteredCommands) {
            if (selectedIndex >= selectableCount) selectedIndex = 0
        }

        val listState = rememberLazyListState()
        // Auto-scroll: translate the flat `selectedIndex` into a
        // LazyColumn item index — accounting for the optional section
        // headers — and animate to it so the highlighted row is always
        // visible. Use animateScrollToItem (vs scrollToItem) so the
        // movement is smooth instead of teleporting on every arrow.
        LaunchedEffect(selectedIndex, selectableCount) {
            if (selectableCount == 0) return@LaunchedEffect
            val sessionsHeader = if (filteredSessions.isNotEmpty()) 1 else 0
            val commandsHeader = if (filteredCommands.isNotEmpty()) 1 else 0
            val lazyIndex = if (selectedIndex < filteredSessions.size) {
                sessionsHeader + selectedIndex
            } else {
                sessionsHeader + filteredSessions.size + commandsHeader +
                    (selectedIndex - filteredSessions.size)
            }
            SilentlyTry.fired("SshAi-Palette", "scroll to selected item") { listState.animateScrollToItem(lazyIndex.coerceAtLeast(0)) }
        }

        /** Invokes the active row's callback. Hoisted so both Enter and a
         *  tap on the row reach the same dispatch logic. */
        fun activateSelected() {
            if (selectableCount == 0) return
            if (selectedIndex < filteredSessions.size) {
                onPickSession(filteredSessions[selectedIndex])
            } else {
                onPickSlashCommand(filteredCommands[selectedIndex - filteredSessions.size])
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .heightIn(max = 520.dp)
                // Arrow / Enter installed at the Surface root via
                // onPreviewKeyEvent — fires BEFORE the focused TextField
                // gets to consume them for cursor movement. Returning
                // true on a match stops the event from reaching the
                // TextField so the cursor doesn't jump on every arrow.
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown -> {
                            if (selectableCount > 0) {
                                selectedIndex = (selectedIndex + 1) % selectableCount
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (selectableCount > 0) {
                                selectedIndex = if (selectedIndex - 1 < 0) selectableCount - 1
                                    else selectedIndex - 1
                            }
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            activateSelected()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Search sessions and commands…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )

                Spacer(Modifier.size(8.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    if (filteredSessions.isNotEmpty()) {
                        item { SectionHeader("Sessions") }
                        items(filteredSessions, key = { "s_" + it.id }) { session ->
                            val flatIdx = filteredSessions.indexOf(session)
                            PaletteRow(
                                icon = Icons.Outlined.Forum,
                                title = session.preview.ifBlank { "(no preview)" },
                                isSelected = flatIdx == selectedIndex,
                                onClick = { onPickSession(session) },
                            )
                        }
                    }
                    if (filteredCommands.isNotEmpty()) {
                        item { SectionHeader("Slash commands") }
                        items(filteredCommands, key = { "c_" + it.name }) { cmd ->
                            val flatIdx = filteredSessions.size + filteredCommands.indexOf(cmd)
                            PaletteRow(
                                icon = Icons.Outlined.Terminal,
                                title = "/" + cmd.name,
                                subtitle = cmd.description.takeIf { it.isNotBlank() },
                                isSelected = flatIdx == selectedIndex,
                                onClick = { onPickSlashCommand(cmd) },
                            )
                        }
                    }
                    if (filteredSessions.isEmpty() && filteredCommands.isEmpty()) {
                        item {
                            Text(
                                "No matches for \"$query\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.size(4.dp))
                Text(
                    "↑↓ navigate · Enter to open · Esc to close",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun PaletteRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    // primaryContainer is the M3 token for "selected list item" — keeps
    // the highlight on-theme (matches the cyberpunk cyan accent without
    // any per-row color logic).
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
