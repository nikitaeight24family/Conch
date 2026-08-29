package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentStatusProbe
import ai.eight24family.conch.agent.UsageProbe
import ai.eight24family.conch.agent.claude.ClaudeMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three screens that were showing numbers nobody could stand behind, and the
 * read-only guard state that replaced a guess with a fact.
 *
 * Every case here came off the user's own screenshots (2026-08-29): a limit
 * warning reading "seven day · 0%" beside a usage bar reading 25%, and an
 * unreleased model codename sitting in the usage panel at 0%.
 */
class LimitAndGuardTest {

    private fun note(events: String): String? =
        ClaudeMessageParser.parse(events)
            .filterIsInstance<AgentMessage.EventNote>()
            .firstOrNull()?.label

    // ── the pushed limit event carries a FRACTION ────────────────────────────

    @Test
    fun `warning utilization arrives as a fraction and reads as a percent`() {
        val text = note(
            """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning",""" +
                """"rateLimitType":"seven_day","utilization":0.25}}"""
        )
        // 0.25 → 25%, NOT the 0% that toInt() used to print.
        assertEquals("close to the limit · weekly · 25%", text)
    }

    @Test
    fun `the endpoint convention 0 to 100 is left alone`() {
        val text = note(
            """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning",""" +
                """"rateLimitType":"five_hour","utilization":61.0}}"""
        )
        assertEquals("close to the limit · 5-hour · 61%", text)
    }

    @Test
    fun `a percentage that rounds away is omitted, never printed as zero`() {
        val text = note(
            """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning",""" +
                """"rateLimitType":"seven_day","utilization":0.001}}"""
        )
        // "close to the limit · 0%" contradicts itself: name the window only.
        assertEquals("close to the limit · weekly", text)
    }

    @Test
    fun `rejected still names the window in the same words`() {
        val err = ClaudeMessageParser.parse(
            """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected",""" +
                """"rateLimitType":"seven_day","utilization":1.0}}"""
        ).filterIsInstance<AgentMessage.Error>().first()
        assertEquals("Limit reached · weekly · 100%", err.text)
    }

    // ── usage panel: no changelog of unreleased models ───────────────────────

    @Test
    fun `an unknown window at zero with no reset is dropped`() {
        val windows = UsageProbe.parseClaude(
            """{"rate_limits":{"five_hour":{"utilization":61.0,"resets_at":"2026-08-29T13:19:00Z"},""" +
                """"seven_day":{"utilization":25.0,"resets_at":"2026-09-04T18:59:00Z"},""" +
                """"nimbus_quill":{"utilization":0.0}}}"""
        )
        assertTrue(windows.none { it.label.contains("Nimbus", ignoreCase = true) })
        assertEquals(2, windows.size)
    }

    @Test
    fun `an unknown window that is actually being used still shows`() {
        val windows = UsageProbe.parseClaude(
            """{"rate_limits":{"seven_day":{"utilization":25.0},""" +
                """"nimbus_quill":{"utilization":3.0}}}"""
        )
        assertTrue(windows.any { it.label.contains("Nimbus", ignoreCase = true) })
    }

    @Test
    fun `a canonical window at zero is never hidden`() {
        val windows = UsageProbe.parseClaude("""{"rate_limits":{"seven_day":{"utilization":0.0}}}""")
        assertEquals(1, windows.size)
    }

    // ── guard: read-only, three states ───────────────────────────────────────

    private fun guard(of: Agent, probeOutput: String): Boolean? =
        AgentStatusProbe.parse(probeOutput)[of]?.guardProtecting

    @Test
    fun `no guard on the server says nothing about any agent`() {
        assertNull(guard(Agent.CLAUDE, "guard_present=n"))
        assertNull(guard(Agent.CRUSH, "guard_present=n"))
    }

    @Test
    fun `a managed harness reads as protected`() {
        val out = "guard_present=y\nguard_on=y\nguard_managed=claude-code,grok"
        assertEquals(true, guard(Agent.CLAUDE, out))
        assertEquals(true, guard(Agent.GROK, out))
        // Known to the guard, but not managed by it — a real state, and not the
        // same as "no guard here".
        assertEquals(false, guard(Agent.CODEX, out))
    }

    @Test
    fun `protection switched off is not protection`() {
        val out = "guard_present=y\nguard_on=n\nguard_managed=claude-code"
        assertEquals(false, guard(Agent.CLAUDE, out))
    }

    @Test
    fun `a CLI the guard has never heard of stays silent`() {
        // hol-guard 3.0.18 knows nothing about qwen, crush or continue: they
        // must show nothing rather than an unearned "unprotected".
        val out = "guard_present=y\nguard_on=y\nguard_managed=claude-code"
        assertNull(guard(Agent.QWEN, out))
        assertNull(guard(Agent.CRUSH, out))
        assertNull(guard(Agent.CONTINUE, out))
    }
}
