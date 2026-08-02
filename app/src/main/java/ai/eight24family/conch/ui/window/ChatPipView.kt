package ai.eight24family.conch.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.util.SilentlyTry

/**
 * Compact view rendered when the host Activity is in Picture-in-Picture
 * mode. ChatScreen short-circuits to this when
 * [AppWindowAdaptive.isInPip] is true.
 *
 * Constraints:
 *  - PiP windows are typically 240-360dp wide. No room for chrome.
 *  - Touch is limited to a single "expand" tap delivered by the system.
 *    No interactive elements inside the window.
 *  - User cares about one thing: is the agent making progress? So we
 *    show last few assistant lines + a tiny working/done indicator.
 *
 * Auto-scroll always sticks the view to the bottom — the assistant's
 * latest tokens are what the user came back to read.
 *
 * Tool calls / system events are stripped from the PiP feed (too much
 * noise in a tiny window). Only `AgentMessage.AssistantText` survives.
 */
@Composable
fun ChatPipView(
    messages: List<AgentMessage>,
    isWorking: Boolean,
    /** Message the user was parked on in the full chat when they minimized; the
     *  PiP scrolls its assistant-only feed to that point instead of jerking to
     *  the latest reply. null = they were at the bottom → follow the latest. */
    anchorMsgId: String? = null,
    modifier: Modifier = Modifier,
) {
    val assistant = messages.filterIsInstance<AgentMessage.AssistantText>()
    val assistantLines = assistant.map { it.text }

    // Map the reading anchor (any message id in the FULL chat) to an index in
    // the assistant-only feed: the last assistant reply at-or-before where the
    // user was reading. null anchor (was at bottom) → the latest reply.
    val targetIndex = run {
        if (assistant.isEmpty()) return@run 0
        if (anchorMsgId == null) return@run assistant.lastIndex
        var seenAssistant = -1
        var found = -2 // -2 = anchor id not seen at all
        for (m in messages) {
            if (m is AgentMessage.AssistantText) seenAssistant++
            if (m.id == anchorMsgId) { found = seenAssistant; break }
        }
        when {
            found == -2 -> assistant.lastIndex          // anchor not in list → latest
            found < 0 -> 0                               // anchor sits before any reply
            else -> found.coerceIn(0, assistant.lastIndex)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(assistantLines.size, targetIndex) {
        if (assistantLines.isNotEmpty()) {
            SilentlyTry.fired("SshAi-PipView", "scroll pip to anchor/latest") {
                listState.animateScrollToItem(targetIndex.coerceIn(0, assistantLines.lastIndex))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(6.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tiny status pill: cyan dot + "Conch" when working,
            // muted dot + "Conch" when idle. Doubles as the only
            // visible identity for the floating window.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val dotColor = if (isWorking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    "Conch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
                if (isWorking) {
                    Text(
                        "· working",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
                }
            }

            if (assistantLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "(no output yet)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(assistantLines.size) { idx ->
                        Text(
                            assistantLines[idx],
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
