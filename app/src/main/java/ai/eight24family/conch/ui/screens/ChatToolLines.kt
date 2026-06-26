package ai.eight24family.conch.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.CloudOff
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
import ai.eight24family.conch.ui.haptic.LocalSshAiHaptics
import ai.eight24family.conch.ui.haptic.SshAiHaptic
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
import ai.eight24family.conch.util.SilentlyTry
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolUseLine(name: String, input: String) {
    // TodoWrite is the agent's working task list — render it as a
    // structured checklist instead of a generic "▸ TodoWrite {...json}"
    // line. This is the affordance the user asked for after seeing the
    // CLI version of Claude Code render the same call as a tree of
    // ✔ / ◼ / ◻ task rows. Always visible, no expand-to-see.
    if (name == "TodoWrite") {
        TodoWriteLine(input)
        return
    }
    val amber = MaterialTheme.colorScheme.tertiary
    var expanded by remember { mutableStateOf(false) }
    val hasDetails = input.isNotBlank()
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) {
        if (expanded) { kotlinx.coroutines.delay(50); bringIntoView.bringIntoView() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDetails) { expanded = !expanded }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "▸ ",
                color = amber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                name,
                color = amber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            if (hasDetails) {
                val argsPreview = input.lineSequence().firstOrNull().orEmpty().take(80)
                Text(
                    " $argsPreview",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (expanded && hasDetails) {
          Box(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)) {
            when (name) {
                "Edit", "MultiEdit" -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 2.dp, bottom = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) { EditDiffViewer(name, input) }
                "Write" -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 2.dp, bottom = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) { WriteFileViewer(input) }
                else -> CopyableCodeBlock(
                    text = input.take(8000),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 2.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
          }
        }
    }
}

/**
 * Render a `TodoWrite` tool call as a structured task list. Input is a
 * JSON object with shape `{ "todos": [{ content, status, activeForm }] }`
 * where status ∈ { "pending", "in_progress", "completed", "cancelled" }.
 *
 * Visual: a header ("◆ Update todo list") followed by an indented list
 * of rows. Each row shows a status glyph + the task text. The glyph
 * choice (✔ done, ◼ in-progress, ◻ pending) matches what the user is
 * familiar with from the CLI version.
 */
