package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeControlWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire shapes of the persistent control protocol — verified
 * against CLI 2.1.170's embedded zod schemas and the Agent SDK sources:
 *  - control_request: request_id at TOP level, payload under `request`;
 *  - control_response: request_id NESTED inside `response`;
 *  - allow REQUIRES updatedInput; AskUserQuestion answers ride inside
 *    updatedInput as {questions: <passthrough>, answers: {text: labels}}.
 */
class ClaudeControlWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `user turn matches the sdk canonical shape`() {
        val line = ClaudeControlWire.encodeUserTurn("hi, build the apk")
        val obj = json.parseToJsonElement(line).jsonObject
        assertEquals("user", obj["type"]!!.jsonPrimitive.content)
        val msg = obj["message"]!!.jsonObject
        assertEquals("user", msg["role"]!!.jsonPrimitive.content)
        assertEquals("hi, build the apk", msg["content"]!!.jsonPrimitive.content)
        assertTrue(obj.containsKey("parent_tool_use_id"))
    }

    @Test
    fun `can_use_tool request parses with top-level request_id`() {
        val line = """{"type":"control_request","request_id":"req_7_ab12cd34","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"rm -rf /tmp/x"},"tool_use_id":"tu_1","description":"Run command"}}"""
        assertTrue(ClaudeControlWire.isControlLine(line))
        val req = ClaudeControlWire.parseControlRequest(line)!!
        assertEquals("req_7_ab12cd34", req.requestId)
        assertEquals("can_use_tool", req.subtype)
        assertEquals("Bash", req.toolName)
        assertEquals("rm -rf /tmp/x", req.inputJson!!["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `allow nests request_id inside response and always carries updatedInput`() {
        val original = json.parseToJsonElement("""{"command":"ls"}""").jsonObject
        val line = ClaudeControlWire.encodeAllow("req_1_deadbeef", original)
        val obj = json.parseToJsonElement(line).jsonObject
        assertEquals("control_response", obj["type"]!!.jsonPrimitive.content)
        val resp = obj["response"]!!.jsonObject
        assertEquals("success", resp["subtype"]!!.jsonPrimitive.content)
        assertEquals("req_1_deadbeef", resp["request_id"]!!.jsonPrimitive.content)
        val payload = resp["response"]!!.jsonObject
        assertEquals("allow", payload["behavior"]!!.jsonPrimitive.content)
        assertEquals("ls", payload["updatedInput"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deny carries required message`() {
        val line = ClaudeControlWire.encodeDeny("req_2_cafe0000", "no")
        val payload = json.parseToJsonElement(line).jsonObject["response"]!!
            .jsonObject["response"]!!.jsonObject
        assertEquals("deny", payload["behavior"]!!.jsonPrimitive.content)
        assertEquals("no", payload["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ask questions parse and answers encode keyed by question text`() {
        val input = json.parseToJsonElement(
            """{"questions":[
                {"question":"How should the output be formatted?","header":"Format","options":[
                    {"label":"Brief","description":"summary only"},
                    {"label":"Detailed","description":"with every step"}],"multiSelect":false},
                {"question":"Which sections to include?","header":"Sections","options":[
                    {"label":"Intro","description":""},
                    {"label":"Conclusions","description":""},
                    {"label":"Code","description":""}],"multiSelect":true}
            ]}"""
        ).jsonObject

        val questions = ClaudeControlWire.parseAskQuestions(input)
        assertEquals(2, questions.size)
        assertEquals("Format", questions[0].header)
        assertEquals(2, questions[0].options.size)
        assertEquals(false, questions[0].multiSelect)
        assertEquals(true, questions[1].multiSelect)
        assertEquals("Intro", questions[1].options[0].label)

        val line = ClaudeControlWire.encodeAskAnswers(
            "req_3_00aa11bb", input,
            mapOf(0 to listOf("Brief"), 1 to listOf("Intro", "Conclusions")),
        )
        val resp = json.parseToJsonElement(line).jsonObject["response"]!!.jsonObject
        assertEquals("req_3_00aa11bb", resp["request_id"]!!.jsonPrimitive.content)
        val payload = resp["response"]!!.jsonObject
        assertEquals("allow", payload["behavior"]!!.jsonPrimitive.content)
        val updated = payload["updatedInput"]!!.jsonObject
        // Original questions array passed through untouched.
        assertEquals(2, updated["questions"]!!.jsonArray.size)
        val answers = updated["answers"]!!.jsonObject
        assertEquals("Brief", answers["How should the output be formatted?"]!!.jsonPrimitive.content)
        // multiSelect answers comma-joined per the CLI's own convention.
        assertEquals("Intro, Conclusions", answers["Which sections to include?"]!!.jsonPrimitive.content)
    }

    @Test
    fun `interrupt has request_id at top level`() {
        val line = ClaudeControlWire.encodeInterrupt("int-5-12345678")
        val obj = json.parseToJsonElement(line).jsonObject
        assertEquals("control_request", obj["type"]!!.jsonPrimitive.content)
        assertEquals("int-5-12345678", obj["request_id"]!!.jsonPrimitive.content)
        assertEquals("interrupt", obj["request"]!!.jsonObject["subtype"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cancel request parses and non-cancel lines do not`() {
        assertEquals(
            "req_9_ffff0000",
            ClaudeControlWire.parseCancelRequest(
                """{"type":"control_cancel_request","request_id":"req_9_ffff0000"}""",
            ),
        )
        assertNull(ClaudeControlWire.parseCancelRequest("""{"type":"user","message":{}}"""))
        assertNull(ClaudeControlWire.parseCancelRequest("plain text"))
    }

    @Test
    fun `dialog cancelled answer for unrenderable dialog kinds`() {
        val line = ClaudeControlWire.encodeDialogCancelled("req_4_dlg00001")
        val payload = json.parseToJsonElement(line).jsonObject["response"]!!
            .jsonObject["response"]!!.jsonObject
        assertEquals("cancelled", payload["behavior"]!!.jsonPrimitive.content)
    }
}
