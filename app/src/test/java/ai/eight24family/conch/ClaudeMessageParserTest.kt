package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.claude.ClaudeMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClaudeMessageParser]. Covers every shape we've actually
 * seen in real Claude stream-json output (live `--print` runs and replayed
 * JSONL session files), plus malformed input.
 *
 * These are the single fragile-est piece of the app — Claude can change
 * message shapes between releases, and a regression here means the chat
 * silently goes blank or fills with raw JSON. Pin every variant.
 */
class ClaudeMessageParserTest {

    // ───────────────────── Pre-flight ─────────────────────

    @Test
    fun `empty input returns nothing`() {
        assertTrue(ClaudeMessageParser.parse("").isEmpty())
        assertTrue(ClaudeMessageParser.parse("   ").isEmpty())
        assertTrue(ClaudeMessageParser.parse("\n\t").isEmpty())
    }

    @Test
    fun `model-unavailable notice becomes one clean card with learn-more url`() {
        val url = "https://www.anthropic.com/news/fable-mythos-access"
        val asError = ClaudeMessageParser.parse(
            """{"type":"error","message":"Claude Fable 5 is currently unavailable. Learn more: $url"}"""
        )
        assertEquals(1, asError.size)
        val card = asError.first() as AgentMessage.Error
        assertEquals("unavailable", card.kind)
        assertEquals("Claude Fable 5 is currently unavailable.", card.text)
        assertEquals(url, card.details)
        // NOT truncated — full title preserved.
        assertFalse(card.text.contains("…"))

        // Arriving as a result (is_error) → SAME stable id so the two
        // copies upsert into one card (no double-render).
        val asResult = ClaudeMessageParser.parse(
            """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Claude Fable 5 is currently unavailable. Learn more: $url"}"""
        )
        assertEquals(1, asResult.size)
        assertEquals(card.id, (asResult.first() as AgentMessage.Error).id)
        // No plain Result bubble / tokens line leaks through.
        assertTrue(asResult.none { it is AgentMessage.Result })
    }

    @Test
    fun `non-json input becomes raw`() {
        val out = ClaudeMessageParser.parse("Welcome to claude")
        assertEquals(1, out.size)
        assertTrue(out.first() is AgentMessage.Raw)
        assertEquals("Welcome to claude", (out.first() as AgentMessage.Raw).text)
    }

