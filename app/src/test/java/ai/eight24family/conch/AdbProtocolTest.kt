package ai.eight24family.conch

import ai.eight24family.conch.adb.AdbProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The ADB framing Conch speaks for itself, so it can hold the shell uid without
 * a second app on the phone.
 *
 * The command ids are asserted against their PUBLISHED values as well as
 * against the derivation that produces them. That is the point: the derivation
 * is what the code uses, the literals are the independent check on it, and if
 * they ever disagree the build says so instead of the phone hanging.
 */
class AdbProtocolTest {

    @Test
    fun `derived command ids match the published protocol constants`() {
        assertEquals(0x4e58_4e43, AdbProtocol.A_CNXN)
        assertEquals(0x4854_5541, AdbProtocol.A_AUTH)
        assertEquals(0x4e45_504f, AdbProtocol.A_OPEN)
        assertEquals(0x5941_4b4f, AdbProtocol.A_OKAY)
        assertEquals(0x4553_4c43, AdbProtocol.A_CLSE)
        assertEquals(0x4554_5257, AdbProtocol.A_WRTE)
        assertEquals(0x534c_5453, AdbProtocol.A_STLS)
        assertEquals(0x434e_5953, AdbProtocol.commandOf("SYNC"))
    }

    @Test
    fun `a command id reads back as its own name`() {
        assertEquals("CNXN", AdbProtocol.Message(AdbProtocol.A_CNXN, 0, 0).name())
        assertEquals("WRTE", AdbProtocol.Message(AdbProtocol.A_WRTE, 1, 2).name())
    }

    @Test
    fun `a message survives a round trip through the wire format`() {
        val sent = AdbProtocol.stringMessage(AdbProtocol.A_OPEN, 7, 0, "shell,v2,raw:id")
        val pipe = ByteArrayOutputStream()
        AdbProtocol.write(pipe, sent)
        val back = AdbProtocol.read(ByteArrayInputStream(pipe.toByteArray()))
        assertEquals(sent, back)
        assertEquals("shell,v2,raw:id\u0000", String(back!!.payload, Charsets.UTF_8))
    }

    @Test
    fun `the header is exactly 24 bytes and carries the inverted magic`() {
        val m = AdbProtocol.Message(AdbProtocol.A_OKAY, 3, 4)
        val bytes = m.encode()
        assertEquals(24, bytes.size)
        // last four bytes, little-endian, are command xor -1
        val magic = (bytes[20].toInt() and 0xFF) or ((bytes[21].toInt() and 0xFF) shl 8) or
            ((bytes[22].toInt() and 0xFF) shl 16) or ((bytes[23].toInt() and 0xFF) shl 24)
        assertEquals(AdbProtocol.A_OKAY xor -1, magic)
    }

    @Test
    fun `the payload check sums bytes UNSIGNED`() {
        // The trap: Kotlin's Byte is signed, so 0xFF would contribute -1 and every
        // payload carrying non-ASCII (i.e. any real command output) would be
        // rejected by adbd for a checksum it computed as 255.
        assertEquals(0xFF * 3, AdbProtocol.checksum(byteArrayOf(-1, -1, -1)))
        assertEquals(0, AdbProtocol.checksum(ByteArray(0)))
        assertEquals('h'.code + 'i'.code, AdbProtocol.checksum("hi".toByteArray()))
    }

    @Test
    fun `a payload of high bytes round-trips`() {
        val payload = ByteArray(256) { (it and 0xFF).toByte() }
        val pipe = ByteArrayOutputStream()
        AdbProtocol.write(pipe, AdbProtocol.Message(AdbProtocol.A_WRTE, 1, 1, payload))
        val back = AdbProtocol.read(ByteArrayInputStream(pipe.toByteArray()))!!
        assertArrayEquals(payload, back.payload)
    }

    @Test
    fun `a clean end of stream between messages is not an error`() {
        assertNull(AdbProtocol.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a stream that ends mid-message is an error, never a short read`() {
        val full = AdbProtocol.stringMessage(AdbProtocol.A_WRTE, 1, 1, "half a payload").encode()
        val truncated = full.copyOf(full.size - 5)
        assertThrows(Exception::class.java) { AdbProtocol.read(ByteArrayInputStream(truncated)) }
    }

    @Test
    fun `a header failing its own magic check is refused`() {
        val bytes = AdbProtocol.Message(AdbProtocol.A_OKAY, 0, 0).encode()
        bytes[20] = (bytes[20] + 1).toByte()
        assertThrows(IllegalStateException::class.java) { AdbProtocol.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `an absurd payload length is refused before anything is allocated`() {
        val m = AdbProtocol.Message(AdbProtocol.A_WRTE, 0, 0)
        val bytes = m.encode()
        // Rewrite data_length to 2 GiB; a peer that lies must not be able to make
        // us allocate on its word.
        bytes[12] = 0; bytes[13] = 0; bytes[14] = 0; bytes[15] = 0x40
        assertThrows(IllegalStateException::class.java) { AdbProtocol.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `a wrong check field does NOT reject the message`() {
        // The field is vestigial: modern adbd sends zero there and ignores what
        // it receives. Verifying it rejected every message a real device sent
        // — the first connection after pairing failed on adbd's opening reply,
        // in a loop (owner's phone, 2026-08-29). This test exists so nobody
        // "fixes" the reader back into that state.
        val bytes = AdbProtocol.stringMessage(AdbProtocol.A_WRTE, 0, 0, "abc").encode()
        bytes[16] = (bytes[16] + 1).toByte() // corrupt the check field itself
        val back = AdbProtocol.read(ByteArrayInputStream(bytes))!!
        assertEquals("abc" + Char(0), String(back.payload, Charsets.UTF_8))
    }

    @Test
    fun `we still WRITE a correct check, in case an older peer verifies it`() {
        val bytes = AdbProtocol.stringMessage(AdbProtocol.A_WRTE, 0, 0, "abc").encode()
        val written = (bytes[16].toInt() and 0xFF) or ((bytes[17].toInt() and 0xFF) shl 8) or
            ((bytes[18].toInt() and 0xFF) shl 16) or ((bytes[19].toInt() and 0xFF) shl 24)
        assertEquals(AdbProtocol.checksum(("abc" + Char(0)).toByteArray()), written)
    }

    @Test
    fun `a command name must be four characters`() {
        assertThrows(IllegalArgumentException::class.java) { AdbProtocol.commandOf("CNX") }
        assertThrows(IllegalArgumentException::class.java) { AdbProtocol.commandOf("CONNECT") }
    }
}
