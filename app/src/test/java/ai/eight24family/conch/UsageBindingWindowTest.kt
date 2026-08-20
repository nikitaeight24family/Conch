package ai.eight24family.conch

import ai.eight24family.conch.agent.RateLimitReset
import ai.eight24family.conch.agent.UsageProbe
import ai.eight24family.conch.agent.UsageReport
import ai.eight24family.conch.agent.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collapsed usage bar shows ONE window. It must be the one that actually
 * blocks you.
 *
 * On 2026-08-19 the chat carried the CLI's own refusal — "You've hit your
 * session limit · resets 2:40pm" — while the bar read **15%**, because the bar
 * always took the FIRST window (5-hour) and the pegged window was a different
 * one. Both halves of that failure are pinned here: the window choice, and the
 * text detector that lets the app believe the CLI immediately instead of waiting
 * for a poll.
 */
class UsageBindingWindowTest {

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

    @Test
    fun `the most-used window wins, not the first one`() {
        val report = UsageReport(
            windows = listOf(
                w("5-hour · all models", 15),
                w("Weekly · all models", 100),
            ),
        )
        assertEquals(100, report.primary?.percent)
        assertEquals("Weekly · all models", report.primary?.label)
    }

    @Test
    fun `the oauth-apps bucket can be the binding one`() {
        // The third-party-OAuth-app window is the bucket OUR access path lives
        // in — invisible in the CLI's own display, and exactly what pegged while
        // the 5-hour window looked fine.
        val report = UsageReport(
            windows = listOf(
                w("5-hour · all models", 15),
                w("Weekly · all models", 22),
                w("Seven day oauth apps", 100),
            ),
        )
        assertEquals("Seven day oauth apps", report.primary?.label)
    }

    @Test
    fun `an aggregate window beats a per-model one at the same utilisation`() {
        val report = UsageReport(
            windows = listOf(
                w("Opus · weekly", 90, perModel = true),
                w("Weekly · all models", 90),
            ),
        )
        assertEquals("Weekly · all models", report.primary?.label)
    }

    @Test
    fun `equal utilisation prefers the window that resets soonest`() {
        val soon = 1_000_000L
        val later = 9_000_000L
        val report = UsageReport(
            windows = listOf(
                w("Weekly · all models", 80, resetMs = later),
                w("5-hour · all models", 80, resetMs = soon),
            ),
        )
        assertEquals(soon, report.primary?.resetAtEpochMs)
    }

    @Test
    fun `a codex report ranks by what is CONSUMED, not by the number shown`() {
        // Codex counts DOWN: percent/fraction are "remaining", usedFraction is
        // the truth. Ranking on `percent` would pick the healthiest window.
        val fresh = UsageWindow(
            label = "Weekly", fraction = 0.95f, percent = 95, resetText = "",
            usedFraction = 0.05f,
        )
        val spent = UsageWindow(
            label = "5-hour", fraction = 0.02f, percent = 2, resetText = "",
            usedFraction = 0.98f,
        )
        val report = UsageReport(windows = listOf(fresh, spent))
        assertEquals("5-hour", report.primary?.label)
    }

    @Test
    fun `empty report has no primary`() {
        assertEquals(null, UsageReport(windows = emptyList()).primary)
    }

    @Test
    fun `the CLI's own refusal lines are recognised`() {
        for (line in listOf(
            "You've hit your session limit · resets 2:40pm (America/Los_Angeles)",
            "Usage limit reached · continuing automatically at 2:40pm",
            "Claude usage limit reached. Your limit will reset at 3pm (America/New_York)",
            "5-hour limit reached — switch model or wait",
            "You've hit your weekly limit",
        )) {
            assertTrue("missed: $line", RateLimitReset.mentionsLimitHit(line))
        }
    }

    @Test
    fun `talking about limits is not hitting one`() {
        // This session's own replies discuss limits at length; an assistant
        // message must never be able to paint the bar as blocked.
        for (line in listOf(
            null,
            "",
            "close to the limit · seven day · 85%",
            "Your 5-hour window is at 82% — worth pacing.",
            "the limit resets 5pm, so you have room",
            "we should add a limit reached banner later",
        )) {
            assertFalse("false trip: $line", RateLimitReset.mentionsLimitHit(line))
        }
    }
}
