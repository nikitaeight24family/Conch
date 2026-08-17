package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a queued message is RELEASED.
 *
 * Every other release in the app hangs off an edge (local Working→Running,
 * mirrored turn end, network back, the once-per-slot kick). Each is correct and
 * each is missable — and Stop is the easiest way to miss one, because it clears
 * the mirrored flag while the state is still Working and STREAM_FORCE exists
 * precisely for turns our tracking already lost. When the edge went missing the
 * queue simply never went out. So the rule is state-based: idle + something
 * queued = send it, however the turn ended.
 */
class QueueReleaseTest {

    private fun release(
        hasQueue: Boolean = true,
        working: Boolean = false,
        drainerBusy: Boolean = false,
        mirrored: Boolean = false,
        ready: Boolean = true,
    ) = ChatViewModel.shouldReleaseQueue(
        hasQueue = hasQueue,
        working = working,
        drainerBusy = drainerBusy,
        mirroredTurnOpen = mirrored,
        sessionReady = ready,
    )

    @Test
    fun `idle session with a queue releases it`() {
        assertTrue(release())
    }

    @Test
    fun `nothing queued releases nothing`() {
        assertFalse(release(hasQueue = false))
    }

    @Test
    fun `our own running turn holds the queue`() {
        assertFalse(release(working = true))
    }

    @Test
    fun `a busy drainer holds the queue`() {
        // Covers the desync Stop's STREAM_FORCE route exists for: state has
        // fallen off Working while a turn of ours is provably still in flight.
        assertFalse(release(drainerBusy = true))
    }

    @Test
    fun `a mirrored turn holds the queue`() {
        assertFalse(release(mirrored = true))
    }

    @Test
    fun `a session that is not up yet holds the queue`() {
        // Bootstrapping / Failed: the reconnect ladder owns this, not the queue.
        assertFalse(release(ready = false))
    }

    @Test
    fun `release is the exact inverse of the queue gate`() {
        // The two rules must never disagree: a send that WOULD be queued right
        // now must not simultaneously be released, or a message could be handed
        // to the CLI mid-turn — straight into the agent's own invisible queue,
        // which is the whole thing the visible outbox exists to prevent.
        for (working in listOf(false, true)) {
            for (busy in listOf(false, true)) {
                for (mirrored in listOf(false, true)) {
                    val queued = ChatViewModel.shouldQueueSend(
                        working = working,
                        runningWithBusyDrainer = busy,
                        mirroredTurnOpen = mirrored,
                    )
                    val released = release(working = working, drainerBusy = busy, mirrored = mirrored)
                    assertFalse(
                        "queue=$queued release=$released for working=$working busy=$busy mirrored=$mirrored",
                        queued && released,
                    )
                }
            }
        }
    }
}
