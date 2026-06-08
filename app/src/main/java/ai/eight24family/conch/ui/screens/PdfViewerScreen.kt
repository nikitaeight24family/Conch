package ai.eight24family.conch.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// ── Safety caps — a giant PDF must never OOM or crash. ──
// LazyColumn only composes visible pages, so the rendered-bitmap set is bounded
// to what's on screen ± a little (off-screen items are disposed → GC'd). These
// cap the per-PAGE bitmap so a single huge page can't blow the heap either.
private const val MAX_PAGE_WIDTH_PX = 2048   // render width ceiling (screen is narrower anyway)
private const val MAX_PAGE_HEIGHT_PX = 6000  // tall pages clamp here; the image just letterboxes

private sealed interface PdfState {
    data object Loading : PdfState
    data class Ready(val renderer: PdfRenderer, val pageCount: Int) : PdfState
    data class Error(val message: String) : PdfState
}

/**
 * In-app PDF reader, built on the platform [PdfRenderer] (AOSP, no third-party
 * library). Pages render lazily off the main thread and one-at-a-time (the
 * renderer is NOT thread-safe and only allows a single open page), so a
 * thousand-page or multi-hundred-MB PDF scrolls without loading everything into
 * memory. Top bar shares the file (`ACTION_SEND`) or hands it to another app.
 *
 * The text viewer ([TextViewerScreen]) refuses non-text via a binary sniff;
 * this is the dedicated path for `application/pdf` so a PDF is never shown as
 * garbage text and never crashes the parser. Encrypted / corrupt PDFs surface
 * a clean "couldn't open" panel with an external-app fallback, never a crash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    uri: Uri,
    filename: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var state by remember(uri) { mutableStateOf<PdfState>(PdfState.Loading) }
    // Serializes every PdfRenderer call — openPage() throws if a page is already
    // open, and the renderer isn't safe across threads.
    val renderMutex = remember(uri) { Mutex() }

    DisposableEffect(uri) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        scope.launch {
            try {
                // MODE_READ_ONLY descriptor must be seekable — MediaStore /
                // FileProvider downloads are real files, so this is fine; a
                // non-seekable source (rare) throws → handled below.
                val fd = ctx.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("couldn't open the file")
                pfd = fd
                val r = PdfRenderer(fd)
                renderer = r
                state = PdfState.Ready(r, r.pageCount)
            } catch (t: Throwable) {
                android.util.Log.w("SshAi-PdfViewer", "open failed: ${t.javaClass.simpleName}: ${t.message}")
                state = PdfState.Error(
                    when {
                        t.message?.contains("password", true) == true ||
                            t.message?.contains("encrypt", true) == true ->
                            "This PDF is password-protected — open it in another app."
                        else -> "Couldn't open this PDF."
                    }
                )
            }
        }
        onDispose {
            scope.cancel()
            // Close under the same mutex so we never tear the renderer down
            // mid-render (use-after-free → native crash).
            CoroutineScope(Dispatchers.IO).launch {
                renderMutex.withLock {
                    SilentlyTry.fired("SshAi-PdfViewer", "close renderer") { renderer?.close() }
                    SilentlyTry.fired("SshAi-PdfViewer", "close fd") { pfd?.close() }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            val pageLabel = (state as? PdfState.Ready)?.let { " · ${it.pageCount} page${if (it.pageCount == 1) "" else "s"}" } ?: ""
            TopAppBar(
                title = {
                    Text(
                        filename + pageLabel,
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
                    IconButton(onClick = { sharePdf(ctx, uri, filename) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { openPdfExternally(ctx, uri) }) {
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
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is PdfState.Loading -> CircularProgressIndicator()
                is PdfState.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    OutlinedButton(onClick = { openPdfExternally(ctx, uri) }) {
                        Text("Open with another app")
                    }
                }
                is PdfState.Ready -> PdfPageList(s.renderer, s.pageCount, renderMutex)
            }
        }
    }
}

@Composable
private fun PdfPageList(renderer: PdfRenderer, pageCount: Int, mutex: Mutex) {
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtMost(MAX_PAGE_WIDTH_PX)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = pageCount, key = { it }) { index ->
                PdfPage(renderer, mutex, index, widthPx)
            }
        }
    }
}

/**
 * One page. Renders its bitmap off-main, behind [mutex], the first time it
 * composes; shows a sized placeholder until then so the LazyColumn keeps a
 * stable scroll extent. The bitmap is dropped when the item leaves composition
 * (LazyColumn disposal) → GC reclaims it, so only on-screen pages cost memory.
 */
@Composable
private fun PdfPage(renderer: PdfRenderer, mutex: Mutex, index: Int, widthPx: Int) {
    var bitmap by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
    // Page aspect ratio for the placeholder — read once (cheap, also serialized).
    var aspect by remember(index) { mutableStateOf(0.7f) }

    LaunchedEffect(index, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        val rendered = withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val scale = widthPx.toFloat() / page.width.coerceAtLeast(1)
                        val w = widthPx.coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceIn(1, MAX_PAGE_HEIGHT_PX)
                        aspect = w.toFloat() / h
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // PDFs assume a white page; without this transparent
                        // areas render black on dark themes.
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }.onFailure {
                    android.util.Log.w("SshAi-PdfViewer", "render page $index failed: ${it.message}")
                }.getOrNull()
            }
        }
        bitmap = rendered
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White),
        )
    } else {
        // Sized placeholder so the scrollbar doesn't jump as pages render in.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.2f, 3f))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

private fun sharePdf(ctx: Context, uri: Uri, filename: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, filename)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    SilentlyTry.fired("SshAi-PdfViewer", "share pdf") {
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openPdfExternally(ctx: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    SilentlyTry.fired("SshAi-PdfViewer", "open pdf externally") { ctx.startActivity(intent) }
}
