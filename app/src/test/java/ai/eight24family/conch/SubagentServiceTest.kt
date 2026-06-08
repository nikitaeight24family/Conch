package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.data.SubagentService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure parts of [SubagentService] — frontmatter splitting and
 * the multi-file output parser fed by `for d in ...; cat ...; done` script
 * output. The SSH plumbing isn't tested here; that's covered by the
 * fact that the real script's output shape is what `parseAgentDocs` is
 * pinned against.
 *
 * `parseAgentDocs` and `splitFrontmatter` are `internal` for this purpose.
 */
class SubagentServiceTest {

    private val svc = SubagentService(
        serverId = "server-x",
        agent = Agent.CLAUDE,
        chatId = null,
    )

    // ───────────────────── splitFrontmatter ─────────────────────

    @Test
    fun `frontmatter with two delimiters is split`() {
        val text = """
            ---
            name: code-reviewer
            description: reviews code
            tools: Read, Grep
            ---

            You are a focused code reviewer.
        """.trimIndent()
        val (fm, body) = svc.splitFrontmatter(text)
        assertEquals("code-reviewer", fm["name"])
        assertEquals("reviews code", fm["description"])
        assertEquals("Read, Grep", fm["tools"])
        assertTrue(body.startsWith("You are a focused code reviewer"))
    }

    @Test
    fun `frontmatter values get quote-stripped`() {
        // YAML quoted strings shouldn't keep their wrapping quotes.
        val text = """
            ---
            name: "code-reviewer"
            description: 'Reviews code, no praise'
            ---
            body
        """.trimIndent()
        val (fm, _) = svc.splitFrontmatter(text)
        assertEquals("code-reviewer", fm["name"])
        assertEquals("Reviews code, no praise", fm["description"])
    }

    @Test
    fun `frontmatter keys get lowercased`() {
        val text = """
            ---
            Name: x
            DESCRIPTION: y
            ---
            body
        """.trimIndent()
        val (fm, _) = svc.splitFrontmatter(text)
        assertEquals("x", fm["name"])
        assertEquals("y", fm["description"])
        assertNull(fm["Name"])
    }

    @Test
    fun `lines without colon are ignored`() {
        val text = """
            ---
            name: x
            this line has no colon
            ---
            body
        """.trimIndent()
        val (fm, _) = svc.splitFrontmatter(text)
        assertEquals(1, fm.size)
        assertEquals("x", fm["name"])
    }

    @Test
    fun `text without frontmatter returns empty map and original body`() {
        val raw = "Just a plain markdown body.\nLine two."
        val (fm, body) = svc.splitFrontmatter(raw)
        assertTrue(fm.isEmpty())
        assertEquals(raw, body)
    }

    @Test
    fun `unclosed frontmatter returns empty map and original body`() {
        val raw = """
            ---
            name: oops
            no closing dashes follow
            body content
        """.trimIndent()
        val (fm, body) = svc.splitFrontmatter(raw)
        assertTrue(fm.isEmpty())
        assertEquals(raw, body)
    }

    @Test
    fun `leading whitespace before frontmatter is allowed`() {
        // splitFrontmatter calls trimStart() before checking for "---".
        val raw = "\n\n   ---\nname: x\n---\nbody"
        val (fm, body) = svc.splitFrontmatter(raw)
        assertEquals("x", fm["name"])
        assertEquals("body", body.trim())
    }

    // ───────────────────── parseAgentDocs ─────────────────────

    @Test
    fun `single global doc is parsed`() {
        val raw = """
            === global|/home/u/.claude/agents/code-reviewer.md
            ---
            name: code-reviewer
            description: reviews diffs
            tools: Read, Grep
            ---

            You are a focused code reviewer.
        """.trimIndent()
        val docs = svc.parseAgentDocs(raw)
        assertEquals(1, docs.size)
        val d = docs.first()
        assertEquals("code-reviewer", d.name)
        assertEquals("global", d.scope)
        assertEquals("reviews diffs", d.description)
        assertEquals(listOf("Read", "Grep"), d.tools)
        assertEquals("/home/u/.claude/agents/code-reviewer.md", d.path)
        assertTrue(d.body.startsWith("You are a focused"))
    }

    @Test
    fun `multiple docs across scopes are parsed`() {
        val raw = """
            === global|/h/.claude/agents/a.md
            ---
            name: a
            ---
            body A
            === project|/repo/.claude/agents/b.md
            ---
            name: b
            tools: Bash
            ---
            body B
        """.trimIndent()
        val docs = svc.parseAgentDocs(raw)
        assertEquals(2, docs.size)

        val (g, p) = docs.partition { it.scope == "global" }
        assertEquals("a", g.single().name)
        assertEquals("b", p.single().name)
        assertEquals(listOf("Bash"), p.single().tools)
    }

    @Test
    fun `doc without frontmatter falls back to filename for name`() {
        val raw = """
            === global|/h/.claude/agents/no-meta.md
            just a body, no frontmatter
        """.trimIndent()
        val docs = svc.parseAgentDocs(raw)
        assertEquals(1, docs.size)
        assertEquals("no-meta", docs.first().name)
        assertEquals(null, docs.first().description)
        assertTrue(docs.first().tools.isEmpty())
    }

    @Test
    fun `tools split trims and skips blanks`() {
        val raw = """
            === global|/h/.claude/agents/x.md
            ---
            name: x
            tools: Read,, Grep , , Bash
            ---
            body
        """.trimIndent()
        val docs = svc.parseAgentDocs(raw)
        assertEquals(listOf("Read", "Grep", "Bash"), docs.first().tools)
    }

    @Test
    fun `empty input is empty list`() {
        assertTrue(svc.parseAgentDocs("").isEmpty())
        assertTrue(svc.parseAgentDocs("   \n  ").isEmpty())
    }

    @Test
    fun `doc header without pipe defaults to global scope`() {
        // Defensive parsing: if the script ever changes its output shape
        // and forgets the `scope|` prefix, we should still salvage
        // something rather than crash.
        val raw = """
            === /home/u/.claude/agents/just-path.md
            ---
            name: x
            ---
            body
        """.trimIndent()
        val docs = svc.parseAgentDocs(raw)
        assertEquals(1, docs.size)
        assertEquals("global", docs.first().scope)
        assertEquals("/home/u/.claude/agents/just-path.md", docs.first().path)
    }
}
