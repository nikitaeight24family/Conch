package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.claude.ClaudeMessageParser
import ai.eight24family.conch.agent.foldSubagents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app knows about a fan-out, and what it must NOT let a subagent do to
 * the chat.
 *
 * ⚠ EVERY FIXTURE BELOW IS A REAL RECORD, captured 2026-08-18 from
 * `claude --print --output-format stream-json --include-partial-messages
 * --verbose` on 2.1.220 (the exact invocation the app uses) while a
 * general-purpose agent ran. Not hand-written from docs — the previous
 * `agent_progress` reader was written from a guess about where the
 * discriminator lives and silently matched nothing for months.
 */
class SubagentTelemetryTest {

    private fun parse(line: String) = ClaudeMessageParser.parse(line)

    // The subagent's own assistant turn as it arrives on the MAIN stream.
    private val subagentTurn = """
        {"type":"assistant","message":{"model":"claude-sonnet-5","id":"msg_011Ce94ARNje7xMRmC86298e",
        "type":"message","role":"assistant","content":[{"type":"text","text":"pong"}],
        "usage":{"input_tokens":2,"cache_creation_input_tokens":25109,"cache_read_input_tokens":0,
        "output_tokens":1}},"parent_tool_use_id":"toolu_01Qq8ze7enn7ypz4ZtCTemUp",
        "session_id":"b2a314cc","uuid":"0428052b","subagent_type":"general-purpose",
        "task_description":"ping test"}
    """.trimIndent().replace("\n", "")

    private val mainTurn = """
        {"type":"assistant","message":{"model":"claude-opus-5","id":"msg_main","type":"message",
        "role":"assistant","content":[{"type":"text","text":"working on it"}]},
        "parent_tool_use_id":null,"session_id":"b2a314cc"}
    """.trimIndent().replace("\n", "")

    private val taskStarted = """
        {"type":"system","subtype":"task_started","task_id":"ac119b73f9ca60c11",
        "tool_use_id":"toolu_01Qq8ze7enn7ypz4ZtCTemUp","description":"ping test",
        "subagent_type":"general-purpose","task_type":"local_agent",
        "prompt":"Reply with the single word: pong","session_id":"b2a314cc"}
    """.trimIndent().replace("\n", "")

    private val taskProgress = """
        {"type":"system","subtype":"task_progress","task_id":"ac119b73f9ca60c11",
        "tool_use_id":"toolu_01Qq8ze7enn7ypz4ZtCTemUp","description":"ping test",
        "subagent_type":"general-purpose","usage":{"total_tokens":18400,"tool_uses":6,
        "duration_ms":74000},"last_tool_name":"Grep"}
    """.trimIndent().replace("\n", "")

    private val taskNotification = """
        {"type":"system","subtype":"task_notification","task_id":"ac119b73f9ca60c11",
        "tool_use_id":"toolu_01Qq8ze7enn7ypz4ZtCTemUp","status":"completed","output_file":"/tmp/x",
        "summary":"pong","usage":{"total_tokens":25112,"tool_uses":0,"duration_ms":2636}}
    """.trimIndent().replace("\n", "")

    private val taskUpdatedFailed = """
        {"type":"system","subtype":"task_updated","task_id":"ac119b73f9ca60c11",
        "patch":{"status":"failed","error":"tool limit exceeded","end_time":1787003232439}}
    """.trimIndent().replace("\n", "")

    private val bashTaskStarted = """
        {"type":"system","subtype":"task_started","task_id":"bash1","tool_use_id":"toolu_bash",
        "description":"./verify.sh","task_type":"local_bash"}
    """.trimIndent().replace("\n", "")

    private fun spawn(toolUseId: String) = AgentMessage.ToolUse(
        id = toolUseId,
        toolName = "Agent",
        input = """{"subagent_type":"general-purpose","description":"ping test"}""",
    )

    // ───────── The model picker must report the CHAT's model ─────────

    @Test
    fun `a subagent turn never reports a model`() {
        val out = parse(subagentTurn)
        // No System row at all → observedModel (which reads the LAST System with
        // a model) cannot be dragged onto the subagent's model. This IS the bug.
        assertTrue(
            "a subagent turn must not emit an observed-model row: $out",
            out.none { it is AgentMessage.System },
        )
    }

    @Test
    fun `the main agent still reports its model`() {
        val out = parse(mainTurn)
        val sys = out.filterIsInstance<AgentMessage.System>().firstOrNull()
        assertEquals("claude-opus-5", sys?.model)
    }

    @Test
    fun `a subagent turn never becomes a chat row`() {
        val out = parse(subagentTurn)
        assertTrue(
            "subagent prose must stay out of the transcript: $out",
            out.none { it is AgentMessage.AssistantText },
        )
        assertEquals(1, out.size)
        val act = out[0] as AgentMessage.SubagentActivity
        assertEquals("toolu_01Qq8ze7enn7ypz4ZtCTemUp", act.parentToolUseId)
        // …but its words are kept on the record, so a fan-out stays searchable.
        assertEquals("pong", act.text)
    }

    @Test
    fun `a chat message that merely quotes the wire format still renders`() {
        // Fail-open: the interception decides on the PARSED top-level field, so a
        // user pasting this JSON into the chat can never make their message vanish.
        val quoted = """
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text",
            "text":"look: \"parent_tool_use_id\":\"toolu_x\" is the join key"}]}}
        """.trimIndent().replace("\n", "")
        val out = parse(quoted)
        assertTrue(
            "a quoted wire format must not be swallowed: $out",
            out.any { it is AgentMessage.AssistantText },
        )
    }

