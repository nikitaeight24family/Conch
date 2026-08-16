package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import ai.eight24family.conch.ui.viewmodel.ChatViewModel.Companion.StopRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Where Stop routes the halt (2026-08-17 fix). THE invariant: a turn running in
 * a process WE own never takes the external pgrep kill — that kill races our own
 * supervision and the send-ack watchdog then redelivers the prompt, so Stop
 * halted for a beat and the turn resumed.
 */
class StopRouteTest {

    @Test
    fun `owned live process while Working stops through the stream`() {
        assertEquals(
            StopRoute.STREAM,
            ChatViewModel.stopRoute(sessionExists = true, ownsLiveProcess = true, isWorking = true),
        )
    }

    /** THE REGRESSION. Reopened mid-turn: we own the process (procAlive) but our
     *  turn tracking desynced off Working. This MUST force the stream teardown,
     *  never fall through to the external kill. */
    @Test
    fun `owned live process desynced off Working forces the stream, never external kill`() {
        val route = ChatViewModel.stopRoute(sessionExists = true, ownsLiveProcess = true, isWorking = false)
        assertEquals(StopRoute.STREAM_FORCE, route)
        assertNotEquals(StopRoute.EXTERNAL_KILL, route)
    }

    @Test
    fun `a one-shot Working turn with no owned process uses the in-channel ladder`() {
        assertEquals(
            StopRoute.ONESHOT,
            ChatViewModel.stopRoute(sessionExists = true, ownsLiveProcess = false, isWorking = true),
        )
    }

    /** The ONLY case the external pgrep ladder is for: a session with no live
     *  process of ours (the orphan after a full restart, mirrored from the file). */
    @Test
    fun `a mirrored turn with no owned process and not Working uses the external ladder`() {
        assertEquals(
            StopRoute.EXTERNAL_KILL,
            ChatViewModel.stopRoute(sessionExists = true, ownsLiveProcess = false, isWorking = false),
        )
    }

    @Test
    fun `no session at all uses the external ladder`() {
        assertEquals(
            StopRoute.EXTERNAL_KILL,
            ChatViewModel.stopRoute(sessionExists = false, ownsLiveProcess = false, isWorking = false),
        )
    }

    /** Belt-and-braces: for EVERY input where we own a live process, the route is
     *  never the external kill. */
    @Test
    fun `owning a live process never routes to the external kill`() {
        for (working in listOf(true, false)) {
            assertNotEquals(
                "ownsLiveProcess + working=$working must not external-kill",
                StopRoute.EXTERNAL_KILL,
                ChatViewModel.stopRoute(sessionExists = true, ownsLiveProcess = true, isWorking = working),
            )
        }
    }
}
