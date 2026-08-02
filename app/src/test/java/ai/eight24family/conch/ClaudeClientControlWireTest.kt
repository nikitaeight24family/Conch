package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeControlWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the CLIENT → CLI control-request forms (set_model /
 * set_permission_mode / set_max_thinking_tokens / get_context_usage /
 * get_usage / file_suggestions / rename_session) and the control_response
 * parse — all verified against the 2.1.219 binary's stdin dispatcher:
 * request_id at the TOP level of a request, NESTED inside `response` of a
 * response.
 */
class ClaudeClientControlWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun req(line: String) = json.parseToJsonElement(line).jsonObject.let { o ->
        assertEquals("control_request", o["type"]!!.jsonPrimitive.content)
        o["request_id"]!!.jsonPrimitive.content to o["request"]!!.jsonObject
    }

    @Test
    fun `set_model carries the model string`() {
        val (id, r) = req(ClaudeControlWire.encodeSetModel("r1", "opus"))
        assertEquals("r1", id)
        assertEquals("set_model", r["subtype"]!!.jsonPrimitive.content)
        assertEquals("opus", r["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set_model null or blank maps to the cli default token`() {
        val (_, r) = req(ClaudeControlWire.encodeSetModel("r1", null))
        assertEquals("default", r["model"]!!.jsonPrimitive.content)
        val (_, r2) = req(ClaudeControlWire.encodeSetModel("r1", "  "))
        assertEquals("default", r2["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set_permission_mode carries the mode`() {
        val (_, r) = req(ClaudeControlWire.encodeSetPermissionMode("r2", "acceptEdits"))
        assertEquals("set_permission_mode", r["subtype"]!!.jsonPrimitive.content)
        assertEquals("acceptEdits", r["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set_max_thinking_tokens carries an integer or an explicit null`() {
        val (_, r) = req(ClaudeControlWire.encodeSetMaxThinkingTokens("r3", 24000))
        assertEquals("set_max_thinking_tokens", r["subtype"]!!.jsonPrimitive.content)
        assertEquals(24000, r["max_thinking_tokens"]!!.jsonPrimitive.content.toInt())
        // The CLI's validator accepts integer OR null — a cleared effort must
        // send a literal null (back to adaptive), not omit the key.
        val (_, r2) = req(ClaudeControlWire.encodeSetMaxThinkingTokens("r3", null))
        assertTrue(r2.containsKey("max_thinking_tokens"))
        assertNull(r2["max_thinking_tokens"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `file_suggestions carries the query and rename_session the title`() {
        val (_, r) = req(ClaudeControlWire.encodeFileSuggestions("r4", "src/main"))
        assertEquals("file_suggestions", r["subtype"]!!.jsonPrimitive.content)
        assertEquals("src/main", r["query"]!!.jsonPrimitive.content)
        val (_, r2) = req(ClaudeControlWire.encodeRenameSession("r5", "Fix the stop button"))
        assertEquals("rename_session", r2["subtype"]!!.jsonPrimitive.content)
        assertEquals("Fix the stop button", r2["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `usage and context requests are bare subtypes`() {
        val (_, r) = req(ClaudeControlWire.encodeGetUsage("r6"))
        assertEquals("get_usage", r["subtype"]!!.jsonPrimitive.content)
        val (_, r2) = req(ClaudeControlWire.encodeGetContextUsage("r7"))
        assertEquals("get_context_usage", r2["subtype"]!!.jsonPrimitive.content)
    }

    @Test
    fun `success response parses with nested request_id and payload`() {
        val line = """{"type":"control_response","response":{"subtype":"success","request_id":"r9","response":{"suggestions":[{"path":"app/build.gradle.kts"}]}}}"""
        val resp = ClaudeControlWire.parseControlResponse(line)!!
        assertTrue(resp.ok)
        assertEquals("r9", resp.requestId)
        assertTrue(resp.payload.toString().contains("build.gradle"))
    }

    @Test
    fun `error response carries the cli message`() {
        val line = """{"type":"control_response","response":{"subtype":"error","request_id":"r10","error":"set_model: model must be a string"}}"""
        val resp = ClaudeControlWire.parseControlResponse(line)!!
        assertFalse(resp.ok)
        assertEquals("set_model: model must be a string", resp.error)
        assertNull(resp.payload)
    }

    @Test
    fun `payload-less success still parses`() {
        val line = """{"type":"control_response","response":{"subtype":"success","request_id":"r11"}}"""
        val resp = ClaudeControlWire.parseControlResponse(line)!!
        assertTrue(resp.ok)
        assertNull(resp.payload)
    }

    @Test
    fun `non-response lines return null`() {
        assertNull(ClaudeControlWire.parseControlResponse("""{"type":"assistant","message":{}}"""))
        assertNull(ClaudeControlWire.parseControlResponse("plain text control_response"))
    }
}
