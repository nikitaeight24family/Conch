package ai.eight24family.conch.linux.voice

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.linux.LocalLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Voice into text, on this phone, with nothing listening but this phone.
 *
 * ⛔ WHY A SECOND BINARY AT ALL. The shipped `llama-server` has no audio
 * input: the word "audio" does not appear anywhere in its own `--help` on
 * this build, so the multimodal door that carries images (mtmd) carries no
 * sound. Voice therefore needs whisper.cpp, which is a different project with
 * its own ggml — cross-built for arm64 with
 * `tools/build-whisper-android.sh` and shipped as `libwhisper-cli.so` in
 * `jniLibs` (the same W^X door the inference engine uses: a library in the
 * APK is extracted to `nativeLibraryDir`, which is the one executable place
 * an app has).
 *
 * ⛔ STATIC, ON PURPOSE. whisper.cpp vendors its own copy of ggml, and a
 * shared build would drop `libggml-base.so` / `libggml-cpu.so` into jniLibs
 * beside llama.cpp's — same names, different builds, and whichever the linker
 * picked first would be a coin toss. One self-contained 2.3 MB binary has no
 * such argument.
 *
 * ── ONE SHOT, NOT A SERVER ──
 *
 * A transcription is a batch job with a beginning and an end, so this runs
 * the CLI and reads its stdout: no port, no lifecycle, no model resident
 * between recordings. Measured on the owner's phone (base, Q5_1, 4 threads):
 * 11 seconds of speech in 3.8 s, transcript exact.
 *
 * The alternative — Android's own `SpeechRecognizer` — was not taken: it is
 * Google's service, and on most devices it is a network call. This app's
 * whole claim is that nothing leaves the phone.
 */
object LocalVoice {

    private const val TAG = "Conch-Voice"

    sealed interface State {
        data object Idle : State
        /** Recording; [seconds] is how long the mic has been open. */
        data class Recording(val seconds: Int) : State
        data object Transcribing : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun setState(s: State) { _state.value = s }

    sealed interface Result {
        data class Ok(val text: String) : Result
        /** No voice model downloaded — the caller offers to get one. */
        data object NoModel : Result
        data class Failed(val reason: String) : Result
    }

    /** The voice model on this phone, if one is downloaded. */
    fun model(): LocalLlm.Model? =
        LocalLlm.CATALOG.firstOrNull { it.voice && LocalLlm.isReady(it) }

    private fun binary(): File =
        File(ServiceLocator.appContext.applicationInfo.nativeLibraryDir, "libwhisper-cli.so")

    fun available(): Boolean = binary().exists()

    /**
     * Transcribe a 16 kHz mono WAV ([VoiceCapture] writes exactly that).
     *
     * `-l auto` because the owner writes Russian and English in the same
     * breath and a pinned language would mangle one of them; `-nt` and
     * `--no-prints` because stdout IS the return value here, and whisper's
     * default timing chatter would land in the composer.
     */
    suspend fun transcribe(wav: File): Result = withContext(Dispatchers.IO) {
        val m = model() ?: return@withContext Result.NoModel
        val bin = binary()
        if (!bin.exists()) return@withContext Result.Failed("no voice engine for this cpu (arm64 only)")
        if (!wav.isFile || wav.length() < 1_000) {
            return@withContext Result.Failed("that recording is empty")
        }
        _state.value = State.Transcribing
        try {
            val p = ProcessBuilder(
                bin.absolutePath,
                "-m", LocalLlm.fileOf(m).absolutePath,
                "-f", wav.absolutePath,
                "-l", "auto",
                "-nt",
                "--no-prints",
                // Four threads: the same number the chat engine uses on CPU,
                // and this runs while nothing else is generating.
                "-t", "4",
            )
                // stderr kept OUT of stdout: stdout is the transcript.
                .redirectErrorStream(false)
                .start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val err = p.errorStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            if (code != 0) {
                android.util.Log.w(TAG, "whisper exited $code: ${err.take(300)}")
                return@withContext Result.Failed(
                    err.lineSequence().lastOrNull { it.isNotBlank() }?.take(160)
                        ?: "the voice engine failed",
                )
            }
            val text = out.trim()
            if (text.isEmpty()) return@withContext Result.Failed("nothing was said")
            android.util.Log.i(TAG, "transcribed ${wav.length() / 32_000}s of audio, ${text.length} chars")
            Result.Ok(text)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "transcription failed: ${t.message}", t)
            Result.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            _state.value = State.Idle
            // The recording is the owner's voice: it exists to become text and
            // then it is gone.
            runCatching { wav.delete() }
        }
    }
}
