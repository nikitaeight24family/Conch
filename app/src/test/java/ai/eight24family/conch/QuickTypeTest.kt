package ai.eight24family.conch

import ai.eight24family.conch.agent.spec.ParserHelpers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `quickType` decides whether a stream line ENDS THE TURN. When it disagrees
 * with the real parser the app renders a finished answer and keeps spinning
 * over it — which is exactly what happened on 2026-07-29: the result envelope
 * arrived with its `type` well down the object, the substring scan returned
 * something else, `turnDone` never completed.
 *
 * So: TOP-LEVEL only, position-independent, and immune to anything nested.
 */
class QuickTypeTest {

    private fun t(line: String) = ParserHelpers.quickType(line)

    @Test
    fun `finds type as the first key`() {
        assertEquals("result", t("""{"type":"result","subtype":"success"}"""))
    }

    /** THE BUG. Captured from the user's phone; `type` is not first. */
    @Test
    fun `finds type when it is NOT the first key`() {
        val real = """{"is_error":false,"duration_api_ms":2593,"num_turns":1,""" +
            """"stop_reason":"end_turn","session_id":"8ce28eb6-ace1-495c-b5e0-c15a95d38dc7",""" +
            """"total_cost_usd":0.0157335,"usage":{"input_tokens":2,"cache_creation_input_tokens":112,""" +
            """"output_tokens":33},"type":"result","subtype":"success"}"""
        assertEquals("result", t(real))
    }

    /** A nested `type` must never win over the top-level one. */
    @Test
    fun `a nested type does not shadow the top-level one`() {
        val line = """{"event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}},""" +
            """"type":"stream_event"}"""
        assertEquals("stream_event", t(line))
    }

    @Test
    fun `a nested type with NO top-level type yields null, not the inner value`() {
        val line = """{"event":{"type":"message_stop"},"session_id":"x"}"""
        assertNull(t(line))
    }

    /** Assistant text can contain anything at all — including our own needle. */
    @Test
    fun `a type-looking string inside message text cannot fool it`() {
        val line = """{"message":{"content":[{"text":"use \"type\":\"result\" to finish"}]},"type":"assistant"}"""
        assertEquals("assistant", t(line))
    }

    @Test
    fun `braces inside text do not corrupt the depth count`() {
        val line = """{"delta":{"text":"fun f() { return {\"a\":1} }"},"type":"stream_event"}"""
        assertEquals("stream_event", t(line))
    }

    @Test
    fun `escaped quotes and backslashes are walked correctly`() {
        val line = """{"text":"path C:\\dir\\ and a quote \" here","type":"result"}"""
        assertEquals("result", t(line))
    }

    @Test
    fun `an array of objects carrying type does not shadow`() {
        val line = """{"content":[{"type":"tool_use"},{"type":"text"}],"type":"assistant"}"""
        assertEquals("assistant", t(line))
    }

    @Test
    fun `whitespace around the colon is tolerated`() {
        assertEquals("result", t("""{ "type" : "result" }"""))
    }

    @Test
    fun `malformed and empty input yields null`() {
        assertNull(t(""))
        assertNull(t("not json"))
        assertNull(t("""{"type":"""))
        assertNull(t("""{"type":"unterminated"""))
        assertNull(t("""{"other":1}"""))
    }

    @Test
    fun `a non-string type value is not returned`() {
        assertNull(t("""{"type":42,"x":1}"""))
    }

    @Test
    fun `the turn-end gate now agrees with the parser on a real result envelope`() {
        val real = """{"is_error":false,"duration_api_ms":2593,"stop_reason":"end_turn",""" +
            """"usage":{"server_tool_use":{"web_search_requests":0}},"type":"result","subtype":"success"}"""
        // The reader's gate is: rawType == "result" || rawType == "error".
        val rawType = t(real)
        org.junit.Assert.assertTrue("turn must be recognised as terminal", rawType == "result" || rawType == "error")
    }
}
