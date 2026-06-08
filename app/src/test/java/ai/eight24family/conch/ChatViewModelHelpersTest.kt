package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.SlashCommandKind
import ai.eight24family.conch.ui.viewmodel.computeCostStats
import ai.eight24family.conch.agent.claude.parseClaudeCustomCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Tests for the pure helpers backing [ChatViewModel] —
 * `parseClaudeCustomCommands` (~/.claude/commands/.md discovery) and
 * `computeCostStats` (token + dollar accounting from the Claude
 * stream-json result envelope).
 *
 * Compose-side rendering of these into the chat UI lives in
 * `ChatScreen.kt`; semantic tree assertions on it would need a
 * working Robolectric ↔ Compose Test setup, which this project
 * doesn't currently have (see SessionsScreenTest's class doc). The
 * helpers tested here are where the actual logic lives — the
 * Composables are thin readers.
 */
class ChatViewModelHelpersTest {

    // ─────────────── parseClaudeCustomCommands ───────────────

    @Test
    fun `parseClaudeCustomCommands returns empty list on blank or whitespace input`() {
        assertTrue(parseClaudeCustomCommands("").isEmpty())
        assertTrue(parseClaudeCustomCommands("   \n  ").isEmpty())
    }

    @Test
    fun `parseClaudeCustomCommands ignores lines outside any === marker`() {
        val raw = """
            something before any marker
            another stray line
        """.trimIndent()
        assertTrue(parseClaudeCustomCommands(raw).isEmpty())
    }

    @Test
    fun `parseClaudeCustomCommands extracts a single command with frontmatter description`() {
        val raw = """
            === global|review|/home/me/.claude/commands/review.md
            ---
            description: Review the diff
            ---
            Please review the staged changes.
        """.trimIndent()

        val cmds = parseClaudeCustomCommands(raw)
        assertEquals(1, cmds.size)
        val c = cmds.single()
        assertEquals("review", c.name)
        assertEquals("Review the diff", c.description)
        assertEquals(SlashCommandKind.CUSTOM, c.kind)
        assertEquals("global: /home/me/.claude/commands/review.md", c.source)
        assertNotNull(c.customPrompt)
        assertTrue(c.customPrompt!!.contains("review the staged changes", ignoreCase = true))
    }

    @Test
    fun `parseClaudeCustomCommands defaults description when frontmatter omits it`() {
        val raw = """
            === project|init|/proj/.claude/commands/init.md
            no frontmatter — just a body
        """.trimIndent()
        val cmd = parseClaudeCustomCommands(raw).single()
        assertEquals("init", cmd.name)
        assertEquals("user-defined command", cmd.description)
    }

    @Test
    fun `parseClaudeCustomCommands strips quotes around frontmatter description`() {
        val raw = """
            === global|x|/x.md
            ---
            description: "double quoted desc"
            ---
            body
        """.trimIndent()
        assertEquals("double quoted desc", parseClaudeCustomCommands(raw).single().description)
    }

    @Test
    fun `parseClaudeCustomCommands sets acceptsArgs when body references ARGUMENTS`() {
        val raw = """
            === global|search|/cmd.md
            Run `grep ${'$'}ARGUMENTS` against the codebase.
        """.trimIndent()
        val cmd = parseClaudeCustomCommands(raw).single()
        assertTrue("body had \$ARGUMENTS — acceptsArgs should be true", cmd.acceptsArgs)
    }

    @Test
    fun `parseClaudeCustomCommands leaves acceptsArgs false when body has no ARGUMENTS`() {
        val raw = """
            === global|cleanup|/c.md
            Tidy the imports across the project.
        """.trimIndent()
        assertFalse(parseClaudeCustomCommands(raw).single().acceptsArgs)
    }

    @Test
    fun `parseClaudeCustomCommands handles multiple commands in sequence`() {
        val raw = """
            === global|a|/a.md
            ---
            description: Alpha
            ---
            alpha body

            === project|b|/b.md
            ---
            description: Beta
            ---
            beta body uses ${'$'}ARGUMENTS
        """.trimIndent()

        val cmds = parseClaudeCustomCommands(raw)
        assertEquals(2, cmds.size)
        assertEquals(listOf("a", "b"), cmds.map { it.name })
        assertEquals(listOf("Alpha", "Beta"), cmds.map { it.description })
        assertFalse(cmds[0].acceptsArgs)
        assertTrue(cmds[1].acceptsArgs)
    }

