package ai.eight24family.conch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.eight24family.conch.ui.window.LocalAppWindowAdaptive

/**
 * Render a unified-diff blob as a colored, monospaced view. Two
 * presentations:
 *
 *  - **Side-by-side** (default at Medium+ pane widths): left column
 *    shows removed lines + paired context, right column shows added
 *    lines + paired context. Empty cells are drawn as muted strips so
 *    the eye can follow vertical alignment of `-` next to `+`. Pairs
 *    matched left-to-right by run, with the longer run leaving blanks
 *    on the shorter side — simple Myers-style alignment, no diff inside
 *    the diff. Looks great on tablet / DeX windows.
 *
 *  - **Inline** (default at Compact widths): single column with
 *    `+`/`-`/space prefixes and tinted backgrounds. Matches the look
 *    of `git diff` in a terminal but with proper color instead of ANSI
 *    escapes. The "good enough for a phone" fallback.
 *
 * Color choices target the cyberpunk-dark theme: a desaturated green
 * for additions, desaturated red for removals, both at ~25% alpha so
 * they tint without overwhelming the monospaced text underneath.
 * Foreground stays `onSurface` — no per-line text color, so diffs
 * remain legible at any contrast ratio.
 *
 * Each hunk is preceded by its `@@` header in a muted box, and each
 * file is preceded by its path in `primary` — both serve as visual
 * separators in multi-file diffs.
 *
 * Performance note: `LazyColumn` is deliberately NOT used here even
 * though some hunks have hundreds of lines. The parent `ToolResultLine`
 * already gates rendering on the user expanding the result, and rolling
 * our own scroll inside a chat's outer scroll causes the touch
 * conflict-of-interest problem (whose scroll wins?). Tall diffs simply
 * extend the chat surface — let the chat's own LazyColumn handle the
 * scroll math.
 */
@Composable
fun DiffView(rawText: String, modifier: Modifier = Modifier) {
    val files = remember(rawText) { UnifiedDiffParser.parse(rawText) }
    if (files.isEmpty()) {
        // Parser failed — fall back to a plain code block so the user
        // still sees something. ToolResultLine should have caught this
        // via `looksLikeDiff()`, but defensive belt-and-braces.
        CopyableCodeBlock(text = rawText, modifier = modifier)
        return
    }
    val adaptive = LocalAppWindowAdaptive.current
    // Side-by-side feels cramped under ~600dp of pane width, so we fall
    // back to inline at Compact. Medium / Expanded use the split.
    val sideBySide = adaptive.isMediumOrWider

    // Bound the height + scroll internally. A large diff (ToolResultLine passes
    // up to 64 KB) would otherwise render as one Column thousands of px tall —
    // past Compose's draw limit the item reserves the height but draws BLANK,
    // i.e. the half-screen empty gaps the user saw. heightIn precedes
    // verticalScroll so the scroller gets a bounded (not infinite) max height.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
    ) {
        files.forEachIndexed { idx, file ->
            if (idx > 0) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            }
            FileHeader(file)
            file.hunks.forEach { hunk ->
                HunkHeader(hunk.header)
                if (sideBySide) {
                    SideBySideHunk(hunk)
                } else {
                    InlineHunk(hunk)
                }
            }
        }
    }
}

@Composable
private fun FileHeader(file: DiffFile) {
    val cyan = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "▸",
            color = cyan,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            file.displayPath,
            color = cyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HunkHeader(header: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            header,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ──────── Inline ────────

@Composable
private fun InlineHunk(hunk: DiffHunk) {
    val hScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(hScroll),
    ) {
        hunk.lines.forEach { line ->
            InlineDiffLineRow(line)
        }
    }
}

@Composable
private fun InlineDiffLineRow(line: DiffLine) {
    val (bg, prefix, prefixColor) = lineDecoration(line.kind)
    Row(
        modifier = Modifier
            .background(bg)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            prefix,
            color = prefixColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(14.dp),
        )
        Text(
            line.content.ifEmpty { " " },
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

// ──────── Side-by-side ────────

@Composable
private fun SideBySideHunk(hunk: DiffHunk) {
    // Pair removes with adds in run order so they align horizontally.
    // The pairing is intentionally simple: walk the line list and
    // accumulate runs of `-`s and `+`s; flush a pair when the run ends
    // or a context line appears. The longer side leaves blanks on the
    // shorter side. Not perfect — a true side-by-side diff would
    // re-diff the runs to align edits inside lines — but `git
    // --side-by-side` does effectively this and it's been good enough
    // for two decades.
    data class Pair(val left: DiffLine?, val right: DiffLine?)
    val pairs = remember(hunk) {
        val out = mutableListOf<Pair>()
        val pending = mutableListOf<DiffLine>()  // removes waiting for adds
        val adds = mutableListOf<DiffLine>()
        fun flushPair() {
            val n = maxOf(pending.size, adds.size)
            for (i in 0 until n) {
                out += Pair(pending.getOrNull(i), adds.getOrNull(i))
            }
            pending.clear()
            adds.clear()
        }
        for (line in hunk.lines) {
            when (line.kind) {
                DiffLine.Kind.REMOVE -> pending += line
                DiffLine.Kind.ADD -> adds += line
                DiffLine.Kind.CONTEXT -> {
                    flushPair()
                    out += Pair(line, line)
                }
            }
        }
        flushPair()
        out
    }
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.5f)
                .horizontalScroll(leftScroll),
        ) {
            pairs.forEach { pair -> SideCell(pair.left, fallbackKind = DiffLine.Kind.REMOVE) }
        }
        Column(
            modifier = Modifier
                .weight(0.5f)
                .horizontalScroll(rightScroll),
        ) {
            pairs.forEach { pair -> SideCell(pair.right, fallbackKind = DiffLine.Kind.ADD) }
        }
    }
}

@Composable
private fun SideCell(line: DiffLine?, fallbackKind: DiffLine.Kind) {
    val effectiveKind = line?.kind ?: fallbackKind
    val (bg, prefix, prefixColor) = lineDecoration(effectiveKind)
    Row(
        modifier = Modifier
            .background(if (line == null) bg.copy(alpha = 0.1f) else bg)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            if (line == null) " " else prefix,
            color = prefixColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(14.dp),
        )
        Text(
            line?.content?.ifEmpty { " " } ?: " ",
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

// ──────── Color tokens ────────

/** Resolve background, line-marker glyph, and marker color for a diff
 *  line kind. Tuned for the cyberpunk-dark theme; alphas keep tints
 *  readable without flattening the underlying surface texture. */
@Composable
private fun lineDecoration(kind: DiffLine.Kind): Triple<Color, String, Color> {
    return when (kind) {
        DiffLine.Kind.ADD -> Triple(
            Color(0xFF1B5E20).copy(alpha = 0.28f),  // desaturated green
            "+",
            Color(0xFF66BB6A),
        )
        DiffLine.Kind.REMOVE -> Triple(
            Color(0xFFB71C1C).copy(alpha = 0.28f),  // desaturated red
            "-",
            Color(0xFFEF5350),
        )
        DiffLine.Kind.CONTEXT -> Triple(
            Color.Transparent,
            " ",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
