package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.PermissionDecision
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * In-chat hits list — shown above the messages list when the user has
 * typed a query into the topbar's search box. Each row is a snippet that
 * scrolls back to the matched message on tap.
 */
@Composable
internal fun InChatHitsList(
    hits: List<InChatHit>,
    onPickHit: (InChatHit) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items(hits, key = { it.msgId + "#" + it.matchStartInSnippet }) { h ->
            InChatHitRow(hit = h, onTap = { onPickHit(h) })
        }
    }
}

/**
 * The actual chat-message LazyColumn. Anchored by stable msgId via the
 * scroll controller's first-scroll pipeline; [highlightSpec] and
 * [matchAnchor] both derive from `vm.initialMatchMsgId`, so the
 * highlighted item and the centred line are guaranteed to refer to the
 * same message — no ordinal misalignment possible. Per-message
 * SelectionContainer (Telegram pattern) replaces a cross-list one.
 *
 * Alpha-hidden until [anchorApplied] flips true so the user never sees
 * the intermediate "list at top → jump to target" two-step.
 */
@Composable
internal fun ChatMessageList(
    messages: List<AgentMessage>,
    lazyListState: LazyListState,
    anchorApplied: Boolean,
    highlightSpec: SearchHighlightSpec?,
    matchAnchor: MatchAnchor?,
    state: SessionState,
    remoteWorking: Boolean,
    vm: ChatViewModel,
) {
    // The working-status row is NOT a list item anymore — it's PINNED above the
    // prompt bar (see PinnedWorkingStatus in ChatScreen) so it never scrolls away
    // (user, 2026-06-14). isWorking here only marks the streaming bubble.
    val isWorking = state is SessionState.Working || remoteWorking
    CompositionLocalProvider(
        LocalSearchHighlight provides highlightSpec,
        LocalMatchAnchor provides matchAnchor,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (anchorApplied) 1f else 0f),
            state = lazyListState,
            contentPadding = PaddingValues(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // Only the line being actively streamed (last assistant message
            // while the agent works) keeps the debounced off-thread markdown
            // parse. Every other (static) line parses synchronously + cached,
            // so it renders formatted on its first frame — no raw-markdown
            // reflow / jitter when the session opens. See AssistantLine.
            val streamingId = if (isWorking)
                (messages.lastOrNull() as? AgentMessage.AssistantText)?.id else null
            items(messages, key = { it.id }) { msg ->
                val onAllow: () -> Unit = {
                    (msg as? AgentMessage.PermissionRequest)?.let {
                        vm.respondPermission(it.id, it.requestId, PermissionDecision.ALLOW_ONCE)
                    }
                }
                val onDeny: () -> Unit = {
                    (msg as? AgentMessage.PermissionRequest)?.let {
                        vm.respondPermission(it.id, it.requestId, PermissionDecision.DENY)
                    }
                }
                val onAllowSession: () -> Unit = {
                    (msg as? AgentMessage.PermissionRequest)?.let {
                        vm.respondPermission(it.id, it.requestId, PermissionDecision.ALLOW_SESSION)
                    }
                }
                val onAnswerQuestion: (Map<Int, List<String>>) -> Unit = { answers ->
                    (msg as? AgentMessage.AskUserQuestion)?.let {
                        vm.respondQuestion(it.requestId, answers)
                    }
                }
                CompositionLocalProvider(LocalCurrentMsgId provides msg.id) {
                    SelectionContainer {
                        TerminalLine(
                            msg, onAllow, onDeny,
                            isStreaming = msg.id == streamingId,
                            onAnswerQuestion = onAnswerQuestion,
                            onLoadEarlier = { vm.loadFullHistory() },
                            onAllowSession = onAllowSession,
                        )
                    }
                }
            }
            // (Working-status row moved OUT of the list — it's pinned above the
            // prompt bar now, so it holds its place instead of scrolling away.)
        }
    }
}

/**
 * Floating "↓ scroll to bottom" button anchored to the bottom-end of the
 * messages list. Shows ONLY when the last visible item is 4+ positions
 * above the end (see INVARIANT #5) so it doesn't dance at every minor
 * manual scroll.
 */
@Composable
internal fun ScrollToBottomButton(
    lazyListState: LazyListState,
    messages: List<AgentMessage>,
    modifier: Modifier = Modifier,
) {
    val showScrollToBottom by remember {
        derivedStateOf {
            val info = lazyListState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val total = info.totalItemsCount
            if (total <= 0 || last == null) {
                false
            } else {
                // Item-index hysteresis (2+ items above the end, no dancing
                // on minor scrolls) OR pixel distance: the bottom of the
                // lowest visible item is more than one viewport below the
                // screen. The second arm covers chats whose tail reply is
                // several screens tall — one reply = ONE item, so the index
                // check alone never fired inside it and the button
                // "disappeared" (2026-06-10).
                val viewportH = info.viewportEndOffset - info.viewportStartOffset
                last.index < total - 2 ||
                    (last.offset + last.size - info.viewportEndOffset) > viewportH
            }
        }
    }
    val scrollScope = rememberCoroutineScope()
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val primary = MaterialTheme.colorScheme.primary
    if (showScrollToBottom) {
        Box(
            modifier = modifier
                .padding(end = 14.dp, bottom = 14.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(surfaceColor)
                .border(1.dp, borderColor, CircleShape)
                .clickable {
                    scrollScope.launch {
                        // True bottom (last item's BOTTOM at the viewport bottom),
                        // NOT scrollToItem(size-1) which only puts the last item's
                        // TOP at the viewport top: that left a gap below a short
                        // last message AND landed close enough that the visibility
                        // gate hid the button before the end was reached.
                        // scrollToBottom overshoots + Compose clamps to the real
                        // max — same helper every other go-to-bottom path uses.
                        lazyListState.scrollToBottom(messages.size)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll to bottom",
                tint = primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
