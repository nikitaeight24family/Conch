package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeSpec
import ai.eight24family.conch.agent.claude.claudeDefaultModelFor
import ai.eight24family.conch.agent.claude.claudeDefaultModelKeyFor
import ai.eight24family.conch.agent.claude.seedClaudeDefault
import ai.eight24family.conch.agent.claude.setClaudeDefault
import ai.eight24family.conch.agent.spec.TopbarModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The chip must never advertise another server's model.
 *
 * A brand-new chat sends no `--model` on purpose — the CLI then starts on the
 * default pinned in THAT box's `~/.claude/settings.json`.
 *
 * Chip and command line read the same per-server record, so they cannot
 * disagree — and an unknown server prints nothing rather than a guess.
 */
class ClaudeDefaultModelPerServerTest {

    private fun stateOn(serverId: String?) = TopbarModelState(
        agentDisplayName = "Claude Code",
        serverId = serverId,
        // A FRESH chat: no pick, no observation, no session header. This is
        // exactly the state the lie was visible in.
        selectedModel = null,
        sessionInitialModel = null,
        observedModel = null,
        defaultModel = null,
        availableModels = emptyMap(),
        modelsProbing = false,
        selectedReasoning = null,
        observedReasoning = null,
        sessionInitialReasoning = null,
        defaultReasoning = null,
    )

    @Test
    fun `each server keeps its own default`() {
        setClaudeDefault("srv-opus", "Opus 5", "opus")
        setClaudeDefault("srv-sonnet", "Sonnet 5", "sonnet")

        assertEquals("Opus 5", claudeDefaultModelFor("srv-opus"))
        assertEquals("Sonnet 5", claudeDefaultModelFor("srv-sonnet"))
        assertEquals("opus", claudeDefaultModelKeyFor("srv-opus"))
        assertEquals("sonnet", claudeDefaultModelKeyFor("srv-sonnet"))
    }

    @Test
    fun `a server nobody asked has no default — not the last one probed`() {
        setClaudeDefault("srv-answered", "Opus 5", "opus")
        assertNull(claudeDefaultModelFor("srv-never-asked"))
        assertNull(claudeDefaultModelKeyFor("srv-never-asked"))
        assertNull(claudeDefaultModelFor(null))
    }

    @Test
    fun `the chip shows this server's default, never another server's`() {
        setClaudeDefault("srv-a", "Opus 5", "opus")
        setClaudeDefault("srv-b", "Sonnet 5", "sonnet")

        assertEquals("Opus 5", ClaudeSpec.topbarUi.displayLabel(stateOn("srv-a")))
        assertEquals("Sonnet 5", ClaudeSpec.topbarUi.displayLabel(stateOn("srv-b")))
    }

    @Test
    fun `an unknown server prints no model rather than inventing one`() {
        setClaudeDefault("srv-known", "Opus 5", "opus")
        // TOPBAR-MODEL-NEVER-INVENTED-1: null means "hold the last label",
        // which is honest. Printing the known server's answer here is the bug.
        assertNull(ClaudeSpec.topbarUi.displayLabel(stateOn("srv-unknown-box")))
    }

    @Test
    fun `the persisted cache fills blanks but never overwrites a live answer`() {
        setClaudeDefault("srv-live", "Opus 5", "opus")
        // Cold-start hydrate landing late must not drag the chip back to what
        // the last run had cached.
        seedClaudeDefault("srv-live", "Sonnet 5", "sonnet")
        assertEquals("Opus 5", claudeDefaultModelFor("srv-live"))
        assertEquals("opus", claudeDefaultModelKeyFor("srv-live"))

        // …but with nothing known for a server, the cache is exactly what makes
        // frame zero correct instead of empty.
        seedClaudeDefault("srv-cold", "Haiku 4.5", "haiku")
        assertEquals("Haiku 4.5", claudeDefaultModelFor("srv-cold"))
    }

    @Test
    fun `a handshake with no attributable server files nothing`() {
        setClaudeDefault(null, "Opus 5", "opus")
        assertNull(claudeDefaultModelFor(null))
        // And it must not have leaked into a real server's record either.
        assertNull(claudeDefaultModelFor("srv-bystander"))
    }
}
