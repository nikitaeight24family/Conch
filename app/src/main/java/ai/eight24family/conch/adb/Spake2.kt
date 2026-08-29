package ai.eight24family.conch.adb

import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over Ed25519, in the exact shape Android's ADB pairing speaks.
 *
 * SPAKE2 is a password-authenticated key exchange: two parties who share only a
 * low-entropy secret — here the six digits the phone shows — end up with a
 * strong shared key, and an eavesdropper gets one password guess per run rather
 * than an offline attack. Android uses it to bootstrap Wireless Debugging, so a
 * client that can speak it can pair with the device WITHOUT a computer and
 * without a second app on the phone.
 *
 * ⚠ WHAT IS OURS AND WHAT IS NOT. The protocol below is written here from its
 * published description. The elliptic-curve arithmetic underneath is NOT ours
 * and deliberately so: it comes from a public-domain (CC0) Ed25519 library,
 * because hand-rolled curve arithmetic is the classic way to ship something that
 * looks like it works and quietly is not. The library is exercised against the
 * RFC 8032 vectors in `Ed25519GroupMathTest` before anything here runs on it.
 *
 * THE SCHEME, precisely (it is a variant, not textbook SPAKE2 — a client that
 * implements the paper will not interoperate):
 *
 *  - Two fixed points, M and N, break the symmetry between the parties. Their
 *    published affine coordinates are checked in `Ed25519ApiShapeTest`.
 *  - w = reduce(SHA-512(password)) mod L, then nudged so its low three bits are
 *    zero. That nudge is NOT cosmetic: the reference implementation forgot to
 *    multiply w by the cofactor, and rather than break every deployed peer it
 *    adds multiples of the group order (which flip exactly those three bits) so
 *    the mask lands in the prime-order subgroup. A client that "fixes" this by
 *    multiplying by eight instead derives a different key and simply never
 *    pairs.
 *  - The ephemeral scalar IS multiplied by eight, which is what cancels the
 *    small-order components later.
 *  - Message = x·B + w·(M for Alice, N for Bob).
 *  - Shared point = x·(peer's message − w·(the OTHER mask)).
 *  - Key = SHA-512 over, always in Alice-then-Bob order: Alice's name, Bob's
 *    name, Alice's message, Bob's message, the shared point, and the FULL
 *    64-byte SHA-512 of the password — each preceded by its length as eight
 *    little-endian bytes.
 */
class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
) {

    enum class Role { ALICE, BOB }

    private val spec = EdDSANamedCurveTable.ED_25519_CURVE_SPEC

    private var privateKey: ByteArray? = null
    private var passwordScalar: ByteArray? = null
    private var passwordHash: ByteArray? = null
    private var myMessage: ByteArray? = null

    /**
     * Produce this side's 32-byte message. Callable once — a second call with a
     * fresh ephemeral scalar would silently invalidate the exchange already in
     * flight.
     */
    fun generateMessage(password: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        check(myMessage == null) { "SPAKE2 message already generated for this exchange" }

        // Ephemeral scalar: 64 random bytes reduced into the group, then times
        // the cofactor so any small-order component of the peer's point cancels
        // when we multiply by it later.
        val ephemeral = ByteArray(64).also { random.nextBytes(it) }
        val x = leftShift3(spec.scalarOps.reduce(ephemeral))
        privateKey = x

        val hash = MessageDigest.getInstance("SHA-512").digest(password)
        passwordHash = hash
        passwordScalar = clearLowThreeBits(spec.scalarOps.reduce(hash.copyOf()))

        val mask = maskPoint(mine = true).scalarMultiply(passwordScalar)
        val message = spec.b.scalarMultiply(x).toP3()
            .add(mask.toP3().toCached())
            .toP2().toByteArray()
        myMessage = message
        return message
    }

    /**
     * Consume the peer's message and derive the 64-byte shared key.
     *
     * Throws when the peer's message is not a point on the curve — the only
     * check available at this layer, and the one that stops a malformed or
     * hostile peer from steering the arithmetic.
     */
    fun processMessage(theirMessage: ByteArray): ByteArray {
        val x = privateKey
        val w = passwordScalar
        val hash = passwordHash
        val mine = myMessage
        check(x != null && w != null && hash != null && mine != null) {
            "generateMessage must be called before processMessage"
        }
        require(theirMessage.size == 32) {
            "a SPAKE2 message is 32 bytes, got ${theirMessage.size}"
        }

        val theirPoint = decodePoint(theirMessage)
        val peerMask = maskPoint(mine = false).scalarMultiply(w)
        val unmasked = theirPoint.sub(peerMask.toP3().toCached()).toP3()
        // ⚠ A point computed at runtime carries no multiplication table, and
        // this library's scalarMultiply walks one — on a bare point it fails with
        // a null table rather than a wrong answer, which is the good outcome, but
        // it still has to be handled. Re-decoding with precomputation is exact:
        // the encoding is canonical, so this is the same point, now multipliable.
        val multipliable = GroupElement(spec.curve, unmasked.toByteArray(), true)
        val shared = multipliable.scalarMultiply(x).toByteArray()

        val sha = MessageDigest.getInstance("SHA-512")
        // Always Alice's fields first: both sides must hash the same bytes in
        // the same order, and only the roles tell them which is which.
        if (role == Role.ALICE) {
            withLengthPrefix(sha, myName)
            withLengthPrefix(sha, theirName)
            withLengthPrefix(sha, mine)
            withLengthPrefix(sha, theirMessage)
        } else {
            withLengthPrefix(sha, theirName)
            withLengthPrefix(sha, myName)
            withLengthPrefix(sha, theirMessage)
            withLengthPrefix(sha, mine)
        }
        withLengthPrefix(sha, shared)
        withLengthPrefix(sha, hash)
        return sha.digest()
    }

    /** Alice masks with M and unmasks N; Bob does the reverse. */
    private fun maskPoint(mine: Boolean): GroupElement =
        if ((role == Role.ALICE) == mine) pointM else pointN

    /**
     * Decode a peer's point, refusing anything a well-behaved peer would never
     * send.
     *
     * TWO checks, because one is not enough. The curve check is the obvious one.
     * The canonical-encoding check is not, and it is the one that bites: the
     * field is only 2^255-19 wide, so a y at or above that reduces silently and
     * can land on a perfectly valid point — 32 bytes of 0xFF do exactly that,
     * which is how the missing check was found. Any real peer encodes y below
     * the prime; refusing the rest costs nothing and closes the door on a peer
     * steering us onto a point of its choosing through an encoding we would
     * otherwise have accepted as ordinary.
     */
    private fun decodePoint(encoded: ByteArray): GroupElement {
        if (!isCanonical(encoded)) {
            throw IllegalArgumentException("peer's SPAKE2 point is not canonically encoded")
        }
        val p = runCatching { GroupElement(spec.curve, encoded, false).toP3() }
            .getOrElse { throw IllegalArgumentException("peer's SPAKE2 point is not on the curve", it) }
        if (!p.isOnCurve(spec.curve)) {
            throw IllegalArgumentException("peer's SPAKE2 point is not on the curve")
        }
        return p
    }

    private fun withLengthPrefix(sha: MessageDigest, data: ByteArray) {
        val len = ByteArray(8)
        var l = data.size.toLong()
        for (i in 0 until 8) {
            len[i] = (l and 0xFF).toByte()
            l = l ushr 8
        }
        sha.update(len)
        sha.update(data)
    }

    private val pointM: GroupElement by lazy { GroupElement(spec.curve, M_ENCODED, true) }
    private val pointN: GroupElement by lazy { GroupElement(spec.curve, N_ENCODED, true) }

    companion object {
        /** SPAKE2's fixed mask points, as published with their coordinates. */
        private val M_ENCODED = hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        private val N_ENCODED = hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")

        /** Order of the prime-order subgroup, little-endian. */
        private val ORDER = hex("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010")

        /** Field prime 2^255 - 19, little-endian. */
        private val FIELD_PRIME = hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")

        /**
         * Is this the encoding a peer would actually produce — y strictly below
         * the field prime, with the top bit read as the sign of x rather than as
         * part of the number?
         */
        internal fun isCanonical(encoded: ByteArray): Boolean {
            if (encoded.size != 32) return false
            val y = encoded.copyOf()
            y[31] = (y[31].toInt() and 0x7F).toByte()
            for (i in 31 downTo 0) {
                val a = y[i].toInt() and 0xFF
                val b = FIELD_PRIME[i].toInt() and 0xFF
                if (a != b) return a < b
            }
            return false // exactly the prime is not below it
        }

        private fun hex(s: String) = ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
        }

        /** Multiply a 32-byte little-endian scalar by eight. Plain shift, not
         *  modular: the caller relies on the value staying below 2^255. */
        internal fun leftShift3(scalar: ByteArray): ByteArray {
            val out = scalar.copyOf()
            var carry = 0
            for (i in out.indices) {
                val v = (out[i].toInt() and 0xFF)
                out[i] = (((v shl 3) or carry) and 0xFF).toByte()
                carry = v ushr 5
            }
            return out
        }

        /**
         * Add the group order to the scalar as many times as it takes to clear
         * the low three bits — L is odd, so adding L flips bit 0, adding 2L
         * flips bit 1, adding 4L flips bit 2. Starting below L, the result stays
         * under 8L and therefore under 2^256.
         */
        internal fun clearLowThreeBits(scalar: ByteArray): ByteArray {
            var out = scalar.copyOf()
            var multiple = ORDER.copyOf()
            for (bit in 0 until 3) {
                if ((out[0].toInt() shr bit) and 1 == 1) out = add256(out, multiple)
                multiple = add256(multiple, multiple)
            }
            return out
        }

        /** 256-bit little-endian addition, overflow discarded. */
        internal fun add256(a: ByteArray, b: ByteArray): ByteArray {
            val out = ByteArray(32)
            var carry = 0
            for (i in 0 until 32) {
                val sum = (a[i].toInt() and 0xFF) + (b[i].toInt() and 0xFF) + carry
                out[i] = (sum and 0xFF).toByte()
                carry = sum ushr 8
            }
            return out
        }
    }
}
