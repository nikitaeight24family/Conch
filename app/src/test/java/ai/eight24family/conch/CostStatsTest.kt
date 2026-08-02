package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.ui.viewmodel.computeCostStats
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the cost/usage aggregator. Pulls `usage` out of system events
 * and `total_cost_usd` / `cost_usd` out of result events, summed across
 * the whole conversation.
 *
 * The aggregator is a `internal` top-level fun; we exercise it directly
 * with crafted [AgentMessage] lists.
 */
class CostStatsTest {

    private fun system(raw: String) = AgentMessage.System(
        id = "s", subtype = "init", raw = raw
    )

    private fun result(text: String?) = AgentMessage.Result(
        id = "r", subtype = "success", text = text
    )

    @Test
    fun `empty list yields zeros`() {
        val s = computeCostStats(emptyList())
        assertEquals(0L, s.inputTokens)
        assertEquals(0L, s.outputTokens)
        assertEquals(0L, s.cacheCreationTokens)
        assertEquals(0L, s.cacheReadTokens)
        assertEquals(0.0, s.totalCostUsd, 1e-9)
        assertEquals(0, s.turns)
    }

    @Test
    fun `system event with usage is summed`() {
        val raw = """{"type":"system","usage":{"input_tokens":10,"output_tokens":20,"cache_creation_input_tokens":3,"cache_read_input_tokens":7}}"""
        val s = computeCostStats(listOf(system(raw)))
        assertEquals(10L, s.inputTokens)
        assertEquals(20L, s.outputTokens)
        assertEquals(3L, s.cacheCreationTokens)
        assertEquals(7L, s.cacheReadTokens)
    }

    @Test
    fun `usage nested under message field is also picked up`() {
        // Some Claude assistant events nest usage under `message`.
        val raw = """{"type":"system","message":{"usage":{"input_tokens":5,"output_tokens":15}}}"""
        val s = computeCostStats(listOf(system(raw)))
        assertEquals(5L, s.inputTokens)
        assertEquals(15L, s.outputTokens)
    }

    @Test
    fun `result event with total_cost_usd adds to cost`() {
        val txt = """{"type":"result","subtype":"success","total_cost_usd":0.0042}"""
        val s = computeCostStats(listOf(result(txt)))
        assertEquals(0.0042, s.totalCostUsd, 1e-9)
        assertEquals(1, s.turns)
    }

    @Test
    fun `result event with cost_usd alias also adds`() {
        val txt = """{"type":"result","subtype":"success","cost_usd":0.001}"""
        val s = computeCostStats(listOf(result(txt)))
        assertEquals(0.001, s.totalCostUsd, 1e-9)
    }

    @Test
    fun `multiple turns sum correctly`() {
        val list = listOf(
            system("""{"usage":{"input_tokens":100,"output_tokens":200}}"""),
            result("""{"total_cost_usd":0.01}"""),
            system("""{"usage":{"input_tokens":50,"output_tokens":75,"cache_read_input_tokens":40}}"""),
            result("""{"total_cost_usd":0.005}""")
        )
        val s = computeCostStats(list)
        assertEquals(150L, s.inputTokens)
        assertEquals(275L, s.outputTokens)
        assertEquals(40L, s.cacheReadTokens)
        assertEquals(0.015, s.totalCostUsd, 1e-9)
        assertEquals(2, s.turns)
    }

    @Test
    fun `turns counts every Result regardless of cost`() {
        val list = listOf(
            result(null),
            result(""),
            result("""{"subtype":"error"}""")
        )
        val s = computeCostStats(list)
        assertEquals(3, s.turns)
        assertEquals(0.0, s.totalCostUsd, 1e-9)
    }

    @Test
    fun `non-system non-result messages are ignored`() {
        val list = listOf(
            AgentMessage.UserText("u", "hi"),
            AgentMessage.AssistantText("a", "hello"),
            AgentMessage.ToolUse("tu", "Bash", "{}"),
            AgentMessage.Raw("r", "· event")
        )
        val s = computeCostStats(list)
        assertEquals(0L, s.inputTokens)
        assertEquals(0L, s.outputTokens)
        assertEquals(0, s.turns)
    }

    @Test
    fun `malformed json in message is silently skipped`() {
        // pluckUsage uses runCatching — parse failure shouldn't crash the
        // whole stat computation.
        val list = listOf(
            system("not json at all"),
            system("""{"usage":{"input_tokens":42}}""")
        )
        val s = computeCostStats(list)
        assertEquals(42L, s.inputTokens)
    }

    @Test
    fun `system event with empty raw is skipped (avoid noise)`() {
        // The `welcome` synthetic system message has empty raw; it shouldn't
        // contribute zeros (and shouldn't fail).
        val list = listOf(AgentMessage.System(id = "s", subtype = "welcome", raw = ""))
        val s = computeCostStats(list)
        assertEquals(0L, s.inputTokens)
        assertEquals(0, s.turns)
    }
}
