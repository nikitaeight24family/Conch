package ai.eight24family.conch

import ai.eight24family.conch.adb.AdbConnection
import ai.eight24family.conch.adb.AdbProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The handshake and stream multiplexing Conch performs against `adbd`, driven
 * here by a scripted peer so every branch can be exercised without a phone.
 *
 * These are the paths that decide whether the shell uid is reachable without a
 * second app installed on the device, so each failure mode is pinned as
 * deliberately as each success: a refused service must not read as empty
 * output, and an unexpected message must not be silently swallowed.
 */
class AdbConnectionTest {

    /** Frames a script of messages as the bytes a device would send. */
    private fun peer(vararg messages: AdbProtocol.Message): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        messages.forEach { out.write(it.encode()) }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun cnxn(banner: String) =
        AdbProtocol.stringMessage(AdbProtocol.A_CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, banner)

    @Test
    fun `a device that answers CNXN is connected, and its banner is kept`() {
        val sent = ByteArrayOutputStream()
        val conn = AdbConnection(peer(cnxn("device::ro.product.name=CPH2671")), sent)
        assertEquals(AdbConnection.Greeting.CONNECTED, conn.greet())
        assertEquals("device::ro.product.name=CPH2671", conn.deviceBanner)
    }

    @Test
    fun `our opening CNXN announces the TLS-capable version and our payload cap`() {
        val sent = ByteArrayOutputStream()
        AdbConnection(peer(cnxn("device::")), sent).greet()
        val ours = AdbProtocol.read(ByteArrayInputStream(sent.toByteArray()))!!
        assertEquals(AdbProtocol.A_CNXN, ours.command)
        assertEquals(AdbProtocol.VERSION, ours.arg0)
        assertEquals(AdbProtocol.MAX_PAYLOAD, ours.arg1)
    }

    @Test
    fun `a device asking for TLS is reported as such, not treated as connected`() {
        val sent = ByteArrayOutputStream()
        val conn = AdbConnection(peer(AdbProtocol.Message(AdbProtocol.A_STLS, AdbProtocol.STLS_VERSION, 0)), sent)
        assertEquals(AdbConnection.Greeting.WANTS_TLS, conn.greet())
        // …and answering it puts an STLS of our own on the wire, which is what
        // makes the peer start its handshake.
        conn.acceptTls()
        val msgs = readAll(sent.toByteArray())
        assertEquals(AdbProtocol.A_CNXN, msgs[0].command)
        assertEquals(AdbProtocol.A_STLS, msgs[1].command)
        assertEquals(AdbProtocol.STLS_VERSION, msgs[1].arg0)
    }

    @Test
    fun `the legacy RSA path is named rather than hung on`() {
        val conn = AdbConnection(
            peer(AdbProtocol.Message(AdbProtocol.A_AUTH, 1, 0, ByteArray(20))),
            ByteArrayOutputStream(),
        )
        assertEquals(AdbConnection.Greeting.WANTS_RSA_AUTH, conn.greet())
    }

    @Test
    fun `a device that hangs up without answering is an error`() {
        val conn = AdbConnection(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertThrows(IllegalStateException::class.java) { conn.greet() }
    }

    @Test
    fun `opening a service reads its whole output and acknowledges every chunk`() {
        val sent = ByteArrayOutputStream()
        val conn = AdbConnection(
            peer(
                cnxn("device::"),
                AdbProtocol.Message(AdbProtocol.A_OKAY, 99, 1),
                AdbProtocol.Message(AdbProtocol.A_WRTE, 99, 1, "uid=2000".toByteArray()),
                AdbProtocol.Message(AdbProtocol.A_WRTE, 99, 1, "(shell)".toByteArray()),
                AdbProtocol.Message(AdbProtocol.A_CLSE, 99, 1),
            ),
            sent,
        )
        conn.greet()
        val stream = conn.open("shell,v2,raw:id")
        assertEquals("uid=2000(shell)", String(stream.readAll(), Charsets.UTF_8))

        val msgs = readAll(sent.toByteArray())
        // CNXN, OPEN, then one OKAY per WRTE — without those the device stops
        // sending after the first chunk and the read hangs forever — and finally
        // a CLSE answering the device's own.
        //
        // ⛔ THAT LAST ONE IS NOT COSMETIC, which is why this expectation
        // changed. Leaving the close unanswered let the daemon trail its `CLSE`
        // into whatever was opened NEXT, and the following command died on
        // "unexpected CLSE while opening 'shell,v2,raw:…'" — every second
        // command on a working phone, measured 2026-09-03 mid-way through
        // starting the phone's Linux.
        assertEquals(AdbProtocol.A_OPEN, msgs[1].command)
        assertEquals("shell,v2,raw:id" + Char(0), String(msgs[1].payload, Charsets.UTF_8))
        assertEquals(
            listOf(AdbProtocol.A_OKAY, AdbProtocol.A_OKAY, AdbProtocol.A_CLSE),
            msgs.drop(2).map { it.command },
        )
    }

    @Test
    fun `a refused service raises instead of returning nothing`() {
        val conn = AdbConnection(
            peer(cnxn("device::"), AdbProtocol.Message(AdbProtocol.A_CLSE, 0, 1)),
            ByteArrayOutputStream(),
        )
        conn.greet()
        // "no such service" and "printed nothing" must never look alike.
        assertThrows(IllegalStateException::class.java) { conn.open("jdwp:1") }
    }

    @Test
    fun `stream ids are not reused`() {
        val sent = ByteArrayOutputStream()
        val conn = AdbConnection(
            peer(
                cnxn("device::"),
                AdbProtocol.Message(AdbProtocol.A_OKAY, 90, 1),
                AdbProtocol.Message(AdbProtocol.A_OKAY, 91, 2),
            ),
            sent,
        )
        conn.greet()
        conn.open("shell:id")
        conn.open("shell:whoami")
        val opens = readAll(sent.toByteArray()).filter { it.command == AdbProtocol.A_OPEN }
        assertEquals(listOf(1, 2), opens.map { it.arg0 })
    }

    @Test
    fun `output past the limit is truncated, not grown without bound`() {
        val chunk = ByteArray(64) { 'x'.code.toByte() }
        val conn = AdbConnection(
            peer(
                cnxn("device::"),
                AdbProtocol.Message(AdbProtocol.A_OKAY, 99, 1),
                AdbProtocol.Message(AdbProtocol.A_WRTE, 99, 1, chunk),
                AdbProtocol.Message(AdbProtocol.A_WRTE, 99, 1, chunk),
                AdbProtocol.Message(AdbProtocol.A_CLSE, 99, 1),
            ),
            ByteArrayOutputStream(),
        )
        conn.greet()
        assertEquals(100, conn.open("shell:logcat").readAll(limit = 100).size)
    }

    private fun readAll(bytes: ByteArray): List<AdbProtocol.Message> {
        val input = ByteArrayInputStream(bytes)
        val out = ArrayList<AdbProtocol.Message>()
        while (true) out.add(AdbProtocol.read(input) ?: return out)
    }
}
