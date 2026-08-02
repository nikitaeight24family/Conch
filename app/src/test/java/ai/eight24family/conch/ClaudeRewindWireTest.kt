package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeControlWire
import ai.eight24family.conch.agent.claude.ClaudeSpec
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rewind wire forms + the launch flag that makes file rewind exist at all.
 * Both verified against a live CLI on 2026-08-02: the dry run named the file
 * it would restore and the apply really put its old content back.
 */
class ClaudeRewindWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun req(line: String) = json.parseToJsonElement(line).jsonObject.let { o ->
        assertEquals("control_request", o["type"]!!.jsonPrimitive.content)
        o["request"]!!.jsonObject
    }

    @Test
    fun `rewind_conversation carries the anchor and the interrupt flag`() {
        val r = req(ClaudeControlWire.encodeRewindConversation("r1", "u-42", true))
        assertEquals("rewind_conversation", r["subtype"]!!.jsonPrimitive.content)
        assertEquals("u-42", r["target_message_uuid"]!!.jsonPrimitive.content)
        assertEquals("true", r["interrupt_if_running"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rewind_files distinguishes dry run from apply`() {
        val dry = req(ClaudeControlWire.encodeRewindFiles("r2", "u-42", true))
        assertEquals("rewind_files", dry["subtype"]!!.jsonPrimitive.content)
        assertEquals("u-42", dry["user_message_id"]!!.jsonPrimitive.content)
        assertEquals("true", dry["dry_run"]!!.jsonPrimitive.content)
        val apply = req(ClaudeControlWire.encodeRewindFiles("r3", "u-42", false))
        assertEquals("false", apply["dry_run"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the persistent launch enables SDK file checkpointing`() {
        // Without this env var the CLI answers "File rewinding is not enabled."
        // in headless mode — i.e. the whole safety net silently does not exist.
        val cmd = ClaudeSpec.buildPersistentCommand(
            ExecInput(text = "", resumeId = null, model = null, approvalMode = AgentApprovalMode.SAFE, cwdSnapshot = null)
        )!!
        assertTrue(cmd.contains("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=1"))
        // …and the rest of the launch is unchanged.
        assertTrue(cmd.contains("--input-format stream-json"))
        // SAFE keeps the permission tool — that mode is why it exists.
        assertTrue(cmd.contains("--permission-prompt-tool stdio"))
    }

    @Test
    fun `checkpointing rides along with yolo sandbox env too`() {
        val cmd = ClaudeSpec.buildPersistentCommand(
            ExecInput(text = "", resumeId = null, model = null, approvalMode = AgentApprovalMode.YOLO, cwdSnapshot = null)
        )!!
        assertTrue(cmd.contains("IS_SANDBOX=1"))
        assertTrue(cmd.contains("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=1"))
        assertTrue(cmd.contains("--permission-mode bypassPermissions"))
        // ⚠ AND NO PERMISSION TOOL IN BYPASS. It routes prompts that can never
        // fire in this mode, while adding a tool to the prefix — which re-caches
        // the whole conversation on every switch between the terminal and the
        // phone (measured: 15195 vs 15 tokens of cache creation, 2026-08-03).
        assertTrue(
            "bypass must not carry the permission tool — it costs a full re-read",
            !cmd.contains("--permission-prompt-tool"),
        )
    }
}
