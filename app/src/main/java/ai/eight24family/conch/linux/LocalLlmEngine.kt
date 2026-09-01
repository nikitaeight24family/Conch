package ai.eight24family.conch.linux

import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The inference engine: llama.cpp's `llama-server`, run as a child process of
 * this app, serving the OpenAI-compatible API on loopback.
 *
 * ── HOW AN APP GETS TO RUN A BINARY AT ALL ──
 *
 * W^X (target 29+) forbids executing anything an app wrote to disk, and Play
 * forbids downloading executable code in the first place. The one door left
 * open is the one this uses: libraries shipped in the APK's `jniLibs` are
 * extracted to `nativeLibraryDir` (see `useLegacyPackaging` in the build
 * script), which is executable — so the launcher is packaged as
 * `libllama-server.so` and exec'd from there, with `LD_LIBRARY_PATH` pointing
 * back at the same directory for its dlopen chain (impl, common, ggml, and
 * the per-CPU ggml variants it picks from at runtime).
 *
 * Verified on-device before it was packaged (2026-08-31): the renamed
 * launcher resolves its tool library, `/health` answers ok, and
 * `/v1/chat/completions` completes against gemma-3-1b.
 *
 * ── LOOPBACK ONLY, ONE MODEL AT A TIME ──
 *
 * `--host 127.0.0.1` — a phone must never grow a LAN-visible inference port.
 * Starting a different model stops the current one first: two 2-GB models
 * resident at once is how a 16 GB phone starts killing apps.
 *
 * The process is a child of the app's process group: when Android kills the
 * app, the engine dies with it. Nothing keeps running that the user cannot
 * see.
 */
object LocalLlmEngine {

    const val PORT = 8317
    const val BASE_URL = "http://127.0.0.1:$PORT"

    /** The engine's context window (-c). 8K died in the field: codex's system
     *  prompt is ~7.1K, so ONE working turn outgrew it and every later send
     *  bounced off `400 … exceeds context` (2026-09-01). 16K holds a real
     *  conversation; codex is told this number (minus reply headroom) via
     *  model_context_window so it compacts BEFORE the wall — the engine can't
     *  shift context itself (hybrid/recurrent models don't support it). */
    const val CTX_TOKENS = 16384

    private const val TAG = "Conch-LocalLlmEngine"

    sealed interface State {
        data object Off : State
        data class Starting(val modelId: String) : State
        /** [gpu] is the observed FACT (the engine's own log, not our intent):
         *  layers are offloaded to the phone's GPU via the OpenCL backend. */
        data class Up(val modelId: String, val gpu: Boolean = false) : State
        data class Failed(val modelId: String, val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()
    @Volatile private var proc: Process? = null
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )
    @Volatile private var watch: kotlinx.coroutines.Job? = null

    /** Missing on non-arm64 devices (the APK carries arm64 engines only) —
     *  the section says so instead of offering buttons that cannot work. */
    fun available(): Boolean = launcher().exists()

    /** The serving process's pid, for /proc-based telemetry — null when off.
     *  Reflection over the `pid` field: `Process.pid()` is not in android.jar,
     *  and Android's ProcessImpl has carried the int field since forever. */
    fun pid(): Int? = proc?.let { p ->
        runCatching {
            p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
        }.getOrNull()
    }

    private fun launcher(): File =
        File(ServiceLocator.appContext.applicationInfo.nativeLibraryDir, "libllama-server.so")

    private fun logFile(): File =
        File(ServiceLocator.appContext.filesDir, "llm/engine.log").apply { parentFile?.mkdirs() }

    // ── GPU (OpenCL) ──
    //
    // The APK carries ggml's OpenCL backend (libggml-opencl.so, a
    // GGML_BACKEND_DL plugin) plus a shim libOpenCL.so that satisfies the
    // plugin's DT_NEEDED and forwards every cl* call into the vendor's real
    // stack, loaded through the "sphal" linker namespace
    // (android_load_sphal_library — the same-process-HAL door every GL app
    // uses). Nothing else works from an exec'd child of an app: its default
    // namespace is ISOLATED (permitted_paths has /system and /data but not
    // /vendor/lib64, measured on Android 16), so plain DT_NEEDED/dlopen of
    // the vendor front is refused, symlinks are realpath()ed away, copying
    // the blobs into filesDir dies on W^X (targetSdk 29+), and putting all
    // of /vendor/lib64 on LD_LIBRARY_PATH shadows system libraries (a vendor
    // libbinder.so broke libbinder_ndk three libraries deep).
    //
    // On phones with no vendor CL at all the shim's clGetPlatformIDs reports
    // "no platforms", llama-server prints "no usable GPU found" and serves on
    // CPU — same behavior as before the backend existed.

