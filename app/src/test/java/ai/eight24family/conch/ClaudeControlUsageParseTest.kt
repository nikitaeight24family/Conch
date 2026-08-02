package ai.eight24family.conch

import ai.eight24family.conch.agent.UsageProbe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the mapping of the CLI's `get_usage` / `get_context_usage`
 * control_response payloads onto the usage bar / context panel models.
 * The get_usage windows are the same shape the oauth endpoint returns
 * (the CLI caches that endpoint) EXCEPT `resets_at` arrives as epoch
 * seconds, not ISO — both must parse.
 */
class ClaudeControlUsageParseTest {

    @Test
    fun `get_usage payload maps to windows plan and model_scoped rows`() {
        val futureEpoch = (System.currentTimeMillis() / 1000) + 9000
        val payload = """
            {"session":{"total_cost_usd":1.25,"total_duration_ms":100},
             "subscription_type":"max",
             "rate_limits_available":true,
             "rate_limits":{
                "five_hour":{"utilization":41.0,"resets_at":$futureEpoch},
                "seven_day":{"utilization":73.5,"resets_at":"2099-01-03T05:00:00Z"},
                "model_scoped":[
                  {"display_name":"Sonnet only","utilization":12.0,"resets_at":$futureEpoch},
                  {"display_name":"Opus only","utilization":null,"resets_at":null}
                ]
             },
             "behaviors":null}
        """.trimIndent()
        val report = UsageProbe.reportFromControlPayload(payload)!!
        assertEquals("Max", report.plan)
        val labels = report.windows.map { it.label }
        assertTrue(labels.contains("5-hour · all models"))
        assertTrue(labels.contains("Weekly · all models"))
        assertTrue(labels.contains("Sonnet only"))
        // The null-utilization model row is skipped, never rendered as 0%.
        assertTrue(labels.none { it == "Opus only" })
        val fiveHour = report.windows.first { it.label == "5-hour · all models" }
        assertEquals(41, fiveHour.percent)
        // Epoch-seconds resets_at must produce a live countdown anchor.
        assertEquals(futureEpoch * 1000, fiveHour.resetAtEpochMs)
        val sonnet = report.windows.first { it.label == "Sonnet only" }
        assertTrue(sonnet.perModel)
    }

    @Test
    fun `payload without windows returns null so callers fall back`() {
        assertNull(
            UsageProbe.reportFromControlPayload(
                """{"session":{"total_cost_usd":0},"subscription_type":null,"rate_limits":null}"""
            )
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `get_context_usage payload maps to context segments`() {
        val payload = json.parseToJsonElement(
            """
            {"categories":[
                {"name":"System prompt","tokens":3100,"color":"promptBorder"},
                {"name":"System tools","tokens":11800,"color":"inactive"},
                {"name":"Messages","tokens":52000,"color":"purple"},
                {"name":"Free space","tokens":133100,"color":"promptBorder"},
                {"name":"MCP tools","tokens":0,"color":"cyan"}
             ],
             "totalTokens":66900,"maxTokens":200000,"rawMaxTokens":200000,
             "percentage":33,"autoCompactThreshold":160000,"isAutoCompactEnabled":true,
             "model":"claude-sonnet-5"}
            """.trimIndent()
        ).jsonObject
        val segs = UsageProbe.contextFromControlPayload(payload)
        assertEquals("Context window", segs.first().label)
        assertEquals("66.9k / 200.0k", segs.first().tokens)
        assertEquals(33f, segs.first().percent)
        val labels = segs.map { it.label }
        assertTrue(labels.contains("System prompt"))
        assertTrue(labels.contains("Messages"))
        // Zero-token categories are dropped, not rendered as empty rows.
        assertTrue(labels.none { it == "MCP tools" })
        val messages = segs.first { it.label == "Messages" }
        assertEquals("52.0k", messages.tokens)
        assertEquals(26f, messages.percent)
        // Percentages are READINGS, not raw floats: 11800/1000000 must not
        // reach the screen as "1.1799999%" (caught on device, 2026-08-02).
        val sys = segs.first { it.label == "System prompt" }
        assertEquals(1.6f, sys.percent)
    }

    @Test
    fun `context payload without totals degrades to empty`() {
        val payload = json.parseToJsonElement("""{"categories":[]}""").jsonObject
        assertTrue(UsageProbe.contextFromControlPayload(payload).isEmpty())
    }
}
