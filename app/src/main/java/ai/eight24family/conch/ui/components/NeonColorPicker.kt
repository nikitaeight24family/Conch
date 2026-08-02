package ai.eight24family.conch.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.util.SilentlyTry
import kotlin.math.roundToInt

/** Cyberpunk color presets — quick access to common neons. */
private val NEON_PRESETS = listOf(
    "#00E5FF", // cyan
    "#FF2BD6", // magenta
    "#A8FF60", // lime
    "#FFC857", // amber
    "#FF4365", // hot pink
    "#7C5CFF", // electric purple
    "#FF6E00", // sunset orange
    "#39FF14"  // chartreuse
)

/**
 * Compact HSV color picker designed for the cyberpunk-CLI look.
 *
 * - Saturation/Value square (drag to pick)
 * - Hue strip below (drag horizontally)
 * - Hex field with live two-way binding
 * - Preset chips for the common neons
 */
@Composable
fun NeonColorPicker(
    initialHex: String,
    onHexChange: (String) -> Unit,
    /** Override the default neon preset palette. Used by the
     *  Background picker to insert pure-black (#000000) as a
     *  pixel-off OLED option — that swatch is useless as an
     *  accent (would hide every cyan-tinted element). */
    presets: List<String> = NEON_PRESETS,
) {
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    // CRITICAL: `remember` keyed on Unit, NOT on `initialHex`. Each
    // SV/Hue gesture writes hsv → LaunchedEffect emits onHexChange →
    // parent's accentHex flow round-trips back through `initialHex`.
    // If `remember` were keyed on `initialHex`, that round-trip
    // would invalidate the MutableState delegate every gesture
    // frame, the pointerInput block would keep a dead reference,
    // and the picker would freeze after the first tap.
    var hsv by remember { mutableStateOf(hexToHsv(initialHex)) }
    var hexField by remember { mutableStateOf(initialHex) }

    // Pull EXTERNAL changes back in (preset chip elsewhere, hex
    // typed in another path, prefs migration) without recreating
    // the state. Only re-sync if the inbound hex doesn't already
    // match our local view — otherwise we'd fight our own
    // outbound updates.
    LaunchedEffect(initialHex) {
        if (!initialHex.equals(hsvToHex(hsv), ignoreCase = true)) {
            hsv = hexToHsv(initialHex)
            hexField = initialHex
        }
    }

    LaunchedEffect(hsv) {
        val newHex = hsvToHex(hsv)
        if (!newHex.equals(hexField, ignoreCase = true)) {
            hexField = newHex
        }
        onHexChange(newHex)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── SV picker ──
        SvPicker(
            hue = hsv.h,
            saturation = hsv.s,
            value = hsv.v,
            onChange = { s, v -> hsv = hsv.copy(s = s, v = v) },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2.4f)
        )

        // ── Hue strip ──
        HueStrip(
            hue = hsv.h,
            onHueChange = { hsv = hsv.copy(h = it) },
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        )

        // ── Hex field + swatch ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RectangleShape,
                color = parseHex(hexField),
                border = BorderStroke(1.dp, outline),
                modifier = Modifier.size(40.dp)
            ) {}
            OutlinedTextField(
                value = hexField,
                onValueChange = { input ->
                    hexField = input.uppercase().take(7)
                    if (Regex("^#[0-9A-Fa-f]{6}$").matches(hexField)) {
                        hsv = hexToHsv(hexField)
                        onHexChange(hexField)
                    }
                },
                singleLine = true,
                shape = RectangleShape,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { /* no-op */ }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = onSurface,
                    unfocusedTextColor = onSurface,
                ),
                modifier = Modifier.weight(1f, fill = true)
            )
        }

        // ── Preset chips ──
        Text(
            "presets",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { hex ->
                val color = parseHex(hex)
                val isSelected = hex.equals(hexField, ignoreCase = true)
                Surface(
                    shape = RectangleShape,
                    color = color,
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) onSurface else outline
                    ),
                    onClick = {
                        hexField = hex.uppercase()
                        hsv = hexToHsv(hex)
                        onHexChange(hex)
                    },
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .height(28.dp)
                ) {}
            }
        }
    }
}

