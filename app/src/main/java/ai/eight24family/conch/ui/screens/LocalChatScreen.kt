package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.LocalLlmEngine
import ai.eight24family.conch.ui.viewmodel.CostStats
import ai.eight24family.conch.ui.viewmodel.LocalChatViewModel
import ai.eight24family.conch.ui.viewmodel.UsageBarState
import ai.eight24family.conch.ui.window.handCursor
import ai.eight24family.conch.util.Bitmaps

/**
 * Chat with the model that is ON this phone — the short path.
 *
 * ⛔ WHAT THIS SCREEN IS FOR: a downloaded model, a keyboard, an answer. No
 * Linux environment, no phone bridge, no developer options, no CLI install,
 * no account. Everything the CLI chat needs from a server — sessions,
 * approvals, tools, a cwd — is absent here BY DESIGN; `[ agent ]` in the top
 * bar goes to that path, which is the honest place for it.
 *
 * Nothing is re-drawn from scratch: the rows are the CLI chat's own
 * [UserLine] / [AssistantLine] / [EventLine], and the composer is its
 * [PromptBar] with `localTelemetry` on — so a local answer looks exactly like
 * an agent's answer, and the bar above the input shows what a local turn
 * really costs (cpu / ram / heat) where a cloud chat would show a quota.
 */
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LocalChatScreen(
    onBack: () -> Unit,
    /** The same model, driven by the real CLI: tools, shell, sessions. */
    onOpenAgent: (modelId: String) -> Unit,
    vm: LocalChatViewModel = viewModel(),
) {
    val messages by vm.messages.collectAsState()
    val working by vm.working.collectAsState()
    val input by vm.input.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val engine by vm.engineState.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val tokPerSec by vm.tokPerSec.collectAsState()
    val enterSends by vm.enterSends.collectAsState()
    val voice by vm.voiceState.collectAsState()
    // ⛔ THE MIC LIVES IN THE TOP BAR, NOT THE COMPOSER. A permanent mic seat
    // in the prompt bar was removed on purpose in August ("starting a
    // recording is a deliberate act"), and dictation is a different act from
    // attaching a voice message anyway: it produces WORDS in the composer,
    // which the owner then edits before sending.
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.micTap() }
    val model = vm.model
    val label = model?.label ?: vm.modelId
    val serving = (engine as? LocalLlmEngine.State.Up)?.modelId == vm.modelId

    val ctxForMic = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    // Follow the stream, but never yank the screen away from someone reading
    // back: only when the view is already at the end. `lastIndex + text
    // length` as the key so a growing answer keeps it pinned too.
    val tail = messages.lastOrNull()?.msg
    val tailLen = (tail as? AgentMessage.AssistantText)?.text?.length ?: 0
    LaunchedEffect(messages.size, tailLen) {
        if (messages.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val atEnd = visible.isEmpty() ||
            visible.last().index >= listState.layoutInfo.totalItemsCount - 2
        if (atEnd) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // State, not advice: what the engine IS doing, the
                        // window it really got, and the measured speed of the
                        // last answer — the three numbers a local turn has
                        // instead of a plan and a quota.
                        Text(
                            buildString {
                                append(
                                    when {
                                        engine is LocalLlmEngine.State.Starting -> "loading…"
                                        serving && (engine as LocalLlmEngine.State.Up).gpu -> "gpu ● serving"
                                        serving -> "cpu ● serving"
                                        engine is LocalLlmEngine.State.Failed -> "engine failed"
                                        else -> "idle"
                                    },
                                )
                                if (serving) {
                                    append(" · ")
                                    append(vm.ctxTokens / 1024)
                                    append("K")
                                }
                                tokPerSec?.let {
                                    append(" · ")
                                    append(String.format(java.util.Locale.US, "%.1f", it))
                                    append(" tok/s")
                                }
                            },
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    // Reasoning is engine LAUNCH state — flipping it restarts
                    // the server, so it is a deliberate tap and it says which
                    // way it is set, never a silent default.
                    BarAction(
                        if (thinking) "[ thinking ]" else "[ think ]",
                        if (thinking) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    ) { vm.setThinking(!thinking) }
                    BarAction(
                        when (val v = voice) {
                            is ai.eight24family.conch.linux.voice.LocalVoice.State.Recording ->
                                "[ ■ %d:%02d ]".format(v.seconds / 60, v.seconds % 60)
                            ai.eight24family.conch.linux.voice.LocalVoice.State.Transcribing ->
                                "[ … ]"
                            ai.eight24family.conch.linux.voice.LocalVoice.State.Idle -> "[ dictate ]"
                        },
                        when (voice) {
                            is ai.eight24family.conch.linux.voice.LocalVoice.State.Recording ->
                                MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outline
                        },
                    ) {
                        if (voice is ai.eight24family.conch.linux.voice.LocalVoice.State.Transcribing) {
                            return@BarAction
                        }
                        if (ai.eight24family.conch.linux.voice.VoiceCapture.micGranted(ctxForMic)) {
                            vm.micTap()
                        } else {
                            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    BarAction("[ agent ]", MaterialTheme.colorScheme.primary) {
                        onOpenAgent(vm.modelId)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        EmptyChatGreeting(
                            agentName = label,
                            host = "this phone",
                            ready = serving,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        items(messages, key = { it.msg.id }) { row ->
                            SelectionContainer {
                                LocalChatRow(
                                    msg = row.msg,
                                    images = row.images,
                                    isStreaming = row.msg.id == "live" && working,
                                )
                            }
                        }
                    }
                }
            }
            PromptBar(
                input = input,
                onInputChange = vm::setInput,
                canSend = !working &&
                    (input.isNotBlank() || attachments.any { it.isImage }),
                working = working,
                usage = UsageBarState.EMPTY,
                usageReport = null,
                usageCost = CostStats(),
                usageExpanded = false,
                onUsageExpandedChange = {},
                // A local model has no quota — its cost is this phone. The
                // bar shows the engine's live cpu / ram / heat instead.
                localTelemetry = true,
                uploading = false,
                statusHint = model?.let { m ->
                    if (LocalLlm.hasVision(m) || m.mmprojUrl == null) null
                    else "add the vision pack on the model's page to send pictures"
                },
                enterSends = enterSends,
                attachments = attachments,
                canAttachMore = attachments.size < 4,
                onAddAttachment = { bytes, name, mime -> vm.addAttachment(bytes, name, mime) },
                onRemoveAttachment = vm::removeAttachment,
                onConnectPhone = {},
                onStop = vm::stop,
                onSend = vm::send,
            )
        }
    }
}

/** Bracketed top-bar verb — the same voice as the model rows' `[ end ]`. */
@Composable
private fun BarAction(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .handCursor()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/**
 * One row. The CLI chat's [TerminalLine] is not reused here on purpose: its
 * user branch reaches for a [ai.eight24family.conch.ui.viewmodel.ChatViewModel]
 * (rewind, remote image download), and constructing one of those for a chat
 * that has no server would be a lie with a side effect. The pieces that hold
 * the LOOK — the bubbles, the markdown, the code blocks, the foldaway note —
 * are the same functions.
 */
@Composable
private fun LocalChatRow(msg: AgentMessage, images: List<String>, isStreaming: Boolean) {
    when (msg) {
        is AgentMessage.UserText -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            if (msg.text.isNotBlank()) UserLine(msg.text)
            images.forEach { path -> LocalPhoto(path) }
        }
        is AgentMessage.AssistantText -> AssistantLine(msg.text, isStreaming = isStreaming)
        is AgentMessage.EventNote -> EventLine(
            label = msg.label.take(140),
            details = msg.detail,
            color = when (msg.tone) {
                AgentMessage.EventNote.Tone.WARN -> MaterialTheme.colorScheme.error
                AgentMessage.EventNote.Tone.INFO -> MaterialTheme.colorScheme.tertiary
                AgentMessage.EventNote.Tone.DIM -> MaterialTheme.colorScheme.outline
            },
        )
        is AgentMessage.Error -> Text(
            "! ${msg.text}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 3.dp),
        )
        else -> Unit
    }
}

/** A picture the user showed the model, decoded off this phone's own disk —
 *  downsampled, because a full decode of a camera shot is ~48 MB of bitmap. */
@Composable
private fun LocalPhoto(path: String) {
    val bmp = remember(path) { Bitmaps.decodeSampledFile(path, 720, lowColor = true) }
        ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(200.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}
