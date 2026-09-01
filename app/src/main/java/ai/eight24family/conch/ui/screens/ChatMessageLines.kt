package ai.eight24family.conch.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import ai.eight24family.conch.ui.haptic.LocalConchHaptics
import ai.eight24family.conch.ui.haptic.ConchHaptic
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import ai.eight24family.conch.ui.keyboard.shortcuts
import ai.eight24family.conch.ui.window.handCursor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.RemoteSession
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.SlashCommands
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.agent.spec.ModelMenuItem
import ai.eight24family.conch.agent.spec.TopbarModelState
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.ui.components.CopyableCodeBlock
import ai.eight24family.conch.ui.viewmodel.ChatModal
import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import ai.eight24family.conch.util.PathDetector
import ai.eight24family.conch.ui.viewmodel.MemoryDocs
import ai.eight24family.conch.ui.viewmodel.MemoryScope
import ai.eight24family.conch.ui.viewmodel.StagedAttachment
import ai.eight24family.conch.ui.viewmodel.UploadStatus
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────── Message rendering ─────────────────────────

// Parsed-markdown cache, keyed by (text, code colours). The off-thread
// `produceState` in TerminalLine seeds its `initialValue` from this, so a
// message that was ALREADY parsed once re-enters composition — LazyColumn
// recycling on scroll, or the usage-panel details collapsing and the chat
// growing back — showing its RENDERED form instantly. Without it, every
// re-entry resets to raw text + a 120ms debounce, flashing literal
// "*markdown*" asterisks for a frame. Genuinely-new text (streaming, first
// load) still misses the cache and goes through the debounced off-thread
// parse, so the heat/battery win from that path stands.
private data class MdCacheKey(val text: String, val codeBg: ULong, val codeFg: ULong)
private val markdownCache = android.util.LruCache<MdCacheKey, AnnotatedString>(128).also {
    // Each key holds the full message text and each value its parsed
    // AnnotatedString — 128 long messages is real heap. Rebuildable from the
    // message list at any time, so it registers for memory-pressure trimming
    // (the app is off-screen when that fires; the re-parse is invisible).
    ai.eight24family.conch.util.MemoryPressure.register("markdownCache") { it.evictAll() }
}

