package ai.eight24family.conch

import ai.eight24family.conch.linux.LinuxEnv
import ai.eight24family.conch.linux.LinuxEnv.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's row on the servers list says what the machine IS.
 *
 * Three states, and the one that matters is the third: an environment that is
 * installed but momentarily out of reach must not be advertised as absent, or
 * the row invites the owner to install a second one on top of the first.
 */
class LocalMachineRowTest {

    private fun sub(p: Presence?, summary: String? = null, size: String? = null) =
        LinuxEnv.subtitle(LinuxEnv.Snapshot(presence = p, summary = summary, size = size))

    @Test
    fun `installed shows what the distribution calls itself, and its size`() {
        assertEquals(
            "Alpine Linux v3.21 · 32 packages · 49M",
            sub(Presence.INSTALLED, "Alpine Linux v3.21 · 32 packages", "49M"),
        )
    }

    @Test
    fun `installed but not yet described still reads as a machine, never as missing`() {
        val s = sub(Presence.INSTALLED)
        assertEquals("linux", s)
        assertFalse(s.contains("set up"))
    }

    @Test
    fun `absent is the only state that offers to install`() {
        assertTrue(sub(Presence.ABSENT).contains("tap to set up"))
    }

    @Test
    fun `unreachable says so and does NOT offer to install over it`() {
        val s = sub(Presence.UNREACHABLE)
        assertTrue(s.contains("not connected"))
        // ⛔ THE ONE THAT MUST NOT REGRESS. isInstalled() folds "could not look"
        // into false; if the row ever reads that Boolean again, this line fails.
        assertFalse(s.contains("set up"))
    }

    @Test
    fun `never looked yet is silent rather than wrong`() {
        val s = sub(null)
        assertEquals("linux", s)
        assertFalse(s.contains("set up"))
        assertFalse(s.contains("not connected"))
    }

    @Test
    fun `size alone still renders when the environment has not been asked its name`() {
        assertEquals("linux · 49M", sub(Presence.INSTALLED, null, "49M"))
    }
}
