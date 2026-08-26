package ai.eight24family.conch

import ai.eight24family.conch.util.Bitmaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sampling math behind every display decode (Google Play's
 * bitmap-memory vital). The decode wrappers themselves are thin
 * BitmapFactory calls; the logic worth pinning is the power-of-two cap.
 */
class BitmapsTest {

    @Test
    fun `sampleSize caps BOTH dimensions to maxDim`() {
        // Already small — no sampling.
        assertEquals(1, Bitmaps.sampleSize(100, 100, 256))
        assertEquals(1, Bitmaps.sampleSize(256, 256, 256))
        // One dimension over — sampled by the smallest sufficient power of two.
        assertEquals(2, Bitmaps.sampleSize(500, 100, 256))
        assertEquals(2, Bitmaps.sampleSize(100, 512, 256))
        assertEquals(2, Bitmaps.sampleSize(257, 100, 256))
        // A 12 MP camera frame for a 64 dp chip: 4000/16=250, 3000/16=188.
        assertEquals(16, Bitmaps.sampleSize(4000, 3000, 256))
        // The inline-chat cap.
        assertEquals(2, Bitmaps.sampleSize(2400, 1080, 1600))
    }

    @Test
    fun `result is a power of two and lands the longer side above half of maxDim`() {
        for (dim in intArrayOf(96, 256, 1600)) {
            for (w in intArrayOf(1, 97, 300, 1000, 4000, 8192)) {
                val s = Bitmaps.sampleSize(w, w / 2 + 1, dim)
                assertEquals("power of two for w=$w dim=$dim", 0, s and (s - 1))
                // Both fit…
                assertTrue("w=$w s=$s dim=$dim", w / s <= dim)
                // …and we never over-shrink (halving again would still fit → wrong s).
                if (s > 1) assertTrue("over-sampled w=$w s=$s dim=$dim", w / (s / 2) > dim)
            }
        }
    }

    @Test
    fun `degenerate inputs decode at full size instead of crashing`() {
        // Unknown bounds (undecodable input probes as -1) and nonsense caps
        // fall back to 1 — the real decode then reports the failure.
        assertEquals(1, Bitmaps.sampleSize(-1, -1, 256))
        assertEquals(1, Bitmaps.sampleSize(0, 100, 256))
        assertEquals(1, Bitmaps.sampleSize(100, 0, 256))
        assertEquals(1, Bitmaps.sampleSize(100, 100, 0))
    }
}