    /** Vendor OpenCL front candidates. Adreno ships libOpenCL.so; Mali stacks
     *  on Samsung/MediaTek usually alias it or export CL from the GLES
     *  driver; PowerVR names its own. Presence is a cheap pre-gate: without
     *  any front the GPU attempt (and its retry-on-CPU cost) is skipped. */
    private val CL_FRONTS = listOf(
        "libOpenCL.so", "libOpenCL_adreno.so",
        "egl/libGLES_mali.so", "libGLES_mali.so", "libPVROCL.so",
    )

    private fun vendorClFrontExists(): Boolean =
        CL_FRONTS.any { File("/vendor/lib64", it).exists() }

    /** The store's device profile wears this as "gpu ✓": the offload DOOR
     *  exists on this phone. Whether it works is ModelRecords' measured fact. */
    fun gpuFrontPresent(): Boolean =
        File(ServiceLocator.appContext.applicationInfo.nativeLibraryDir, "libggml-opencl.so").exists() &&
            vendorClFrontExists()

    /** The engine's own verdict, from its log: llama-server prints
     *  "no usable GPU found" when -ngl was asked but no backend device came
     *  up. Absence of the line on a GPU launch = layers are on the GPU. */
    private fun logSaysNoGpu(): Boolean = runCatching {
        logFile().useLines { lines -> lines.any { it.contains("no usable GPU", ignoreCase = true) } }
    }.getOrDefault(false)

    /** Which chat template a model launches under. The default is the model's
     *  OWN GGUF template (null) — that is what makes a store download drive
     *  Codex out of the box, and the reasoning is measured on-device, not
     *  guessed (2026-09-01):
     *
     *  llama.cpp couples tool-calling to the template it recognises. Given a
     *  model's NATIVE template it injects that family's tools and uses the
     *  MATCHING tool parser — so Llama-3.2 fires a real `shell` tool call on its
     *  own template (measured), and every family llama.cpp supports (Llama,
     *  Mistral, Functionary, DeepSeek, Command-R, Hermes…) tool-calls the same.
     *  Impose a FOREIGN tool format instead and it breaks two ways, both seen on
     *  the owner's phone: a ChatML template on Gemma uses stop tokens not in
     *  Gemma's vocab so it never stops (endless fake dialogue); a Hermes
     *  `<tool_call>` template on Gemma makes llama.cpp build a peg parser for a
     *  format Gemma wasn't trained on, so the first plain reply fails to parse
     *  and the task is CANCELLED mid-stream ("stream disconnected"). A model
     *  whose native template has NO tool section (Gemma) simply chats without
     *  tool calls — clean, never a crash; that is the honest floor for it.
     *
     *  The ONE override: Qwen. Its native template hard-raises on Codex's
     *  non-first system message, and Qwen is trained on the Hermes `<tool_call>`
     *  format, so agent.jinja (ChatML + Hermes, transcript-tolerant) is BOTH
     *  what it needs and what it speaks — verified tool-calling. Add another
     *  override only after measuring that a family needs one AND that the
     *  substitute matches what the model was trained to emit. */
    private fun agentTemplateAssetFor(m: LocalLlm.Model): String? {
        val hay = (m.id + " " + m.file + " " + m.label + " " + (m.brandOrg ?: "")).lowercase()
        return if ("qwen" in hay) "llm/agent.jinja" else null
    }

    /** Materialize the chosen template (an APK asset) as a real file for
     *  --chat-template-file. Rewritten every launch: cheap, and an app update
     *  silently refreshes it. Null ⇒ no override ⇒ model's own GGUF template. */
    private fun templateFileFor(m: LocalLlm.Model): File? {
        val asset = m.templateAsset ?: agentTemplateAssetFor(m) ?: return null
        return runCatching {
            val out = File(ServiceLocator.appContext.filesDir, "llm/${asset.substringAfterLast('/')}")
            out.parentFile?.mkdirs()
            ServiceLocator.appContext.assets.open(asset).use { ins ->
                out.outputStream().use { ins.copyTo(it) }
            }
            out
        }.getOrNull()
    }

