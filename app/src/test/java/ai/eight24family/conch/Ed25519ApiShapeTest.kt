package ai.eight24family.conch

import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * What the group library actually hands back, pinned before anything is built
 * on assumptions about it.
 *
 * SPAKE2 needs to multiply a FIXED point (not the base point) by a scalar and
 * then ADD two such results — and in this representation-based library, addition
 * demands a P3 on the left and a CACHED on the right. Which representation a
 * scalar multiplication returns therefore decides whether the protocol code can
 * be written at all, and getting it wrong surfaces as a runtime exception deep
 * inside a pairing attempt rather than at compile time.
 */
class Ed25519ApiShapeTest {

    private val spec = EdDSANamedCurveTable.ED_25519_CURVE_SPEC

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    /** SPAKE2's M point, as published by the implementation adbd uses. */
    private val M = "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"

    @Test
    fun `a decoded point can be asked for a scalar multiple`() {
        // The `true` asks the library to precompute; without it scalarMultiply
        // has no table to walk.
        val m = GroupElement(spec.curve, hex(M), true)
        val scalar = ByteArray(32).also { it[0] = 8 }
        val product = m.scalarMultiply(scalar)
        assertNotNull(product)
        assertEquals(32, product.toByteArray().size)
    }

    @Test
    fun `scalar multiplication returns a representation that can be added`() {
        val m = GroupElement(spec.curve, hex(M), true)
        val a = m.scalarMultiply(ByteArray(32).also { it[0] = 3 })
        val b = spec.b.scalarMultiply(ByteArray(32).also { it[0] = 5 })
        // If this throws, the protocol has to be written another way.
        val sum = a.toP3().add(b.toP3().toCached())
        assertEquals(32, sum.toP2().toByteArray().size)
    }

    @Test
    fun `subtraction is available on the same shapes`() {
        val m = GroupElement(spec.curve, hex(M), true)
        val a = m.scalarMultiply(ByteArray(32).also { it[0] = 7 })
        val b = m.scalarMultiply(ByteArray(32).also { it[0] = 4 })
        val diff = a.toP3().sub(b.toP3().toCached()).toP2().toByteArray()
        val direct = m.scalarMultiply(ByteArray(32).also { it[0] = 3 }).toByteArray()
        assertEquals(direct.toList(), diff.toList())
    }

    @Test
    fun `a point computed at runtime cannot be multiplied until it is re-decoded`() {
        // The trap that cost a test run: scalarMultiply walks a precomputed
        // table, and a point produced by add/sub has none. It fails loudly
        // rather than silently — but the protocol still has to round-trip the
        // point through its (canonical) encoding to get a multipliable one.
        val m = GroupElement(spec.curve, hex(M), true)
        val derived = m.scalarMultiply(ByteArray(32).also { it[0] = 3 }).toP3()
            .add(spec.b.scalarMultiply(ByteArray(32).also { it[0] = 2 }).toP3().toCached())
            .toP3()
        try {
            derived.scalarMultiply(ByteArray(32).also { it[0] = 2 })
            throw AssertionError("expected a bare point to refuse scalarMultiply")
        } catch (expected: NullPointerException) {
            // exactly the failure the protocol code must avoid
        }
        val multipliable = GroupElement(spec.curve, derived.toByteArray(), true)
        assertEquals(32, multipliable.scalarMultiply(ByteArray(32).also { it[0] = 2 }).toByteArray().size)
    }

    @Test
    fun `the published M and N decode to the published coordinates`() {
        // Cheapest possible guard against a transposed constant: the source that
        // defines these points also states their affine coordinates.
        val m = GroupElement(spec.curve, hex(M), false).toP3()
        val n = GroupElement(
            spec.curve, hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"), false,
        ).toP3()
        assertEquals(
            "31406539342727633121250288103050113562375374900226415211311216773867585644232",
            java.math.BigInteger(1, m.x.toByteArray().reversedArray()).toString(),
        )
        assertEquals(
            "49918732221787544735331783592030787422991506689877079631459872391322455579424",
            java.math.BigInteger(1, n.x.toByteArray().reversedArray()).toString(),
        )
    }
}