    @Test
    fun `malformed json becomes raw`() {
        val out = ClaudeMessageParser.parse("""{"type":"assistant"""")
        assertEquals(1, out.size)
        assertTrue(out.first() is AgentMessage.Raw)
    }

    @Test
    fun `unknown type is dropped quietly`() {
        // Bookkeeping events Claude emits that we don't have rendering for
        // shouldn't pollute the chat — see `parseObject`'s `else -> emptyList()`.
        val json = """{"type":"unknown_event","foo":"bar"}"""
        assertTrue(ClaudeMessageParser.parse(json).isEmpty())
    }

    // ───────────────────── system / compaction ─────────────────────

    @Test
    fun `status compacting opens the live row and boundary closes it on the same id`() {
        // The status event carries session_id — it must NOT be swallowed
        // by the init-like branch (which matches anything with session_id).
        val statusJson = """{"type":"system","subtype":"status","status":"compacting","session_id":"sess-1"}"""
        val live = ClaudeMessageParser.parse(statusJson)
        assertEquals(1, live.size)
        val liveMsg = live.first() as AgentMessage.System
        assertEquals("compacting", liveMsg.subtype)

        val boundaryJson = """{"type":"system","subtype":"compact_boundary","session_id":"sess-1","compact_metadata":{"trigger":"auto","pre_tokens":154321}}"""
        val done = ClaudeMessageParser.parse(boundaryJson)
        assertEquals(1, done.size)
        val doneMsg = done.first() as AgentMessage.System
        assertEquals("compact_done", doneMsg.subtype)
        assertEquals("context compacted · auto · 154k tokens", doneMsg.raw)
        // Same stable id → history upserts the live row into the summary.
        assertEquals(liveMsg.id, doneMsg.id)
    }

    @Test
    fun `non-compacting status events emit nothing`() {
        val json = """{"type":"system","subtype":"status","status":"idle","session_id":"sess-1"}"""
        assertTrue(ClaudeMessageParser.parse(json).isEmpty())
    }

    @Test
    fun `compact boundary without metadata still yields the divider`() {
        val json = """{"type":"system","subtype":"compact_boundary","session_id":"sess-1"}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        assertEquals("context compacted", (out.first() as AgentMessage.System).raw)
    }

    // ───────────────────── system / init ─────────────────────

    @Test
    fun `system init carries model session cwd version`() {
        val json = """{"type":"system","subtype":"init","model":"claude-sonnet-4-6","session_id":"sess-1","cwd":"/home/x","version":"1.2.3","tools":["Read","Write","Bash"]}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        val s = out.first() as AgentMessage.System
        assertEquals("init", s.subtype)
        assertEquals("claude-sonnet-4-6", s.model)
        assertEquals("sess-1", s.sessionId)
        assertEquals("/home/x", s.cwd)
        assertEquals("1.2.3", s.version)
        assertEquals(3, s.toolCount)
    }

    @Test
    fun `system event with model but no subtype still parses as init`() {
        // Some sessions emit a system event with model on continuation.
        val json = """{"type":"system","model":"claude"}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        val s = out.first() as AgentMessage.System
        assertEquals("init", s.subtype)
        assertEquals("claude", s.model)
    }

    @Test
    fun `system turn_duration is hidden — telemetry, not chat`() {
        // Per-turn duration/message-count stats are noise to the user —
        // dropped, not rendered.
        val json = """{"type":"system","subtype":"turn_duration","durationMs":2500,"messageCount":3}"""
        assertTrue(ClaudeMessageParser.parse(json).isEmpty())
    }

    @Test
    fun `file-history-snapshot and file_snapshot are hidden`() {
        // Internal undo plumbing — never user-facing.
        assertTrue(ClaudeMessageParser.parse(
            """{"type":"file-history-snapshot","snapshot":{"trackedFileBackups":{"a":1,"b":2}}}"""
        ).isEmpty())
        assertTrue(ClaudeMessageParser.parse(
            """{"type":"system","subtype":"file_snapshot","snapshot_files":["a","b"]}"""
        ).isEmpty())
    }

    // ───────────────────── assistant content ─────────────────────

    @Test
    fun `assistant text content`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Hello!"}]}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        val m = out.first() as AgentMessage.AssistantText
        assertEquals("Hello!", m.text)
    }

    @Test
    fun `assistant primitive string content`() {
        // Some replayed sessions store content as plain string, not array.
        val json = """{"type":"assistant","message":{"role":"assistant","content":"Plain reply"}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        assertEquals("Plain reply", (out.first() as AgentMessage.AssistantText).text)
    }

    @Test
    fun `assistant text plus tool_use yields both`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"running ls"},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}]}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(2, out.size)
        assertTrue(out[0] is AgentMessage.AssistantText)
        val tu = out[1] as AgentMessage.ToolUse
        assertEquals("Bash", tu.toolName)
        assertEquals("toolu_1", tu.id)
        // input is the JSON-encoded object string
        assertTrue(tu.input.contains("\"command\""))
        assertTrue(tu.input.contains("\"ls\""))
    }

    @Test
    fun `assistant blank text block is skipped`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"   "},{"type":"tool_use","id":"t","name":"X","input":{}}]}}"""
        val out = ClaudeMessageParser.parse(json)
        // blank text dropped, tool_use kept
        assertEquals(1, out.size)
        assertTrue(out[0] is AgentMessage.ToolUse)
    }

    @Test
    fun `thinking block with visible text becomes raw`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"weighing options"}]}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        val r = out.first() as AgentMessage.Raw
        assertTrue(r.text.contains("thinking"))
        assertTrue(r.text.contains("weighing"))
    }

    @Test
    fun `thinking block with empty text is dropped`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":""}]}}"""
        assertTrue(ClaudeMessageParser.parse(json).isEmpty())
    }

    @Test
    fun `image block becomes raw marker`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"image","source":{"type":"base64","data":"AAA"}}]}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        assertTrue((out.first() as AgentMessage.Raw).text.contains("image"))
    }

    // ───────────────────── user / tool_result ─────────────────────

    @Test
    fun `user tool_result with primitive content`() {
        val json = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"file1.txt\nfile2.txt"}]}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        val m = out.first() as AgentMessage.ToolResult
        assertEquals("toolu_1", m.toolUseId)
        assertEquals("file1.txt\nfile2.txt", m.output)
        assertFalse(m.isError)
    }

    @Test
    fun `user tool_result with array-of-text content`() {
        // Variant Claude sometimes uses for multi-block tool output.
        val json = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":[{"type":"text","text":"line1"},{"type":"text","text":"line2"}]}]}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        val m = out.first() as AgentMessage.ToolResult
        assertEquals("line1\nline2", m.output)
    }

    @Test
    fun `user message with synthetic xml is routed to System`() {
        // Slash-command echoes etc. Claude addresses them at the model, not
        // the human. They're emitted as `System(subtype="user_synthetic")` —
        // the chat UI hides those by default and the search UI surfaces them
        // behind the toggle. NO `UserText` should be produced for these
        // payloads.
        val json = """{"type":"user","message":{"role":"user","content":"<command-name>/clear</command-name>"}}"""
        val out = ClaudeMessageParser.parse(json)
        assertTrue("expected no UserText", out.none { it is AgentMessage.UserText })
        assertEquals(1, out.count { it is AgentMessage.System && it.subtype == "user_synthetic" })
    }

    @Test
    fun `user message with caveat prefix is routed to System`() {
        val json = """{"type":"user","message":{"role":"user","content":"Caveat: The messages below were generated by the user."}}"""
        val out = ClaudeMessageParser.parse(json)
        assertTrue("expected no UserText", out.none { it is AgentMessage.UserText })
        assertEquals(1, out.count { it is AgentMessage.System && it.subtype == "user_synthetic" })
    }

    @Test
    fun `real user message survives`() {
        val json = """{"type":"user","message":{"role":"user","content":"hello agent"}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        assertEquals("hello agent", (out.first() as AgentMessage.UserText).text)
    }

    // ───────────────────── result / error / permission ─────────────────────

    @Test
    fun `result message`() {
        val json = """{"type":"result","subtype":"success","total_cost_usd":0.001,"result":"done","usage":{"input_tokens":12000,"output_tokens":1400},"duration_ms":45000}"""
        val out = ClaudeMessageParser.parse(json)
        // Result + the per-turn usage line (user asked to see spend per turn).
        assertEquals(2, out.size)
        val m = out.first() as AgentMessage.Result
        assertEquals("success", m.subtype)
        assertEquals("done", m.text)
        val usage = out[1] as AgentMessage.EventNote
        assertTrue(usage.label.contains("in 12.0k"))
        assertTrue(usage.label.contains("out 1.4k"))
        assertTrue(usage.label.contains("45s"))
    }

    @Test
    fun `error message`() {
        val json = """{"type":"error","message":"something broke"}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        assertEquals("something broke", (out.first() as AgentMessage.Error).text)
    }

    @Test
    fun `permission_request becomes PermissionRequest`() {
        val json = """{"type":"permission_request","id":"req-1","tool_name":"Bash","description":"run rm -rf","input":{"cmd":"rm"}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        val p = out.first() as AgentMessage.PermissionRequest
        assertEquals("req-1", p.requestId)
        assertEquals("Bash", p.toolName)
        assertEquals("run rm -rf", p.description)
        assertEquals(AgentMessage.PermissionRequest.Resolution.PENDING, p.resolved)
    }

    // ───────────────────── isSyntheticUserText ─────────────────────

    @Test
    fun `isSyntheticUserText flags claude xml wrappers`() {
        listOf(
            "<local-command-caveat>foo",
            "<local-command-stdout>",
            "<command-name>/clear</command-name>",
            "<task-notification>",
            "<system-reminder>",
            "<bash-stdout>",
            "<request-interrupted>",
            "Caveat: foo",
            "[Request interrupted by user]"
        ).forEach { assertTrue("should flag: $it", ClaudeMessageParser.isSyntheticUserText(it)) }
    }

    @Test
    fun `isSyntheticUserText leaves real messages alone`() {
        listOf(
            "Hello, can you help",
            "<this-is-not-a-real-tag>",
            "Caveats are nice",  // not a prefix match for "Caveat:"
            "",
        ).forEach { assertFalse("should NOT flag: $it", ClaudeMessageParser.isSyntheticUserText(it)) }
    }

    // ───────────────────── system event surface ─────────────────────
    // Full-subtype coverage (2026-06-12): nothing is silently swallowed.

    @Test
    fun `task events upsert one row per task_id`() {
        val started = ClaudeMessageParser.parse(
            """{"type":"system","subtype":"task_started","task_id":"t1","description":"scan repo"}""",
        ).first() as AgentMessage.EventNote
        val progress = ClaudeMessageParser.parse(
            """{"type":"system","subtype":"task_progress","task_id":"t1","description":"scan repo","last_tool_name":"Grep","usage":{"total_tokens":500,"tool_uses":3,"duration_ms":4000}}""",
        ).first() as AgentMessage.EventNote
        assertEquals(started.id, progress.id) // same row, updated in place
        assertTrue(started.label.contains("started"))
        assertTrue(progress.label.contains("Grep"))
    }

    @Test
    fun `model fallback is a visible warning`() {
        val out = ClaudeMessageParser.parse(
            """{"type":"system","subtype":"model_fallback","trigger":"overloaded","original_model":"claude-fable-5","fallback_model":"claude-opus-4-8","content":"x"}""",
        ).first() as AgentMessage.EventNote
        assertEquals(AgentMessage.EventNote.Tone.WARN, out.tone)
        assertTrue(out.label.contains("claude-fable-5"))
        assertTrue(out.label.contains("claude-opus-4-8"))
    }

    @Test
    fun `notification text and priority drive label and tone`() {
        val urgent = ClaudeMessageParser.parse(
            """{"type":"system","subtype":"notification","key":"k","text":"Server reboot soon","priority":"high"}""",
        ).first() as AgentMessage.EventNote
        assertEquals(AgentMessage.EventNote.Tone.WARN, urgent.tone)
        assertTrue(urgent.label.contains("Server reboot soon"))
    }

    @Test
    fun `thinking tokens are transient ui state not chat rows`() {
        // Fed to AgentSession.liveThinkingTokens by the stream readers and
        // rendered above the spinner; dropped at turn end. NOT a message.
        val out = ClaudeMessageParser.parse(
            """{"type":"system","subtype":"thinking_tokens","estimated_tokens":1200,"estimated_tokens_delta":100}""",
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `unknown future subtype renders generically instead of vanishing`() {
        val out = ClaudeMessageParser.parse(
            """{"type":"system","subtype":"quantum_flux","session_id":"s","message":"new thing happened"}""",
        )
        assertEquals(1, out.size)
        val n = out.first() as AgentMessage.EventNote
        assertTrue(n.label.contains("quantum flux"))
        assertTrue(n.label.contains("new thing happened"))
    }

    @Test
    fun `memory saved lists written paths in detail`() {
        val out = ClaudeMessageParser.parse(
            """{"type":"system","subtype":"memory_saved","written_paths":["/a/m1.md","/a/m2.md"]}""",
        ).first() as AgentMessage.EventNote
        assertTrue(out.label.contains("saved 2"))
        assertTrue(out.detail!!.contains("m2.md"))
    }

    // ───────────────────── attachment events ─────────────────────

    @Test
    fun `attachment task_reminder with items shows count`() {
        val json = """{"type":"attachment","attachment":{"type":"task_reminder","itemCount":4}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        assertTrue((out.first() as AgentMessage.EventNote).label.contains("4"))
    }

    @Test
    fun `attachment task_reminder with zero items is silent`() {
        val json = """{"type":"attachment","attachment":{"type":"task_reminder","itemCount":0}}"""
        assertTrue(ClaudeMessageParser.parse(json).isEmpty())
    }

    @Test
    fun `attachment edited_text_file shows basename`() {
        val json = """{"type":"attachment","attachment":{"type":"edited_text_file","filename":"/very/long/path/to/Foo.kt"}}"""
        val out = ClaudeMessageParser.parse(json)
        assertEquals(1, out.size)
        val r = out.first() as AgentMessage.EventNote
        assertTrue(r.label.contains("Foo.kt"))
        assertFalse("should strip directories", r.label.contains("/very/long"))
    }

}
