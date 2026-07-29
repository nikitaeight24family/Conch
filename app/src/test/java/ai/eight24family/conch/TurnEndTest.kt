package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.claude.ClaudeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turn completion, end to end through the real parser.
 *
 * This is the signal that stops the thinking indicator, and it had no test that
 * fed it a WHOLE turn. The 2026-07-29 capture is the fixture: the reply landed,
 * the spinner ran on, and nothing in the app could say why.
 */
class TurnEndTest {

    private fun parse(line: String) = ClaudeSpec.parseStreamLine(line, "t_")
    private fun endsTurn(line: String) = parse(line).any { it is AgentMessage.TurnEnd }

    /** The lines the reader actually saw, in order, from the phone. */
    private val capture = listOf(
        """{"type":"system","subtype":"status","status":"requesting","session_id":"8ce28eb6"}""",
        """{"type":"stream_event","event":{"type":"message_start","message":{"model":"claude-opus-5","id":"msg_01"}},"session_id":"8ce28eb6"}""",
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Yes"}},"session_id":"8ce28eb6"}""",
        """{"type":"assistant","message":{"id":"msg_01","role":"assistant","content":[{"type":"text","text":"Yes, the bridge works."}]}}""",
        """{"type":"stream_event","event":{"type":"message_stop"},"session_id":"8ce28eb6"}""",
        """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1785340200}}""",
        // The envelope as captured: `type` NOT first, and possibly absent entirely.
        """{"is_error":false,"duration_api_ms":2593,"num_turns":1,"stop_reason":"end_turn",""" +
            """"session_id":"8ce28eb6-ace1-495c-b5e0-c15a95d38dc7","total_cost_usd":0.0157335,""" +
            """"usage":{"input_tokens":2,"cache_creation_input_tokens":112,"output_tokens":33},""" +
            """"result":"Yes, the bridge works."}""",
    )

    @Test
    fun `exactly one line ends the turn, and it is the last`() {
        assertEquals(
            listOf(false, false, false, false, false, false, true),
            capture.map { endsTurn(it) },
        )
    }

    @Test
    fun `the captured envelope is recognised even with no top-level type`() {
        val msgs = parse(capture.last())
        assertTrue("must end the turn", msgs.any { it is AgentMessage.TurnEnd })
        assertTrue("and still render its result row", msgs.any { it is AgentMessage.Result })
    }

    @Test
    fun `every terminal envelope ends the turn, whatever card it renders`() {
        val terminals = listOf(
            """{"type":"result","subtype":"success","total_cost_usd":0.001,"result":"done","usage":{"output_tokens":3},"duration_ms":4500}""",
            """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Overloaded"}""",
            """{"type":"result","subtype":"success","is_error":true,"result":"Request interrupted by user"}""",
            """{"type":"error","message":"upstream is on fire"}""",
        )
        terminals.forEach { assertTrue("must end the turn: $it", endsTurn(it)) }
    }

    /**
     * A mid-turn 529 retry renders an Error banner and the turn KEEPS RUNNING.
     * Ending it here would abandon a live turn.
     */
    @Test
    fun `api_retry does NOT end the turn`() {
        assertFalse(endsTurn("""{"type":"api_retry","error":"Overloaded","attempt":"3"}"""))
    }

    @Test
    fun `ordinary stream traffic never ends the turn`() {
        val nonTerminal = listOf(
            """{"type":"stream_event","event":{"type":"content_block_stop","index":0}}""",
            """{"type":"assistant","message":{"id":"m","content":[{"type":"tool_use","name":"Bash"}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}""",
            """{"type":"system","subtype":"init","session_id":"x"}""",
            """{"type":"summary","summary":"a compaction"}""",
        )
        nonTerminal.forEach { assertFalse("must NOT end the turn: $it", endsTurn(it)) }
    }

    /**
     * The shape rule must never promote a mid-turn line. `event` / `message`
     * records are left alone no matter what else they carry.
     */
    @Test
    fun `a stream_event carrying usage numbers is not mistaken for the end`() {
        val delta = """{"type":"stream_event","event":{"type":"message_delta",""" +
            """"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":33}}}"""
        assertFalse(endsTurn(delta))
        // Even with the top-level type stripped, `event` protects it.
        val stripped = """{"event":{"type":"message_delta","usage":{"output_tokens":33}},"num_turns":1,"duration_api_ms":5}"""
        assertFalse(endsTurn(stripped))
    }

    @Test
    fun `garbage never ends the turn`() {
        listOf("", "   ", "not json", """{"type":"result""").forEach {
            assertFalse(endsTurn(it))
        }
    }
}
