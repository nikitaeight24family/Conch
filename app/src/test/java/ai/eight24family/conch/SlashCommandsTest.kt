package ai.eight24family.conch

import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.SlashCommandKind
import ai.eight24family.conch.agent.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SlashCommands]. The parser is dead simple but it gates
 * the chat send-path (`if (text.startsWith("/") && runSlash(text)) return`)
 * — getting it wrong means user prompts that happen to start with `/`
 * disappear into a slash dispatcher. Pin it.
 */
class SlashCommandsTest {

    // ───────────────────── parse ─────────────────────

    @Test
    fun `non-slash returns null`() {
        assertNull(SlashCommands.parse("hello"))
        assertNull(SlashCommands.parse("hello /world"))
        assertNull(SlashCommands.parse(""))
    }

    @Test
    fun `lone slash returns null`() {
        // A bare `/` shouldn't be treated as a (zero-length-name) command.
        assertNull(SlashCommands.parse("/"))
    }

    @Test
    fun `double-slash is treated as comment, not a command`() {
        // Markdown-friendly: `//` lines are NOT commands.
        assertNull(SlashCommands.parse("// just a comment"))
    }

    @Test
    fun `single command no args`() {
        val r = SlashCommands.parse("/help")
        assertNotNull(r)
        assertEquals("help", r!!.first)
        assertEquals("", r.second)
    }

    @Test
    fun `command with trailing whitespace is trimmed`() {
        val r = SlashCommands.parse("/help  ")
        assertNotNull(r)
        assertEquals("help", r!!.first)
    }

    @Test
    fun `command with args splits on first space`() {
        val r = SlashCommands.parse("/diff some path")
        assertNotNull(r)
        assertEquals("diff", r!!.first)
        assertEquals("some path", r.second)
    }

    @Test
    fun `args trim whitespace around them`() {
        val r = SlashCommands.parse("/run    cmd here  ")!!
        assertEquals("run", r.first)
        assertEquals("cmd here", r.second)
    }

    @Test
    fun `leading whitespace trims to slash`() {
        // We `trim()` before looking at the first char.
        val r = SlashCommands.parse("   /memory")!!
        assertEquals("memory", r.first)
    }

    // ───────────────────── find ─────────────────────

    @Test
    fun `find resolves built-ins case-insensitive`() {
        val cmd = SlashCommands.find("HeLp")
        // /help was deleted; /memory is in BUILT_IN.
        assertNull(cmd)
        val mem = SlashCommands.find("MEMORY")
        assertNotNull(mem)
        assertEquals(SlashCommandKind.OPEN_MEMORY, mem!!.kind)
    }

    @Test
    fun `find returns null on unknown name`() {
        assertNull(SlashCommands.find("not-a-real-command"))
    }

    @Test
    fun `find prefers built-in over custom on name collision`() {
        val custom = listOf(
            SlashCommand("memory", "user-defined memory shadow", SlashCommandKind.CUSTOM, customPrompt = "x")
        )
        val resolved = SlashCommands.find("memory", custom)!!
        // Built-in wins so user can't shadow critical commands.
        assertEquals(SlashCommandKind.OPEN_MEMORY, resolved.kind)
    }

    @Test
    fun `find resolves custom command when name is unique`() {
        val custom = listOf(
            SlashCommand("foo", "bar", SlashCommandKind.CUSTOM, customPrompt = "do foo")
        )
        val resolved = SlashCommands.find("foo", custom)!!
        assertEquals(SlashCommandKind.CUSTOM, resolved.kind)
        assertEquals("do foo", resolved.customPrompt)
    }

    // ───────────────────── matchPrefix ─────────────────────

    @Test
    fun `matchPrefix empty string returns everything`() {
        val all = SlashCommands.matchPrefix("", emptyList())
        assertEquals(SlashCommands.BUILT_IN.size, all.size)
    }

    @Test
    fun `matchPrefix filters by prefix case-insensitive`() {
        val starting = SlashCommands.matchPrefix("M", emptyList())
        // /memory / /model should match
        assertTrue("expected /memory", starting.any { it.name == "memory" })
        assertTrue("expected /model", starting.any { it.name == "model" })
    }

    @Test
    fun `matchPrefix puts built-ins before custom`() {
        val custom = listOf(
            SlashCommand("aaa", "z", SlashCommandKind.CUSTOM),
            SlashCommand("clear-x", "z", SlashCommandKind.CUSTOM)
        )
        val out = SlashCommands.matchPrefix("c", custom)
        // Built-in /clear comes before any custom matches starting with "c"
        val builtinIdx = out.indexOfFirst { it.kind == SlashCommandKind.NEW_SESSION && it.name == "clear" }
        val customIdx = out.indexOfFirst { it.kind == SlashCommandKind.CUSTOM && it.name == "clear-x" }
        assertTrue("built-in should appear", builtinIdx >= 0)
        assertTrue("custom should appear", customIdx >= 0)
        assertTrue("built-in must come first", builtinIdx < customIdx)
    }

    // ───────────────────── catalog sanity ─────────────────────

    @Test
    fun `catalog has exactly the kinds we expect`() {
        // Pinning the public catalog so accidental additions (like restoring
        // /help or /init's old shape) get caught in review.
        val names = SlashCommands.BUILT_IN.map { it.name }.toSet()
        val expected = setOf("clear", "new", "diff", "init", "memory", "agents", "model", "review")
        assertEquals(expected, names)
    }

    @Test
    fun `every catalog entry has a kind from the enum`() {
        // Defends against typos that would create a dispatchSlash branch
        // with no matching enum value.
        val allKinds = SlashCommandKind.entries.toSet()
        SlashCommands.BUILT_IN.forEach {
            assertTrue("unknown kind: ${it.kind}", it.kind in allKinds)
        }
    }
}
