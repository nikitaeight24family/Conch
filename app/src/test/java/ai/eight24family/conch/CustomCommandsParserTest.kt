package ai.eight24family.conch

import ai.eight24family.conch.agent.SlashCommandKind
import ai.eight24family.conch.agent.claude.parseClaudeCustomCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the parser of `~/.claude/commands/` and `<cwd>/.claude/commands/`
 * directory listings, used to surface user-defined slash commands.
 *
 * Script output shape: `=== <scope>|<name>|<path>` header followed by file
 * contents until the next `=== `.
 */
class CustomCommandsParserTest {

    @Test
    fun `empty input yields no commands`() {
        assertTrue(parseClaudeCustomCommands("").isEmpty())
        assertTrue(parseClaudeCustomCommands("   \n  ").isEmpty())
    }

    @Test
    fun `single command with frontmatter description`() {
        val raw = """
            === global|deploy|/h/.claude/commands/deploy.md
            ---
            description: deploy the staging branch
            ---

            git push staging main
        """.trimIndent()
        val cmds = parseClaudeCustomCommands(raw)
        assertEquals(1, cmds.size)
        val c = cmds.first()
        assertEquals("deploy", c.name)
        assertEquals("deploy the staging branch", c.description)
        assertEquals(SlashCommandKind.CUSTOM, c.kind)
        assertEquals("global: /h/.claude/commands/deploy.md", c.source)
        assertNotNull(c.customPrompt)
        assertTrue(c.customPrompt!!.contains("git push staging"))
        assertFalse(c.acceptsArgs)
    }

    @Test
    fun `command with ARGUMENTS placeholder marks acceptsArgs`() {
        val raw = """
            === project|review|/repo/.claude/commands/review.md
            ---
            description: review a PR
            ---

            Please review PR #${'$'}ARGUMENTS and report issues.
        """.trimIndent()
        val cmds = parseClaudeCustomCommands(raw)
        assertEquals(1, cmds.size)
        assertTrue(cmds.first().acceptsArgs)
    }

    @Test
    fun `command without frontmatter gets default description`() {
        val raw = """
            === global|hello|/h/.claude/commands/hello.md

            Just say hi.
        """.trimIndent()
        val cmds = parseClaudeCustomCommands(raw)
        assertEquals(1, cmds.size)
        assertEquals("user-defined command", cmds.first().description)
    }

    @Test
    fun `commands without name are skipped`() {
        // Header missing the name segment — flush() bails when name is blank.
        val raw = """
            === global||/h/.claude/commands/anonymous.md
            ---
            description: oops
            ---
            body
        """.trimIndent()
        assertTrue(parseClaudeCustomCommands(raw).isEmpty())
    }

    @Test
    fun `multiple commands across scopes`() {
        val raw = """
            === global|a|/h/.claude/commands/a.md
            ---
            description: A
            ---
            body A
            === project|b|/r/.claude/commands/b.md
            ---
            description: B
            ---
            body B with ${'$'}ARGUMENTS
        """.trimIndent()
        val cmds = parseClaudeCustomCommands(raw)
        assertEquals(2, cmds.size)

        val a = cmds.first { it.name == "a" }
        val b = cmds.first { it.name == "b" }
        assertEquals("A", a.description)
        assertEquals("B", b.description)
        assertFalse(a.acceptsArgs)
        assertTrue(b.acceptsArgs)
        assertTrue(a.source!!.startsWith("global:"))
        assertTrue(b.source!!.startsWith("project:"))
    }

    @Test
    fun `header pipe split limits at 3 fields - path can contain pipes`() {
        // Defensive: if a path ever contains `|` (unusual but possible),
        // split with limit=3 keeps it intact in the path field.
        val raw = """
            === global|weird|/h/.claude/commands/has|pipe.md
            body
        """.trimIndent()
        val cmds = parseClaudeCustomCommands(raw)
        assertEquals(1, cmds.size)
        assertEquals("global: /h/.claude/commands/has|pipe.md", cmds.first().source)
    }

    @Test
    fun `lines before first header are ignored`() {
        // Some shells may emit warnings before the first ===; we should
        // not start collecting body until we see a header.
        val raw = """
            warning: some shell noise
            another line of noise
            === global|c|/h/.claude/commands/c.md
            ---
            description: C
            ---
            body C
        """.trimIndent()
        val cmds = parseClaudeCustomCommands(raw)
        assertEquals(1, cmds.size)
        assertEquals("c", cmds.first().name)
        assertFalse(cmds.first().customPrompt!!.contains("shell noise"))
    }
}
