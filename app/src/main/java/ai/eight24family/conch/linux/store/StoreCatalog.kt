package ai.eight24family.conch.linux.store

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.linux.LocalLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The model store's SHELF — what the store offers, as data.
 *
 * The shelf is a JSON manifest, not code: it ships as a bundled asset (the
 * store works on a plane) and refreshes from the public Conch repo, so new
 * models appear WITHOUT an app release — the store grows server-side, the way
 * a store must. Only MODELS (data files) are ever fetched; the engine still
 * rides in the APK — Play forbids downloading executable code, and that rule
 * is why a "driver store" is not here (see the session report, 2026-09-01).
 *
 * ── TRUST BOUNDARY ──
 *
 * The manifest is remote content feeding URLs into a downloader, so every
 * entry passes [sanitize] before it exists in memory: https-only, hosts
 * pinned to Hugging Face, ids/filenames constrained to safe charsets (they
 * become filesystem paths), sizes positive. A manifest that fails wholesale
 * is DISCARDED and the previous one stays — a bad deploy must not empty the
 * store, and a poisoned one must not redirect it.
 *
 * ── WHAT THE NUMBERS MEAN ──
 *
 * `kvPerTok` — KV-cache bytes per context token (2 × layers × kv-heads ×
 * head-dim × 2, f16), precomputed at curation time from each model's real
 * architecture; the fits verdict is computed, never vibed. `activeBytes` —
 * bytes actually read per generated token (== file size for dense models,
 * the active-expert share for MoE), which is what generation speed follows
 * on a memory-bound phone.
 */
object StoreCatalog {

    /** Raw manifest URL in the PUBLIC repo (this private repo's releases
     *  mirror). The path must exist there for refresh to land — until then
     *  the bundled seed serves, which is the same file. */
    const val REMOTE_URL =
        "https://raw.githubusercontent.com/nikitaeight24family/Conch/main/store/catalog.json"

    private const val ASSET = "llm/store-catalog.json"
    private const val TAG = "Conch-ModelStore"
    private const val REFRESH_TTL_MS = 6 * 60 * 60 * 1000L

    data class BwClass(val match: List<String>, val gbps: Int)

    data class Entry(
        val id: String,
        /** Present on builtin rows too (label etc. resolve via [LocalLlm]). */
        val builtin: Boolean,
        val label: String?,
        val family: String,
        /** Shelf section: tiny / everyday / strong / frontier. */
        val cat: String,
        val file: String?,
        val url: String?,
        val bytes: Long,
        val quant: String?,
        val hfRepo: String?,
        val kvPerTok: Long,
        /** Bytes read per generated token — file size for dense, the active
         *  share (routing cost priced in) for MoE. Drives the speed estimate. */
        val activeBytes: Long,
        /** "verified" = ran real Codex tool work ON THIS APP; "expected" =
         *  everything else. Worn honestly. */
        val tier: String,
        /** Tool-calling capable BY DESIGN — the model is trained + templated
         * for function calling, so it can drive Codex's shell. Set from
         * research per model, NOT from size or family reflex. Drives the
         * "agent" badge; models without it are honest chat/vision models.
         * */
        val agent: Boolean,
        val blurb: String?,
        val desc: String?,
        /** The ORIGINAL publisher's Hugging Face org — the source of the real
         *  brand mark (BrandIcons): meta-llama for Llama, openai for gpt-oss —
         *  not the quantizer whose repo the bytes come from. */
        val brandOrg: String? = null,
        /** Repo answers 401 to anonymous pulls — shown, never shelved as
         *  downloadable. Manifest models are curated open; browse hits learn
         *  this at resolve time. */
        val gated: Boolean = false,
        /** Vision projector (mmproj) for multimodal families — downloaded
         *  alongside the weights so the model can see images. Null = text-only. */
        val visionUrl: String? = null,
        val visionFile: String? = null,
        val visionBytes: Long = 0L,
    )

