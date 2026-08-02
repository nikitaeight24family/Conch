package ai.eight24family.conch.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

/**
 * Syntax-highlighter wrapper around `dev.snipme:highlights:1.1.0`.
 *
 * Two responsibilities:
 *  - Detect the source language from a filename / extension.
 *  - Run the library against the file's text and map its token spans
 *    onto a Compose [AnnotatedString].
 *
 * The library ships 17 lexers (Kotlin / Java / Rust / Go / JS / TS /
 * Python / Ruby / Shell / Swift / PHP / C / C++ / Dart / Perl / C# /
 * CoffeeScript). For file types it doesn't natively support we degrade:
 *   - JSON          → reuse JAVASCRIPT (close-enough syntax)
 *   - YAML / TOML   → minimal in-house tokenizer (`#` comments,
 *                     `"..."` / `'...'` strings, integer/float
 *                     literals). Good enough to read a config without
 *                     pretending we've implemented a full parser.
 *   - .conf / .ini  → same minimal tokenizer.
 *   - anything else → plain text, no highlighting.
 */
object CodeHighlighter {

    /**
     * Pick a [SyntaxLanguage] for a given filename. Returns `null`
     * when we don't have a real lexer; callers should fall back to
     * the minimal tokenizer or plain text.
     *
     * If [text] is non-null and the filename has no usable extension
     * (`codex`, `bash_profile`, etc.), the first line is checked for
     * a `#!` shebang to recover JS / Python / Ruby / Shell / Perl /
     * PHP detection. Without shebang fallback every shebang-
     * launched script with no `.ext` rendered as undecorated plain
     * text — exactly the symptom (the highlighter wasn't being
     * engaged at all).
     */
    fun detectLanguage(filename: String, text: String? = null): SyntaxLanguage? {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isBlank() && !text.isNullOrEmpty()) {
            val firstLine = text.lineSequence().firstOrNull().orEmpty()
            if (firstLine.startsWith("#!")) {
                val shebang = firstLine.lowercase()
                return when {
                    "node" in shebang -> SyntaxLanguage.JAVASCRIPT
                    "python" in shebang -> SyntaxLanguage.PYTHON
                    "ruby" in shebang -> SyntaxLanguage.RUBY
                    "perl" in shebang -> SyntaxLanguage.PERL
                    "php" in shebang -> SyntaxLanguage.PHP
                    "bash" in shebang -> SyntaxLanguage.SHELL
                    "/sh" in shebang || shebang.endsWith(" sh") -> SyntaxLanguage.SHELL
                    "zsh" in shebang -> SyntaxLanguage.SHELL
                    "fish" in shebang -> SyntaxLanguage.SHELL
                    "swift" in shebang -> SyntaxLanguage.SWIFT
                    else -> null
                }
            }
        }
        return when (ext) {
            "kt", "kts" -> SyntaxLanguage.KOTLIN
            "java" -> SyntaxLanguage.JAVA
            "rs" -> SyntaxLanguage.RUST
            "go" -> SyntaxLanguage.GO
            "js", "mjs", "cjs", "jsx" -> SyntaxLanguage.JAVASCRIPT
            "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
            "py", "pyi" -> SyntaxLanguage.PYTHON
            "rb" -> SyntaxLanguage.RUBY
            "sh", "bash", "zsh", "fish" -> SyntaxLanguage.SHELL
            "swift" -> SyntaxLanguage.SWIFT
            "php" -> SyntaxLanguage.PHP
            "c", "h" -> SyntaxLanguage.C
            "cpp", "cc", "cxx", "hpp", "hxx" -> SyntaxLanguage.CPP
            "dart" -> SyntaxLanguage.DART
            "pl", "pm" -> SyntaxLanguage.PERL
            "cs" -> SyntaxLanguage.CSHARP
            "coffee" -> SyntaxLanguage.COFFEESCRIPT
            // JSON has no dedicated lexer in the lib — JS handles it
            // (`true`/`false`/`null` keywords + string/number literals
            // match well enough). Same idea for JSONC.
            "json", "jsonc", "json5" -> SyntaxLanguage.JAVASCRIPT
            // Friendly aliases for config-ish files.
            else -> null
        }
    }

    /**
     * True for filenames our minimal tokenizer should pick up when
     * [detectLanguage] returns null. Keeps the viewer's "is this a
     * code file" branch simple.
     */
    fun isConfigLike(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in setOf("yaml", "yml", "toml", "conf", "ini", "cfg", "env", "properties")
    }

    /**
     * Render [text] as a Compose [AnnotatedString] with syntax
     * highlighting applied.
     *
     * `themeColors` is sampled from MaterialTheme at the call site
     * (this helper is non-composable so it can run off the main
     * thread for large files).
     */
    fun highlight(
        text: String,
        filename: String,
        themeColors: HighlightColors,
    ): AnnotatedString {
        val language = detectLanguage(filename, text)
        if (language != null) {
            return runHighlights(text, language, themeColors)
        }
        if (isConfigLike(filename)) {
            return minimalTokenize(text, themeColors)
        }
        return AnnotatedString(text)
    }

    private fun runHighlights(
        text: String,
        language: SyntaxLanguage,
        c: HighlightColors,
    ): AnnotatedString {
        // The library guards against blank/empty input internally,
        // but we keep an early return so the Highlights builder
        // doesn't allocate a code-structure for an empty string.
        if (text.isEmpty()) return AnnotatedString("")
        // `darcula()` is the dark blue theme that maps closest to
        // our app's cyan-on-near-black palette. The token COLORS
        // baked into the theme are ignored — we re-map every
        // `ColorHighlight` onto our [HighlightColors] bundle so
        // the viewer stays in the app's colour system regardless
        // of which theme the library defaults to.
        val highlights = Highlights.Builder()
            .code(text)
            .language(language)
            .theme(SyntaxThemes.darcula())
            .build()
            .getHighlights()
        return buildAnnotatedString {
            append(text)
            for (h in highlights) {
                val (start, end, style) = mapHighlight(h, c) ?: continue
                if (start in 0..text.length && end in 0..text.length && end > start) {
                    addStyle(style, start, end)
                }
            }
        }
    }

    /**
     * Very simple fallback tokenizer for YAML/TOML/conf/ini:
     *  - `#` to end-of-line → comment
     *  - `"..."` / `'...'` → string
     *  - integer / float literals → number
     *  - leading `key:` or `key =` → key
     *
     * Not a real parser. Just enough visual structure that a config
     * doesn't render as one undifferentiated wall of text.
     */
    private fun minimalTokenize(text: String, c: HighlightColors): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString("")
        return buildAnnotatedString {
            append(text)
            // Comments — `#` through end of line.
            Regex("(?m)#[^\n]*").findAll(text).forEach {
                addStyle(SpanStyle(color = c.comment), it.range.first, it.range.last + 1)
            }
            // Strings — double- and single-quoted.
            Regex("(?s)\"(?:\\\\.|[^\"\\\\])*\"").findAll(text).forEach {
                addStyle(SpanStyle(color = c.string), it.range.first, it.range.last + 1)
            }
            Regex("(?s)'(?:\\\\.|[^'\\\\])*'").findAll(text).forEach {
                addStyle(SpanStyle(color = c.string), it.range.first, it.range.last + 1)
            }
            // Numeric literals — int / float / hex.
            Regex("(?<![A-Za-z_])(?:-?\\d+\\.\\d+|-?\\d+|0x[0-9a-fA-F]+)").findAll(text).forEach {
                addStyle(SpanStyle(color = c.number), it.range.first, it.range.last + 1)
            }
            // TOML / YAML keys — leading-of-line identifier followed by `:` or `=`.
            Regex("(?m)^\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*[:=]").findAll(text).forEach { m ->
                val g = m.groups[1] ?: return@forEach
                addStyle(
                    SpanStyle(color = c.keyword, fontWeight = FontWeight.SemiBold),
                    g.range.first, g.range.last + 1,
                )
            }
        }
    }

    /**
     * Translate one `CodeHighlight` from the library into a Compose
     * `[start, end), SpanStyle` triple.
     *
     * The library emits two subclasses: [ColorHighlight] (a colored
     * token — keyword/string/number/comment/etc., distinguished by
     * the bundled `color` value which we MAP onto our app palette
     * rather than honour as-is, so the viewer stays in our colour
     * system) and [BoldHighlight] (just a bold flag, no colour
     * change of its own).
     *
     * Color → role mapping uses approximate hue ranges of the
     * Darcula theme the lib ships:
     *   - greenish      → comment
     *   - orange/yellow → string / number literals
     *   - blue / cyan   → keyword
     *   - everything else → identifier (default body text colour)
     */
    private fun mapHighlight(
        h: Any,
        c: HighlightColors,
    ): Triple<Int, Int, SpanStyle>? = when (h) {
        is ColorHighlight -> {
            val style = SpanStyle(color = mapThemeColor(h.rgb, c))
            Triple(h.location.start, h.location.end, style)
        }
        is BoldHighlight -> {
            val style = SpanStyle(fontWeight = FontWeight.Bold, color = c.keyword)
            Triple(h.location.start, h.location.end, style)
        }
        else -> null
    }

    /**
     * Heuristically classify Darcula's syntax-token RGB into one of
     * our role colours. Darcula's palette is small and stable —
     * roughly: keywords are blueish (cc7832 / 6897bb), strings are
     * green-yellow (6a8759), numbers are bluish (6897bb), comments
     * are grey (808080). We decode by hue band rather than match
     * exact hex so future theme tweaks don't break us.
     */
    private fun mapThemeColor(rgb: Int, c: HighlightColors): Color {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        // Greys → comment.
        if (kotlin.math.abs(r - g) < 16 && kotlin.math.abs(g - b) < 16) {
            return c.comment
        }
        // Strong green → string.
        if (g > r + 20 && g > b + 20) return c.string
        // Warm orange/yellow → also string-ish (Darcula uses orange
        // for some literal types).
        if (r > 180 && g > 90 && b < 100) return c.string
        // Strong blue → keyword.
        if (b > r + 30 || b > g + 30) return c.keyword
        // Reddish → number (Darcula doesn't really use red, but other
        // themes might, and this falls through naturally to keyword
        // otherwise — pick number for the rare case).
        if (r > g + 40 && r > b + 40) return c.number
        return c.identifier
    }
}

