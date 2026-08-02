package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentSessionHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ON-SCREEN half of a rewind. The server forks its transcript, so nothing
 * disappears unless we drop it locally — and the row to drop is almost always
 * the OPTIMISTIC bubble, which carries no record uuid (its file echo is
 * discarded as a duplicate). Matching only by uuid made the server rewind
 * while the discarded turn stayed on screen: a rewind that looked half-broken
 * (caught on device, 2026-08-02).
 */
class RewindTruncateTest {

    private fun history(): AgentSessionHistory =
        AgentSessionHistory(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    private fun seed(h: AgentSessionHistory, vararg msgs: AgentMessage) {
        h.loadHistory(msgs.toList())
    }

    @Test
    fun `truncates by record uuid when the row carries one`() {
        val h = history()
        seed(
            h,
            AgentMessage.UserText("1", "first", recordUuid = "u-1"),
            AgentMessage.AssistantText("2", "reply one"),
            AgentMessage.UserText("3", "second", recordUuid = "u-2"),
            AgentMessage.AssistantText("4", "reply two"),
        )
        assertEquals(2, h.truncateFromUserRecord("u-2").size)
        assertEquals(listOf("1", "2"), h.history.value.map { it.id })
    }

    @Test
    fun `falls back to the prompt text for an optimistic bubble`() {
        val h = history()
        seed(
            h,
            AgentMessage.UserText("1", "first", recordUuid = "u-1"),
            AgentMessage.AssistantText("2", "reply one"),
            // Just sent: no uuid, because the file echo that carries it is
            // dropped as a duplicate of this very row.
            AgentMessage.UserText("3", "second"),
            AgentMessage.AssistantText("4", "reply two"),
        )
        assertEquals(2, h.truncateFromUserRecord("u-2", fallbackText = "second").size)
        assertEquals(listOf("1", "2"), h.history.value.map { it.id })
    }

    @Test
    fun `a repeated prompt truncates at the LAST occurrence`() {
        val h = history()
        seed(
            h,
            AgentMessage.UserText("1", "again"),
            AgentMessage.AssistantText("2", "a"),
            AgentMessage.UserText("3", "again"),
            AgentMessage.AssistantText("4", "b"),
        )
        assertEquals(2, h.truncateFromUserRecord("nope", fallbackText = "again").size)
        assertEquals(listOf("1", "2"), h.history.value.map { it.id })
    }

    @Test
    fun `an anchor that is nowhere on screen touches nothing`() {
        val h = history()
        seed(
            h,
            AgentMessage.UserText("1", "first", recordUuid = "u-1"),
            AgentMessage.AssistantText("2", "reply"),
        )
        assertEquals(0, h.truncateFromUserRecord("u-9", fallbackText = "not here").size)
        assertEquals(listOf("1", "2"), h.history.value.map { it.id })
    }
}
