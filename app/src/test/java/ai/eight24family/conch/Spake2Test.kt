package ai.eight24family.conch

import ai.eight24family.conch.adb.Spake2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The password-authenticated exchange that lets Conch pair with the phone's own
 * Wireless Debugging — no computer, and no second app on the device.
 *
 * Two kinds of check live here, and they answer different questions. The
 * round-trips prove the halves agree with EACH OTHER; the scalar tests pin the
 * two pieces of arithmetic that are easy to get subtly wrong and impossible to
 * notice, because a wrong scalar does not throw — it just derives a key the
 * other side does not share, and pairing fails with no explanation at all.
 */
class Spake2Test {

    private val alice = "adb pair client".toByteArray()
    private val bob = "adb pair server".toByteArray()

    private fun exchange(
        passwordA: ByteArray,
        passwordB: ByteArray = passwordA,
    ): Pair<ByteArray, ByteArray> {
        val a = Spake2(Spake2.Role.ALICE, alice, bob)
        val b = Spake2(Spake2.Role.BOB, bob, alice)
        val msgA = a.generateMessage(passwordA, SecureRandom())
        val msgB = b.generateMessage(passwordB, SecureRandom())
        return a.processMessage(msgB) to b.processMessage(msgA)
    }

    @Test
    fun `both sides derive the same 64-byte key from the same code`() {
        val (keyA, keyB) = exchange("642099".toByteArray())
        assertEquals(64, keyA.size)
        assertArrayEquals(keyA, keyB)
    }

    @Test
    fun `a different code gives a different key on each side`() {
        val (keyA, keyB) = exchange("642099".toByteArray(), "642098".toByteArray())
        assertFalse(keyA.contentEquals(keyB))
    }

