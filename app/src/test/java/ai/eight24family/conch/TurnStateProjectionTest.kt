package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeSpec
import ai.eight24family.conch.agent.codex.CodexSpec
import ai.eight24family.conch.agent.gemini.GeminiSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-phone replacement for the server-side `jq` turn-state projection.
 *
 * This is the input to the ONLY signal that can stop the thinking indicator, so
 * it is tested against the record shapes the CLIs really write. Field order is
 * load-bearing: [ai.eight24family.conch.agent.spec.AgentCliSpec.inferTurnState]
 * reads by index, so an off-by-one corrupts the verdict silently instead of
 * failing loudly.
 */
class TurnStateProjectionTest {

    // ── Claude ───────────────────────────────────────────────────────────────

    private fun claude(vararg lines: String) =
        ClaudeSpec.projectTurnStateRecords(lines.asSequence())

    @Test
    fun `claude assistant end_turn projects all nine fields`() {
        val line = """{"type":"assistant","timestamp":"2026-07-29T10:00:00.000Z",""" +
            """"message":{"id":"msg_01","stop_reason":"end_turn","usage":{"output_tokens":107},""" +
            """"content":[{"type":"text","text":"Done."}]}}"""
        val r = claude(line).single()
        assertEquals(9, r.size)
        assertEquals("assistant", r[0])
        assertEquals("false", r[1])   // isMeta
        assertEquals("false", r[2])   // tool_result
        assertEquals("false", r[3])   // tool_use
        assertEquals("107", r[4])
        assertEquals("msg_01", r[5])
        assertEquals("2026-07-29T10:00:00.000Z", r[6])
        assertEquals("Done.", r[7])
        assertEquals("end_turn", r[8])
    }

    @Test
    fun `claude tool_use and tool_result set their flags`() {
        val use = """{"type":"assistant","message":{"stop_reason":"tool_use","content":[{"type":"tool_use","name":"Bash"}]}}"""
        val res = """{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}"""
        val rs = claude(use, res)
        assertEquals("true", rs[0][3])  // tool_use
        assertEquals("tool_use", rs[0][8])
        assertEquals("true", rs[1][2])  // tool_result
    }

    @Test
    fun `claude marks the meta row that used to freeze the spinner`() {
        val meta = """{"type":"user","isMeta":true,"message":{"content":""" +
            """[{"type":"text","text":"<local-command-caveat>Caveat: ...</local-command-caveat>"}]}}"""
        assertEquals("true", claude(meta).single()[1])
    }

    @Test
    fun `claude flattens tabs and newlines and caps the text at 160`() {
        val long = "x".repeat(400)
        val line = """{"type":"user","message":{"content":[{"type":"text","text":"a\tb\nc$long"}]}}"""
        val text = claude(line).single()[7]
        assertEquals(160, text.length)
        assertFalse(text.contains('\t'))
        assertFalse(text.contains('\n'))
        assertTrue(text.startsWith("a b c"))
    }

    @Test
    fun `claude handles string content as well as block arrays`() {
        val line = """{"type":"user","message":{"content":"plain string prompt"}}"""
        assertEquals("plain string prompt", claude(line).single()[7])
    }

    @Test
    fun `claude skips other record types and junk`() {
        val junk = listOf(
            "",
            "   ",
            "not json at all",
            """{"type":"summary","summary":"x"}""",
            """{"type":"system","subtype":"init"}""",
            """{"type":"assistant","message":{"stop_""",   // partial trailing line
        )
        assertTrue(ClaudeSpec.projectTurnStateRecords(junk.asSequence()).isEmpty())
    }

    @Test
    fun `claude survives a half-written last line and keeps the good ones`() {
        val good = """{"type":"assistant","message":{"id":"m","stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}}"""
        val partial = """{"type":"assistant","message":{"id":"m2","content":[{"type":"te"""
        // jq ABORTS on this and returns nothing at all; we must not.
        val rs = ClaudeSpec.projectTurnStateRecords(listOf(good, partial).asSequence())
        assertEquals(1, rs.size)
        assertEquals("end_turn", rs[0][8])
    }

    @Test
    fun `claude missing fields default the way the jq alternatives did`() {
        val bare = """{"type":"user","message":{"content":[]}}"""
        val r = claude(bare).single()
        assertEquals("false", r[1]); assertEquals("false", r[2]); assertEquals("false", r[3])
        assertEquals("0", r[4]); assertEquals("", r[5]); assertEquals("", r[6])
        assertEquals("", r[7]); assertEquals("", r[8])
    }

