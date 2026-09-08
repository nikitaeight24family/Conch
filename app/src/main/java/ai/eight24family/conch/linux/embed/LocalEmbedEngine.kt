package ai.eight24family.conch.linux.embed

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.PhoneResources
import ai.eight24family.conch.linux.chat.LocalApiAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A SECOND engine, for embeddings.
 *
 * ⛔ IT HAS TO BE A SECOND PROCESS. `llama-server`'s own help says it:
 * `--embeddings` "restrict to only support embedding use case; use only with
 * dedicated embedding models". An embedder is a different model with a
 * different head, and turning the flag on would stop the serving chat model
 * from generating. So embeddings get their own port, their own small model
 * and their own lifecycle — and the chat model keeps the GPU and stays loaded
 * while a search runs.
 *
 * ── WHAT IT IS FOR ──
 *
 * This is the runtime under "find that conversation where we fixed the
 * bricked phone" — semantic search over the chats already on this device
 * ([ChatIndex]) — and under whatever retrieval comes next. Nothing here talks
 * to a network: the model is a file the owner downloaded, the vectors live in
 * app storage, and the port is on loopback behind the same key the chat
 * engine uses (see [LocalApiAccess] — a second port is a second door, and it
 * gets the same lock).
 *
 * ── WHY CPU, AND WHY IT MAY SAY NO ──
 *
 * `-ngl` is deliberately absent: the phone has ONE GPU and the chat model is
 * usually on it, so an indexing pass must not fight the thing the owner is
 * talking to. bge-m3 on 2 CPU threads is fast enough for a chat archive
 * (measured on the owner's phone: ~40 short messages a second).
 *
 * And it refuses rather than swapping: if free ram cannot hold the embedder
 * on top of what is already resident, [ensureUp] says so instead of
 * unloading the model the owner is using. A search is not worth someone's
 * conversation.
 */
object LocalEmbedEngine {

    private const val TAG = "Conch-Embed"

    /** One port up from the chat engine, loopback only, same reasoning. */
    const val PORT = 8318
    const val BASE_URL = "http://127.0.0.1:$PORT"

    /**
     * ⛔ AN EMBEDDING INPUT MUST FIT ONE PHYSICAL BATCH. There is no chunked
     * prefill for a non-causal model, so the server refuses outright rather
     * than splitting - measured on the owner's first indexing pass:
     *
     *     input (517 tokens) is too large to process.
     *     increase the physical batch size
     *
     * at the defaults (-c 512, -ub 512). So the window, the logical batch and
     * the PHYSICAL batch are all set to the same number, and the chunker
     * ([ChatIndex]) keeps every unit comfortably under it. 1024 costs ~100 MB
     * of KV on bge-m3, which is the right end of the trade for a phone;
     * bge-m3 would take 8192 and cost eight times that.
     */
    private const val CTX = 1024

    /** Freed after this long unused. Shorter patience than the chat engine's
     *  two minutes would re-load it inside one search session; longer wastes
     *  ram nobody is asking for. */
    private const val IDLE_STOP_MS = 300_000L

    /** What the process needs on top of the weights: no prefill spike of a
     *  chat model, so nothing like the chat engine's compute budget. */
    private const val OVERHEAD_BYTES = 300_000_000L

    sealed interface State {
        data object Off : State
        data object Starting : State
        data class Up(val modelId: String) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()
    @Volatile private var proc: Process? = null
    @Volatile private var lastUsedMs = 0L
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )
    @Volatile private var watch: kotlinx.coroutines.Job? = null

    private fun launcher(): File =
        File(ServiceLocator.appContext.applicationInfo.nativeLibraryDir, "libllama-server.so")

    private fun logFile(): File =
        File(ServiceLocator.appContext.filesDir, "llm/embed-engine.log").apply { parentFile?.mkdirs() }

    /** The embedding model on this phone, if one is downloaded. */
    fun model(): LocalLlm.Model? =
        LocalLlm.CATALOG.firstOrNull { it.embedder && LocalLlm.isReady(it) }

    fun ramNeeded(m: LocalLlm.Model): Long = m.bytes + OVERHEAD_BYTES

    sealed interface Ready {
        data class Ok(val modelId: String) : Ready
        /** No embedding model downloaded — the caller offers to get one. */
        data object NoModel : Ready
        data class Failed(val reason: String) : Ready
    }

    /**
     * Bring the embedder up, or explain why not. Idempotent and cheap when it
     * is already serving.
     */
    suspend fun ensureUp(): Ready = withContext(Dispatchers.IO) {
        val m = model() ?: return@withContext Ready.NoModel
        lock.withLock {
            if ((_state.value as? State.Up)?.modelId == m.id && proc?.isAlive == true && healthOk()) {
                lastUsedMs = System.currentTimeMillis()
                return@withLock Ready.Ok(m.id)
            }
            stopLocked()
            val bin = launcher()
            if (!bin.exists()) return@withLock Ready.Failed("no engine for this cpu (arm64 only)")
            // Refuse rather than evict: the chat model may be mid-answer.
            val free = PhoneResources.read().ramFreeBytes
            val need = ramNeeded(m)
            if (free < need) {
                val short = (need - free) / 1_000_000
                return@withLock Ready.Failed("needs ${short}MB more free memory right now")
            }
            _state.value = State.Starting
            val args = listOf(
                bin.absolutePath,
                "-m", LocalLlm.fileOf(m).absolutePath,
                "--host", "127.0.0.1",
                "--port", "$PORT",
                "--embeddings",
                "-c", "$CTX",
                "-b", "$CTX",
                "-ub", "$CTX",
                "-np", "1",
                "-t", "2",
                "--no-webui",
                // The same door needs the same lock: this port is reachable by
                // every app on the device exactly like the chat one.
                "--api-key-file", LocalApiAccess.writeKeyFile().absolutePath,
                // No prompt cache for a stateless embedder.
                "--cache-ram", "0",
            )
            val p = runCatching {
                ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile())
                    .apply { environment()["LD_LIBRARY_PATH"] = bin.parent!! }
                    .start()
            }.getOrElse {
                _state.value = State.Failed(it.message ?: "could not start")
                return@withLock Ready.Failed(it.message ?: "could not start")
            }
            proc = p
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) break
                if (healthOk()) {
                    _state.value = State.Up(m.id)
                    lastUsedMs = System.currentTimeMillis()
                    android.util.Log.i(TAG, "embedder up on $BASE_URL (${m.label})")
                    startWatch()
                    return@withLock Ready.Ok(m.id)
                }
                delay(300)
            }
            val tail = runCatching {
                logFile().readText().lineSequence().map { it.trim() }
                    .lastOrNull { it.isNotEmpty() && !it.startsWith("[clshim]") }
            }.getOrNull()
            stopLocked()
            val why = tail?.take(160) ?: "the embedder did not come up"
            _state.value = State.Failed(why)
            android.util.Log.w(TAG, "embedder failed: $why")
            Ready.Failed(why)
        }
    }

    fun touch() { lastUsedMs = System.currentTimeMillis() }

    suspend fun stop() = withContext(Dispatchers.IO) { lock.withLock { stopLocked() } }

    private fun stopLocked() {
        watch?.cancel(); watch = null
        proc?.let { p ->
            runCatching { p.destroy() }
            runCatching { p.waitFor() }
        }
        proc = null
        if (_state.value !is State.Off) android.util.Log.i(TAG, "embedder stopped")
        _state.value = State.Off
    }

    /** Frees the weights when nobody has embedded anything for a while. */
    private fun startWatch() {
        watch?.cancel()
        watch = scope.launch {
            while (true) {
                delay(10_000)
                if (_state.value !is State.Up) return@launch
                val p = proc ?: return@launch
                if (!p.isAlive) {
                    android.util.Log.w(TAG, "embedder died")
                    _state.value = State.Off
                    proc = null
                    return@launch
                }
                if (System.currentTimeMillis() - lastUsedMs > IDLE_STOP_MS) {
                    android.util.Log.i(TAG, "embedder idle — freeing its ram")
                    stop()
                    return@launch
                }
            }
        }
    }

    private fun healthOk(): Boolean = runCatching {
        val c = URL("$BASE_URL/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1_000
        c.readTimeout = 1_000
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    }.getOrDefault(false)
}
