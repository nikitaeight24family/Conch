package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.codex.CodexMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CodexMessageParser]. Codex's rollout JSONL has THREE
 * top-level wrapper types (`session_meta`, `response_item`, `event_msg`),
 * each with a polymorphic `payload`. Tests pin the variants we render
 * AND the variants we deliberately drop (`agent_message` / `user_message`
 * which duplicate response_item.message; reasoning blocks; turn_context;
 * compacted; etc).
 */
class CodexMessageParserTest {

    // ───────────────────── Pre-flight ─────────────────────

    @Test
    fun `empty input returns nothing`() {
        assertTrue(CodexMessageParser.parse("").isEmpty())
        assertTrue(CodexMessageParser.parse("   ").isEmpty())
    }

    @Test
    fun `non-json input becomes raw`() {
        val out = CodexMessageParser.parse("plain text")
        assertEquals(1, out.size)
        assertTrue(out.first() is AgentMessage.Raw)
        assertEquals("plain text", (out.first() as AgentMessage.Raw).text)
    }

    @Test
    fun `malformed json becomes raw`() {
        val out = CodexMessageParser.parse("""{"type":"response_item""")
        assertEquals(1, out.size)
        assertTrue(out.first() is AgentMessage.Raw)
    }

    @Test
    fun `unknown wrapper type is dropped`() {
        // turn_context, compacted, etc — bookkeeping, not for the chat.
        assertTrue(CodexMessageParser.parse("""{"type":"turn_context"}""").isEmpty())
        assertTrue(CodexMessageParser.parse("""{"type":"compacted"}""").isEmpty())
        assertTrue(CodexMessageParser.parse("""{"type":"never_heard_of_it"}""").isEmpty())
    }

    // ───────────────────── session_meta ─────────────────────

