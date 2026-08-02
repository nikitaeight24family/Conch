package ai.eight24family.conch

import ai.eight24family.conch.data.UploadCache
import org.junit.Assert.assertEquals
import org.junit.Test

/** The streaming SHA-256 (used for large attachments that never sit in RAM)
 *  MUST produce byte-identical hex to the in-memory variant, or the upload
 *  dedupe cache silently misses across the two paths. */
class UploadCacheShaTest {
    @Test
    fun `streaming sha matches in-memory sha`() {
        for (size in intArrayOf(0, 1, 63, 64, 65, 1024, 65_536, 200_000)) {
            val bytes = ByteArray(size) { ((it * 31 + 7) and 0xff).toByte() }
            val inMem = UploadCache.sha256Hex(bytes)
            val streamed = UploadCache.sha256HexStream(bytes.inputStream())
            assertEquals("size=$size", inMem, streamed)
        }
    }
}
