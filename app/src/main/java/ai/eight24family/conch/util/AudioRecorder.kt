package ai.eight24family.conch.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

/**
 * Microphone capture, shared by the two things that need it: a voice message the
 * user records in the composer, and the `audio` verb the server-side agent can
 * call through conch-bridge.
 *
 * AAC in an MP4 container — the format every phone can encode in hardware and
 * every desktop and CLI can play without extra codecs. A minute of speech is
 * roughly 500 KB at 64 kbps mono, which matters because a voice message crosses
 * the user's SSH link and a bridge capture crosses it twice.
 *
 * ⚠ A microphone is the most sensitive thing in this app. Two rules hold
 * everywhere it is used:
 *  - recording is ALWAYS started by an explicit action, never speculatively;
 *  - the recorder is released the moment it stops, so nothing holds the mic open
 *    in the background. Android would show the mic indicator anyway; the point is
 *    that it should never have reason to.
 */
object AudioRecorder {

    private const val TAG = "SshAi-Audio"

    /** Sane ceiling for a single capture. A runaway recorder is both a privacy
     *  problem and a way to fill the cache. */
    const val MAX_SECONDS = 300

    fun micGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Directory for captures. Inside cacheDir so the OS can reclaim it and so
     *  nothing lands in shared storage. */
    fun dir(ctx: Context): File = File(ctx.cacheDir, "conch_audio").apply { mkdirs() }

    /**
     * A capture in progress. [stop] is idempotent and returns the file, or null
     * when nothing usable was written (a permission yanked mid-record, a codec
     * that refused, a stop so fast the encoder produced no frames).
     */
    class Session internal constructor(
        private val recorder: MediaRecorder,
        val file: File,
        val startedAtMs: Long,
    ) {
        @Volatile private var stopped = false

        fun stop(): File? {
            if (stopped) return file.takeIf { it.length() > 0 }
            stopped = true
            // stop() throws when the encoder never got a frame — a tap so short
            // there is no audio. That is not an error worth surfacing; it just
            // means there is no voice message.
            SilentlyTry.fired(TAG, "stop recorder") { recorder.stop() }
            SilentlyTry.fired(TAG, "release recorder") { recorder.release() }
            if (file.length() <= 0L) {
                SilentlyTry.fired(TAG, "delete empty capture") { file.delete() }
                return null
            }
            return file
        }

        /** Abandon: stop and delete. For a cancelled voice message. */
        fun discard() {
            stop()
            SilentlyTry.fired(TAG, "discard capture") { file.delete() }
        }
    }

    /**
     * Start recording. Returns null when the mic isn't granted or the encoder
     * refuses — callers must handle that rather than assume a session.
     */
    fun start(ctx: Context, namePrefix: String): Session? {
        if (!micGranted(ctx)) {
            android.util.Log.w(TAG, "start refused: RECORD_AUDIO not granted")
            return null
        }
        val file = File(dir(ctx), "${namePrefix}_${System.currentTimeMillis()}.m4a")
        return SilentlyTry.logged(TAG, "start recorder") {
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
            else MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Mono 44.1 kHz at 64 kbps: speech-grade, ~8 KB/s on the wire.
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(44_100)
            rec.setAudioEncodingBitRate(64_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            Session(rec, file, System.currentTimeMillis())
        } ?: run {
            SilentlyTry.fired(TAG, "delete unstarted capture") { file.delete() }
            null
        }
    }

    /**
     * Record for a fixed duration and return the bytes. Used by the bridge, where
     * there is nobody to press stop.
     *
     * Null when the mic isn't available or nothing was captured.
     */
    suspend fun recordFor(ctx: Context, seconds: Int, namePrefix: String = "bridge"): ByteArray? {
        val capped = seconds.coerceIn(1, MAX_SECONDS)
        val session = start(ctx, namePrefix) ?: return null
        delay(capped * 1000L)
        val file = session.stop() ?: return null
        val bytes = SilentlyTry.logged(TAG, "read capture") { file.readBytes() }
        SilentlyTry.fired(TAG, "delete capture after read") { file.delete() }
        return bytes
    }

    /** Sweep captures older than a day — a process death mid-record would
     *  otherwise leave audio in the cache indefinitely. */
    fun sweepOld(ctx: Context) {
        SilentlyTry.fired(TAG, "sweep old captures") {
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dir(ctx).listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        }
    }
}