/**
 * Theme-derived colour bundle for the highlighter. Sampled at the
 * call site from MaterialTheme so the highlighter stays
 * non-composable (and so a large file can be tokenised on
 * Dispatchers.Default without crossing the composition boundary).
 */
data class HighlightColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val identifier: Color,
)

@Composable
fun defaultHighlightColors(): HighlightColors {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    // Detect light theme by background luminance rather than
    // `isSystemInDarkTheme()` — the app supports user-overridden
    // theme mode in prefs, and the system flag would lie.
    val bg = cs.background
    val isLight = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) > 0.5f
    return if (isLight) {
        // Light theme: darker, more saturated tones so syntax
        // stays readable against the cream/off-white background.
        // Mirrors the GitHub-light convention which most devs
        // recognise — saturation high enough to pop, hue spread
        // wide enough to tell keyword/string/number apart at a
        // glance.
        HighlightColors(
            keyword = Color(0xFF0033B3),     // deep blue (control flow)
            string = Color(0xFF067D17),      // dark green (literals)
            number = Color(0xFFAF6700),      // ochre / burnt orange
            comment = Color(0xFF7A7A7A),     // medium grey
            identifier = cs.onSurface,
        )
    } else {
        HighlightColors(
            keyword = cs.primary,            // cyan accent
            string = Color(0xFF7DD3A8),      // soft green
            number = Color(0xFFFFB454),      // amber
            comment = cs.outline,
            identifier = cs.onSurface,
        )
    }
}
