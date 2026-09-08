package ai.eight24family.conch.linux.voice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile

/**
 * The microphone, written straight to the format whisper eats.
 *
 * ⛔ WHY NOT [ai.eight24family.conch.util.AudioRecorder]. That one exists to
 * make a voice MESSAGE — AAC in an .m4a, 44.1 kHz, sized for an SSH channel —
 * and whisper.cpp reads none of that: it wants 16 kHz mono PCM and has no
 * decoder for anything else. Transcoding an m4a on the phone would mean
 * MediaExtractor + MediaCodec + a resampler to arrive at what `AudioRecord`
 * hands over for free. So this records the destination format directly, and
 * the two recorders stay honest about serving two different purposes.
 *
 * 16 kHz mono 16-bit is 32 KB per second — a two-minute dictation is 3.8 MB
 * in the cache, deleted the moment it has become text
 * ([LocalVoice.transcribe] does that in its `finally`).
 */
object VoiceCapture {

    private const val TAG = "Conch-Voice"

    /** Whisper's own sample rate. Anything else has to be resampled, and the
     *  resampling would be ours to get wrong. */
    const val SAMPLE_RATE = 16_000

    /** A dictation, not a podcast. Two minutes is 3.8 MB of PCM and about
     *  25 s of transcription on this phone's CPU. */
    const val MAX_SECONDS = 120

    fun micGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun dir(ctx: Context): File =
        File(ctx.cacheDir, "conch_voice").apply { mkdirs() }

    class Session internal constructor(
        private val recorder: AudioRecord,
        private val thread: Thread,
        val file: File,
        val startedAtMs: Long,
    ) {
        @Volatile internal var stopFlag = false
        @Volatile private var finished = false

        /** Stop, finish the WAV header, and hand back the file (null when
         *  nothing usable was captured). */
        fun stop(): File? {
            if (finished) return file.takeIf { it.length() > 44 }
            finished = true
            stopFlag = true
            runCatching { thread.join(2_000) }
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            val ok = runCatching { patchWavHeader(file) }.getOrDefault(false)
            if (!ok || file.length() <= 44) {
                runCatching { file.delete() }
                return null
            }
            return file
        }

        fun discard() {
            stop()
            runCatching { file.delete() }
        }
    }

    /**
     * Open the mic and start writing a WAV. Returns null when the permission
     * is missing or the platform refuses the configuration — the caller must
     * handle that rather than assume a session.
     */
    fun start(ctx: Context): Session? {
        if (!micGranted(ctx)) {
            android.util.Log.w(TAG, "refused: RECORD_AUDIO not granted")
            return null
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            android.util.Log.w(TAG, "platform refused 16 kHz mono PCM")
            return null
        }
        val bufSize = maxOf(minBuf, SAMPLE_RATE) // ~0.5 s of slack
        val rec = runCatching {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            android.util.Log.w(TAG, "recorder did not initialise")
            return null
        }
        val file = File(dir(ctx), "voice_${System.currentTimeMillis()}.wav")
        runCatching { writeWavHeader(file) }.onFailure {
            rec.release()
            return null
        }
        rec.startRecording()
        lateinit var session: Session
        val t = Thread {
            val buf = ByteArray(bufSize)
            val maxBytes = MAX_SECONDS * SAMPLE_RATE * 2
            file.outputStream().use { out ->
                // Append after the 44-byte header we just wrote.
                out.channel.position(44)
                var written = 0
                while (!session.stopFlag && written < maxBytes) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                }
            }
        }
        session = Session(rec, t, file, System.currentTimeMillis())
        t.isDaemon = true
        t.start()
        return session
    }

    /** A 44-byte canonical WAV header with zero lengths, patched on stop. */
    private fun writeWavHeader(f: File) {
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            r.write("RIFF".toByteArray())
            r.write(le32(0))                       // size, patched later
            r.write("WAVE".toByteArray())
            r.write("fmt ".toByteArray())
            r.write(le32(16))                      // PCM header size
            r.write(le16(1))                       // PCM
            r.write(le16(1))                       // mono
            r.write(le32(SAMPLE_RATE))
            r.write(le32(SAMPLE_RATE * 2))         // byte rate
            r.write(le16(2))                       // block align
            r.write(le16(16))                      // bits
            r.write("data".toByteArray())
            r.write(le32(0))                       // data size, patched later
        }
    }

    /** Fill in the two lengths now that the recording has one. */
    private fun patchWavHeader(f: File): Boolean {
        val total = f.length()
        if (total <= 44) return false
        val dataLen = (total - 44).toInt()
        RandomAccessFile(f, "rw").use { r ->
            r.seek(4); r.write(le32(dataLen + 36))
            r.seek(40); r.write(le32(dataLen))
        }
        return true
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    private fun le32(v: Int) = ByteArray(4) { ((v shr (8 * it)) and 0xff).toByte() }
}
