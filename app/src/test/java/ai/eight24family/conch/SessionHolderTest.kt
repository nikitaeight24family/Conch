package ai.eight24family.conch

import ai.eight24family.conch.agent.SessionHolder
import ai.eight24family.conch.agent.claude.ClaudeSessionLock
import ai.eight24family.conch.agent.codex.CodexThreadLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on "who else has this session open".
 *
 * The behavioural half of this was proved against the owner's server on
 * 2026-09-11 and is recorded on [SessionHolder]: Claude holds NO fd on its
 * rollout and takes no lock on it, so a probe that asks only about file handles
 * finds a console REPL never. These tests pin the consequences that a later
 * refactor could silently undo — the CWD layer existing at all, an
 * [SessionHolder.Probe.Unreachable] never collapsing into
 * [SessionHolder.Probe.Free], and shell injection staying impossible.
 */
class SessionHolderTest {

    private val id = "0b7dc7b7-85ad-4000-b58e-455f1432a6ef"

    // ── the probe script ────────────────────────────────────────────────────

    @Test
    fun `claude probe carries the cwd layer, because fds find nothing`() {
        val s = ClaudeSessionLock.probeScript(id, "/home/user/conch")
        // The layer that actually finds a console REPL: its working directory,
        // mangled the way the CLI names project dirs.
        assertTrue("no cwd readlink", s.contains("/proc/\$p/cwd"))
        assertTrue("no slug mangle", s.contains("sed 's/[^a-zA-Z0-9]/-/g'"))
        assertTrue("cwd not interpolated", s.contains("/home/user/conch"))
        // Narrowed to ONE session: newest rollout in the directory, and the
        // process must predate that file's last write.
        assertTrue("no newest-file narrowing", s.contains("ls -1t"))
        assertTrue("no start-time narrowing", s.contains("etimes="))
        // And still classifies by what the holder talks to.
        assertTrue("no tty classification", s.contains("/dev/pts/*"))
    }

    @Test
    fun `⛔ the cwd layer demands the process actually BE the cli`() {
        // `pgrep -f claude` matches the owner's `/usr/bin/python3
        // ~/tgsend/tgclaude.py`, whose cwd is /home/user — a real Claude
        // project directory. Without an argv[0] check it reads as a HEADLESS
        // holder of his newest session and the orphan cleanup ends it.
        val s = ClaudeSessionLock.probeScript(id, "/home/user")
        assertTrue("no argv[0] check", s.contains("\${a0##*/}"))
        assertTrue("no interpreter argv[1] check", s.contains("\${a1##*/}"))
        assertTrue("check not bound to the cli name", s.contains("in claude|claude.*)"))
        // ...and it must gate the CWD layer specifically, not the fd/argv ones,
        // which are already evidence about the session itself.
        val cwdAt = s.indexOf("/proc/\$p/cwd")
        val realAt = s.indexOf("real=''")
        assertTrue("argv[0] gate must precede the cwd read", realAt in 1 until cwdAt)
    }

    @Test
    fun `with no cwd the claude probe degrades instead of lying`() {
        val s = ClaudeSessionLock.probeScript(id, null)
        // dir empty => the cwd layer is skipped, never half-evaluated against
        // an unset shell var.
        assertTrue(s.contains("dir=''"))
        assertFalse(s.contains("/proc/\$p/cwd\" 2>/dev/null); \n"))
    }

    @Test
    fun `codex probe keeps the fd layer that was measured to work for it`() {
        val s = CodexThreadLock.probeScript(id)
        assertTrue("no fd layer", s.contains("/proc/\$p/fd"))
        // Codex needs no cwd layer — it does hold its rollout open.
        assertTrue(s.contains("dir=''"))
    }

