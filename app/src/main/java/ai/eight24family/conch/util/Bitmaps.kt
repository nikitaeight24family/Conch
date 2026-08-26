package ai.eight24family.conch.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Downsampled bitmap decoding for display surfaces.
 *
 * "Bitmap memory" is a first-class Android-vitals metric in Google Play's
 * memory-quality requirement, and full-size decodes are how a chat app fails
 * it: a 12 MP camera JPEG is ~48 MB as an ARGB_8888 Bitmap, and several call
 * sites used to decode exactly that for thumbnails measured in dp. Every
 * display decode goes through here: probe the bounds with inJustDecodeBounds
 * (no pixel allocation), then decode with the smallest power-of-two
 * inSampleSize that fits [sampleSize]'s cap.
 */
object Bitmaps {

    /**
     * Smallest power-of-two inSampleSize bringing BOTH dimensions to <=
     * [maxDim]. Power-of-two because BitmapFactory rounds anything else down
     * to one anyway. Unknown bounds (<= 0 — undecodable input) return 1 and
     * let the real decode report the failure.
     */
    fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0 || maxDim <= 0) return 1
        var sample = 1
        while (width / sample > maxDim || height / sample > maxDim) sample *= 2
        return sample
    }

    /**
     * Decode [bytes] with both dimensions capped to [maxDim] (largest
     * power-of-two undershoot, so the longer side lands in (maxDim/2, maxDim]).
     * [lowColor] decodes to RGB_565 — half the bytes per pixel; right for an
     * opaque photo thumbnail, wrong for anything needing alpha or smooth
     * gradients at large sizes.
     */
    fun decodeSampled(bytes: ByteArray, maxDim: Int, lowColor: Boolean = false): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options(bounds, maxDim, lowColor))
    }

    /** [decodeSampled] for a file path — the bounds probe reads only the header. */
    fun decodeSampledFile(path: String, maxDim: Int, lowColor: Boolean = false): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        return BitmapFactory.decodeFile(path, options(bounds, maxDim, lowColor))
    }

    private fun options(bounds: BitmapFactory.Options, maxDim: Int, lowColor: Boolean) =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            if (lowColor) inPreferredConfig = Bitmap.Config.RGB_565
        }
}
