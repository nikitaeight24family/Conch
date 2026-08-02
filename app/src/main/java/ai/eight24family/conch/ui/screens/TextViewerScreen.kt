package ai.eight24family.conch.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.eight24family.conch.agent.shellEscape
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.CodeHighlighter
import ai.eight24family.conch.util.SilentlyTry
import ai.eight24family.conch.util.defaultHighlightColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Safety caps — the whole point of this screen NOT crashing ──
// READ_CAP bounds how much we ever pull off the stream (so a multi-GB
// file can't OOM the read). VIEW_CAP bounds how much we hand to Compose's
// text layout in one node (a single giant Text/AnnotatedString is what
// blows up). HIGHLIGHT_CAP bounds the regex tokenizer. EDIT_CAP gates the
// editor to small, complete files (editing a truncated file would silently
// corrupt it on save).
private const val READ_CAP_BYTES = 2 * 1024 * 1024
private const val VIEW_CAP_CHARS = 200_000
private const val HIGHLIGHT_CAP_CHARS = 120_000
private const val EDIT_CAP_CHARS = 200_000

/** Outcome of a bounded, binary-sniffed read. */
private data class TextLoad(val text: String, val binary: Boolean, val truncated: Boolean)

/**
 * Built-in viewer / editor for **text** files.
 *
 * Reached after a successful file download when the user picked
 * "Open in Conch" from the chooser sheet (or had previously remembered
 * that choice for the file's extension).
 *
 * Crash-safety is the contract here: this screen can be pointed at ANY
 * file (the chooser tries to gate non-text out, but a remembered choice or
 * a weird extension can still land here), so it must degrade gracefully:
 *  - **binary** content → no text rendering at all; offer "open with…".
 *  - **huge** files → read/highlight/render are all bounded; a banner says
 *    the preview is truncated, and editing is disabled (can't safely save
 *    a partial file).
 *
 * The save path needs [serverId] + [remotePath]; both are null when the
 * viewer was launched from somewhere that doesn't know them (view-only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewerScreen(
    uri: Uri,
    filename: String,
    serverId: String?,
    remotePath: String?,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val canSave = !serverId.isNullOrBlank() && !remotePath.isNullOrBlank()

    // ── File load (off-main, bounded + binary-sniffed) ─────────────
    var load by remember(uri) { mutableStateOf<TextLoad?>(null) }
    var loadError by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        runCatching { withContext(Dispatchers.IO) { loadTextFile(ctx, uri) } }
            .onSuccess { load = it }
            .onFailure { loadError = it.message ?: it.javaClass.simpleName }
    }
    val originalText = load?.text
    val isBinary = load?.binary == true
    val fileTruncated = load?.truncated == true
    // What we actually render in view mode — capped so a huge file can't
    // blow up Compose's text layout or the highlighter.
    val displayText = (originalText ?: "").let {
        if (it.length > VIEW_CAP_CHARS) it.take(VIEW_CAP_CHARS) else it
    }
    val viewTruncated = fileTruncated || (originalText?.length ?: 0) > VIEW_CAP_CHARS
    // Editing only for small, complete, text files.
    val editable = canSave && load != null && !isBinary && !fileTruncated &&
        (originalText?.length ?: 0) <= EDIT_CAP_CHARS

    // ── Edit state ─────────────────────────────────────────────────
    var editing by remember { mutableStateOf(false) }
    var draft by remember(originalText) {
        mutableStateOf(TextFieldValue(originalText ?: ""))
    }
    val dirty = draft.text != (originalText ?: "")
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // ── View-mode highlighting (off-main, bounded + fail-safe) ─────
    val colors = defaultHighlightColors()
    var annotated by remember(displayText) { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(displayText, filename, editing, isBinary) {
        if (editing || isBinary) return@LaunchedEffect
        annotated = withContext(Dispatchers.Default) {
            // Highlight only modestly-sized text, and never let a tokenizer
            // failure on odd input crash the screen — fall back to plain.
            if (displayText.length <= HIGHLIGHT_CAP_CHARS)
                runCatching { CodeHighlighter.highlight(displayText, filename, colors) }
                    .getOrElse { AnnotatedString(displayText) }
            else AnnotatedString(displayText)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Column {
                        Text(
                            filename,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val lang = when {
                            isBinary -> "binary"
                            else -> CodeHighlighter.detectLanguage(filename, originalText)
                                ?.name?.lowercase()
                                ?: if (CodeHighlighter.isConfigLike(filename)) "config" else "plain"
                        }
                        val mode = when {
                            isBinary -> "not text"
                            editing && dirty -> "edit · unsaved"
                            editing -> "edit"
                            viewTruncated -> "view · truncated"
                            else -> "view"
                        }
                        Text(
                            "$lang · $mode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (editing && dirty) confirmDiscard = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    if (editable) {
                        if (!editing) {
                            IconButton(onClick = { editing = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = "edit")
                            }
                        } else {
                            // Save button — disabled when nothing to save
                            // (no diff) or a save is in flight.
                            IconButton(
                                enabled = dirty && !saving,
                                onClick = {
                                    saving = true
                                    saveError = null
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            writeRemote(serverId!!, remotePath!!, draft.text)
                                        }
                                        saving = false
                                        if (ok == null) {
                                            // Saved content is the new full, complete text.
                                            load = TextLoad(draft.text, binary = false, truncated = false)
                                            editing = false
                                        } else {
                                            saveError = ok
                                        }
                                    }
                                }
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(2.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        if (dirty) Icons.Filled.Save else Icons.Filled.Check,
                                        contentDescription = "save",
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
                .navigationBarsPadding()
        ) {
            when {
                loadError != null -> Text(
                    "Couldn't read file: $loadError",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                load == null -> Text(
                    "Loading…",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
                // Binary / non-text → never feed it to the text path. Offer
                // the system "open with…" instead.
                isBinary -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Not a text file",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "\"$filename\" isn't a text file, so it can't be shown in the editor. " +
                            "Open it with an app that handles this file type.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    OutlinedButton(onClick = { openExternally(ctx, uri, filename) }) {
                        Text("Open with another app")
                    }
                }
                editing -> {
                    // Edit mode with live syntax highlighting via
                    // VisualTransformation. Only reachable for small,
                    // complete files (see `editable`), so re-tokenising on
                    // each keystroke stays cheap.
                    val highlightTransformation = remember(filename, colors) {
                        androidx.compose.ui.text.input.VisualTransformation { input ->
                            val out = runCatching {
                                CodeHighlighter.highlight(input.text, filename, colors)
                            }.getOrElse { AnnotatedString(input.text) }
                            androidx.compose.ui.text.input.TransformedText(
                                out,
                                androidx.compose.ui.text.input.OffsetMapping.Identity,
                            )
                        }
                    }
                    val hScroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(hScroll)
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                MaterialTheme.colorScheme.primary
                            ),
                            visualTransformation = highlightTransformation,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                        )
                    }
                }
                else -> {
                    // View mode: capped display, highlighted-if-small, plain
                    // otherwise. `annotated` is null until the (off-main)
                    // highlight lands — show plain text meanwhile, no gap.
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (viewTruncated) {
                            Text(
                                "// large file — showing the first ${displayText.length / 1024} KB. " +
                                    "Open with another app for the whole file.",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        val vScroll = rememberScrollState()
                        val hScroll = rememberScrollState()
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(vScroll)
                        ) {
                            Text(
                                text = annotated ?: AnnotatedString(displayText),
                                modifier = Modifier
                                    .horizontalScroll(hScroll)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .fillMaxWidth(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            saveError?.let { err ->
                AlertDialog(
                    onDismissRequest = { saveError = null },
                    title = { Text("Couldn't save") },
                    text = { Text(err) },
                    confirmButton = {
                        TextButton(onClick = { saveError = null }) { Text("OK") }
                    },
                )
            }
            if (confirmDiscard) {
                AlertDialog(
                    onDismissRequest = { confirmDiscard = false },
                    title = { Text("Discard changes?") },
                    text = { Text("Unsaved edits to \"$filename\" will be lost.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDiscard = false
                            onBack()
                        }) { Text("Discard") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
                    },
                )
            }
        }
    }
}

/**
 * Bounded, fail-safe read for the viewer. Pulls at most [READ_CAP_BYTES]
 * off the stream, sniffs the first 64 KB for binary content (a NUL byte or
 * a high ratio of control bytes), and decodes as UTF-8 (lossy — never
 * throws). Returns [TextLoad.binary] = true for non-text so the caller
 * shows the "open with…" path instead of garbage.
 */
