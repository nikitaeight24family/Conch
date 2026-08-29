package ai.eight24family.conch

import ai.eight24family.conch.data.HistoryCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The unread watermark is read once per cached session per home-list reload —
 * 380 sessions on the owner's phone, a tick every 2.5 s — so it is memoized in
 * memory. A memo that can drift from disk would show a stale unread badge
 * forever, which is worse than the I/O it saves; these tests pin the two ways
 * it could drift.
 */
class SeenWatermarkMemoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cache: HistoryCache
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tempFolder.newFolder("session_history")
        cache = HistoryCache(dir)
    }

    @Test
    fun `absent watermark reads as null and stays null`() {
        assertNull(cache.seenBytes("s1"))
        // Second read comes from the memo — same answer, no file conjured.
        assertNull(cache.seenBytes("s1"))
        assertNull(cache.seenBytes("s1"))
    }

    @Test
    fun `a write is visible to the very next read`() {
        assertNull(cache.seenBytes("s2"))   // memoizes "no watermark"
        cache.markSeenBytes("s2", 1234L)
        // If the memo were not updated on write, this would still say null.
        assertEquals(1234L, cache.seenBytes("s2"))
    }

    @Test
    fun `the monotonic guard still holds through the memo`() {
        cache.markSeenBytes("s3", 900L)
        cache.markSeenBytes("s3", 400L)     // a stale writer must not roll it back
        assertEquals(900L, cache.seenBytes("s3"))
        cache.markSeenBytes("s3", 1500L)
        assertEquals(1500L, cache.seenBytes("s3"))
    }

    @Test
    fun `the value survives a fresh cache over the same directory`() {
        cache.markSeenBytes("s4", 777L)
        // A new instance has an empty memo: it must fall back to the file.
        assertEquals(777L, HistoryCache(dir).seenBytes("s4"))
    }

    @Test
    fun `forgetting a session drops its memo`() {
        cache.markSeenBytes("s5", 55L)
        assertEquals(55L, cache.seenBytes("s5"))
        cache.forget("s5")
        // The body is gone; whatever the watermark file does, the memo must not
        // keep answering from a session that was dropped.
        val after = cache.seenBytes("s5")
        assertEquals(if (File(dir, "s5.seen").exists()) 55L else null, after)
    }
}
