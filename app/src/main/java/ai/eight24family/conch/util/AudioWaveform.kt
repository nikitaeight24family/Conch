package ai.eight24family.conch.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Amplitude envelope of an audio file — the bars behind a voice-message player.
 *
 * Decoded from the file itself rather than sampled while recording, so the SAME
 * code draws a note the user just recorded and any audio they attach or receive.
 * Decoding is the only way to know what a file we did not create sounds like.
 *
 * Cost is small and paid once: a minute of AAC decodes in well under a second on
 * a phone, and the result is a few hundred floats cached by path + mtime + size,
 * so a recomposition or a scroll never re-decodes.
 */
object AudioWaveform {

    private const val TAG = "SshAi-Audio"

    /** How many bars a player draws. Enough to look like speech, few enough to
     *  stay legible on a phone-width row. */
    const val BUCKETS = 64

    /** key = path|mtime|size — a file rewritten in place must not reuse bars. */
    private val cache = ConcurrentHashMap<String, FloatArray>()

    private fun keyOf(f: File) = "${f.absolutePath}|${f.lastModified()}|${f.length()}"

    /** Cached bars, or null when this file has not been decoded yet. */
    fun peek(file: File): FloatArray? = cache[keyOf(file)]

    /**
     * Amplitude per bucket, each 0f..1f, normalised so the loudest bucket is 1.
     *
     * Returns a flat-ish placeholder rather than null when the file cannot be
     * decoded: a player with no bars looks broken, and the user still needs the
     * play button. Never throws.
     */
    suspend fun of(file: File, buckets: Int = BUCKETS): FloatArray = withContext(Dispatchers.IO) {
        val key = keyOf(file)
        cache[key]?.let { return@withContext it }
        val bars = SilentlyTry.loggedOrElse(TAG, "decode waveform", null) { decode(file, buckets) }
            ?: FloatArray(buckets) { 0.18f }
        cache[key] = bars
        bars
    }

    /**
     * Decode to PCM and reduce to per-bucket RMS.
     *
     * RMS rather than peak: peak makes every bar of speech look identical because
     * a single click dominates the bucket, which is exactly the flat "barcode"
     * that reads as broken. RMS tracks loudness the way an ear does.
     */
    private fun decode(file: File, buckets: Int): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Sum of squares per bucket, filled as PCM comes out. The total sample
            // count is not known up front for a VBR file, so bucket by TIME using
            // the container's duration when it has one, and fall back to growing a
            // list when it does not.
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val sums = DoubleArray(buckets)
            val counts = LongArray(buckets)
            val loose = ArrayList<Float>(4096)

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: continue
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIdx)
                        if (out != null) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val shorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val bucket = if (durationUs > 0)
                                ((info.presentationTimeUs.toDouble() / durationUs) * buckets)
                                    .toInt().coerceIn(0, buckets - 1)
                            else -1
                            accumulate(shorts, bucket, sums, counts, loose)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
            }

            val raw = if (durationUs > 0) {
                FloatArray(buckets) { i ->
                    if (counts[i] == 0L) 0f else kotlin.math.sqrt(sums[i] / counts[i]).toFloat()
                }
            } else {
                // No duration in the container — resample whatever we collected.
                if (loose.isEmpty()) return null
                FloatArray(buckets) { i ->
                    val from = (i.toLong() * loose.size / buckets).toInt()
                    val to = (((i + 1).toLong() * loose.size / buckets).toInt()).coerceAtLeast(from + 1)
                    var acc = 0f
                    var n = 0
                    for (k in from until minOf(to, loose.size)) { acc += loose[k]; n++ }
                    if (n == 0) 0f else acc / n
                }
            }
            return normalise(raw)
        } finally {
            SilentlyTry.fired(TAG, "stop waveform codec") { codec?.stop() }
            SilentlyTry.fired(TAG, "release waveform codec") { codec?.release() }
            SilentlyTry.fired(TAG, "release extractor") { extractor.release() }
        }
    }

    private fun accumulate(
        shorts: java.nio.ShortBuffer,
        bucket: Int,
        sums: DoubleArray,
        counts: LongArray,
        loose: ArrayList<Float>,
    ) {
        // Stride: a bar does not need every sample, and stepping keeps a long
        // recording from turning into millions of iterations.
        val stride = 16
        var i = 0
        var acc = 0.0
        var n = 0
        while (i < shorts.limit()) {
            val v = shorts.get(i) / 32768.0
            acc += v * v
            n++
            i += stride
        }
        if (n == 0) return
        if (bucket >= 0) {
            sums[bucket] += acc
            counts[bucket] += n
        } else {
            loose += kotlin.math.sqrt(acc / n).toFloat()
        }
    }

    /**
     * Scale so the loudest bar is full height, with a visible floor.
     *
     * Without the floor, silence between words collapses to nothing and the row
     * reads as a broken image rather than a quiet passage.
     */
    private fun normalise(raw: FloatArray): FloatArray {
        val max = raw.maxOrNull() ?: 0f
        if (max <= 0f) return FloatArray(raw.size) { 0.18f }
        return FloatArray(raw.size) { i -> (raw[i] / max).coerceIn(0.08f, 1f) }
    }
}