private fun loadTextFile(ctx: Context, uri: Uri): TextLoad {
    val stream = ctx.contentResolver.openInputStream(uri) ?: return TextLoad("", false, false)
    return stream.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        var more = false
        while (total < READ_CAP_BYTES) {
            val n = s.read(chunk)
            if (n < 0) break
            val take = minOf(n, READ_CAP_BYTES - total)
            out.write(chunk, 0, take)
            total += take
            if (take < n) { more = true; break }
        }
        if (!more && total >= READ_CAP_BYTES) more = s.read() >= 0
        val bytes = out.toByteArray()
        val sample = minOf(bytes.size, 64 * 1024)
        var nonText = 0
        var nul = false
        var i = 0
        while (i < sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) { nul = true; break }
            // Allow tab(9), LF(10), CR(13) and printable (>=32); flag the rest.
            if (b < 9 || b == 11 || b == 12 || (b in 14..31) || b == 127) nonText++
            i++
        }
        val binary = nul || (sample > 0 && nonText.toLong() * 100L / sample >= 5L)
        if (binary) TextLoad("", binary = true, truncated = more)
        else TextLoad(String(bytes, Charsets.UTF_8), binary = false, truncated = more)
    }
}

/** Hand the file to the system's "open with…" chooser via ACTION_VIEW. */
private fun openExternally(ctx: Context, uri: Uri, filename: String) {
    val ext = filename.substringAfterLast('.', "").lowercase()
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    SilentlyTry.fired("SshAi-TextViewer", "open non-text externally") { ctx.startActivity(intent) }
}

