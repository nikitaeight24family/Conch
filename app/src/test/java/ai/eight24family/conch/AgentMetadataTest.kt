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
    fun `each agent has a distinct filename`() {
        // CLAUDE.md / AGENTS.md / GEMINI.md — no collisions.
        val filenames = Agent.entries.map { it.memoryFilename }
        assertEquals(filenames.size, filenames.toSet().size)
    }

    @Test
    fun `cli command and npm package look sane`() {
        Agent.entries.forEach {
            assertTrue("cliCommand should be lowercase letters: ${it.cliCommand}",
                it.cliCommand.matches(Regex("[a-z]+")))
            assertTrue("npmPackage should look like @scope/pkg: ${it.npmPackage}",
                it.npmPackage.startsWith("@") && it.npmPackage.contains("/"))
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
