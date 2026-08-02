package ai.eight24family.conch.util

/**
 * Find file-path mentions in agent reply text. The intent is narrow:
 * decorate references to concrete files the user can download — not to
 * highlight every `/`-shaped substring. We err on the side of false
 * negatives over false positives, because the chat UI hangs a clickable
 * disk icon off every match.
 *
 * What counts:
 *   • Absolute Linux paths starting with `/` and at least one more segment
 *     (e.g. `/tmp/foo.json`, `/var/log/x.log`, `/home/u/src/main.kt`).
 *   • Tilde-rooted paths (e.g. `~/.claude/agents/code-reviewer.md`).
 *   • Either form may sit inside backticks; markdown code fences are
 *     transparent to us — we run on the post-render text where backticks
 *     are gone.
 *
 * What we deliberately reject:
 *   • URLs (`https://…`, `git://…`, anything containing `://`) — they
 *     match the absolute-path shape but aren't local files.
 *   • Glob patterns (contain `*` or `?`) — not concrete files.
 *   • Single-segment paths like `/tmp` or `/etc` — too generic; the
 *     download would either fail or produce a dump of useless directory
 *     bytes via cat.
 *   • Paths embedded inside an identifier (preceded by `\w` or `/`) —
 *     prevents matching `something/X` substrings out of `foo/something/X`.
 */
object PathDetector {

    data class Match(
        /** Inclusive start index in the original text. */
        val start: Int,
        /** Exclusive end index. */
        val end: Int,
        val path: String,
    )

    // Absolute paths: `/seg1/seg2[/.]` with optional trailing extension.
    // Required: at least 2 segments (the leading `/` plus one more). Each
    // segment starts with `[A-Za-z0-9_.]` — `.` is allowed because
    // dot-directories are extremely common (`.codex`, `.claude`, `.ssh`,
    // `.config`). The earlier regex banned a leading dot and silently
    // broke disk-icon detection for every dotfile path the agent
    // mentioned.
    private val absRegex = Regex(
        // Disallow preceding word/path char so we don't match middle-of-token.
        "(?<![\\w/])" +
        // Path body: `/seg(/seg)+` where seg is a portable filename segment.
        "(/[A-Za-z0-9_.][A-Za-z0-9._\\-+]*(?:/[A-Za-z0-9_.][A-Za-z0-9._\\-+]*)+)"
    )

    private val tildeRegex = Regex(
        "(?<![\\w/])" +
        "(~/(?:[A-Za-z0-9_.][A-Za-z0-9._\\-+]*/)*[A-Za-z0-9_.][A-Za-z0-9._\\-+]*)"
    )

    fun detect(text: String): List<Match> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Match>()
        sequenceOf(absRegex, tildeRegex).forEach { regex ->
            for (m in regex.findAll(text)) {
                val g = m.groups[1] ?: continue
                val raw = g.value
                val cleaned = trimTrailingPunctuation(raw)
                if (cleaned.length < 4) continue
                if (looksLikeUrl(cleaned)) continue
                if (looksLikeGlob(cleaned)) continue
                if (isShellRedirect(text, g.range.first)) continue
                // Reject `.` / `..` segments — they're path-traversal
                // tokens, not real file names. With the loosened
                // leading-dot rule the regex would otherwise let
                // `/foo/./bar` or `/x/../y` through.
                if (cleaned.split('/').any { it == "." || it == ".." }) continue
                val end = g.range.first + cleaned.length
                out += Match(g.range.first, end, cleaned)
            }
        }
        // Dedupe and sort. Two regexes can in principle match the same span;
        // dedupe by (start,end). Sorted ascending so the renderer can splice
        // matches into the AnnotatedString left-to-right in one pass.
        return out.distinctBy { it.start to it.end }.sortedBy { it.start }
    }

    /** Strip closing `.`, `,`, `:`, `;`, `)` etc. that punctuation tends to glue to a path. */
    private fun trimTrailingPunctuation(s: String): String {
        var end = s.length
        while (end > 0 && s[end - 1] in TRAILING_PUNCT) end--
        return s.substring(0, end)
    }

    private val TRAILING_PUNCT = charArrayOf('.', ',', ';', ':', ')', ']', '}', '"', '\'', '!', '?')

    private fun looksLikeUrl(p: String): Boolean = p.contains("://")

    private fun looksLikeGlob(p: String): Boolean =
        p.contains('*') || p.contains('?') || p.contains('[')

    /**
     * Filter out paths that appear right after `>` / `>>` / `<` — those are
     * shell redirects in code samples, not files we can actually fetch
     * (the path may not even exist yet at the time the agent wrote it).
     */
    private fun isShellRedirect(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && text[i] == ' ') i--
        if (i < 0) return false
        val c = text[i]
        return c == '>' || c == '<'
    }
}
