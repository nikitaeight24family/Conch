package ai.eight24family.conch.ui.components

import ai.eight24family.conch.util.AudioWaveform
import ai.eight24family.conch.util.SilentlyTry
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * Voice-message player: play/pause, a waveform you can scrub, elapsed and size.
 *
 * Deliberately built on MediaPlayer rather than anything heavier — one short
 * local file, no streaming, no playlist. The whole point is that a recorded note
 * is checkable BEFORE it is sent, and listenable after.
 *
 * Only ONE player sounds at a time: starting this one stops whatever else was
 * playing, because two overlapping voice notes are noise, not a feature.
 */
private object VoicePlayback {
    @Volatile var current: MediaPlayer? = null
    @Volatile var currentKey: String? = null

    fun stopOthers(key: String) {
        if (currentKey != null && currentKey != key) {
            SilentlyTry.fired("SshAi-Audio", "stop other player") { current?.release() }
            current = null
            currentKey = null
        }
    }

    fun claim(key: String, player: MediaPlayer) {
        stopOthers(key)
        current = player
        currentKey = key
    }

    fun releaseIfMine(key: String) {
        if (currentKey == key) { current = null; currentKey = null }
    }
}

@Composable
fun VoicePlayer(
    file: File,
    sizeBytes: Long,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val key = remember(file.absolutePath) { file.absolutePath }

    var player by remember(key) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(key) { mutableStateOf(false) }
    var durationMs by remember(key) { mutableStateOf(0) }
    var positionMs by remember(key) { mutableStateOf(0) }
    var bars by remember(key) { mutableStateOf(AudioWaveform.peek(file)) }

    // Decode the envelope once, off the main thread.
    LaunchedEffect(key) { if (bars == null) bars = AudioWaveform.of(file) }

    // Duration without starting playback, so the row shows a real length before
    // the user ever presses play.
    LaunchedEffect(key) {
        if (durationMs == 0) {
            durationMs = SilentlyTry.loggedOrElse("SshAi-Audio", "probe duration", 0) {
                // NOT `.use {}` — MediaMetadataRetriever only became AutoCloseable
                // in API 29 and this app ships to 26.
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toIntOrNull() ?: 0
                } finally {
                    SilentlyTry.fired("SshAi-Audio", "release retriever") { r.release() }
                }
            }
        }
    }

    // Tick the position while playing.
    LaunchedEffect(playing) {
        while (playing) {
            kotlinx.coroutines.delay(60)
            val p = player ?: break
            positionMs = SilentlyTry.loggedOrElse("SshAi-Audio", "read position", positionMs) {
                p.currentPosition
            }
        }
    }

    DisposableEffect(key) {
        onDispose {
            SilentlyTry.fired("SshAi-Audio", "release player") { player?.release() }
            VoicePlayback.releaseIfMine(key)
        }
    }

    fun ensurePlayer(): MediaPlayer? = player ?: SilentlyTry.logged("SshAi-Audio", "open player") {
        // Build it OUTSIDE the apply so a throwing prepare() can still release
        // the native handle. `MediaPlayer().apply { prepare() }` leaks one per
        // failed attempt — visible in logcat as a stream of
        // "MediaPlayer finalized without being released".
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener {
                playing = false
                positionMs = 0
                SilentlyTry.fired("SshAi-Audio", "rewind") { it.seekTo(0) }
            }
            mp
        } catch (t: Throwable) {
            SilentlyTry.fired("SshAi-Audio", "release failed player") { mp.release() }
            throw t
        }
    }?.also {
        player = it
        if (durationMs == 0) durationMs = it.duration
    }

    fun seekToFraction(f: Float) {
        val d = durationMs
        if (d <= 0) return
        val target = (f.coerceIn(0f, 1f) * d).toInt()
        positionMs = target
        SilentlyTry.fired("SshAi-Audio", "seek") { player?.seekTo(target) }
    }

    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(cyan)
                .clickable {
                    val p = ensurePlayer() ?: return@clickable
                    if (playing) {
                        SilentlyTry.fired("SshAi-Audio", "pause") { p.pause() }
                        playing = false
                    } else {
                        VoicePlayback.claim(key, p)
                        SilentlyTry.fired("SshAi-Audio", "play") { p.start() }
                        playing = true
                    }
                },
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "pause" else "play",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Waveform(
                bars = bars,
                progress = progress,
                played = cyan,
                unplayed = outline,
                onScrub = { f ->
                    // Scrubbing before the first play still has to work — open
                    // the player lazily so the bar is not dead until pressed.
                    if (player == null) ensurePlayer()
                    seekToFraction(f)
                },
                modifier = Modifier.fillMaxWidth().height(32.dp),
            )
            Text(
                buildString {
                    append(mmss(if (playing || positionMs > 0) positionMs else durationMs))
                    if (sizeBytes > 0) append(", ").append(prettySize(sizeBytes))
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = outline,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "remove", tint = outline)
            }
        }
    }
}

/**
 * The bars. Played portion in the accent colour, the rest dim — the same
 * read-at-a-glance cue every voice message in every messenger uses.
 *
 * Tap anywhere to jump there; drag to scrub continuously.
 */
@Composable
private fun Waveform(
    bars: FloatArray?,
    progress: Float,
    played: androidx.compose.ui.graphics.Color,
    unplayed: androidx.compose.ui.graphics.Color,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Placeholder while the decode is in flight, so the row never jumps height.
    val data = bars ?: FloatArray(AudioWaveform.BUCKETS) { 0.15f }
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { off -> onScrub(off.x / size.width.toFloat()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onScrub(change.position.x / size.width.toFloat())
                }
            },
    ) {
        val n = data.size
        if (n == 0) return@Canvas
        val slot = size.width / n
        val barW = (slot * 0.55f).coerceAtLeast(1.5f)
        val mid = size.height / 2f
        val playedUntil = progress * n
        for (i in 0 until n) {
            val h = (data[i] * size.height).coerceAtLeast(2f)
            val x = i * slot + slot / 2f
            drawLine(
                color = if (i < playedUntil) played else unplayed,
                start = Offset(x, mid - h / 2f),
                end = Offset(x, mid + h / 2f),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun mmss(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun prettySize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
