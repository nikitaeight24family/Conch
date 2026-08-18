package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.claude.ClaudeMessageParser
import ai.eight24family.conch.agent.foldSubagents
import ai.eight24family.conch.agent.foldTaskOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose background task is it?
 *
 * The CLI keeps ONE task registry per session and reports all of it on the main
 * stream, so with a fan-out running, most `task_*` traffic is the AGENTS' shell
 * commands. Rendering them all as chat rows buried the conversation under `task ·
 * completed · Background command "…"` lines, plus a `background tasks changed`
 * row on every change.
 *
 * The owner is NOT on the wire — the registry has `agentId`, the event does not —
 * so it is inferred: the session's own background command has its `tool_use`
 * block in the main transcript; an agent's does not.
 */
class TaskOwnershipTest {

    private fun parse(line: String) = ClaudeMessageParser.parse(line)
    private fun j(s: String) = s.trimIndent().replace("\n", "")

    /** A background Bash the MAIN agent started: its tool_use is in the chat. */
    private fun ownBashToolUse(id: String) = AgentMessage.ToolUse(
        id = id,
        toolName = "Bash",
        input = """{"command":"./verify.sh","run_in_background":true}""",
    )

    private fun bashTaskStarted(taskId: String, toolUseId: String) = j(
        """
        {"type":"system","subtype":"task_started","task_id":"$taskId",
        "tool_use_id":"$toolUseId","description":"Background command \"Run acceptance\"",
        "task_type":"local_bash"}
        """,
    )

    private val agentSpawn = AgentMessage.ToolUse(
        id = "toolu_agent",
        toolName = "Agent",
        input = """{"subagent_type":"general-purpose","description":"audit clans"}""",
    )

    private val agentTaskStarted = j(
        """
        {"type":"system","subtype":"task_started","task_id":"agenttask1",
        "tool_use_id":"toolu_agent","description":"audit clans",
        "subagent_type":"general-purpose","task_type":"local_agent"}
        """,
    )

    private val snapshot = j(
        """
        {"type":"system","subtype":"background_tasks_changed","tasks":[
        {"task_id":"bash_agent1","task_type":"local_bash","description":"Run session_probe"},
        {"task_id":"bash_agent2","task_type":"local_bash","description":"Run casual_probe"}]}
        """,
    )

    // ───────── background_tasks_changed ─────────

    @Test
    fun `the background task set is never a chat row`() {
        val out = parse(snapshot)
        assertTrue(
            "it fires on every change — a note per event is the flood: $out",
            out.none { it is AgentMessage.EventNote },
        )
        val snap = out.single() as AgentMessage.BackgroundTasks
        assertEquals(2, snap.tasks.size)
        assertEquals("bash_agent1", snap.tasks[0].taskId)
        assertEquals("local_bash", snap.tasks[0].taskType)
    }

    @Test
    fun `the snapshot upserts because it has REPLACE semantics`() {
        // Same stable id twice → one row, latest wins. Two ids would accumulate
        // one dead snapshot per change, which is what we are fixing.
        val a = parse(snapshot).single().id
        val b = parse(j("""{"type":"system","subtype":"background_tasks_changed","tasks":[]}""")).single().id
        assertEquals(a, b)
    }

    // ───────── ownership ─────────

    @Test
    fun `a task this session started keeps its chat row`() {
        val msgs = listOf(ownBashToolUse("toolu_mine")) + parse(bashTaskStarted("t1", "toolu_mine"))
        val own = foldTaskOwnership(msgs)
        assertTrue("sysevt-task-t1" in own.ownTaskNoteIds)
        assertEquals(0, own.agentTaskCount)
    }

    @Test
    fun `a task with no tool_use of ours belongs to the agents`() {
        // No ToolUse row for toolu_theirs — a subagent called Bash, and subagent
        // traffic folds into SubagentActivity, so it never becomes a ToolUse.
        val own = foldTaskOwnership(parse(bashTaskStarted("t2", "toolu_theirs")))
        assertTrue("sysevt-task-t2" !in own.ownTaskNoteIds)
        assertEquals(1, own.agentTaskCount)
    }

    @Test
    fun `an agent task is never a session task row`() {
        // Even though the Agent tool_use IS in our transcript: the roster shows
        // an agent far better than a note can, so it must not be both.
        val msgs = listOf(agentSpawn) + parse(agentTaskStarted)
        val own = foldTaskOwnership(msgs)
        assertTrue("sysevt-task-agenttask1" !in own.ownTaskNoteIds)
        // …and it is not counted as a background COMMAND either.
        assertEquals(0, own.agentTaskCount)
    }

    @Test
    fun `the CLI snapshot drives the live count`() {
        val own = foldTaskOwnership(
            parse(bashTaskStarted("bash_agent1", "toolu_x")) +
                parse(bashTaskStarted("bash_agent2", "toolu_y")) +
                parse(snapshot),
        )
        assertEquals(2, own.agentTaskCount)
    }

    @Test
    fun `a task that dropped out of the snapshot stops being counted`() {
        // REPLACE semantics: absence from the newest snapshot means finished,
        // even if no terminal task_updated ever arrived for it.
        val own = foldTaskOwnership(
            parse(bashTaskStarted("bash_agent1", "toolu_x")) +
                parse(snapshot) +
                parse(j("""{"type":"system","subtype":"background_tasks_changed","tasks":[]}""")),
        )
        assertEquals(0, own.agentTaskCount)
    }

