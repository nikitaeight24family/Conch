package ai.eight24family.conch

import ai.eight24family.conch.agent.RemoteTurnKiller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one shared kill for a server-side turn nobody owns a channel to
 * (Stop on a mirrored/zombie turn). The script is interpolated into a shell
 * command and its sentinel lines are the ONLY protocol back — both ends need
 * pinning: a script that self-kills its own wrapper never reaches TERM, and a
 * misparsed verdict repaints the spinner over a live turn.
 */
class RemoteTurnKillerTest {

    @Test
    fun `resume id guard accepts uuids and rejects shell metacharacters`() {
        assertTrue(RemoteTurnKiller.isKillableResumeId("2f1c9a34-88f0-4b7e-9a11-000000000000"))
        assertTrue(RemoteTurnKiller.isKillableResumeId("abcdef0123456789"))
        assertFalse(RemoteTurnKiller.isKillableResumeId(null))
        assertFalse(RemoteTurnKiller.isKillableResumeId("short"))
        assertFalse(RemoteTurnKiller.isKillableResumeId("x; rm -rf ~"))
        assertFalse(RemoteTurnKiller.isKillableResumeId("2f1c9a34\$(reboot)"))
        assertFalse(RemoteTurnKiller.isKillableResumeId("id with spaces here"))
    }

    @Test
    fun `script refuses an unsafe id outright`() {
        try {
            RemoteTurnKiller.killScript("evil; rm -rf /; 0123456789abcdef")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `script carries the full ladder and per-rung liveness checks`() {
        val s = RemoteTurnKiller.killScript("2f1c9a34-88f0-4b7e-9a11-000000000000")
        // All three rungs, in order — the 08-11 version stopped at TERM.
        val intAt = s.indexOf("kill -INT")
        val termAt = s.indexOf("kill -TERM")
        val killAt = s.indexOf("kill -KILL")
        assertTrue(intAt in 0 until termAt)
        assertTrue(termAt in 0 until killAt)
        // Each escalation is gated on a survivor list, not fired blind.
        assertTrue(s.contains("kill -0"))
        // Sentinels present — the caller's whole protocol.
        assertTrue(s.contains(RemoteTurnKiller.MARK_NONE))
        assertTrue(s.contains(RemoteTurnKiller.MARK_DONE))
        assertTrue(s.contains(RemoteTurnKiller.MARK_SURVIVED))
    }

    /** The awk argv0 filter must drop shell WRAPPERS however they spell
     *  themselves — `bash`, `/bin/bash`, a login `-bash`, plain `sh` — because
     *  the kill script itself IS such a wrapper (its cmdline carries the id),
     *  and the old `$2 != "bash"` string-compare missed `/bin/bash`. */
    @Test
    fun `wrapper filter covers absolute paths and login shells`() {
        val s = RemoteTurnKiller.killScript("2f1c9a34-88f0-4b7e-9a11-000000000000")
        assertTrue(s.contains("""$2 !~ /(^|\/|-)((ba|da|a|z)?sh)$/"""))
        // The BusyBox fallback (no pgrep -a → can't see argv0) excludes SELF by
        // pid instead.
        assertTrue(s.contains("grep -v \"^\$\$"))
    }

    @Test
    fun `outcome parses done with pids`() {
        val v = RemoteTurnKiller.parseOutcome("CONCH_KILL_DONE: 4021 4022\n")
        assertTrue(v is RemoteTurnKiller.Outcome.Killed)
        assertEquals(listOf(4021L, 4022L), (v as RemoteTurnKiller.Outcome.Killed).pids)
    }

    @Test
    fun `outcome parses survivors`() {
        val v = RemoteTurnKiller.parseOutcome("CONCH_KILL_SURVIVED: 4021")
        assertTrue(v is RemoteTurnKiller.Outcome.Survived)
        assertEquals(listOf(4021L), (v as RemoteTurnKiller.Outcome.Survived).pids)
    }

    @Test
    fun `outcome parses none and transport failure`() {
        assertEquals(RemoteTurnKiller.Outcome.NoneFound, RemoteTurnKiller.parseOutcome("CONCH_KILL_NONE"))
        assertEquals(RemoteTurnKiller.Outcome.Unreachable, RemoteTurnKiller.parseOutcome(null))
    }

    /** A chatty login shell (motd, profile echo) must not be able to fake or
     *  bury the verdict: sentinel is matched per-line, and output with no
     *  sentinel at all reads as UNREACHABLE — never as "killed". */
    @Test
    fun `noise around the sentinel is tolerated - no sentinel is unreachable`() {
        val noisy = "Welcome to Ubuntu\nCONCH_KILL_DONE: 512\nlast login: yesterday"
        assertTrue(RemoteTurnKiller.parseOutcome(noisy) is RemoteTurnKiller.Outcome.Killed)
        assertEquals(
            RemoteTurnKiller.Outcome.Unreachable,
            RemoteTurnKiller.parseOutcome("bash: pgrep: command not found"),
        )
    }
}
