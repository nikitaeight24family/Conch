package ai.eight24family.conch

import ai.eight24family.conch.agent.UsageProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The usage parser must be DYNAMIC — every rate window the endpoint returns,
 * not a hardcoded four. A new model's cap (Fable 5 today, whatever ships next)
 * has to surface on its own instead of being dropped (user 2026-07-16).
 */
class ClaudeUsageParseTest {

    // A realistic oauth/usage body: the two aggregate windows + the per-model
    // "second layer", including a model (fable) our old hardcoded list never knew.
    private val json = """
        {
          "five_hour":       {"utilization": 12.0, "resets_at": "2026-07-16T20:30:00Z"},
          "seven_day":       {"utilization": 2.0,  "resets_at": "2026-07-19T00:00:00Z"},
          "seven_day_opus":  {"utilization": 30.0, "resets_at": "2026-07-19T00:00:00Z"},
          "seven_day_sonnet":{"utilization": 8.0,  "resets_at": "2026-07-19T00:00:00Z"},
          "seven_day_fable": {"utilization": 55.0, "resets_at": "2026-07-19T00:00:00Z"},
          "extra_usage":     {"used_credits": 1.25}
        }
    """.trimIndent()

    @Test
    fun `every window is parsed, including a never-hardcoded model`() {
        val w = UsageProbe.parseClaude(json)
        val labels = w.map { it.label }
        // All five rate windows (extra_usage is NOT a window → excluded).
        assertEquals(5, w.size)
        assertTrue("Fable surfaces on its own", labels.any { it.contains("Fable") })
        assertTrue(labels.contains("5-hour · all models"))
        assertTrue(labels.contains("Weekly · all models"))
        assertTrue(labels.any { it.startsWith("Opus") })
    }

    @Test
    fun `order is aggregates first, then per-model second layer`() {
        val w = UsageProbe.parseClaude(json)
        assertEquals("5-hour · all models", w[0].label)
        assertEquals("Weekly · all models", w[1].label)
        // The remaining three are the per-model layer.
        assertTrue(w.drop(2).all { it.perModel })
        assertFalse(w[0].perModel)
        assertFalse(w[1].perModel)
    }

    @Test
    fun `percent and reset epoch are read per window`() {
        val w = UsageProbe.parseClaude(json).associateBy { it.label }
        assertEquals(12, w["5-hour · all models"]!!.percent)
        assertEquals(55, w.values.first { it.label.contains("Fable") }.percent)
        assertTrue("absolute reset anchored", w["Weekly · all models"]!!.resetAtEpochMs != null)
    }

    @Test
    fun `a future unknown model key still yields a clean label`() {
        // Whatever ships next — a made-up "seven_day_quartz" — must read cleanly,
        // proving the parser needs no code change per new model.
        val future = """{"seven_day_quartz": {"utilization": 5.0, "resets_at": "2026-07-19T00:00:00Z"}}"""
        val w = UsageProbe.parseClaude(future)
        assertEquals(1, w.size)
        assertEquals("Quartz · weekly", w[0].label)
        assertTrue(w[0].perModel)
    }

    @Test
    fun `no windows for a body without utilization`() {
        assertTrue(UsageProbe.parseClaude("""{"extra_usage":{"used_credits":3.0}}""").isEmpty())
        assertTrue(UsageProbe.parseClaude("{}").isEmpty())
    }
}
