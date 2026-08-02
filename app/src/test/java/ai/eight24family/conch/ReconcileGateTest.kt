package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeSpec
import ai.eight24family.conch.agent.codex.CodexSpec
import ai.eight24family.conch.agent.gemini.GeminiSpec
import ai.eight24family.conch.ui.viewmodel.ChatViewModelTailPoll
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether the app may force-complete a live turn from the
 * session file. It force-completes a turn AND tears down the reader, so a false
 * positive costs the user a running turn's output — this needs to be exact.
 */
class ReconcileGateTest {

    private fun gate(
        curWorking: Boolean = true,
        sawGrowth: Boolean = true,
        turnComplete: Boolean = true,
        pendingCtl: Boolean = false,
        waitingForUser: Boolean = false,
        stuckSinceMs: Long = 10_000L,
    ) = ChatViewModelTailPoll.shouldReconcileStuckTurn(
        curWorking = curWorking,
        sawGrowthThisTurn = sawGrowth,
        turnComplete = turnComplete,
        pendingCtl = pendingCtl,
        waitingForUser = waitingForUser,
        stuckSinceMs = stuckSinceMs,
    )

    @Test
    fun `a wedged turn whose file went terminal is reconciled`() {
        assertTrue(gate())
    }

    /**
     * THE REGRESSION the growth latch exists for. `Working` is set before the CLI
     * process is even launched, so on the first poll tick after a send the last
     * record in the file is still the PREVIOUS turn's end_turn — frozen for as
     * long as the chat sat idle. Without the latch that force-completes a turn
     * the agent has not begun answering.
     */
    @Test
    fun `the previous turn's terminal record must NOT reconcile the new turn`() {
        assertFalse(gate(sawGrowth = false, stuckSinceMs = 1_007_000L))
    }

    @Test
    fun `growth alone is not enough - the file must also be terminal`() {
        assertFalse(gate(turnComplete = false))
    }

    @Test
    fun `a turn blocked on the user is never reconciled`() {
        assertFalse("live control_request", gate(pendingCtl = true))
        assertFalse("file-visible approval", gate(waitingForUser = true))
    }

    @Test
    fun `the grace must elapse`() {
        val g = ChatViewModelTailPoll.RECONCILE_STUCK_GRACE_MS
        assertTrue(gate(stuckSinceMs = g))
        assertFalse(gate(stuckSinceMs = g - 1))
    }

    @Test
    fun `a mirrored turn is not ours to reconcile`() {
        assertFalse(gate(curWorking = false))
    }

    // ── the reconcile must be REACHABLE for every agent ──────────────────────
    //
    // turnComplete is the gate, and for Codex and Gemini it was never set at all
    // — the safety net was structurally dead for those two, which is exactly the
    // state that produced the unstoppable spinner on Claude.

    @Test
    fun `claude reports turnComplete on a terminal stop_reason`() {
        val done = listOf(
            """{"type":"user","message":{"content":[{"type":"text","text":"go"}]}}""",
            """{"type":"assistant","message":{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}}""",
        )
        assertTrue(ClaudeSpec.inferTurnState(ClaudeSpec.projectTurnStateRecords(done.asSequence()), 9_000L).turnComplete)
    }

    @Test
    fun `codex reports turnComplete only on a real done marker`() {
        fun complete(vararg lines: String) =
            CodexSpec.inferTurnState(CodexSpec.projectTurnStateRecords(lines.asSequence()), 9_000L).turnComplete
        assertTrue(
            complete(
                """{"type":"event_msg","payload":{"type":"task_started"}}""",
                """{"type":"event_msg","payload":{"type":"task_complete"}}""",
            ),
        )
        assertTrue(complete("""{"type":"turn.failed"}"""))
        assertFalse(
            "still running",
            complete(
                """{"type":"event_msg","payload":{"type":"task_started"}}""",
                """{"type":"event_msg","payload":{"type":"token_count"}}""",
            ),
        )
    }

    /**
     * A window with no boundary at all is a STALENESS GUESS, not proof. Force-
     * completing on a guess is what tears down long research turns, so this one
     * must stay false however stale it looks.
     */
    @Test
    fun `codex never claims completion from the no-boundary fallback`() {
        val noBoundary = listOf("""{"type":"event_msg","payload":{"type":"token_count"}}""")
        val sig = CodexSpec.inferTurnState(CodexSpec.projectTurnStateRecords(noBoundary.asSequence()), 30 * 60_000L)
        assertFalse(sig.inFlight)
        assertFalse("no proof of completion here", sig.turnComplete)
    }

    @Test
    fun `gemini reports turnComplete once the model replied`() {
        fun complete(vararg lines: String) =
            GeminiSpec.inferTurnState(GeminiSpec.projectTurnStateRecords(lines.asSequence()), 9_000L).turnComplete
        assertTrue(complete("""{"type":"user","timestamp":"t1"}""", """{"type":"gemini","timestamp":"t2"}"""))
        assertFalse(complete("""{"type":"user","timestamp":"t1"}"""))
    }
}
