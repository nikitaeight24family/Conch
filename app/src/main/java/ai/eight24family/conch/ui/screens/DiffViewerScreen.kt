package ai.eight24family.conch.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DIFF_READ_CAP_BYTES = 2 * 1024 * 1024
// One colored AnnotatedString in a single Text node — cap the chars handed to
// Compose's text layout (a huge single node is what crashes). Beyond this we
// truncate + point at "open with another app", exactly like the text viewer.
private const val DIFF_VIEW_CAP_CHARS = 200_000

// GitHub-ish diff palette, readable on the dark cyberpunk theme.
private val ADDED = Color(0xFF3FB950)
private val REMOVED = Color(0xFFF85149)
private val HUNK = Color(0xFF58A6FF)
private val META = Color(0xFF8B949E)

private data class DiffRender(val text: AnnotatedString, val truncated: Boolean)

/**
 * Unified-diff / patch reader for `.diff` / `.patch` files: added lines green,
 * removed red, `@@` hunk headers blue, file/meta headers dimmed — so reviewing
 * what an agent changed (before committing from the train) reads at a glance
 * instead of as a wall of `+`/`-` text. Bounded read + char cap keep a giant
 * diff from OOM-ing; pure rendering, no third-party library. Share / Open-with
 * hand the patch elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffViewerScreen(
    uri: Uri,
    filename: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var render by remember(uri) { mutableStateOf<DiffRender?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val bounded = readBoundedUtf8(ctx, uri, DIFF_READ_CAP_BYTES)
                colorizeDiff(bounded.text, bounded.truncated)
            }.getOrNull()
        }
        if (r == null) failed = true else render = r
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
                    IconButton(onClick = { shareLocalFile(ctx, uri, "text/x-patch", filename) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { openLocalFileExternally(ctx, uri, "text/plain") }) {
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
                .padding(pad)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val r = render
            when {
                failed -> Text(
                    "Couldn't read this file.",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(32.dp),
                )
                r == null -> CircularProgressIndicator()
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    if (r.truncated) {
                        Text(
                            "Large diff — showing the first part. Open with another app for the whole file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(
                        text = r.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Build the colored diff body, bounded to [DIFF_VIEW_CAP_CHARS]. */
private fun colorizeDiff(src: String, readTruncated: Boolean): DiffRender {
    var truncated = readTruncated
    val annotated = buildAnnotatedString {
        var chars = 0
        for (line in src.lineSequence()) {
            if (chars + line.length + 1 > DIFF_VIEW_CAP_CHARS) {
                truncated = true
                break
            }
            val color = when {
                line.startsWith("@@") -> HUNK
                line.startsWith("+++") || line.startsWith("---") -> META
                line.startsWith("diff ") || line.startsWith("index ") ||
                    line.startsWith("new file") || line.startsWith("deleted file") ||
                    line.startsWith("rename ") || line.startsWith("similarity ") ||
                    line.startsWith("\\ No newline") -> META
                line.startsWith("+") -> ADDED
                line.startsWith("-") -> REMOVED
                else -> null
            }
            val weight = if (line.startsWith("@@")) FontWeight.Bold else null
            if (color != null || weight != null) {
                withStyle(SpanStyle(color = color ?: Color.Unspecified, fontWeight = weight)) {
                    append(line)
                }
            } else {
                append(line)
            }
            append('\n')
            chars += line.length + 1
        }
    }
    return DiffRender(annotated, truncated)
}
