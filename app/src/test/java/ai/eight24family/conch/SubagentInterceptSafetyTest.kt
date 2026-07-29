package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.claude.ClaudeMessageParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subagent interception must NEVER eat a real chat line.
 *
 * The first version matched the raw substrings `"isSidechain":true` /
 * `"agent_progress"` anywhere in the line and dropped anything that then failed
 * to look like a subagent record. That swallowed `system.init` — the record
 * carrying session_id and the model — so new chats opened with an empty model
 * chip and never answered at all.
 *
 * These are the tests that would have caught it.
 */
class SubagentInterceptSafetyTest {

    private fun parse(line: String) = ClaudeMessageParser.parse(line)

    @Test
    fun `system init survives even when it mentions agent_progress`() {
        // A capability/schema listing that merely contains the word must not
        // make the whole init record disappear.
        val init = """{"type":"system","subtype":"init","session_id":"abc-123",""" +
            """"model":"claude-opus-4-8","tools":["Task","Bash"],""" +
            """"output_types":["assistant","agent_progress","result"]}"""
        val out = parse(init)
        assertTrue(
            "init must not be swallowed, got $out",
            out.isNotEmpty() && out.none { it is AgentMessage.SubagentActivity },
        )
    }

    @Test
    fun `an assistant reply discussing agent_progress stays in the chat`() {
        val reply = """{"type":"assistant","message":{"role":"assistant","content":""" +
            """[{"type":"text","text":"the CLI emits agent_progress with isSidechain set"}]}}"""
        val out = parse(reply)
        assertTrue(
            "a reply about these fields must render, got $out",
            out.any { it is AgentMessage.AssistantText },
        )
    }

    @Test
    fun `an ordinary record carrying isSidechain false is untouched`() {
        val normal = """{"type":"assistant","isSidechain":false,"message":{"role":"assistant",""" +
            """"content":[{"type":"text","text":"hello"}]}}"""
        val out = parse(normal)
        assertTrue(
            "isSidechain=false is the NORMAL case, got $out",
            out.any { it is AgentMessage.AssistantText },
        )
    }

    @Test
    fun `a genuine sidechain turn is still intercepted`() {
        val side = """{"type":"assistant","isSidechain":true,"agentId":"ag1",""" +
            """"parentToolUseID":"t1","message":{"role":"assistant","usage":{"output_tokens":10},""" +
            """"content":[{"type":"text","text":"subagent working"}]}}"""
        val out = parse(side)
        assertTrue(
            "a real sidechain turn must become SubagentActivity, got $out",
            out.any { it is AgentMessage.SubagentActivity },
        )
    }

    @Test
    fun `a genuine agent_progress record is still intercepted`() {
        val prog = """{"type":"agent_progress","agentId":"ag2","parentToolUseID":"t2"}"""
        val out = parse(prog)
        assertTrue(
            "agent_progress must become SubagentActivity, got $out",
            out.any { it is AgentMessage.SubagentActivity },
        )
    }
}
