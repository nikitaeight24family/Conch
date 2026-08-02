package ai.eight24family.conch.ui.screens

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * Highlight scope carried via composition local from the chat root down
 * to every message renderer. Non-null means "the user opened this chat
 * by tapping a global-search hit — apply this query as a background
 * highlight, BUT ONLY in the specific message the user actually picked
 * (identified by [targetMsgId])". Highlighting every occurrence across
 * every message in the chat was the original behaviour and the user
 * pushed back
 *
 * [targetMsgId] is null until the chat has loaded enough messages for
 * the first-match search to resolve; renderers treat null as "don't
 * highlight yet" and re-evaluate on recomposition once it lands.
 */
internal data class SearchHighlightSpec(
    val query: String,
    val targetMsgId: String?,
    /** Char offset of the target match inside the raw message body
     * (msg.text). AssistantLine passes through) and tells
     * [applyHighlightOverlay] which to paint via a ±30-char sweep
     * around that hint. -1 = no specific occurrence — paint the
     * first match. */
    val targetCharOffset: Int = -1,
)

internal val LocalSearchHighlight = compositionLocalOf<SearchHighlightSpec?> { null }

/** Side-channel for the search-opened anchor pipeline. When set, the
 *  matched-message renderer attaches `onTextLayout = onLayout` to its
 *  Text so the layout result flows back up to ChatScreen — which then
 *  computes the y-offset of the matched LINE within the message and
 *  scrolls the LazyColumn so that line sits at viewport centre.
 *
 *  Centring at line level (not whole-message level) is critical: real
 *  assistant messages can be 3000+ px tall, four screen heights. Whole-
 *  message centring is a coin flip whether the matched substring is
 *  even visible. Telegram does the same line-precise centring on its
 *  long-message hits — this is what "Durov-grade" looks like at the
 *  pixel level. */
internal data class MatchAnchor(
    val msgId: String,
    /** First character offset of the search query inside the message's
     *  RAW body text, ignoring case. The caller (UserLine/AssistantLine)
     *  shifts this by any leading prefix it injected into the rendered
     *  Text (e.g. UserLine's "❯ " adds 2 chars) before calling
     *  [reportLayout]. */
    val charOffset: Int,
    /** Called by the matched message's Text via onTextLayout. The caller
     *  passes the [TextLayoutResult] and the additional offset that
     *  separates the start of the rendered text from the start of the
     *  raw body (e.g. UserLine = 2 for the "❯ " prefix, AssistantLine = 0). */
    val reportLayout: (
        result: TextLayoutResult,
        prefixLen: Int,
    ) -> Unit,
)

internal val LocalMatchAnchor = compositionLocalOf<MatchAnchor?> { null }

/** The id of the AgentMessage currently being rendered. Provided just
 *  above each TerminalLine call so leaf renderers (UserLine,
 *  AssistantLine) can compare themselves against
 *  [LocalSearchHighlight].targetMsgId without needing the message
 *  object as a parameter. */
internal val LocalCurrentMsgId = compositionLocalOf<String?> { null }

/** Wrap every case-insensitive occurrence of [query] inside [input] in
 *  a highlight SpanStyle. Returns [input] unchanged if [query] is null /
 *  blank / has no matches. Cheap: one indexOf walk, no regex.
 *
 *  Highlights stack on top of existing styles (markdown, links, etc.)
 *  by `append(input)` then `addStyle(..)` on the relevant ranges —
 *  AnnotatedString resolves overlapping span styles by merging, so
 *  bold markdown stays bold under a highlighted background. */
