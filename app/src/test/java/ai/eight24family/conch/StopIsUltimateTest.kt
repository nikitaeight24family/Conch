package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentSessionPersistentStream
import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STOP IS A LAW.
 *
 * (2026-08-18).
 *
 * The three things that made it not ultimate, pinned here:
 *  - the kill waited out a four-second grace, which reads as "Stop did nothing";
 *  - the escalation was gated on our own turn tracking still saying `Working`,
 *    and that tracking is exactly what desyncs on a wedged reader;
 *  - the server-side kill ran on ONE route only, so a turn we merely mirror (or
 *    one started by this phone before a restart) survived.
 *
 * And the one thing Stop must NOT do, also pinned: stop the queue.
 */
class StopIsUltimateTest {

    @Test
    fun `the interrupt grace is short enough to feel instant`() {
        // 4 s of spinner after a Stop is indistinguishable from a broken button.
        // The window only has to fit a healthy interrupt (hundreds of ms).
        assertTrue(
            "grace=${AgentSessionPersistentStream.STOP_GRACE_MS}ms",
            AgentSessionPersistentStream.STOP_GRACE_MS <= 1_000L,
        )
    }

    @Test
    fun `every owned route escalates on the process being alive, not on Working`() {
        // `shouldEscalateKill` is the non-force gate: it needs `working`. Stop now
        // passes force=true on every owned route precisely so this gate cannot
        // veto the kill — this test pins the gate's shape so a future change that
        // starts relying on it again is visible.
        assertFalse(
            "a desynced turn must not be able to veto the kill",
            AgentSessionPersistentStream.shouldEscalateKill(
                sameTurn = true, victimDone = false, working = false, alive = true,
            ),
        )
        assertTrue(
            AgentSessionPersistentStream.shouldEscalateKill(
                sameTurn = true, victimDone = false, working = true, alive = true,
            ),
        )
    }

    @Test
    fun `an owned turn still halts through the stream, never the external ladder`() {
        // The external pgrep kill is now fired UNCONDITIONALLY alongside the local
        // one, but the LOCAL route must still be the stream for a process we own:
        // routing an owned process to the external ladder is what once let the
        // send-ack watchdog read the death as a drop and redeliver the prompt.
        for (working in listOf(true, false)) {
            val route = ChatViewModel.stopRoute(
                sessionExists = true, ownsLiveProcess = true, isWorking = working,
            )
            assertTrue(
                "owned route=$route",
                route == ChatViewModel.Companion.StopRoute.STREAM ||
                    route == ChatViewModel.Companion.StopRoute.STREAM_FORCE,
            )
        }
    }

    @Test
    fun `a queued message is still released once the session goes idle`() {
        // Stop kills the RUNNING turn; the queue then starts the next one. If this
        // ever returns false for an idle session with a queue, Stop has quietly
        // become "block the chat" instead of "halt the turn".
        assertTrue(
            ChatViewModel.shouldReleaseQueue(
                hasQueue = true,
                working = false,
                drainerBusy = false,
                mirroredTurnOpen = false,
                sessionReady = true,
            ),
        )
    }

    @Test
    fun `stop retries often enough to land soon after the link returns`() {
        assertTrue(
            "retry=${ChatViewModel.STOP_RETRY_POLL_MS}ms",
            ChatViewModel.STOP_RETRY_POLL_MS in 1_000L..5_000L,
        )
    }

    @Test
    fun `the resume-id guard still refuses anything not shaped like a session id`() {
        // The deferred kill interpolates the id into a shell script, so the guard
        // is load-bearing for the retry path too.
        val killer = ai.eight24family.conch.agent.RemoteTurnKiller
        assertTrue(killer.isKillableResumeId("b2a314cc-1ea8-4270-ad5c-085590174b4b"))
        assertFalse(killer.isKillableResumeId("; rm -rf ~"))
        assertFalse(killer.isKillableResumeId(null))
        assertFalse(killer.isKillableResumeId(""))
    }
}