@Composable
private fun SvPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (s: Float, v: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val outline = MaterialTheme.colorScheme.outline
    val pure = Color.hsv(hue.coerceIn(0f, 359.999f), 1f, 1f)
    Box(
        modifier = modifier
            .background(Color.White)
            .background(Brush.horizontalGradient(listOf(Color.White, pure)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .border(BorderStroke(1.dp, outline), RectangleShape)
            // ONE pointerInput handling both tap and drag — splitting
            // them across two `.pointerInput` blocks let `detectTapGestures`
            // consume the initial down before `detectDragGestures`
            // ever got a chance, so subsequent finger movement never
            // produced an update. `awaitEachGesture` keeps the down
            // event AND every drag move in the same scope, so the
            // accent colour follows the finger live.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    updateSv(down.position, size, onChange)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        updateSv(change.position, size, onChange)
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = saturation * size.width
            val y = (1f - value) * size.height
            drawCircle(
                color = Color.Black,
                radius = 7f,
                center = Offset(x, y),
                style = Stroke(width = 2f)
            )
            drawCircle(
                color = Color.White,
                radius = 5f,
                center = Offset(x, y),
                style = Stroke(width = 2f)
            )
        }
    }
}

@Composable
private fun HueStrip(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val outline = MaterialTheme.colorScheme.outline
    val rainbow = Brush.horizontalGradient(
        listOf(
            Color.hsv(0f, 1f, 1f),
            Color.hsv(60f, 1f, 1f),
            Color.hsv(120f, 1f, 1f),
            Color.hsv(180f, 1f, 1f),
            Color.hsv(240f, 1f, 1f),
            Color.hsv(300f, 1f, 1f),
            Color.hsv(359.999f, 1f, 1f),
        )
    )
    Box(
        modifier = modifier
            .background(rainbow)
            .border(BorderStroke(1.dp, outline), RectangleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    updateHue(down.position.x, size.width, onHueChange)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        updateHue(change.position.x, size.width, onHueChange)
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = (hue / 360f) * size.width
            drawLine(
                color = Color.Black,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 5f
            )
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f
            )
        }
    }
}

private fun updateSv(
    position: Offset,
    size: IntSize,
    onChange: (s: Float, v: Float) -> Unit,
) {
    if (size.width <= 0 || size.height <= 0) return
    val s = (position.x / size.width).coerceIn(0f, 1f)
    val v = 1f - (position.y / size.height).coerceIn(0f, 1f)
    onChange(s, v)
}

private fun updateHue(
    x: Float,
    width: Int,
    onHueChange: (Float) -> Unit,
) {
    if (width <= 0) return
    onHueChange((x / width).coerceIn(0f, 1f) * 360f)
}

// ────────────────────────── Color math ──────────────────────────

private data class Hsv(val h: Float, val s: Float, val v: Float)

private fun hexToHsv(hex: String): Hsv {
    val c = parseHex(hex)
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> (60 * ((g - b) / d) + 360) % 360
        max == g -> 60 * ((b - r) / d) + 120
        else -> 60 * ((r - g) / d) + 240
    }
    val s = if (max == 0f) 0f else d / max
    return Hsv(h, s, max)
}

private fun hsvToHex(hsv: Hsv): String {
    val c = Color.hsv(hsv.h.coerceIn(0f, 359.999f), hsv.s.coerceIn(0f, 1f), hsv.v.coerceIn(0f, 1f))
    val r = (c.red * 255).roundToInt()
    val g = (c.green * 255).roundToInt()
    val b = (c.blue * 255).roundToInt()
    return "#%02X%02X%02X".format(r, g, b)
}

private fun parseHex(hex: String): Color = SilentlyTry.loggedOrElse("SshAi-ColorPicker", "parse hex color", Color(0xFF00E5FF)) {
    val cleaned = hex.trim().removePrefix("#")
    val v = cleaned.toLong(16) or 0xFF000000
    Color(v.toInt())
}
