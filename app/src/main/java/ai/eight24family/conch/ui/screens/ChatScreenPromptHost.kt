package ai.eight24family.conch.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    val agentCommands by vm.agentCommands.collectAsState()
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
    // No live CLI and the last turn is older than the cache's hour: the next
    // message pays to re-send the whole conversation.
    val coldRebuild by vm.coldCacheRebuild.collectAsState()
    val runningElsewhere by vm.runningElsewhere.collectAsState()

    // Slash-command autocomplete state. Filters built-in + user-defined
    // commands by what's typed after the leading `/` and before any
    // space.
    // Ours first, then the user's own files, then everything the CLI itself
    // offers (its skills included) — which the palette never showed at all.
    val acItems = if (input.startsWith("/") && !input.contains(' ')) {
        ai.eight24family.conch.agent.SlashCommands
            .matchPrefix(input.removePrefix("/"), customCommands + agentCommands)
            .take(9)
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
    // A `/loop` the CLI armed for itself: it will wake up and spend tokens with
    // no further input, so it gets a visible countdown and a stop.
    val loopArmed by vm.loopArmed.collectAsState()
    loopArmed?.let { LoopStrip(armed = it, onStop = { vm.stopLoop() }) }
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
                // Someone else is writing this session's file while our own
                // turn is idle — a terminal on the server, or a background
                // agent. Sending from here would launch a second CLI on it.
                runningElsewhere = runningElsewhere,
                coldRebuild = coldRebuild,
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
 * The armed `/loop`: what it will do, when, and one tap to end it.
 *
 * A loop is the one thing in the app that spends money while the user is doing
 * nothing, so it is not allowed to be invisible. The countdown is live, and the
 * model's own one-line reason for the delay sits under it — it is the only
 * honest answer to "why is it waiting that long".
 */
@Composable
private fun LoopStrip(
    armed: ai.eight24family.conch.agent.LoopWatch.Armed,
    onStop: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // Recomposes once a second while the strip is on screen; stops with it.
    val now = androidx.compose.runtime.remember(armed) {
        androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())
    }
    androidx.compose.runtime.LaunchedEffect(armed) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            now.longValue = System.currentTimeMillis()
        }
    }
    val left = ((armed.dueAtMs - now.longValue).coerceAtLeast(0L) / 1000L).toInt()
    // An interval loop has a cadence, not a single next moment — counting down
    // to a time we don't know would be an invention.
    val due = armed.cadence ?: when {
        left >= 60 -> "next run in ${left / 60}m ${left % 60}s"
        left > 0 -> "next run in ${left}s"
        else -> "running now"
    }
    val shortDue = armed.cadence ?: when {
        left >= 60 -> "${left / 60}m${left % 60}s"
        left > 0 -> "${left}s"
        else -> "now"
    }
    // Collapse-to-the-right on tapping the arrows. Collapsed = a compact
    // right-aligned pill: arrows (tap to expand back) + countdown + stop.
    // The width animates, so the strip visually slides shut toward the right
    // edge.
    var collapsed by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            modifier = Modifier
                .then(if (collapsed) Modifier else Modifier.fillMaxWidth())
                .animateContentSize()
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.08f))
                .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Autorenew,
                contentDescription = if (collapsed) "expand loop strip" else "collapse loop strip",
                tint = accent,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { collapsed = !collapsed }
                    .padding(4.dp),
            )
            if (collapsed) {
                Text(
                    shortDue,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else {
                Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(
                        "Loop running · $due",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    armed.reason?.let { why ->
                        Text(
                            why,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            androidx.compose.material3.TextButton(onClick = onStop) {
                Text("stop", style = MaterialTheme.typography.labelMedium, color = accent)
            }
        }
    }
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
