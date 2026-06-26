package ai.eight24family.conch.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.ui.viewmodel.ChatViewModel

/**
 * Bottom-of-column host for the slash-command autocomplete strip and the
 * [PromptBar] itself. Lives in the content slot (not Scaffold.bottomBar)
 * so it rises with the whole chat block via the outer Column's
 * imePadding — see the user's feedback captured in the comment above the
 * outer Column in ChatScreen.kt.
 */
@Composable
internal fun ChatPromptHost(
    vm: ChatViewModel,
    input: String,
    onInputChange: (String) -> Unit,
    onSlashAcPick: (SlashCommand) -> Unit,
    onSend: () -> Unit,
    serverId: String,
    cameFromSearch: Boolean,
    currentAgent: Agent,
    state: SessionState,
    remoteWorking: Boolean,
    usageExpanded: Boolean,
    onUsageExpandedChange: (Boolean) -> Unit,
) {
    val anyUploading by vm.anyUploading.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val customCommands by vm.customCommands.collectAsState()
    val reconnecting by vm.reconnecting.collectAsState()
    val reconnectAttempt by vm.reconnectAttempt.collectAsState()
    val hasPending by vm.hasPending.collectAsState()
    val enterSends by vm.enterSends.collectAsState()
    val usageBar by vm.usageBar.collectAsState()
    val usageReport by vm.usageReport.collectAsState()
    val usageCost by vm.costStats.collectAsState()
    val contextBreakdown by vm.contextBreakdown.collectAsState()
    val contextLoading by vm.contextLoading.collectAsState()
    val bridgeActive by vm.bridgeActive.collectAsState()

    // Slash-command autocomplete state. Filters built-in + user-defined
    // commands by what's typed after the leading `/` and before any
    // space.
    val acItems = if (input.startsWith("/") && !input.contains(' ')) {
        ai.eight24family.conch.agent.SlashCommands
            .matchPrefix(input.removePrefix("/"), customCommands)
            .take(7)
    } else emptyList()

    if (acItems.isNotEmpty()) {
        SlashAutocomplete(
            items = acItems,
            onPick = onSlashAcPick,
        )
    }
    PromptBar(
        input = input,
        onInputChange = onInputChange,
        canSend = !anyUploading,
        working = state is SessionState.Working || remoteWorking,
        usage = usageBar,
        usageReport = usageReport,
        usageCost = usageCost,
        usageExpanded = usageExpanded,
        onUsageExpandedChange = { exp ->
            // Kick off the (slow, copy-based) /context probe the first time the
            // panel opens — Claude-only, cached after the first fetch.
            if (exp) vm.fetchContextBreakdown()
            onUsageExpandedChange(exp)
        },
        contextBreakdown = contextBreakdown,
        contextLoading = contextLoading,
        bridgeActive = bridgeActive,
        uploading = anyUploading,
        statusHint = run {
            // Suppress "// agent: failed —" / "disconnected" hints when
            // the chat is in search-opened read-only mode without a
            // live SSH transport. In that mode an agent session can't
            // possibly start (no signer), so reporting its Failed state
            // next to the prompt bar is exactly the noise the user
            // flagged moving.
            val offlineReadOnly = cameFromSearch &&
                ServiceLocator.sshConnectionPool.peek(serverId) == null
            if (offlineReadOnly) null
            else promptBarStatusHint(
                state = state,
                anyUploading = anyUploading,
                reconnecting = reconnecting,
                reconnectAttempt = reconnectAttempt,
                inputBlank = input.isBlank(),
                attachmentsEmpty = attachments.isEmpty(),
                hasPending = hasPending,
            )
        },
        enterSends = enterSends,
        attachments = attachments,
        canAttachMore = attachments.size < ChatViewModel.MAX_ATTACHMENTS,
        onAddAttachment = { bytes, name, mime -> vm.addAttachment(bytes, name, mime) },
        onAddFileAttachment = { file, name, mime, size -> vm.addFileAttachment(file, name, mime, size) },
        onRemoveAttachment = { vm.removeAttachment(it) },
        onConnectPhone = { vm.connectPhoneToServer() },
        onStop = { vm.stopCurrent() },
        onSend = onSend,
    )
}
