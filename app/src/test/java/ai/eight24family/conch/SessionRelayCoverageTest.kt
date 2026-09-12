package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.SessionHolder
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EVERY agent must be probe-able, not just the two that grew the feature.
 *
 * The relay bounds its `/proc` scan with `pgrep -f <cliCommand>`, and
 * [SessionHolder.canProbe] refuses a pattern it cannot safely interpolate. A new
 * agent whose binary is called something else (an uppercase name, a dot, a
 * path) would silently opt itself OUT of collision protection — the phone would
 * quietly become the second writer on that CLI's sessions and nobody would see
 * an error. This test is the tripwire: add an agent, and it fails until the name
 * is probe-able or the exclusion is deliberate.
 */
class SessionRelayCoverageTest {

    private val sampleSessionId = "019924ab-7e31-7c0a-9f11-2b6d5c8e40aa"

    @Test
    fun `every shipped agent's CLI name can bound a probe`() {
        for (agent in Agent.entries) {
            val cli = agent.cliCommand
            assertTrue(
                "agent ${agent.name} has cliCommand '$cli', which cannot bound a pgrep probe — " +
                    "it would ship with no session-collision protection",
                SessionHolder.canProbe(sampleSessionId, cli),
            )
        }
    }

    @Test
    fun `a session id that is not id-shaped is never interpolated into a script`() {
        // The relay is handed whatever the CLI minted; a name-like "session id"
        // (some agents let a user name a session) must not reach the shell.
        for (bad in listOf("../../etc/passwd", "a b", "\$(id)", "'; rm -rf /", "x".repeat(64))) {
            assertTrue(
                "unsafe id accepted: $bad",
                !SessionHolder.canProbe(bad, "claude"),
            )
        }
    }
}
