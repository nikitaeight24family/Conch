package ai.eight24family.conch.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.eight24family.conch.util.SilentlyTry
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Drawing tool. Move = pan/zoom; the rest annotate. */
private enum class Tool { Move, Pen, Line, Arrow, Rect, Oval }

/** One annotation. `points` is used by Pen; `start`/`end` by the shape tools. */
private data class Annotation(
    val tool: Tool,
    val color: Color,
    val strokeW: Float,
    val points: List<Offset> = emptyList(),
    val start: Offset = Offset.Zero,
    val end: Offset = Offset.Zero,
)

private val PALETTE = listOf(
    Color(0xFFFF1744), Color(0xFFFF9100), Color(0xFFFFEA00), Color(0xFF00E676),
    Color(0xFF00E5FF), Color(0xFF2979FF), Color(0xFFD500F9), Color(0xFFFFFFFF),
    Color(0xFF000000),
)

/**
 * Full-screen image viewer + annotator. Pinch-zoom / pan in Move mode; draw
 * pencil / line / arrow / rectangle / oval in the other modes with a colour
 * palette + stroke width. Undo / clear / share (exports the annotated image).
 *
 * Coordinate model: the image fills a CONTENT box sized to its aspect ratio and
 * centred on screen. A single graphicsLayer (top-left transform origin) applies
 * zoom+pan to both the image and the annotation Canvas, so annotations stay
 * pinned to image features. Screen→content mapping is the exact inverse:
 *   content = (screen − contentOrigin − pan) / scale
 * Annotations are stored in content coordinates, so export is a 1:1 redraw at
 * the content box's pixel size.
 */
