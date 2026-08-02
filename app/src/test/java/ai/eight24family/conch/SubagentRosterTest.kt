package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.foldSubagents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster behind the CLI's "← 1 agent · ↓ to manage" footer. Conch used to
 * show subagents nowhere at all (user, 2026-07-23).
 */
class SubagentRosterTest {

    private fun task(id: String, type: String, desc: String) = AgentMessage.ToolUse(
        id = id,
        toolName = "Task",
        input = """{"subagent_type":"$type","description":"$desc"}""",
    )

    @Test
    fun `Task tool use seeds an agent with its type and task`() {
        val roster = foldSubagents(listOf(task("t1", "general-purpose", "Inventory HPAF gateway core")))
        assertEquals(1, roster.size)
        assertEquals("general-purpose", roster[0].type)
        assertEquals("Inventory HPAF gateway core", roster[0].task)
        assertFalse(roster[0].done)
    }

    @Test
    fun `activity accumulates tokens onto the spawning Task via parentToolUseId`() {
        val roster = foldSubagents(
            listOf(
                task("t1", "general-purpose", "IAL"),
                AgentMessage.SubagentActivity("a", agentId = "ag1", parentToolUseId = "t1", tokens = 30_000),
                AgentMessage.SubagentActivity("b", agentId = "ag1", parentToolUseId = "t1", tokens = 6_600, elapsedSeconds = 42),
            ),
        )
        assertEquals(1, roster.size)
        assertEquals(36_600L, roster[0].tokens)
        assertEquals(42L, roster[0].elapsedSeconds)
    }

    @Test
    fun `the Task tool result is what marks the agent finished`() {
        val roster = foldSubagents(
            listOf(
                task("t1", "general-purpose", "PIE engine"),
                AgentMessage.SubagentActivity("a", agentId = "ag1", parentToolUseId = "t1", tokens = 100),
                AgentMessage.ToolResult(id = "r1", toolUseId = "t1", output = "done", isError = false),
            ),
        )
        assertTrue(roster[0].done)
    }

    @Test
    fun `several agents keep launch order and count independently`() {
        val roster = foldSubagents(
            listOf(
                task("t1", "general-purpose", "gateway core"),
                task("t2", "general-purpose", "audit + Merkle"),
                AgentMessage.SubagentActivity("a", agentId = "x", parentToolUseId = "t2", tokens = 39_400),
                AgentMessage.SubagentActivity("b", agentId = "y", parentToolUseId = "t1", tokens = 36_600),
                AgentMessage.ToolResult(id = "r", toolUseId = "t1", output = "ok", isError = false),
            ),
        )
        assertEquals(listOf("t1", "t2"), roster.map { it.key })
        assertEquals(36_600L, roster[0].tokens)
        assertTrue(roster[0].done)
        assertEquals(39_400L, roster[1].tokens)
        assertFalse("t2 never returned, so it is still running", roster[1].done)
    }

    @Test
    fun `activity with no parent falls back to agentId so it is never dropped`() {
        val roster = foldSubagents(
            listOf(AgentMessage.SubagentActivity("a", agentId = "ag9", parentToolUseId = null, tokens = 12)),
        )
        assertEquals(1, roster.size)
        assertEquals("ag9", roster[0].key)
    }

    @Test
    fun `non-Task tools are not agents`() {
        val roster = foldSubagents(
            listOf(AgentMessage.ToolUse(id = "b1", toolName = "Bash", input = """{"command":"ls"}""")),
        )
        assertTrue(roster.isEmpty())
    }
}