    @Test
    fun `claude projection feeds a correct end-to-end verdict`() {
        val lines = listOf(
            """{"type":"user","timestamp":"2026-07-29T10:00:00.000Z","message":{"content":[{"type":"text","text":"go"}]}}""",
            """{"type":"assistant","message":{"id":"a","stop_reason":"tool_use","usage":{"output_tokens":10},"content":[{"type":"tool_use"}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result"}]}}""",
            """{"type":"assistant","message":{"id":"b","stop_reason":"end_turn","usage":{"output_tokens":90},"content":[{"type":"text","text":"done"}]}}""",
        )
        val sig = ClaudeSpec.inferTurnState(ClaudeSpec.projectTurnStateRecords(lines.asSequence()), 1_000L)
        assertFalse(sig.inFlight)
        assertTrue(sig.turnComplete)
        assertEquals(100L, sig.tokens)
        assertEquals(java.time.Instant.parse("2026-07-29T10:00:00.000Z").toEpochMilli(), sig.turnStartMs)
    }

    @Test
    fun `claude - a slash command after the reply still reads as done`() {
        val lines = listOf(
            """{"type":"user","timestamp":"2026-07-29T10:00:00.000Z","message":{"content":[{"type":"text","text":"go"}]}}""",
            """{"type":"assistant","message":{"id":"b","stop_reason":"end_turn","content":[{"type":"text","text":"done"}]}}""",
            """{"type":"user","isMeta":true,"message":{"content":[{"type":"text","text":"<local-command-caveat>x</local-command-caveat>"}]}}""",
        )
        val sig = ClaudeSpec.inferTurnState(ClaudeSpec.projectTurnStateRecords(lines.asSequence()), 1_000L)
        assertFalse("the spinner must stop", sig.inFlight)
        assertTrue("and the reconcile must stay armed", sig.turnComplete)
    }

    // ── Codex ────────────────────────────────────────────────────────────────

    @Test
    fun `codex unwraps event_msg payload type and reads nested tokens`() {
        val started = """{"type":"event_msg","timestamp":"2026-07-29T10:00:00.000Z","payload":{"type":"task_started"}}"""
        val tokens = """{"type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"output_tokens":42}}}}"""
        val rs = CodexSpec.projectTurnStateRecords(listOf(started, tokens).asSequence())
        assertEquals(listOf("task_started", "2026-07-29T10:00:00.000Z", "0"), rs[0])
        assertEquals("token_count", rs[1][0])
        assertEquals("42", rs[1][2])
    }

    @Test
    fun `codex keeps the new top-level turn markers and drops everything else`() {
        val lines = listOf(
            """{"type":"turn.started","timestamp":"t1"}""",
            """{"type":"item.completed"}""",
            """{"type":"event_msg","payload":{"type":"agent_message"}}""",
            """{"type":"turn.completed","timestamp":"t2"}""",
        )
        assertEquals(
            listOf("turn.started", "turn.completed"),
            CodexSpec.projectTurnStateRecords(lines.asSequence()).map { it[0] },
        )
    }

    @Test
    fun `codex projection feeds a correct end-to-end verdict`() {
        val running = listOf("""{"type":"event_msg","payload":{"type":"task_started"}}""")
        val done = running + """{"type":"event_msg","payload":{"type":"task_complete"}}"""
        assertTrue(CodexSpec.inferTurnState(CodexSpec.projectTurnStateRecords(running.asSequence()), 1_000L).inFlight)
        assertFalse(CodexSpec.inferTurnState(CodexSpec.projectTurnStateRecords(done.asSequence()), 1_000L).inFlight)
    }

    // ── Gemini ───────────────────────────────────────────────────────────────

    @Test
    fun `gemini projects its three fields and ignores snapshots`() {
        val lines = listOf(
            """{"type":"user","timestamp":"t1"}""",
            """{"${'$'}set":{"anything":1}}""",
            """{"type":"gemini","timestamp":"t2","tokens":{"output":55}}""",
        )
        val rs = GeminiSpec.projectTurnStateRecords(lines.asSequence())
        assertEquals(2, rs.size)
        assertEquals(listOf("user", "t1", "0"), rs[0])
        assertEquals(listOf("gemini", "t2", "55"), rs[1])
    }

    @Test
    fun `gemini projection feeds a correct end-to-end verdict`() {
        val waiting = listOf("""{"type":"user","timestamp":"t1"}""")
        val replied = waiting + """{"type":"gemini","timestamp":"t2"}"""
        assertTrue(GeminiSpec.inferTurnState(GeminiSpec.projectTurnStateRecords(waiting.asSequence()), 1_000L).inFlight)
        assertFalse(GeminiSpec.inferTurnState(GeminiSpec.projectTurnStateRecords(replied.asSequence()), 1_000L).inFlight)
    }

    // ── cross-agent fencing ──────────────────────────────────────────────────

    @Test
    fun `a Claude rollout projects to nothing under the Codex and Gemini specs`() {
        val claudeLine = """{"type":"assistant","message":{"stop_reason":"end_turn","content":[{"type":"text","text":"x"}]}}"""
        assertTrue(CodexSpec.projectTurnStateRecords(listOf(claudeLine).asSequence()).isEmpty())
        assertTrue(GeminiSpec.projectTurnStateRecords(listOf(claudeLine).asSequence()).isEmpty())
    }
}
