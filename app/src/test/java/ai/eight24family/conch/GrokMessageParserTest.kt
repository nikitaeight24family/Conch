package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.grok.GrokMessageParser
import ai.eight24family.conch.agent.grok.GrokSpec
import ai.eight24family.conch.agent.grok.grokLabelFromId
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.agent.spec.ExecInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [GrokMessageParser] + the GrokSpec surfaces to REAL captures from
 * `@xai-official/grok` 1.0.5 (live runs, 2026-08-28):
 *  - the live stream is Claude's stream-json wire format by Grok's own
 *    contract (`--output-format streaming-messages-json`) → delegated;
 *  - the saved session file (`updates.jsonl`) is consolidated ACP records;
 *  - native streaming-json `error` lines must surface as Error.
 */
class GrokMessageParserTest {

    // ── Live stream (Claude wire format — delegation) ──

    @Test
    fun `messages-format init line yields System init`() {
        // VERBATIM capture (unauthenticated run, 1.0.5) — trimmed uuid.
        val line = """{"type":"system","subtype":"init","session_id":"01a04437-a5fa-7313-8971-2141cfde619d","apiKeySource":"oauth","model":"grok-4.6","cwd":"/tmp/x","permissionMode":"default","tools":[],"slash_commands":[],"mcp_servers":[],"skills":[],"uuid":"2d8ea4d2-0000-0000-0000-000000000000"}"""
        val out = GrokMessageParser.parse(line)
        val sys = out.filterIsInstance<AgentMessage.System>().firstOrNull()
        assertNotNull("init must parse via the Claude wire format", sys)
        assertEquals("init", sys!!.subtype)
        assertEquals("01a04437-a5fa-7313-8971-2141cfde619d", sys.sessionId)
        assertEquals("grok-4.6", sys.model)
    }

    @Test
    fun `native error line surfaces as Error`() {
        // VERBATIM shape of an unauthenticated json/streaming-json run.
        val line = """{"type":"error","message":"Not signed in. To authenticate without a browser, run:\n  grok login --device-code"}"""
        val out = GrokMessageParser.parse(line)
        assertEquals(1, out.size)
        val err = out.first() as AgentMessage.Error
        assertTrue(err.text.contains("Not signed in"))
    }

    // ── Saved session file: consolidated ACP updates.jsonl ──

    /** VERBATIM line from a live session (2026-08-28). */
    private val userLine =
        """{"timestamp":1787850896,"method":"session/update","params":{"sessionId":"01a04437-a5fa-7313-8971-2141cfde619d","update":{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"Reply with exactly: hi"},"_meta":{"modelId":"grok-4.6","promptIndex":0}},"_meta":{"eventId":"01a04437-a5fa-7313-8971-2141cfde619d-2","agentTimestampMs":1787850894189}}}"""

    private val thoughtLine =
        """{"timestamp":1787850896,"method":"session/update","params":{"sessionId":"01a04437-a5fa-7313-8971-2141cfde619d","update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"The user wants me to reply with exactly \"hi\"."}},"_meta":{"totalTokens":2841,"eventId":"01a04437-a5fa-7313-8971-2141cfde619d-28","agentTimestampMs":1787850896136}}}"""

    private val assistantLine =
        """{"timestamp":1787850896,"method":"session/update","params":{"sessionId":"01a04437-a5fa-7313-8971-2141cfde619d","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}},"_meta":{"totalTokens":2841,"eventId":"01a04437-a5fa-7313-8971-2141cfde619d-29","agentTimestampMs":1787850896136}}}"""

    private val doneLine =
        """{"timestamp":1787850896,"method":"_x.ai/session/update","params":{"sessionId":"01a04437-a5fa-7313-8971-2141cfde619d","update":{"sessionUpdate":"turn_completed","prompt_id":"2edd2a9c-58a8-49f3-9c6a-a968bd28b533","stop_reason":"end_turn","usage":{"inputTokens":12014,"outputTokens":27,"totalTokens":12041,"cachedReadTokens":1664,"cacheCreationTokens":0,"reasoningTokens":26,"modelCalls":1}}}}"""

    @Test
    fun `user chunk replays as UserText with the CLI's own eventId`() {
        val out = GrokMessageParser.parse(userLine)
        assertEquals(1, out.size)
        val u = out.first() as AgentMessage.UserText
        assertEquals("Reply with exactly: hi", u.text)
        assertEquals("01a04437-a5fa-7313-8971-2141cfde619d-2", u.id)
    }

    @Test
    fun `agent chunk replays as AssistantText`() {
        val out = GrokMessageParser.parse(assistantLine)
        assertEquals(1, out.size)
        val a = out.first() as AgentMessage.AssistantText
        assertEquals("hi", a.text)
        assertEquals("01a04437-a5fa-7313-8971-2141cfde619d-29", a.id)
    }

    @Test
    fun `thought chunk replays as a thinking note, not a chat bubble`() {
        val out = GrokMessageParser.parse(thoughtLine)
        assertEquals(1, out.size)
        val n = out.first() as AgentMessage.EventNote
        assertTrue(n.label.startsWith("thinking"))
    }

    @Test
    fun `turn_completed emits the cross-agent token line`() {
        val out = GrokMessageParser.parse(doneLine)
        assertEquals(1, out.size)
        val n = out.first() as AgentMessage.EventNote
        assertTrue(n.label.contains("tokens"))
        assertTrue("in 12.0k in: ${n.label}", n.label.contains("in 12.0k"))
        assertTrue(n.label.contains("out 27"))
        assertTrue(n.label.contains("thinking 26"))
        assertTrue(n.label.contains("cached 1.7k"))
    }

