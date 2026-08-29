package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.ui.viewmodel.collapseBridgeHandshake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone handshake must leave ONE row in the chat.
 *
 * ⚠ THE SEQUENCES BELOW ARE TRANSCRIBED FROM REAL SESSION FILES, not invented.
 * Three earlier attempts at this filter failed by assuming an order the agent
 * does not use — the prompt is not first, the usage line is not last, and the
 * turn carries user-role rows that are nobody's message.
 */
class BridgeHandshakeCollapseTest {

    private val marker = "I've connected my phone to this server"
    private val token = "CONCH_BRIDGE_READY"

    private var n = 0
    private fun id() = "m${n++}"
    private fun note(label: String) = AgentMessage.EventNote(id(), label)
    private fun user(text: String) = AgentMessage.UserText(id(), text)
    private fun assistant(text: String) = AgentMessage.AssistantText(id(), text)
    private fun tool() = AgentMessage.ToolUse(id(), "exec", "conch-bridge ping")
    private fun toolOut() = AgentMessage.ToolResult(id(), "exec", "pong", isError = false)

    private fun subtypes(list: List<AgentMessage>) =
        list.filterIsInstance<AgentMessage.System>().map { it.subtype }

    /** The handshake turn exactly as it appears in 01a04cf8…jsonl (2026-08-29). */
    private fun realHandshakeTurn() = listOf(
        note("turn started"),
        note("context · developer"),
        user("<recommended_plugins>\nHere is a list of plugins…"),
        note("world state"),
        user("$marker. There's a CLI at ~/.local/bin/conch-bridge…"),
        tool(),
        toolOut(),
        note("tokens · in 12.7k · out 70 · 3s"),
        assistant(token),
        note("turn complete · 14s"),
    )

    @Test
    fun `the real handshake turn collapses to one row, whole`() {
        val out = collapseBridgeHandshake(realHandshakeTurn(), marker, token)

        assertEquals(listOf("bridge_connected"), subtypes(out))
        assertEquals("one row and nothing else", 1, out.size)
    }

    @Test
    fun `the turn after it is untouched`() {
        val his = user("turn off developer options")
        val answer = assistant("Done.")
        val msgs = realHandshakeTurn() + listOf(
            note("thread settings applied"),
            note("turn started"),
            his,
            answer,
            note("turn complete · 9s"),
        )

        val out = collapseBridgeHandshake(msgs, marker, token)

        assertTrue("his message survives", his in out)
        assertTrue("the reply survives", answer in out)
        assertEquals(listOf("bridge_connected"), subtypes(out))
        assertTrue("his turn keeps its own rows", out.any { it is AgentMessage.EventNote && it.label == "turn started" })
    }

    @Test
    fun `a message caught inside the handshake turn is carried out of it`() {
        // He typed while the handshake was running. Losing that message because
        // of where it landed would be the worst failure this filter can have.
        val his = user("can you see the phone?")
        val msgs = listOf(
            note("turn started"),
            user("$marker …"),
            his,
            tool(),
            assistant(token),
            note("turn complete · 3s"),
        )

        val out = collapseBridgeHandshake(msgs, marker, token)

        assertTrue(his in out)
        assertEquals(listOf("bridge_connected"), subtypes(out))
    }

    @Test
    fun `a turn with no handshake in it is left completely alone`() {
        val msgs = listOf(
            note("turn started"),
            user("what is in /etc?"),
            tool(),
            toolOut(),
            note("tokens · in 1k · out 20 · 1s"),
            assistant("Configuration files."),
            note("turn complete · 2s"),
        )

        assertEquals(msgs, collapseBridgeHandshake(msgs, marker, token))
    }

    @Test
    fun `a handshake still running reads as connecting`() {
        val msgs = listOf(note("turn started"), user("$marker …"), tool())
        assertEquals(listOf("bridge_connecting"), subtypes(collapseBridgeHandshake(msgs, marker, token)))
    }

    @Test
    fun `a turn that ended without confirming reads as failed`() {
        val msgs = listOf(note("turn started"), user("$marker …"), tool(), note("turn complete · 30s"))
        assertEquals(listOf("bridge_failed"), subtypes(collapseBridgeHandshake(msgs, marker, token)))
    }

    @Test
    fun `every handshake collapses, not just the first`() {
        val msgs = realHandshakeTurn() + realHandshakeTurn()
        assertEquals(
            listOf("bridge_connected", "bridge_connected"),
            subtypes(collapseBridgeHandshake(msgs, marker, token)),
        )
        assertEquals(2, collapseBridgeHandshake(msgs, marker, token).size)
    }

    @Test
    fun `rows outside any turn pass through untouched`() {
        val welcome = AgentMessage.System(id(), "welcome", raw = "")
        val msgs = listOf(welcome) + realHandshakeTurn()
        val out = collapseBridgeHandshake(msgs, marker, token)
        assertEquals(listOf("welcome", "bridge_connected"), subtypes(out))
        assertEquals(2, out.size)
    }
}
