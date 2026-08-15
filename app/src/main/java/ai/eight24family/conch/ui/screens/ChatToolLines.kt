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
    // Claude Code's newer task tools (TaskCreate / TaskUpdate) superseded
    // TodoWrite as the CLI's working task list — the terminal renders them as
    // ✔/◼/◻ checklist rows, while here they fell through to the generic "▸
    // TaskCreate {…json}" line, so tasks were effectively invisible. Same
    // visual language as TodoWrite; TaskList/TaskGet keep the generic row
    // (their meat is in the RESULT, which ToolResultLine already shows).
    if (name == "TaskCreate" || name == "TaskUpdate") {
        TaskToolLine(name, input)
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

/**
 * Render a `TaskCreate` / `TaskUpdate` tool call as a checklist row (the CLI's
 * task-list affordance, one task per call — unlike TodoWrite these are
 * incremental, so each call shows the one task it touches).
 *
 *   TaskCreate {subject, description, activeForm}   → "◻ subject"
 *   TaskUpdate {taskId, status?, subject?}          → glyph by status + "#id · …"
 */
@Composable
internal fun TaskToolLine(name: String, input: String) {
    val amber = MaterialTheme.colorScheme.tertiary
    val done = MaterialTheme.colorScheme.primary
    val pending = MaterialTheme.colorScheme.onSurfaceVariant
    val t = remember(input) { parseTaskToolInput(input) }
    val creating = name == "TaskCreate"
    val status = if (creating) "pending" else t.status ?: "updated"
    val (glyph, color, strike) = when (status) {
        "completed" -> Triple("✔", done, true)
        "in_progress" -> Triple("◼", amber, false)
        "deleted" -> Triple("✕", pending, true)
        else -> Triple("◻", pending, false)
    }
    val label = buildString {
        t.taskId?.let { append('#').append(it).append(' ') }
        append(t.subject ?: if (creating) "task" else "task → ${status.replace('_', ' ')}")
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    ) {
        Text(
            "$glyph ",
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (status == "in_progress") FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            label,
            color = if (strike) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough
                             else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

internal data class TaskToolInput(val subject: String?, val status: String?, val taskId: String?)

/** Tolerant TaskCreate/TaskUpdate-input parser — same fallback contract as
 *  [parseTodos]: any shape mismatch degrades to nulls, never a crash. */
internal fun parseTaskToolInput(input: String): TaskToolInput {
    if (input.isBlank()) return TaskToolInput(null, null, null)
    val obj = SilentlyTry.logged("SshAi-ToolLines", "parse task tool json") {
        kotlinx.serialization.json.Json.parseToJsonElement(input) as? kotlinx.serialization.json.JsonObject
    } ?: return TaskToolInput(null, null, null)
    fun str(key: String): String? =
        (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    return TaskToolInput(subject = str("subject"), status = str("status"), taskId = str("taskId"))
}

/** One task on the session's task board — folded from the transcript.
 *  [recentlyCompleted] = its completing update landed AFTER the user's last
 *  prompt, i.e. it belongs to the CURRENT working stretch. */
internal data class TaskBoardRow(
    val taskId: String,
    val subject: String,
    val status: String,
    val recentlyCompleted: Boolean = false,
)

private val TASK_CREATED_RX = Regex("""^Task #(\d+) created successfully(?::\s*(.*))?""")

/** A TaskList result line: `#12. [in_progress] Subject text` (leading '#'
 *  optional — be tolerant to CLI formatting drift). */
private val TASK_LIST_LINE_RX = Regex("""^#?(\d+)\.\s*\[([a-z_]+)\]\s*(.+)$""")

/**
 * Rebuild the CLI's task list from the transcript: TaskCreate's RESULT line
 * ("Task #N created successfully: <subject>") binds the id to the subject
 * (falling back to the paired ToolUse input's subject), TaskUpdate calls move
 * status / rename / delete. Pure fold over the display list — no extra state
 * to persist, survives re-entry and resume because the transcript IS the state.
 *
 * ⚠ A blind cumulative fold LIES on long sessions: the CLI's list shrinks (the
 * agent deletes stale tasks), and any deletion outside our parsed window left
 * ghosts — the phone showed «+16 completed» and struck-through tasks from hours
 * ago while the terminal showed 9 rows. Two corrections: 1. Every TaskList
 * RESULT is an authoritative snapshot of the whole list — REPLACE the board with
 * it (deletions included, wherever they happened). 2. Completed rows are only
 * "fresh" (struck through in the panel) when their completing update landed
 * after the user's last prompt; older completions fold into the «… +N completed»
 * counter, like the CLI.
 */
internal fun foldTaskBoard(messages: List<AgentMessage>): List<TaskBoardRow> {
    val createSubjects = HashMap<String, String>()          // toolUseId → subject
    val taskListCalls = HashSet<String>()                   // toolUseIds of TaskList
    val board = LinkedHashMap<String, TaskBoardRow>()       // taskId → row, insertion order
    val completedAt = HashMap<String, Int>()                // taskId → msg index of completion
    var lastUserIdx = -1
    messages.forEachIndexed { idx, m ->
        when (m) {
            is AgentMessage.UserText -> lastUserIdx = idx
            is AgentMessage.ToolUse -> when (m.toolName) {
                "TaskCreate" -> parseTaskToolInput(m.input).subject?.let { createSubjects[m.id] = it }
                "TaskList" -> taskListCalls.add(m.id)
                "TaskUpdate" -> {
                    val t = parseTaskToolInput(m.input)
                    val id = t.taskId
                    if (id != null) {
                        if (t.status == "deleted") {
                            board.remove(id)
                            completedAt.remove(id)
                        } else {
                            val prev = board[id]
                            val status = t.status ?: prev?.status ?: "pending"
                            if (status == "completed" && prev?.status != "completed") completedAt[id] = idx
                            board[id] = TaskBoardRow(
                                taskId = id,
                                subject = t.subject ?: prev?.subject ?: "task #$id",
                                status = status,
                            )
                        }
                    }
                }
            }
            is AgentMessage.ToolResult -> if (!m.isError) {
                if (m.toolUseId in taskListCalls) {
                    // Authoritative snapshot — rebuild the board from it.
                    val snapshot = LinkedHashMap<String, TaskBoardRow>()
                    for (line in m.output.lineSequence()) {
                        val match = TASK_LIST_LINE_RX.find(line.trim()) ?: continue
                        val id = match.groupValues[1]
                        val status = match.groupValues[2]
                        if (status == "deleted") continue
                        snapshot[id] = TaskBoardRow(
                            taskId = id,
                            subject = match.groupValues[3].trim().ifEmpty { board[id]?.subject ?: "task #$id" },
                            status = status,
                        )
                    }
                    if (snapshot.isNotEmpty()) {
                        board.clear()
                        board.putAll(snapshot)
                        completedAt.keys.retainAll(snapshot.keys)
                    }
                } else {
                    val first = m.output.lineSequence().firstOrNull().orEmpty().trim()
                    TASK_CREATED_RX.find(first)?.let { match ->
                        val id = match.groupValues[1]
                        if (id !in board) {
                            val subj = createSubjects[m.toolUseId]
                                ?: match.groupValues[2].takeIf { it.isNotBlank() }
                                ?: "task #$id"
                            board[id] = TaskBoardRow(id, subj, "pending")
                        }
                    }
                }
            }
            else -> {}
        }
    }
    return board.values.map { row ->
        if (row.status == "completed" && (completedAt[row.taskId] ?: -1) > lastUserIdx) {
            row.copy(recentlyCompleted = true)
        } else row
    }
}

/**
 * The session task board, PINNED above the prompt bar — the phone twin of the
 * CLI's checklist widget ("■ current … +2 pending, 4 completed"). The inline
 * [TaskToolLine] rows show each call as it happens; this panel shows the LIVE
 * STATE — subjects included — which is what the transcript rows alone can't.
 * Tap the header to reveal completed rows.
 */
@Composable
internal fun TaskBoardPanel(rows: List<TaskBoardRow>) {
    if (rows.isEmpty()) return
    // Mirrors the CLI widget EXACTLY: no header, no star — the list hangs
    // under the pinned working row with a ⎿ elbow, ■ current, □ pending,
    // then the first few ✔ completed struck through and "… +N completed" for
    // the rest. Tap anywhere to unfold all completed; tap again to fold
    // back.
    var expandCompleted by rememberSaveable { mutableStateOf(false) }
    // Collapse-down to a THIN STRIP showing just the main (in-progress) task.
    // The ▾ chevron on the first row collapses; tapping the strip expands
    // back.
    var boardCollapsed by rememberSaveable { mutableStateOf(false) }
    val amber = MaterialTheme.colorScheme.tertiary
    val done = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val inProgress = rows.filter { it.status == "in_progress" }
    val pending = rows.filter { it.status != "in_progress" && it.status != "completed" }
    val completed = rows.filter { it.status == "completed" }
    val visiblePending = if (pending.size > 8) pending.take(8) else pending
    val hiddenPending = pending.size - visiblePending.size
    // Struck-through rows: ONLY tasks completed in the CURRENT stretch (since
    // the user's last prompt) — like the CLI. Everything older is just the
    // «… +N completed» counter; tap unfolds the full history.
    val fresh = completed.filter { it.recentlyCompleted }
    val visibleCompleted = if (expandCompleted) completed else fresh.take(5)
    val hiddenCompleted = completed.size - visibleCompleted.size

    @Composable
    fun taskRow(glyph: String, glyphColor: Color, text: String, textColor: Color, bold: Boolean, strike: Boolean, first: Boolean) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (first) "⎿  " else "   ",
                color = dim,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "$glyph ",
                color = glyphColor,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text,
                color = textColor,
                style = MaterialTheme.typography.bodySmall,
                textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (boardCollapsed) {
        val main = inProgress.firstOrNull() ?: pending.firstOrNull() ?: rows.first()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { boardCollapsed = false }
                .padding(horizontal = 12.dp, vertical = 1.dp),
        ) {
            Text("■ ", color = amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(
                main.subject,
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val more = rows.size - 1
            if (more > 0) Text("  +$more ▴", color = dim, style = MaterialTheme.typography.labelSmall)
            else Text("  ▴", color = dim, style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    Box(modifier = Modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable { expandCompleted = !expandCompleted },
    ) {
        var first = true
        for (t in inProgress) {
            taskRow("■", amber, t.subject, MaterialTheme.colorScheme.onSurface, bold = true, strike = false, first = first)
            first = false
        }
        for (t in visiblePending) {
            taskRow("□", dim, t.subject, dim, bold = false, strike = false, first = first)
            first = false
        }
        if (hiddenPending > 0) {
            Text("   … +$hiddenPending pending", color = dim, style = MaterialTheme.typography.bodySmall)
        }
        for (t in visibleCompleted) {
            taskRow("✔", done, t.subject, dim, bold = false, strike = true, first = first)
            first = false
        }
        if (hiddenCompleted > 0) {
            Text("   … +$hiddenCompleted completed", color = dim, style = MaterialTheme.typography.bodySmall)
        }
    }
    // Collapse handle — top-right, separate from the body tap (which toggles
    // the completed history).
    Text(
        "▾",
        color = dim,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .clickable { boardCollapsed = true }
            .padding(horizontal = 14.dp, vertical = 2.dp),
    )
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
        // A user-chosen "deny" is NOT an error — render the resolved card in a
        // calm dim/neutral colour, not alarming red. The pending "[ deny ]"
        // button stays accented below; only the RESOLVED state is calmed.
        AgentMessage.PermissionRequest.Resolution.DENIED -> MaterialTheme.colorScheme.outline
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