internal fun applyHighlightOverlay(
    input: AnnotatedString,
    query: String?,
    bg: Color,
    fg: Color,
    /** Raw body text the caller passed through lightMarkdown / link
     *  detection / path splice to produce [input]. Used to translate
     *  [rawCharOffset] (a position in raw text, computed by FTS at
     *  search time) into the corresponding position in [input.text]
     *  via parallel character walk — markdown markers like `**`/`*`/
     *  `` ` `` are dropped from the annotated text but the body
     *  content is preserved, so a forward walk that advances both
     *  pointers when chars match and only the raw pointer on mismatch
     *  yields an exact mapping. */
    rawText: String = "",
    rawCharOffset: Int = -1,
): AnnotatedString {
    if (query.isNullOrBlank()) return input
    val text = input.text
    val pos = if (rawCharOffset >= 0 && rawText.isNotEmpty()) {
        val mapped = rawToAnnotatedPos(rawText, rawCharOffset, input)
        // Verify: chars at [mapped..mapped+query.length) should equal
        // the query. If they do, we're done — exact landing, no sweep.
        // If they don't (uncommon — caller passed a mismatched raw or
        // some transformation other than markdown intervened), do a
        // small ±5 sweep as a safety net.
        if (mapped + query.length <= text.length &&
            text.regionMatches(mapped, query, 0, query.length, ignoreCase = true)
        ) {
            mapped
        } else {
            val windowStart = (mapped - 5).coerceAtLeast(0)
            val windowEnd = (mapped + 5).coerceAtMost(text.length)
            var best = -1
            var bestDist = Int.MAX_VALUE
            var p = text.indexOf(query, startIndex = windowStart, ignoreCase = true)
            while (p in 0..windowEnd) {
                val dist = kotlin.math.abs(p - mapped)
                if (dist < bestDist) { best = p; bestDist = dist }
                p = text.indexOf(query, startIndex = p + 1, ignoreCase = true)
            }
            if (best >= 0) best else text.indexOf(query, ignoreCase = true)
        }
    } else {
        text.indexOf(query, ignoreCase = true)
    }
    if (pos < 0) return input
    return buildAnnotatedString {
        append(input)
        addStyle(
            SpanStyle(
                background = bg,
                color = fg,
                fontWeight = FontWeight.Bold,
            ),
            pos,
            pos + query.length,
        )
    }
}

/** Walk [raw] and [annotated] in parallel to translate raw-text
 *  position [rawPos] into the corresponding annotated-text position.
 *
 *  Three transformations stand between `msg.text` and the rendered
 *  annotated string:
 *   1. `lightMarkdown` — drops markup chars (`**`/`*`/`` ` ``/`` ``` ``)
 *      and trims leading `\n` from fenced-code-block bodies. The
 *      stripped chars never carry body content matching user queries.
 *   2. `linkedBase` — identity on text content (adds LinkAnnotation
 *      only).
 *   3. `pathSplice` — `appendInlineContent(diskInlineKey(p), " ")`
 *      inserts a single space (the placeholder's alternate text) AFTER
 *      each detected file path. The annotated string carries an
 *      `INLINE_CONTENT_TAG` annotation at that single-char range.
 *
 *  Walk algorithm — for each step, three cases on `raw[r]` vs
 *  `annotated.text[a]`:
 *    A. Equal — advance both.
 *    B. Different, and `a` sits on an inline-content placeholder —
 *       advance `a` alone (annotated has an extra char from #3).
 *    C. Otherwise — advance `r` alone (raw has an extra markup char
 *       from #1).
 *
 *  This is O(rawPos). Returns the annotated position. */
internal fun rawToAnnotatedPos(
    raw: String,
    rawPos: Int,
    annotated: AnnotatedString,
): Int {
    if (rawPos <= 0) return 0
    val text = annotated.text
    var r = 0
    var a = 0
    while (r < rawPos && r < raw.length && a < text.length) {
        if (raw[r] == text[a]) {
            r++; a++
        } else if (isInlineContentAt(annotated, a)) {
            a++
        } else {
            r++
        }
    }
    return a
}

internal const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/** True if position [a] in [annotated] is covered by an inline-content
 *  annotation — i.e. it's a placeholder char inserted by
 *  `appendInlineContent`, not original body content. */
internal fun isInlineContentAt(
    annotated: AnnotatedString,
    a: Int,
): Boolean {
    if (a >= annotated.text.length) return false
    return annotated
        .getStringAnnotations(INLINE_CONTENT_TAG, a, a + 1)
        .isNotEmpty()
}
