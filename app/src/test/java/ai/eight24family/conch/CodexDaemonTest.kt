package ai.eight24family.conch

import ai.eight24family.conch.agent.codex.CodexDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared-brain capability probe. What it must never do is guess: his own
 * 824 box runs codex 0.80.0 and has none of the three pieces, so an optimistic
 * default would put "type `codex --remote unix://`" in front of him and fail.
 */
class CodexDaemonTest {

    private fun probe(ver: String, d: Int, p: Int, r: Int) =
        "CONCH_CODEXD_VER:$ver\nCONCH_CODEXD_DAEMON:$d\nCONCH_CODEXD_PROXY:$p\nCONCH_CODEXD_REMOTE:$r"

    @Test
    fun `all three pieces means a brain can be shared`() {
        val s = CodexDaemon.parseProbe(probe("0.153.4", 1, 1, 1))!!
        assertEquals("0.153.4", s.version)
        assertTrue(s.sharedBrain)
    }

    @Test
    fun `an old codex is reported with its version, not as a shrug`() {
        // Verbatim from his 824 box: the CLI answers "codex-cli 0.80.0", and the
        // dialog's sentence is "this server runs codex <v>" — so the product
        // name must not survive into it.
        val s = CodexDaemon.parseProbe(probe("codex-cli 0.80.0", 0, 0, 0))!!
        assertEquals("0.80.0", s.version)
        assertFalse(s.sharedBrain)
    }

    @Test
    fun `two of three is still not a shared brain`() {
        // A daemon we cannot proxy into, or one his TUI cannot join, buys
        // nothing — the advice would be half a path.
        assertFalse(CodexDaemon.parseProbe(probe("0.150.0", 1, 1, 0))!!.sharedBrain)
        assertFalse(CodexDaemon.parseProbe(probe("0.150.0", 1, 0, 1))!!.sharedBrain)
        assertFalse(CodexDaemon.parseProbe(probe("0.150.0", 0, 1, 1))!!.sharedBrain)
    }

    @Test
    fun `a probe that did not run is null, never a negative answer`() {
        // Same rule as SessionHolder.Probe.Unreachable: silence is not evidence.
        assertNull(CodexDaemon.parseProbe(null))
        assertNull(CodexDaemon.parseProbe(""))
        assertNull(CodexDaemon.parseProbe("bash: codex: command not found"))
        // …and a chatty login shell around real markers still parses.
        val noisy = "Welcome to Ubuntu\n" + probe("0.153.4", 1, 1, 1) + "\nlast login: today"
        assertTrue(CodexDaemon.parseProbe(noisy)!!.sharedBrain)
    }

    @Test
    fun `the daemon is only up when it says so`() {
        assertTrue(CodexDaemon.parseStart("CONCH_CODEXD_UP:1"))
        assertFalse(CodexDaemon.parseStart("CONCH_CODEXD_UP:0"))
        assertFalse(CodexDaemon.parseStart(null))
        assertFalse(CodexDaemon.parseStart("error: unrecognized subcommand 'daemon'"))
    }

    @Test
    fun `the scripts are POSIX sh and carry no user data`() {
        val scripts = listOf(CodexDaemon.probeScript(), CodexDaemon.startScript())
        for (s in scripts) {
            // No bashisms — these ride the same `sh -lc` fallback SessionHolder
            // documents for servers without bash.
            assertFalse(s.contains("[["))
            assertFalse(s.contains("=~"))
            // Nothing is interpolated into them at all, so there is no
            // injection surface to guard: pin that they stay literal.
            assertFalse(s.contains("\$rid"))
            assertFalse(s.contains("\$tid"))
        }
    }

    @Test
    fun `the commands we put in front of the user stay English and exact`() {
        val texts = listOf(
            CodexDaemon.CHANNEL_COMMAND,
            CodexDaemon.TERMINAL_COMMAND,
            CodexDaemon.UPDATE_COMMAND,
        )
        for (t in texts) assertFalse(t.any { it.code in 0x0400..0x04FF })
        // `unix://` with no path = the default socket: nothing for him to look
        // up, and nothing for us to get wrong on his box.
        assertEquals("codex --remote unix://", CodexDaemon.TERMINAL_COMMAND)
        assertEquals("codex app-server proxy 2>/dev/null", CodexDaemon.CHANNEL_COMMAND)
    }

    /**
     * Dumps the GENERATED scripts so they can be run against a real shell —
     * the same discipline SessionHolder's probe was verified with (a hand-typed
     * copy proves the copy works, not the code).
     */
    @Test
    fun `dump the generated scripts for shell verification`() {
        val dir = java.io.File("build/tmp/conch-scripts").apply { mkdirs() }
        java.io.File(dir, "probe.sh").writeText(CodexDaemon.probeScript())
        java.io.File(dir, "start.sh").writeText(CodexDaemon.startScript())
        java.io.File(dir, "hook-install.sh").writeText(CodexDaemon.installHookScript())
        java.io.File(dir, "hook-remove.sh").writeText(CodexDaemon.removeHookScript())
        java.io.File(dir, "ws-ensure.sh").writeText(
            ai.eight24family.conch.agent.codex.CodexWsBrain.ensureScript()
        )
        java.io.File(dir, "ws-hook.sh").writeText(
            ai.eight24family.conch.agent.codex.CodexWsBrain.installHookScript(47100)
        )
        java.io.File(dir, "gemini-cwd.sh").writeText(
            ai.eight24family.conch.agent.gemini.GeminiSpec
                .cwdBackfillScript("a04a3b25-2d22-486a-8d8e-01c639531648").orEmpty()
        )
        assertTrue(java.io.File(dir, "hook-install.sh").length() > 0)
    }

    @Test
    fun `the hook never swallows the app's own codex calls`() {
        val sh = CodexDaemon.installHookScript()
        // All three gates must be in the emitted function: interactive stdout,
        // zero arguments, and a daemon that answers. Losing any one of them
        // turns `codex app-server proxy` into
        // `codex --remote unix:// app-server proxy` and breaks the channel.
        assertTrue(sh.contains("[ -t 1 ]"))
        assertTrue(sh.contains("-eq 0"))
        assertTrue(sh.contains("daemon version"))
        assertTrue(sh.contains("command codex"))
        // …and it is idempotent by marker, both ways.
        assertTrue(sh.contains(CodexDaemon.MARK_BEGIN))
        assertTrue(CodexDaemon.removeHookScript().contains("<<< conch <<<"))
    }

    @Test
    fun `hook outcomes are parsed, not guessed`() {
        assertEquals("added:/home/user/.bashrc",
            CodexDaemon.parseHook("CONCH_CODEXD_HOOK:added:/home/user/.bashrc"))
        assertEquals("present", CodexDaemon.parseHook("noise\nCONCH_CODEXD_HOOK:present"))
        assertEquals("unsupported:csh", CodexDaemon.parseHook("CONCH_CODEXD_HOOK:unsupported:csh"))
        assertNull(CodexDaemon.parseHook("bash: codex: command not found"))
        assertNull(CodexDaemon.parseHook(null))
    }
}
