package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.SessionState
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
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val server by vm.server.collectAsState()
    val connected by vm.connected.collectAsState()
    val currentAgent by vm.currentAgent.collectAsState()
    val remoteSessions by vm.remoteSessions.collectAsState()
    val resumeId by vm.resumeId.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val observedModel by vm.observedModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val unavailableModels by vm.unavailableModelLabels.collectAsState()
    val modelsProbing by vm.modelsProbing.collectAsState()
    val defaultModel by vm.defaultModel.collectAsState()
    val sessionInitialModel by vm.sessionInitialModel.collectAsState()
    val selectedReasoning by vm.selectedReasoning.collectAsState()
    val reasoningCatalog by vm.reasoningCatalog.collectAsState()
    val defaultReasoning by vm.defaultReasoning.collectAsState()
    val sessionInitialReasoning by vm.sessionInitialReasoning.collectAsState()
    val observedReasoning by vm.observedReasoning.collectAsState()
    val customCommands by vm.customCommands.collectAsState()
    val approvalMode by vm.approvalMode.collectAsState()
    val showApprovalIcon by vm.showApprovalInChatBar.collectAsState()

    val loadCameBackEmpty by vm.loadCameBackEmpty.collectAsState()
    // Claude's auto-generated session title (ai-title) — the real title, like the
    // CLI shows. Preferred over the listing preview / first user message.
    val observedTitle by vm.observedTitle.collectAsState()
    val title = observedTitle?.takeIf { it.isNotBlank() }
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
            loadCameBackEmpty -> "// session unavailable"
            else -> "// loading…"
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
        connected = connected,
        selectedModel = selectedModel,
        observedModel = observedModel,
        availableModels = availableModels,
        unavailableModels = unavailableModels,
        modelsProbing = modelsProbing,
        defaultModel = defaultModel,
        sessionInitialModel = sessionInitialModel,
        selectedReasoning = selectedReasoning,
        reasoningCatalog = reasoningCatalog,
        defaultReasoning = defaultReasoning,
        sessionInitialReasoning = sessionInitialReasoning,
        observedReasoning = observedReasoning,
        modelMenuOpen = modelMenuOpen,
        onToggleModelMenu = onToggleModelMenu,
        onSelectModel = { m -> vm.setModel(m); onCloseModelMenu() },
        onSelectModelAndReasoning = { m, r ->
            vm.setModelAndReasoning(m, r)
            onCloseModelMenu()
        },
        onOpenServerStats = {
            onOpenStatsSheet()
            vm.refreshServerStats()
        },
        customCommands = customCommands,
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
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }
    }
}
