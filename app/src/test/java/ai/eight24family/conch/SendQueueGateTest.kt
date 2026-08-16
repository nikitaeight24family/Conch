package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a fresh send is parked in the VISIBLE QUEUE instead of hitting the CLI
 * straight away. THE fix (2026-08-17): a send into a MIRRORED turn — one running
 * server-side that our state machine hasn't caught up on after a reopen — must
 * queue, not go hot. Hot-sending into a reopened live turn interrupted it and
 * cold-restarted the session with a full history re-send.
 */
class SendQueueGateTest {

    private fun queue(
        working: Boolean = false,
        runningBusy: Boolean = false,
        mirrored: Boolean = false,
    ) = ChatViewModel.shouldQueueSend(
        working = working,
        runningWithBusyDrainer = runningBusy,
        mirroredTurnOpen = mirrored,
    )

    @Test
    fun `our own working turn queues`() {
        assertTrue(queue(working = true))
    }

    @Test
    fun `running while the drainer winds a turn down queues`() {
        assertTrue(queue(runningBusy = true))
    }

    /** THE REGRESSION. Reopened into a turn running server-side: our state
     *  machine says Running/Idle (not Working, drainer not busy), but the file
     *  mirror says a turn is in flight. Must queue, never go hot. */
    @Test
    fun `a mirrored turn we only mirror queues`() {
        assertTrue(queue(working = false, runningBusy = false, mirrored = true))
    }

    @Test
    fun `a genuinely idle session sends straight through`() {
        assertFalse(queue(working = false, runningBusy = false, mirrored = false))
    }

    /** Belt-and-braces: if ANY in-flight signal is set, the send queues. */
    @Test
    fun `any in-flight signal forces the queue`() {
        for (w in listOf(true, false)) for (r in listOf(true, false)) for (m in listOf(true, false)) {
            val expected = w || r || m
            org.junit.Assert.assertEquals(
                "w=$w r=$r m=$m", expected, queue(working = w, runningBusy = r, mirrored = m),
            )
        }
    }
}
