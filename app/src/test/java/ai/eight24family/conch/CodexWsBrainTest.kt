package ai.eight24family.conch

import ai.eight24family.conch.agent.codex.CodexWsBrain
import ai.eight24family.conch.agent.codex.CodexWsFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The shared brain: what the server is asked to do, and how the frames that
 * carry the protocol are built. Everything here is pinned because the failure
 * modes are silent — a mis-framed WebSocket does not error, it delivers
 * garbage that looks like a protocol bug for weeks.
 */
class CodexWsBrainTest {

    @Test
    fun `an already-running brain is reused, not restarted`() {
        val b = CodexWsBrain.parseEnsure("CONCH_CODEXWS_PORT:47100\nCONCH_CODEXWS_STARTED:0")!!
        assertEquals(47100, b.port)
        assertFalse("reusing must not read as a fresh start", b.startedNow)
    }

    @Test
    fun `a fresh start reports the port it settled on`() {
        val b = CodexWsBrain.parseEnsure("CONCH_CODEXWS_PORT:47103\nCONCH_CODEXWS_STARTED:1")!!
        assertEquals(47103, b.port)
        assertTrue(b.startedNow)
    }

    @Test
    fun `every failure is null, never a guessed port`() {
        // An old codex (his own /usr/bin is 0.80, which has no --listen), no
        // free port, a start that never bound: all of them must degrade to the
        // private app-server rather than to a connection attempt that hangs.
        assertNull(CodexWsBrain.parseEnsure("CONCH_CODEXWS_ERR:nolisten"))
        assertNull(CodexWsBrain.parseEnsure("CONCH_CODEXWS_ERR:nocodex"))
        assertNull(CodexWsBrain.parseEnsure("CONCH_CODEXWS_ERR:noport"))
        assertNull(CodexWsBrain.parseEnsure("CONCH_CODEXWS_ERR:nostart"))
        assertNull(CodexWsBrain.parseEnsure(null))
        assertNull(CodexWsBrain.parseEnsure("bash: codex: command not found"))
    }

    @Test
    fun `the ensure script asks the binary and binds loopback only`() {
        val sh = CodexWsBrain.ensureScript()
        assertTrue("must prove --listen exists", sh.contains("grep -q -- '--listen'"))
        assertTrue("must bind loopback", sh.contains("ws://127.0.0.1:"))
        assertFalse("must never bind a routable address", sh.contains("ws://0.0.0.0"))
        assertTrue("must outlive the ssh channel", sh.contains("setsid nohup"))
        // POSIX sh: the probe rides the same `sh -lc` fallback as SessionHolder.
        assertFalse(sh.contains("[["))
        assertFalse(sh.contains("=~"))
    }

    @Test
    fun `the hook joins the brain and never swallows the app's own calls`() {
        val sh = CodexWsBrain.installHookScript(47100)
        assertTrue(sh.contains("--remote ws://127.0.0.1:47100"))
        // The three gates: a terminal, no arguments, and a brain that answers.
        assertTrue(sh.contains("[ -t 1 ]"))
        assertTrue(sh.contains("-eq 0"))
        assertTrue(sh.contains("/dev/tcp/127.0.0.1/47100"))
        assertTrue(sh.contains("command codex"))
        // Replaces its own older block instead of stacking a stale port.
        assertTrue(sh.contains("sed -i.conch-bak"))
        assertEquals("codex --remote ws://127.0.0.1:47100", CodexWsBrain.terminalCommand(47100))
    }

    @Test
    fun `client frames are masked, which the server requires`() {
        val out = ByteArrayOutputStream()
        CodexWsFrames.writeText(out, "hi")
        val b = out.toByteArray()
        assertEquals("FIN + text opcode", 0x81.toByte(), b[0])
        assertTrue("mask bit must be set on every client frame", (b[1].toInt() and 0x80) != 0)
        assertEquals("payload length", 2, b[1].toInt() and 0x7F)
        // 2 header + 4 mask + 2 payload
        assertEquals(8, b.size)
        val mask = b.copyOfRange(2, 6)
        val payload = b.copyOfRange(6, 8)
        val decoded = ByteArray(2) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        assertEquals("hi", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `a 200-byte payload uses the 16-bit length form`() {
        val out = ByteArrayOutputStream()
        CodexWsFrames.writeText(out, "x".repeat(200))
        val b = out.toByteArray()
        assertEquals(126, b[1].toInt() and 0x7F)
        assertEquals(200, ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF))
    }
}