    @Test
    fun `every probe has an argv layer, which is the only exact one`() {
        assertTrue(ClaudeSessionLock.probeScript(id, "/tmp/x").contains("cmdline"))
        assertTrue(CodexThreadLock.probeScript(id).contains("cmdline"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a session id that could carry shell metacharacters is refused`() {
        SessionHolder.probeScript("x'; rm -rf ~; echo '", "claude")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pgrep pattern that could carry shell metacharacters is refused`() {
        SessionHolder.probeScript(id, "claude; rm -rf /")
    }

    @Test
    fun `canProbe answers before probeScript can throw`() {
        assertTrue(SessionHolder.canProbe(id, "claude"))
        assertFalse(SessionHolder.canProbe(null, "claude"))
        assertFalse(SessionHolder.canProbe("not-a-uuid-!", "claude"))
        assertFalse(SessionHolder.canProbe(id, "cursor agent"))
    }

    @Test
    fun `a cwd with a quote in it cannot break out of the script`() {
        val s = ClaudeSessionLock.probeScript(id, "/tmp/it's here")
        // shellEscape must have neutralised it; the raw sequence that would end
        // the quoting and start a command must not appear.
        assertFalse(s.contains("'/tmp/it's here'"))
    }

    // ── parsing the answer ──────────────────────────────────────────────────

    @Test
    fun `a tty holder is a person and is reported as one`() {
        val p = SessionHolder.parseProbe(
            "${SessionHolder.MARK_HOLDER}4029585|TTY|/dev/pts/3|Thu Sep 11 09:10:00 2026|cwd"
        )
        val h = (p as SessionHolder.Probe.Held).holders.single()
        assertEquals(4029585L, h.pid)
        assertEquals(SessionHolder.Kind.TTY, h.kind)
        assertEquals("/dev/pts/3", h.stream)
        assertEquals("cwd", h.via)
    }

    @Test
    fun `a pipe holder is our own orphan`() {
        val p = SessionHolder.parseProbe("${SessionHolder.MARK_HOLDER}1537|HEADLESS|pipe:[11850]||fd")
        assertEquals(
            SessionHolder.Kind.HEADLESS,
            (p as SessionHolder.Probe.Held).holders.single().kind,
        )
    }

    @Test
    fun `a pre-via holder line still parses, so an older script is not a crash`() {
        val p = SessionHolder.parseProbe("${SessionHolder.MARK_HOLDER}77|TTY|/dev/pts/0|x")
        assertEquals("", (p as SessionHolder.Probe.Held).holders.single().via)
    }

    @Test
    fun `free is free`() {
        assertEquals(SessionHolder.Probe.Free, SessionHolder.parseProbe(SessionHolder.MARK_FREE))
    }

    @Test
    fun `a login shell's chatter around the sentinel does not hide it`() {
        val p = SessionHolder.parseProbe("Welcome to Ubuntu\n  ${SessionHolder.MARK_FREE}  \nbye")
        assertEquals(SessionHolder.Probe.Free, p)
    }

    @Test
    fun `⛔ a probe that could not run is NOT free`() {
        // This is the distinction the phantom sessions of 2026-07..09 were made
        // of: acting on a guess. No output, or output with no sentinel, means we
        // learned nothing — and "nobody has it" is a licence to launch.
        assertEquals(SessionHolder.Probe.Unreachable, SessionHolder.parseProbe(null))
        assertEquals(SessionHolder.Probe.Unreachable, SessionHolder.parseProbe(""))
        assertEquals(
            SessionHolder.Probe.Unreachable,
            SessionHolder.parseProbe("bash: pgrep: command not found"),
        )
    }

    @Test
    fun `holders win over a stray free line`() {
        val p = SessionHolder.parseProbe(
            "${SessionHolder.MARK_HOLDER}12|TTY|/dev/pts/1|x|cwd\n${SessionHolder.MARK_FREE}"
        )
        assertTrue(p is SessionHolder.Probe.Held)
    }

    // ── what the user reads ────────────────────────────────────────────────

    @Test
    fun `the row names where the session is, not just that it is busy`() {
        val holders = listOf(
            SessionHolder.Holder(1537, SessionHolder.Kind.HEADLESS, "pipe:[1]", "", "fd"),
            SessionHolder.Holder(4029585, SessionHolder.Kind.TTY, "/dev/pts/3", "Thu Sep 11 09:10:00 2026", "cwd"),
        )
        val note = ClaudeSessionLock.ttyHolderNote(holders)
        // The TTY holder is the one a person can go and look at — it must be the
        // one named, even though it is not first in the list.
        assertTrue(note.contains("/dev/pts/3"))
        assertTrue(note.contains("4029585"))
        assertTrue(note.contains("Thu Sep 11 09:10:00 2026"))
        assertTrue("must offer the way out", note.contains("tap to take it over"))
        // Claude's reason differs from codex's and the row has to say which.
        assertTrue(note.contains("fork its history"))
        assertTrue(CodexThreadLock.ttyHolderNote(holders).contains("one writer"))
    }

    @Test
    fun `the app UI stays English-only`() {
        val texts = listOf(
            ClaudeSessionLock.ttyHolderNote(
                listOf(SessionHolder.Holder(1, SessionHolder.Kind.TTY, "/dev/pts/0", "now", "cwd"))
            ),
            ClaudeSessionLock.takenOverNote(listOf(1L, 2L)),
            ClaudeSessionLock.takeoverFailedNote(),
            CodexThreadLock.takeoverFailedNote(),
        )
        // Code points, not characters. Writing this range as a character
        // literal would put Cyrillic into the source of the very test that
        // forbids it, and the mirror's publish gate — correctly — refuses to
        // ship any. U+0400..U+04FF is the same block, spelled numerically.
        for (t in texts) {
            assertFalse(
                "Cyrillic leaked into a user-visible string: $t",
                t.any { it.code in 0x0400..0x04FF },
            )
        }
    }

    @Test
    fun `the takeover marker ids are distinct and stable`() {
        assertEquals("conch-claude-session-held", ClaudeSessionLock.TAKEOVER_MARKER_ID)
        assertEquals("conch-codex-thread-locked", CodexThreadLock.TAKEOVER_MARKER_ID)
    }
}
