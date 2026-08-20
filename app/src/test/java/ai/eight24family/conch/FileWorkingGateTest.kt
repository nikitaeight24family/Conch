package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ChatViewModelTailPoll
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 */
class FileWorkingGateTest {

    // ── heartbeat: projected in-flight counts only while the file is warm ──

    @Test
    fun `a fresh file with an unfinished last record is in flight`() {
        assertTrue(ChatViewModelTailPoll.heartbeatInFlight(inFlight = true, frozenForMs = 4_000L))
    }

    @Test
    fun `a file frozen past the stale window is not running, whatever the record says`() {
        assertFalse(
            ChatViewModelTailPoll.heartbeatInFlight(
                inFlight = true,
                frozenForMs = ChatViewModelTailPoll.STALE_TURN_MS,
            ),
        )
    }

    /** Unknown mtime = no proof of recency = no spinner. Claiming a live turn
     *  without proof is the fake-working regression fixed 2026-08-17. */
    @Test
    fun `an unreadable mtime never lights the spinner`() {
        assertFalse(ChatViewModelTailPoll.heartbeatInFlight(inFlight = true, frozenForMs = null))
    }

    /**
     * THE 2026-08-20 BUG. A long tool — rsync, a build, a 47-minute hash — writes
     * NOTHING to the JSONL while it runs, so the mtime heartbeat declared the turn
     * over after 60 s: the app dropped the spinner and buzzed its three-pulse
     * "done" while the server was mid-tool. A live writer in this session's own
     * project outranks the clock.
     */
    @Test
    fun `a live writer keeps a long silent tool in flight`() {
        assertTrue(
            ChatViewModelTailPoll.heartbeatInFlight(
                inFlight = true,
                frozenForMs = 45L * 60_000L,
                writerAlive = true,
            ),
        )
    }

    /** The same proof cuts the other way — no writer means no turn, however
     *  freshly the file was touched (a finished turn's last write is recent). */
    @Test
    fun `no writer means the turn is over even with a warm file`() {
        assertFalse(
            ChatViewModelTailPoll.heartbeatInFlight(
                inFlight = true,
                frozenForMs = 1_000L,
                writerAlive = false,
            ),
        )
    }

    /** Unknown liveness (no pgrep / no /proc on the host) must fall back to the
     *  old clock rule, not to a guess in either direction. */
    @Test
    fun `unknown writer liveness falls back to the mtime heartbeat`() {
        assertTrue(
            ChatViewModelTailPoll.heartbeatInFlight(
                inFlight = true, frozenForMs = 4_000L, writerAlive = null,
            ),
        )
        assertFalse(
            ChatViewModelTailPoll.heartbeatInFlight(
                inFlight = true,
                frozenForMs = ChatViewModelTailPoll.STALE_TURN_MS,
                writerAlive = null,
            ),
        )
    }

    /** A dead writer must not be able to claim a turn through the new signal. */
    @Test
    fun `a finished record stays finished whatever the writer is doing`() {
        assertFalse(
            ChatViewModelTailPoll.heartbeatInFlight(
                inFlight = false, frozenForMs = 0L, writerAlive = true,
            ),
        )
    }

    @Test
    fun `a finished record is not in flight however fresh the file`() {
        assertFalse(ChatViewModelTailPoll.heartbeatInFlight(inFlight = false, frozenForMs = 0L))
    }

    // ── fileWorking: local turn OR mirrored turn, reconcile clears at once ──

    @Test
    fun `a mirrored turn lights working with no local session at all`() {
        assertTrue(ChatViewModelTailPoll.fileWorking(curWorking = false, liveStuck = false, inFlight = true))
    }

    @Test
    fun `our own turn lights working before the file has any bytes`() {
        assertTrue(ChatViewModelTailPoll.fileWorking(curWorking = true, liveStuck = false, inFlight = false))
    }

    /** A reconciled stuck turn must drop the spinner THE SAME tick — waiting
     *  for the state machine to catch up is how the gerund outlived the turn
     *  (user, 2026-06-14). */
    @Test
    fun `a reconciled stuck turn clears immediately`() {
        assertFalse(ChatViewModelTailPoll.fileWorking(curWorking = true, liveStuck = true, inFlight = false))
    }

    @Test
    fun `idle is idle`() {
        assertFalse(ChatViewModelTailPoll.fileWorking(curWorking = false, liveStuck = false, inFlight = false))
    }
}
