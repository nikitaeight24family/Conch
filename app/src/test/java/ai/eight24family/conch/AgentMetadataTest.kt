package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.SubagentCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity tests on the static metadata that drives per-CLI behaviour:
 * memory file paths, capability flags, subagent tools/templates.
 *
 * These look trivial, but a typo in `~/.codex/AGENTS.md` writes user
 * memory to the wrong file forever. Cheap to test, dangerous if wrong.
 */
class AgentMetadataTest {

    // ───────────────────── Agent enum ─────────────────────

    @Test
    fun `each agent has matching filename and global path`() {
        Agent.entries.forEach { agent ->
            assertTrue(
                "global path '${agent.memoryGlobalPath}' should end with /${agent.memoryFilename}",
                agent.memoryGlobalPath.endsWith("/${agent.memoryFilename}")
            )
            assertTrue(
                "global display '${agent.memoryGlobalDisplay}' should end with /${agent.memoryFilename}",
                agent.memoryGlobalDisplay.endsWith("/${agent.memoryFilename}")
            )
        }
    }

    @Test
    fun `memory paths use HOME variable, not literal home dir`() {
        // The shell command runs in `bash -lc`, so $HOME interpolation
        // is what we want — never expand to a hardcoded /home/whoever
        // since we don't know the target user.
        Agent.entries.forEach {
            assertTrue(
                "${it.name} global path must use \$HOME: ${it.memoryGlobalPath}",
                it.memoryGlobalPath.startsWith("\$HOME/")
            )
        }
    }

    @Test
    fun `display paths use tilde, not HOME`() {
        // What the user sees in the editor header.
        Agent.entries.forEach {
            assertTrue(
                "${it.name} display path must use ~: ${it.memoryGlobalDisplay}",
                it.memoryGlobalDisplay.startsWith("~/")
            )
        }
    }

    @Test
    fun `claude only supports subagents`() {
        assertTrue(Agent.CLAUDE.supportsSubagents)
        assertFalse(Agent.CODEX.supportsSubagents)
        assertFalse(Agent.GEMINI.supportsSubagents)
    }

    @Test
    fun `all agents support memory`() {
        // Per-CLI memory is now wired up for every agent.
        Agent.entries.forEach { assertTrue("${it.name} should support memory", it.supportsMemory) }
    }

    @Test
    fun `global memory paths are distinct`() {
        // THE collision that matters: two agents writing global memory to one
        // file would silently feed one CLI's instructions to another, forever.
        // Every CLI keeps its own config dir, so this must always hold.
        val globals = Agent.entries.map { it.memoryGlobalPath }
        assertEquals("global memory paths collide: $globals", globals.size, globals.toSet().size)
    }

    @Test
    fun `project memory filenames repeat only for the shared standard`() {
        // Project-level filenames MAY legitimately repeat: `AGENTS.md` is a
        // published cross-vendor convention that several CLIs genuinely read,
        // and pointing one of them at an invented private filename would make
        // the memory editor write a file that CLI never loads — worse than the
        // collision. Any OTHER repeat is a copy-paste bug.
        val repeated = Agent.entries.map { it.memoryFilename }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        assertEquals(
            "only the shared AGENTS.md standard may repeat, found: $repeated",
            emptySet<String>(), repeated - "AGENTS.md",
        )
    }

    @Test
    fun `cli command looks sane`() {
        Agent.entries.forEach {
            // Lowercase, may carry hyphens (`cursor-agent`). No spaces, no
            // paths, no flags — it is exec'd and pgrep'd verbatim.
            assertTrue("cliCommand should be a lowercase binary name: ${it.cliCommand}",
                it.cliCommand.matches(Regex("[a-z][a-z0-9-]*")))
        }
    }

    @Test
    fun `every agent has an install channel`() {
        // npmPackage is nullable BECAUSE some CLIs (Cursor) ship only through
        // the vendor's own installer — and inventing an npm name there would
        // point our installer at a squatted package. The real invariant is
        // that each agent has at least ONE channel: npm, or the vendor script.
        Agent.entries.forEach { agent ->
            val spec = ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent]
            val pkg = spec.npmPackage
            assertTrue(
                "${agent.name} has neither an npm package nor an official installer",
                pkg != null || spec.officialInstallCommand != null,
            )
            if (pkg != null) {
                assertTrue(
                    "npmPackage should be a package id (scoped or plain): $pkg",
                    pkg.matches(Regex("(@[a-z0-9._-]+/)?[a-z0-9._-]+")),
                )
            }
        }
    }

    // ───────────────────── SubagentCatalog ─────────────────────

    @Test
    fun `tools list is non-empty and has canonical names`() {
        val names = SubagentCatalog.ALL_TOOLS.map { it.name }
        // Canonical Claude tools we expect to be present.
        listOf("Read", "Write", "Edit", "Bash", "Grep", "Glob", "Task").forEach {
            assertTrue("expected $it in tools list", it in names)
        }
        assertTrue(SubagentCatalog.ALL_TOOLS.size >= 7)
    }

    @Test
    fun `tool names are exact-case (Claude matches verbatim)`() {
        // Lower-case 'read' won't match Claude's tool registry.
        SubagentCatalog.ALL_TOOLS.forEach {
            assertTrue("first char must be uppercase: ${it.name}",
                it.name.first().isUpperCase())
        }
    }

    @Test
    fun `tool lookup by name works`() {
        assertNotNull(SubagentCatalog.toolByName("Bash"))
        assertNotNull(SubagentCatalog.toolByName("Read"))
        assertEquals(null, SubagentCatalog.toolByName("bash"))   // case-sensitive
        assertEquals(null, SubagentCatalog.toolByName("nope"))
    }

    @Test
    fun `templates have unique ids and a blank entry`() {
        val ids = SubagentCatalog.TEMPLATES.map { it.id }
        assertEquals("ids should be unique", ids.size, ids.toSet().size)
        assertNotNull(SubagentCatalog.templateById("blank"))
    }

    @Test
    fun `non-blank templates have a body and a description`() {
        SubagentCatalog.TEMPLATES.filter { it.id != "blank" }.forEach {
            assertTrue("template ${it.id} should have a body", it.body.isNotBlank())
            assertTrue("template ${it.id} should have a description", it.description.isNotBlank())
        }
    }

    @Test
    fun `template tools reference real tool names`() {
        val knownNames = SubagentCatalog.ALL_TOOLS.map { it.name }.toSet()
        SubagentCatalog.TEMPLATES.forEach { t ->
            t.tools.forEach { name ->
                assertTrue(
                    "template ${t.id} references unknown tool '$name'",
                    name in knownNames
                )
            }
        }
    }

    @Test
    fun `template lookup by id`() {
        assertNotNull(SubagentCatalog.templateById("code-reviewer"))
        assertEquals(null, SubagentCatalog.templateById("nonexistent"))
    }
}
