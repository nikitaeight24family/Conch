package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.foldSubagents
import ai.eight24family.conch.agent.foldTaskOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Work that is over must stop claiming to be alive.
 *
 * A completion event is the ONLY thing that used to retire a subagent or a
 * background task — so a disconnect, a reinstall or a killed process, all of
 * which drop events on the floor, left them running forever. The owner's chat
 * showed "5 agents · 5 live" and "waiting for a background task" under a turn
 * that had already printed its final summary (2026-08-29).
 *
 * The rules that replaced "wait for a completion we may never get" are both
 * about turn boundaries, and each has a case it must NOT break: an agent can
 * genuinely still be running when the next user message arrives (this app
 * queues sends), and a background task is *designed* to outlive its turn.
 */
class ZombieWorkTest {

    private fun task(id: String, type: String, desc: String) = AgentMessage.ToolUse(
        id = id,
        toolName = "Task",
        input = """{"subagent_type":"$type","description":"$desc"}""",
    )

    private fun user(id: String) = AgentMessage.UserText(id = id, text = "next")

    private fun result(id: String) = AgentMessage.Result(id = id, subtype = "success", text = "done")

    private fun bgTask(id: String, taskId: String, parent: String, done: Boolean = false) =
        AgentMessage.SubagentActivity(
            id = id,
            agentId = null,
            parentToolUseId = parent,
            taskId = taskId,
            taskType = "background_command",
            done = done,
        )

    // ── subagents ────────────────────────────────────────────────────────────

    @Test
    fun `an unfinished agent from an earlier turn dies once a later turn ends`() {
        val roster = foldSubagents(
            listOf(
                task("t1", "general-purpose", "audit"),
                user("u2"),
                result("r2"),
            ),
        )
        // No ToolResult for t1 ever arrived — but a later turn has closed, so the
        // CLI has nothing left to run it in.
        assertTrue(roster.isEmpty())
    }

    @Test
    fun `it survives while the new turn is still open`() {
        val roster = foldSubagents(listOf(task("t1", "general-purpose", "audit"), user("u2")))
        // A queued send does not stop a fan-out: no result yet, so it still counts.
        assertEquals(1, roster.size)
    }

    @Test
    fun `the current turn's own agents are never touched by the rule`() {
        val roster = foldSubagents(listOf(user("u1"), task("t1", "Explore", "current"), result("r1")))
        assertEquals(1, roster.size)
    }

    // ── background tasks ─────────────────────────────────────────────────────

    @Test
    fun `an unconfirmed background task from two turns back stops being claimed`() {
        val own = foldTaskOwnership(
            listOf(
                AgentMessage.ToolUse(id = "b1", toolName = "Bash", input = """{"command":"make"}"""),
                bgTask("s1", taskId = "task-1", parent = "b1"),
                user("u2"),
                result("r2"),
                user("u3"),
                result("r3"),
            ),
        )
        assertEquals(0, own.ownRunningTasks)
    }

    @Test
    fun `one turn of age proves nothing — it outlives its turn by design`() {
        val own = foldTaskOwnership(
            listOf(
                AgentMessage.ToolUse(id = "b1", toolName = "Bash", input = """{"command":"make"}"""),
                bgTask("s1", taskId = "task-1", parent = "b1"),
                user("u2"),
                result("r2"),
            ),
        )
        // "resumes on its own" is the whole point: still pending, still shown.
        assertEquals(1, own.ownRunningTasks)
    }

    @Test
    fun `a snapshot is authoritative and never expires`() {
        val own = foldTaskOwnership(
            listOf(
                AgentMessage.ToolUse(id = "b1", toolName = "Bash", input = """{"command":"make"}"""),
                bgTask("s1", taskId = "task-1", parent = "b1"),
                user("u2"),
                result("r2"),
                user("u3"),
                result("r3"),
                AgentMessage.BackgroundTasks(
                    id = "snap",
                    tasks = listOf(
                        AgentMessage.BackgroundTasks.Entry(
                            taskId = "task-1",
                            description = "make",
                            taskType = "background_command",
                        ),
                    ),
                ),
            ),
        )
        // The CLI says it IS running — age is irrelevant next to that.
        assertEquals(1, own.ownRunningTasks)
    }
}
