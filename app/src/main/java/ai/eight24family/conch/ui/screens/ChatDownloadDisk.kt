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

internal fun diskInlineKey(path: String) = "dl:$path"

/**
 * Tiny disk icon rendered inline next to a path the agent mentioned.
 * Tap → ChatViewModel.downloadFile streams the file from the server
 * into the phone's Downloads folder. Visual states:
 *
 *  • idle       — disk icon + a thin animated neon gradient outline so
 *                 it reads as clickable, not decorative.
 *  • working    — circular progress (determinate when stat returned a
 *                 size, indeterminate otherwise).
 *  • done       — filled cyan disk; a re-tap re-runs the download (the
 *                 user explicitly asked again).
 *  • failed     — error tint; tap to retry.
 *
 * The whole thing fits in a 1.6em x 1.4em inline placeholder so it
 * sits on the same baseline as the surrounding bodyLarge text.
 */
// TODO(1.1.0): touch target for the inline download disk is only ~22×20dp,
// below the 48dp WCAG/MDC recommendation. Wrapping the outer Box in an
// IconButton (or enlarging to 48dp) breaks inline-text flow because this
// renders inside an InlineTextContent placeholder sized in `em` to align
// with the surrounding bodyLarge baseline. Need to either (a) move the
// download affordance out of the inline glyph and into a separate action
// row under the message, or (b) use `Modifier.minimumInteractiveComponentSize`
// with a transparent extended tap area that doesn't affect the inline box.
@Composable
internal fun DownloadDisk(path: String, vm: ChatViewModel) {
    val downloads by vm.downloads.collectAsState()
    val state = downloads[path]
    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val errorColor = MaterialTheme.colorScheme.error
    val ctx = LocalContext.current

    // Static border — no shimmer animation. The earlier diagonal
    // gradient sweep ("disk-neon") looked busy and snapped at the
    // end of each cycle, which is exactly the kind of decoration
    // that nets negative attention without earning its keep. State
    // color is enough to signal what's actionable.
    val borderColor = when (state) {
        is ChatViewModel.DownloadStatus.Failed -> errorColor
        is ChatViewModel.DownloadStatus.Done -> cyan
        is ChatViewModel.DownloadStatus.Downloading -> cyan.copy(alpha = 0.55f)
        null -> cyan
    }

    // Wrap the disk box + size label in a single clickable Row so
    // tapping anywhere in the inline slot triggers the same action.
    // Done state surfaces the byte count to the right of the
    // checkmark; other states show only the box (size is unknown
    // until the download finishes).
    val diskHaptic = ai.eight24family.conch.ui.haptic.LocalSshAiHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // ⚠ TOUCH AREA, NOT LAYOUT SIZE. The visible disk is ~22×20dp inside an
            // `em`-sized inline placeholder, so growing the box would break the
            // baseline alignment this whole file exists to keep. This expands only
            // what the finger has to hit, to the 48dp minimum — and it matters
            // because this is the ONLY way to retry a failed download, so a miss
            // costs the user the exact action they came for (audit, 2026-08-30).
            .minimumInteractiveComponentSize()
            .clickable(enabled = state !is ChatViewModel.DownloadStatus.Downloading) {
                // Discrete Tap on the disk icon. Mirrors the
                // physical feel of pressing a real button —
                // important here because the icon is small and the
                // user might wonder if their finger landed on it.
                diskHaptic.perform(ai.eight24family.conch.ui.haptic.SshAiHaptic.Tap)
                when (val s = state) {
                    is ChatViewModel.DownloadStatus.Done -> {
                        // Open routing lives in the ViewModel — it
                        // looks up a remembered "where to open .ext"
                        // preference and either acts directly OR
                        // emits a prompt event for ChatScreen's
                        // bottom sheet to handle. Filename / mime /
                        // size are computed at the click site so
                        // the VM stays Android-free.
                        val mime = ctx.contentResolver.getType(s.localUri) ?: "*/*"
                        vm.openDownloadedFile(s.localUri, path, mime, s.sizeBytes)
                    }
                    else -> vm.downloadFile(path)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(width = 22.dp, height = 20.dp)
                .clip(RectangleShape)
                .border(1.dp, borderColor, RectangleShape)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
        when (state) {
            is ChatViewModel.DownloadStatus.Downloading -> {
                val p = state.progress
                if (p < 0f) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = cyan,
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = cyan,
                    )
                }
            }
            is ChatViewModel.DownloadStatus.Done -> {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "saved to ${state.displayLocation}",
                    tint = cyan,
                    modifier = Modifier.size(14.dp),
                )
            }
            is ChatViewModel.DownloadStatus.Failed -> {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = "download failed: ${state.reason} (tap to retry)",
                    tint = errorColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            null -> {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = "download $path",
                    tint = cyan,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        }
        // Size label — Done state shows the on-disk size of the
        // downloaded file; everything else shows the remote size
        // we probed via `stat -c %s` when confirming the file
        // exists. The user wanted the size visible BEFORE tapping
        // download too, not just after, so they can tell apart a
        // 200B config from a 50MB log.
        val fileSizes by vm.fileSizes.collectAsState()
        val displayBytes: Long? = when (val s = state) {
            is ChatViewModel.DownloadStatus.Done ->
                s.sizeBytes.takeIf { it >= 0 } ?: fileSizes[path]
            is ChatViewModel.DownloadStatus.Failed -> fileSizes[path]
            is ChatViewModel.DownloadStatus.Downloading -> fileSizes[path]
            null -> fileSizes[path]
        }
        if (displayBytes != null && displayBytes >= 0) {
            Text(
                text = formatBytes(displayBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp, end = 2.dp),
            )
        }
    }
}

