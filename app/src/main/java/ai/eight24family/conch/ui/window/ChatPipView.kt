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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import ai.eight24family.conch.util.SilentlyTry

/**
 * Compact view rendered when the host Activity is in Picture-in-Picture mode.
 * `ChatScreen` stops composing and MainActivity.Root draws this over the live
 * NavHost.
 *
 * ## What this window is for
 *
 * ONE question: **is it working, and how far along is it?** That is the only
 * reason to keep a 200dp window on screen instead of just leaving the app.
 *
 * The previous version answered it badly enough to be useless. Three separate
 * causes, all fixed here or at the call site
 *
 *  1. **It opened when nothing was running.** The PiP gate counted session
 *     objects, not turns — see `MainActivity.onUserLeaveHint`. A window about
 *     nothing can only show noise.
 *  2. **It restored the READING ANCHOR.** The full chat rightly reopens where
 *     you left off; a status window doing that shows a reply from twenty
 *     minutes ago while the agent works below the fold. PiP now always follows
 *     the live tail, and content from a finished turn is LABELLED as such
 *     instead of impersonating live output.
 *  3. **"Working" was only our own `SessionState.Working`.** A mirrored turn
 *     (driven from the console) left the dot grey while the agent was clearly
 *     working, and there was no verb, no elapsed, no tokens, no agents, no
 *     queue — nothing but a dot and old prose.
 *
 * Everything shown is the SAME source the full chat uses (`WorkingStatusRow`,
 * the subagent roster, the outbox) — the window is a small window onto the real
 * state, never a second guess at it.
 *
 * There are deliberately NO controls, here or in the system header: touch in a
 * PiP window is one tap that the system spends on expanding, and a destructive
 * control on that tap is how a turn used to die by accident.
 */