/**
 * Write [content] to [remotePath] on [serverId] over the pooled SSH client.
 * Returns null on success, an error string otherwise.
 *
 * Uses `cat > escapedPath` with stdin = content bytes. The pool already
 * holds the authenticated SSHClient; we just open a fresh channel. No
 * fallback to a brand-new connection — if the pool isn't live, we error
 * out and prompt the user to reopen the chat first.
 */
private suspend fun writeRemote(
    serverId: String,
    remotePath: String,
    content: String,
): String? = runCatching {
    val pool = ServiceLocator.sshConnectionPool
    val client = pool.peek(serverId)
        ?: return@runCatching "SSH session isn't open. Reopen the chat with this server and try again."
    if (!client.isConnected) {
        return@runCatching "SSH connection dropped. Reopen the chat and save again."
    }
    val escaped = shellEscape(remotePath)
    val session = client.startSession()
    try {
        val cmd = session.exec("bash -lc " + shellEscape("cat > $escaped"))
        cmd.outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        cmd.join(30, java.util.concurrent.TimeUnit.SECONDS)
        val exit = cmd.exitStatus
        if (exit != null && exit != 0) {
            "Server returned exit=$exit. You may not have write permission for $remotePath."
        } else null
    } finally {
        SilentlyTry.fired("SshAi-TextViewer", "close save ssh session") { session.close() }
    }
}.getOrElse { it.message ?: it.javaClass.simpleName }