    data class Catalog(
        val v: Int,
        /** Share of TOTAL ram a resident model may claim before the phone
         *  starts killing what the user has open — the store's stable
         *  "runs on this phone at all" gate (availMem is the live one). */
        val capacityFraction: Double,
        val bw: List<BwClass>,
        val defaultGbps: Int,
        val models: List<Entry>,
        /** Where the GPU-driver spec row links OUT to (never an in-app
         *  install — Play forbids downloading executable code). Manifest-
         *  driven so it can be re-pointed without an app release; null hides
         *  the link. */
        val driversUrl: String? = null,
    )

    // ⛔ DECLARATION ORDER IS LOAD-BEARING. Kotlin initializes object fields
    // top-to-bottom, and `_catalog`'s initializer runs the whole seed parse —
    // so everything parse/sanitize touches (the regexes below) MUST be
    // declared above it or they are still null mid-parse. Exactly that
    // shipped once: every entry NPE-dropped, the store opened empty, and the
    // unit tests stayed green because they call parse() after full init
    // (2026-09-01).
    private val ID_RE = Regex("^[a-z0-9_\\-]{2,64}$")
    private val FILE_RE = Regex("^[A-Za-z0-9._\\-]{4,200}\\.gguf$")
    private val REPO_RE = Regex("^[\\w.\\-]+/[\\w.\\-]+$")

    private val _catalog = MutableStateFlow(loadInitial())
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    /** Compute buffers + engine runtime on top of weights and KV for store
     *  models — sized from the 4B's real prefill OOM (compute spikes are the
     *  killer, not steady state). Builtins keep their tuned flat overhead. */
    const val COMPUTE_BYTES = 1_200_000_000L

    // ── wire → memory ──

