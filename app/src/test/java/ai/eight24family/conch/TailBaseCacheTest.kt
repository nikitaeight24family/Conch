package ai.eight24family.conch

import ai.eight24family.conch.data.GlobalPrefetcher
import ai.eight24family.conch.data.HistoryCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tail-first mirroring's one new axiom (Workstream C, 2026-08-17): local
 * byte 0 no longer has to be remote byte 0 — the `.base` sidecar holds the
 * origin, and every offset consumer adds it. These pin the sidecar contract,
 * the seen-watermark rebase across a re-tail (the badge must survive in
 * REMOTE coordinates), and the slab alignment helper.
 */
class TailBaseCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun cache(): HistoryCache {
        val root = File(tmp.root, "session_history")
        return HistoryCache::class.java
            .getDeclaredConstructor(File::class.java)
            .apply { isAccessible = true }
            .newInstance(root) as HistoryCache
    }

    @Test
    fun `base defaults to zero and zero deletes the sidecar`() {
        val c = cache()
        assertEquals(0L, c.baseOffset("sid"))
        c.setBaseOffset("sid", 4_000L)
        assertEquals(4_000L, c.baseOffset("sid"))
        c.setBaseOffset("sid", 0L)
        assertEquals(0L, c.baseOffset("sid"))
    }

    @Test
    fun `saveTail stores the slab and its origin`() {
        val c = cache()
        val slab = "line-a\nline-b\n".toByteArray()
        c.saveTail("sid", slab, newBase = 10_000L)
        assertEquals(10_000L, c.baseOffset("sid"))
        assertEquals(slab.size.toLong(), c.size("sid"))
    }

    /** A full-body save is the statement "local IS remote from byte 0" — a
     *  leftover base would shift every remote-offset computation. */
    @Test
    fun `full save resets the base`() {
        val c = cache()
        c.saveTail("sid", "tail\n".toByteArray(), newBase = 9_000L)
        c.save("sid", "whole-file\n".toByteArray())
        assertEquals(0L, c.baseOffset("sid"))
    }

    // ── seen-watermark rebase across a re-tail ──
    //
    // Remote truth: the user had read up to remote byte R = oldBase + seenLocal.
    // After a re-tail with newBase, the same R must be seenLocal' = R − newBase,
    // clamped into the new file.

    @Test
    fun `seen watermark survives a re-tail in remote coordinates`() {
        val c = cache()
        c.saveTail("sid", ByteArray(1000) { '\n'.code.toByte() }, newBase = 5_000L)
        c.markSeenBytes("sid", 600L) // remote read position = 5_600
        c.saveTail("sid", ByteArray(1200) { '\n'.code.toByte() }, newBase = 5_200L)
        // Same remote position, new local frame: 5_600 − 5_200 = 400.
        assertEquals(400L, c.seenBytes("sid"))
    }

    @Test
    fun `seen clamps to zero when the re-tail jumps past the read position`() {
        val c = cache()
        c.saveTail("sid", ByteArray(500) { '\n'.code.toByte() }, newBase = 1_000L)
        c.markSeenBytes("sid", 100L) // remote 1_100
        c.saveTail("sid", ByteArray(800) { '\n'.code.toByte() }, newBase = 50_000L)
        // Everything now cached is beyond what was read → all unread.
        assertEquals(0L, c.seenBytes("sid"))
    }

    @Test
    fun `never-viewed session gains no watermark from a re-tail`() {
        val c = cache()
        c.saveTail("sid", "x\n".toByteArray(), newBase = 3_000L)
        assertEquals(null, c.seenBytes("sid"))
    }

    // ── slab alignment ──

    @Test
    fun `mid-file slab drops its partial first line and advances the origin`() {
        val slab = "partial-tail\n{\"a\":1}\n{\"b\":2}\n".toByteArray()
        val (aligned, dropped) = GlobalPrefetcher.dropLeadingPartialLine(slab, isFileStart = false)
        assertEquals("partial-tail\n".length.toLong(), dropped)
        assertArrayEquals("{\"a\":1}\n{\"b\":2}\n".toByteArray(), aligned)
    }

    @Test
    fun `file-start slab keeps its first line`() {
        val slab = "{\"a\":1}\n".toByteArray()
        val (aligned, dropped) = GlobalPrefetcher.dropLeadingPartialLine(slab, isFileStart = true)
        assertEquals(0L, dropped)
        assertArrayEquals(slab, aligned)
    }

    @Test
    fun `slab with no newline at all yields nothing`() {
        val slab = "no-newline-here".toByteArray()
        val (aligned, dropped) = GlobalPrefetcher.dropLeadingPartialLine(slab, isFileStart = false)
        assertEquals(slab.size.toLong(), dropped)
        assertEquals(0, aligned.size)
    }
}
