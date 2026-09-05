package ai.eight24family.conch

import ai.eight24family.conch.agent.RemoteEnv
import ai.eight24family.conch.agent.UsageProbe
import ai.eight24family.conch.agent.claude.ClaudeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Written next to the real session, that copy was a second <uuid>.jsonl with
 * the same title for the 15-50 s the probe took, the 30 s listing sweep
 * caught it, and the list showed a duplicate whose file was gone by the time
 * anyone tapped it — an EMPTY copy. The copy now lives in a project
 * directory of its own that the listing skips by name, and is removed on
 * every exit path.
 */
class ContextProbeIsolationTest {

    private val rid = "ff5e9136-0b6c-4b56-b6f9-5261c95d6c29"

    @Test
    fun `probe copy lives under its own project slug, never next to the real session`() {
        val s = UsageProbe.contextProbeScript(rid)
        assertTrue(s.contains("RID=\"$rid\""))
        // Its own cwd, and the project slug Claude derives from it.
        assertTrue(s.contains("pdir=\"\$HOME/${RemoteEnv.CTX_PROBE_DIR_REL}\""))
        assertTrue(s.contains("sed 's/[^A-Za-z0-9]/-/g'"))
        assertTrue(s.contains("ddir=\"\$HOME/.claude/projects/\$slug\""))
        assertTrue(s.contains("copy=\"\$ddir/\$newid.jsonl\""))
        // The old in-place copy — dirname of the real file — is gone.
        assertFalse(s.contains("dirname"))
        assertFalse(s.contains("\$dir/\$newid.jsonl"))
        // The CLI runs FROM the probe dir, so pre-2.1.223 lookups find the copy.
        val cd = s.indexOf("cd \"\$pdir\"")
        val run = s.indexOf("claude -p --resume \"\$newid\"")
        assertTrue(cd > 0 && run > cd)
    }

    @Test
    fun `probe copy is removed on every exit path, not only the happy one`() {
        val s = UsageProbe.contextProbeScript(rid)
        assertTrue(s.contains("trap 'rm -f \"\$copy\"' EXIT"))
        assertTrue(s.contains("trap 'rm -f \"\$copy\"; exit 1' HUP INT TERM"))
        // Traps are armed BEFORE the copy exists.
        assertTrue(s.indexOf("trap 'rm -f") < s.indexOf("cp \"\$real\" \"\$copy\""))
    }

    @Test
    fun `session listing skips the probe directory in both of its passes`() {
        val script = ClaudeSpec.listSessionsScript.orEmpty()
        val guard = "case \"\$f\" in *${RemoteEnv.CTX_PROBE_SLUG_MARK}/*) continue;; esac"
        assertEquals(2, Regex(Regex.escape(guard)).findAll(script).count())
        // Each guard is the first real statement of its loop after the
        // file-exists check — nothing reads or rewrites the file before it.
        val loopHead = "for f in ~/.claude/projects/*/*.jsonl; do"
        for (m in Regex(Regex.escape(guard)).findAll(script)) {
            val start = script.lastIndexOf(loopHead, m.range.first)
            assertTrue(start >= 0)
            val statements = script.substring(start, m.range.first)
                .lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            assertEquals(listOf(loopHead, "[ -f \"\$f\" ] || continue"), statements)
        }
    }

    @Test
    fun `slug marker is what Claude derives from the probe cwd`() {
        // Claude's rule: every non-alphanumeric character of the cwd → '-'.
        val slugOf = { path: String -> path.replace(Regex("[^A-Za-z0-9]"), "-") }
        assertEquals(RemoteEnv.CTX_PROBE_SLUG_MARK, slugOf("/" + RemoteEnv.CTX_PROBE_DIR_REL))
        assertTrue(slugOf("/home/user/.conch/ctx-probe").endsWith(RemoteEnv.CTX_PROBE_SLUG_MARK))
        assertTrue(slugOf("/home/user/.conch/ctx-probe").endsWith(RemoteEnv.CTX_PROBE_SLUG_MARK))
        // And a real project dir never matches it.
        assertFalse(slugOf("/home/user").endsWith(RemoteEnv.CTX_PROBE_SLUG_MARK))
        assertFalse(slugOf("/home/user/reach").endsWith(RemoteEnv.CTX_PROBE_SLUG_MARK))
    }
}
