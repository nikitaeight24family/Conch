package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.copilot.CopilotMessageParser
import ai.eight24family.conch.agent.copilot.CopilotSpec
import ai.eight24family.conch.agent.copilot.copilotLabelFromId
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [CopilotMessageParser] + CopilotSpec surfaces to the event vocabulary
 * `@github/copilot` 1.0.80 itself ships (`schemas/session-events.schema.json`
 * + `copilot-sdk/generated/session-events.d.ts`) and to live captures of the
 * envelope (`{type,data,id,timestamp,parentId,ephemeral?}`, 2026-08-27).
 */
class CopilotMessageParserTest {

    /** VERBATIM live capture (uuid tails trimmed). */
    private val mcpLine =
        """{"type":"session.mcp_server_status_changed","data":{"serverName":"github-mcp-server","status":"connected"},"ephemeral":true,"id":"3deee52c-4f49-4d1b-a1b3-8541a72437c6","timestamp":"2026-08-27T17:00:01.032Z","parentId":"4db6a695-04ee-466f-b26b-74dae52ad74c"}"""

    private val startLine =
        """{"type":"session.start","data":{"sessionId":"4db6a695-04ee-466f-b26b-74dae52ad74c","copilotVersion":"1.0.80","selectedModel":"claude-sonnet-4.5","reasoningEffort":"medium","context":{"cwd":"/home/u/proj","branch":"main"}},"id":"aaaa1111-0000-0000-0000-000000000001","timestamp":"2026-08-27T16:59:52.784Z","parentId":null}"""

    private val userLine =
        """{"type":"user.message","data":{"content":"Fix the login bug","attachments":[],"agentMode":"interactive"},"id":"aaaa1111-0000-0000-0000-000000000002","timestamp":"2026-08-27T17:00:02.000Z","parentId":"aaaa1111-0000-0000-0000-000000000001"}"""

    private val deltaLine =
        """{"type":"assistant.message_delta","data":{"messageId":"m1","deltaContent":"Lo"},"ephemeral":true,"id":"aaaa1111-0000-0000-0000-000000000003","timestamp":"2026-08-27T17:00:03.000Z","parentId":"aaaa1111-0000-0000-0000-000000000002"}"""

    private val messageLine =
        """{"type":"assistant.message","data":{"messageId":"m1","content":"Looking at the auth flow now.","toolRequests":[]},"id":"aaaa1111-0000-0000-0000-000000000004","timestamp":"2026-08-27T17:00:04.000Z","parentId":"aaaa1111-0000-0000-0000-000000000003"}"""

    private val toolStartLine =
        """{"type":"tool.execution_start","data":{"toolCallId":"call_1","toolName":"shell","arguments":{"command":"grep -r login src/"}},"id":"aaaa1111-0000-0000-0000-000000000005","timestamp":"2026-08-27T17:00:05.000Z","parentId":"aaaa1111-0000-0000-0000-000000000004"}"""

    private val toolDoneLine =
        """{"type":"tool.execution_complete","data":{"toolCallId":"call_1","success":true,"result":{"content":"src/auth.ts:42: login()"}},"id":"aaaa1111-0000-0000-0000-000000000006","timestamp":"2026-08-27T17:00:06.000Z","parentId":"aaaa1111-0000-0000-0000-000000000005"}"""

    private val usageLine =
        """{"type":"assistant.usage","data":{"model":"claude-sonnet-4.5","inputTokens":24939,"outputTokens":1430,"cacheReadTokens":21263,"reasoningTokens":154,"copilotUsage":{"totalNanoAiu":500000000}},"id":"aaaa1111-0000-0000-0000-000000000007","timestamp":"2026-08-27T17:00:07.000Z","parentId":"aaaa1111-0000-0000-0000-000000000006"}"""

    private val idleLine =
        """{"type":"session.idle","data":{},"id":"aaaa1111-0000-0000-0000-000000000008","timestamp":"2026-08-27T17:00:08.000Z","parentId":"aaaa1111-0000-0000-0000-000000000007"}"""

    private val errorLine =
        """{"type":"session.error","data":{"errorType":"quota","errorCode":"quota_exceeded","message":"You've run out of your included AI credits for the month.","statusCode":402},"id":"aaaa1111-0000-0000-0000-000000000009","timestamp":"2026-08-27T17:00:09.000Z","parentId":null}"""

    // ── Event mapping ──

    @Test
    fun `session start yields System init with model and cwd`() {
        val out = CopilotMessageParser.parse(startLine)
        assertEquals(1, out.size)
        val sys = out.first() as AgentMessage.System
        assertEquals("init", sys.subtype)
        assertEquals("4db6a695-04ee-466f-b26b-74dae52ad74c", sys.sessionId)
        assertEquals("claude-sonnet-4.5", sys.model)
        assertEquals("medium", sys.reasoning)
        assertEquals("/home/u/proj", sys.cwd)
    }

