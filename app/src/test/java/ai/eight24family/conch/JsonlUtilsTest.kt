package ai.eight24family.conch

import ai.eight24family.conch.util.JsonlUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
