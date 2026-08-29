package ai.eight24family.conch

import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Proof that the Ed25519 group arithmetic Conch will build ADB pairing on is
 * actually correct — before anything is built on it.
 *
 * The pairing step of Android's Wireless Debugging turns a six-digit code into a
 * shared secret with SPAKE2, which needs real elliptic-curve group operations:
 * decode a compressed point, multiply an arbitrary point by a scalar, add two
 * points. Conch takes those from a public-domain (CC0) library and writes the
 * protocol on top itself — so the library is exercised HERE, through the exact
 * operations that protocol will use, against the published RFC 8032 vectors.
 *
 * If any of these ever fail, nothing above them can be trusted: a wrong point
 * does not throw, it just produces a key the other side does not share, and the
 * pairing fails with no explanation.
 */
class Ed25519GroupMathTest {

    private val spec = EdDSANamedCurveTable.ED_25519_CURVE_SPEC

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** RFC 8032 §5.1.5: the secret scalar is SHA-512 of the seed, clamped. */
    private fun clampedScalar(seed: ByteArray): ByteArray {
        val h = MessageDigest.getInstance("SHA-512").digest(seed)
        val a = h.copyOf(32)
        a[0] = (a[0].toInt() and 0xF8).toByte()
        a[31] = ((a[31].toInt() and 0x7F) or 0x40).toByte()
        return a
    }

    @Test
    fun `base point times a known scalar gives the published public key`() {
        // RFC 8032, Test 1.
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expected = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val a = spec.b.scalarMultiply(clampedScalar(seed))
        assertEquals(toHex(expected), toHex(a.toByteArray()))
    }

    @Test
    fun `a second published vector agrees too`() {
        // RFC 8032, Test 2.
        val seed = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val expected = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        assertEquals(toHex(expected), toHex(spec.b.scalarMultiply(clampedScalar(seed)).toByteArray()))
    }

    @Test
    fun `a compressed point decodes and re-encodes to the same bytes`() {
        // Decoding is how the protocol's fixed mask points enter the computation;
        // a decoder that silently altered a point would poison every key derived
        // from it.
        val encoded = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val point = GroupElement(spec.curve, encoded)
        assertArrayEquals(encoded, point.toByteArray())
        assertTrue(point.toP3().isOnCurve(spec.curve))
    }

    @Test
    fun `addition and doubling agree, and negation cancels`() {
        val p = spec.b.scalarMultiply(clampedScalar(hex("00".repeat(32)))).toP3()
        // P + P must equal 2P — the two operations SPAKE2 mixes are consistent.
        assertArrayEquals(p.dbl().toP2().toByteArray(), p.add(p.toCached()).toP2().toByteArray())
        // P + (-P) is the identity, encoded as y = 1.
        val identity = p.add(p.negate().toCached()).toP2().toByteArray()
        val expectedIdentity = ByteArray(32).also { it[0] = 1 }
        assertArrayEquals(expectedIdentity, identity)
    }

    @Test
    fun `scalar multiplication distributes over scalar addition`() {
        // (a + b) * B == a*B + b*B. Not a vector from any document — an algebraic
        // identity the library must satisfy, which catches a broken reduction
        // that fixed vectors alone could miss.
        val a = ByteArray(32).also { it[0] = 5 }
        val b = ByteArray(32).also { it[0] = 9 }
        val sum = ByteArray(32).also { it[0] = 14 }
        val left = spec.b.scalarMultiply(sum).toByteArray()
        val right = spec.b.scalarMultiply(a).toP3()
            .add(spec.b.scalarMultiply(b).toP3().toCached()).toP2().toByteArray()
        assertArrayEquals(left, right)
    }

    @Test
    fun `the curve order reduces a scalar as the group requires`() {
        // L itself reduces to zero: the scalar arithmetic SPAKE2 needs is the
        // library's, and this is the cheapest proof that it is doing it mod L.
        val order = hex("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010")
        val reduced = spec.scalarOps.reduce(order + ByteArray(32))
        assertArrayEquals(ByteArray(32), reduced)
    }
}
