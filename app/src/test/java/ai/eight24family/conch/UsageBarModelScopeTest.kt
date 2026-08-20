package ai.eight24family.conch

import ai.eight24family.conch.agent.UsageReport
import ai.eight24family.conch.agent.UsageWindow
import ai.eight24family.conch.agent.appliesTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collapsed bar must show a wall that is actually in front of THIS session.
 *
 * Two opposite failures, one rule. On 2026-08-19 the bar read a comfortable 15%
 * (the 5-hour window) while the CLI was refusing turns on a different, pegged
 * window. On 2026-08-20 the correction over-reached: an **Opus 5** chat read a
 * flat `100%` because the account's **Fable weekly** cap was spent — a cap that
 * cannot stop an Opus turn. Per-model caps are scoped to their model;
 * aggregates escalate only when they are genuinely near the wall, and then they
 * are NAMED.
 */
class UsageBarModelScopeTest {

    private fun w(label: String, used: Int, perModel: Boolean = false, resetMs: Long? = null) =
        UsageWindow(
            label = label,
            fraction = used / 100f,
            percent = used,
            resetText = "",
            usedFraction = used / 100f,
            resetAtEpochMs = resetMs,
            perModel = perModel,
        )

    /** The account in the 2026-08-20 screenshot: 5h 1%, weekly 75%, Fable 100%. */
    private val screenshot = UsageReport(
        windows = listOf(
            w("5-hour · all models", 1, resetMs = 4_000L),
            w("Weekly · all models", 75, resetMs = 900_000L),
            w("Fable · weekly", 100, perModel = true, resetMs = 899_000L),
        ),
    )

    @Test
    fun `an Opus chat is not told Fable's cap is spent`() {
        val pick = screenshot.barPick("claude-opus-5")
        assertEquals("5-hour · all models", pick?.window?.label)
        assertEquals(1, pick?.window?.percent)
        assertFalse("the working window needs no name", pick!!.escalated)
    }

    @Test
    fun `the same account on Fable DOES see the wall it is behind`() {
        val pick = screenshot.barPick("claude-fable-5")
        assertEquals("Fable · weekly", pick?.window?.label)
        assertTrue(pick!!.escalated)
        assertEquals("Fable weekly", pick.window.shortName)
    }

    @Test
    fun `a display label or a bare alias identifies the model just as well`() {
        for (spelling in listOf("Opus 5", "Opus 5 1M", "opus", "claude-opus-5[1m]")) {
            assertEquals(
                "spelling=$spelling",
                "5-hour · all models",
                screenshot.barPick(spelling)?.window?.label,
            )
        }
        for (spelling in listOf("Fable 5", "fable", "claude-fable-5")) {
            assertEquals("spelling=$spelling", "Fable · weekly", screenshot.barPick(spelling)?.window?.label)
        }
    }

    /** The 08-19 regression: an aggregate at the wall still takes the bar. */
    @Test
    fun `an aggregate window at the wall still wins, and says which one it is`() {
        val report = UsageReport(
            windows = listOf(
                w("5-hour · all models", 15, resetMs = 4_000L),
                w("Weekly · all models", 100, resetMs = 900_000L),
            ),
        )
        val pick = report.barPick("claude-opus-5")
        assertEquals("Weekly · all models", pick?.window?.label)
        assertTrue(pick!!.escalated)
        assertEquals("Weekly", pick.window.shortName)
    }

    @Test
    fun `the oauth-apps bucket our own access path lives in still escalates`() {
        val report = UsageReport(
            windows = listOf(
                w("5-hour · all models", 15, resetMs = 4_000L),
                w("Weekly · all models", 22, resetMs = 900_000L),
                w("Seven day oauth apps", 100, resetMs = 900_000L),
            ),
        )
        assertEquals("Seven day oauth apps", report.barPick("claude-opus-5")?.window?.label)
    }

    @Test
    fun `a weekly window merely getting full does not hijack the bar`() {
        // 75% is worth a colour and a row in the panel, not the headline number.
        val pick = screenshot.barPick("claude-opus-5")
        assertEquals(1, pick?.window?.percent)
    }

    @Test
    fun `an unknown model keeps every window eligible`() {
        // Nothing to attribute against: a hidden block is worse than a surprise.
        assertEquals("Fable · weekly", screenshot.barPick(null)?.window?.label)
        assertEquals("Fable · weekly", screenshot.barPick("")?.window?.label)
    }

    @Test
    fun `codex has no per-model layer and is unaffected`() {
        // Counts DOWN: percent is "remaining", usedFraction is the truth.
        val fresh = UsageWindow(
            label = "Weekly limit", fraction = 0.95f, percent = 95, resetText = "",
            usedFraction = 0.05f, resetAtEpochMs = 900_000L,
        )
        val spent = UsageWindow(
            label = "5-hour limit", fraction = 0.02f, percent = 2, resetText = "",
            usedFraction = 0.98f, resetAtEpochMs = 4_000L,
        )
        val pick = UsageReport(windows = listOf(fresh, spent)).barPick("gpt-5-codex")
        assertEquals("5-hour limit", pick?.window?.label)
        // It IS the anchor, so it carries no name.
        assertFalse(pick!!.escalated)
    }

    @Test
    fun `eligibility is per family, not per label text`() {
        val fable = w("Fable · weekly", 100, perModel = true)
        assertTrue(fable.appliesTo("claude-fable-5"))
        assertFalse(fable.appliesTo("claude-opus-5"))
        assertTrue("unknown model → cannot attribute → stays eligible", fable.appliesTo(null))
        assertTrue("aggregates always apply", w("Weekly · all models", 50).appliesTo("claude-opus-5"))
    }

    @Test
    fun `a report of nothing but foreign per-model caps still shows something`() {
        val report = UsageReport(windows = listOf(w("Fable · weekly", 100, perModel = true)))
        val pick = report.barPick("claude-opus-5")
        assertEquals("Fable · weekly", pick?.window?.label)
        assertTrue("named, because it is not this session's window", pick!!.escalated)
    }
}