    private enum class LaunchOutcome { UP, FAIL, RETRY_CPU }

    /** Whether the CURRENT serving process runs with GPU offload — the
     *  watchdog restarts in the same mode, and demotes to CPU on a loop. */
    @Volatile private var servingGpu = false

    /** Whether the CURRENT serving process allows the model to reason —
     *  launch-time state (--reasoning-budget), so flipping it means a
     *  restart, and idempotency must compare it like the model id. */
    @Volatile private var servingThinking = false

    /** Whether the CURRENT serving process loaded the vision projector — a
     *  pack that finished downloading mid-serve must trigger a relaunch, or
     *  the model stays blind until something else restarts it. */
    @Volatile private var servingVision = false

    /** forceCpu of the last launch attempt, so watchdog restarts repeat the
     *  mode instead of re-trying the GPU that was just demoted away from. */
    @Volatile private var lastForcedCpu = false

    suspend fun start(m: LocalLlm.Model, thinking: Boolean = false, forceCpu: Boolean = false): Boolean =
        when (launch(m, thinking, forceCpu)) {
            LaunchOutcome.UP -> true
            LaunchOutcome.RETRY_CPU -> launch(m, thinking, forceCpu = true) == LaunchOutcome.UP
            LaunchOutcome.FAIL -> false
        }

