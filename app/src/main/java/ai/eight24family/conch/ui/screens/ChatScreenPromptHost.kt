package ai.eight24family.conch.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val queuedMessages by vm.queuedMessages.collectAsState()
    val enterSends by vm.enterSends.collectAsState()
    val usageBar by vm.usageBar.collectAsState()
    val usageReport by vm.usageReport.collectAsState()
    val usageCost by vm.costStats.collectAsState()
    // Current agent in a BLOCK Claude run-state (no subscription / trial ended /
    // rate limited / login expired …) → the whole prompt bar reflects it: the
    // specific reason as a banner instead of the (meaningless, stale) usage bar,
    // and send disabled. Same truth as the agent-picker row + session list.
    val codeBlockText by vm.claudeBlockLine.collectAsState()
    val codeBlocked = codeBlockText != null
    val contextBreakdown by vm.contextBreakdown.collectAsState()
    val contextLoading by vm.contextLoading.collectAsState()
    val claudePlan by vm.claudePlan.collectAsState()

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
    // @-mention file suggestions — server-side search over the CLI's own file
    // index (Claude control channel). The strip renders only while a trailing
    // @token is being typed AND the channel returned something.
    val mentionQuery = ai.eight24family.conch.util.MentionToken.activeQuery(input)
        ?.takeIf { currentAgent == Agent.CLAUDE }
    androidx.compose.runtime.LaunchedEffect(mentionQuery) {
        vm.updateMentionQuery(mentionQuery)
    }
    val fileSuggestions by vm.fileSuggestions.collectAsState()
    if (mentionQuery != null && fileSuggestions.isNotEmpty()) {
        FileMentionAutocomplete(
            items = fileSuggestions,
            onPick = { path ->
                onInputChange(ai.eight24family.conch.util.MentionToken.complete(input, path))
            },
        )
    }
    // Messages typed mid-turn wait here (visible, cancelable) until the current
    // reply finishes — then they're sent in order.
    if (queuedMessages.isNotEmpty()) {
        QueuedMessagesStrip(queued = queuedMessages, onCancel = { vm.cancelQueued(it) })
    }
    PromptBar(
        input = input,
        onInputChange = onInputChange,
        canSend = !anyUploading && !codeBlocked,
        codeBlocked = codeBlocked,
        codeBlockText = codeBlockText,
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
        claudePlan = claudePlan,
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

/**
 * Visible queue of messages the user sent mid-turn. Each row shows the text +
 * a ✕ to take it back before it's sent. Drained one-per-turn by the VM once the
 * current reply finishes (see [ChatViewModel.drainOutbox]).
 */
@Composable
private fun QueuedMessagesStrip(
    queued: List<ChatViewModel.QueuedMessage>,
    onCancel: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        queued.forEach { q ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.08f))
                    .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp),
                )
                // Tiny thumbnails of any attached images (max 4) — the user wanted
                // the actual little pictures, not a long "Attached image(s) at: …".
                q.thumbs.take(4).forEach { bytes ->
                    val bmp: ImageBitmap? = remember(bytes) {
                        runCatching {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                        )
                    }
                }
                val label = q.displayText.replace('\n', ' ').trim().ifBlank { null }
                if (label != null) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = { onCancel(q.id) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cancel queued message",
                        tint = accent.copy(alpha = 0.85f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}
