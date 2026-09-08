package ai.eight24family.conch.linux

import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local models — language models that run ON this phone, offline.
 *
 * ── THE PLAY-USER CONTRACT ──
 *
 * Someone who installed Conch from the store and owns nothing else taps ONE
 * button and has a model; taps another and has another. Nothing else is asked
 * of them. That rules out every dependency the first version had: no Linux
 * environment, no phone bridge, no wireless-debugging ritual. The app itself
 * downloads the model file into its own storage, and the engine ships INSIDE
 * the APK — which is also the only shape Play allows: **an app may not
 * download executable code** (Device & Network Abuse policy), so the engine
 * rides in `jniLibs` and only MODELS (data) are fetched at runtime.
 *
 * The engine is llama.cpp's own Android arm64 release build (bionic, built by
 * ggml-org's CI), stripped from 240 MB to 24.7 MB with the NDK's llvm-strip —
 * see [LocalLlmEngine]. Measured end-to-end on the owner's phone
 * (OPPO CPH2671, 2026-08-31): gemma-3-1b Q4_K_M answers over the
 * OpenAI-compatible API at 38.7 tok/s generation, 4 threads.
 *
 * ── WHERE MODELS LIVE ──
 *
 * `filesDir/llm/models` — INTERNAL storage, deliberately: llama.cpp mmaps the
 * weights, and internal storage is ext4 where external app storage is FUSE
 * with page-fault overhead on exactly that path. Files are removed with the
 * app, appear in the app's own storage accounting, and need no permission.
 *
 * ── WHAT DECIDES "FITS" ──
 *
 * Weights are mmapped and end up resident once read, so a model needs about
 * its file size plus [RAM_OVERHEAD_BYTES] for the KV cache and runtime, sized
 * for the few-K contexts these models default to. Compared against
 * [PhoneResources] at render time: the verdict is a statement about NOW, not
 * a property of the model.
 */
object LocalLlm {

    /** KV cache (16K context — LocalLlmEngine.CTX_TOKENS) + prefill compute
     *  buffers + runtime, on top of the mmapped weights. Sized from the real
     *  casualty: the 4B prefill OOM-died with ~2.8 GB free, so "fits" must
     *  demand more than that; 16K doubles the old KV share, and Qwen3.5's
     *  hybrid layers keep it modest, so the bump is small. */
    const val RAM_OVERHEAD_BYTES = 1_800_000_000L

    /** How a local model travels through the agent layer: the chat's model
     *  value is `local:<catalog id>`, and CodexSpec turns that into its
     *  custom-provider flags pointing at [LocalLlmEngine]. */
    const val MODEL_ARG_PREFIX = "local:"

    /** What the CLI must see as `--model` / RPC `model`: the bare catalog id —
     *  the prefix is Conch plumbing, never a model name. Pass-through for
     *  ordinary models. */
    fun cliModelName(value: String): String = value.removePrefix(MODEL_ARG_PREFIX)

    /** The two "effort" levels a local model offers — really the engine's
     * Never sent to codex: its effort enum would refuse them — see
     * [isLocalEffort] at the turn/start call sites. */
    const val EFFORT_INSTANT = "instant"
    const val EFFORT_THINKING = "thinking"
    fun isLocalEffort(effort: String): Boolean =
        effort == EFFORT_INSTANT || effort == EFFORT_THINKING