    @Test
    fun `user message yields UserText`() {
        val out = CopilotMessageParser.parse(userLine)
        assertEquals("out=$out", 1, out.size)
        assertEquals("Fix the login bug", (out.first() as AgentMessage.UserText).text)
    }

    @Test
    fun `deltas are dropped, full assistant message upserts by messageId`() {
        assertTrue(CopilotMessageParser.parse(deltaLine).isEmpty())
        val out = CopilotMessageParser.parse(messageLine)
        assertEquals("out=$out", 1, out.size)
        val a = out.first() as AgentMessage.AssistantText
        assertEquals("m1", a.id)
        assertEquals("Looking at the auth flow now.", a.text)
    }

    @Test
    fun `tool events map to ToolUse and ToolResult joined by toolCallId`() {
        val use = CopilotMessageParser.parse(toolStartLine).first() as AgentMessage.ToolUse
        assertEquals("call_1", use.id)
        assertEquals("shell", use.toolName)
        assertTrue(use.input.contains("grep -r login"))
        val res = CopilotMessageParser.parse(toolDoneLine).first() as AgentMessage.ToolResult
        assertEquals("call_1", res.toolUseId)
        assertFalse(res.isError)
        assertTrue(res.output.contains("src/auth.ts:42"))
    }

    @Test
    fun `usage emits the cross-agent token line plus AI credits`() {
        val out = CopilotMessageParser.parse(usageLine)
        assertEquals(1, out.size)
        val n = out.first() as AgentMessage.EventNote
        assertTrue(n.label.contains("tokens"))
        assertTrue("Locale.US k-format: ${n.label}", n.label.contains("in 24.9k"))
        assertTrue(n.label.contains("out 1.4k"))
        assertTrue(n.label.contains("thinking 154"))
        assertTrue(n.label.contains("cached 21.3k"))
        // 500_000_000 nano-AIU = 0.5 AI credits — Copilot's own billing unit.
        assertTrue("credits in: ${n.label}", n.label.contains("0.5000 AI credits"))
    }

    @Test
    fun `session idle is the terminal TurnEnd`() {
        val out = CopilotMessageParser.parse(idleLine)
        assertEquals(1, out.size)
        val end = out.first() as AgentMessage.TurnEnd
        assertEquals("session.idle", end.reason)
    }

    @Test
    fun `quota error surfaces as Error`() {
        val out = CopilotMessageParser.parse(errorLine)
        assertEquals(1, out.size)
        val err = out.first() as AgentMessage.Error
        assertTrue(err.text.contains("AI credits"))
    }

    @Test
    fun `mcp status churn is silent`() {
        assertTrue(CopilotMessageParser.parse(mcpLine).isEmpty())
    }

    @Test
    fun `unknown event surfaces generically, never swallowed`() {
        val line =
            """{"type":"session.future_thing","data":{"message":"brand new"},"id":"aaaa1111-0000-0000-0000-00000000000a","timestamp":"2026-08-27T17:00:10.000Z","parentId":null}"""
        val out = CopilotMessageParser.parse(line)
        assertEquals(1, out.size)
        val n = out.first() as AgentMessage.EventNote
        assertTrue(n.label.contains("session future thing"))
        assertTrue(n.label.contains("brand new"))
    }

    @Test
    fun `stderr prose arrives as Raw`() {
        val out = CopilotMessageParser.parse("Error: Authentication failed (Request ID: 1802)")
        assertEquals(1, out.size)
        assertTrue(out.first() is AgentMessage.Raw)
    }

    // ── Turn-state projection ──

    @Test
    fun `prompt with work in progress is in flight`() {
        val recs = CopilotSpec.projectTurnStateRecords(
            sequenceOf(startLine, userLine, messageLine, toolStartLine),
        )
        val sig = CopilotSpec.inferTurnState(recs, frozenForMs = 1_000L)
        assertTrue(sig.inFlight)
        assertFalse(sig.thinking) // assistant/tool activity has started
        assertFalse(sig.turnComplete)
        assertEquals(
            java.time.Instant.parse("2026-08-27T17:00:02.000Z").toEpochMilli(),
            sig.turnStartMs,
        )
    }

    @Test
    fun `session idle is the definitive done and tokens sum per turn`() {
        val recs = CopilotSpec.projectTurnStateRecords(
            sequenceOf(startLine, userLine, messageLine, usageLine, idleLine),
        )
        val sig = CopilotSpec.inferTurnState(recs, frozenForMs = null)
        assertFalse(sig.inFlight)
        assertTrue(sig.turnComplete)
        // In-flight token counting is covered by the same records minus idle:
        val running = CopilotSpec.projectTurnStateRecords(
            sequenceOf(startLine, userLine, messageLine, usageLine),
        )
        val runSig = CopilotSpec.inferTurnState(running, frozenForMs = 0L)
        assertTrue(runSig.inFlight)
        assertEquals(1430L, runSig.tokens)
    }

