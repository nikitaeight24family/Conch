package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentSessionHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the core chat invariant the user paid for many times:
 *
 *   **A user's sent prompt must NEVER vanish from the chat.**
 *
 * [AgentSessionHistory.loadHistory] is the ONLY history op that replaces the
 * list (every rebuild — reconnect / read-only re-open / offline→connect /
 * resume — calls it with the server JSONL or local cache, which LAGS by ~one
 * turn). A blind replace silently deleted the just-sent optimistic prompt —
 * the recurring bug. loadHistory now preserves any un-synced user prompt
 */
class AgentSessionHistoryLoadHistoryTest {

    private fun newHistory() = AgentSessionHistory(CoroutineScope(Dispatchers.Unconfined))

    private fun userCount(h: AgentSessionHistory, body: String): Int =
        h.history.value.filterIsInstance<AgentMessage.UserText>().count { it.text.trim() == body }

    @Test
    fun loadHistory_preserves_unsynced_optimistic_prompt() {
        val h = newHistory()
        // User just sent → shown optimistically (local UUID id).
        h.emitMsg(AgentMessage.UserText("opt-1", "more"))
        // A rebuild reloads from cache/JSONL that hasn't caught up to it yet.
        h.loadHistory(listOf(AgentMessage.AssistantText("a0", "earlier reply")))
        assertEquals("un-synced prompt must survive the rebuild", 1, userCount(h, "more"))
    }

    @Test
    fun loadHistory_does_not_duplicate_a_synced_prompt() {
        val h = newHistory()
        h.emitMsg(AgentMessage.UserText("opt-1", "more"))
        // JSONL now carries the same prompt under its own canonical id.
        h.loadHistory(listOf(AgentMessage.UserText("jsonl-1", "more")))
        assertEquals("a synced prompt shows exactly once (no dup)", 1, userCount(h, "more"))
    }

    @Test
    fun loadHistory_keeps_surplus_when_repeats_only_partially_synced() {
        val h = newHistory()
        h.emitMsg(AgentMessage.UserText("opt-1", "more"))
        h.emitMsg(AgentMessage.UserText("opt-2", "more"))
        // JSONL synced only ONE of the two identical sends.
        h.loadHistory(listOf(AgentMessage.UserText("jsonl-1", "more")))
        assertEquals("count = max(optimistic, synced)", 2, userCount(h, "more"))
    }

    @Test
    fun loadHistory_replaces_agent_content_normally() {
        val h = newHistory()
        // Agent content is JSONL-authoritative — a stale one IS replaced.
        h.loadHistory(listOf(AgentMessage.AssistantText("a-old", "stale")))
        h.loadHistory(listOf(AgentMessage.AssistantText("a-new", "fresh")))
        val texts = h.history.value.filterIsInstance<AgentMessage.AssistantText>().map { it.text }
        assertEquals(listOf("fresh"), texts)
    }

    @Test
    fun loadHistory_preserved_prompt_lands_after_incoming() {
        val h = newHistory()
        h.emitMsg(AgentMessage.UserText("opt-1", "latest question"))
        h.loadHistory(
            listOf(
                AgentMessage.UserText("j-1", "older question"),
                AgentMessage.AssistantText("j-2", "older answer"),
            )
        )
        // Surplus optimistic prompt is the most-recent message → appended last.
        assertEquals("latest question", (h.history.value.last() as AgentMessage.UserText).text)
        assertEquals(3, h.history.value.size)
    }
}
