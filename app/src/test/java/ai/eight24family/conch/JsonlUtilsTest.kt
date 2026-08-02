package ai.eight24family.conch

import ai.eight24family.conch.util.JsonlUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Edge-case coverage for [JsonlUtils.trimToLastNewline] — the
 * boundary trimmer that lets ssh.ai tail incrementally-written
 * stream-json without ever feeding a half-line to a parser.
 *
 * Part of issue #21 ("edge cases — slow/no network, large JSONL,
 * rotation, process death"). This is the one of those four that
 * lives in pure pre-Android code and can be locked down here.
 */
class JsonlUtilsTest {

    @Test
    fun `empty input returns an empty array`() {
        assertEquals(0, JsonlUtils.trimToLastNewline(ByteArray(0)).size)
    }

    @Test
    fun `buffer with no newline at all returns empty (nothing complete yet)`() {
        val bytes = "halfline_no_terminator".toByteArray()
        assertEquals(0, JsonlUtils.trimToLastNewline(bytes).size)
    }

    @Test
    fun `buffer ending exactly on newline is returned verbatim`() {
        val text = "{\"a\":1}\n{\"a\":2}\n"
        val out = JsonlUtils.trimToLastNewline(text.toByteArray())
        assertArrayEquals(text.toByteArray(), out)
    }

    @Test
    fun `buffer with trailing half-line is trimmed to the last complete line`() {
        val complete = "{\"a\":1}\n{\"a\":2}\n"
        val tail = complete + "{\"a\":3"  // half-written third line
        val out = JsonlUtils.trimToLastNewline(tail.toByteArray())
        assertArrayEquals(complete.toByteArray(), out)
    }

    @Test
    fun `single newline-only buffer is returned as-is`() {
        val out = JsonlUtils.trimToLastNewline("\n".toByteArray())
        assertArrayEquals("\n".toByteArray(), out)
    }

    @Test
    fun `large 10MB buffer with a half-line tail trims correctly without OOM`() {
        // Simulate a real remote JSONL: 10 MB of synthetic complete
        // lines plus one trailing half-line. Confirms (a) we don't
        // run out of memory walking the buffer, (b) we don't lose
        // the final complete line, (c) we drop the half-line.
        val line = "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"" +
            "x".repeat(120) + "\"}]}}\n"
        val lineBytes = line.toByteArray()
        val lines = 10 * 1024 * 1024 / lineBytes.size
        val expectedSize = lines * lineBytes.size
        val full = ByteArray(expectedSize)
        var off = 0
        repeat(lines) {
            System.arraycopy(lineBytes, 0, full, off, lineBytes.size)
            off += lineBytes.size
        }
        val withTail = full + "{\"type\":\"half".toByteArray()

        val started = System.currentTimeMillis()
        val out = JsonlUtils.trimToLastNewline(withTail)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(expectedSize, out.size)
        // Sanity: shouldn't take more than a couple of seconds on any
        // CI worker. The algorithm is O(n) walking backwards.
        assertTrue("trim too slow: ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `trim is idempotent — running it twice on its own output returns the same bytes`() {
        val input = "a\nb\nc".toByteArray() // c is a half-line
        val first = JsonlUtils.trimToLastNewline(input)
        val second = JsonlUtils.trimToLastNewline(first)
        assertArrayEquals(first, second)
    }

    // ───────────────────────── tailSlice ─────────────────────────
    // The display-windowing primitive: parse only the recent tail of a
    // huge session so a 20 MB JSONL doesn't freeze chat-open, while the
    // full file stays cached. Must start on a clean line boundary and
    // never emit a partial line.

    private fun ByteBuffer.asString(): String {
        val arr = ByteArray(remaining())
        duplicate().get(arr)
        return String(arr, Charsets.UTF_8)
    }

    @Test
    fun `tailSlice returns the whole trimmed file when it fits the window`() {
        val text = "{\"a\":1}\n{\"a\":2}\n"
        val w = JsonlUtils.tailSlice(ByteBuffer.wrap(text.toByteArray()), 1024)
        assertEquals(0L, w.droppedBytes)
        assertTrue(!w.windowed)
        assertEquals(text, w.slice.asString())
    }

    @Test
    fun `tailSlice windows to the last whole lines and drops the partial head`() {
        val sb = StringBuilder()
        for (i in 0 until 100) sb.append("{\"i\":").append(i).append("}\n")
        val full = sb.toString().toByteArray()
        val w = JsonlUtils.tailSlice(ByteBuffer.wrap(full), 40) // ~ last few lines only
        assertTrue("should be windowed", w.windowed)
        val s = w.slice.asString()
        assertTrue("starts on a clean line boundary: <$s>", s.startsWith("{"))
        assertTrue("ends with the very last line: <$s>", s.endsWith("{\"i\":99}\n"))
        // every emitted line is a complete JSON object — no partial head/tail
        s.split("\n").filter { it.isNotEmpty() }.forEach {
            assertTrue("complete line: <$it>", it.startsWith("{") && it.endsWith("}"))
        }
    }

    @Test
    fun `tailSlice droppedBytes lands exactly past a newline boundary`() {
        val sb = StringBuilder()
        for (i in 0 until 50) sb.append("{\"i\":").append(i).append("}\n")
        val full = sb.toString().toByteArray()
        val w = JsonlUtils.tailSlice(ByteBuffer.wrap(full), 30)
        assertTrue(w.droppedBytes > 0)
        // the byte right before the window start is a '\n' (window begins
        // immediately after a complete line — no partial head survives)
        assertEquals('\n'.code.toByte(), full[(w.droppedBytes - 1).toInt()])
    }

    @Test
    fun `tailSlice on an empty buffer is empty`() {
        val w = JsonlUtils.tailSlice(ByteBuffer.allocate(0), 1024)
        assertEquals(0, w.slice.remaining())
        assertEquals(0L, w.droppedBytes)
    }

    @Test
    fun `tailSlice with no newline yields nothing complete`() {
        val w = JsonlUtils.tailSlice(ByteBuffer.wrap("noterminator".toByteArray()), 1024)
        assertEquals(0, w.slice.remaining())
        assertEquals(0L, w.droppedBytes)
    }
}
