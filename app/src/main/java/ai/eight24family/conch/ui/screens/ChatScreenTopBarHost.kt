package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.ui.components.PhoneBridgeGlyph
import ai.eight24family.conch.ui.viewmodel.ChatViewModel

/**
 * Topbar host. Resolves the chat title, pulls every model/reasoning/
 * approval flow off the VM, and wires the menu open/close setters
 * through to [TerminalTopBar]. The orchestrator only owns the menu-open
 * booleans (because they participate in keyboard-shortcut dispatch) and
 * the search-query state (search lives in the topbar but its body is
 * rendered in the content slot).
 *
 * Title resolution order:
 *   1. Preview from the cached remote-sessions list (most
 *      authoritative — same string SessionsScreen rendered).
 *   2. First UserText in the message stream (the session's opening
 *      prompt, sliced to a single line). Used while remoteSessions is
 *      still being fetched after a deep link / fresh nav into the chat.
 *   3. "// new chat" only when there is genuinely no resumeId — i.e.
 *      the user really did open a new session, not tap into an
 *      existing one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBarHost(
    vm: ChatViewModel,
    messages: List<AgentMessage>,
    state: SessionState,
    remoteWorking: Boolean,
    modelMenuOpen: Boolean,
    onToggleModelMenu: () -> Unit,
    onCloseModelMenu: () -> Unit,
    commandMenuOpen: Boolean,
    onToggleCommandMenu: () -> Unit,
    onCloseCommandMenu: () -> Unit,
    approvalMenuOpen: Boolean,
    onToggleApprovalMenu: () -> Unit,
    onCloseApprovalMenu: () -> Unit,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onOpenStatsSheet: () -> Unit,
    onOpenSubagents: (chatId: String?) -> Unit,
    onForkChat: (resumeId: String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val server by vm.server.collectAsState()
    val connected by vm.connected.collectAsState()
    val currentAgent by vm.currentAgent.collectAsState()
    val remoteSessions by vm.remoteSessions.collectAsState()
    val resumeId by vm.resumeId.collectAsState()
    val bridgePresence by vm.bridgePresence.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val observedModel by vm.observedModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val hiddenModels by vm.hiddenModels.collectAsState()
    val unavailableModels by vm.unavailableModelLabels.collectAsState()
    val obsNewerThanPick by vm.observationNewerThanPick.collectAsState()
    val effortPickIsNewer by vm.reasoningPickIsNewerFlow.collectAsState()
    val modelsProbing by vm.modelsProbing.collectAsState()
    val modelsStale by vm.modelsStale.collectAsState()
    val defaultModel by vm.defaultModel.collectAsState()
    val sessionInitialModel by vm.sessionInitialModel.collectAsState()
    val selectedReasoning by vm.selectedReasoning.collectAsState()
    val reasoningCatalog by vm.reasoningCatalog.collectAsState()
    val defaultReasoning by vm.defaultReasoning.collectAsState()
    val sessionInitialReasoning by vm.sessionInitialReasoning.collectAsState()
    val observedReasoning by vm.observedReasoning.collectAsState()
    val phoneCloudLoggedIn by vm.phoneCloudLoggedIn.collectAsState()
    val customCommands by vm.customCommands.collectAsState()
    val agentCommands by vm.agentCommands.collectAsState()
    val approvalMode by vm.approvalMode.collectAsState()
    val showApprovalIcon by vm.showApprovalInChatBar.collectAsState()

    val loadCameBackEmpty by vm.loadCameBackEmpty.collectAsState()
    // Claude's auto-generated session title (ai-title) — the real title, like the
    // CLI shows. Preferred over the listing preview / first user message.
    val observedTitle by vm.observedTitle.collectAsState()
    // A rename the user just performed wins over everything — the server-side
    // listing catches up on its next sweep.
    val renamedTitle by vm.renamedTitle.collectAsState()
    val title = renamedTitle?.takeIf { it.isNotBlank() }
        ?: observedTitle?.takeIf { it.isNotBlank() }
        ?: remoteSessions.firstOrNull { it.id == resumeId }
            ?.preview?.takeIf { it.isNotBlank() }
        ?: messages.firstOrNull { it is AgentMessage.UserText }
            ?.let { (it as AgentMessage.UserText).text }
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(80)
        // A resumed session with no cache whose server file is gone/unreachable
        // must NOT hang on "// loading…" forever. loadCameBackEmpty flips the
        // fallback to a clear terminal state once the fetch returns empty.
        ?: when {
            resumeId == null -> "// new chat"
            // ⚠ AN ID IS NOT A TRANSCRIPT. A new chat is handed a session id the
            // moment the CLI announces one, so this fell straight through to "//
            // loading…" and sat there over an empty new session with nothing
            // being loaded at all. Only a chat opened FROM the session list is
            // waiting on anything.
            !vm.openedAsResume -> "// new chat"
            loadCameBackEmpty -> "// session unavailable"
            else -> "// loading…"
        }

    // Rename-session dialog (rename_session over the live control channel).
    var renameDialogOpen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    if (renameDialogOpen) {
        var renameText by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                title.takeIf { !it.startsWith("//") }.orEmpty()
            )
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameDialogOpen = false },
            title = { Text("Rename session") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Title") },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        vm.renameSession(renameText)
                        renameDialogOpen = false
                    },
                ) { Text("Rename") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameDialogOpen = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Manual compaction — confirmed, with the cost on screen. Compaction
    // rewrites the conversation into a summary: it is not undoable from here,
    // and the next turn re-caches the (now much smaller) context. Say both.
    // ⚠ THE ONE POPUP THAT EARNS ITS INTERRUPTION. It appears only when the
    // next message would cost far more than it looks like it should, and it
    // offers the cheaper way out rather than just an OK.
    val costWarning by vm.costWarning.collectAsState()
    costWarning?.let { w ->
        val cold = w.kind == ChatViewModel.CostWarning.Kind.COLD_CACHE
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissCostWarning() },
            icon = {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            },
            title = {
                Text(if (cold) "This message re-sends the whole conversation"
                     else "This session is already running on the server")
            },
            text = {
                Column {
                    Text(
                        if (cold)
                            "It has been idle over an hour, so the provider's cache has " +
                                "expired. Sending now pays for the entire conversation again — " +
                                "about ${w.percent}% of the context window — instead of reading " +
                                "it back cheaply."
                        else
                            "Something else is writing this conversation right now — a terminal " +
                                "on the server, or a background agent. Sending from here starts a " +
                                "SECOND agent on the same session.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                    Text(
                        if (cold)
                            "Compacting first replaces the earlier turns with a summary, so this " +
                                "send — and every later one — carries far less. Nothing leaves the " +
                                "transcript."
                        else
                            "Waiting until it finishes keeps one agent on one conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { if (cold) vm.compactThenSend() else vm.dismissCostWarning() },
                ) { Text(if (cold) "compact first" else "wait") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.sendAnyway() }) {
                    Text("send anyway")
                }
            },
        )
    }

    val pendingCompact by vm.pendingCompact.collectAsState()
    pendingCompact?.let { pc ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.cancelCompact() },
            title = { Text("Compact this conversation?") },
            text = {
                Column {
                    Text(
                        "Context now: ${pc.percent}% of the window.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    androidx.compose.foundation.layout.Spacer(
                        Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "Claude Code replaces the earlier turns with a summary, so the " +
                            "conversation keeps going in far less context. The exchange itself " +
                            "is not lost from the transcript, but the agent will only see the " +
                            "summary from here on — and the next message pays to cache the new, " +
                            "shorter context once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.confirmCompact() }) {
                    Text("Compact")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.cancelCompact() }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Bar, then a thin full-width session-title strip stacked below it.
    Column(modifier = Modifier.fillMaxWidth()) {
    TerminalTopBar(
        title = title,
        agent = currentAgent,
        // Fall back to the cached name so the server shows at frame zero,
        // before vm.server resolves from Room (avoids the "one line, no server"
        // flash on open).
        serverName = server?.name ?: vm.cachedServerName ?: "",
        serverId = server?.id,
        connected = connected,
        selectedModel = selectedModel,
        observedModel = observedModel,
        availableModels = availableModels,
        hiddenModels = hiddenModels,
        unavailableModels = unavailableModels,
        observationNewerThanPick = obsNewerThanPick,
        reasoningPickIsNewer = effortPickIsNewer,
        modelsProbing = modelsProbing,
        modelsStale = modelsStale,
        defaultModel = defaultModel,
        sessionInitialModel = sessionInitialModel,
        selectedReasoning = selectedReasoning,
        reasoningCatalog = reasoningCatalog,
        defaultReasoning = defaultReasoning,
        sessionInitialReasoning = sessionInitialReasoning,
        observedReasoning = observedReasoning,
        modelMenuOpen = modelMenuOpen,
        phoneCloudLoggedIn = phoneCloudLoggedIn,
        onToggleModelMenu = onToggleModelMenu,
        // requestSetModel, NOT setModel: a switch busts the per-model prompt
        // cache, so the user is asked first — on Anthropic's own terms.
        onSelectModel = { m ->
            vm.requestSetModel(m, availableModels[m] ?: m.orEmpty())
            onCloseModelMenu()
        },
        onSelectModelAndReasoning = { m, r ->
            vm.setReasoning(r)
            vm.requestSetModel(m, availableModels[m] ?: m.orEmpty())
            onCloseModelMenu()
        },
        onOpenServerStats = {
            onOpenStatsSheet()
            vm.refreshServerStats()
        },
        // The menu lists the user's own command files AND the CLI's own
        // commands/skills — the latter were unreachable from the phone until now.
        customCommands = customCommands + agentCommands,
        commandMenuOpen = commandMenuOpen,
        onToggleCommandMenu = onToggleCommandMenu,
        onPickCommand = { cmd ->
            onCloseCommandMenu()
            vm.dispatchSlash(cmd)
        },
        onOpenMemory = { vm.openMemoryEditor() },
        onOpenAgents = { onOpenSubagents(vm.localSessionId.value) },
        showSubagentsIcon = currentAgent.supportsSubagents,
        showMemoryIcon = currentAgent.supportsMemory,
        onRestartCli = { vm.restartCli() },
        showRenameItem = currentAgent == ai.eight24family.conch.agent.Agent.CLAUDE,
        // Forking needs a session to inherit: `--fork-session` is meaningless
        // without `--resume`, so a chat that has never been assigned an id has
        // nothing to branch from.
        showForkItem = currentAgent == ai.eight24family.conch.agent.Agent.CLAUDE && resumeId != null,
        onForkChat = { resumeId?.let { onForkChat(it) } },
        onRenameSession = { renameDialogOpen = true },
        showCompactItem = currentAgent == ai.eight24family.conch.agent.Agent.CLAUDE,
        onCompact = { vm.requestCompact() },
        approvalMode = approvalMode,
        approvalMenuOpen = approvalMenuOpen,
        onToggleApprovalMenu = onToggleApprovalMenu,
        onSelectApproval = { mode ->
            vm.setApprovalMode(mode)
            onCloseApprovalMenu()
        },
        onAskAgentToRelaxApprovals = { vm.sendDisableApprovalsPrompt() },
        showApprovalIcon = showApprovalIcon,
        showWorkingHint = state is SessionState.Working || remoteWorking,
        onOpenSettings = onOpenSettings,
        searchActive = searchQuery != null,
        searchQuery = searchQuery.orEmpty(),
        searchMatchCount = remember(searchQuery, messages) {
            val q = searchQuery
            if (q.isNullOrEmpty()) 0 else buildInChatHits(messages, q).size
        },
        onSearchQueryChange = onSearchQueryChange,
        onOpenSearch = { onSearchQueryChange(searchQuery ?: "") },
        onCloseSearch = { onSearchQueryChange(null) },
        onBack = onBack,
    )
        // Session title — a thin, full-width caption strip under the bar. The
        // bar's title slot now holds only the model picker, so the session name
        // gets the WHOLE screen width here. Hidden while in-chat search is open.
        if (title.isNotBlank() && searchQuery == null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 📱 at the END of the session name — same glyph the session LISTS
                // show, so opening a wired chat keeps the phone exactly where the
                // list had it (moved here from the usage bar). Colored = bridge
                // live, dim = was connected/now offline.
                PhoneBridgeGlyph(
                    bridgePresence,
                    modifier = Modifier.padding(start = 6.dp),
                    size = 14.dp,
                )
            }
        }
    }
}
