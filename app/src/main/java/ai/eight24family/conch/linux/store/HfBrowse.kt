package ai.eight24family.conch.linux.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The store's LONG TAIL — live search over all of Hugging Face's GGUF repos,
 * under the curated shelf: editorial on top, the whole catalog below, the
 * Play shape.
 *
 * A search hit knows only what the listing API says (repo, pulls, likes) —
 * the file, its size and the gated flag are RESOLVED when its page opens:
 * one repo-info call plus one tree call, picking a single-file Q4_0 (the
 * quant the phone's OpenCL kernels want), else Q4_K_M, else the smallest
 * whole gguf. Multi-part ggufs are skipped — the downloader moves one file.
 *
 * Resolved entries live in [resolved] keyed by a synthetic catalog id, so
 * the model page and [ai.eight24family.conch.linux.LocalLlm.addFromStore]
 * serve them exactly like shelf models — kvPerTok stays 0, which routes
 * ramNeeded through the conservative flat overhead: honest for an unknown
 * architecture.
 */
object HfBrowse {

    private const val TAG = "Conch-ModelStore"

    data class Hit(
        val repo: String,
        val downloads: Long,
        val likes: Long,
        /** Family + brand inferred from the MODEL NAME, not the uploader — so
         * an unsloth-quantized Qwen wears Qwen's mark, not unsloth's sloth.
         * */
        val family: String,
        val brandOrg: String?,
    )

    /** The real brand behind a repo, read off the model name (the part after
     *  '/'), NOT the uploader — quantizers (unsloth, bartowski, …) re-host
     *  many families. (family, hf-org-for-avatar). org null → no avatar, and
     *  the family carries a monogram from the model's own initial instead of a
     *  misleading third-party logo. */
    fun brandFor(repo: String): Pair<String, String?> {
        val name = repo.substringAfter('/').lowercase()
        val known = listOf(
            "qwen" to ("qwen" to "Qwen"),
            "gemma" to ("gemma" to "google"),
            "llama" to ("llama" to "meta-llama"),
            "phi" to ("phi" to "microsoft"),
            "granite" to ("granite" to "ibm-granite"),
            "smol" to ("smol" to "HuggingFaceTB"),
            "mistral" to ("mistral" to "mistralai"),
            "ministral" to ("mistral" to "mistralai"),
            "mixtral" to ("mistral" to "mistralai"),
            "devstral" to ("mistral" to "mistralai"),
            "magistral" to ("mistral" to "mistralai"),
            "deepseek" to ("deepseek" to "deepseek-ai"),
            "gpt-oss" to ("openai" to "openai"),
            "lfm" to ("liquid" to "LiquidAI"),
            "glm" to ("glm" to "zai-org"),
            "yi-" to ("yi" to "01-ai"),
            "internlm" to ("internlm" to "internlm"),
            "falcon" to ("falcon" to "tiiuae"),
        )
        for ((needle, fam) in known) if (name.contains(needle)) return fam
        // Unknown family → monogram from the model's own name, never the
        // uploader's logo.
        return name.substringBefore('-').ifBlank { "model" } to null
    }

    /** Synthetic-id → entry, grown by [register] and enriched by [resolve]. */
    private val _resolved = MutableStateFlow<Map<String, StoreCatalog.Entry>>(emptyMap())
    val resolved: StateFlow<Map<String, StoreCatalog.Entry>> = _resolved.asStateFlow()

    fun browseId(repo: String): String =
        "hf-" + repo.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').take(58)

    fun entryOf(id: String): StoreCatalog.Entry? = _resolved.value[id]

    /** A hit becomes an addressable (routable) entry the moment it's tapped. */
    fun register(hit: Hit): String {
        val id = browseId(hit.repo)
        if (_resolved.value[id] == null) {
            _resolved.value += (id to StoreCatalog.Entry(
                id = id,
                builtin = false,
                label = hit.repo.substringAfter('/')
                    .removeSuffix("-GGUF").removeSuffix("-gguf"),
                family = hit.family,
                cat = "browse",
                file = null,
                url = null,
                bytes = 0L,
                quant = null,
                hfRepo = hit.repo,
                kvPerTok = 0L,
                activeBytes = 1L,
                tier = "expected",
                // A raw HF search hit is unproven — never badged as an agent
                // until it earns it (the whole point of the honesty pass).
                agent = false,
                blurb = null,
                desc = null,
                brandOrg = hit.brandOrg,
                gated = false,
            ))
        }
        return id
    }

    suspend fun search(query: String, limit: Int = 30): List<Hit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        fetchList(
            "https://huggingface.co/api/models?filter=gguf&sort=downloads&direction=-1" +
                "&limit=$limit&search=" + URLEncoder.encode(q, "UTF-8"),
            "search",
        )
    }

    /** ⛔ THE STORE IS THE WHOLE ECOSYSTEM, not a hand-list. With no query the
     * store shows the most-downloaded GGUF models on Hugging Face — thousands
     * reachable, ranked by real popularity — under the curated shelf, so
     * Conch's honest agent/verified flags lead and the living catalog fills
     * the rest. Curated ids are filtered out by the caller so nothing
     * double-lists. */
    suspend fun popular(limit: Int = 50): List<Hit> = withContext(Dispatchers.IO) {
        fetchList(
            "https://huggingface.co/api/models?filter=gguf&sort=downloads&direction=-1&limit=$limit",
            "popular",
        )
    }

    private fun fetchList(url: String, what: String): List<Hit> = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 12_000
        }
        if (conn.responseCode != 200) { conn.disconnect(); return@runCatching emptyList() }
        val body = conn.inputStream.use { it.reader().readText() }
        conn.disconnect()
        Json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                // Gated repos answer 401 to the anonymous downloader — a store
                // must not shelve what its own button can't fetch.
                val gated = o["gated"]?.jsonPrimitive?.let {
                    it.booleanOrNull ?: (it.contentOrNull != "false")
                } ?: false
                if (gated) return@runCatching null
                // The repo id rides into URL paths and the synthetic id —
                // anything outside org/name shape is not a hit, it's noise.
                val repo = o["id"]!!.jsonPrimitive.content
                if (!Regex("^[\\w.\\-]+/[\\w.\\-]+$").matches(repo)) return@runCatching null
                val (fam, org) = brandFor(repo)
                Hit(
                    repo = repo,
                    downloads = o["downloads"]?.jsonPrimitive?.longOrNull ?: 0L,
                    likes = o["likes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    family = fam,
                    brandOrg = org,
                )
            }.getOrNull()
        }
    }.getOrElse {
        android.util.Log.i(TAG, "hf $what failed: ${it.message}")
        emptyList()
    }

    /** Fill in what the page needs: the downloadable file, its true size, the
     *  gated flag, all-time counts. Idempotent; silent failure leaves the
     *  entry unresolved and the page saying so. */
    suspend fun resolve(id: String) = withContext(Dispatchers.IO) {
        val e = _resolved.value[id] ?: return@withContext
        if (e.bytes > 0L || e.gated) return@withContext
        val repo = e.hfRepo ?: return@withContext
        runCatching {
            val info = (URL(
                "https://huggingface.co/api/models/$repo?expand[]=downloadsAllTime&expand[]=likes&expand[]=gated",
            ).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 10_000 }
            var gated = false
            if (info.responseCode == 200) {
                val o = Json.parseToJsonElement(info.inputStream.use { it.reader().readText() }).jsonObject
                gated = o["gated"]?.jsonPrimitive?.let {
                    it.booleanOrNull ?: (it.contentOrNull != "false")
                } ?: false
                HfStats.noteFromModelInfo(
                    repo,
                    o["downloadsAllTime"]?.jsonPrimitive?.longOrNull,
                    o["likes"]?.jsonPrimitive?.longOrNull,
                )
            }
            info.disconnect()
            if (gated) {
                _resolved.value += (id to e.copy(gated = true))
                return@runCatching
            }
            val tree = (URL("https://huggingface.co/api/models/$repo/tree/main?recursive=1")
                .openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 15_000 }
            require(tree.responseCode == 200) { "tree ${tree.responseCode}" }
            val files = Json.parseToJsonElement(tree.inputStream.use { it.reader().readText() })
                .jsonArray.mapNotNull { el ->
                    runCatching {
                        val o = el.jsonObject
                        val path = o["path"]!!.jsonPrimitive.content
                        val size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L
                        path to size
                    }.getOrNull()
                }
            tree.disconnect()
            val candidates = files.filter { (p, s) ->
                p.endsWith(".gguf", ignoreCase = true) && s > 0 &&
                    !p.contains("mmproj", ignoreCase = true) &&
                    !Regex("-\\d{5}-of-\\d{5}", RegexOption.IGNORE_CASE).containsMatchIn(p)
            }
            val pick = candidates.firstOrNull { it.first.contains("Q4_0", true) }
                ?: candidates.firstOrNull { it.first.contains("Q4_K_M", true) }
                ?: candidates.minByOrNull { it.second }
                ?: error("no single-file gguf in repo")
            val fileName = pick.first.substringAfterLast('/')
            // The basename becomes a file in our own storage — same charset
            // law the manifest sanitizer enforces.
            require(Regex("^[A-Za-z0-9._\\-]{4,200}\\.gguf$").matches(fileName)) { "unsafe filename" }
            _resolved.value += (id to e.copy(
                file = fileName,
                url = "https://huggingface.co/$repo/resolve/main/${pick.first}",
                bytes = pick.second,
                // Dense assumption for an unknown architecture — estimates
                // stay conservative, and honest ones arrive via [ verify ].
                activeBytes = pick.second,
                quant = Regex("(?i)(Q\\d[_A-Z0-9]*|IQ\\d[_A-Z0-9]*|MXFP4|F16|BF16)")
                    .find(fileName)?.value?.uppercase(),
                // The real "About this model" — the model card's own words from
                // Hugging Face, so browse models aren't blank.
                desc = e.desc ?: fetchCardSummary(repo),
            ))
            android.util.Log.i(TAG, "resolved $repo -> $fileName (${pick.second} bytes)")
        }.onFailure { android.util.Log.i(TAG, "resolve failed for $repo: ${it.message}") }
    }

    /** The model card's own description, from Hugging Face — README.md with the
     *  YAML front-matter stripped, first real prose paragraph, capped. Best
     *  effort: null on any failure (the page just shows no About). */
    private fun fetchCardSummary(repo: String): String? = runCatching {
        val conn = (URL("https://huggingface.co/$repo/raw/main/README.md")
            .openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 10_000 }
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        var md = conn.inputStream.use { it.reader().readText() }.also { conn.disconnect() }
        // Strip the leading YAML front-matter (--- … ---).
        if (md.startsWith("---")) md = md.substringAfter("---").substringAfter("---")
        val para = md.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("!") &&
                    !line.startsWith("<") && !line.startsWith("|") && !line.startsWith("---") &&
                    !line.startsWith("[![") && !line.startsWith("- ") && !line.startsWith("* ")
            }
            .firstOrNull() ?: return null
        // De-markdown lightly (links, bold/italic markers) and cap.
        val clean = para
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace(Regex("[*_`]"), "")
            .trim()
        clean.takeIf { it.length >= 20 }?.take(600)
    }.getOrNull()
}