    @Test
    fun `two runs of the same code never repeat a key`() {
        // The ephemeral scalar is what makes each pairing unique; if it were
        // fixed, a recorded exchange would replay forever.
        val (first, _) = exchange("111111".toByteArray())
        val (second, _) = exchange("111111".toByteArray())
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `the message is exactly 32 bytes`() {
        val a = Spake2(Spake2.Role.ALICE, alice, bob)
        assertEquals(32, a.generateMessage("000000".toByteArray()).size)
    }

    @Test
    fun `the roles are not interchangeable`() {
        // Both sides playing Alice mask with the same point and must NOT agree —
        // this is the symmetry break the scheme depends on.
        val a = Spake2(Spake2.Role.ALICE, alice, bob)
        val b = Spake2(Spake2.Role.ALICE, bob, alice)
        val msgA = a.generateMessage("642099".toByteArray())
        val msgB = b.generateMessage("642099".toByteArray())
        assertFalse(a.processMessage(msgB).contentEquals(b.processMessage(msgA)))
    }

    @Test
    fun `a non-canonically encoded point is refused`() {
        // 32 bytes of 0xFF is a y ABOVE the field prime. Reduced, it lands on a
        // perfectly valid point — so the curve check alone waves it through, and
        // only the canonical-encoding check stops it. This case is what found
        // that the check was missing.
        val a = Spake2(Spake2.Role.ALICE, alice, bob)
        a.generateMessage("642099".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            a.processMessage(ByteArray(32) { 0xFF.toByte() })
        }
    }

    @Test
    fun `a canonical encoding that is not a curve point is refused`() {
        // Not every y has a matching x. Find one that has none and confirm it is
        // rejected for the OTHER reason, so both guards are shown to carry weight.
        var offCurve: ByteArray? = null
        for (candidate in 2..500) {
            val enc = ByteArray(32).also { it[0] = candidate.toByte() }
            val probe = Spake2(Spake2.Role.ALICE, alice, bob)
            probe.generateMessage("x".toByteArray())
            if (runCatching { probe.processMessage(enc) }.isFailure) {
                offCurve = enc
                break
            }
        }
        assertTrue("expected to find a y with no matching x", offCurve != null)
        val a = Spake2(Spake2.Role.ALICE, alice, bob)
        a.generateMessage("642099".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { a.processMessage(offCurve!!) }
    }

    @Test
    fun `the canonical check accepts a real point and rejects the prime itself`() {
        assertTrue(Spake2.isCanonical(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")))
        // y equal to the field prime is not below it.
        assertFalse(Spake2.isCanonical(hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")))
        assertFalse(Spake2.isCanonical(ByteArray(31)))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    @Test
    fun `a message of the wrong length is refused`() {
        val a = Spake2(Spake2.Role.ALICE, alice, bob)
        a.generateMessage("642099".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { a.processMessage(ByteArray(31)) }
    }

    @Test
    fun `processing before generating is refused`() {
        val a = Spake2(Spake2.Role.ALICE, alice, bob)
        assertThrows(IllegalStateException::class.java) { a.processMessage(ByteArray(32)) }
    }

    @Test
    fun `a message cannot be generated twice`() {
        val a = Spake2(Spake2.Role.ALICE, alice, bob)
        a.generateMessage("642099".toByteArray())
        assertThrows(IllegalStateException::class.java) { a.generateMessage("642099".toByteArray()) }
    }

    @Test
    fun `the names are bound into the key`() {
        // Same password, different identities: the keys must not match, or the
        // exchange would be transplantable between contexts.
        val a1 = Spake2(Spake2.Role.ALICE, alice, bob)
        val b1 = Spake2(Spake2.Role.BOB, bob, alice)
        val m1 = a1.generateMessage("1".toByteArray())
        val k1 = a1.processMessage(b1.generateMessage("1".toByteArray()))
        assertArrayEquals(k1, b1.processMessage(m1))

        val a2 = Spake2(Spake2.Role.ALICE, "other".toByteArray(), bob)
        val b2 = Spake2(Spake2.Role.BOB, bob, "other".toByteArray())
        val m2 = a2.generateMessage("1".toByteArray())
        val k2 = a2.processMessage(b2.generateMessage("1".toByteArray()))
        assertArrayEquals(k2, b2.processMessage(m2))

        assertFalse(k1.contentEquals(k2))
    }

    // ── the arithmetic that fails silently ───────────────────────────────────

    @Test
    fun `multiplying a scalar by eight is a plain shift, carried across bytes`() {
        val one = ByteArray(32).also { it[0] = 1 }
        assertEquals(8, Spake2.leftShift3(one)[0].toInt())
        // 0x20 << 3 = 0x100: the bit must land in the NEXT byte, not be dropped.
        val thirtyTwo = ByteArray(32).also { it[0] = 0x20 }
        val shifted = Spake2.leftShift3(thirtyTwo)
        assertEquals(0, shifted[0].toInt())
        assertEquals(1, shifted[1].toInt())
    }

    @Test
    fun `clearing the low three bits leaves them zero for every combination`() {
        // All eight starting states, because the correction adds L, 2L and 4L
        // conditionally and an off-by-one in that ladder still "works" for some.
        for (low in 0 until 8) {
            val s = ByteArray(32).also { it[0] = low.toByte(); it[1] = 0x11 }
            val fixed = Spake2.clearLowThreeBits(s)
            assertEquals("low bits of $low", 0, fixed[0].toInt() and 7)
        }
    }

    @Test
    fun `a scalar that already ends in zero bits is left untouched`() {
        val s = ByteArray(32).also { it[0] = 0x08; it[1] = 0x11 }
        assertArrayEquals(s, Spake2.clearLowThreeBits(s))
    }

    @Test
    fun `addition carries across the whole 256 bits`() {
        val a = ByteArray(32) { 0xFF.toByte() }
        val one = ByteArray(32).also { it[0] = 1 }
        // 2^256 - 1 + 1 wraps to zero: overflow is discarded, not thrown.
        assertArrayEquals(ByteArray(32), Spake2.add256(a, one))
        val b = ByteArray(32).also { it[0] = 0xFF.toByte() }
        val carried = Spake2.add256(b, one)
        assertEquals(0, carried[0].toInt())
        assertEquals(1, carried[1].toInt())
    }

    @Test
    fun `a long password is accepted — the code is hashed, not used raw`() {
        val long = ByteArray(500) { 'x'.code.toByte() }
        val (a, b) = exchange(long)
        assertArrayEquals(a, b)
        assertTrue(a.any { it.toInt() != 0 })
    }
}