    /**
     * Codex on the phone's row is usable with NO login at all — a downloaded
     * local model IS its authorization (its custom provider needs no key, and
     * the picker there offers nothing else). Every `installed && loggedIn`
     * gate must treat this as signed in, or the phone's own sessions never
     * get listed and the new-chat targets exclude the one agent that works.
     */
    fun localBrainAuthorizes(serverId: String, agent: ai.eight24family.conch.agent.Agent): Boolean =
        ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent].supportsLocalModel &&
            serverId == LinuxSsh.SERVER_ID &&
            CATALOG.any { isReady(it) }

    data class Model(
        val id: String,
        val label: String,
        val file: String,
        val url: String,
        val bytes: Long,
        val blurb: String,
        /** OVERRIDE the chat template. Null (the default) = the model's OWN GGUF
         *  template, which is what makes a store download drive Codex out of the
         *  box: llama.cpp injects that family's tools and uses its matching tool
         *  parser (measured — Llama-3.2 fires real tool calls on its native
         *  template). The engine overrides only for Qwen (agent.jinja) — see
         *  [LocalLlmEngine.agentTemplateAssetFor] for why. Set this by hand only
         *  after measuring a family needs it AND the substitute matches the
         *  model's trained tool format; a mismatch cancels the stream. */
        val templateAsset: String? = null,
        /** The MODEL's own brand mark. Local chats are driven by Codex but
         * they are not "Codex" to the user — rows, chips and menus show the
         * real model's face. */
        val iconRes: Int = ai.eight24family.conch.R.drawable.ic_qwen,
        /** Vision projector (mmproj) — the piece that turns a multimodal
         *  family's TEXT gguf into one that can see. Downloaded alongside the
         *  weights; without it an image in the chat died as a raw 500
         *  ("image input is not supported — provide the mmproj",
         *  owner's photo of a GPU box, 2026-09-01). */
        val mmprojFile: String? = null,
        val mmprojUrl: String? = null,
        val mmprojBytes: Long = 0L,
        /** Model family for the store's mark and grouping — "qwen" for the
         *  builtins, whatever the store manifest says for added models. */
        val family: String = "qwen",
        /** KV-cache bytes per context token (f16), computed from the real
         *  architecture: 2 x full-attention-layers x kv-heads x head-dim x 2.
         *  0 means the architecture was never read (a Hugging Face hit), and
         *  only then does the flat [RAM_OVERHEAD_BYTES] stand in. */
        val kvPerTok: Long = 0L,
        /** The original publisher's HF org — where the REAL brand mark comes
         *  from (BrandIcons); null falls back to family art/monogram. */
        val brandOrg: String? = null,
        /**
         * SHA-256 the finished weights must hash to, lowercase hex.
         *
         * SIZE WAS THE ONLY CHECK, AND SIZE IS NOT INTEGRITY. These are
         * gigabytes off a CDN fed straight into llama.cpp's GGUF parser, and
         * a file that is the right LENGTH and the wrong BYTES is exactly what
         * a truncated-then-resumed transfer, a mirror mix-up or a tampered
         * hop produces. Hugging Face publishes the hash of every LFS file
         * (`lfs.oid` in its tree API), so there is no reason to trust length
         * alone.
         *
         * Null = unknown (an older manifest entry, or a file the owner
         * imported himself): then the length check stands alone, as before.
         */
        val sha256: String? = null,
        /** Same, for the vision projector - equally large, equally corruptible. */
        val mmprojSha256: String? = null,
        /**
         * This model turns text into VECTORS; it does not talk.
         *
         * llama-server's own help is blunt about it: `--embeddings` is
         * "restrict to only support embedding use case; use only with
         * dedicated embedding models". So an embedder runs in its own process
         * on its own port ([ai.eight24family.conch.linux.embed.LocalEmbedEngine])
         * and must never be offered as a brain - a chat row that opened one
         * would sit there answering nothing.
         */
        val embedder: Boolean = false,
        /**
         * This model turns SPEECH into text. Same reasoning as [embedder] and
         * then some: it is not even a GGUF (whisper.cpp ships its own ggml
         * format) and it runs as a one-shot CLI, not a server - see
         * [ai.eight24family.conch.linux.voice.LocalVoice].
         */
        val voice: Boolean = false,
    ) {
        /**
         * Can this model hold a conversation?
         *
         * ⛔ EVERY "pick a model" PATH ASKS THIS ONE QUESTION. There are three
         * kinds of model on the phone now - chat, embed, voice - and only the
         * first has a chat head. A picker, a library row's tap, or codex's
         * "first ready model" default that forgot to ask would open a chat
         * with something that answers nothing at all. Adding the fourth kind
         * (a reranker, a TTS voice) touches this line and nothing else.
         */
        val isBrain: Boolean get() = !embedder && !voice
    }

    /**
     * Three sizes of Qwen3.5, all Q4_0, all open repos that answer without a
     * token (verified with HEAD requests, 2026-09-01 — sizes are the servers'
     * own content-length). Gated repos answer 401 to anonymous pulls, so only
     * re-uploads that actually download made the list: a one-tap button must
     * not end in a login screen.
     *
     * WHY THIS GENERATION, THIS QUANT: the first catalog carried Qwen 3, whose
     * small sizes REASON by default — the owner's "say hi" burned a wall of
     * hidden think-tokens before one visible word. Qwen3.5's small sizes are
     * answer-first (thinking exists but is opt-in), and the engine pins it off
     * anyway (see LocalLlmEngine's --reasoning-budget 0). Q4_0 instead of Q4_K_M
     * because the OpenCL backend's Adreno kernels are written for Q4_0 —
     * measured on the owner's phone: 0.8B prefill 698 tok/s / gen 48.9 tok/s at
     * 2.6K context, where the old Q4_K_M 1.7B collapsed to 10 tok/s generation
     * on long context.
     *
     * These drive the real Codex CLI (tools, shell, files). Every local model
     * launches under its OWN GGUF template (the exception is Qwen — see
     * LocalLlmEngine.agentTemplateAssetFor), which is what lets a store download
     * tool-call out of the box: llama.cpp injects that family's tools and uses
     * its matching parser (measured — Llama-3.2 fires real tool calls). A family
     * llama.cpp has no tool support for (e.g. Gemma) just chats, no tool calls —
     * clean, never a crash. These builtins stay the vetted, best-quality picks
     * (Qwen3.5); the store is open to the rest, and a bigger model is always the
     * better agent.
     */
    val BUILTIN = listOf(
        Model(
            id = "qwen3_5-0_8b",
            label = "Qwen 3.5 0.8B",
            file = "Qwen3.5-0.8B-Q4_0.gguf",
            // Published lfs.oid, checked against the bytes before the
            // file is named ready (see Model.sha256).
            sha256 = "444406ddd926550c724ec18d5120a9d40ded44908a063b0e66e9a7e5464c652c",
            url = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_0.gguf",
            bytes = 507_154_688,
            blurb = "smallest — instant replies, simple tasks",
            brandOrg = "Qwen",
            // 2 x 6 full-attention layers x 2 kv heads x 256 head dim x 2 B (f16),
            // from the published config. Qwen3.5 is hybrid - only every fourth
            // layer keeps a per-token KV - which is why these are far below what
            // a dense model of the same size would cost.
            kvPerTok = 12_288,
            mmprojFile = "mmproj-Qwen3.5-0.8B-F16.gguf",
            mmprojSha256 = "56e4c6cfe73b0c82e3e82bc518d7591997e61d81f723fc41a586f4fa69ea2453",
            mmprojUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/mmproj-F16.gguf",
            mmprojBytes = 204_987_232,
        ),
        Model(
            id = "qwen3_5-2b",
            label = "Qwen 3.5 2B",
            file = "Qwen3.5-2B-Q4_0.gguf",
            // Published lfs.oid, checked against the bytes before the
            // file is named ready (see Model.sha256).
            sha256 = "cd70221bebaee0503e0f6717e174250cd7825aa88438b3aabec9ad55731d9bb1",
            url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_0.gguf",
            bytes = 1_214_873_856,
            blurb = "fast and capable — the everyday pick",
            brandOrg = "Qwen",
            kvPerTok = 12_288,
            mmprojFile = "mmproj-Qwen3.5-2B-F16.gguf",
            mmprojSha256 = "7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7",
            mmprojUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/mmproj-F16.gguf",
            mmprojBytes = 668_227_264,
        ),
        Model(
            id = "qwen3_5-4b",
            label = "Qwen 3.5 4B",
            file = "Qwen3.5-4B-Q4_0.gguf",
            // Published lfs.oid, checked against the bytes before the
            // file is named ready (see Model.sha256).
            sha256 = "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb",
            url = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf",
            bytes = 2_583_221_408,
            blurb = "strongest here for agent work",
            brandOrg = "Qwen",
            // 2 x 8 full-attention layers x 4 kv heads x 256 head dim x 2 B.
            kvPerTok = 32_768,
            mmprojFile = "mmproj-Qwen3.5-4B-F16.gguf",
            mmprojSha256 = "cd88edcf8d031894960bb0c9c5b9b7e1fea6ebee02b9f7ce925a00d12891f864",
            mmprojUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/mmproj-F16.gguf",
            mmprojBytes = 672_423_616,
        ),
        /**
         * The search model: bge-m3, Q8, from ggml-org's own conversion.
         *
         * ⛔ CHOSEN BY MEASUREMENT, AND THE FIRST CANDIDATE LOST. The obvious
         * pick was multilingual-e5-small - 132 MB against 635 - and on the
         * owner's phone it ranked its own language only: for the Russian
         * query it put the right Russian message first (0.958) and the
         * equivalent ENGLISH one LAST (0.917), below (0.937), with everything
         * crushed into a 0.92-0.96 band. bge-m3 on the same six texts: the
         * Russian answer 0.535, the English answer 0.443, everything
         * unrelated 0.380 and below. A search that cannot tell borsch from
         * fastboot is not a feature, so the 500 extra megabytes are the price
         * of the thing working. (Also measured: the first e5 conversion tried
         * (cstr/.) does not even LOAD on this build - "bert model needs to
         * define token type count".)
         *
         * Downloaded only if the owner asks for search; nothing else uses it.
         */
        /**
         * Voice in: whisper base, Q5_1, from whisper.cpp's own model repo.
         *
         * base rather than tiny (31 MB) or small (190 MB) by the same logic
         * the search model was picked with: tiny mangles anything but slow
         * English, and small is three times the bytes for an accuracy gain a
         * dictation does not need. Measured on the owner's phone: 11 seconds
         * of speech in 3.8 s on 4 CPU threads, transcript exact.
         *
         * NOT a GGUF - whisper.cpp has its own format, which is why this
         * entry's `file` does not end in .gguf and why nothing but
         * [ai.eight24family.conch.linux.voice.LocalVoice] ever opens it.
         */
        Model(
            id = "whisper-base",
            label = "Whisper base (voice)",
            file = "ggml-base-q5_1.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            bytes = 59_707_625,
            sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
            blurb = "turns your voice into text on this phone - 99 languages",
            brandOrg = "openai",
            family = "whisper",
            voice = true,
        ),
        Model(
            id = "bge-m3",
            label = "BGE-M3 (search)",
            file = "bge-m3-q8_0.gguf",
            url = "https://huggingface.co/ggml-org/bge-m3-Q8_0-GGUF/resolve/main/bge-m3-q8_0.gguf",
            bytes = 634_553_760,
            sha256 = "aa473d51f451a22f0fcf39ba3330c14bed38a385712b1113440f69df4047a173",
            blurb = "finds things in your chats by meaning - 100+ languages",
            brandOrg = "BAAI",
            family = "bge",
            embedder = true,
            // XLM-RoBERTa large: 24 layers x 16 kv heads x 64 head dim, f16.
            // Small in absolute terms because the window is 512, not 16K.
            kvPerTok = 98_304,
        ),
    )

    // ── the LIVING catalog: builtins + models the user took from the store ──
    //
    // Everything downstream (engine, chats, picker, download service, rows)
    // reads CATALOG, so a store model IS a first-class local model the moment
    // it's added — zero special cases anywhere else. Added entries persist in
    // the app's own storage and load before first use; ids already in BUILTIN
    // are skipped so a manifest echo can't duplicate a row.

    private val addedModels = MutableStateFlow<List<Model>>(emptyList())
    @Volatile private var addedLoaded = false

    private fun addedFile(): java.io.File =
        java.io.File(ServiceLocator.appContext.filesDir, "llm/added-models.json")

    private fun ensureAddedLoaded() {
        if (addedLoaded) return
        synchronized(this) {
            if (addedLoaded) return
            armAutoResume()
            runCatching {
                val arr = kotlinx.serialization.json.Json
                    .parseToJsonElement(addedFile().readText()).jsonArray
                addedModels.value = arr.mapNotNull { el ->
                    runCatching {
                        val o = el.jsonObject
                        fun s(k: String) = o[k]?.jsonPrimitive?.content
                        fun l(k: String) = o[k]?.jsonPrimitive?.long
                        Model(
                            id = s("id")!!,
                            label = s("label")!!,
                            file = s("file")!!,
                            url = s("url")!!,
                            bytes = l("bytes")!!,
                            blurb = s("blurb") ?: "",
                            family = s("family") ?: "model",
                            kvPerTok = l("kvPerTok") ?: 0L,
                            brandOrg = s("brandOrg"),
                            mmprojFile = s("mmprojFile"),
                            mmprojUrl = s("mmprojUrl"),
                            mmprojBytes = l("mmprojBytes") ?: 0L,
                            sha256 = s("sha256"),
                            mmprojSha256 = s("mmprojSha256"),
                        )
                    }.getOrNull()
                }
            }
            addedLoaded = true
        }
    }

    private fun saveAdded() = runCatching {
        val arr = kotlinx.serialization.json.JsonArray(
            addedModels.value.map { m ->
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(m.id))
                    put("label", kotlinx.serialization.json.JsonPrimitive(m.label))
                    put("file", kotlinx.serialization.json.JsonPrimitive(m.file))
                    put("url", kotlinx.serialization.json.JsonPrimitive(m.url))
                    put("bytes", kotlinx.serialization.json.JsonPrimitive(m.bytes))
                    put("blurb", kotlinx.serialization.json.JsonPrimitive(m.blurb))
                    put("family", kotlinx.serialization.json.JsonPrimitive(m.family))
                    put("kvPerTok", kotlinx.serialization.json.JsonPrimitive(m.kvPerTok))
                    m.brandOrg?.let { put("brandOrg", kotlinx.serialization.json.JsonPrimitive(it)) }
                    m.mmprojFile?.let { put("mmprojFile", kotlinx.serialization.json.JsonPrimitive(it)) }
                    m.mmprojUrl?.let { put("mmprojUrl", kotlinx.serialization.json.JsonPrimitive(it)) }
                    if (m.mmprojBytes > 0) put("mmprojBytes", kotlinx.serialization.json.JsonPrimitive(m.mmprojBytes))
                    m.sha256?.let { put("sha256", kotlinx.serialization.json.JsonPrimitive(it)) }
                    m.mmprojSha256?.let { put("mmprojSha256", kotlinx.serialization.json.JsonPrimitive(it)) }
                }
            },
        )
        addedFile().apply { parentFile?.mkdirs() }.writeText(arr.toString())
    }.let { }

    // ── which CLI harness drives each local model (the app decides) ──
    //
    // The owner never picks an environment — the app routes each model to the
    // CLI that fits it, and the turn installs that CLI on first use if it isn't
    // on the phone yet. Qwen models go to Qwen Code (its native chat-completions
    // tool format); everything else to Codex (proven across the families
    // llama.cpp tool-supports). A hard fallback to Codex guarantees the returned
    // agent is always one whose spec supportsLocalModel.
    @Suppress("UNUSED_PARAMETER")
    fun harnessFor(modelId: String): ai.eight24family.conch.agent.Agent {
        // Every local model runs on the SAME on-device llama-server; the CLI
        // wrapper does NOT change whether it can tool-call — that's the engine +
        // the model's chat template (handled in LocalLlmEngine.templateFileFor).
        // Measured on the phone: Codex drives Qwen and Llama to REAL tool calls,
        // and no CLI makes Gemma tool-call (llama.cpp has no parser for its
        // format). So the harness is not what decides quality here.
        //
        // Codex is the harness that is installed on the phone and proven across
        // every family the engine tool-supports, so it drives them ALL — one
        // brain, no per-model CLI to install. Qwen Code / opencode stay
        // local-capable (supportsLocalModel, exec wiring, topbar) for the day
        // the app auto-installs a harness on demand; routing to one that isn't
        // installed only produced "qwen is not on PATH", which is not "it just
        // works". This is the single routing point — per-model routing, if it
        // ever earns its keep, goes here.
        return ai.eight24family.conch.agent.Agent.CODEX
    }

    val CATALOG: List<Model>
        get() {
            ensureAddedLoaded()
            return BUILTIN + addedModels.value.filter { a -> BUILTIN.none { it.id == a.id } }
        }

    fun isBuiltin(m: Model): Boolean = BUILTIN.any { it.id == m.id }

    /** Take a store model into the library (idempotent). The caller decides
     *  whether to also start the download — the metered guard lives in UI. */
    @Synchronized
    fun addFromStore(m: Model) {
        ensureAddedLoaded()
        if (BUILTIN.any { it.id == m.id }) return
        val existing = addedModels.value.firstOrNull { it.id == m.id }
        if (existing != null) {
            // ⛔ AN ENTRY IS A SNAPSHOT, AND SNAPSHOTS GO STALE. Every model
            // added before checksums existed carries `sha256 = null`, so a
            // delete-and-get-again would have re-downloaded it UNVERIFIED
            // while the manifest sitting right there knew the hash. Returning
            // early was almost right: it must not overwrite what the entry
            // learned on this phone (a kvPerTok read from the file's own
            // header beats a manifest number), but it must accept what the
            // shelf knows and the entry does not.
            val merged = existing.copy(
                sha256 = existing.sha256 ?: m.sha256,
                mmprojSha256 = existing.mmprojSha256 ?: m.mmprojSha256,
                kvPerTok = if (existing.kvPerTok > 0L) existing.kvPerTok else m.kvPerTok,
            )
            if (merged != existing) {
                addedModels.value = addedModels.value.map { if (it.id == m.id) merged else it }
                saveAdded()
                revision.value++
            }
            return
        }
        addedModels.value += m
        saveAdded()
        revision.value++
    }

    fun byId(id: String): Model? = CATALOG.firstOrNull { it.id == id }

    // ── storage ──

    val modelsDir: File
        get() = File(ServiceLocator.appContext.filesDir, "llm/models").apply { mkdirs() }

    fun fileOf(m: Model): File = File(modelsDir, m.file)
    private fun partOf(m: Model): File = File(modelsDir, "${m.file}.part")
    fun mmprojOf(m: Model): File? = m.mmprojFile?.let { File(modelsDir, it) }
    private fun mmprojPartOf(m: Model): File? = m.mmprojFile?.let { File(modelsDir, "$it.part") }

    /** True when the vision projector is fully downloaded — the engine adds
     *  --mmproj and the model can SEE. Ready deliberately does not require
     *  it: text chat must not break for weights downloaded before vision
     *  existed here. */
    fun hasVision(m: Model): Boolean =
        m.mmprojUrl != null && mmprojOf(m)?.length() == m.mmprojBytes

    /** What the download button is committing to: weights + vision pack. */
    fun totalBytes(m: Model): Long = m.bytes + (if (m.mmprojUrl != null) m.mmprojBytes else 0L)

    // ── state, straight off the filesystem (cheap — local files) ──

    sealed interface Status {
        data object Absent : Status
        data class Downloading(val bytesSoFar: Long) : Status
        data class Paused(val bytesSoFar: Long, val error: String?) : Status
        data object Ready : Status
    }

    /** Ready means the EXACT catalog size — existence alone once showed a
     *  zero-byte file as ready, and the engine died on it wordlessly
     *  (2026-08-31). A final file with any other length reads as a paused
     *  download; [startDownload] turns it back into a `.part` and resumes. */
    fun isReady(m: Model): Boolean =
        runCatching { fileOf(m).length() == m.bytes }.getOrDefault(false)

    fun status(m: Model): Status {
        val active = jobs[m.id]?.isActive == true
        if (isReady(m)) {
            // Weights done; the vision pack may still be streaming behind them.
            if (active) return Status.Downloading(m.bytes + visionBytesSoFar(m))
            return Status.Ready
        }
        val broken = fileOf(m).length()
        val part = maxOf(partOf(m).length(), broken)
        return when {
            active -> Status.Downloading(part + visionBytesSoFar(m))
            part > 0L -> Status.Paused(part + visionBytesSoFar(m), lastError.value[m.id])
            else -> Status.Absent
        }
    }

    private fun visionBytesSoFar(m: Model): Long =
        if (m.mmprojUrl == null) 0L
        else maxOf(mmprojOf(m)?.length() ?: 0L, mmprojPartOf(m)?.length() ?: 0L)

    // ── the downloader ──
    //
    // The app's own HTTP stack, into the app's own storage — nothing else can
    // write here (SELinux keeps the shell out of app data, which is also why
    // the first version's environment-side wget had to go). A `.part` plus a
    // Range request makes every interruption resumable; tapping the button
    // again continues, it never starts over.

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Live byte counts for in-flight downloads, keyed by model id — the row
     *  renders from this instead of polling the filesystem. */
    val progress = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Live download speed, bytes/second, keyed by model id — computed HERE,
     * at the one place bytes actually move, EMA-smoothed over the ~1MB
     * progress ticks so the number reads steady. */
    val speed = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The last failure per model id, shown on the paused row so "it stopped"
     *  is never silent. Cleared by the next attempt. */
    val lastError = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Bumped whenever a download finishes or a model is deleted, so rows
     *  re-read the filesystem without polling it. */
    val revision = MutableStateFlow(0)

    /**
     * Ids whose downloaded bytes are being hashed right now.
     *
     * A multi-gigabyte verify is seconds of full-tilt reading with the
     * progress bar already at 100 %, and without a state for it the row sits
     * on "done" doing something - the shape of a hang. The library says
     * "checking..." instead.
     */
    val verifying = MutableStateFlow<Set<String>>(emptySet())

    @Synchronized
    fun startDownload(m: Model) {
        if (jobs[m.id]?.isActive == true || (isReady(m) && (m.mmprojUrl == null || hasVision(m)))) return
        // An IMPORTED model is a file the owner already had: there is nothing
        // to fetch, and a blank url would only reach the downloader as a
        // malformed-URL crash. Say the true thing on its row instead.
        if (m.url.isBlank()) {
            lastError.value += (
                m.id to if (partOf(m).exists()) {
                    "the import was interrupted - import the file again"
                } else {
                    "imported from this phone - nothing to download"
                }
                )
            return
        }
        // A final-named file with the WRONG SIZE is a corpse (truncated write,
        // interrupted move): demote it to `.part` so the Range resume finishes
        // it — or drop it when a real .part already exists. A whole file is
        // left alone: a vision top-up runs while the engine may be mmapping
        // those very weights.
        fileOf(m).takeIf { it.exists() && it.length() != m.bytes }?.let { corpse ->
            if (partOf(m).exists()) corpse.delete() else corpse.renameTo(partOf(m))
        }
        lastError.value -= m.id
        // Starting again — by tap or by auto-resume — clears the cancel intent.
        userCancelled -= m.id
        jobs[m.id] = scope.launch { runDownload(m) }
        progress.value += (m.id to partOf(m).length())
        // Pin the process for the duration — a gigabyte on mobile data
        // outlives any screen. The service watches [progress] and stops
        // itself when the last download ends.
        ai.eight24family.conch.util.SilentlyTry.fired("Conch-LocalLlm", "start download service") {
            ai.eight24family.conch.service.LlmDownloadService.start(ServiceLocator.appContext)
        }
    }

    @Synchronized
    fun cancelDownload(m: Model) {
        jobs.remove(m.id)?.cancel()
        progress.value -= m.id
        speed.value -= m.id
        // An explicit cancel is an INTENT: the Wi-Fi auto-resume must not
        // undo it. Session-scoped — a fresh app start trusts the .part again.
        userCancelled += m.id
        revision.value++
    }

    // ── Wi-Fi auto-resume ──
    //
    // A paused.part resumes ITSELF the moment the phone is on an unmetered
    // network — if the disk still holds the remainder. Only RESUMES: fresh
    // downloads stay behind their tap, and anything the user cancelled this
    // session stays cancelled. Metered networks never trigger it — that
    // consent dialog stays the law.

    private val userCancelled: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    @Volatile private var autoResumeArmed = false

    private fun armAutoResume() {
        if (autoResumeArmed) return
        autoResumeArmed = true
        runCatching {
            val cm = ServiceLocator.appContext
                .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities,
                ) {
                    if (caps.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                        )
                    ) {
                        scope.launch { maybeAutoResume() }
                    }
                }
            })
            scope.launch { maybeAutoResume() } // the network may already be Wi-Fi
        }
    }

    private fun maybeAutoResume() {
        runCatching {
            if (ai.eight24family.conch.util.NetGuard.isMetered(ServiceLocator.appContext)) return
            val diskFree = PhoneResources.read().diskFreeBytes
            CATALOG.forEach { m ->
                if (m.id in userCancelled) return@forEach
                if (jobs[m.id]?.isActive == true) return@forEach
                val st = status(m)
                if (st !is Status.Paused) return@forEach
                val remaining = (totalBytes(m) - st.bytesSoFar).coerceAtLeast(0L)
                // the remainder plus half a gig of slack.
                if (diskFree - remaining > 500_000_000L) {
                    android.util.Log.i(
                        "Conch-LocalLlm",
                        "wi-fi auto-resume: ${m.id} at ${st.bytesSoFar} of ${totalBytes(m)}",
                    )
                    startDownload(m)
                }
            }
        }
    }

    fun delete(m: Model) {
        cancelDownload(m)
        fileOf(m).delete()
        partOf(m).delete()
        mmprojOf(m)?.delete()
        mmprojPartOf(m)?.delete()
        lastError.value -= m.id
        // A deleted STORE model leaves the library too — its row goes back to
        // the store's [ get ], instead of haunting the panel as a ghost entry.
        if (!isBuiltin(m)) synchronized(this) {
            ensureAddedLoaded()
            addedModels.value = addedModels.value.filterNot { it.id == m.id }
            saveAdded()
        }
        revision.value++
    }

    // ── importing a model the owner already has ──

    /**
     * Take a `.gguf` off this phone (or an SD card, or a USB stick) into the
     * library.
     *
     * WHY A COPY AND NOT A LINK: the engine is a SEPARATE PROCESS that opens a
     * path and mmaps it. A SAF uri is not a path, and the document it points at
     * may live behind a provider on removable storage that vanishes mid-answer
     * - so the bytes are brought into `filesDir/llm/models`, the same place a
     * download lands, and from there every other part of the app treats an
     * imported model exactly like a downloaded one. The cost is honest and
     * visible: a copy of a multi-gigabyte file, with progress, once.
     *
     * The architecture is then read out of the file's own header
     * ([GgufMeta]) - which is what lets the store price an imported model
     * properly ("~2.4G ram - fits") instead of falling back to a flat guess.
     */
    fun importFrom(uri: android.net.Uri, displayName: String, sizeBytes: Long) {
        val safeName = run {
            val base = displayName.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(180)
            if (cleaned.endsWith(".gguf", ignoreCase = true)) cleaned else cleaned + ".gguf"
        }
        val baseId = "own-" + safeName.removeSuffix(".gguf")
            .lowercase().replace(Regex("[^a-z0-9_\\-]"), "-").trim('-').take(56)
        val id = generateSequence(0) { it + 1 }
            .map { if (it == 0) baseId else baseId + "-" + it }
            .first { cand -> byId(cand) == null }
        val target = File(modelsDir, safeName)
        if (target.exists() && target.length() == sizeBytes) {
            // Already sitting there under that name - adopt it rather than
            // copying a second time.
            register(id, safeName, target)
            return
        }
        val part = File(modelsDir, safeName + ".part")
        // Registered FIRST, with the size the picker reported: the library row,
        // its progress bar, its speed and its cancel are the download ones, and
        // an import with no row would be a gigabyte moving invisibly. Dropped
        // again if the copy fails.
        register(id, safeName, target, expectBytes = sizeBytes)
        jobs[id] = scope.launch {
            progress.value += (id to 0L)
            lastError.value -= id
            revision.value++
            try {
                val ctx = ServiceLocator.appContext
                part.delete()
                var copied = 0L
                var lastMs = System.currentTimeMillis()
                var lastBytes = 0L
                ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "could not open that file" }
                    java.io.FileOutputStream(part).use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastBytes >= 4_000_000L) {
                                progress.value += (id to copied)
                                val now = System.currentTimeMillis()
                                if (now > lastMs) {
                                    val inst = (copied - lastBytes) * 1000.0 / (now - lastMs)
                                    val prev = speed.value[id]?.toDouble() ?: inst
                                    speed.value += (id to (0.3 * inst + 0.7 * prev).toLong())
                                }
                                lastMs = now
                                lastBytes = copied
                            }
                        }
                    }
                }
                // A GGUF starts with the four bytes "GGUF". Checking them
                // costs nothing and turns "the engine died wordlessly on a
                // renamed zip" into a sentence the owner can act on.
                val magic = java.io.FileInputStream(part).use { fis ->
                    ByteArray(4).also { fis.read(it) }
                }
                require(String(magic, Charsets.US_ASCII) == "GGUF") {
                    "that file is not a GGUF model"
                }
                if (!part.renameTo(target)) error("could not finalize the copy")
                // Re-registered now that the file is whole: the label and the
                // KV arithmetic come from the header, not from the filename.
                register(id, safeName, target)
                android.util.Log.i("Conch-LocalLlm", "imported $safeName as $id (${target.length()} bytes)")
            } catch (t: Throwable) {
                part.delete()
                if (t is kotlinx.coroutines.CancellationException) throw t
                android.util.Log.w("Conch-LocalLlm", "import of $safeName failed: ${t.message}")
                lastError.value += (id to (t.message ?: t.javaClass.simpleName))
                // A failed import leaves NO ghost row: the entry was only ever
                // a promise about a file that did not arrive.
                synchronized(this@LocalLlm) {
                    addedModels.value = addedModels.value.filterNot { it.id == id }
                    saveAdded()
                }
            } finally {
                progress.value -= id
                speed.value -= id
                revision.value++
            }
        }
    }

    /** Register a copied file as a first-class local model, priced from its
     *  own header. */
    private fun register(id: String, fileName: String, file: File, expectBytes: Long = 0L) {
        val meta = if (file.isFile) GgufMeta.read(file) else null
        val label = meta?.name?.takeIf { it.isNotBlank() && it.length <= 48 }
            ?: fileName.removeSuffix(".gguf").replace('_', ' ').take(48)
        val m = Model(
            id = id,
            label = label,
            file = fileName,
            // Nothing to fetch: the bytes are already here. [startDownload]
            // refuses a blank url out loud rather than crashing on it.
            url = "",
            bytes = if (file.isFile && file.length() > 0L) file.length() else expectBytes,
            blurb = buildString {
                append("imported")
                meta?.arch?.let { append(" - "); append(it) }
            },
            family = meta?.arch ?: "model",
            kvPerTok = meta?.kvPerTok ?: 0L,
        )
        synchronized(this) {
            ensureAddedLoaded()
            addedModels.value = addedModels.value.filterNot { it.id == id } + m
            saveAdded()
        }
        revision.value++
    }

    /** The published checksum the CURRENT shelf carries for this id, when the
     *  stored entry has none of its own. Null when the shelf is silent (an
     *  imported file, an older manifest) - then length is the only check, as
     *  it always was. */
    private fun shelfSha(id: String): String? =
        publishedSha(id, vision = false)

    private fun shelfVisionSha(id: String): String? =
        publishedSha(id, vision = true)

    /**
     * The published checksum for this id: the live shelf first, then the
     * manifest baked into this APK.
     *
     * ⛔ WHY THE SEED IS A SOURCE AND NOT JUST A COLD START. `StoreCatalog.refresh`
     * replaces the shelf with the remote file and never compares versions, so
     * the live catalog can be OLDER than the one this build shipped with —
     * measured 2026-09-08: the live manifest was v2 with `sha256` on ZERO of
     * eighteen entries, against fifteen in the seed. Without this fallback a
     * store download silently drops from verified to length-checked the moment
     * the manifest lags a release, which is the normal state of that file
     * between releases.
     *
     * ⛔ ONLY WHEN IT IS PROVABLY THE SAME FILE. A re-pointed url or a changed
     * size means the seed's hash belongs to a DIFFERENT download, and enforcing
     * it would fail every user's fetch with a corruption error that is really a
     * stale-metadata error. Then we return null and length is the only check,
     * exactly as before.
     *
     * Null is honest: an imported file or a model added before checksums
     * existed has no published hash anywhere.
     */
    private fun publishedSha(id: String, vision: Boolean): String? {
        fun shaOf(e: ai.eight24family.conch.linux.store.StoreCatalog.Entry) =
            if (vision) e.visionSha256 else e.sha256
        val live = ai.eight24family.conch.linux.store.StoreCatalog.catalog.value
            .models.firstOrNull { it.id == id }
        live?.let { shaOf(it) }?.let { return it }
        val seeded = ai.eight24family.conch.linux.store.StoreCatalog.seed()
            ?.models?.firstOrNull { it.id == id } ?: return null
        if (live != null) {
            val sameFile = if (vision) {
                seeded.visionUrl == live.visionUrl && seeded.visionBytes == live.visionBytes
            } else {
                seeded.url == live.url && seeded.bytes == live.bytes
            }
            if (!sameFile) return null
        }
        return shaOf(seeded)
    }

    private fun CoroutineScope.runDownload(m: Model) {
        try {
            // The weights first, always. The VISION pack rides the same job
            // only when it was explicitly asked for (the weights are already
            // whole — the "vision" button) or was already begun: a store user
            // who taps download gets the model, not a mandatory extra
            // half-gigabyte.
            if (!isReady(m)) {
                fetchFile(
                    m, m.url, partOf(m), fileOf(m), m.bytes,
                    // ⛔ THE SHELF IS THE FRESHER SOURCE. An entry is a
                    // snapshot: every model added before checksums existed
                    // carries a null hash, and browsing the store does not
                    // rewrite it (nothing is "added" by looking). Asking the
                    // live catalog HERE — the one moment the hash matters —
                    // means a model in the library since before this feature
                    // is still verified on its next download.
                    progressBase = 0L, expectSha = m.sha256 ?: shelfSha(m.id),
                )
            }
            // The architecture is IN the file we just finished. A curated row
            // was priced from a published config; a Hugging Face browse hit
            // arrived with kvPerTok = 0 and has been carrying the conservative
            // flat guess ever since — 1.8 GB standing in for KV+compute, which
            // decides "fits" vs "hidden" on a 4 GB phone. Read it once, now,
            // and the row tells the truth from here on.
            if (m.kvPerTok == 0L && !isBuiltin(m) && isReady(m)) {
                GgufMeta.read(fileOf(m))?.takeIf { it.kvPerTok > 0L }?.let { meta ->
                    synchronized(this@LocalLlm) {
                        ensureAddedLoaded()
                        addedModels.value = addedModels.value.map { a ->
                            if (a.id == m.id) a.copy(kvPerTok = meta.kvPerTok) else a
                        }
                        saveAdded()
                    }
                    android.util.Log.i(
                        "Conch-LocalLlm",
                        "priced ${m.id} from its own header: ${meta.arch}, " +
                            "${meta.kvLayers}/${meta.layers} kv layers, ${meta.kvPerTok} B/token",
                    )
                }
            }
            val mmUrl = m.mmprojUrl
            val mmPart = mmprojPartOf(m)
            val mmFinal = mmprojOf(m)
            val visionWanted = mmUrl != null && mmPart != null && mmFinal != null && !hasVision(m) &&
                (isReady(m) || mmPart.length() > 0L)
            if (visionWanted) {
                fetchFile(
                    m, mmUrl!!, mmPart!!, mmFinal!!, m.mmprojBytes,
                    progressBase = m.bytes,
                    expectSha = m.mmprojSha256 ?: shelfVisionSha(m.id),
                )
                android.util.Log.i("Conch-LocalLlm", "vision pack ready: ${m.mmprojFile}")
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.w("Conch-LocalLlm", "download of ${m.id} stopped: ${t.message}")
            lastError.value += (m.id to (t.message ?: t.javaClass.simpleName))
        } finally {
            progress.value -= m.id
            speed.value -= m.id
            revision.value++
        }
    }

    /** One resumable file: `.part` + Range, finalized by rename. [progressBase]
     *  offsets the live counter so a vision pack streaming after the weights
     *  reports weights+pack, matching what the row's total shows. */
    private fun CoroutineScope.fetchFile(
        m: Model,
        url: String,
        part: File,
        final: File,
        want: Long,
        progressBase: Long,
        expectSha: String? = null,
    ) {
        val have = part.length()
        // A part that is already the whole file needs finalizing, not
        // fetching — asking for a range past the end gets a 416.
        if (have != want) {
            // Through HfAuth: it carries the owner's token while the hop is
            // still huggingface.co - which is what makes a GATED repo
            // downloadable at all - and drops it before the signed CDN
            // redirect, which must never see a credential.
            val conn = ai.eight24family.conch.linux.store.HfAuth.open(url, rangeFrom = have)
            val code = conn.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false // server ignored the range — start over
                416 -> null // requested range not satisfiable — the part is complete
                else -> throw IllegalStateException("server answered $code")
            }
            if (append != null) {
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, append)
                        .use { out ->
                            val buf = ByteArray(256 * 1024)
                            var since = 0L
                            var lastMs = System.currentTimeMillis()
                            var lastBytes = part.length()
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                since += n
                                if (since >= 1_000_000L) {
                                    since = 0L
                                    val soFar = part.length()
                                    progress.value += (m.id to progressBase + soFar)
                                    val now = System.currentTimeMillis()
                                    if (now > lastMs) {
                                        val inst = (soFar - lastBytes) * 1000.0 / (now - lastMs)
                                        val prev = speed.value[m.id]?.toDouble() ?: inst
                                        speed.value += (m.id to (0.3 * inst + 0.7 * prev).toLong())
                                    }
                                    lastMs = now
                                    lastBytes = soFar
                                }
                            }
                        }
                }
            }
            conn.disconnect()
        }
        if (part.length() != want) {
            throw IllegalStateException(
                "download ended at ${part.length()} of $want bytes — tap download to resume",
            )
        }
        // THE HASH IS CHECKED BEFORE THE RENAME, NEVER AFTER. The rename IS
        // the "this model is ready" signal the rest of the app reads, so a
        // file that fails the check must not wear that name for a moment.
        // Hashed at the END rather than during the stream because a download
        // resumes across app restarts and a digest cannot: one pass over ext4
        // costs seconds, a wrong resume policy costs correctness.
        if (expectSha != null) {
            verifying.value += m.id
            try {
                val got = java.io.FileInputStream(part).use {
                    ai.eight24family.conch.data.UploadCache.sha256HexStream(it)
                }
                if (!got.equals(expectSha, ignoreCase = true)) {
                    // Wrong bytes cannot be repaired by resuming - drop the
                    // part so the next tap starts clean.
                    part.delete()
                    android.util.Log.w(
                        "Conch-LocalLlm",
                        "checksum mismatch for " + m.id + ": expected " + expectSha + " got " + got,
                    )
                    throw IllegalStateException(
                        "the downloaded file failed its checksum - deleted, tap download to try again",
                    )
                }
                android.util.Log.i("Conch-LocalLlm", "checksum ok for " + final.name)
            } finally {
                verifying.value -= m.id
            }
        }
        if (!part.renameTo(final)) throw IllegalStateException("could not finalize the file")
        progress.value += (m.id to progressBase + want)
    }

    // ── the "fits" verdict, pure ──

    /**
     * What this model costs resident, at the context it will really run with
     * on THIS device.
     *
     * ⛔ ONE FORMULA FOR EVERY MODEL, THE BUILT-IN ONES INCLUDED. They used to
     * be priced by a flat 1.8 GB standing for KV+compute at a fixed 16K, and
     * that was wrong in both directions: it over-charged the 0.8B by ~0.4 GB,
     * so the app hid ITS OWN DEFAULT PICK from every 4 GB phone by 30 MB; and
     * it under-charged the 4B, whose 0.67 GB vision projector the flat number
     * never counted, so an 8 GB phone was offered a model that needs ~5 GB.
     * The architecture in the table above comes from the published configs.
     *
     * The projector counts because the engine loads it (`--mmproj`) whenever
     * the model has one: resident for the session, not on demand.
     */
    fun ramNeeded(m: Model, ctx: Int = LocalLlmEngine.ctxFor(m)): Long =
        if (m.voice) {
            // Whisper runs, answers and exits: the weights are resident for
            // seconds, and the encoder's working set is small next to a chat
            // model's prefill spike.
            m.bytes + 250_000_000L
        } else if (m.embedder) {
            // No prefill spike of a chat model and a 512-token window: the
            // chat engine's 1.2 GB compute budget would over-charge this by
            // more than the weights themselves, and an over-charged model is
            // one the store hides from the phone that could run it.
            ai.eight24family.conch.linux.embed.LocalEmbedEngine.ramNeeded(m)
        } else if (m.kvPerTok > 0L) {
            m.bytes + m.mmprojBytes + m.kvPerTok * ctx +
                ai.eight24family.conch.linux.store.StoreCatalog.COMPUTE_BYTES
        } else {
            // Architecture unknown - the flat number is the only honest guess.
            m.bytes + m.mmprojBytes + RAM_OVERHEAD_BYTES
        }

    enum class Fit { FITS, TIGHT, SHORT }

    fun fit(m: Model, ramFreeBytes: Long): Fit = when {
        ramFreeBytes >= ramNeeded(m) + 500_000_000L -> Fit.FITS
        ramFreeBytes >= ramNeeded(m) -> Fit.TIGHT
        else -> Fit.SHORT
    }
}
