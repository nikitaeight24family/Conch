package ai.eight24family.conch

import ai.eight24family.conch.data.HistoryCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/**
 * File I/O round-trip tests for [HistoryCache]. Bypasses Android `Context`
 * via the `internal` constructor that takes a plain [File] — that way
 * tests run on the JVM with no Robolectric / AGP dance, and they exercise
 * the same `writeBytes` / mmap-load / `appendBytes` paths as production.
 *
 * `load()` returns a mmap-backed `ByteBuffer` instead of a heap ByteArray
 * (the production fix that nukes the 24 MB-per-open allocation). Tests
 * compare contents by copying the buffer back to a ByteArray via
 * [bufferToBytes].
 */
class HistoryCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cache: HistoryCache
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tempFolder.newFolder("session_history")
        cache = HistoryCache(dir)
    }

    /** Drain a mmap-backed `ByteBuffer` to a heap `ByteArray` for assertions.
     *  Duplicates so the original buffer's position stays at 0. */
    private fun bufferToBytes(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate().apply { rewind() }
        val arr = ByteArray(dup.remaining())
        dup.get(arr)
        return arr
    }

    @Test
    fun `load on missing file returns null`() {
        assertNull(cache.load("never-saved"))
    }

    @Test
    fun `save then load round-trip`() {
        val payload = "line1\nline2\nline3\n".toByteArray()
        cache.save("sess-1", payload)
        val snap = cache.load("sess-1")
        assertNotNull(snap)
        assertArrayEquals(payload, bufferToBytes(snap!!.buffer))
        assertTrue("cachedAt should be a recent timestamp", snap.cachedAt > 0)
    }

    @Test
    fun `save overwrites existing file`() {
        cache.save("s", "first".toByteArray())
        cache.save("s", "second".toByteArray())
        val snap = cache.load("s")
        assertNotNull(snap)
        assertEquals("second", String(bufferToBytes(snap!!.buffer)))
    }

    @Test
    fun `append concatenates without truncating`() {
        cache.save("s", "hello\n".toByteArray())
        cache.append("s", "world\n".toByteArray())
        val snap = cache.load("s")
        assertNotNull(snap)
        assertEquals("hello\nworld\n", String(bufferToBytes(snap!!.buffer)))
    }

    @Test
    fun `append on missing file creates it`() {
        cache.append("brand-new", "fresh\n".toByteArray())
        val snap = cache.load("brand-new")
        assertNotNull(snap)
        assertEquals("fresh\n", String(bufferToBytes(snap!!.buffer)))
    }

    @Test
    fun `append with empty bytes is a no-op`() {
        cache.save("s", "x".toByteArray())
        val before = bufferToBytes(cache.load("s")!!.buffer)
        cache.append("s", ByteArray(0))
        val after = bufferToBytes(cache.load("s")!!.buffer)
        assertArrayEquals(before, after)
    }

    @Test
    fun `forget removes the file`() {
        cache.save("s", "data".toByteArray())
        // Close the mapping immediately on the existence check —
        // otherwise on Windows the still-mapped file blocks
        // `forget()`'s `File.delete()` (mmap holds the file handle),
        // `delete()` silently fails inside `runCatching`, and the
        // next `load()` finds the file still there. Linux/macOS
        // allows deletion of mapped files; tests fail only on Win.
        cache.load("s")?.use { assertNotNull(it) } ?: error("expected non-null")
        cache.forget("s")
        assertNull(cache.load("s"))
    }

    @Test
    fun `forget on missing file is harmless`() {
        cache.forget("never-existed")  // should not throw
    }

    @Test
    fun `size reports byte count or zero`() {
        assertEquals(0L, cache.size("missing"))
        cache.save("s", ByteArray(123))
        assertEquals(123L, cache.size("s"))
    }

    @Test
    fun `unsafe characters in session id are sanitized into the filename`() {
        // Defense: filename sanitiser strips everything outside
        // [A-Za-z0-9._-]. Save a payload with a hostile id, verify only
        // one file lands inside the cache dir and no path-traversal.
        cache.save("../sneaky/../../etc passwd", "x".toByteArray())
        val files = dir.listFiles()?.toList().orEmpty()
        assertEquals(1, files.size)
        val name = files.first().name
        assertTrue("filename must not contain /", "/" !in name)
        assertTrue("filename must not contain \\", "\\" !in name)
        assertTrue(name.endsWith(".jsonl"))
        assertEquals(dir.canonicalPath, files.first().parentFile!!.canonicalPath)
    }

    @Test
    fun `large payload survives round-trip`() {
        // Real session JSONLs run into multi-MB. Confirm we don't truncate
        // or OOM at 5 MiB. mmap path zero-copies — but we still drain it
        // through `bufferToBytes` here to byte-compare against the source.
        val payload = ByteArray(5 * 1024 * 1024) { (it and 0xFF).toByte() }
        cache.save("big", payload)
        val snap = cache.load("big")
        assertNotNull(snap)
        assertArrayEquals(payload, bufferToBytes(snap!!.buffer))
    }

    @Test
    fun `empty file save then load returns null`() {
        // load() short-circuits when length() is 0, returning null — the
        // polling code relies on this so an empty body doesn't get re-fed
        // to the parser.
        cache.save("s", ByteArray(0))
        assertNull(cache.load("s"))
    }

    // ── serverContainsAllLocal: the benign-shrink vs real-compaction test that
    //    keeps our own `sdk-cli`→`cli` entrypoint rewrite from being mistaken for
    //    a compaction (which corrupted chat order + the topbar model). ──

    @Test
    fun `entrypoint rewrite (sdk-cli to cli) is NOT a compaction`() {
        // Same three turns, same uuids — only the entrypoint value shrank. The
        // server still holds every local id → this is our own rewrite, not a
        // Claude compaction → the caller must re-adopt the server verbatim.
        val local = (
            """{"uuid":"a1","entrypoint":"sdk-cli","message":{"id":"msg_1"}}""" + "\n" +
                """{"uuid":"b2","entrypoint":"sdk-cli","message":{"id":"msg_2"}}""" + "\n" +
                """{"uuid":"c3","entrypoint":"sdk-cli","message":{"id":"msg_3"}}""" + "\n"
            ).toByteArray()
        val server = String(local).replace("\"sdk-cli\"", "\"cli\"").toByteArray()
        assertTrue("server is 12 bytes SHORTER", server.size < local.size)
        cache.save("sess", local)
        assertTrue(
            "entrypoint rewrite keeps all ids → NOT a compaction",
            cache.serverContainsAllLocal("sess", server),
        )
    }

    @Test
    fun `real-compaction merge keeps dropped turns chronologically + all blocks`() {
        // local: two OLD turns (a1,b2) + one recent turn whose thinking & tool_use
        // blocks are SEPARATE lines sharing message.id msg_3 but distinct uuids.
        val local = (
            """{"uuid":"a1","message":{"id":"msg_1","content":"old-1"}}""" + "\n" +
                """{"uuid":"b2","message":{"id":"msg_2","content":"old-2"}}""" + "\n" +
                """{"uuid":"c3a","message":{"id":"msg_3","type":"thinking"}}""" + "\n" +
                """{"uuid":"c3b","message":{"id":"msg_3","type":"tool_use"}}""" + "\n"
            ).toByteArray()
        // server (post-compaction): summary replaces old turns; recent turn kept whole.
        val server = (
            """{"uuid":"sum","message":{"id":"msg_summary","content":"summary"}}""" + "\n" +
                """{"uuid":"c3a","message":{"id":"msg_3","type":"thinking"}}""" + "\n" +
                """{"uuid":"c3b","message":{"id":"msg_3","type":"tool_use"}}""" + "\n"
            ).toByteArray()
        cache.save("sess", local)
        val merged = cache.mergeServer("sess", server)!!
        val lines = String(merged).trim().split("\n")
        val uuids = lines.map { Regex("\"uuid\":\"([^\"]+)\"").find(it)!!.groupValues[1] }
        // Chronological: dropped-old turns FIRST (a1,b2), then the server truth.
        assertEquals(listOf("a1", "b2", "sum", "c3a", "c3b"), uuids)
        // BOTH blocks of msg_3 survive (uuid key, not the collapsing message.id).
        assertTrue("thinking block kept", uuids.contains("c3a"))
        assertTrue("tool_use block kept", uuids.contains("c3b"))
    }

    @Test
    fun `real compaction (server dropped old turns) IS a compaction`() {
        // The server file genuinely lost the first turn's ids → containment
        // fails → the caller falls through to the keep-local merge.
        val local = (
            """{"uuid":"a1","message":{"id":"msg_1"}}""" + "\n" +
                """{"uuid":"b2","message":{"id":"msg_2"}}""" + "\n" +
                """{"uuid":"c3","message":{"id":"msg_3"}}""" + "\n"
            ).toByteArray()
        // Compaction replaced msg_1/msg_2 with a summary and kept only msg_3.
        val server = (
            """{"uuid":"sum","message":{"id":"msg_summary"}}""" + "\n" +
                """{"uuid":"c3","message":{"id":"msg_3"}}""" + "\n"
            ).toByteArray()
        cache.save("sess", local)
        assertFalse(
            "server dropped msg_1/msg_2 → this IS a real compaction",
            cache.serverContainsAllLocal("sess", server),
        )
    }
}
