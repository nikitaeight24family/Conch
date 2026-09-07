package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ChatViewModelTailPoll
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STOP IS A LAW — AND THE MIRROR POLL IS THE LAST THING THAT CAN BREAK IT.
 *
 * Eight presses of Stop, no effect — reported as the fifteenth time this had
 * been "fixed" (owner, 2026-09-07). Every earlier fix worked on DELIVERY — the
 * protocol interrupt, the escalation ladder, the pgrep kill by resume id, the
 * persisted order — and
 * each of them was correct while the button still read as dead, because the tail
 * poll re-derives "a turn is in flight" from the session state every ~5 s and
 * republishes `remoteFileOpen`, the exact flag the spinner and the Stop button
 * read. Stop's own writes survived one tick.
 *
 * Captured off the owner's phone while he was pressing it:
 *
 *     D/Conch-Tail: turn sid=a664037c inFlight=false complete=false
 *                   thinking=true frozenMs=190000 size=146668
 *
 * `inFlight=false` (the writer is gone), `complete=false` (it died without a
 * terminal record, so the stuck-turn reconcile could never fire) — and the
 * spinner still up, because `fileWorking` published it off `curWorking` alone.
 *
 * So Stop is now a LATCH the mirror obeys, lifted only by proof the turn
 * outlived the halt.
 */
class StopLatchTest {

    private val T = 1_788_768_000_000L

    // ── the latch itself ─────────────────────────────────────────────────────

    @Test
    fun `no stop pressed - the mirror is free to report a live turn`() {
        assertFalse(ChatViewModelTailPoll.stopStandsOver(stoppedAtMs = null, lastGrowthAtMs = T))
    }

    /** THE BUG. A stop the escalation could not deliver (dead transport, dead
     *  process, wedged reader — every case where `cancelTurn` logs "nothing to
     *  kill" and leaves the state on Working) must still silence the mirror. */
    @Test
    fun `a stop with no growth after it stands`() {
        assertTrue(ChatViewModelTailPoll.stopStandsOver(stoppedAtMs = T, lastGrowthAtMs = T - 190_000L))
    }

    /** …and a stop pressed before this poller ever saw growth stands too — that
     *  is the reopened-chat case, where the loop's growth clock starts at 0. */
    @Test
    fun `a stop stands when nothing has grown at all`() {
        assertTrue(ChatViewModelTailPoll.stopStandsOver(stoppedAtMs = T, lastGrowthAtMs = 0L))
    }

    /** The ONE thing that lifts it: bytes landing after the halt. The turn
     *  outlived the stop and is honestly writing, so the spinner is the truth —
     *  and the server-side kill's own verdict ("still running") agrees. */
    @Test
    fun `growth after the stop lifts the latch`() {
        assertFalse(ChatViewModelTailPoll.stopStandsOver(stoppedAtMs = T, lastGrowthAtMs = T + 1L))
    }

    /** A write in the same millisecond is the dying CLI's final flush, not a
     *  live turn — it must NOT resurrect the spinner. */
    @Test
    fun `a same-millisecond flush does not lift the latch`() {
        assertTrue(ChatViewModelTailPoll.stopStandsOver(stoppedAtMs = T, lastGrowthAtMs = T))
    }

    // ── and it must compose with the spinner gate, not merely exist ──────────

    /**
     * The whole bug in one assertion: our own turn (`curWorking`) with a dead
     * writer and no terminal record — `fileWorking` says WORKING, and it is
     * right to, because that is what "a turn of ours is running" means. The stop
     * latch is what has to turn it off, and the poll must apply both.
     */
    @Test
    fun `the spinner gate alone cannot stop - the latch is what does`() {
        val gate = ChatViewModelTailPoll.fileWorking(
            curWorking = true, liveStuck = false, inFlight = false,
        )
        assertTrue("this is why eight presses did nothing", gate)
        val stopStands = ChatViewModelTailPoll.stopStandsOver(
            stoppedAtMs = T, lastGrowthAtMs = T - 190_000L,
        )
        assertFalse("working, as the poll now publishes it", gate && !stopStands)
    }
}
