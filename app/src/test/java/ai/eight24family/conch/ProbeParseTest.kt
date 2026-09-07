package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ChatViewModelTailPoll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The poll probe's reply → [ChatViewModelTailPoll.PollProbe].
 *
 * ⚠ THIS TEST EXISTS BECAUSE A FIELD WAS DROPPED ON THE FLOOR. `writerAlive`
 * was produced by the probe's own third shell line, parsed into a local — and
 * never passed to the returned probe. So `probe.writerAlive` was `null` on
 * every tick of every session for as long as the signal existed: every consumer
 * silently fell back to the mtime clock, `heartbeatInFlight`'s "PROOF BEATS THE
 * CLOCK" branches were green in [FileWorkingGateTest] and unreachable in the
 * app, and the stuck-turn reconcile could not tell a long silent research turn
 * from a writer that had died without a terminal record — which is the state
 * that produced the unkillable spinner of 2026-09-07. Dropping a field is a
 * compiler warning, and a warning in a four-minute Android build is invisible.
 *
 * Every field the shell reports is asserted here, so the next one added has to
 * be wired to be green.
 */
class ProbeParseTest {

    private fun probe(vararg lines: String) =
        ChatViewModelTailPoll.parseProbeOutput(lines.joinToString("\n") + "\n---\n")

    @Test
    fun `a healthy reply carries size, mtime, frozen duration AND the writer verdict`() {
        val p = probe("146668,1788767000", "1788767190", "CONCH_WRITER=1")
        assertEquals(146668L, p.size)
        assertEquals(1788767000_000L, p.mtimeMs)
        assertEquals("server clock at both ends", 190_000L, p.frozenForMs)
        assertEquals(true, p.writerAlive)
    }

    /** The 2026-09-07 shape: file frozen for minutes, no agent process left. */
    @Test
    fun `a dead writer is reported as proven dead, not unknown`() {
        assertEquals(false, probe("146668,1788767000", "1788767190", "CONCH_WRITER=0").writerAlive)
    }

    /** `?` is what a host with no pgrep / no /proc / unreadable cwds prints. It
     *  must stay null — unknown liveness keeps the mtime rule in charge instead
     *  of guessing a turn alive OR dead. */
    @Test
    fun `unknown liveness stays null`() {
        assertNull(probe("146668,1788767000", "1788767190", "CONCH_WRITER=?").writerAlive)
        assertNull("no verdict line at all", probe("146668,1788767000", "1788767190").writerAlive)
    }

    /** A missing file must not let `date +%s` slide into first place and be read
     *  as a ~1.8 GB size — the bug the sentinel + comma check exist for. */
    @Test
    fun `a missing file yields no size and no frozen duration`() {
        val p = probe("CONCH_NOFILE", "1788767190", "CONCH_WRITER=0")
        assertNull(p.size)
        assertNull(p.mtimeMs)
        assertNull(p.frozenForMs)
        assertEquals("liveness is still readable without the file", false, p.writerAlive)
    }

    @Test
    fun `a clock that runs backwards never reports negative frozen time`() {
        assertEquals(0L, probe("10,1788767200", "1788767190", "CONCH_WRITER=1").frozenForMs)
    }

    @Test
    fun `garbage in the size slot is refused rather than half-parsed`() {
        val p = probe("not-a-stat", "1788767190", "CONCH_WRITER=1")
        assertNull(p.size)
        assertNull(p.frozenForMs)
    }
}
