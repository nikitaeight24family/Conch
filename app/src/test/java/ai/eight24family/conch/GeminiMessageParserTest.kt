package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.gemini.GeminiMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GeminiMessageParser]'s system-event surface (2026-06-12):
 * per-turn token usage from the `result` event's stats block, and the
 * never-swallow rule for unknown LIVE event types (saved-file mutation-log
 * noise stays hidden — it would spray rows on every history re-hydration).
 */
class GeminiMessageParserTest {

    @Test
    fun `result event emits Result plus token usage note from stats`() {
        val json = """{"type":"result","status":"success","response":"done","timestamp":"2026-06-12T10:00:00Z",""" +
            """"stats":{"models":{"gemini-2.5-pro":{"api":{"totalRequests":2},""" +
            """"tokens":{"prompt":24939,"candidates":1430,"total":26523,"cached":21263,"thoughts":154,"tool":0}}}}}"""
        val out = GeminiMessageParser.parse(json)
        assertEquals(2, out.size)
        assertTrue(out[0] is AgentMessage.Result)
        val n = out[1] as AgentMessage.EventNote
        assertTrue(n.label.contains("tokens"))
        assertTrue("Locale.US k-format: ${n.label}", n.label.contains("in 24.9k"))
        assertTrue(n.label.contains("out 1.4k"))
        assertTrue(n.label.contains("thinking 154"))
        assertTrue(n.label.contains("cached 21.3k"))
    }

    @Test
    fun `result without stats emits only the Result`() {
        val out = GeminiMessageParser.parse("""{"type":"result","status":"success","response":"ok"}""")
        assertEquals(1, out.size)
        assertTrue(out.first() is AgentMessage.Result)
    }

    @Test
    fun `unknown LIVE event type surfaces generically`() {
        // Live stream events all carry a timestamp — never swallow them.
        val json = """{"type":"loop_detected","reason":"repeated tool call","timestamp":"2026-06-12T10:00:00Z"}"""
        val out = GeminiMessageParser.parse(json)
        assertEquals(1, out.size)
        val n = out.first() as AgentMessage.EventNote
        assertTrue(n.label.contains("loop detected"))
        assertTrue(n.label.contains("repeated tool call"))
    }

    @Test
    fun `unknown saved-file record type stays hidden`() {
        // No timestamp → mutation-log noise from a saved session file;
        // generic-noting it would spray rows on every re-hydration.
        assertTrue(GeminiMessageParser.parse("""{"type":"checkpoint","id":"x"}""").isEmpty())
    }
}
