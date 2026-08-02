package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.claude.ClaudeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session's effort must come from what the CLI itself records.
 *
 * The parser used to recognise only `ultra_effort_enter`, on a June note that
 * the ordinary levels never reach the session file. That stopped being true —
 * the CLI now stamps a top-level `"effort"` on its records — and because we
 * weren't reading it, a session the CLI was running at **xhigh** was displayed
 * as **low** and relaunched with the low thinking budget (proven on the user's
 * own box, 2026-08-02: 2424 `"effort":"xhigh"` records against our own
 * `MAX_THINKING_TOKENS=4096` launch line).
 */
class ClaudeEffortObservedTest {

    private fun observed(line: String): String? =
        ClaudeSpec.parseStreamLine(line)
            .filterIsInstance<AgentMessage.System>()
            .firstOrNull { it.subtype == "reasoning_observed" }
            ?.reasoning

    @Test
    fun `the CLI's own effort field is observed, at every level`() {
        for (level in listOf("low", "medium", "high", "xhigh", "max", "ultracode")) {
            val line = """{"type":"assistant","uuid":"u1","effort":"$level",""" +
                """"message":{"role":"assistant","model":"claude-opus-5","content":"hi"}}"""
            assertEquals("level $level must be observed", level, observed(line))
        }
    }

    @Test
    fun `a record without the field observes nothing`() {
        val line = """{"type":"assistant","uuid":"u1","message":{"role":"assistant","content":"hi"}}"""
        assertNull(observed(line))
    }

    @Test
    fun `the word inside a message is not an effort`() {
        // The value is read out of a raw line, so the closed set is what stops a
        // chat message from becoming a setting.
        val line = """{"type":"assistant","uuid":"u1","message":{"role":"assistant",""" +
            """"content":"use \"effort\":\"whatever-you-like\" in the config"}}"""
        assertNull(observed(line))
    }

    @Test
    fun `observing the effort never costs the chat row`() {
        // FAIL-OPEN: the observation is ADDED, never replaces what the line
        // actually said — the same rule the subagent interception learned the
        // hard way (SUBAGENT-INTERCEPT-FAILS-OPEN-1).
        val line = """{"type":"assistant","uuid":"u1","effort":"xhigh",""" +
            """"message":{"role":"assistant","model":"claude-opus-5","content":"the answer"}}"""
        val msgs = ClaudeSpec.parseStreamLine(line)
        assertTrue(msgs.any { it is AgentMessage.AssistantText && it.text.contains("the answer") })
        assertEquals("xhigh", observed(line))
    }
}