@Composable
fun ChatPipView(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val messages by vm.messages.collectAsState()
    val state by vm.state.collectAsState()
    val remoteWorking by vm.remoteFileOpen.collectAsState()
    val agent by vm.currentAgent.collectAsState()
    val model by vm.observedModel.collectAsState()
    val roster by vm.subagents.collectAsState()
    val queued by vm.queuedMessages.collectAsState()
    val server by vm.server.collectAsState()

    val isWorking = state is SessionState.Working || remoteWorking
    val failed = state as? SessionState.Failed

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(6.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Identity: which chat this window is even about ──
            // The old design argued a header "shouts metadata back at the user
            // who tapped in 30 seconds ago". That held while PiP followed the
            // foreground chat and nothing else; the moment it can also show a
            // BACKGROUND turn (the only case worth floating), "which chat is
            // this" stops being obvious and starts being the first thing you
            // need. One 10sp line pays for itself.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                failed != null -> MaterialTheme.colorScheme.error
                                isWorking -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline
                            },
                        ),
                )
                Text(
                    server?.name ?: vm.cachedServerName ?: "Conch",
                    style = MaterialTheme.typography.labelSmall,
                    // Name key carries the accent while `server` is still loading —
                    // the cached name is available before the row is.
                    color = ai.eight24family.conch.ui.theme.serverNameColor(
                        serverId = server?.id,
                        serverName = server?.name ?: vm.cachedServerName,
                        fallback = MaterialTheme.colorScheme.primary,
                    ),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                model?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "· ${shortModel(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }

            // ── Status: the answer to the only question ──
            if (isWorking) {
                // The chat's OWN working row, verbatim: real verb, real elapsed
                // (synced to the turn's start, not to when PiP opened), real
                // token count, real "waiting for your answer on the server".
                // Reused rather than reimplemented so the floating window can
                // never disagree with the full screen about what is happening.
                val liveTokens by vm.liveThinkingTokens.collectAsState()
                val remoteTokens by vm.remoteTokens.collectAsState()
                val remoteTurnStart by vm.remoteTurnStartMs.collectAsState()
                val remoteThinking by vm.remoteThinking.collectAsState()
                val remoteWaiting by vm.remoteWaitingForInput.collectAsState()
                val effort by vm.activeReasoningEffort.collectAsState()
                var localStartMs by remember { mutableStateOf(0L) }
                LaunchedEffect(isWorking) { if (isWorking) localStartMs = System.currentTimeMillis() }
                ai.eight24family.conch.ui.screens.WorkingStatusRow(
                    startMs = remoteTurnStart ?: vm.sessionTurnStartMs().takeIf { it > 0L } ?: localStartMs,
                    thinkingTokens = (liveTokens ?: 0L).takeIf { it > 0L } ?: remoteTokens.takeIf { it > 0L },
                    effort = effort,
                    thinking = remoteThinking,
                    waitingForInput = remoteWaiting,
                    agent = agent,
                )
            } else {
                Text(
                    when {
                        failed != null -> "✕ ${failed.reason.take(60)}"
                        state is SessionState.Bootstrapping -> "connecting…"
                        // Not working and there IS output → the turn finished
                        // while the window was up. Say so; the tail below is a
                        // result, not progress.
                        messages.any { it is AgentMessage.AssistantText } -> "✓ done"
                        else -> "idle"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }

            // ── What else is in flight ──
            // A fan-out and a queue are both things the user cannot see from
            // anywhere else while backgrounded, and both change what "still
            // working" means. One line, only when non-zero.
            val runningAgents = roster.count { !it.done }
            val extra = listOfNotNull(
                runningAgents.takeIf { it > 0 }?.let { "$it agent${if (it == 1) "" else "s"}" },
                queued.size.takeIf { it > 0 }?.let { "$it queued" },
            )
            if (extra.isNotEmpty()) {
                Text(
                    extra.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }

            // ── The live tail ──
            val tail = remember(messages, isWorking) { pipTail(messages, isWorking) }
            if (tail.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isWorking) "no output yet" else "tap to open",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                // ALWAYS follow the newest line. This window is a progress
                // readout; there is no reading position to preserve in it (the
                // full chat keeps that, and restoring it HERE is what made the
                // window show old replies).
                LaunchedEffect(tail.size) {
                    SilentlyTry.fired("SshAi-PipView", "follow pip tail") {
                        listState.animateScrollToItem((tail.size - 1).coerceAtLeast(0))
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(tail.size, key = { tail[it].key }) { idx ->
                        val line = tail[idx]
                        Text(
                            line.text,
                            color = if (line.dim) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
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

/** One rendered line of the PiP tail. */
internal data class PipLine(val key: String, val text: String, val dim: Boolean)

/**
 * The lines worth showing in a 200dp window, oldest → newest.
 *
 * While a turn runs this is THIS TURN only — everything after the last user
 * prompt — so the window shows work in progress and nothing else. Tool calls
 * are collapsed to their name (`⚙ Grep`): in a window this size the arguments
 * are noise, but "it is currently running Grep" is exactly the progress signal
 * the user is looking for.
 *
 * When no turn is running there is nothing in flight to show, so it falls back
 * to the last assistant reply, explicitly labelled — the previous version showed
 * that same prose with no label and it read as live output from a session the
 * user had not touched in an hour.
 *
 * Pure so the rule is testable (PipTailTest).
 */
internal fun pipTail(messages: List<AgentMessage>, isWorking: Boolean): List<PipLine> {
    if (messages.isEmpty()) return emptyList()
    val lastUser = messages.indexOfLast { it is AgentMessage.UserText }
    val scope = if (isWorking && lastUser >= 0) messages.drop(lastUser + 1) else messages
    val out = ArrayList<PipLine>()
    for (m in scope) {
        when (m) {
            is AgentMessage.AssistantText ->
                m.text.trim().takeIf { it.isNotBlank() }?.let { out += PipLine(m.id, it, dim = false) }
            is AgentMessage.ToolUse ->
                out += PipLine(m.id, "⚙ ${m.toolName}", dim = true)
            is AgentMessage.Error ->
                m.text.trim().takeIf { it.isNotBlank() }?.let { out += PipLine(m.id, "! $it", dim = false) }
            else -> Unit
        }
    }
    if (out.isEmpty()) return emptyList()
    if (isWorking) {
        // Cap the in-flight tail: a long turn can produce hundreds of rows and
        // only the last few fit anyway. Keeping the list short also keeps the
        // auto-scroll instant.
        return out.takeLast(PIP_TAIL_MAX)
    }
    // Idle: the last reply, and say that it IS the last reply.
    val last = out.lastOrNull { !it.dim } ?: out.last()
    return listOf(PipLine("pip-last-label", "last reply", dim = true), last)
}

private const val PIP_TAIL_MAX = 12

/** `claude-sonnet-5` → `sonnet`; an already-short alias passes through. */
private fun shortModel(model: String): String =
    model.removePrefix("claude-").split('-').firstOrNull { it.isNotBlank() } ?: model