    // ───────── Usage, model and status reach the roster ─────────

    @Test
    fun `subagent turn contributes its usage and model`() {
        val act = parse(subagentTurn)[0] as AgentMessage.SubagentActivity
        // 2 + 25109 + 0 + 1
        assertEquals(25_112L, act.tokens)
        assertEquals("claude-sonnet-5", act.model)
    }

    @Test
    fun `task_progress carries tokens tool count duration and the live tool`() {
        val act = parse(taskProgress).filterIsInstance<AgentMessage.SubagentActivity>().single()
        assertEquals(18_400L, act.totalTokens)
        assertEquals(6, act.toolUses)
        assertEquals(74_000L, act.durationMs)
        assertEquals("Grep", act.lastTool)
        // A progress tick must NOT claim a status — inventing "running" here
        // would resurrect an agent that already completed.
        assertNull(act.status)
        assertFalse(act.done)
    }

    @Test
    fun `task events keep their chat note as well`() {
        // The one-line note drives the pinned background-task row; the structured
        // twin drives the roster. Losing either is a regression.
        val out = parse(taskProgress)
        assertTrue(out.any { it is AgentMessage.EventNote })
        assertTrue(out.any { it is AgentMessage.SubagentActivity })
    }

    @Test
    fun `a background bash task is not an agent`() {
        val out = parse(bashTaskStarted)
        assertTrue(
            "local_bash belongs to the task board, not the agent roster: $out",
            out.none { it is AgentMessage.SubagentActivity },
        )
        assertTrue(out.any { it is AgentMessage.EventNote })
    }

    @Test
    fun `nested agent_progress is recognised`() {
        // The discriminator is at data.type — reading only the top level (which
        // says "progress") is what made the roster blind to these.
        val line = """
            {"type":"progress","parentToolUseID":"toolu_01Qq8ze7enn7ypz4ZtCTemUp",
            "toolUseID":"agent_1","data":{"type":"agent_progress","agentId":"ag7",
            "agentType":"general-purpose","description":"ping test","resolvedModel":"claude-haiku-4-5",
            "message":{"role":"assistant","usage":{"output_tokens":10}}}}
        """.trimIndent().replace("\n", "")
        val act = parse(line).filterIsInstance<AgentMessage.SubagentActivity>().single()
        assertEquals("ag7", act.agentId)
        assertEquals("toolu_01Qq8ze7enn7ypz4ZtCTemUp", act.parentToolUseId)
        assertEquals("general-purpose", act.subagentType)
        assertEquals("claude-haiku-4-5", act.model)
        assertEquals(10L, act.tokens)
    }

    // ───────── The fold ─────────

    @Test
    fun `roster shows the CLI total not the sum of totals`() {
        // task_progress and task_notification both report a CUMULATIVE total.
        // Summing them (as the incremental per-turn usage is summed) would bill
        // the agent once per tick — 18.4k + 25.1k for an agent that used 25.1k.
        val msgs = listOf(spawn("toolu_01Qq8ze7enn7ypz4ZtCTemUp")) +
            parse(taskStarted) + parse(taskProgress) + parse(taskNotification)
        val roster = foldSubagents(msgs)
        assertEquals(1, roster.size)
        assertEquals(25_112L, roster[0].tokens)
        assertEquals("completed", roster[0].status)
        assertEquals("pong", roster[0].summary)
        assertTrue(roster[0].done)
    }

    @Test
    fun `duration wins over an elapsed stamp and lands in seconds`() {
        val roster = foldSubagents(
            listOf(spawn("toolu_01Qq8ze7enn7ypz4ZtCTemUp")) + parse(taskProgress),
        )
        assertEquals(74L, roster[0].elapsedSeconds)
        assertEquals(6, roster[0].toolUses)
        assertEquals("Grep", roster[0].lastTool)
    }

    @Test
    fun `a task_updated with only a task_id still lands on the right agent`() {
        // THE join that needs the taskId↔toolUseId pairing: task_updated ships no
        // tool_use_id, so without remembering the pair from task_started the
        // failure would land nowhere and the agent would spin forever.
        val msgs = listOf(spawn("toolu_01Qq8ze7enn7ypz4ZtCTemUp")) +
            parse(taskStarted) + parse(taskUpdatedFailed)
        val roster = foldSubagents(msgs)
        assertEquals(1, roster.size)
        assertEquals("failed", roster[0].status)
        assertEquals("tool limit exceeded", roster[0].error)
        assertTrue(roster[0].done)
    }

    @Test
    fun `an unpaired task_updated is dropped rather than inventing an agent`() {
        val roster = foldSubagents(parse(taskUpdatedFailed))
        assertTrue("no spawn, no pairing → no row: $roster", roster.isEmpty())
    }

    @Test
    fun `the live stream alone is enough to show tokens and model`() {
        // Before any task_progress tick (short agents finish first), the roster
        // still has to answer "how much did it cost, on what".
        val roster = foldSubagents(listOf(spawn("toolu_01Qq8ze7enn7ypz4ZtCTemUp")) + parse(subagentTurn))
        assertEquals(25_112L, roster[0].tokens)
        assertEquals("claude-sonnet-5", roster[0].model)
    }
}