/**
 * Bottom sheet that asks "where to open this file" — our built-in
 * viewer, an external app, or the share sheet — with a "remember
 * for .ext files" checkbox. Fired by `vm.openFilePrompt` when no
 * preference is stored yet for the file's extension.
 *
 * Three square icon buttons in one row, label under each. Lean and
 * compact — the previous full-width stacked buttons took too much
 * sheet height for what amounts to a 3-way pick.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun OpenFileChooserSheet(
    request: ChatViewModel.OpenFilePromptRequest,
    onPick: (choice: String, rememberChoice: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var rememberChoice by remember(request) { mutableStateOf(false) }
    // Offer "Open here" for files we have an in-app viewer for: text-like files
    // (the bounded, binary-sniffed text viewer) and PDFs (the paginated PDF
    // reader). Images, archives, and binaries get just "Other app" / "Share" —
    // we have no viewer for them, so "Open here" would only disappoint.
    val canOpenHere = isTextLike(request.mime, request.extension) ||
        isPdf(request.mime, request.extension)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                request.filename,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sizeLabel = if (request.sizeBytes >= 0) formatBytes(request.sizeBytes) else null
            val subtitle = listOfNotNull(
                request.extension.takeIf { it.isNotBlank() }?.let { ".$it" },
                sizeLabel,
                request.mime.takeIf { it != "*/*" && it != "application/octet-stream" },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            // Three square icon buttons in a row. Each is its own
            // tile with icon + label below — same visual weight, so
            // none of them reads as "the recommended" choice unlike
            // the previous filled-vs-outlined hierarchy.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceAround,
            ) {
                if (canOpenHere) {
                    OpenChoiceTile(
                        icon = Icons.Filled.Edit,
                        label = "Open here",
                        onClick = { onPick("internal", rememberChoice) },
                    )
                }
                OpenChoiceTile(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    label = "Other app",
                    onClick = { onPick("external", rememberChoice) },
                )
                OpenChoiceTile(
                    icon = Icons.Filled.Share,
                    label = "Share",
                    onClick = { onPick("share", rememberChoice) },
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { rememberChoice = !rememberChoice }
                    .padding(vertical = 4.dp),
            ) {
                androidx.compose.material3.Checkbox(
                    checked = rememberChoice,
                    onCheckedChange = { rememberChoice = it },
                )
                val rememberLabel = if (request.extension.isNotBlank())
                    "Remember for .${request.extension} files"
                else "Remember for files with no extension"
                Text(
                    rememberLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * One square tile in the chooser-sheet's action row. Icon at top,
 * label below. Bordered box so it reads as a button even at small
 * sizes.
 */
@Composable
internal fun OpenChoiceTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(12.dp),
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * Human-readable byte size. Stops at GB — beyond that the user has
 * bigger problems than a tooltip label.
 *
 * **Locale-pinned to US** — without this Russian users saw `165,5
 * KB` (comma as decimal separator) which then got clipped to
 * `165,` by the narrow inline-content placeholder, looking like a
 * unit-less number. English- style decimals also keep the label
 * width tight enough that the suffix never gets cropped.
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return ""
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes < kb -> "${bytes}B"
        bytes < mb -> "%.1fKB".format(java.util.Locale.US, bytes / kb)
        bytes < gb -> "%.1fMB".format(java.util.Locale.US, bytes / mb)
        else -> "%.2fGB".format(java.util.Locale.US, bytes / gb)
    }
}

/** Extensions our in-app text viewer can meaningfully show. Anything not
 *  here (and not a text mime) only gets "Other app" / "Share". */
private val TEXT_LIKE_EXTENSIONS = setOf(
    "txt", "text", "md", "markdown", "rst", "adoc", "log", "csv", "tsv",
    "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "conf",
    "cfg", "config", "properties", "env", "plist", "gradle", "lock",
    "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql", "graphql", "gql",
    "py", "pyw", "rb", "php", "pl", "pm", "lua", "r", "tcl", "groovy",
    "kt", "kts", "java", "scala", "clj", "cljs",
    "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte",
    "css", "scss", "sass", "less", "html", "htm", "xhtml", "svg",
    "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm", "cs", "go", "rs",
    "swift", "dart", "ex", "exs", "erl", "hrl", "hs", "ml", "mli", "fs",
    "nim", "zig", "v", "diff", "patch", "srt", "vtt", "tex", "bib",
    "make", "mk", "cmake", "dockerfile", "gitignore", "gitattributes", "editorconfig",
)

/**
 * Whether a downloaded file is worth offering "Open here" (our text viewer)
 * for — text by mime or by a known source/config extension. Everything else
 * (images, PDFs, archives, binaries) only gets the system "Other app" /
 * "Share" options. The viewer itself is crash-safe if something slips
 * through; this just avoids tempting the user with a viewer that would only
 * say "not a text file".
 */
/** A PDF — gets the in-app paginated PDF reader ([PdfViewerScreen]) under
 *  "Open here", separate from the text viewer. */
private fun isPdf(mime: String, ext: String): Boolean =
    mime.equals("application/pdf", ignoreCase = true) || ext.equals("pdf", ignoreCase = true)

private fun isTextLike(mime: String, ext: String): Boolean {
    if (mime.startsWith("text/")) return true
    val m = mime.lowercase()
    if (m in setOf(
            "application/json", "application/ld+json", "application/manifest+json",
            "application/xml", "application/javascript", "application/ecmascript",
            "application/x-sh", "application/x-shellscript", "application/x-yaml",
            "application/yaml", "application/toml", "application/sql",
            "application/x-ndjson", "image/svg+xml",
        )
    ) return true
    return ext.lowercase() in TEXT_LIKE_EXTENSIONS
}
