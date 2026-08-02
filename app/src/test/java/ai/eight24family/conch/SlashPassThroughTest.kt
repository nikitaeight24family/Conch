package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.SlashCommandKind
import ai.eight24family.conch.ui.viewmodel.ChatModal
import ai.eight24family.conch.ui.viewmodel.ChatViewModelSlash
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A command we don't recognise is not an error.
 *
 * Typing `/loop` — a command the CLI has had all along, and which our own
 * palette was already listing — popped a modal reading **"no such command"**
 * and swallowed the line (user, 2026-08-03). The client cannot be the arbiter
 * of what the CLI knows: it ships new commands, skills and plugins on its own
 * schedule, and a phone that vets names can only ever be behind. Unknown → send
 * the line, let the CLI answer.
 */
class SlashPassThroughTest {

    private var modal: ChatModal? = null

    private fun coord(agent: Agent = Agent.CLAUDE) = ChatViewModelSlash(
        scope = TestScope(),
        serverId = "s1",
        currentAgent = { agent },
        currentLocalSessionId = { null },
        sessionAccess = { null },
        observedCwd = { null },
        setModal = { modal = it },
        postSendUpdate = { },
        newSession = { },
    )

    @Test
    fun `an unknown command is passed through, not refused`() {
        val c = coord()
        assertFalse("/loop must reach the CLI", c.runSlash("/loop check the deploy"))
        assertNull("and nothing may be popped over it", modal)
    }

    @Test
    fun `the CLI's own commands are recognised once the handshake lands`() {
        val c = coord()
        c.setAgentCommands(listOf(SlashCommand("loop", "run on an interval", SlashCommandKind.AGENT_BUILTIN)))
        // Handled here only because dispatch sends the very same line; either
        // path ends at the CLI, which is the point.
        assertTrue(c.runSlash("/loop check the deploy"))
        assertNull(modal)
    }

    @Test
    fun `our own handlers still win`() {
        val c = coord()
        assertTrue(c.runSlash("/memory"))
        assertTrue("the memory editor still opens", modal is ChatModal.Memory)
    }
}
