package ai.eight24family.conch

import ai.eight24family.conch.agent.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/** The usage bar must count its reset DOWN live off the absolute reset time,
 *  not freeze the fetch-time string (user 2026-06-14: app stuck at "49m" while
 *  the desktop ticked to 14m). */
class UsageWindowResetTest {
    private fun win(resetAtEpochMs: Long?, fallback: String = "") =
        UsageWindow(label = "5h", fraction = 0.1f, percent = 10, resetText = fallback, resetAtEpochMs = resetAtEpochMs)

    @Test
    fun `live reset counts down from the absolute time`() {
        val now = 1_000_000_000_000L
        assertEquals("14m", win(now + 14 * 60_000L).resetTextLive(now))
        assertEquals("3h", win(now + 3 * 3600_000L).resetTextLive(now))
        assertEquals("2d", win(now + 2 * 86_400_000L).resetTextLive(now))
        // Already past → EMPTY, not a lying "now". A stale reset the account is still
        // sitting on (rate-limited, no turn finishing to refetch) must not read
        // "resets now" forever.
        assertEquals("", win(now - 5_000L).resetTextLive(now))
    }

    @Test
    fun `same window ticks down as the clock advances`() {
        val resetAt = 2_000_000_000_000L
        val w = win(resetAt)
        assertEquals("49m", w.resetTextLive(resetAt - 49 * 60_000L))
        assertEquals("14m", w.resetTextLive(resetAt - 14 * 60_000L))  // later clock → smaller number
    }

    @Test
    fun `no absolute anchor falls back to the fetch-time string`() {
        assertEquals("45m", win(resetAtEpochMs = null, fallback = "45m").resetTextLive(123L))
    }
}
