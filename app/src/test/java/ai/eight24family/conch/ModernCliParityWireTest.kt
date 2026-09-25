package ai.eight24family.conch

import ai.eight24family.conch.agent.SlashCommandKind
import ai.eight24family.conch.agent.SlashCommands
import ai.eight24family.conch.agent.claude.ClaudeControlWire
import ai.eight24family.conch.agent.claude.ClaudeSpec
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.agent.codex.CodexAppServerEvents
import ai.eight24family.conch.agent.codex.CodexAppServerWire
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire shapes for the surfaces added to keep up with the current CLIs
 * (claude 2.1.281, codex 0.156.1). Field names are copied from the codex
 * app-server TypeScript bindings (`codex app-server generate-ts`) and from the
 * zod schemas embedded in the claude binary — if either CLI renames one,
 * these fail before a phone does.
 */
class ModernCliParityWireTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(line: String): JsonObject = json.parseToJsonElement(line).jsonObject
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    // ── codex ──────────────────────────────────────────────────────────────

    @Test
    fun `turn steer names the running turn and carries the input items`() {
        val o = obj(CodexAppServerWire.encodeTurnSteer(7, "th1", "tu9", "no, the other file", listOf("/tmp/a.png")))
        assertEquals("turn/steer", o.s("method"))
        assertEquals("7", o.s("id"))
        val p = o["params"]!!.jsonObject
        assertEquals("th1", p.s("threadId"))
        // TurnSteerParams.expectedTurnId: the server refuses a steer aimed at a
        // turn that is no longer the active one.
        assertEquals("tu9", p.s("expectedTurnId"))
        val input = p["input"]!!.jsonArray
        assertEquals("text", input[0].jsonObject.s("type"))
        assertEquals("no, the other file", input[0].jsonObject.s("text"))
        assertEquals("localImage", input[1].jsonObject.s("type"))
        assertEquals("/tmp/a.png", input[1].jsonObject.s("path"))
    }

    @Test
    fun `thread compact start carries only the thread id`() {
        val o = obj(CodexAppServerWire.encodeThreadCompactStart(3, "th1"))
        assertEquals("thread/compact/start", o.s("method"))
        val p = o["params"]!!.jsonObject
        assertEquals(setOf("threadId"), p.keys)
        assertEquals("th1", p.s("threadId"))
    }

    @Test
    fun `thread name set carries thread id and name`() {
        val o = obj(CodexAppServerWire.encodeThreadSetName(4, "th1", "auth refactor"))
        assertEquals("thread/name/set", o.s("method"))
        val p = o["params"]!!.jsonObject
        assertEquals("th1", p.s("threadId"))
        assertEquals("auth refactor", p.s("name"))
    }

    @Test
    fun `thread fork branches from the source thread with the resume overrides`() {
        val o = obj(CodexAppServerWire.encodeThreadFork(5, "src", "gpt-5.5", "/home/user/app", AgentApprovalMode.SAFE))
        assertEquals("thread/fork", o.s("method"))
        val p = o["params"]!!.jsonObject
        assertEquals("src", p.s("threadId"))
        assertEquals("gpt-5.5", p.s("model"))
        assertEquals("/home/user/app", p.s("cwd"))
        // Same approval mapping the resume uses — a fork must not silently
        // widen (or narrow) what the agent may do.
        val resume = obj(CodexAppServerWire.encodeThreadResume(6, "src", null, null, AgentApprovalMode.SAFE))["params"]!!.jsonObject
        assertEquals(resume.s("approvalPolicy"), p.s("approvalPolicy"))
        assertEquals(resume.s("sandbox"), p.s("sandbox"))
    }

    @Test
    fun `compaction item is recognised in both casings codex has shipped`() {
        assertTrue(CodexAppServerEvents.isCompactionItem(obj("""{"type":"contextCompaction","id":"i1"}""")))
        assertTrue(CodexAppServerEvents.isCompactionItem(obj("""{"type":"ContextCompaction","id":"i1"}""")))
        assertFalse(CodexAppServerEvents.isCompactionItem(obj("""{"type":"agentMessage","id":"i1"}""")))
    }

    // ── claude ─────────────────────────────────────────────────────────────

    @Test
    fun `initialize opts into prompt suggestions`() {
        val req = obj(ClaudeControlWire.encodeInitialize("r1"))["request"]!!.jsonObject
        assertEquals("initialize", req.s("subtype"))
        assertEquals("true", req.s("promptSuggestions"))
    }

    @Test
    fun `prompt suggestion line yields the suggestion and nothing else does`() {
        val line = """{"type":"prompt_suggestion","suggestion":"run the tests","uuid":"u1","session_id":"s1"}"""
        assertEquals("run the tests", ClaudeControlWire.parsePromptSuggestion(line))
        assertNull(ClaudeControlWire.parsePromptSuggestion("""{"type":"prompt_suggestion","suggestion":"  "}"""))
        // A transcript line that merely MENTIONS the word is not an offer.
        assertNull(
            ClaudeControlWire.parsePromptSuggestion(
                """{"type":"assistant","message":{"content":[{"type":"text","text":"\"prompt_suggestion\""}]}}""",
            ),
        )
    }

    @Test
    fun `side question is a control request with the question`() {
        val o = obj(ClaudeControlWire.encodeSideQuestion("r2", "what does -p do?"))
        assertEquals("control_request", o.s("type"))
        assertEquals("r2", o.s("request_id"))
        val req = o["request"]!!.jsonObject
        assertEquals("side_question", req.s("subtype"))
        assertEquals("what does -p do?", req.s("question"))
    }

    @Test
    fun `prompt suggestions flag is sent only to a CLI known to accept it`() {
        // Present in 2.1.220, absent in 2.1.100; an unknown option kills the
        // launch, so unknown or older versions get nothing.
        assertTrue(ClaudeSpec.acceptsPromptSuggestions("2.1.220"))
        assertTrue(ClaudeSpec.acceptsPromptSuggestions("2.1.278 (Claude Code)"))
        assertTrue(ClaudeSpec.acceptsPromptSuggestions("2.2.0"))
        assertFalse(ClaudeSpec.acceptsPromptSuggestions("2.1.219"))
        assertFalse(ClaudeSpec.acceptsPromptSuggestions("2.1.100"))
        assertFalse(ClaudeSpec.acceptsPromptSuggestions(null))
        assertFalse(ClaudeSpec.acceptsPromptSuggestions(""))
        assertFalse(ClaudeSpec.acceptsPromptSuggestions("unknown"))
    }

    @Test
    fun `persistent launch carries the flag only when asked`() {
        fun cmd(on: Boolean) = ClaudeSpec.buildPersistentCommand(
            ExecInput(text = "", resumeId = null, model = null, approvalMode = AgentApprovalMode.SAFE,
                cwdSnapshot = null, promptSuggestions = on),
        )!!
        assertTrue(cmd(true).contains(" --prompt-suggestions"))
        assertFalse(cmd(false).contains("--prompt-suggestions"))
    }

    // ── palette ────────────────────────────────────────────────────────────

    @Test
    fun `btw is a built-in that takes the question as args`() {
        val btw = SlashCommands.find("btw")!!
        assertEquals(SlashCommandKind.SIDE_QUESTION, btw.kind)
        assertTrue(btw.acceptsArgs)
    }

    @Test
    fun `codex natives run over rpc and never shadow a built-in`() {
        val kinds = SlashCommands.CODEX_NATIVE.associate { it.name to it.kind }
        assertEquals(SlashCommandKind.COMPACT_THREAD, kinds["compact"])
        assertEquals(SlashCommandKind.RENAME_SESSION, kinds["rename"])
        val builtIns = SlashCommands.BUILT_IN.map { it.name }.toSet()
        assertTrue(SlashCommands.CODEX_NATIVE.none { it.name in builtIns })
        assertEquals(
            SlashCommands.CODEX_NATIVE.size,
            SlashCommands.mergeAgentCommands(SlashCommands.CODEX_NATIVE, emptyList()).size,
        )
    }
}