@Composable
internal fun TerminalLine(
    msg: AgentMessage,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    isStreaming: Boolean = false,
    /** Commits the user's picks for an [AgentMessage.AskUserQuestion]
     *  card (question index → chosen option labels). Defaulted so
     *  read-only surfaces (PiP, history replay) need no wiring. */
    onAnswerQuestion: (Map<Int, List<String>>) -> Unit = {},
    /** Tap on the "earlier history hidden" marker → load the full session. */
    onLoadEarlier: () -> Unit = {},
    /** "Always allow" on a permission card — only wired where the agent
     *  supports a session-scoped grant (Codex/Gemini). */
    onAllowSession: () -> Unit = {},
) {
    when (msg) {
        // Subagent activity is panel data, not a transcript row. Rendering it
        // here would put a fan-out of eight agents' turns into the middle of the
        // conversation — exactly what the CLI filters out of its own transcript.
        is AgentMessage.SubagentActivity -> Unit
        // The CLI's background-task set. Feeds the agent panel's count; the
        // display list filters it out before we ever get here.
        is AgentMessage.BackgroundTasks -> Unit
        // Feeds the slash autocomplete; never a transcript row.
        is AgentMessage.CommandsChanged -> Unit
        // Turn-completion signal. The reader drops it before history, so this
        // branch should be unreachable — it exists so the compiler keeps
        // guarding the rest of this `when`.
        is AgentMessage.TurnEnd -> Unit
        is AgentMessage.AskUserQuestion -> QuestionCard(msg, onAnswerQuestion)
        is AgentMessage.EventNote -> EventLine(
            label = msg.label.take(140),
            details = msg.detail,
            color = when (msg.tone) {
                AgentMessage.EventNote.Tone.WARN -> MaterialTheme.colorScheme.error
                AgentMessage.EventNote.Tone.INFO -> MaterialTheme.colorScheme.tertiary
                AgentMessage.EventNote.Tone.DIM -> MaterialTheme.colorScheme.outline
            },
            // The history-window marker is tappable — load the full session.
            onClick = if (msg.id == ai.eight24family.conch.ui.viewmodel.ChatViewModel.HISTORY_WINDOW_MARKER_ID)
                onLoadEarlier else null,
        )
        is AgentMessage.UserText -> {
            // Chat exchanges image PATHS, not bytes. Show the picture inline,
            // never the path / "Attached image at:" text.
            val vm: ChatViewModel = viewModel()
            val (clean, imgs) = remember(msg.text) { extractImages(msg.text) }
            // ⚠ LONG-PRESS BELONGS TO THE TEXT, NOT TO US. Every row is wrapped
            // in a SelectionContainer, so a long press is how the user selects
            // and copies — and for a while this branch stole that gesture to
            // open the rewind sheet, which both removed copying and put a
            // DESTRUCTIVE action on a habit (user, 2026-08-02). Rewind now has
            // its own quiet handle beside the bubble: one deliberate tap.
            val onRewind = { vm.openRewind(msg.recordUuid, clean.ifBlank { msg.text }) }
            if (imgs.isEmpty()) {
                RewindableUserRow(onRewind) { UserLine(msg.text) }
            } else {
                // User messages live on the RIGHT — right-align the images too
                // (they were left-aligned like the agent's, which looked wrong).
                RewindableUserRow(onRewind) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                    ) {
                        // Image on top, the caption the user typed UNDER it.
                        imgs.forEach { InlineRemoteImage(it, vm) }
                        if (clean.isNotBlank()) UserLine(clean)
                    }
                }
            }
        }
        is AgentMessage.AssistantText -> {
            val vm: ChatViewModel = viewModel()
            val (clean, imgs) = remember(msg.text) { extractImages(msg.text) }
            if (imgs.isEmpty()) {
                AssistantLine(msg.text, vm = vm, isStreaming = isStreaming)
            } else {
                Column {
                    if (clean.isNotBlank()) AssistantLine(clean, vm = vm, isStreaming = isStreaming)
                    imgs.forEach { InlineRemoteImage(it, vm) }
                }
            }
        }
        is AgentMessage.PermissionRequest -> PermissionLine(msg, onAllow, onDeny, onAllowSession)
        is AgentMessage.ToolUse -> ToolUseLine(msg.toolName, msg.input)
        is AgentMessage.ToolResult -> ToolResultLine(msg.output, msg.isError)
        is AgentMessage.System -> when (msg.subtype) {
            // Live compaction: animated row while the CLI compacts the
            // conversation, morphing IN PLACE (same message id, upserted)
            // into the dim summary divider when compact_boundary lands.
            // Parity with the CLI's own "Compacting conversation…"
            // (user request, 2026-06-10).
            "compacting" -> CompactingRow()
            "compact_done" -> EventLine(
                label = msg.raw.take(120),
                details = null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Invisible carriers for the topbar: the session's current model
            // (OBSERVED_MODEL_ID) and current effort (OBSERVED_REASONING_ID) —
            // never chat rows.
            "model_observed" -> Unit
            "reasoning_observed" -> Unit
            "ai_title" -> Unit  // invisible carrier for the topbar title
            // Phone bridge handshake done: the agent's one-token reply is swapped
            // for this clean centered row (the scary prompt + token never show).
            "bridge_connected" -> BridgeConnectedRow()
            // Handshake in flight — clean "connecting phone… N%" row (the % ramps);
            // all the technical ping/pong/Bash steps are hidden behind it.
            "bridge_connecting" -> BridgeConnectingRow()
            // Handshake finished without confirming — quiet "couldn't connect" row
            // (no eternal spinner, no raw error dump).
            "bridge_failed" -> BridgeStatusRow("couldn't connect to phone")
            // The phone cannot run commands and the reason is worth naming. Text
            // comes from the row itself — see [ChatViewModel.bridgeUnreachable]
            // — because this used to be a JUMP to the Settings screen instead of
            // a sentence, which is a bad trade even when the screen is right, and
            // a farce when it is not (owner, 2026-08-29: he tapped Phone and
            // landed on a page reading "Ready").
            "bridge_unreachable" -> BridgeStatusRow(msg.raw)
            else -> {
                // Skip the per-message session banner Claude emits on every
                // --print invocation (subtype "init" with model/cwd/version).
                // Useful state already lives in the TopBar; an extra banner
                // per turn is noise.
                if (msg.subtype != "init" && (msg.model != null || msg.sessionId != null || msg.cwd != null)) {
                    SystemLine(msg)
                }
                // else: silently drop
            }
        }
        is AgentMessage.Result -> {
            // `result · success` after every assistant turn is just chrome.
            // Show errors only.
            if (!msg.subtype.equals("success", ignoreCase = true)) {
                EventLine(
                    label = "result · ${msg.subtype}",
                    details = msg.text,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        is AgentMessage.Error -> when (msg.kind) {
            // Prominent card for upstream-overloaded ("Service is busy"). The
            // CLI silently retries 529 for minutes; user explicitly asked for
            // claude.ai-style feedback. See
            // ClaudeMessageParser.matchesOverloaded.
            "overloaded" -> ServiceBusyCard(title = msg.text, body = msg.details)
            // Anthropic model-unavailable notice (e.g. the dead Fable 5 pin): the app
            // now handles this itself — forces the chat onto the recommended available
            // model (Opus) and the topbar shows it — so the scary banner is pure noise
            // on a chat that's actually going to run fine.
            "unavailable" -> Unit
            else -> EventLine(
                label = "! ${msg.text.take(120)}",
                details = msg.details ?: msg.text,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is AgentMessage.Raw -> {
            // Drop bookkeeping noise — `simpleEvent` in ClaudeMessageParser
            // emits `· file backup · N files`, `· turn · 45s · 631 msgs`,
            // `· system · away_summary`, `· edited · X`, etc. for every
            // session-internal event. None of it is actionable to the user;
            // it just makes the chat scroll fill up with greyed-out chrome.
            // We still render lines that DON'T start with the bookkeeping
            // `· ` prefix — that's where genuine `stderr: ...` leaks go,
            // and those ARE useful for debugging.
            if (!msg.text.startsWith("· ")) {
                EventLine(
                    label = msg.text.removePrefix("· ").take(120),
                    details = null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** True for a path that points at an image we can decode + render inline. */
internal fun isImagePath(path: String): Boolean {
    val name = path.substringAfterLast('/').substringBefore('?').substringBefore('#')
    return name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
}

/** Chat never carries image BYTES — both sides write file PATHS. Pull image
 *  paths out of a message and strip them (plus the "Attached image(s)/file(s)
 *  at:" preamble the send path injects, and the leftover bullet/label) so the
 *  chat shows the picture, not the path text. Returns (cleanedText, imagePaths). */
internal fun extractImages(raw: String): Pair<String, List<String>> {
    val imgs = PathDetector.detect(raw).map { it.path }.filter { isImagePath(it) }.distinct()
    if (imgs.isEmpty()) return raw to emptyList()
    var t = raw
    for (p in imgs) t = t.replace(p, "")
    t = t.lines().filterNot { ln ->
        val s = ln.trim()
        s.matches(Regex("(?i)attached (image|file)\\(s\\) at:")) ||
            s == "-" || s == "•" ||
            s.matches(Regex("[-•]\\s*\\(?\\s*\\)?")) ||
            s.matches(Regex("[-•]\\s*\\(.*\\)"))
    }.joinToString("\n")
    t = t.replace(Regex("\n{3,}"), "\n\n").trim()
    return t to imgs
}

/** Inline image: stream the remote file into memory + decode (in the VM),
 *  showing a spinner until ready — the path text is NEVER shown. Tap a loaded
 *  image to save / open / share it via the normal download flow. */
@Composable
internal fun InlineRemoteImage(path: String, vm: ChatViewModel) {
    val images by vm.inlineImages.collectAsState()
    val st = images[path]
    LaunchedEffect(path) { vm.loadInlineImage(path) }
    val name = path.substringAfterLast('/')
    Box(modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
        when (st) {
            is ai.eight24family.conch.ui.viewmodel.ChatViewModelDownloads.InlineImage.Ready -> Image(
                bitmap = st.bitmap,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp))
                    // Tap → full-screen viewer/annotator (zoom + draw tools),
                    // NOT "open as file". Save/share lives inside the viewer.
                    .clickable { vm.openImageViewer(path) },
            )
            is ai.eight24family.conch.ui.viewmodel.ChatViewModelDownloads.InlineImage.Failed -> Text(
                "couldn't load image ($name) — tap to retry",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.clickable { vm.loadInlineImage(path) },
            )
            else -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  $name",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * A user message row plus its rewind handle.
 *
 * The handle is a small, dim ⟲ to the LEFT of the right-aligned bubble: a
 * distinct target with a single deliberate tap, so the destructive action is
 * never something you can trigger by habit. Long-press stays with the text
 * (selection / copy / translate), which is what people actually do most.
 *
 * Rare action pays the extra pixel; frequent action pays nothing.
 */
@Composable
private fun RewindableUserRow(
    onRewind: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(
            onClick = onRewind,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                Icons.Filled.History,
                contentDescription = "Rewind the conversation to this message",
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                modifier = Modifier.size(17.dp),
            )
        }
        Box(modifier = Modifier.weight(1f, fill = false)) { content() }
    }
}

@Composable
internal fun UserLine(text: String) {
    val cyan = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    // Yellow-on-dark highlight — chosen for maximum contrast so the
    // match jumps off the screen the moment the chat lands. The earlier
    // tertiary-alpha-0.35 variant blended into our orange accent colour
    // on top of dark backgrounds and the user reported.
    val highlightBg = androidx.compose.ui.graphics.Color(0xFFFFEB3B)
    val highlightFg = androidx.compose.ui.graphics.Color(0xFF111111)
    val spec = LocalSearchHighlight.current
    val myId = LocalCurrentMsgId.current
    val active = spec?.takeIf { it.targetMsgId != null && it.targetMsgId == myId }
    val effectiveQuery = active?.query
    val rawCharOffset = active?.targetCharOffset ?: -1
    androidx.compose.runtime.LaunchedEffect(effectiveQuery) {
        if (effectiveQuery != null) {
            android.util.Log.d(
                "Conch-Hl",
                "UserLine match q=«${effectiveQuery}» rawOff=$rawCharOffset msg=${myId} text=${text.take(60)}"
            )
        }
    }
    // Memoise the annotated build + highlight overlay. The SHA-1 / SpanStyle
    // walk inside `applyHighlightOverlay` → `rawToAnnotatedPos` is O(N) on
    // text length and allocates a `List<AnnotationRange>` per char on every
    // call. Without `remember`, every recomposition of UserLine (and there
    // are MANY — every parent state change retriggers) re-ran the full
    // walk for every message in view. The output is pure function of these
    // keys; cache it.
    val annotated = remember(text, cyan, onSurface, effectiveQuery, highlightBg, highlightFg, rawCharOffset) {
        val base = buildAnnotatedString {
            // No "❯ " prompt prefix anymore — user messages are right-aligned
            // bubbles (messenger style), not terminal prompt lines. Body starts
            // at index 0, so search-highlight offsets pass straight through.
            withStyle(SpanStyle(color = onSurface)) { append(text) }
        }
        val rawForHighlight = if (rawCharOffset >= 0) text else ""
        applyHighlightOverlay(
            base, effectiveQuery, highlightBg, highlightFg,
            rawText = rawForHighlight, rawCharOffset = rawCharOffset,
        )
    }
    // Same blank-gap guard as AssistantLine: a pathologically large user
    // message (a big paste, or one carrying a multi-thousand-char unbreakable
    // line) is capped to a bounded, internally-scrollable box so it's always
    // drawn instead of reserving height and painting blank.
    val oversize = remember(text) {
        text.length > 12000 || text.lineSequence().any { it.length > 4000 }
    }
    // User message = right-aligned bubble (messenger style), wraps content up
    // to ~85% width. Agent messages stay left/full-width.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        val maxBubble = maxWidth * 0.85f
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubble)
                    .background(cyan.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Matched-message anchor: prefixLen=0 now (no "❯ " prefix).
                val anchor = LocalMatchAnchor.current
                val isMatched = anchor != null && anchor.msgId == LocalCurrentMsgId.current
                Text(
                    annotated,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = if (oversize) {
                        Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                    onTextLayout = if (isMatched) {
                        { result -> anchor!!.reportLayout(result, 0) }
                    } else {
                        {}
                    },
                )
            }
        }
    }
}

// TODO(1.1.0): Stream-stall UI.
//   Spec: when this AssistantLine has been receiving partial tokens but no
//   new text has arrived for 30 s, replace the trailing "thinking dots" (or
//   overlay this composable) with a subtle "Stream paused — tap to retry"
//   affordance with a Refresh icon. Tap should resume the turn.
//
//   Sketch:
//     val lastChange = remember { mutableStateOf(System.currentTimeMillis()) }
//     LaunchedEffect(text) { lastChange.value = System.currentTimeMillis() }
//     var stalled by remember { mutableStateOf(false) }
//     LaunchedEffect(text) {
//         stalled = false
//         delay(30_000)
//         if (System.currentTimeMillis() - lastChange.value >= 30_000) stalled = true
//     }
//     if (stalled && /* turn is still in flight */) { … overlay UI … }
//
//   Blocker: requires either a `vm.retry()`/resumeTurn entry point on
//   ChatViewModel (not currently exposed — explicitly out of scope for this
//   pass per task constraints), or hoisting the existing "Reconnecting…"
//   dialog signal down to AssistantLine. Skipping pending API decision.
@Composable
internal fun AssistantLine(text: String, vm: ChatViewModel = viewModel(), isStreaming: Boolean = false) {
    // While the agent is streaming, this composable redraws every few
    // hundred ms with a slightly longer `text`. The previous Markdown
    // implementation re-parsed the entire string from scratch on every
    // delta, which produced a visible flicker — block layouts being
    // torn down and rebuilt mid-stream. `remember(text)` caches the
    // parsed AnnotatedString so the same text never gets parsed twice;
    // a smooth update lands when the next byte arrives.
    // Theme-aware inline-code colours — pulled here so `lightMarkdown`
    // (non-composable) doesn't touch MaterialTheme. Detect light
    // theme by background luminance, not `isSystemInDarkTheme()`:
    // the user can override theme via the app's own prefs, and the
    // system flag would lie.
    //
    // Dark theme keeps the cyan-on-near-black look. Light theme
    // uses surfaceVariant + primary so code stops slamming dark
    // patches into the cream background.
    val bgColor = MaterialTheme.colorScheme.background
    val isLightTheme = (0.299 * bgColor.red + 0.587 * bgColor.green + 0.114 * bgColor.blue) > 0.5f
    val codeBg = if (isLightTheme) MaterialTheme.colorScheme.surfaceVariant
                 else androidx.compose.ui.graphics.Color(0xFF1F2933)
    val codeFg = if (isLightTheme) MaterialTheme.colorScheme.primary
                 else androidx.compose.ui.graphics.Color(0xFF00E5FF)
    // Markdown TABLES: a single AnnotatedString can't lay out a grid, so a reply
    // with a `| … | … |` + `|---|` block rendered as raw pipe-soup. When a table
    // is present, take the segmented path — plain text via lightMarkdown, tables
    // as a real bordered grid. the common no-table reply keeps the full path.)
    if (remember(text) { containsMarkdownTable(text) }) {
        TabledAssistantLine(text, codeBg, codeFg, isStreaming)
        return
    }
    // **Stream-friendly markdown parse: off-thread + debounced.**
    //
    // The old `remember(text) { lightMarkdown(text) }` re-parsed the
    // ENTIRE message from scratch on every prompt-byte (Claude streams
    // ~50-100 tokens/sec → 50-100 full reparses per second on the main
    // thread). For a 2000-char reply that's millions of regex ops, all
    // running on the UI thread, all hot. User complaint:.
    //
    //  - `produceState` cancels-and-restarts when `text` changes, so
    //    rapid mid-stream mutations never finish their parse — only
    //    the LAST stable text actually gets parsed.
    //  - `delay(120)` debounces: while text is mutating rapidly,
    //    nothing parses; once mutations stop for 120ms (= one
    //    user-perceptible frame budget) we kick off ONE parse.
    //  - `withContext(Dispatchers.Default)` moves the heavy regex/
    //    AnnotatedString build off the main thread.
    //  - Initial value seeds raw text so the first frame isn't blank
    //    while the producer warms up.
    //
    // Net effect for a streamed reply: ~8 parses/sec max while text
    // is changing, ~zero parses while idle. UI thread is free.
    val mdKey = MdCacheKey(text, codeBg.value, codeFg.value)
    val baseAnnotated: androidx.compose.ui.text.AnnotatedString = if (!isStreaming) {
        // STATIC line (session open, scrollback, finished turns): parse
        // synchronously the first time and cache it, so the line renders fully
        // formatted on its VERY FIRST frame. No 120ms window of raw
        // "*markdown*" → no height change after the open-scroll settles → no
        // jitter on entry. Repeat composition (recycle) is a cache hit = free.
        remember(mdKey) {
            markdownCache.get(mdKey)
                ?: lightMarkdown(text, codeBg = codeBg, codeFg = codeFg)
                    .also { markdownCache.put(mdKey, it) }
        }
    } else {
        // LIVE streaming line only: debounced off-thread parse so the hot
        // per-token reparse (phone heating, battery drain) never returns.
        // Seeded from cache/raw; the result is cached for when it goes static.
        val streamed by androidx.compose.runtime.produceState(
            initialValue = markdownCache.get(mdKey)
                ?: androidx.compose.ui.text.AnnotatedString(text),
            text, codeBg, codeFg,
        ) {
            markdownCache.get(mdKey)?.let { value = it; return@produceState }
            kotlinx.coroutines.delay(120L)
            val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                lightMarkdown(text, codeBg = codeBg, codeFg = codeFg)
            }
            markdownCache.put(mdKey, parsed)
            value = parsed
        }
        streamed
    }

    // **URL linkification pass.** Sweep the markdown-rendered text for
    // http(s) URLs and wrap each match in `LinkAnnotation.Url`. Compose's
    // Text composable picks these up automatically, applies the link
    // style (primary colour + underline) and routes clicks through
    // `LocalUriHandler`.
    //
    // Runs BEFORE the path splice so a URL that ends in an
    // existing-file-shaped suffix (rare but possible) still picks up
    // the disk icon on top of being clickable.
    val linkColor = MaterialTheme.colorScheme.primary
    val linkedBase = remember(baseAnnotated, linkColor) {
        val matches = ChatLinkDetector.detect(baseAnnotated.text)
        if (matches.isEmpty()) baseAnnotated
        else buildAnnotatedString {
            var cursor = 0
            for (m in matches) {
                if (m.start > cursor) append(baseAnnotated.subSequence(cursor, m.start))
                val link = androidx.compose.ui.text.LinkAnnotation.Url(
                    url = m.url,
                    styles = androidx.compose.ui.text.TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        ),
                    ),
                )
                val handle = pushLink(link)
                try {
                    append(baseAnnotated.subSequence(m.start, m.end))
                } finally {
                    pop(handle)
                }
                cursor = m.end
            }
            if (cursor < baseAnnotated.length) {
                append(baseAnnotated.subSequence(cursor, baseAnnotated.length))
            }
        }
    }

    // Find file paths the agent mentioned. Detection runs on the
    // POST-render text so paths inside backticks (which the markdown
    // pass strips) are still picked up. Splice in a placeholder
    // immediately after each match — the placeholder maps to a
    // clickable disk icon below.
    // Conditional inline-disk: only show the download icon for paths
    // that REALLY exist on the server. The agent often quotes paths
    // from logs / hallucinations / scratch dirs that have since been
    // cleaned — pre-this-change every match got a clickable disk that
    // led to a "file not found" download. We now kick off an async
    // `[ -f <path> ]` probe per detected path (`vm.checkFileExists`)
    // and re-build the AnnotatedString when the existence map changes,
    // so the icon only appears AFTER existence is confirmed. Missing
    // paths render as plain text without any download affordance.
    val fileExists by vm.fileExists.collectAsState()
    val detectedPaths = remember(linkedBase) {
        PathDetector.detect(linkedBase.text)
    }
    // Debounce the existence probe so a streaming assistant message
    // doesn't fire one SSH check per partial path (`/home`, `/home/u`,
    // `/home/u/foo`, `/home/u/foo.txt` — each transient state burns a
    // round-trip). When `detectedPaths` keeps changing, LaunchedEffect
    // cancels the in-flight delay and restarts. Only after the text
    // stops mutating for 600 ms do we actually probe — by then the
    // path is its final form.
    // Key on localSessionId too — when SSH eventually comes up
    // (after SK touch / network reconnect), this effect re-fires
    // and re-probes any paths that the previous attempt couldn't
    // resolve. Without it, paths rendered from cached JSONL stay
    // forever without a disk icon if the first probe fired before
    // `activeSessions[sid]` was populated.
    val localSessionId by vm.localSessionId.collectAsState()
    androidx.compose.runtime.LaunchedEffect(detectedPaths, localSessionId) {
        if (detectedPaths.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(600L)
        detectedPaths.forEach { vm.checkFileExists(it.path) }
    }
    val pathSplice = remember(linkedBase, fileExists, detectedPaths) {
        val matches = detectedPaths
        if (matches.isEmpty()) linkedBase to emptyList()
        else buildAnnotatedString {
            var cursor = 0
            for (m in matches) {
                if (m.start > cursor) append(linkedBase.subSequence(cursor, m.start))
                append(linkedBase.subSequence(m.start, m.end))
                // Only emit the inline placeholder if the file is
                // CONFIRMED to exist on the server. null / false → no
                // disk icon, the path stays as plain inline text.
                if (fileExists[m.path] == true) {
                    appendInlineContent(diskInlineKey(m.path), " ")
                }
                cursor = m.end
            }
            if (cursor < linkedBase.length) {
                append(linkedBase.subSequence(cursor, linkedBase.length))
            }
        } to matches.map { it.path }.filter { fileExists[it] == true }.distinct()
    }
    // Search-result highlight overlay. When the chat was opened from a
    // global-search hit, [LocalSearchHighlight] carries the user's
    // query down; we paint a yellow-on-dark background stripe over
    // every occurrence — BUT ONLY in the specific message the user
    // tapped (identified by targetMsgId). Other messages that happen
    // to contain the query are left untouched per user feedback:.
    // Applied LAST so it sits on top of markdown, link, and
    // path-splice styling without breaking any of them.
    val spec = LocalSearchHighlight.current
    val myId = LocalCurrentMsgId.current
    val active = spec?.takeIf { it.targetMsgId != null && it.targetMsgId == myId }
    val effectiveQuery = active?.query
    val rawCharOffset = active?.targetCharOffset ?: -1
    androidx.compose.runtime.LaunchedEffect(effectiveQuery) {
        if (effectiveQuery != null) {
            android.util.Log.d(
                "Conch-Hl",
                "AssistantLine match q=«${effectiveQuery}» rawOff=$rawCharOffset msg=${myId} text=${text.take(60)}"
            )
        }
    }
    // Yellow-on-dark highlight — chosen for maximum contrast so the
    // match jumps off the screen the moment the chat lands. The earlier
    // tertiary-alpha-0.35 variant blended into our orange accent colour
    // on top of dark backgrounds and the user reported.
    val highlightBg = androidx.compose.ui.graphics.Color(0xFFFFEB3B)
    val highlightFg = androidx.compose.ui.graphics.Color(0xFF111111)
    // Memoise the highlight-overlay computation. AssistantLine recomposes
    // on every parent state change (and there are many during streaming);
    // `rawToAnnotatedPos` does a walk over `pathSplice.first` whose
    // `getStringAnnotations` lookup allocates a list per character.
    // Keys cover every input the function reads.
    val annotated = remember(pathSplice.first, effectiveQuery, highlightBg, highlightFg, text, rawCharOffset) {
        applyHighlightOverlay(
            pathSplice.first, effectiveQuery, highlightBg, highlightFg,
            rawText = if (rawCharOffset >= 0) text else "",
            rawCharOffset = rawCharOffset,
        )
    }
    val paths = pathSplice.second

    // Build inlineContent map only when there are paths — keeps the
    // common path (no detected files) zero-overhead.
    //
    // Placeholder width has to fit: 22dp disk box + 4dp gap + size
    // label up to "999.9 KB" / "12.34 GB". At bodyLarge (~16sp)
    // body font, 7.5em ≈ 120sp ≈ 120dp — enough headroom for the
    // 7-char "0.00 GB" worst case without truncating the unit
    // suffix ("165," clipping bug).
    val inline: Map<String, InlineTextContent> = if (paths.isEmpty()) emptyMap() else {
        paths.associate { p ->
            diskInlineKey(p) to InlineTextContent(
                placeholder = Placeholder(
                    width = 7.5.em,
                    height = 1.4.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                ),
            ) { _ -> DownloadDisk(path = p, vm = vm) }
        }
    }

    // Matched-message side-channel. AssistantLine renders the body text
    // as-is (no prefix), so prefixLen = 0.
    val matchAnchor = LocalMatchAnchor.current
    val isMatched = matchAnchor != null && matchAnchor.msgId == myId
    val clipboard = LocalClipboardManager.current
    // An assistant message big enough to overflow Compose's draw budget (a
    // multi-KB reply, or one carrying a multi-thousand-char unbreakable line)
    // would reserve its height but paint BLANK — the half-screen gap. Normal
    // replies (the overwhelming majority) are untouched; only a pathologically
    // large one gets capped to a bounded, internally-scrollable box so it's
    // always drawn. Tool/code/diff blocks have their own bound; this is the
    // plain-assistant safety net.
    val oversize = remember(text) {
        text.length > 12000 || text.lineSequence().any { it.length > 4000 }
    }
    // Box (not Column) so the copy button OVERLAYS the bottom-right corner — on
    // the level of the last line, not its own full-width row.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            inlineContent = inline,
            modifier = if (oversize) {
                Modifier
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
            } else {
                Modifier
            },
            onTextLayout = if (isMatched) {
                { result -> matchAnchor!!.reportLayout(result, 0) }
            } else {
                {}
            },
        )
        // Copy-to-clipboard — ASSISTANT messages only. Copies the raw text (not
        // the markdown render). Hidden mid-stream so it copies the finished reply.
        // Overlaid at the bottom-right corner, on the level of the last line — no
        // backing.
        if (!isStreaming && text.isNotBlank()) {
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(text)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy message",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ───────────────────────── Markdown tables ─────────────────────────
// A single AnnotatedString can't lay out a grid, so a reply with a markdown
// table is split into segments: plain text (lightMarkdown) + real bordered grids.

private sealed interface MdSeg {
    data class Txt(val s: String) : MdSeg
    data class Tbl(val rows: List<List<String>>) : MdSeg
}

private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.count { it == '|' } >= 2
}

/** A `|---|:--:|---|` divider row (dashes/colons only). */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim().trim('|').trim()
    return t.isNotEmpty() && t.contains('-') && t.all { it == '-' || it == ':' || it == '|' || it == ' ' }
}

/** A ``` or ~~~ fence open/close line. */
private fun isFenceToggle(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("```") || t.startsWith("~~~")
}

/** Split a table row on UNescaped pipes only — a cell may legitimately contain
 *  a literal pipe written `\|` (GFM), which must NOT create a new column
 *  (user audit, 2026-06-14). Negative lookbehind for a backslash; then unescape. */
private val TABLE_CELL_DELIM = Regex("""(?<!\\)\|""")

private fun parseTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|")
        .split(TABLE_CELL_DELIM)
        .map { it.replace("\\|", "|").trim() }

internal fun containsMarkdownTable(text: String): Boolean {
    val lines = text.split('\n')
    var inFence = false
    for (i in 0 until lines.size - 1) {
        if (isFenceToggle(lines[i])) { inFence = !inFence; continue }
        if (inFence) continue   // a |...| block inside ```code``` is NOT a table
        if (isTableRow(lines[i]) && isTableSeparator(lines[i + 1])) return true
    }
    return false
}

private fun splitMarkdownSegments(text: String): List<MdSeg> {
    val lines = text.split('\n')
    val out = mutableListOf<MdSeg>()
    val buf = StringBuilder()
    fun flush() {
        val s = buf.toString().trim('\n')
        if (s.isNotBlank()) out.add(MdSeg.Txt(s))
        buf.clear()
    }
    var i = 0
    var inFence = false
    while (i < lines.size) {
        // Fence lines (and everything between them) stay verbatim in the text
        // segment — lightMarkdown renders the whole ```block``` as code. A
        // table-shaped block inside a fence used to be ripped into a grid with
        // orphaned backtick lines left behind (user audit, 2026-06-14).
        if (isFenceToggle(lines[i])) {
            inFence = !inFence
            buf.append(lines[i]).append('\n'); i++
            continue
        }
        if (!inFence && isTableRow(lines[i]) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            flush()
            val rows = mutableListOf(parseTableRow(lines[i]))   // header
            i += 2                                              // skip header + separator
            while (i < lines.size && !isFenceToggle(lines[i]) && isTableRow(lines[i])) {
                rows.add(parseTableRow(lines[i])); i++
            }
            out.add(MdSeg.Tbl(rows))
        } else {
            buf.append(lines[i]).append('\n'); i++
        }
    }
    flush()
    return out
}

/** Bordered grid — content-sized columns (a "#" column stays narrow), bold
 *  header. Parity with the CLI's box-drawing tables (user, 2026-06-14). */
@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }.coerceAtLeast(1)
    val weights = remember(rows) {
        FloatArray(cols) { c -> rows.maxOf { (it.getOrNull(c)?.length ?: 0) }.coerceAtLeast(1).toFloat() }
    }
    val border = MaterialTheme.colorScheme.outline
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        rows.forEachIndexed { r, row ->
            Row(modifier = Modifier.height(IntrinsicSize.Min).fillMaxWidth()) {
                for (c in 0 until cols) {
                    Text(
                        text = row.getOrNull(c).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .weight(weights[c])
                            .fillMaxHeight()
                            .border(0.5.dp, border)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Assistant reply that contains a markdown table — rendered as segments. */
@Composable
private fun TabledAssistantLine(
    text: String,
    codeBg: androidx.compose.ui.graphics.Color,
    codeFg: androidx.compose.ui.graphics.Color,
    isStreaming: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    val segments = remember(text) { splitMarkdownSegments(text) }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            segments.forEach { seg ->
                when (seg) {
                    is MdSeg.Txt -> {
                        val md = remember(seg.s, codeBg, codeFg) {
                            lightMarkdown(seg.s, codeBg = codeBg, codeFg = codeFg)
                        }
                        Text(
                            text = md,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    is MdSeg.Tbl -> MarkdownTable(seg.rows)
                }
            }
        }
        if (!isStreaming && text.isNotBlank()) {
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(text)) },
                modifier = Modifier.align(Alignment.BottomEnd).size(22.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy message",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

internal fun lightMarkdown(
    src: String,
    codeBg: androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color(0xFF1F2933),
    codeFg: androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color(0xFF00E5FF),
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        when {
            // ```fenced code```
            src.startsWith("```", i) -> {
                val end = src.indexOf("```", i + 3)
                if (end < 0) { append(src.substring(i)); break }
                val body = src.substring(i + 3, end).trimStart('\n')
                withStyle(SpanStyle(
                    color = codeFg,
                    background = codeBg,
                    fontFamily = FontFamily.Monospace,
                )) { append(body) }
                i = end + 3
            }
            // `inline code`
            src[i] == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end < 0) { append('`'); i++; continue }
                withStyle(SpanStyle(
                    color = codeFg,
                    background = codeBg,
                    fontFamily = FontFamily.Monospace,
                )) { append(src.substring(i + 1, end)) }
                i = end + 1
            }
            // **bold**
            src.startsWith("**", i) -> {
                val end = src.indexOf("**", i + 2)
                if (end < 0) { append("**"); i += 2; continue }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(src.substring(i + 2, end))
                }
                i = end + 2
            }
            // *italic*
            src[i] == '*' && i + 1 < src.length && src[i + 1] != ' ' -> {
                val end = src.indexOf('*', i + 1)
                if (end < 0) { append('*'); i++; continue }
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(src.substring(i + 1, end))
                }
                i = end + 1
            }
            else -> { append(src[i]); i++ }
        }
    }
}

@Composable
@Suppress("UnusedPrivateMember")
internal fun AssistantLineMarkdownLegacy(text: String) {
    // Kept for reference — the earlier full-Markdown rendering. Re-enable
    // by swapping the call in `TerminalLine` if `lightMarkdown` ever
    // proves too thin.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        com.mikepenz.markdown.m3.Markdown(
            content = text,
            colors = markdownColor(
                text = MaterialTheme.colorScheme.onSurface,
                codeText = MaterialTheme.colorScheme.tertiary,
                inlineCodeText = MaterialTheme.colorScheme.tertiary,
                linkText = MaterialTheme.colorScheme.primary,
                codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
                dividerColor = MaterialTheme.colorScheme.outline,
            ),
            typography = markdownTypography(
                h1 = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.primary),
                h2 = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary),
                h3 = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.primary),
                h4 = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.primary),
                h5 = MaterialTheme.typography.titleSmall,
                h6 = MaterialTheme.typography.titleSmall,
                text = MaterialTheme.typography.bodyLarge,
                paragraph = MaterialTheme.typography.bodyLarge,
                code = MaterialTheme.typography.bodyMedium,
                inlineCode = MaterialTheme.typography.bodyMedium,
                quote = MaterialTheme.typography.bodyLarge,
                bullet = MaterialTheme.typography.bodyLarge,
                list = MaterialTheme.typography.bodyLarge,
                ordered = MaterialTheme.typography.bodyLarge,
            )
        )
    }
}