@Composable
internal fun ImageAnnotatorOverlay(
    image: ImageBitmap,
    filename: String,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val ctx = LocalContext.current
        var scale by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        var tool by remember { mutableStateOf(Tool.Pen) }
        var color by remember { mutableStateOf(PALETTE[0]) }
        var strokeW by remember { mutableFloatStateOf(8f) }
        val elements = remember { mutableStateListOf<Annotation>() }
        var current by remember { mutableStateOf<Annotation?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF2000000)),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val screenW = with(density) { maxWidth.toPx() }
                val screenH = with(density) { maxHeight.toPx() }
                // Content box = image fit to screen (aspect-correct), centred.
                val imgW = image.width.toFloat()
                val imgH = image.height.toFloat()
                val fit = min(screenW / imgW, screenH / imgH)
                val contentW = imgW * fit
                val contentH = imgH * fit
                val originX = (screenW - contentW) / 2f
                val originY = (screenH - contentH) / 2f
                val contentOrigin = Offset(originX, originY)

                fun toContent(screen: Offset): Offset = (screen - contentOrigin - pan) / scale

                Box(
                    modifier = Modifier
                        .size(
                            with(density) { contentW.toDp() },
                            with(density) { contentH.toDp() },
                        )
                        // Lay the content box at contentOrigin (centred), so its
                        // on-screen position MATCHES the screen→content formula
                        // `(screen − contentOrigin − pan)/scale`. BoxWithConstraints
                        // is TopStart by default — without this the box sat at
                        // (0,0) while the formula assumed centred → drawing landed
                        // offset from the finger.
                        .offset { androidx.compose.ui.unit.IntOffset(originX.toInt(), originY.toInt()) }
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = pan.x; translationY = pan.y
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(0.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // The actual offset of the content box on screen is
                    // contentOrigin (BoxWithConstraints centres us). The
                    // graphicsLayer pan is ADDED on top.
                    androidx.compose.foundation.Image(
                        bitmap = image,
                        contentDescription = filename,
                        modifier = Modifier.matchParentSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                    Canvas(modifier = Modifier.matchParentSize()) {
                        elements.forEach { drawAnnotation(it) }
                        current?.let { drawAnnotation(it) }
                    }
                }

                // Gesture layer — full-screen, on top. Move = transform; tools =
                // draw (mapping each screen point into content coordinates).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(tool) {
                            if (tool == Tool.Move) {
                                detectTransformGestures { centroid, panChange, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 8f)
                                    // Keep the gesture centroid pinned while zooming.
                                    val c = (centroid - contentOrigin - pan) / scale
                                    pan = centroid - contentOrigin - c * newScale + panChange
                                    scale = newScale
                                }
                            } else {
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        val p = toContent(pos)
                                        current = if (tool == Tool.Pen) {
                                            Annotation(tool, color, strokeW, points = listOf(p))
                                        } else {
                                            Annotation(tool, color, strokeW, start = p, end = p)
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        val p = toContent(change.position)
                                        val cur = current ?: return@detectDragGestures
                                        current = if (cur.tool == Tool.Pen) {
                                            cur.copy(points = cur.points + p)
                                        } else {
                                            cur.copy(end = p)
                                        }
                                    },
                                    onDragEnd = {
                                        current?.let { elements.add(it) }
                                        current = null
                                    },
                                    onDragCancel = { current = null },
                                )
                            }
                        },
                )

                // ── Top bar: close · undo · clear · share ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "close", tint = Color.White)
                    }
                    Row {
                        IconButton(
                            onClick = { if (elements.isNotEmpty()) elements.removeAt(elements.lastIndex) },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "undo", tint = Color.White)
                        }
                        IconButton(onClick = { elements.clear() }) {
                            Icon(Icons.Filled.Clear, contentDescription = "clear", tint = Color.White)
                        }
                        IconButton(onClick = {
                            SilentlyTry.fired("Conch-Annot", "share annotated image") {
                                val out = exportAnnotated(image, elements, contentW.toInt(), contentH.toInt())
                                shareBitmap(ctx, out, filename)
                            }
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "share", tint = Color.White)
                        }
                    }
                }

                // ── Bottom: tools · colours · stroke ──
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ToolButton("✋", tool == Tool.Move) { tool = Tool.Move }
                        ToolButton("✏", tool == Tool.Pen) { tool = Tool.Pen }
                        ToolButton("／", tool == Tool.Line) { tool = Tool.Line }
                        ToolButton("➜", tool == Tool.Arrow) { tool = Tool.Arrow }
                        ToolButton("▭", tool == Tool.Rect) { tool = Tool.Rect }
                        ToolButton("◯", tool == Tool.Oval) { tool = Tool.Oval }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PALETTE.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (c == color) 3.dp else 1.dp,
                                        color = if (c == color) MaterialTheme.colorScheme.primary else Color(0x66FFFFFF),
                                        shape = CircleShape,
                                    )
                                    .clickable { color = c },
                            )
                        }
                    }
                    Slider(
                        value = strokeW,
                        onValueChange = { strokeW = it },
                        valueRange = 2f..40f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(glyph: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** Render one annotation onto a Compose DrawScope (content coordinates). */
private fun DrawScope.drawAnnotation(a: Annotation) {
    val stroke = Stroke(width = a.strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    when (a.tool) {
        Tool.Pen -> {
            if (a.points.size < 2) {
                a.points.firstOrNull()?.let { drawCircle(a.color, a.strokeW / 2f, it) }
                return
            }
            val path = Path().apply {
                moveTo(a.points[0].x, a.points[0].y)
                for (i in 1 until a.points.size) lineTo(a.points[i].x, a.points[i].y)
            }
            drawPath(path, a.color, style = stroke)
        }
        Tool.Line -> drawLine(a.color, a.start, a.end, a.strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        Tool.Arrow -> {
            drawLine(a.color, a.start, a.end, a.strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val ang = atan2((a.end.y - a.start.y).toDouble(), (a.end.x - a.start.x).toDouble())
            val head = max(a.strokeW * 4f, 24f)
            val a1 = ang + Math.toRadians(150.0)
            val a2 = ang - Math.toRadians(150.0)
            drawLine(a.color, a.end, Offset(a.end.x + (head * cos(a1)).toFloat(), a.end.y + (head * sin(a1)).toFloat()), a.strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(a.color, a.end, Offset(a.end.x + (head * cos(a2)).toFloat(), a.end.y + (head * sin(a2)).toFloat()), a.strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Tool.Rect -> {
            val tl = Offset(min(a.start.x, a.end.x), min(a.start.y, a.end.y))
            val sz = Size(kotlin.math.abs(a.end.x - a.start.x), kotlin.math.abs(a.end.y - a.start.y))
            drawRect(a.color, topLeft = tl, size = sz, style = stroke)
        }
        Tool.Oval -> {
            val tl = Offset(min(a.start.x, a.end.x), min(a.start.y, a.end.y))
            val sz = Size(kotlin.math.abs(a.end.x - a.start.x), kotlin.math.abs(a.end.y - a.start.y))
            drawOval(a.color, topLeft = tl, size = sz, style = stroke)
        }
        Tool.Move -> {}
    }
}

/** Redraw the image + annotations into an Android bitmap for export. Content
 *  coordinates are in display px; the image is scaled by [fit] from its native
 *  pixels, so the export at [w]×[h] is a 1:1 copy of what's on screen. */
private fun exportAnnotated(
    image: ImageBitmap,
    annotations: List<Annotation>,
    w: Int,
    h: Int,
): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(max(1, w), max(1, h), android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawBitmap(
        image.asAndroidBitmap(),
        null,
        android.graphics.Rect(0, 0, w, h),
        null,
    )
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    for (a in annotations) {
        paint.color = a.color.toArgb()
        paint.strokeWidth = a.strokeW
        when (a.tool) {
            Tool.Pen -> {
                if (a.points.size >= 2) {
                    val p = android.graphics.Path()
                    p.moveTo(a.points[0].x, a.points[0].y)
                    for (i in 1 until a.points.size) p.lineTo(a.points[i].x, a.points[i].y)
                    canvas.drawPath(p, paint)
                }
            }
            Tool.Line -> canvas.drawLine(a.start.x, a.start.y, a.end.x, a.end.y, paint)
            Tool.Arrow -> {
                canvas.drawLine(a.start.x, a.start.y, a.end.x, a.end.y, paint)
                val ang = atan2((a.end.y - a.start.y).toDouble(), (a.end.x - a.start.x).toDouble())
                val head = max(a.strokeW * 4f, 24f)
                val a1 = ang + Math.toRadians(150.0); val a2 = ang - Math.toRadians(150.0)
                canvas.drawLine(a.end.x, a.end.y, a.end.x + (head * cos(a1)).toFloat(), a.end.y + (head * sin(a1)).toFloat(), paint)
                canvas.drawLine(a.end.x, a.end.y, a.end.x + (head * cos(a2)).toFloat(), a.end.y + (head * sin(a2)).toFloat(), paint)
            }
            Tool.Rect -> canvas.drawRect(min(a.start.x, a.end.x), min(a.start.y, a.end.y), max(a.start.x, a.end.x), max(a.start.y, a.end.y), paint)
            Tool.Oval -> canvas.drawOval(min(a.start.x, a.end.x), min(a.start.y, a.end.y), max(a.start.x, a.end.x), max(a.start.y, a.end.y), paint)
            Tool.Move -> {}
        }
    }
    return bmp
}

/** Save the annotated bitmap to Downloads/conch/ and fire the share sheet. */
private fun shareBitmap(ctx: android.content.Context, bmp: android.graphics.Bitmap, srcName: String) {
    val base = srcName.substringBeforeLast('.', srcName)
    val name = "${base}_annotated.png"
    val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/conch/")
        }
        val u = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        if (u != null) ctx.contentResolver.openOutputStream(u)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        u
    } else {
        val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val f = java.io.File(dir, name)
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        android.net.Uri.fromFile(f)
    }
    if (uri != null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, name).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
