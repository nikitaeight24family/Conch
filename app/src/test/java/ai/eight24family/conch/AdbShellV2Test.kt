package ai.eight24family.conch

import ai.eight24family.conch.adb.AdbShellV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splitting a `shell,v2` stream back into stdout, stderr and an exit code.
 *
 * The bridge has always handed the agent clean stdout with the exit status and
 * stderr alongside it, so this framing is what keeps that contract when the
 * shell comes from our own ADB client.
 */
class AdbShellV2Test {

    private fun stream(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun out(text: String) = AdbShellV2.packet(AdbShellV2.ID_STDOUT, text.toByteArray())
    private fun err(text: String) = AdbShellV2.packet(AdbShellV2.ID_STDERR, text.toByteArray())
    private fun exit(code: Int) = AdbShellV2.packet(AdbShellV2.ID_EXIT, byteArrayOf(code.toByte()))

    @Test
    fun `the three channels come apart`() {
        val r = AdbShellV2.demux(stream(out("uid=2000"), err("warning"), out("(shell)"), exit(0)))
        assertEquals("uid=2000(shell)", r.stdout)
        assertEquals("warning", r.stderr)
        assertEquals(0, r.exitCode)
        assertFalse(r.truncated)
    }

    @Test
    fun `a failing command keeps its exit code`() {
        val r = AdbShellV2.demux(stream(err("No such file or directory"), exit(127)))
        assertEquals(127, r.exitCode)
        assertEquals("", r.stdout)
        assertTrue(r.stderr.isNotEmpty())
    }

    @Test
    fun `no exit packet reads as unknown, not as success`() {
        // A stream cut short must never report 0 — the agent would take a killed
        // command for a successful one.
        assertEquals(-1, AdbShellV2.demux(stream(out("partial"))).exitCode)
    }

    @Test
    fun `the length is little-endian and spans four bytes`() {
        val big = "x".repeat(300)
        val packet = AdbShellV2.packet(AdbShellV2.ID_STDOUT, big.toByteArray())
        // 300 = 0x12C: low byte first.
        assertEquals(0x2C, packet[1].toInt() and 0xFF)
        assertEquals(0x01, packet[2].toInt() and 0xFF)
        assertEquals(0, packet[3].toInt())
        assertEquals(0, packet[4].toInt())
        assertEquals(big, AdbShellV2.demux(packet).stdout)
    }

    @Test
    fun `a packet cut off at the end is dropped, not guessed`() {
        // Half a packet means the stream ended mid-frame. Emitting whatever bytes
        // arrived would put invented output in front of an agent.
        val whole = stream(out("kept"), out("lost"))
        val cut = whole.copyOf(whole.size - 2)
        assertEquals("kept", AdbShellV2.demux(cut).stdout)
    }

    @Test
    fun `output past the limit is marked truncated`() {
        val r = AdbShellV2.demux(stream(out("a".repeat(100)), exit(0)), limit = 10)
        assertEquals(10, r.stdout.length)
        assertTrue(r.truncated)
        // The exit code still lands: truncation must not lose the status.
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `an unknown packet id is skipped without derailing the rest`() {
        val odd = AdbShellV2.packet(99, "???".toByteArray())
        val r = AdbShellV2.demux(stream(out("before"), odd, out("after"), exit(0)))
        assertEquals("beforeafter", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `an empty stream is empty, not an error`() {
        val r = AdbShellV2.demux(ByteArray(0))
        assertEquals("", r.stdout)
        assertEquals(-1, r.exitCode)
    }
}
