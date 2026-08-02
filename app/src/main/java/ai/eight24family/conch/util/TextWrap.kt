package ai.eight24family.conch.util

/**
 * Defensive soft-wrapping for chat content.
 *
 * A tool argument / result can be a single pathologically long run with NO
 * whitespace — e.g. a ~2 KB JSON arg on one line, a 10 000-char base64 blob or
 * URL. Rendered in a `Text`, such an unbreakable run can blow past the line box
 * and corrupt the column's vertical metric (the "huge empty gap" bug: the item
 * reserves height for a layout that never paints right). We give the layout
 * explicit break opportunities by inserting a zero-width space (U+200B) into any
 * run of non-whitespace longer than [maxRun]. The glyph is invisible and
 * zero-width, so the text reads identically — and stripping every U+200B
 * reproduces the original byte-for-byte, so clipboard/copy paths keep using the
 * raw string.
 *
 * Pure + allocation-light: returns the input unchanged when nothing needs
 * wrapping (the common case), so it's cheap to call on every render.
 */
object TextWrap {

    const val ZWSP = '​'

    /** Insert [ZWSP] break opportunities into non-whitespace runs longer than
     *  [maxRun]. Idempotent on already-wrapped text (existing ZWSP resets the
     *  run, so re-wrapping never piles up extra breaks). */
    fun softWrapLongRuns(text: String, maxRun: Int = 64): String {
        require(maxRun >= 1)
        if (text.length <= maxRun) return text
        // Fast scan: if no run exceeds maxRun, return as-is (zero allocation).
        if (!hasLongRun(text, maxRun)) return text
        val sb = StringBuilder(text.length + text.length / maxRun + 1)
        var run = 0
        for (c in text) {
            if (c == ZWSP || c.isWhitespace()) {
                run = 0
                sb.append(c)
            } else {
                if (run == maxRun) {
                    sb.append(ZWSP)
                    run = 0
                }
                sb.append(c)
                run++
            }
        }
        return sb.toString()
    }

    private fun hasLongRun(text: String, maxRun: Int): Boolean {
        var run = 0
        for (c in text) {
            if (c == ZWSP || c.isWhitespace()) {
                run = 0
            } else {
                run++
                if (run > maxRun) return true
            }
        }
        return false
    }
}
