package ai.eight24family.conch.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.eight24family.conch.util.SilentlyTry
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Markdown docs are small in practice (READMEs, plans). Cap the read so a
// pathological multi-MB "markdown" can't OOM the parser/renderer.
private const val MD_READ_CAP_BYTES = 2 * 1024 * 1024

/**
 * Rendered Markdown reader for `.md` / `.markdown` files: headings, lists,
 * tables, code blocks, links — the way an agent-authored README / plan is meant
 * to be read, not as raw `#`/`*` text. Reuses the project's existing
 * Multiplatform-Markdown-Renderer (already a dependency + already attributed in
 * the licenses screen — zero new deps). A top-bar toggle flips to raw source;
 * Share / Open-with hand the file off elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownViewerScreen(
    uri: Uri,
    filename: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var loaded by remember(uri) { mutableStateOf<BoundedText?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    var showRaw by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        loaded = withContext(Dispatchers.IO) {
            runCatching { readBoundedUtf8(ctx, uri, MD_READ_CAP_BYTES) }.getOrNull()
        }
        if (loaded == null) failed = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        filename,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRaw = !showRaw }) {
                        if (showRaw) Icon(Icons.Filled.Article, contentDescription = "Show rendered")
                        else Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Show raw source")
                    }
                    IconButton(onClick = { shareLocalFile(ctx, uri, "text/markdown", filename) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { openLocalFileExternally(ctx, uri, "text/markdown") }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open with another app")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentAlignment = Alignment.Center,
        ) {
            val data = loaded
            when {
                failed -> Text(
                    "Couldn't read this file.",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(32.dp),
                )
                data == null -> CircularProgressIndicator()
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (data.truncated) {
                        Text(
                            "Large file — showing the first ${MD_READ_CAP_BYTES / (1024 * 1024)} MB. Open with another app for the whole file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (showRaw) {
                        Text(
                            data.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        )
                    } else {
                        com.mikepenz.markdown.m3.Markdown(
                            content = data.text,
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
                                h1 = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.primary),
                                h2 = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.primary),
                                h3 = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary),
                                h4 = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.primary),
                                h5 = MaterialTheme.typography.titleSmall,
                                h6 = MaterialTheme.typography.titleSmall,
                                text = MaterialTheme.typography.bodyLarge,
                                paragraph = MaterialTheme.typography.bodyLarge,
                                code = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                quote = MaterialTheme.typography.bodyLarge,
                                bullet = MaterialTheme.typography.bodyLarge,
                                list = MaterialTheme.typography.bodyLarge,
                                ordered = MaterialTheme.typography.bodyLarge,
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ── Shared file-viewer IO + intents (used by Markdown + Diff viewers) ──

/** Result of a bounded read: decoded text + whether the file had more bytes. */
internal data class BoundedText(val text: String, val truncated: Boolean)

/**
 * Read at most [cap] bytes off [uri] and decode UTF-8 (lossy — never throws).
 * Bounds the read so a multi-GB file can't OOM. `truncated` = there was more.
 */
internal fun readBoundedUtf8(ctx: Context, uri: Uri, cap: Int): BoundedText {
    val stream = ctx.contentResolver.openInputStream(uri) ?: return BoundedText("", false)
    return stream.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        var more = false
        while (total < cap) {
            val n = s.read(chunk)
            if (n < 0) break
            val take = minOf(n, cap - total)
            out.write(chunk, 0, take)
            total += take
            if (take < n) { more = true; break }
        }
        if (!more && total >= cap) more = s.read() >= 0
        BoundedText(String(out.toByteArray(), Charsets.UTF_8), more)
    }
}

internal fun shareLocalFile(ctx: Context, uri: Uri, mime: String, filename: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, filename)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    SilentlyTry.fired("SshAi-Viewer", "share file") {
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal fun openLocalFileExternally(ctx: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    SilentlyTry.fired("SshAi-Viewer", "open file externally") { ctx.startActivity(intent) }
}
