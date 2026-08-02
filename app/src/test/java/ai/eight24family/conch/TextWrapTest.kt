package ai.eight24family.conch

import ai.eight24family.conch.util.TextWrap
import ai.eight24family.conch.util.TextWrap.ZWSP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repro + regression test for the "huge empty gap" bug at the LOGIC level.
 *
 * The visual symptom (a long unbreakable run breaking the chat column's
 * vertical metric / not painting) can't be asserted in this project's test
 * setup — Compose UI tests don't run here (Robolectric `noncompatWidthPixels`,
 * see SessionsScreenTest). So we lock down the testable contract: any run
 * longer than `maxRun` gets break opportunities, while the text round-trips
 * to the original (clipboard safety) and short/normal text is untouched.
 */
class TextWrapTest {

    @Test
    fun `short text returned unchanged and same instance`() {
        val s = "small tool arg"
        assertSame(s, TextWrap.softWrapLongRuns(s))
    }

    @Test
    fun `text with normal whitespace and no long run is untouched`() {
        val s = "{ \"a\": 1, \"b\": \"two words here\", \"c\": [1, 2, 3] }".repeat(20)
        // No single run exceeds 64 (spaces reset it), so it must pass through.
        assertFalse(s.contains(ZWSP))
        assertEquals(s, TextWrap.softWrapLongRuns(s, maxRun = 64))
    }

    @Test
    fun `4000-char single run without spaces gets break opportunities`() {
        val s = "a".repeat(4000)
        val w = TextWrap.softWrapLongRuns(s, maxRun = 64)
        assertTrue("expected ZWSP breaks inserted", w.contains(ZWSP))
        // No segment between breaks exceeds maxRun → the layout can always wrap.
        assertTrue(w.split(ZWSP).all { it.length <= 64 })
        // Round-trips: stripping the invisible breaks reproduces the original.
        assertEquals(s, w.replace(ZWSP.toString(), ""))
    }

    @Test
    fun `8kb single-line json wraps and round-trips`() {
        // One ~8KB line, no newlines, only a few spaces — mimics the bug's
        // "tool-result / system 5-10KB JSON one block" repro.
        val s = "{" + (0 until 400).joinToString(",") { "\"key$it\":\"${"v".repeat(15)}$it\"" } + "}"
        assertTrue("repro input should be multi-KB", s.length > 5000)
        val w = TextWrap.softWrapLongRuns(s, maxRun = 64)
        assertTrue(w.contains(ZWSP))
        assertTrue("no run may exceed maxRun after wrap",
            w.split(ZWSP).none { seg -> longestNonWsRun(seg) > 64 })
        assertEquals(s, w.replace(ZWSP.toString(), ""))
    }

    @Test
    fun `whitespace resets the run so wrapping respects existing breaks`() {
        val s = "x".repeat(40) + " " + "y".repeat(40) // two 40-runs, each < 64
        assertEquals(s, TextWrap.softWrapLongRuns(s, maxRun = 64))
    }

    @Test
    fun `re-wrapping already-wrapped text does not pile up extra breaks`() {
        val s = "z".repeat(500)
        val once = TextWrap.softWrapLongRuns(s, maxRun = 64)
        val twice = TextWrap.softWrapLongRuns(once, maxRun = 64)
        assertEquals(once, twice)
    }

    private fun longestNonWsRun(s: String): Int {
        var run = 0; var max = 0
        for (c in s) {
            if (c == ZWSP || c.isWhitespace()) run = 0 else { run++; if (run > max) max = run }
        }
        return max
    }
}
