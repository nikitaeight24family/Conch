package ai.eight24family.conch

import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.SlashCommandKind
import ai.eight24family.conch.agent.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CLI's own commands and skills reach the palette — but never at the cost
 * of the ones we implement natively.
 *
 * The list is real: the `initialize` handshake on a stock install reports 45
 * commands (/compact, /context, /usage, /doctor, /security-review and every
 * skill). None of them were reachable from the phone before, because the
 * palette only ever knew our eight built-ins plus the user's own files.
 */
class AgentCommandsPaletteTest {

    private fun agent(vararg names: String) = names.map {
        SlashCommand(it, "from the CLI", SlashCommandKind.AGENT_BUILTIN)
    }

    @Test
    fun `native handlers are not shadowed by the CLI's versions`() {
        // /model must keep opening the picker, not send a useless line of text.
        val merged = SlashCommands.mergeAgentCommands(
            agent("model", "memory", "agents", "clear", "compact", "doctor"),
            custom = emptyList(),
        )
        assertEquals(listOf("compact", "doctor"), merged.map { it.name })
    }

    @Test
    fun `a user's own file wins over the CLI's command of the same name`() {
        val custom = listOf(SlashCommand("verify", "mine", SlashCommandKind.CUSTOM))
        val merged = SlashCommands.mergeAgentCommands(agent("verify", "dataviz"), custom)
        assertEquals(listOf("dataviz"), merged.map { it.name })
    }

    @Test
    fun `everything else survives and is dispatchable as a turn`() {
        val merged = SlashCommands.mergeAgentCommands(
            agent("security-review", "deep-research", "usage"), emptyList(),
        )
        assertEquals(3, merged.size)
        assertTrue(merged.all { it.kind == SlashCommandKind.AGENT_BUILTIN })
    }

    @Test
    fun `the palette matches them by prefix alongside ours`() {
        val agentCmds = agent("compact", "context", "doctor")
        val hits = SlashCommands.matchPrefix("co", custom = agentCmds)
        assertTrue(hits.any { it.name == "compact" })
        assertTrue(hits.any { it.name == "context" })
        assertTrue(hits.none { it.name == "doctor" })
    }
}
