package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentSessionPersistentStream
import ai.eight24family.conch.ui.viewmodel.ChatViewModelTailPoll
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "⏸ waiting for your answer — open the server session".
 *
 * It tells the user to go do something on another machine, so a false positive
 * sends them off after a question that does not exist. On 2026-07-29 it stayed up
 * after the question was answered AND across the next prompt.
 */
class WaitingBannerGateTest {

    private fun gate(
        pendingCtl: Boolean = false,
        fileWaiting: Boolean = false,
        ours: Boolean = false,
        inFlight: Boolean = true,
        thinking: Boolean = true,
        frozen: Long? = 4 * 60_000L,
    ) = ChatViewModelTailPoll.waitingForInput(
        pendingCtl = pendingCtl,
        fileWaiting = fileWaiting,
        fileTailIsOurs = ours,
        inFlight = inFlight,
        thinking = thinking,
        frozenForMs = frozen,
    )

    @Test
    fun `a live control we hold always shows the banner`() {
        assertTrue(gate(pendingCtl = true, inFlight = false, thinking = false, frozen = null))
    }

    @Test
    fun `the file's own waiting signal shows the banner`() {
        assertTrue(gate(fileWaiting = true, inFlight = false, thinking = false, frozen = null))
    }

    /**
     * THE REGRESSION. Our own turn was killed, so the file's last record is a
     * `user` row with no interrupt marker: inFlight and thinking stay true for
     * twelve minutes and the file freezes because nothing is writing. That must
     * NOT be read as "the agent is waiting for an answer".
     */
    @Test
    fun `our own dead turn never claims a pending question`() {
        assertFalse(gate(ours = true, frozen = 10 * 60_000L))
    }

    @Test
    fun `a mirrored frozen think still nudges to the server`() {
        assertTrue(gate(ours = false, frozen = 4 * 60_000L))
    }

    @Test
    fun `a mirrored think under the stall window stays quiet`() {
        assertFalse(gate(ours = false, frozen = 2 * 60_000L))
    }

    @Test
    fun `a running tool is long work, not a question`() {
        assertFalse(gate(thinking = false, frozen = 20 * 60_000L))
    }

    @Test
    fun `an unreadable server clock never trips the heuristic`() {
        assertFalse(gate(ours = false, frozen = null))
    }

    @Test
    fun `an idle file says nothing`() {
        assertFalse(gate(inFlight = false, thinking = false, frozen = 30 * 60_000L))
    }

    // ── the duplicate question card ─────────────────────────────────────────

    /**
     * Claude emits the question as an ordinary assistant/tool_use line about ONE
     * LINE BEFORE the control_request. The parser turns that into the read-only
     * MIRROR card. Rendering it on the live channel is what showed the question
     * twice — once answerable, once "answer this in your CLI session".
     */
    @Test
    fun `the mirror question card is not rendered on the live stream`() {
        val mirrored = AgentMessage.AskUserQuestion(
            id = "toolu_01QuAK",
            requestId = "",
            questions = emptyList(),
            readOnly = true,
        )
        assertFalse(AgentSessionPersistentStream.rendersOnLiveStream(mirrored))
    }

    @Test
    fun `the answerable question card IS rendered on the live stream`() {
        val live = AgentMessage.AskUserQuestion(
            id = "ask-db0750f5",
            requestId = "db0750f5",
            questions = emptyList(),
            readOnly = false,
        )
        assertTrue(AgentSessionPersistentStream.rendersOnLiveStream(live))
    }

    @Test
    fun `ordinary rows still render, signals still do not`() {
        assertTrue(AgentSessionPersistentStream.rendersOnLiveStream(
            AgentMessage.AssistantText("m1", "hello"),
        ))
        assertFalse(AgentSessionPersistentStream.rendersOnLiveStream(
            AgentMessage.TurnEnd("t1", "result"),
        ))
        assertFalse(AgentSessionPersistentStream.rendersOnLiveStream(
            AgentMessage.UserText("u1", "hi"),
        ))
    }
}