    // ── Command construction ──

    @Test
    fun `exec command is jsonl, no-ask-user, allow-gated`() {
        val cmd = CopilotSpec.buildExecCommand(
            ExecInput(
                text = "hello",
                resumeId = null,
                model = "claude-sonnet-4.5",
                approvalMode = AgentApprovalMode.AUTO,
                cwdSnapshot = null,
                reasoningEffort = "high",
            ),
        )
        assertTrue(cmd.contains("copilot -p 'hello'"))
        assertTrue(cmd.contains("--output-format json"))
        assertTrue(cmd.contains("--no-auto-update"))
        assertTrue(cmd.contains("--no-ask-user"))
        assertTrue(cmd.contains("--allow-all-tools"))
        assertFalse(cmd.contains("--yolo"))
        assertTrue(cmd.contains("--model 'claude-sonnet-4.5'"))
        assertTrue(cmd.contains("--reasoning-effort 'high'"))
        assertTrue("stderr carries the error prose — merge it", cmd.endsWith("2>&1"))
    }

    @Test
    fun `yolo and plan map to copilot's own flags`() {
        val yolo = CopilotSpec.buildExecCommand(
            ExecInput("x", null, null, AgentApprovalMode.YOLO, null),
        )
        assertTrue(yolo.contains("--yolo"))
        val plan = CopilotSpec.buildExecCommand(
            ExecInput("x", null, null, AgentApprovalMode.PLAN, null),
        )
        assertTrue(plan.contains("--plan"))
        assertFalse(plan.contains("--allow-all-tools"))
    }

    @Test
    fun `resume beats pre-set id and fresh chats use session-id`() {
        val resume = CopilotSpec.buildExecCommand(
            ExecInput(
                "x", "4db6a695-04ee-466f-b26b-74dae52ad74c", null,
                AgentApprovalMode.SAFE, null,
                preGeneratedSessionId = "ffffffff-0000-4000-8000-000000000000",
            ),
        )
        assertTrue(resume.contains("--resume='4db6a695-04ee-466f-b26b-74dae52ad74c'"))
        assertFalse(resume.contains("--session-id"))
        val fresh = CopilotSpec.buildExecCommand(
            ExecInput(
                "x", null, null, AgentApprovalMode.SAFE, null,
                preGeneratedSessionId = "ffffffff-0000-4000-8000-000000000000",
            ),
        )
        assertTrue(fresh.contains("--session-id 'ffffffff-0000-4000-8000-000000000000'"))
    }

    // ── Misc spec surfaces ──

    @Test
    fun `model labels prettify like the CLI's own naming`() {
        assertEquals("Claude Sonnet 4.5", copilotLabelFromId("claude-sonnet-4.5"))
        assertEquals("GPT-5.6 Sol", copilotLabelFromId("gpt-5.6-sol"))
        assertEquals("Gemini 3.7 Flash", copilotLabelFromId("gemini-3.7-flash"))
        assertEquals("Kimi K3", copilotLabelFromId("kimi-k3"))
        assertEquals("Auto", copilotLabelFromId("auto"))
    }

    @Test
    fun `session preview extracts the prompt from a cut user event`() {
        val cut = userLine.take(80) // mid-JSON cut, closing quote gone
        val preview = CopilotSpec.extractSessionPreview(cut)
        assertEquals("Fix the login bug", preview)
    }

    @Test
    fun `session title rides the unit separator`() {
        val us = 0x1F.toChar()
        val raw = "Auth investigation$us$userLine"
        assertEquals("Auth investigation", CopilotSpec.extractSessionTitle(raw))
        assertEquals("Fix the login bug", CopilotSpec.extractSessionPreview(raw))
    }

    @Test
    fun `delete removes the whole session directory, guarded`() {
        val cmd = CopilotSpec.deleteSessionCommand(
            "4db6a695-04ee-466f-b26b-74dae52ad74c",
            "/home/u/.copilot/session-state/4db6a695-04ee-466f-b26b-74dae52ad74c/events.jsonl",
        )
        assertTrue(cmd.contains("rm -rf"))
        assertTrue(cmd.contains("*/session-state/*"))
    }

    @Test
    fun `system message for title change carries the title`() {
        val line =
            """{"type":"session.title_changed","data":{"title":"Auth investigation"},"id":"aaaa1111-0000-0000-0000-00000000000b","timestamp":"2026-08-27T17:00:11.000Z","parentId":null}"""
        val out = CopilotMessageParser.parse(line)
        val sys = out.first() as AgentMessage.System
        assertNotNull(sys.title)
        assertEquals("Auth investigation", sys.title)
    }
}
