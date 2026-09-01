package ai.eight24family.conch.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Downscale a photo destined for the PHONE's own model. A 12 MP camera shot
 * explodes into thousands of vision tokens once the CLIP tiler is done with
 * it — the owner's box photo turned one question into a 13.4K-token prefill
 * that crawled for minutes and read as a hang (2026-09-01). ~1.4 MP keeps
 * every label on a product box readable while cutting the token bill and the
 * encoder time several-fold.
 *
 * Remote servers are deliberately NOT shrunk: cloud agents have their own
 * preprocessing budgets, and the file on the server is also read by tools.
 */
object LocalImageShrink {

    private const val MAX_PIXELS = 1_400_000L

    /** Returns JPEG bytes at ≤[MAX_PIXELS], or [src] unchanged when it is
     *  already small, undecodable, or the re-encode failed to save anything. */
    fun shrink(src: ByteArray): ByteArray = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        val w = bounds.outWidth.toLong()
        val h = bounds.outHeight.toLong()
        if (w <= 0 || h <= 0 || w * h <= MAX_PIXELS) return src
        // Coarse power-of-two subsample first (cheap, memory-bound), then one
        // exact scale to the target budget.
        var sample = 1
        while ((w / sample) * (h / sample) > MAX_PIXELS * 4) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            src, 0, src.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return src
        val scale = kotlin.math.sqrt(
            MAX_PIXELS.toDouble() / (decoded.width.toLong() * decoded.height),
        ).coerceAtMost(1.0)
        val bmp = if (scale < 0.995) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else decoded
        val out = java.io.ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (bmp !== decoded) decoded.recycle()
        bmp.recycle()
        val jpeg = out.toByteArray()
        if (ok && jpeg.isNotEmpty() && jpeg.size < src.size) jpeg else src
    }.getOrElse { src }
}
