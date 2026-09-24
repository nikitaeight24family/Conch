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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
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
    val coldMaybe by vm.coldCacheMaybe.collectAsState()
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
        // Codex can take a queued message INTO the running turn (`turn/steer`),
        // the way Enter does mid-turn in its own TUI. Offered, never automatic:
        // the queue stays the default, so a mid-turn message is still visible
        // and cancelable until the owner chooses otherwise.
        val canSteer by vm.canSteerQueued.collectAsState()
        QueuedMessagesStrip(
            queued = queuedMessages,
            onCancel = { vm.cancelQueued(it) },
            onSteer = if (canSteer) ({ id: String -> vm.steerQueued(id) }) else null,
        )
    }
    // The CLI's own guess at the next prompt (Claude `prompt_suggestion`).
    // Only on an idle, empty composer: it answers the turn that just ended,
    // and must never sit on top of words the owner is typing. A tap FILLS the
    // composer — sending stays a separate, deliberate tap.
    val suggestion by vm.promptSuggestion.collectAsState()
    val suggestionShown = suggestion?.takeIf {
        input.isBlank() && state !is SessionState.Working && !remoteWorking && queuedMessages.isEmpty()
    }
    if (suggestionShown != null) {
        PromptSuggestionChip(
            text = suggestionShown,
            onUse = {
                onInputChange(suggestionShown)
                vm.dismissPromptSuggestion()
            },
            onDismiss = { vm.dismissPromptSuggestion() },
        )
    }
    // `/btw` answer — outside the conversation, so a sheet and not a row.
    val sideAnswer by vm.sideAnswer.collectAsState()
    sideAnswer?.let { sa ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissSideAnswer() },
            title = {
                Text(
                    "btw · " + sa.question,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                if (sa.pending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("asking — not added to the chat", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            sa.answer.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.dismissSideAnswer() }) { Text("Close") }
            },
        )
    }
    // Work a usage limit cut short, and when it will carry on by itself.
    val autoResume by vm.autoResume.collectAsState()
    val autoResumeIn by vm.autoResumeResetIn.collectAsState()
    autoResume?.let {
        AutoResumeStrip(armed = it, resetIn = autoResumeIn, onToggle = { vm.toggleAutoResume() })
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
        // A local-model harness on the phone's own row runs the LOCAL engine:
        // no quota to show — the bar shows the hardware the inference is spending.
        localTelemetry = serverId == ai.eight24family.conch.linux.LinuxSsh.SERVER_ID &&
            ai.eight24family.conch.agent.spec.AgentSpecRegistry[currentAgent].supportsLocalModel,
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
                coldMaybe = coldMaybe,
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
/**
 * Auto-continue after a usage limit — CONCH'S OWN CHROME, not a chat row.
 *
 * ⛔ IT WAS AN EventNote IN THE TRANSCRIPT AND THAT WAS THE BUG. Rendered among
 * the agent's rows it read as something the agent had said — and a line the app
 * writes about its own future behaviour must never be mistakable for the
 * session's content.
 *
 * So it lives where the app's other self-statements live: beside [LoopStrip] and
 * [QueuedMessagesStrip], above the composer. Deliberately NOT identical to the
 * queue strip — that one is about text you typed and can still take back, this
 * one is about the app acting on its own later. Same family, different member:
 * the tertiary accent rather than the primary, a squarer corner, and a leading
 * rule down its left edge.
 *
 * The action is a WORD, not an icon. "Cancel" on an ✕ is fine for a message you
 * queued a second ago; switching off an automatic action deserves to say what
 * it does, and to be as easy to switch back on.
 */
@Composable
private fun AutoResumeStrip(
    armed: ai.eight24family.conch.data.prefs.AppPreferences.AutoResume,
    resetIn: String,
    onToggle: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    val on = armed.enabled
    val tint = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = if (on) 0.10f else 0.05f))
            .border(1.dp, tint.copy(alpha = if (on) 0.35f else 0.18f), RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The left rule is the whole visual difference at a glance: the queue
        // strip has none, so the two never read as the same thing.
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .background(tint.copy(alpha = if (on) 0.8f else 0.35f)),
        )
        Icon(
            Icons.Filled.Autorenew,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(start = 8.dp).size(15.dp),
        )
        Text(
            text = when {
                on && resetIn.isNotBlank() -> "Continuing in $resetIn, when the limit resets"
                on -> "Continuing when the limit resets"
                resetIn.isNotBlank() -> "Auto-continue off · limit resets in $resetIn"
                else -> "Auto-continue off"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp, top = 7.dp, bottom = 7.dp),
        )
        androidx.compose.material3.TextButton(
            onClick = onToggle,
            modifier = Modifier.padding(end = 2.dp),
        ) {
            Text(
                if (on) "Cancel" else "Continue",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
        }
    }
}

@Composable
private fun QueuedMessagesStrip(
    queued: List<ChatViewModel.QueuedMessage>,
    onCancel: (String) -> Unit,
    /** Non-null when the running turn can take a message now (Codex steer). */
    onSteer: ((String) -> Unit)? = null,
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
                            // 24 dp on screen — decode at most 96 px in RGB_565,
                            // not the full multi-megapixel image (bitmap-memory
                            // vital; the full decode was ~500× the shown pixels).
                            ai.eight24family.conch.util.Bitmaps
                                .decodeSampled(bytes, maxDim = 96, lowColor = true)
                                ?.asImageBitmap()
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
                if (onSteer != null) {
                    androidx.compose.material3.TextButton(
                        onClick = { onSteer(q.id) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("Now", style = MaterialTheme.typography.labelMedium, color = accent)
                    }
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


/** One-tap offer of the CLI's predicted next prompt. Tap = into the composer. */
@Composable
private fun PromptSuggestionChip(
    text: String,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .clickable(onClick = onUse)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("↳", style = MaterialTheme.typography.labelLarge, color = accent)
        Text(
            text.replace('\n', ' '),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 7.dp),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss suggestion",
                tint = accent.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