    @Test
    fun `our own running task is not counted as the agents'`() {
        val own = foldTaskOwnership(
            listOf(ownBashToolUse("toolu_mine")) +
                parse(bashTaskStarted("bash_agent1", "toolu_mine")) +
                parse(snapshot),
        )
        // The snapshot lists bash_agent1 + bash_agent2; the first is ours.
        assertEquals(1, own.agentTaskCount)
    }

    // ───────── the roster stays agents-only ─────────

    @Test
    fun `a background command never becomes an agent row`() {
        val roster = foldSubagents(
            listOf(ownBashToolUse("toolu_mine")) + parse(bashTaskStarted("t1", "toolu_mine")),
        )
        assertTrue("local_bash is a command, not an agent: $roster", roster.isEmpty())
    }

    @Test
    fun `agents still make rows next to background commands`() {
        val roster = foldSubagents(
            listOf(agentSpawn) + parse(agentTaskStarted) +
                parse(bashTaskStarted("t9", "toolu_theirs")),
        )
        assertEquals(1, roster.size)
        assertEquals("general-purpose", roster[0].type)
    }

    // ───────── skip_transcript ─────────

    @Test
    fun `a task the CLI marked skip_transcript gets no chat row`() {
        // The CLI sets this on its OWN internal forks (reactive compaction,
        // memory extraction, prompt suggestion, "dream") — work the user never
        // asked for and cannot act on.
        val out = parse(
            j(
                """
                {"type":"system","subtype":"task_notification","task_id":"internal1",
                "status":"completed","summary":"","skip_transcript":true}
                """,
            ),
        )
        assertTrue("skip_transcript means keep it out: $out", out.none { it is AgentMessage.EventNote })
    }

    @Test
    fun `a normal task still gets its row`() {
        val out = parse(bashTaskStarted("t1", "toolu_mine"))
        assertTrue(out.any { it is AgentMessage.EventNote })
    }

    // ───────── id stability: the dedup contract ─────────

    @Test
    fun `the same task event parsed twice yields the same row id`() {
        // The tail-poll mirrors JSONL records that already came down the live
        // stream (a long fan-out goes stream-silent by design), and the ONLY
        // defence against a double-add for these rows is the id. With a random
        // uuid the roster billed every agent twice.
        val a = parse(bashTaskStarted("t1", "toolu_mine")).map { it.id }
        val b = parse(bashTaskStarted("t1", "toolu_mine")).map { it.id }
        assertEquals(a, b)
    }

    @Test
    fun `task events of one task collapse to at most one row per subtype`() {
        // Bounded history: a 20-agent fan-out ticking progress must not append a
        // row per tick forever. Per SUBTYPE, not per task, because task_started
        // is the only event carrying tool_use_id — the thing ownership needs —
        // so a later task_updated must not replace it.
        val started = parse(bashTaskStarted("t1", "toolu_mine"))
            .filterIsInstance<AgentMessage.SubagentActivity>().single().id
        val progressA = parse(
            j(
                """
                {"type":"system","subtype":"task_progress","task_id":"t1",
                "tool_use_id":"toolu_mine","usage":{"total_tokens":10,"tool_uses":1,"duration_ms":5}}
                """,
            ),
        ).filterIsInstance<AgentMessage.SubagentActivity>().single().id
        val progressB = parse(
            j(
                """
                {"type":"system","subtype":"task_progress","task_id":"t1",
                "tool_use_id":"toolu_mine","usage":{"total_tokens":99,"tool_uses":7,"duration_ms":50}}
                """,
            ),
        ).filterIsInstance<AgentMessage.SubagentActivity>().single().id
        assertEquals("two progress ticks share one row", progressA, progressB)
        assertTrue("started keeps its own row", started != progressA)
    }

    @Test
    fun `a background command's completion cannot invent an agent row`() {
        // THE hole this closes: a bash task_notification carries no task_type and
        // no subagent_type, but it DOES carry tool_use_id — so it walked into the
        // roster and gave a shell command a nameless agent row.
        val notification = j(
            """
            {"type":"system","subtype":"task_notification","task_id":"t1",
            "tool_use_id":"toolu_bash","status":"completed","summary":"done",
            "usage":{"total_tokens":40,"tool_uses":0,"duration_ms":900}}
            """,
        )
        assertTrue(
            "only an agent may open a roster row",
            foldSubagents(parse(notification)).isEmpty(),
        )
    }

    @Test
    fun `a notification still updates an agent row that already exists`() {
        // …while the same event must still land on a REAL agent.
        val roster = foldSubagents(
            listOf(agentSpawn) + parse(agentTaskStarted) + parse(
                j(
                    """
                    {"type":"system","subtype":"task_notification","task_id":"agenttask1",
                    "tool_use_id":"toolu_agent","status":"completed","summary":"audited",
                    "usage":{"total_tokens":1234,"tool_uses":9,"duration_ms":4000}}
                    """,
                ),
            ),
        )
        assertEquals(1, roster.size)
        assertEquals("completed", roster[0].status)
        assertEquals(1234L, roster[0].tokens)
        assertEquals("audited", roster[0].summary)
    }
}
