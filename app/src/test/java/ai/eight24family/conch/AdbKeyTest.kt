package ai.eight24family.conch

import ai.eight24family.conch.adb.AdbKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The identity Conch hands a phone when pairing, and presents again when it
 * connects.
 *
 * The public key does not travel in any ordinary encoding: ADB has always used
 * a fixed C struct carrying the modulus, two Montgomery constants and the
 * exponent, all as little-endian 32-bit words. The constants are computed from
 * the modulus, so a mistake in them does not throw — the device simply stores a
 * key that will never match, and every later connection is refused with nothing
 * to read. These tests recompute them independently from the parsed struct.
 *
 * Key generation is slow, so one key serves the whole class.
 */
class AdbKeyTest {

    private companion object {
        val key: AdbKey = AdbKey.generate("tester@conch")
        val blob: ByteArray = key.publicKeyBlob()
    }

    /** The struct, parsed back out of the base64 half of the blob. */
    private class Parsed(bytes: ByteArray) {
        val words: Int
        val n0inv: Long
        val n: BigInteger
        val rr: BigInteger
        val exponent: Long

        init {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            words = buf.int
            n0inv = buf.int.toLong() and 0xFFFFFFFFL
            n = readNumber(buf, words)
            rr = readNumber(buf, words)
            exponent = buf.int.toLong() and 0xFFFFFFFFL
        }

        private fun readNumber(buf: ByteBuffer, words: Int): BigInteger {
            var value = BigInteger.ZERO
            // Least significant word first.
            for (i in 0 until words) {
                val w = BigInteger.valueOf(buf.int.toLong() and 0xFFFFFFFFL)
                value = value.add(w.shiftLeft(32 * i))
            }
            return value
        }
    }

    private fun parsed(): Parsed {
        val text = String(blob, Charsets.US_ASCII)
        val base64 = text.substringBefore(' ')
        return Parsed(Base64.getDecoder().decode(base64))
    }

    @Test
    fun `the blob is base64 followed by a space and a name`() {
        val text = String(blob, Charsets.US_ASCII)
        assertTrue("expected a space-separated name, got: ${text.take(40)}…", text.contains(' '))
        assertEquals("tester@conch", text.substringAfter(' '))
        // 2048-bit key: 2 header words + 64 + 64 + 1 exponent = 131 words.
        val decoded = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(131 * 4, decoded.size)
    }

    @Test
    fun `the struct carries this key's real modulus and exponent`() {
        val p = parsed()
        val pub = key.keyPair.public as java.security.interfaces.RSAPublicKey
        assertEquals(64, p.words)
        assertEquals(pub.modulus, p.n)
        assertEquals(pub.publicExponent.toLong(), p.exponent)
    }

    @Test
    fun `n0inv really is the negative inverse of the low word`() {
        // The verifier's Montgomery multiplication needs n0inv such that
        // n * n0inv = -1 (mod 2^32). Recomputed here from the modulus that came
        // back out of the struct, so a wrong sign or a missing negation shows up
        // as an equation that does not hold.
        val p = parsed()
        val r32 = BigInteger.ONE.shiftLeft(32)
        val product = p.n.multiply(BigInteger.valueOf(p.n0inv)).mod(r32)
        assertEquals(r32.subtract(BigInteger.ONE), product)
    }

    @Test
    fun `rr really is R squared mod n`() {
        val p = parsed()
        val expected = BigInteger.ONE.shiftLeft(2048 * 2).mod(p.n)
        assertEquals(expected, p.rr)
        assertTrue("rr must be reduced below the modulus", p.rr < p.n)
    }

    @Test
    fun `the certificate carries the same key the blob describes`() {
        // The whole point: the device stores the blob at pairing and matches it
        // against the certificate at connect. Two different keys pair happily
        // and then refuse every connection.
        val p = parsed()
        val fromCert = org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(
            key.certificate.subjectPublicKeyInfo.parsePublicKey(),
        )
        assertEquals(p.n, fromCert.modulus)
        assertEquals(p.exponent, fromCert.publicExponent.toLong())
    }

    @Test
    fun `the certificate is self-signed and valid now`() {
        val cert = key.certificate
        assertEquals(cert.issuer, cert.subject)
        val now = java.util.Date()
        assertTrue(cert.startDate.date.before(now))
        assertTrue(cert.endDate.date.after(now))
    }

    @Test
    fun `two keys are not the same key`() {
        val other = AdbKey.generate("tester@conch")
        assertNotEquals(
            String(blob, Charsets.US_ASCII),
            String(other.publicKeyBlob(), Charsets.US_ASCII),
        )
        // Same subject, different serial — the serial is random, not a clock, so
        // two certificates minted in one millisecond still differ.
        assertNotEquals(key.certificate.serialNumber, other.certificate.serialNumber)
    }

    @Test
    fun `a stored private key rebuilds the identical identity`() {
        // Pairing is once and forever, so the key has to survive a restart: the
        // blob rebuilt from the private half must be byte-identical, or the
        // device stops recognising us.
        val restored = AdbKey.fromPrivateKey(
            key.keyPair.private as java.security.interfaces.RSAPrivateKey,
            "tester@conch",
        )
        assertEquals(
            String(blob, Charsets.US_ASCII),
            String(restored.publicKeyBlob(), Charsets.US_ASCII),
        )
    }

    @Test
    fun `the blob fits the record the protocol sends it in`() {
        // It travels inside a fixed 8192-byte PeerInfo; a 2048-bit key is about
        // 720 bytes of base64, but the check belongs here rather than in a
        // comment.
        assertTrue("blob is ${blob.size}B", blob.size <= 8191)
    }
}
