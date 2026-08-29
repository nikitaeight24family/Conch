package ai.eight24family.conch

import ai.eight24family.conch.adb.AdbPairing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The pairing exchange's framing, key derivation and cipher.
 *
 * The constants here are the ones that decide whether a client pairs or hangs,
 * and every one of them is a place where the reference implementation's C idiom
 * silently chooses the bytes — `sizeof` on a string literal includes its NUL,
 * except where the same file subtracts one. Those are asserted as literal
 * lengths, because the only other way to find a mistake is a phone that refuses
 * to pair with no message.
 */
class AdbPairingTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    // ── the constants that fail silently ─────────────────────────────────────

    @Test
    fun `the SPAKE2 identities carry their terminator`() {
        assertEquals(16, AdbPairing.CLIENT_NAME.size)
        assertEquals(16, AdbPairing.SERVER_NAME.size)
        assertEquals(0, AdbPairing.CLIENT_NAME.last().toInt())
        assertEquals(0, AdbPairing.SERVER_NAME.last().toInt())
        assertEquals("adb pair client", String(AdbPairing.CLIENT_NAME, 0, 15))
        assertEquals("adb pair server", String(AdbPairing.SERVER_NAME, 0, 15))
    }

    @Test
    fun `the TLS export label carries its terminator too`() {
        assertEquals(10, AdbPairing.TLS_EXPORT_LABEL.size)
        assertEquals(0, AdbPairing.TLS_EXPORT_LABEL.last().toInt())
    }

    @Test
    fun `the AES key info does NOT carry a terminator`() {
        // The same source uses sizeof for the names and sizeof-1 here. Both
        // conventions have to be reproduced exactly.
        assertEquals(32, AdbPairing.AES_KEY_INFO.size)
        assertNotEquals(0, AdbPairing.AES_KEY_INFO.last().toInt())
        assertEquals("adb pairing_auth aes-128-gcm key", String(AdbPairing.AES_KEY_INFO))
    }

    @Test
    fun `the pairing code is bound to the channel it was typed on`() {
        val code = "642099".toByteArray()
        val exported = ByteArray(64) { it.toByte() }
        val password = AdbPairing.passwordWithChannelBinding(code, exported)
        assertEquals(6 + 64, password.size)
        assertArrayEquals(code, password.copyOfRange(0, 6))
        assertArrayEquals(exported, password.copyOfRange(6, 70))
        // A binding of the wrong size means we exported from the wrong place.
        assertThrows(IllegalArgumentException::class.java) {
            AdbPairing.passwordWithChannelBinding(code, ByteArray(32))
        }
    }

    // ── framing ──────────────────────────────────────────────────────────────

    @Test
    fun `a packet round-trips with a big-endian length`() {
        val payload = ByteArray(300) { 7 }
        val pipe = ByteArrayOutputStream()
        AdbPairing.writePacket(pipe, AdbPairing.Packet(AdbPairing.TYPE_SPAKE2_MSG, payload))
        val bytes = pipe.toByteArray()
        assertEquals(6 + 300, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        // 300 = 0x0000012C, most significant byte first.
        assertArrayEquals(hex("0000012c"), bytes.copyOfRange(2, 6))
        val back = AdbPairing.readPacket(ByteArrayInputStream(bytes))
        assertEquals(AdbPairing.TYPE_SPAKE2_MSG, back.type)
        assertArrayEquals(payload, back.payload)
    }

    @Test
    fun `an empty payload is refused — the device rejects one`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbPairing.writePacket(ByteArrayOutputStream(), AdbPairing.Packet(AdbPairing.TYPE_PEER_INFO, ByteArray(0)))
        }
        val zeroSized = hex("010100000000")
        assertThrows(IllegalStateException::class.java) {
            AdbPairing.readPacket(ByteArrayInputStream(zeroSized))
        }
    }

    @Test
    fun `a payload larger than the peer allows is refused before allocating`() {
        val tooBig = hex("0100") + hex("7fffffff")
        assertThrows(IllegalStateException::class.java) {
            AdbPairing.readPacket(ByteArrayInputStream(tooBig))
        }
    }

    @Test
    fun `an unknown packet type is refused`() {
        val unknown = hex("0109") + hex("00000004") + hex("deadbeef")
        assertThrows(IllegalStateException::class.java) {
            AdbPairing.readPacket(ByteArrayInputStream(unknown))
        }
    }

    @Test
    fun `an older packet version is refused`() {
        val old = hex("0000") + hex("00000004") + hex("deadbeef")
        assertThrows(IllegalStateException::class.java) {
            AdbPairing.readPacket(ByteArrayInputStream(old))
        }
    }

    @Test
    fun `a truncated packet raises instead of returning a short payload`() {
        val truncated = hex("0100") + hex("00000010") + hex("0011")
        assertThrows(Exception::class.java) { AdbPairing.readPacket(ByteArrayInputStream(truncated)) }
    }

    // ── peer info ────────────────────────────────────────────────────────────

    @Test
    fun `a peer info record is a fixed 8192 bytes whatever it carries`() {
        val key = "QAAAA... user@host".toByteArray()
        val record = AdbPairing.encodePeerInfo(AdbPairing.PEER_INFO_RSA_PUBLIC_KEY, key)
        assertEquals(8192, record.size)
        assertEquals(0, record[0].toInt())
        val (type, data) = AdbPairing.decodePeerInfo(record)
        assertEquals(AdbPairing.PEER_INFO_RSA_PUBLIC_KEY, type)
        assertArrayEquals(key, data)
    }

    @Test
    fun `something too large for the record is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbPairing.encodePeerInfo(0, ByteArray(8192))
        }
    }

    // ── key derivation ───────────────────────────────────────────────────────

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val okm = AdbPairing.hkdfSha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `HKDF matches RFC 5869 test case 3 — no salt, no info`() {
        // The case that matches our own usage: the pairing key is derived with a
        // null salt, which HKDF defines as a block of zeros, not as skipping the
        // extract step.
        val okm = AdbPairing.hkdfSha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = null,
            info = ByteArray(0),
            length = 42,
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    // ── the cipher ───────────────────────────────────────────────────────────

    @Test
    fun `a message survives the round trip`() {
        val a = AdbPairing.Cipher(ByteArray(64) { it.toByte() })
        val b = AdbPairing.Cipher(ByteArray(64) { it.toByte() })
        val plain = "hello, phone".toByteArray()
        assertArrayEquals(plain, b.decrypt(a.encrypt(plain)))
    }

    @Test
    fun `the nonce advances, so the same plaintext never encrypts the same way`() {
        val c = AdbPairing.Cipher(ByteArray(64) { 3 })
        val plain = ByteArray(16) { 1 }
        assertFalse(c.encrypt(plain).contentEquals(c.encrypt(plain)))
    }

    @Test
    fun `the two sides must stay in step`() {
        val a = AdbPairing.Cipher(ByteArray(64) { 5 })
        val b = AdbPairing.Cipher(ByteArray(64) { 5 })
        val first = a.encrypt("one".toByteArray())
        val second = a.encrypt("two".toByteArray())
        // Decrypting out of order fails the tag: the counter is the nonce.
        assertThrows(Exception::class.java) { b.decrypt(second) }
        assertArrayEquals("one".toByteArray(), AdbPairing.Cipher(ByteArray(64) { 5 }).decrypt(first))
    }

    @Test
    fun `a tampered message does not decrypt`() {
        val a = AdbPairing.Cipher(ByteArray(64) { 9 })
        val b = AdbPairing.Cipher(ByteArray(64) { 9 })
        val sealed = a.encrypt("intact".toByteArray())
        sealed[0] = (sealed[0] + 1).toByte()
        assertThrows(Exception::class.java) { b.decrypt(sealed) }
    }

    @Test
    fun `a different exchange key cannot read the message`() {
        val a = AdbPairing.Cipher(ByteArray(64) { 1 })
        val other = AdbPairing.Cipher(ByteArray(64) { 2 })
        assertThrows(Exception::class.java) { other.decrypt(a.encrypt("secret".toByteArray())) }
    }

    @Test
    fun `the tag adds exactly sixteen bytes`() {
        val c = AdbPairing.Cipher(ByteArray(64) { 4 })
        assertEquals(8192 + 16, c.encrypt(ByteArray(8192)).size)
    }
}
