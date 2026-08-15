package ai.eight24family.conch

import ai.eight24family.conch.agent.UsageProbe
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A usage bar must not name a window the provider never described.
 *
 * The Codex bar's label was hard-coded to the payload KEY — `primary` was
 * called "5-hour limit" because that is Claude's primary window. On the user's
 * ChatGPT account `primary` carries `"window_minutes":10080`, i.e. SEVEN DAYS,
 * so the bar read "5-hour limit · resets Sun 9:03 PM (3d8h)" — a claim its own
 * reset time disproves (2026-08-06). The size is in the payload and was already
 * being read for the reset maths; the label comes from it now.
 */
class UsageWindowLabelTest {

    @Test
    fun `the window the user actually hit is named weekly`() {
        // 10080 minutes — verbatim from the user's rollout.
        assertEquals("Weekly limit", UsageProbe.durationWindowLabel(10_080L * 60))
    }

    @Test
    fun `a real five-hour window is still called five-hour`() {
        assertEquals("5-hour limit", UsageProbe.durationWindowLabel(5 * 3_600L))
    }

    @Test
    fun `common cadences read naturally`() {
        assertEquals("Daily limit", UsageProbe.durationWindowLabel(86_400L))
        assertEquals("2-week limit", UsageProbe.durationWindowLabel(14 * 86_400L))
        assertEquals("30-day limit", UsageProbe.durationWindowLabel(30 * 86_400L))
        assertEquals("1-hour limit", UsageProbe.durationWindowLabel(3_600L))
        assertEquals("30-minute limit", UsageProbe.durationWindowLabel(1_800L))
    }

    @Test
    fun `an unfamiliar cadence is described, never borrowed from another product`() {
        assertEquals("36-hour limit", UsageProbe.durationWindowLabel(36 * 3_600L))
        // Nothing we can phrase → say nothing about the duration.
        assertEquals("Usage limit", UsageProbe.durationWindowLabel(90L))
        assertEquals("Usage limit", UsageProbe.durationWindowLabel(0L))
    }
}