    private suspend fun launch(m: LocalLlm.Model, thinking: Boolean, forceCpu: Boolean): LaunchOutcome =
        withContext(Dispatchers.IO) {
        lastForcedCpu = forceCpu
        lock.withLock {
            // Idempotent for the model already serving IN THE SAME MODE: the
            // chat calls this on entry, and restarting a healthy engine would
            // drop its warm KV cache and add a full weight reload for nothing.
            // A thinking flip is launch state — same model still restarts; so
            // does a vision pack that landed after this serve began.
            if ((_state.value as? State.Up)?.modelId == m.id && servingThinking == thinking &&
                servingVision == LocalLlm.hasVision(m) &&
                proc?.isAlive == true && healthOk()
            ) {
                return@withLock LaunchOutcome.UP
            }
            stopLocked()
            val bin = launcher()
            if (!bin.exists()) {
                _state.value = State.Failed(m.id, "no engine for this cpu (arm64 only)")
                return@withLock LaunchOutcome.FAIL
            }
            val model = LocalLlm.fileOf(m)
            if (!LocalLlm.isReady(m)) {
                // Wrong-sized weights would kill the server wordlessly mid-mmap
                // (a zero-byte file did exactly that, 2026-08-31) — refuse with
                // the reason instead.
                _state.value = State.Failed(m.id, "model file is incomplete — download it again")
                return@withLock LaunchOutcome.FAIL
            }
            // ⛔ NOT ENOUGH RAM → RECLAIM FIRST, automatically. The prefill
            // spike on top of weights+KV is the real OOM killer (the 4B died
            // with ~2.8 GB free), so free the system's cached processes — its
            // own safe kill — the moment we're short of what this model needs.
            // Best effort: no bridge → no-op, and we still try to launch (the
            // watchdog names an OOM if it strikes). Costs a ~1.5 s reclaim
            // only when memory is actually tight, never on a comfortable
            // launch.
            val need = LocalLlm.ramNeeded(m)
            if (PhoneResources.read().ramFreeBytes < need) {
                when (val o = RamReclaim.freeUp()) {
                    is RamReclaim.Outcome.Freed -> android.util.Log.i(
                        TAG,
                        "auto-reclaimed ${o.freedBytes / 1_000_000}MB before launch " +
                            "(${o.availAfter / 1_000_000}MB free) for ${m.id}",
                    )
                    RamReclaim.Outcome.BridgeDown -> android.util.Log.i(
                        TAG, "low ram for ${m.id} and no bridge to reclaim — launching anyway",
                    )
                }
            }
            // Android's phantom-process killer reaps CPU-heavy child processes
            // (32-process cap, "excessive CPU" verdicts) — an inference engine
            // is its natural prey. Where the phone bridge is armed, lift the
            // limits idempotently before every launch: they reset on reboot
            // and the platform can re-sync them back. Without the bridge this
            // silently does nothing, and the watchdog below at least NAMES the
            // killer when it strikes instead of a wordless dead port.
            ai.eight24family.conch.util.SilentlyTry.fired(TAG, "lift phantom-process limits") {
                ai.eight24family.conch.adb.LocalAdbShell.exec(
                    "device_config put activity_manager max_phantom_processes 2147483647; " +
                        "settings put global settings_enable_monitor_phantom_procs false",
                )
            }
            _state.value = State.Starting(m.id)
            val nativeDir = bin.parent!!
            // GPU offload: only when a vendor CL front exists AND the plugin
            // shipped in this APK (arm64). One flag, whole graph — measured on
            // Adreno 830 / Qwen3: prefill ×7.5 (1.7B) and ×16 (4B), the codex
            // first turn drops from ~13 min to ~1.5 min on the 4B. Long-context
            // generation is somewhat slower than CPU there, but agent turns are
            // prefill-dominated, so the trade wins end-to-end.
            val gpuPlugin = File(nativeDir, "libggml-opencl.so")
            val tryGpu = !forceCpu && gpuPlugin.exists() && vendorClFrontExists()
            val p = try {
                // --jinja: the model's own chat template, which is what makes
                // TOOL CALLING work — Codex sends tools, and without it the
                // server falls back to a template with no tool support.
                // CTX_TOKENS (16K) fits Codex's ~7K system prompt + a real
                // conversation; its KV share is priced into
                // LocalLlm.RAM_OVERHEAD_BYTES.
                val args = buildList {
                    add(bin.absolutePath)
                    add("-m"); add(model.absolutePath)
                    add("--host"); add("127.0.0.1")
                    add("--port"); add("$PORT")
                    // CPU threads follow where the math runs. On GPU launches
                    // the whole graph is offloaded and the CPU only tokenizes
                    // and samples — 2 threads suffice, and every idle core is
                    // heat NOT added to a SoC already running its GPU flat out
                    // (the owner's phone hit the system's "device too hot"
                    // overlay with 4+6 threads spinning under GPU inference).
                    // On CPU launches: generation is memory-bound — 4 threads
                    // saturate it; the PREFILL is compute-bound and scales,
                    // and Codex's ~7K-token system prompt is all prefill on
                    // the first turn (measured: 40 s/2048 tokens at -t 4), so
                    // batch threads get more cores.
                    add("-t"); add(if (tryGpu) "2" else "4")
                    add("-tb"); add(if (tryGpu) "2" else "6")
                    add("-c"); add("$CTX_TOKENS")
                    // ⛔ ONE SLOT. b10712's default is --parallel 4, which
                    // quietly provisioned FOUR 8K slots (`n_slots = 4` in the
                    // log) — a phone chat is one conversation, and the extra
                    // slots multiply KV footprint and dilute the LCP cache the
                    // multi-step codex turns live off.
                    add("-np"); add("1")
                    // ⛔ PEAK MEMORY IS THE KILLER, LITERALLY: the 4B prefill's
                    // compute buffers on top of weights+KV OOM-killed the engine
                    // 11 s into a 7K prompt with ~2.8 GB free (kernel SIGKILL,
                    // no logcat trace). With -np 1 reclaiming the slot overhead,
                    // moderate batches fit again — 256-token ubatches (tried
                    // during the hunt) cost real prefill speed on this CPU. A
                    // q8_0 K-cache was tried too and REVOKED: quantized KV on
                    // the CPU backend collapsed the 4B prefill to 7.6 tok/s. GPU
                    // micro-batches are the UI's breathing room: each ubatch is
                    // one solid block of GPU work, and 512-token blocks starved
                    // the compositor long enough to read as even at LOW queue
                    // priority. 128 gives the renderer a window every few
                    // hundred ms; prefill pays a little, the device stays alive
                    // — the owner's explicit trade (2026-09-01). CPU keeps the
                    // measured-fast sizes.
                    add("-b"); add(if (tryGpu) "512" else "1024")
                    add("-ub"); add(if (tryGpu) "128" else "512")
                    if (tryGpu) { add("-ngl"); add("99") }
                    add("--jinja")
                    add("--no-webui")
                    // ⛔ ANSWER, DON'T MEDITATE — unless asked. Qwen's chat
                    // template defaults hybrid models into reasoning: "say hi"
                    // burned a wall of hidden think-tokens before one visible
                    // word. Default is thinking OFF — both knobs, the template
                    // kwarg alone is known- insufficient on some models, and
                    // both are no-ops for templates without a thinking switch.
                    // The chat's effort picker can flip it back on per chat (a
                    // restart — this is launch state). ProcessBuilder passes
                    // argv verbatim: no shell, the JSON needs no quoting.
                    if (!thinking) { add("--reasoning-budget"); add("0") }
                    add("--chat-template-kwargs")
                    add("{\"enable_thinking\":${if (thinking) "true" else "false"}}")
                    // Default: NO override — the model's own GGUF template, so
                    // llama.cpp injects that family's tools + uses its matching
                    // tool parser (real tool-calls out of the box). Only Qwen
                    // overrides to agent.jinja (see agentTemplateAssetFor).
                    templateFileFor(m)?.let { add("--chat-template-file"); add(it.absolutePath) }
                    // Vision projector, when its pack is downloaded — this is
                    // what lets a photo in the chat reach the model instead of
                    // dying as "image input is not supported" (2026-09-01).
                    if (ai.eight24family.conch.linux.LocalLlm.hasVision(m)) {
                        ai.eight24family.conch.linux.LocalLlm.mmprojOf(m)?.let {
                            add("--mmproj"); add(it.absolutePath)
                        }
                    }
                }
                ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile())
                    // LD_LIBRARY_PATH: nativeLibraryDir alone resolves the whole
                    // dlopen chain — per-CPU ggml variants, and on GPU launches
                    // the shim libOpenCL.so living right there (the vendor stack
                    // behind it comes through the sphal namespace, not through
                    // paths). GGML_BACKEND_PATH names the plugin FILE (a
                    // directory value fails dlopen) and only on GPU launches:
                    // ggml loads it in ADDITION to its normal scan.
                    .apply {
                        environment()["LD_LIBRARY_PATH"] = nativeDir
                        if (tryGpu) environment()["GGML_BACKEND_PATH"] = gpuPlugin.absolutePath
                    }
                    .start()
            } catch (t: Throwable) {
                _state.value = State.Failed(m.id, t.message ?: t.javaClass.simpleName)
                return@withLock LaunchOutcome.FAIL
            }
            proc = p
            // Weights stream off ext4 into the page cache here — a few seconds
            // for the small models, longer the first cold time. 90 s covers it
            // with margin; a dead process short-circuits the wait.
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) break
                if (healthOk()) {
                    // The FACT, not the intent: the engine says "no usable GPU"
                    // when the plugin loaded nothing offloadable. Such a launch
                    // is a CPU launch crippled by GPU-sized thread counts —
                    // relaunch it as an honest CPU config instead of serving it.
                    if (tryGpu && logSaysNoGpu()) {
                        android.util.Log.w(TAG, "gpu launch found no usable GPU for ${m.id} — relaunching on cpu")
                        stopLocked()
                        return@withLock LaunchOutcome.RETRY_CPU
                    }
                    servingGpu = tryGpu
                    servingThinking = thinking
                    servingVision = LocalLlm.hasVision(m)
                    _state.value = State.Up(m.id, tryGpu)
                    // The store's passive trust fact: this model DID come up
                    // on this phone, in this mode. Free — it just happened.
                    ai.eight24family.conch.linux.store.ModelRecords.markRan(m.id, tryGpu)
                    android.util.Log.i(
                        TAG,
                        "serving ${m.id} on $BASE_URL (${if (tryGpu) "gpu" else "cpu"}" +
                            "${if (thinking) ", thinking" else ""})",
                    )
                    startWatch(m, thinking)
                    return@withLock LaunchOutcome.UP
                }
                delay(400)
            }
            val tail = runCatching {
                logFile().readText().lineSequence().map { it.trim() }
                    .lastOrNull { it.isNotEmpty() }
            }.getOrNull()
            stopLocked()
            if (tryGpu) {
                // A GPU launch that died or never answered is not a verdict on
                // the model — some drivers pass the probe and then fall over in
                // kernel compilation. One honest retry on CPU before failing.
                android.util.Log.w(TAG, "gpu launch failed for ${m.id} ($tail) — retrying on cpu")
                return@withLock LaunchOutcome.RETRY_CPU
            }
            _state.value = State.Failed(m.id, tail ?: "the engine did not come up")
            android.util.Log.w(TAG, "engine failed for ${m.id}: $tail")
            LaunchOutcome.FAIL
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        lock.withLock {
            stopLocked()
            _state.value = State.Off
        }
    }

    private fun stopLocked() {
        watch?.cancel()
        watch = null
        proc?.let { p ->
            p.destroy()
            runCatching {
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        proc = null
    }

    /** Epoch ms of the last watchdog-driven restart — one free revival per
     *  minute. A second death inside the window is a pattern (OOM loop), and
     *  looping a 4 GB load against the killer would burn the battery for
     *  nothing: that one becomes a Failed the user can see. */
    @Volatile private var lastAutoRestartMs = 0L

    /** A serving engine that DIES must say so — a dead loopback port looks
     *  exactly like "still loading" to everything above it. Killers seen on
     *  the owner's phone: the phantom-process reaper (mitigated at launch)
     *  and the kernel OOM killer under a 4B prefill. One automatic restart
     *  per minute turns a single strike into a hiccup the in-flight codex
     *  retries ride out — the app fixes itself instead of reporting. */
    /** Free the model's ram after this long with no generation — the engine
     * is 2-5 GB resident, and a phone that stopped using it should get that
     * back. Warm enough for a follow-up; freed if you walk away. The next
     * turn pays one cold weight-reload. */
    private const val IDLE_STOP_MS = 120_000L

    /** Is the engine mid-generation right now? /slots is_processing. On any
     *  error we assume BUSY, so a transient blip never kills a live turn. */
    private fun isProcessing(): Boolean = runCatching {
        val c = URL("$BASE_URL/slots").openConnection() as HttpURLConnection
        c.connectTimeout = 1_000; c.readTimeout = 1_000
        val body = c.inputStream.bufferedReader().readText().also { c.disconnect() }
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).any { arr.optJSONObject(it)?.optBoolean("is_processing") == true }
    }.getOrDefault(true)

    private fun startWatch(m: LocalLlm.Model, thinking: Boolean) {
        watch?.cancel()
        watch = scope.launch {
            var lastActiveMs = System.currentTimeMillis()
            while (true) {
                delay(4_000)
                if (_state.value !is State.Up) return@launch
                val p = proc ?: return@launch
                // Idle reclaim: while alive and NOT generating, count down to a
                // self-stop that frees the weights. Any processing resets it,
                // so a long turn or a burst of follow-ups never trips it.
                if (p.isAlive) {
                    if (isProcessing()) {
                        lastActiveMs = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastActiveMs > IDLE_STOP_MS) {
                        android.util.Log.i(TAG, "engine idle ${IDLE_STOP_MS / 1000}s serving ${m.id} — stopping to free ram")
                        stop()
                        return@launch
                    }
                }
                if (!p.isAlive) {
                    proc = null
                    val diedOnGpu = servingGpu
                    val now = System.currentTimeMillis()
                    if (now - lastAutoRestartMs > 60_000) {
                        lastAutoRestartMs = now
                        android.util.Log.w(TAG, "engine died while serving ${m.id} — restarting once")
                        // Same mode it died in: a demoted engine must not creep
                        // back onto the GPU that just killed it.
                        start(m, thinking, forceCpu = lastForcedCpu)
                    } else if (diedOnGpu) {
                        // Two GPU deaths inside a minute is a driver/VRAM
                        // pattern, not bad luck — demote to CPU instead of
                        // giving up: slower beats dead.
                        android.util.Log.w(TAG, "engine died twice on gpu serving ${m.id} — demoting to cpu")
                        start(m, thinking, forceCpu = true)
                    } else {
                        android.util.Log.w(TAG, "engine died twice within a minute serving ${m.id} — giving up")
                        _state.value = State.Failed(
                            m.id,
                            "the system keeps killing the engine (out of memory?) — free some ram or use a smaller model",
                        )
                    }
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
