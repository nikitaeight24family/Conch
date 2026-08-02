package ai.eight24family.conch.ui.screens

/**
 * Tiny URL extractor for the chat-rendering pipeline.
 *
 * **Why a local regex, not `android.text.util.Linkify`?**
 * - We feed the output to Compose's `LinkAnnotation.Url`, which lives
 *   purely in the `androidx.compose.ui.text` world — no `Spannable`,
 *   no platform `Linkify` API. We need `(start, end, url)` triples,
 *   nothing more.
 * - Linkify also matches phone numbers, addresses, emails — agent
 *   output is dense with file paths / hashes / version strings that
 *   trip those side-detectors. URL-only keeps false positives near
 *   zero.
 *
 * **Trailing-punctuation strip.** The agent often writes a URL
 * followed by `,` / `.` / `;` / `)` / `]` / `>` — those should NOT
 * be part of the clickable target. We greedily peel them off the
 * end before recording the match.
 *
 * **Markdown is already stripped** by `lightMarkdown` before this
 * runs (no `[label](url)` syntax to worry about — by the time we
 * see the text, the `[label]` portion has been peeled into a plain
 * label and the `(url)` part either stays in parens or was stripped
 * depending on the markdown rule). Worst case: we re-link the bare
 * URL, which is the desired behaviour anyway.
 */
object ChatLinkDetector {

    /**
     * Match `http://` or `https://` followed by anything that isn't
     * whitespace or a Compose layout-breaking control char. We keep
     * the regex deliberately permissive — modern URLs include
     * unicode hostnames, percent-encoded segments, fragment IDs,
     * `:`/`@` for userinfo, etc.
     */
    private val URL_REGEX = Regex(
        """\bhttps?://[^\s<>"`]+""",
        RegexOption.IGNORE_CASE,
    )

    /** End-of-URL punctuation that's almost always sentence/list
     *  punctuation, not part of the URL itself. Order doesn't matter
     *  — we keep stripping while the last char matches. */
    private val TRAILING_STRIP = setOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '\'', '"',
    )

    data class Match(
        /** Inclusive start offset in the source string. */
        val start: Int,
        /** Exclusive end offset. */
        val end: Int,
        /** The trimmed URL (no trailing punctuation). */
        val url: String,
    )

    fun detect(text: String): List<Match> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (m in URL_REGEX.findAll(text)) {
            var s = m.range.first
            var e = m.range.last + 1
            // Strip trailing punctuation. Also handle paired parens:
            // a `)` that closes a `(` INSIDE the URL stays — but a
            // `)` with no matching `(` inside should be peeled.
            while (e > s + 1 && text[e - 1] in TRAILING_STRIP) {
                // Special case: balance parens — if we have more `)`
                // than `(` in [s, e), peel the trailing `)`. Otherwise
                // it's likely part of the URL ("Wikipedia (URL)" style).
                val ch = text[e - 1]
                if (ch == ')' || ch == ']' || ch == '}') {
                    val opener = when (ch) { ')' -> '('; ']' -> '['; else -> '{' }
                    var balance = 0
                    for (i in s until e) {
                        when (text[i]) {
                            opener -> balance++
                            ch -> balance--
                        }
                    }
                    if (balance >= 0) break  // matched inside the URL, keep it
                }
                e--
            }
            if (e > s) out += Match(s, e, text.substring(s, e))
        }
        return out
    }
}
