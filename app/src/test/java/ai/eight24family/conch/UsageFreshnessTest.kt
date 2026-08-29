package ai.eight24family.conch

import ai.eight24family.conch.agent.usageReadingSupersedes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The usage bar is painted by a ladder of sources on every refresh, one of which
 * reads state trusted up to an hour old. Left ungated it landed between two live
 * readings and the same window rendered 87 → 86 → 87 → 86 forever.
 */
class UsageFreshnessTest {

    @Test
    fun `a live reading always wins`() {
        // null = fetched just now.
        assertTrue(usageReadingSupersedes(currentAt = 1_000L, incomingAt = null))
        assertTrue(usageReadingSupersedes(currentAt = null, incomingAt = null))
    }

    @Test
    fun `a stored reading never overwrites a live one`() {
        assertFalse(usageReadingSupersedes(currentAt = null, incomingAt = 1_787_972_815_324L))
    }

    @Test
    fun `a stored reading may replace an older stored one`() {
        assertTrue(usageReadingSupersedes(currentAt = 1_000L, incomingAt = 2_000L))
        assertTrue(usageReadingSupersedes(currentAt = 1_000L, incomingAt = 1_000L))
        assertFalse(usageReadingSupersedes(currentAt = 2_000L, incomingAt = 1_000L))
    }
}