@Composable
internal fun TodoWriteLine(input: String) {
    val amber = MaterialTheme.colorScheme.tertiary
    val done = MaterialTheme.colorScheme.primary
    val inProgress = MaterialTheme.colorScheme.tertiary
    val pending = MaterialTheme.colorScheme.onSurfaceVariant
    val todos = remember(input) { parseTodos(input) }
    if (todos.isEmpty()) {
        // Couldn't parse — fall back to a single header line so nothing
        // visually goes missing, but skip the "▸ TodoWrite {raw}" noise.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "◆ ",
                color = amber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Update todo list",
                color = amber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "◆ ",
                color = amber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Update todo list",
                color = amber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        for (t in todos) {
            val (glyph, color, strike) = when (t.status) {
                "completed" -> Triple("✔", done, true)
                "in_progress" -> Triple("◼", inProgress, false)
                "cancelled" -> Triple("✕", pending, true)
                else -> Triple("◻", pending, false)
            }
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 1.dp),
            ) {
                Text(
                    "$glyph ",
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (t.status == "in_progress") FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    t.content,
                    color = if (strike) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough
                                     else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

internal data class TodoItem(val content: String, val status: String)

/** Tolerant TodoWrite-input parser. Falls back to empty list on any
 *  shape mismatch — the renderer treats that as "show generic header
 *  only" so a malformed call doesn't blank the message. */
internal fun parseTodos(input: String): List<TodoItem> {
    if (input.isBlank()) return emptyList()
    val root = SilentlyTry.logged("SshAi-ToolLines", "parse todos json") {
        kotlinx.serialization.json.Json.parseToJsonElement(input)
    } ?: return emptyList()
    val obj = (root as? kotlinx.serialization.json.JsonObject) ?: return emptyList()
    val arr = obj["todos"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val content = (o["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return@mapNotNull null
        val status = (o["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: "pending"
        TodoItem(content, status)
    }
}

@Composable
internal fun ToolResultLine(output: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    var expanded by remember { mutableStateOf(false) }
    val firstLine = output.lineSequence().firstOrNull().orEmpty().take(100)
    val moreLines = output.lineSequence().count() - 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "└─ ",
                color = color,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                firstLine,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (moreLines > 0) {
                Text(
                    " +${moreLines}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (expanded && output.isNotBlank()) {
            // Phase 6: when the tool result looks like a unified diff
            // (presence of `@@` hunk headers + ±/-/+ lines), render via
            // [DiffView] for the colored, side-by-side-on-wide-windows
            // experience. Otherwise fall through to the plain
            // CopyableCodeBlock — same path as before.
            val trimmed = output.take(64_000)
            if (ai.eight24family.conch.ui.components.UnifiedDiffParser.looksLikeDiff(trimmed)) {
                ai.eight24family.conch.ui.components.DiffView(
                    rawText = trimmed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 2.dp, bottom = 4.dp),
                )
            } else {
                CopyableCodeBlock(
                    text = output.take(16000),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 2.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
internal fun PermissionLine(
    req: AgentMessage.PermissionRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onAllowSession: () -> Unit = {},
) {
    val color = when (req.resolved) {
        AgentMessage.PermissionRequest.Resolution.PENDING -> MaterialTheme.colorScheme.secondary
        AgentMessage.PermissionRequest.Resolution.ALLOWED -> MaterialTheme.colorScheme.tertiary
        AgentMessage.PermissionRequest.Resolution.DENIED -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color, RoundedCornerShape(2.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "▸ permission · ${req.toolName}",
            color = color,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        if (req.input.isNotBlank()) {
            CopyableCodeBlock(
                text = req.input.take(800),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                contentPadding = PaddingValues(6.dp)
            )
        }
        when (req.resolved) {
            AgentMessage.PermissionRequest.Resolution.PENDING ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAllow) {
                        Text(
                            "[ allow ]",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    // "Always allow this session" — only where the agent's protocol
                    // grants a session scope (Codex acceptForSession / Gemini
                    // allow_always). Kills re-tapping the same approval on a phone.
                    if (req.canAllowSession) {
                        TextButton(onClick = onAllowSession) {
                            Text(
                                "[ always ]",
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    TextButton(onClick = onDeny) {
                        Text(
                            "[ deny ]",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            AgentMessage.PermissionRequest.Resolution.ALLOWED ->
                Text("✓ allowed", color = color, style = MaterialTheme.typography.labelLarge)
            AgentMessage.PermissionRequest.Resolution.DENIED ->
                Text("✕ denied", color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun SystemLine(msg: AgentMessage.System) {
    val cyan = MaterialTheme.colorScheme.primary
    val magenta = MaterialTheme.colorScheme.secondary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface

    if (msg.subtype == "welcome") {
        // Mirrors the banner Claude Code prints in interactive mode, but
        // populated from a real probe (pwd + `<cli> --version`) we ran on
        // the server right after the SSH handshake completed.
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                "✻ welcome",
                color = magenta,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            msg.version?.takeIf { it.isNotBlank() }?.let {
                Text("  $it", color = dim, style = MaterialTheme.typography.bodyMedium)
            }
            msg.cwd?.takeIf { it.isNotBlank() }?.let {
                Text("  cwd: $it", color = dim, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "  // type a prompt to start",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        return
    }
    // Session bookkeeping (init/resume + every system event that
    // carries a sessionId) used to render as a multi-line `═══ session
    // model=… cwd=… id=…` block. With `--include-partial-messages`
    // Claude emits one of these for every partial assistant tick, so
    // the chat would fill up with these banners. Drop them entirely —
    // the chat title + topbar already tell the user which model and
    // server they're on.
    if (msg.subtype == "init" || msg.model != null || msg.sessionId != null) return
    Text(
        "· ${msg.subtype.ifBlank { "system" }}",
        color = dim,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun EventLine(label: String, details: String?, color: Color, onClick: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val hasDetails = !details.isNullOrBlank() && details != label
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) {
        if (expanded) { kotlinx.coroutines.delay(50); bringIntoView.bringIntoView() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDetails || onClick != null) {
                if (onClick != null) onClick() else expanded = !expanded
            }
            .padding(vertical = 1.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasDetails) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.padding(end = 2.dp)
                )
            } else {
                Text("· ", color = color, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                label.removePrefix("· "),
                color = color,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded && hasDetails) {
            CopyableCodeBlock(
                text = details!!.take(8000),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoView)
                    .padding(start = 18.dp, top = 2.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                contentPadding = PaddingValues(6.dp)
            )
        }
    }
}

/**
 * Prominent "Service is busy" card — same treatment claude.ai web shows on a
 * 529 / overloaded model response. Used for [AgentMessage.Error] with
 * `kind="overloaded"`; emitted by [ClaudeMessageParser] on every `api_retry`
 * (CLI is silently retrying up to 10×) and on the final `is_error:true` result
 * if retries are exhausted. The banner upserts in place via a stable id, so
 * successive retries refresh the same row instead of stacking ten cards.
 */
@Composable
internal fun ServiceBusyCard(title: String, body: String?) {
    val accent = MaterialTheme.colorScheme.tertiary
    val fg = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = fg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    color = dim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Model-unavailable card — Anthropic's "Claude X is currently unavailable"
 * notice rendered like claude.ai's own UI: clean title, NOT truncated, with
 * a tappable "Learn more" opening the announcement URL. Used for
 * [AgentMessage.Error] with `kind="unavailable"`; [details] carries the URL.
 * Stable id upserts the `result` + `error` copies into ONE card.
 */
@Composable
internal fun ModelUnavailableCard(title: String, url: String?) {
    val accent = MaterialTheme.colorScheme.tertiary
    val fg = MaterialTheme.colorScheme.onSurface
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!url.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { SilentlyTry.fired("SshAi-UI", "open learn-more url") { uriHandler.openUri(url) } }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    "Learn more",
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Reusable `[ label ]` action button — same shape as `[ + new session ]`
 * and the `[ servername ]` topbar pill. Used for send/stop in PromptBar
 * and could be lifted further if more action buttons need this style.
 *
 * Disabled state: same shape, dimmer accent (caller picks via [accent]),
 * the click handler is gated by [enabled].
 */
@Composable
internal fun BracketActionButton(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
        contentColor = accent,
        border = BorderStroke(1.dp, accent),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
