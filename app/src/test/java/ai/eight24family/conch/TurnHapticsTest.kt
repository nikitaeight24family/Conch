package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.agent.claude.ClaudeMessageParser
import ai.eight24family.conch.ui.haptic.ConchHaptic
import ai.eight24family.conch.ui.viewmodel.ChatViewModelHaptics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completion buzz.
 *
 * ⛔ THIS CLASS EXISTS BECAUSE THIS CODE HAD TO BE FORCE-STOPPED. Keying the
 * three-pulse "answer finished" haptic off the arrival of a TurnEnd row assumed
 * message ids are stable; `turnEnd()` minted a random uuid, so every re-parse of
 * the same `result` record looked like a new end-of-turn and the phone buzzed at
 * full amplitude every two seconds until the app was killed. Before that, the
 * same haptic fired the instant the user pressed SEND, because "working" is an OR
 * of two signals that hand off to each other and its falling edge is not an
 * ending.
 *
 * So both rules are pinned here: it cannot repeat, and it cannot come early.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnHapticsTest {

    private fun user(id: String) = AgentMessage.UserText(id, "go")
    private fun asst(id: String) = AgentMessage.AssistantText(id, "text")
    private fun end(id: String) = AgentMessage.TurnEnd(id, "result")

    private class Rig {
        val fired = mutableListOf<ConchHaptic>()
        val messages = MutableStateFlow<List<AgentMessage>>(emptyList())
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val remote = MutableStateFlow(false)
        val ends get() = fired.count { it == ConchHaptic.TurnEnd }
    }

    private fun runRig(body: suspend kotlinx.coroutines.test.TestScope.(Rig) -> Unit) = runTest {
        val rig = Rig()
        // backgroundScope, not `this`: the coordinator's collectors are infinite
        // by design (they live as long as the chat), and runTest waits for its own
        // children to finish.
        ChatViewModelHaptics(
            // Unconfined so the coordinator's `collect`s start the moment they are
            // launched (the standard dispatcher would leave them queued), while
            // `delay` still runs on the virtual clock below.
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
            ),
            perform = { rig.fired += it },
            // The scheduler's clock, so `advanceTimeBy` actually moves the
            // windows this class reasons about.
            now = { testScheduler.currentTime },
        ).install(rig.messages, rig.state, rig.remote)
        // Seed: the first non-empty history must never buzz.
        rig.messages.value = listOf(user("u0"), asst("a0"))
        advanceUntilIdle()
        assertTrue("opening a chat must not buzz: ${rig.fired}", rig.fired.isEmpty())
        body(rig)
    }

    // ───────── it cannot come early ─────────

    @Test
    fun `the handoff gap between our state and the file mirror does not announce an end`() =
        runRig { rig ->
            // Turn starts (our own state), runs, then our state settles off
            // Working while the file mirror has not yet ticked to true — the exact
            // gap that buzzed on send.
            rig.state.value = SessionState.Working
            advanceTimeBy(1_500)
            rig.state.value = SessionState.Running
            advanceTimeBy(1_000)
            assertEquals("no buzz inside the gap", 0, rig.ends)
            // The poller catches up: the turn was never over.
            rig.remote.value = true
            advanceUntilIdle()
            assertEquals("the gap must never become an announcement", 0, rig.ends)
        }

    @Test
    fun `a real end announces once the state has settled`() = runRig { rig ->
        rig.state.value = SessionState.Working
        advanceTimeBy(1_500)
        rig.state.value = SessionState.Running
        advanceUntilIdle()
        assertEquals(1, rig.ends)
    }

    @Test
    fun `a blip too short to be a turn announces nothing`() = runRig { rig ->
        rig.state.value = SessionState.Working
        advanceTimeBy(200)
        rig.state.value = SessionState.Running
        advanceUntilIdle()
        assertEquals(0, rig.ends)
    }

    // ───────── it cannot repeat ─────────

    @Test
    fun `replayed end-of-turn rows announce once, not once each`() = runRig { rig ->
        rig.state.value = SessionState.Working
        advanceTimeBy(1_500)
        rig.messages.value = rig.messages.value + asst("a1") + end("e1")
        rig.state.value = SessionState.Running
        advanceUntilIdle()
        assertEquals(1, rig.ends)
        // Now the mirror re-appends the same turn's end with fresh ids, over and
        // over, the way a tail-poll re-reading a frozen file used to. THIS is the
        // loop that had to be force-stopped.
        repeat(20) { i ->
            rig.messages.value = rig.messages.value + end("replay$i")
            advanceTimeBy(3_000)
        }
        assertEquals("one turn, one buzz", 1, rig.ends)
    }

    @Test
    fun `a new turn re-arms the buzz`() = runRig { rig ->
        rig.state.value = SessionState.Working
        advanceTimeBy(1_500)
        rig.state.value = SessionState.Running
        advanceUntilIdle()
        assertEquals(1, rig.ends)
        rig.state.value = SessionState.Working
        advanceTimeBy(1_500)
        rig.state.value = SessionState.Running
        advanceUntilIdle()
        assertEquals(2, rig.ends)
    }

    @Test
    fun `an end row arriving long after the turn is a replay, not news`() = runRig { rig ->
        rig.state.value = SessionState.Working
        advanceTimeBy(1_500)
        rig.state.value = SessionState.Running
        advanceUntilIdle()
        val after = rig.ends
        advanceTimeBy(60_000)
        rig.messages.value = rig.messages.value + end("late")
        advanceUntilIdle()
        assertEquals(after, rig.ends)
    }

    // ───────── row haptics ─────────

    @Test
    fun `rows arriving with nothing running do not buzz`() = runRig { rig ->
        // A chat continued on the PC: the tail-poll appends two rows while no turn
        // of ours is or was running.
        rig.messages.value = rig.messages.value + asst("a9")
        advanceUntilIdle()
        assertTrue("catch-up is not an answer: ${rig.fired}", rig.fired.isEmpty())
    }

    @Test
    fun `rows during a turn buzz`() = runRig { rig ->
        rig.state.value = SessionState.Working
        advanceUntilIdle()
        rig.messages.value = rig.messages.value + asst("a2")
        advanceUntilIdle()
        assertTrue(rig.fired.contains(ConchHaptic.Tap))
    }

    @Test
    fun `a history load does not buzz per row`() = runRig { rig ->
        rig.state.value = SessionState.Working
        advanceUntilIdle()
        rig.messages.value = rig.messages.value + (1..40).map { asst("h$it") }
        advanceUntilIdle()
        assertTrue("a 40-row reload is a load, not 40 answers: ${rig.fired}", rig.fired.isEmpty())
    }

    // ───────── the root cause: id stability ─────────

    @Test
    fun `the parser gives the same end-of-turn record the same id twice`() {
        val line = """{"type":"result","subtype":"success","duration_ms":10,"is_error":false}"""
        val a = ClaudeMessageParser.parse(line).filterIsInstance<AgentMessage.TurnEnd>().single()
        val b = ClaudeMessageParser.parse(line).filterIsInstance<AgentMessage.TurnEnd>().single()
        assertEquals("a re-parse must not look like a new turn ending", a.id, b.id)
    }
}