    /** Parse + sanitize a manifest. Throws on structural rot; single bad
     *  entries are dropped, not fatal — one typo must not empty the shelf. */
    fun parse(text: String): Catalog {
        val root = Json.parseToJsonElement(text).jsonObject
        val v = root["v"]?.jsonPrimitive?.longOrNull?.toInt() ?: 1
        val frac = root["capacityFraction"]?.jsonPrimitive?.doubleOrNull ?: 0.62
        val bw = root["bw"]?.jsonArray?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                BwClass(
                    match = o["match"]!!.jsonArray.map { it.jsonPrimitive.content.lowercase() },
                    gbps = o["gbps"]!!.jsonPrimitive.longOrNull!!.toInt(),
                )
            }.getOrNull()
        } ?: emptyList()
        val defaultGbps = root["defaultGbps"]?.jsonPrimitive?.longOrNull?.toInt() ?: 6
        val models = root["models"]?.jsonArray?.mapNotNull { el ->
            runCatching { sanitize(el.jsonObject) }.getOrNull()
        } ?: emptyList()
        require(models.isNotEmpty()) { "manifest carries no valid models" }
        // Link-out only, https only — a driver page opened in a browser, never
        // fetched into the app.
        val driversUrl = root["driversUrl"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { runCatching { java.net.URI(it).scheme == "https" }.getOrDefault(false) }
        return Catalog(v, frac.coerceIn(0.3, 0.9), bw, defaultGbps, models, driversUrl)
    }

    private fun sanitize(o: kotlinx.serialization.json.JsonObject): Entry? {
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        fun lng(k: String) = o[k]?.jsonPrimitive?.longOrNull
        val id = str("id")?.takeIf { ID_RE.matches(it) } ?: return null
        val builtin = o["builtin"]?.jsonPrimitive?.booleanOrNull == true
        val url = str("url")
        val file = str("file")
        if (!builtin) {
            // A downloadable entry IS a downloader instruction — pin it.
            if (url == null || file == null) return null
            val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            if (u.scheme != "https" || u.host != "huggingface.co") return null
            if (!FILE_RE.matches(file)) return null
            if ((lng("bytes") ?: 0L) <= 0L) return null
        }
        val bytes = lng("bytes") ?: LocalLlm.BUILTIN.firstOrNull { it.id == id }?.bytes ?: 0L
        return Entry(
            id = id,
            builtin = builtin,
            label = str("label"),
            family = str("family") ?: "model",
            cat = str("cat") ?: "everyday",
            file = file,
            url = url,
            bytes = bytes,
            quant = str("quant"),
            hfRepo = str("hfRepo")?.takeIf { REPO_RE.matches(it) },
            kvPerTok = (lng("kvPerTok") ?: 0L).coerceIn(0L, 1_000_000L),
            activeBytes = (lng("activeBytes") ?: bytes).coerceAtLeast(1L),
            tier = if (str("tier") == "verified") "verified" else "expected",
            agent = o["agent"]?.jsonPrimitive?.let {
                it.booleanOrNull ?: (it.contentOrNull == "true")
            } ?: false,
            blurb = str("blurb"),
            desc = str("desc"),
            brandOrg = str("brandOrg")?.takeIf { Regex("^[A-Za-z0-9_.\\-]{2,42}$").matches(it) },
            visionUrl = str("visionUrl")?.takeIf {
                runCatching { java.net.URI(it).host == "huggingface.co" }.getOrDefault(false)
            },
            visionFile = str("visionFile")?.takeIf { FILE_RE.matches(it) },
            visionBytes = (lng("visionBytes") ?: 0L).coerceAtLeast(0L),
        )
    }

    // ── sources: bundled seed, disk cache, remote refresh ──

    private fun cacheFile(): File =
        File(ServiceLocator.appContext.filesDir, "llm/store-catalog-cache.json")

    private fun loadInitial(): Catalog {
        runCatching {
            val c = cacheFile()
            if (c.exists()) {
                return parse(c.readText()).also {
                    android.util.Log.i(TAG, "shelf loaded: ${it.models.size} models (cache, v${it.v})")
                }
            }
        }
        return runCatching {
            ServiceLocator.appContext.assets.open(ASSET).use { parse(it.reader().readText()) }
                .also { android.util.Log.i(TAG, "shelf loaded: ${it.models.size} models (seed, v${it.v})") }
        }.getOrElse {
            android.util.Log.w(TAG, "seed manifest unreadable: ${it.message}")
            Catalog(0, 0.62, emptyList(), 6, emptyList())
        }
    }

    @Volatile private var lastRefreshMs = 0L

    /** Refresh the shelf from the public repo — silent on every failure (the
     *  bundled/cached shelf keeps serving; an offline store is still a store). */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs < REFRESH_TTL_MS) return@withContext
        lastRefreshMs = now
        runCatching {
            val conn = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                android.util.Log.i(TAG, "shelf refresh: remote answered $code — keeping current shelf")
                return@runCatching
            }
            val text = conn.inputStream.use { it.reader().readText() }
            conn.disconnect()
            val fresh = parse(text) // throws → previous shelf stays
            cacheFile().apply { parentFile?.mkdirs() }.writeText(text)
            _catalog.value = fresh
            android.util.Log.i(TAG, "shelf refreshed: ${fresh.models.size} models (v${fresh.v})")
        }.onFailure { android.util.Log.i(TAG, "shelf refresh skipped: ${it.message}") }
    }

    // ── the store entry → a real local model ──

    /** What [LocalLlm] needs to own a store model: same type as the builtins,
     *  so the engine, the chats, the picker and the download service serve it
     *  with zero special cases. */
    fun toModel(e: Entry): LocalLlm.Model? {
        if (e.builtin) return LocalLlm.BUILTIN.firstOrNull { it.id == e.id }
        if (e.gated || e.bytes <= 0L) return null
        return LocalLlm.Model(
            id = e.id,
            label = e.label ?: e.id,
            file = e.file ?: return null,
            url = e.url ?: return null,
            bytes = e.bytes,
            blurb = e.blurb ?: "",
            family = e.family,
            kvPerTok = e.kvPerTok,
            brandOrg = e.brandOrg,
            mmprojFile = e.visionFile,
            mmprojUrl = e.visionUrl,
            mmprojBytes = e.visionBytes,
        )
    }
}
