package ai.eight24family.conch

import ai.eight24family.conch.agent.RateLimitReset
import ai.eight24family.conch.agent.usageCountdownText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The CLI is the source of truth for a rate-limit reset — an inference-only
 * token can't read the usage endpoint, so the bar must parse "resets 8:30pm"
 * out of the CLI's own turn output instead of freezing on a stale "resets now".
 */
class RateLimitResetTest {

    private val la = ZoneId.of("America/Los_Angeles")
    private val ny = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")

    private fun epoch(z: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, z).toInstant().toEpochMilli()

    @Test
    fun `explicit zone in the message wins over the device zone`() {
        // 10am LA "now"; device zone deliberately UTC to prove the message zone
        // (America/Los_Angeles) is what's used.
        val now = epoch(la, 2026, 7, 16, 10, 0)
        val got = RateLimitReset.parse(
            "You've hit your session limit · resets 8:30pm (America/Los_Angeles)", now, utc,
        )
        assertEquals(epoch(la, 2026, 7, 16, 20, 30), got)
    }

    @Test
    fun `reset at Npm form (usage limit reached) parses`() {
        val now = epoch(ny, 2026, 7, 16, 9, 0)
        val got = RateLimitReset.parse(
            "Claude usage limit reached. Your limit will reset at 3pm (America/New_York)", now, utc,
        )
        assertEquals(epoch(ny, 2026, 7, 16, 15, 0), got)
    }

    @Test
    fun `a time already past today rolls to tomorrow`() {
        // 9pm LA now, reset "8:30pm" → the limit lifts at TOMORROW's 8:30pm.
        val now = epoch(la, 2026, 7, 16, 21, 0)
        val got = RateLimitReset.parse("resets 8:30pm (America/Los_Angeles)", now, utc)
        assertEquals(epoch(la, 2026, 7, 17, 20, 30), got)
    }

    @Test
    fun `no zone in the message falls back to the device zone`() {
        val now = epoch(la, 2026, 7, 16, 10, 0)
        val got = RateLimitReset.parse("resets 8:30pm", now, la)
        assertEquals(epoch(la, 2026, 7, 16, 20, 30), got)
    }

    @Test
    fun `relative form resets in Xh Ym`() {
        val now = 1_700_000_000_000L
        assertEquals(now + (3 * 3600 + 20 * 60) * 1000L,
            RateLimitReset.parse("session limit reached, resets in 3h 20m", now, la))
        assertEquals(now + 45 * 60 * 1000L,
            RateLimitReset.parse("resets in 45m", now, la))
        assertEquals(now + 2 * 3600 * 1000L,
            RateLimitReset.parse("resets in 2h", now, la))
    }

    @Test
    fun `am boundary noon and midnight`() {
        val now = epoch(la, 2026, 7, 16, 1, 0)
        // 12am = midnight (00:00) → already past 1am? no, 12am today is 00:00 <
        // 1am now → rolls to tomorrow 00:00.
        assertEquals(epoch(la, 2026, 7, 17, 0, 0),
            RateLimitReset.parse("resets 12:00am (America/Los_Angeles)", now, utc))
        // 12pm = noon.
        assertEquals(epoch(la, 2026, 7, 16, 12, 0),
            RateLimitReset.parse("resets 12:00pm (America/Los_Angeles)", now, utc))
    }

    @Test
    fun `prose without a real clock signal does not false-trip`() {
        val now = epoch(la, 2026, 7, 16, 10, 0)
        assertNull(RateLimitReset.parse("here's your answer, all done", now, la))
        assertNull(RateLimitReset.parse(null, now, la))
        // "reset ... to 5" — no minute, am/pm, or zone → too weak, ignored.
        assertNull(RateLimitReset.parse("I reset the value to 5 and moved on", now, la))
    }

    @Test
    fun `resetPhrase extracts the verbatim clause for a human message`() {
        assertEquals(
            "resets 8:30pm (America/Los_Angeles)",
            RateLimitReset.resetPhrase(
                "You've hit your session limit · resets 8:30pm (America/Los_Angeles)"),
        )
        assertEquals("resets in 3h 20m", RateLimitReset.resetPhrase("limit, resets in 3h 20m"))
        assertNull(RateLimitReset.resetPhrase("no reset clause here"))
    }

    @Test
    fun `countdown text is empty when past, formatted otherwise`() {
        // The core of the fix: a stale / here-now reset renders EMPTY, never the
        // lying "now" the user saw hang forever.
        assertEquals("", usageCountdownText(0))
        assertEquals("", usageCountdownText(-5))
        assertEquals("45m", usageCountdownText(45 * 60L))
        assertEquals("2h40m", usageCountdownText(2 * 3600L + 40 * 60L))
        assertEquals("3h", usageCountdownText(3 * 3600L))
        assertEquals("2d", usageCountdownText(2 * 86_400L))
        // Days carry the hour remainder, same as hours carry minutes. The real
        // report: Thursday 12:53 → weekly reset Sunday 5:00 AM is 2d16h07m, and
        // a floored "2d" next to the "Sun" label read as a contradiction — the
        // calendar says Sunday is three sleeps off (user, 2026-07-23).
        assertEquals("2d16h", usageCountdownText(2 * 86_400L + 16 * 3600L + 7 * 60L))
        assertEquals("3d", usageCountdownText(3 * 86_400L))
        assertEquals("1d1h", usageCountdownText(86_400L + 3600L))
    }
}