    @Test
    fun `unknown ACP record surfaces generically, never swallowed`() {
        val line =
            """{"timestamp":1787850896,"method":"session/update","params":{"sessionId":"x","update":{"sessionUpdate":"future_thing","detail":"whatever"},"_meta":{"eventId":"x-1"}}}"""
        val out = GrokMessageParser.parse(line)
        assertEquals(1, out.size)
        val n = out.first() as AgentMessage.EventNote
        assertTrue(n.label.contains("future thing"))
    }

    // ── Turn-state projection off the same real lines ──

    @Test
    fun `user chunk with no completion means in flight`() {
        val recs = GrokSpec.projectTurnStateRecords(sequenceOf(userLine, thoughtLine))
        val sig = GrokSpec.inferTurnState(recs, frozenForMs = 1_000L)
        assertTrue(sig.inFlight)
        assertFalse(sig.turnComplete)
        assertEquals(1787850894189L, sig.turnStartMs)
    }

    @Test
    fun `turn_completed is the definitive done`() {
        val recs = GrokSpec.projectTurnStateRecords(
            sequenceOf(userLine, thoughtLine, assistantLine, doneLine),
        )
        val sig = GrokSpec.inferTurnState(recs, frozenForMs = null)
        assertFalse(sig.inFlight)
        assertTrue(sig.turnComplete)
    }

    @Test
    fun `frozen file without completion clears the spinner eventually`() {
        val recs = GrokSpec.projectTurnStateRecords(sequenceOf(userLine))
        val sig = GrokSpec.inferTurnState(recs, frozenForMs = 13 * 60_000L)
        assertFalse(sig.inFlight)
        assertFalse("staleness is a guess, not a completion", sig.turnComplete)
    }

    // ── Command construction ──

    @Test
    fun `exec command uses messages wire format and drops stderr`() {
        val cmd = GrokSpec.buildExecCommand(
            ExecInput(
                text = "hello",
                resumeId = null,
                model = "grok-4.6",
                approvalMode = AgentApprovalMode.YOLO,
                cwdSnapshot = null,
                reasoningEffort = "xhigh",
            ),
        )
        assertTrue(cmd.contains("grok -p 'hello'"))
        assertTrue(cmd.contains("--output-format streaming-messages-json"))
        assertTrue(cmd.contains("--include-partial-messages"))
        assertTrue(cmd.contains("--permission-mode bypassPermissions"))
        assertTrue(cmd.contains("-m 'grok-4.6'"))
        assertTrue(cmd.contains("--reasoning-effort 'xhigh'"))
        assertTrue(cmd.contains("GROK_DISABLE_AUTOUPDATER=1"))
        assertTrue("stderr must be dropped, not merged", cmd.endsWith("2>/dev/null"))
    }

    @Test
    fun `resume uses -r and fork rides it`() {
        val cmd = GrokSpec.buildExecCommand(
            ExecInput(
                text = "go",
                resumeId = "01a04437-a5fa-7313-8971-2141cfde619d",
                model = null,
                approvalMode = AgentApprovalMode.SAFE,
                cwdSnapshot = null,
                forkSession = true,
            ),
        )
        assertTrue(cmd.contains("-r '01a04437-a5fa-7313-8971-2141cfde619d' --fork-session"))
        assertTrue(cmd.contains("--permission-mode default"))
        assertFalse("no -s alongside -r", cmd.contains(" -s "))
    }

    @Test
    fun `fresh session gets the pre-generated uuid`() {
        val cmd = GrokSpec.buildExecCommand(
            ExecInput(
                text = "go",
                resumeId = null,
                model = null,
                approvalMode = AgentApprovalMode.AUTO,
                cwdSnapshot = null,
                preGeneratedSessionId = "0192aaaa-bbbb-7ccc-8ddd-eeeeffff0000",
            ),
        )
        assertTrue(cmd.contains("-s '0192aaaa-bbbb-7ccc-8ddd-eeeeffff0000'"))
        assertTrue(cmd.contains("--permission-mode acceptEdits"))
    }

    // ── Misc spec surfaces ──

    @Test
    fun `model labels prettify like the CLI's own naming`() {
        assertEquals("Grok 4.6", grokLabelFromId("grok-4.6"))
        assertEquals("Grok 4.5", grokLabelFromId("grok-4.5"))
        assertEquals("Grok 5 Fast", grokLabelFromId("grok-5-fast"))
        assertEquals("something-else", grokLabelFromId("something-else"))
    }

    @Test
    fun `session preview extracts the prompt text from a cut record`() {
        // The listing cuts candidates at 700 bytes — mid-JSON cut included.
        val cut = userLine.take(260)
        val preview = GrokSpec.extractSessionPreview(cut)
        assertEquals("Reply with exactly: hi", preview)
    }

    @Test
    fun `session title rides the unit separator`() {
        val us = 0x1F.toChar()
        val raw = "Fix the login bug$us$userLine"
        assertEquals("Fix the login bug", GrokSpec.extractSessionTitle(raw))
        assertEquals("Reply with exactly: hi", GrokSpec.extractSessionPreview(raw))
        assertNull(GrokSpec.extractSessionTitle(userLine))
    }

    @Test
    fun `delete removes the whole session directory, guarded`() {
        val cmd = GrokSpec.deleteSessionCommand(
            "01a04437-a5fa-7313-8971-2141cfde619d",
            "/home/u/.grok/sessions/enc/01a04437-a5fa-7313-8971-2141cfde619d/updates.jsonl",
        )
        assertTrue(cmd.contains("rm -rf"))
        assertTrue("must be guarded to the sessions tree", cmd.contains("*/sessions/*"))
    }
}
