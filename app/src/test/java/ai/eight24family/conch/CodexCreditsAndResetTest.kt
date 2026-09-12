package ai.eight24family.conch

import ai.eight24family.conch.agent.UsageProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex's limit windows, and the rule that a reset time is the provider's
 * number or nothing.
 *
 * Both payloads are REAL, lifted from the owner's server on 2026-09-12: the
 * rollout's own snake_case record and a live `account/rateLimits/read`.
 *
 * ⛔ AND WHAT IS NOT HERE IS THE POINT. There is no synthetic "Credits" window.
 * Codex's own panel lists exactly two rows — and — and states a credit block
 * as a SENTENCE beneath them. A row invented here first replaced those windows
 * and then sat beside them, and neither is what the native app does. A window
 * is a percentage with a clock; credits are neither.
 */
class CodexCreditsAndResetTest {

    /** The rollout's own snake_case record — a snapshot of a past moment. */
    private val SNAPSHOT =
        """{"rate_limits":{"limit_id":"codex","primary":{"used_percent":16.0,""" +
            """"window_minutes":300,"resets_at":9999999999},"secondary":""" +
            """{"used_percent":2.0,"window_minutes":10080,"resets_at":9999999999},""" +
            """"credits":{"has_credits":false,"unlimited":false,"balance":null},""" +
            """"rate_limit_reached_type":"workspace_member_credits_depleted"}}"""

    /** The app-server's camelCase answer — the live one. */
    private val LIVE =
        """{"rateLimits":{"limitId":"codex","primary":{"usedPercent":16,""" +
            """"windowDurationMins":300,"resetsAt":9999999999},""" +
            """"secondary":{"usedPercent":2,"windowDurationMins":10080,""" +
            """"resetsAt":9999999999},""" +
            """"credits":{"hasCredits":false,"unlimited":false,"balance":null},""" +
            """"rateLimitReachedType":"workspace_member_credits_depleted"}}"""

    @Test
    fun `depleted credits are recognised in both spellings`() {
        assertTrue(UsageProbe.codexCreditsDepleted(SNAPSHOT))
        assertTrue(UsageProbe.codexCreditsDepleted(LIVE))
    }

    @Test
    fun `an account with credits is not depleted`() {
        val healthy =
            """{"rate_limits":{"limit_id":"codex","primary":{"used_percent":12.0,""" +
                """"window_minutes":300,"resets_at":9999999999},""" +
                """"credits":{"has_credits":true,"unlimited":false,"balance":null}}}"""
        assertTrue(!UsageProbe.codexCreditsDepleted(healthy))
    }

    /** The windows are the provider's, reported as it reports them — and they
     *  survive a depleted-credit account rather than being replaced by it. */
    @Test
    fun `the provider's own windows are what gets reported`() {
        val windows = UsageProbe.reportFromCodex(LIVE)?.windows.orEmpty()
        assertEquals(2, windows.size)
        // Codex counts DOWN: 16% used reads as 84% left, like its own panel.
        assertEquals(84, windows[0].percent)
        assertEquals(98, windows[1].percent)
        assertTrue("no invented row", windows.none { it.label.contains("Credit", true) })
    }

    /** A LIVE answer carries the countdown. */
    @Test
    fun `a live answer carries the provider's reset`() {
        val w = UsageProbe.reportFromCodex(LIVE)?.windows?.firstOrNull()
        assertEquals(9999999999L * 1000, w?.resetAtEpochMs)
    }

    /**
     * ⛔ A SNAPSHOT IS NOT AN ANSWER AT ALL. The rollout line records a past
     * moment and on 2026-09-12 it was wrong on BOTH axes at once: "1% left,
     * resets in 51 min" against the CLI's own "84%, 20:40", because the window
     * had rolled hours after the line was written. Every "why doesn't it match
     * the CLI" that day traced back to it. With no live answer the probe now
     * reports nothing, and the bar shows nothing — a state the user can read,
     * unlike a confident wrong number.
     */
    @Test
    fun `a session-file snapshot is not reported at all`() {
        assertNull("a snapshot must never reach the bar", UsageProbe.reportFromCodex(SNAPSHOT))
    }

    /**
     * ⛔ NO PROJECTION. A reset already past says nothing about the next one.
     * This used to be stepped forward by the window cadence and presented as
     * fact — the provider's number or none at all.
     */
    @Test
    fun `a reset already in the past is reported as unknown, never projected`() {
        val stale =
            """{"rateLimits":{"limitId":"codex","primary":{"usedPercent":50,""" +
                """"windowDurationMins":300,"resetsAt":1000000000},""" +
                """"credits":{"hasCredits":true}}}"""
        val w = UsageProbe.reportFromCodex(stale)?.windows?.firstOrNull()
        assertTrue("expected a window, got none", w != null)
        assertNull("a past reset must not be projected forward", w!!.resetAtEpochMs)
        assertEquals("", w.resetText)
    }
}