    @Test
    fun `session_meta becomes init system event`() {
        val json = """{"type":"session_meta","payload":{"id":"sess-abc","model":"gpt-5","cwd":"/repo","cli_version":"0.32.0"}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        val s = out.first() as AgentMessage.System
        assertEquals("init", s.subtype)
        assertEquals("sess-abc", s.sessionId)
        assertEquals("gpt-5", s.model)
        assertEquals("/repo", s.cwd)
        assertEquals("0.32.0", s.version)
    }

    @Test
    fun `session_meta without payload is dropped`() {
        val json = """{"type":"session_meta"}"""
        assertTrue(CodexMessageParser.parse(json).isEmpty())
    }

    @Test
    fun `session_meta does NOT fall back to model_provider when model missing`() {
        // The provider name ("openai") is not the model name; using it as a
        // fallback for the model topbar leaks "openai" into the UI and breaks
        // `codex exec --model openai` because that isn't a real model id.
        // Parser must return null model when payload.model is absent, even
        // if model_provider is present.
        val json = """{"type":"session_meta","payload":{"id":"x","model_provider":"openai"}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(null, (out.first() as AgentMessage.System).model)
    }

    // ───────────────────── response_item.message ─────────────────────

    @Test
    fun `response_item assistant message text`() {
        val json = """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hi human"}]}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        assertEquals("hi human", (out.first() as AgentMessage.AssistantText).text)
    }

    @Test
    fun `response_item user message text`() {
        val json = """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"build it"}]}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        assertEquals("build it", (out.first() as AgentMessage.UserText).text)
    }

    @Test
    fun `response_item user with synthetic env_context routed to System`() {
        // CLI-injected context payloads (environment_context, INSTRUCTIONS,
        // AGENTS.md) come back as `System(subtype="user_synthetic")`, not
        // `UserText`. The chat UI hides those by default. Earlier behaviour
        // expected empty output — that lost the system payload from the
        // search index. Updated to assert routing instead of dropping.
        val json = """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"<environment_context>repo info</environment_context>"}]}}"""
        val out = CodexMessageParser.parse(json)
        assertTrue("no UserText", out.none { it is AgentMessage.UserText })
        assertEquals(1, out.count { it is AgentMessage.System && it.subtype == "user_synthetic" })
    }

    @Test
    fun `response_item user with INSTRUCTIONS routed to System`() {
        val json = """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"<INSTRUCTIONS>system</INSTRUCTIONS>"}]}}"""
        val out = CodexMessageParser.parse(json)
        assertTrue("no UserText", out.none { it is AgentMessage.UserText })
        assertEquals(1, out.count { it is AgentMessage.System && it.subtype == "user_synthetic" })
    }

    @Test
    fun `response_item user with AGENTS_md content routed to System`() {
        // Codex sometimes prefixes user turn with the AGENTS.md memory file.
        val json = """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md\nbuild with cargo"}]}}"""
        val out = CodexMessageParser.parse(json)
        assertTrue("no UserText", out.none { it is AgentMessage.UserText })
        assertEquals(1, out.count { it is AgentMessage.System && it.subtype == "user_synthetic" })
    }

    @Test
    fun `response_item with primitive string content`() {
        val json = """{"type":"response_item","payload":{"type":"message","role":"assistant","content":"plain"}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        assertEquals("plain", (out.first() as AgentMessage.AssistantText).text)
    }

    @Test
    fun `response_item blank content dropped`() {
        val json = """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"   "}]}}"""
        assertTrue(CodexMessageParser.parse(json).isEmpty())
    }

    @Test
    fun `response_item unknown role dropped`() {
        val json = """{"type":"response_item","payload":{"type":"message","role":"system","content":"x"}}"""
        assertTrue(CodexMessageParser.parse(json).isEmpty())
    }

    // ───────────────────── response_item.function_call / output ─────────────────────

    @Test
    fun `response_item function_call becomes ToolUse`() {
        val json = """{"type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"{\"cmd\":\"ls\"}","call_id":"c1"}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        val tu = out.first() as AgentMessage.ToolUse
        assertEquals("shell", tu.toolName)
        assertTrue(tu.input.contains("ls"))
    }

    @Test
    fun `response_item function_call_output becomes ToolResult`() {
        val json = """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"file1.txt"}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        val tr = out.first() as AgentMessage.ToolResult
        assertEquals("c1", tr.toolUseId)
        assertEquals("file1.txt", tr.output)
        assertFalse(tr.isError)
    }

    @Test
    fun `function_call_output flags non-zero exit as error`() {
        val json = """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"command exited with code 2"}}"""
        val out = CodexMessageParser.parse(json)
        assertTrue((out.first() as AgentMessage.ToolResult).isError)
    }

    @Test
    fun `function_call_output zero exit is not an error`() {
        val json = """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"command exited with code 0\nok"}}"""
        val out = CodexMessageParser.parse(json)
        assertFalse((out.first() as AgentMessage.ToolResult).isError)
    }

    @Test
    fun `reasoning block with summary becomes thinking event`() {
        val json = """{"type":"response_item","payload":{"type":"reasoning","summary":["weighing approaches","picking option A"]}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        val r = out.first() as AgentMessage.Raw
        assertTrue(r.text.contains("thinking"))
        assertTrue(r.text.contains("weighing"))
    }

    @Test
    fun `reasoning without summary is dropped`() {
        val json = """{"type":"response_item","payload":{"type":"reasoning","encrypted_content":"opaque"}}"""
        assertTrue(CodexMessageParser.parse(json).isEmpty())
    }

    // ───────────────────── event_msg dedup with response_item ─────────────────────

    @Test
    fun `event_msg agent_message is dropped to avoid double-rendering`() {
        // Codex emits both response_item.message AND event_msg.agent_message
        // for the same assistant reply. Rendering both produces ghost
        // duplicates — we deliberately drop the event_msg copy.
        val json = """{"type":"event_msg","payload":{"type":"agent_message","message":"hello again"}}"""
        assertTrue(CodexMessageParser.parse(json).isEmpty())
    }

    @Test
    fun `event_msg user_message is also dropped`() {
        val json = """{"type":"event_msg","payload":{"type":"user_message","message":"echo"}}"""
        assertTrue(CodexMessageParser.parse(json).isEmpty())
    }

    // ───────────────────── event_msg lifecycle events ─────────────────────

    @Test
    fun `task_started event`() {
        val json = """{"type":"event_msg","payload":{"type":"task_started"}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        assertTrue((out.first() as AgentMessage.Raw).text.contains("turn started"))
    }

    @Test
    fun `task_complete event with cost and duration`() {
        val json = """{"type":"event_msg","payload":{"type":"task_complete","cost_usd":"0.003","duration_ms":4500}}"""
        val out = CodexMessageParser.parse(json)
        val r = out.first() as AgentMessage.Raw
        assertTrue(r.text.contains("4s"))
        assertTrue(r.text.contains("$0.003"))
    }

    @Test
    fun `turn_aborted with reason`() {
        val json = """{"type":"event_msg","payload":{"type":"turn_aborted","reason":"user_cancel"}}"""
        val out = CodexMessageParser.parse(json)
        val r = out.first() as AgentMessage.Raw
        assertTrue(r.text.contains("aborted"))
        assertTrue(r.text.contains("user_cancel"))
    }

    @Test
    fun `context_compacted event`() {
        val json = """{"type":"event_msg","payload":{"type":"context_compacted"}}"""
        val out = CodexMessageParser.parse(json)
        assertTrue((out.first() as AgentMessage.Raw).text.contains("context compacted"))
    }

    // ───────────────────── event_msg exec command ─────────────────────

    @Test
    fun `exec_command_begin shows the command`() {
        val json = """{"type":"event_msg","payload":{"type":"exec_command_begin","command":["/bin/bash","-lc","ls -la"]}}"""
        val out = CodexMessageParser.parse(json)
        val r = out.first() as AgentMessage.Raw
        assertTrue(r.text.contains("exec"))
        assertTrue(r.text.contains("ls -la"))
        assertFalse("bash wrapper should be stripped", r.text.contains("/bin/bash"))
    }

    @Test
    fun `exec_command_end becomes ToolResult with command in output`() {
        val json = """{"type":"event_msg","payload":{"type":"exec_command_end","command":["/bin/bash","-lc","echo hi"],"aggregated_output":"hi","exit_code":"0","call_id":"c1"}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        val tr = out.first() as AgentMessage.ToolResult
        assertEquals("c1", tr.toolUseId)
        assertTrue(tr.output.contains("$ echo hi"))
        assertTrue(tr.output.contains("hi"))
        assertFalse(tr.isError)
    }

    @Test
    fun `exec_command_end non-zero exit flags error`() {
        val json = """{"type":"event_msg","payload":{"type":"exec_command_end","command":["sh","-c","false"],"aggregated_output":"","exit_code":"1","call_id":"c"}}"""
        val out = CodexMessageParser.parse(json)
        assertTrue((out.first() as AgentMessage.ToolResult).isError)
    }

    // ───────────────────── event_msg patch ─────────────────────

    @Test
    fun `patch_apply_end success names file`() {
        val json = """{"type":"event_msg","payload":{"type":"patch_apply_end","success":"true","changes":{"src/Foo.kt":{}}}}"""
        val out = CodexMessageParser.parse(json)
        val r = out.first() as AgentMessage.Raw
        assertTrue(r.text.contains("patched"))
        assertTrue(r.text.contains("Foo.kt"))
    }

    @Test
    fun `patch_apply_end with multiple files shows count`() {
        val json = """{"type":"event_msg","payload":{"type":"patch_apply_end","success":"true","changes":{"a/A.kt":{},"b/B.kt":{},"c/C.kt":{}}}}"""
        val out = CodexMessageParser.parse(json)
        val r = out.first() as AgentMessage.Raw
        // first file name + "+2 more"
        assertTrue(r.text.contains("+2 more"))
    }

    @Test
    fun `patch_apply_end failure marks failed`() {
        val json = """{"type":"event_msg","payload":{"type":"patch_apply_end","success":"false","changes":{"a/A.kt":{}}}}"""
        assertTrue((CodexMessageParser.parse(json).first() as AgentMessage.Raw).text.contains("patch failed"))
    }

    // ───────────────────── event_msg error ─────────────────────

    @Test
    fun `event_msg error becomes Error`() {
        val json = """{"type":"event_msg","payload":{"type":"error","message":"boom"}}"""
        val out = CodexMessageParser.parse(json)
        assertEquals(1, out.size)
        assertEquals("boom", (out.first() as AgentMessage.Error).text)
    }

    @Test
    fun `event_msg token_count is silently dropped`() {
        val json = """{"type":"event_msg","payload":{"type":"token_count","total":1234}}"""
        assertTrue(CodexMessageParser.parse(json).isEmpty())
    }

    @Test
    fun `event_msg collab spawn is surfaced`() {
        val json = """{"type":"event_msg","payload":{"type":"collab_agent_spawn_end","agent_name":"reviewer"}}"""
        val out = CodexMessageParser.parse(json)
        val r = out.first() as AgentMessage.Raw
        assertTrue(r.text.contains("spawned"))
        assertTrue(r.text.contains("reviewer"))
    }

    // ───────────────────── isSyntheticUserText ─────────────────────

    @Test
    fun `isSyntheticUserText flags codex synthetic prefixes`() {
        listOf(
            "<environment_context>",
            "<INSTRUCTIONS>",
            "<user_instructions>",
            "<turn_aborted>",
            "<system-reminder>",
            "<command-name>",
            "<command-message>",
            "<command-args>",
            "# AGENTS.md\nfoo",
            "# Skills\nbar",
        ).forEach { assertTrue("should flag: $it", CodexMessageParser.isSyntheticUserText(it)) }
    }

    @Test
    fun `isSyntheticUserText leaves real prompts alone`() {
        listOf(
            "Hello, please help.",
            "Read AGENTS.md",          // not a prefix match
            "What's # in this context",
            ""
        ).forEach { assertFalse("should NOT flag: $it", CodexMessageParser.isSyntheticUserText(it)) }
    }
}