    // ─────────────── computeCostStats ───────────────

    @Test
    fun `computeCostStats returns zeros for empty message list`() {
        val s = computeCostStats(emptyList())
        assertEquals(0, s.turns)
        assertEquals(0L, s.inputTokens)
        assertEquals(0L, s.outputTokens)
        assertEquals(0.0, s.totalCostUsd, 0.0)
    }

    @Test
    fun `computeCostStats counts turns from Result messages`() {
        val msgs = listOf(
            result(text = """{"usage":{"input_tokens":10,"output_tokens":20}}"""),
            result(text = """{"usage":{"input_tokens":30,"output_tokens":5}}"""),
        )
        val s = computeCostStats(msgs)
        assertEquals(2, s.turns)
        assertEquals(40L, s.inputTokens)
        assertEquals(25L, s.outputTokens)
    }

    @Test
    fun `computeCostStats tolerates Result messages with null text`() {
        val msgs = listOf(
            AgentMessage.Result(id = UUID.randomUUID().toString(), subtype = "success", text = null),
            AgentMessage.Result(id = UUID.randomUUID().toString(), subtype = "success", text = null),
        )
        val s = computeCostStats(msgs)
        // turn count still increments — that's how the user knows two
        // round-trips happened, even when the CLI omitted usage.
        assertEquals(2, s.turns)
        assertEquals(0L, s.inputTokens)
    }

    @Test
    fun `computeCostStats picks up cache token fields from System messages`() {
        val msgs = listOf(
            system(raw = """{"usage":{"cache_creation_input_tokens":1000,"cache_read_input_tokens":50}}"""),
        )
        val s = computeCostStats(msgs)
        assertEquals(0, s.turns) // System messages aren't turns
        assertEquals(1000L, s.cacheCreationTokens)
        assertEquals(50L, s.cacheReadTokens)
    }

    @Test
    fun `computeCostStats reads usage from message-nested envelope`() {
        // Some Claude events stamp usage under `.message.usage` instead of
        // top-level `.usage`. Both shapes occur in real session logs.
        val msgs = listOf(
            result(text = """{"message":{"usage":{"input_tokens":7,"output_tokens":3}}}"""),
        )
        val s = computeCostStats(msgs)
        assertEquals(7L, s.inputTokens)
        assertEquals(3L, s.outputTokens)
    }

    @Test
    fun `computeCostStats sums total_cost_usd across messages`() {
        val msgs = listOf(
            result(text = """{"total_cost_usd":0.0123}"""),
            result(text = """{"cost_usd":0.0077}"""),
        )
        val s = computeCostStats(msgs)
        // Two turns, summed cost = 0.020 USD
        assertEquals(2, s.turns)
        assertEquals(0.0200, s.totalCostUsd, 1e-9)
    }

    @Test
    fun `computeCostStats ignores malformed JSON without throwing`() {
        val msgs = listOf(
            result(text = "not actually json"),
            result(text = """{"usage":{"input_tokens":"oops a string"}}"""),
            result(text = """{"usage":{"input_tokens":42}}"""),
        )
        val s = computeCostStats(msgs)
        assertEquals(3, s.turns) // turn counter doesn't care about parse errors
        assertEquals(42L, s.inputTokens) // only the well-formed line landed
    }

    @Test
    fun `computeCostStats ignores other message kinds entirely`() {
        val msgs = listOf<AgentMessage>(
            AgentMessage.AssistantText(UUID.randomUUID().toString(), "hi"),
            AgentMessage.UserText(UUID.randomUUID().toString(), "bye"),
            AgentMessage.ToolUse(UUID.randomUUID().toString(), "Bash", "ls"),
            AgentMessage.Error(UUID.randomUUID().toString(), "oops"),
        )
        val s = computeCostStats(msgs)
        assertEquals(0, s.turns)
        assertEquals(0L, s.inputTokens + s.outputTokens + s.cacheReadTokens + s.cacheCreationTokens)
        assertEquals(0.0, s.totalCostUsd, 0.0)
    }

    // ───── helpers to build messages without arguing with type checker ─────

    private fun result(text: String): AgentMessage.Result =
        AgentMessage.Result(id = UUID.randomUUID().toString(), subtype = "success", text = text)

    private fun system(raw: String): AgentMessage.System =
        AgentMessage.System(id = UUID.randomUUID().toString(), subtype = "init", raw = raw)
}
