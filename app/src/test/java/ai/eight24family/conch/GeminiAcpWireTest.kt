package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.gemini.GeminiAcpEvents
import ai.eight24family.conch.agent.gemini.GeminiAcpWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Gemini ACP wire shapes (canonical Agent Client Protocol v1
 * JSON schema, fetched 2026-06-13) and the session/update → AgentMessage
 * mapping. ACP is proper JSON-RPC 2.0 — WITH the "jsonrpc" envelope,
 * unlike codex app-server.
 */
class GeminiAcpWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `initialize carries protocolVersion 1 and fs-less capabilities`() {
        val o = json.parseToJsonElement(GeminiAcpWire.encodeInitialize(1, "1.0.9")).jsonObject
        assertEquals("2.0", o["jsonrpc"]?.jsonPrimitive?.contentOrNull)
        assertEquals("initialize", o["method"]?.jsonPrimitive?.contentOrNull)
        val p = o["params"]!!.jsonObject
        // Integer, not string — ACP versions are numeric.
        assertEquals("1", p["protocolVersion"]?.jsonPrimitive?.contentOrNull)
        val fs = p["clientCapabilities"]!!.jsonObject["fs"]!!.jsonObject
        assertEquals("false", fs["readTextFile"]?.jsonPrimitive?.contentOrNull)
        assertEquals("false", fs["writeTextFile"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `prompt carries text content block and cancel is id-less`() {
        val prompt = json.parseToJsonElement(
            GeminiAcpWire.encodePrompt(5, "sess_1", "fix it")
        ).jsonObject
        assertEquals("session/prompt", prompt["method"]?.jsonPrimitive?.contentOrNull)
        assertTrue(prompt.toString().contains("\"type\":\"text\""))
        assertTrue(prompt.toString().contains("\"text\":\"fix it\""))

        val cancel = json.parseToJsonElement(GeminiAcpWire.encodeCancel("sess_1")).jsonObject
        assertEquals("session/cancel", cancel["method"]?.jsonPrimitive?.contentOrNull)
        assertNull("cancel is a notification — no id", cancel["id"])
    }

    @Test
    fun `prompt appends at-mentions for attached images`() {
        val line = GeminiAcpWire.encodePrompt(
            6, "sess_2", "describe these",
            imagePaths = listOf("/home/user/.uploads/a.png", "/home/user/.uploads/b.jpg"),
        )
        // Image paths ride in the single text block as native @<path> mentions.
        assertTrue(line.contains("\"type\":\"text\""))
        assertTrue(line.contains("describe these"))
        assertTrue(line.contains("@/home/user/.uploads/a.png"))
        assertTrue(line.contains("@/home/user/.uploads/b.jpg"))
    }

    @Test
    fun `prompt with no images leaves the text untouched`() {
        val line = GeminiAcpWire.encodePrompt(7, "sess_3", "plain text")
        assertTrue(line.contains("\"text\":\"plain text\""))
        assertTrue("no stray at-mention when no images", !line.contains("@/"))
    }

    @Test
    fun `permission response nests the outcome object`() {
        val req = GeminiAcpWire.parseLine(
            """{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s","toolCall":{"toolCallId":"c1"},"options":[]}}"""
        ) as GeminiAcpWire.Incoming.ServerReq
        val sel = json.parseToJsonElement(
            GeminiAcpWire.encodePermissionSelected(req.id, "proceed_once")
        ).jsonObject
        assertEquals("7", sel["id"]?.jsonPrimitive?.contentOrNull)
        val outcome = sel["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("selected", outcome["outcome"]?.jsonPrimitive?.contentOrNull)
        assertEquals("proceed_once", outcome["optionId"]?.jsonPrimitive?.contentOrNull)

        val can = json.parseToJsonElement(GeminiAcpWire.encodePermissionCancelled(req.id)).jsonObject
        assertEquals(
            "cancelled",
            can["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `permission options parse and kind-driven pick never hardcodes ids`() {
        val params = json.parseToJsonElement(
            """{"sessionId":"s","toolCall":{},"options":[
                {"optionId":"yolo_42","name":"Allow","kind":"allow_once"},
                {"optionId":"always_7","name":"Always","kind":"allow_always"},
                {"optionId":"nope_9","name":"Deny","kind":"reject_once"}]}"""
        ).jsonObject
        val options = GeminiAcpWire.parsePermissionOptions(params)
        assertEquals(3, options.size)
        assertEquals("yolo_42", GeminiAcpWire.pickOption(options, allow = true))
        assertEquals("nope_9", GeminiAcpWire.pickOption(options, allow = false))
        // "Always allow this session" prefers the allow_always option.
        assertEquals("always_7", GeminiAcpWire.pickOption(options, allow = true, preferAlways = true))
        // preferAlways falls back to allow_once when no allow_always is offered.
        val onceOnly = listOf(
            GeminiAcpWire.PermissionOption("once_1", "Allow", "allow_once"),
            GeminiAcpWire.PermissionOption("deny_1", "Deny", "reject_once"),
        )
        assertEquals("once_1", GeminiAcpWire.pickOption(onceOnly, allow = true, preferAlways = true))
        // Kind-less options fall back positionally.
        val bare = listOf(
            GeminiAcpWire.PermissionOption("a", "Allow", ""),
            GeminiAcpWire.PermissionOption("b", "Deny", ""),
        )
        assertEquals("a", GeminiAcpWire.pickOption(bare, allow = true))
        assertEquals("b", GeminiAcpWire.pickOption(bare, allow = false))
    }

    @Test
    fun `routing distinguishes requests notifications and responses`() {
        assertTrue(
            GeminiAcpWire.parseLine("""{"jsonrpc":"2.0","id":1,"method":"session/request_permission","params":{}}""")
                is GeminiAcpWire.Incoming.ServerReq
        )
        assertTrue(
            GeminiAcpWire.parseLine("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{}}}""")
                is GeminiAcpWire.Incoming.Notification
        )
        val resp = GeminiAcpWire.parseLine("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
            as GeminiAcpWire.Incoming.Response
        assertEquals(3L, resp.id)
        assertEquals("end_turn", resp.result!!.jsonObject["stopReason"]?.jsonPrimitive?.contentOrNull)
        assertNull(GeminiAcpWire.parseLine("garbage"))
    }

    // ───────────────── update mapping ─────────────────

    private fun update(s: String) = json.parseToJsonElement(s).jsonObject

    @Test
    fun `tool_call maps to ToolUse and completed update to ToolResult`() {
        val use = GeminiAcpEvents.mapUpdate(
            update("""{"sessionUpdate":"tool_call","toolCallId":"c1","title":"Reading main.kt","kind":"read","status":"pending","rawInput":{"path":"main.kt"}}"""),
            turnTag = "t1",
        ).first() as AgentMessage.ToolUse
        assertEquals("gemacp_c1", use.id)
        assertEquals("Reading main.kt", use.toolName)
        assertTrue(use.input.contains("main.kt"))

        val done = GeminiAcpEvents.mapUpdate(
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"file contents"}}]}"""),
            turnTag = "t1",
        ).first() as AgentMessage.ToolResult
        assertEquals("gemacp_c1", done.toolUseId)
        assertEquals("file contents", done.output)
        assertTrue(!done.isError)

        val failed = GeminiAcpEvents.mapUpdate(
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"c2","status":"failed","content":[{"type":"diff","path":"/repo/a.kt","oldText":"x","newText":"y"}]}"""),
            turnTag = "t1",
        ).first() as AgentMessage.ToolResult
        assertTrue(failed.isError)
        assertTrue(failed.output.contains("diff · /repo/a.kt"))
    }

    @Test
    fun `in-progress tool updates stay quiet`() {
        assertTrue(GeminiAcpEvents.mapUpdate(
            update("""{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"in_progress"}"""),
            turnTag = "t1",
        ).isEmpty())
    }

    @Test
    fun `plan maps to live progress note`() {
        val n = GeminiAcpEvents.mapUpdate(
            update("""{"sessionUpdate":"plan","entries":[
                {"content":"read code","priority":"high","status":"completed"},
                {"content":"fix bug","priority":"high","status":"in_progress"}]}"""),
            turnTag = "t3",
        ).first() as AgentMessage.EventNote
        assertTrue(n.label.contains("plan · 1/2"))
        assertTrue(n.detail!!.contains("✓ read code"))
        assertEquals("gemacp-plan-t3", n.id)
    }

    @Test
    fun `unknown update kind surfaces generically chunks stay with the driver`() {
        val out = GeminiAcpEvents.mapUpdate(
            update("""{"sessionUpdate":"token_budget_update","used":1234}"""),
            turnTag = "t1",
        )
        assertEquals(1, out.size)
        assertTrue((out.first() as AgentMessage.EventNote).label.contains("token budget update"))
        // Chunks are accumulated by the driver, not mapped here.
        assertTrue(GeminiAcpEvents.mapUpdate(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}}"""),
            turnTag = "t1",
        ).isEmpty())
    }
}