/** Clean centered "phone connected" row — what the chat shows in place of the
 *  bridge handshake's one-token reply (the prompt + token are hidden upstream).
 *  English only (UI string). */
@Composable
private fun BridgeConnectedRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PhoneAndroid,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "phone connected",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        )
    }
}

/** "connecting phone…" — shown while the bridge handshake runs (all its
 *  technical ping/pong/Bash steps are hidden behind it). Replaced by
 *  [BridgeConnectedRow] the moment the phone confirms. English only (UI string). */
@Composable
private fun BridgeConnectingRow() {
    val stalled = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // ⛔ NO PROGRESS NUMBER HERE. This row used to ramp a counter 0 → 95 % over
        // ~8.5 s. The row now states what is happening and nothing more.
        //
        // The one piece of real state it still carries: if this row is STILL mounted
        // ~38 s on it was never replaced by the "phone connected" row — the handshake
        // stalled (e.g. the turn died). Flip to a quiet "couldn't connect" rather than
        // sit here forever (user, 2026-06-27: the "connecting 95%" ghost). A real
        // success swaps this System row for BridgeConnectedRow, unmounting this
        // composable and cancelling the timer — so it can never print a false
        // failure. 38 s is deliberately past `conch-bridge`'s own 30 s deadline, so
        // when the agent has something to say about the failure, it says it first.
        kotlinx.coroutines.delay(38_000)
        stalled.value = true
    }
    if (stalled.value) {
        BridgeStatusRow("couldn't connect to phone")
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PhoneAndroid,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "connecting phone…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
        )
    }
}

/** Quiet centered phone-bridge status (e.g. "couldn't connect to phone"). Muted —
 *  no raw error, no spinner. English only (UI string). */
@Composable
private fun BridgeStatusRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PhoneAndroid,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
