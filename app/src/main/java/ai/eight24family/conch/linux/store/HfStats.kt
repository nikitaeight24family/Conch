package ai.eight24family.conch.linux.store

import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real popularity numbers for the shelf — Hugging Face's own all-time
 * download and like counts per repo, read from its public API when the store
 * is OPEN (a user-initiated screen, same standing as tapping download) and
 * cached for a day. Nothing is sent: an anonymous GET per repo, no ids, no
 * device info — the privacy stance survives intact.
 *
 * These are the store's "N installs" line until Conch has its own community
 * counts — and they are honest ones: the global pull count of the exact repo
 * the download button fetches from.
 */
object HfStats {

    data class Stat(val downloads: Long, val likes: Long, val atMs: Long)

    private const val TAG = "Conch-ModelStore"
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    private val _stats = MutableStateFlow<Map<String, Stat>>(emptyMap())
    val flow: StateFlow<Map<String, Stat>> = _stats.asStateFlow()
    @Volatile private var loaded = false

    private fun file(): File = File(ServiceLocator.appContext.filesDir, "llm/hf-stats.json")

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val root = Json.parseToJsonElement(file().readText()).jsonObject
                _stats.value = root.mapValues { (_, v) ->
                    val o = v.jsonObject
                    Stat(
                        downloads = o["dl"]?.jsonPrimitive?.longOrNull ?: 0L,
                        likes = o["likes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        atMs = o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                }
            }
            loaded = true
        }
    }

    private fun save() = runCatching {
        val sb = StringBuilder("{")
        _stats.value.entries.forEachIndexed { i, (repo, s) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(repo).append("\":{\"dl\":").append(s.downloads)
                .append(",\"likes\":").append(s.likes).append(",\"at\":").append(s.atMs).append('}')
        }
        sb.append('}')
        file().apply { parentFile?.mkdirs() }.writeText(sb.toString())
    }.let { }

    fun of(repo: String?): Stat? {
        if (repo == null) return null
        ensureLoaded()
        return _stats.value[repo]
    }

    /** Fetch every stale repo once; misses stay silent (rows just show no
     *  counts — an offline shelf must not grow error text). */
    suspend fun refresh(repos: List<String>) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        var changed = false
        for (repo in repos.distinct()) {
            val have = _stats.value[repo]
            if (have != null && now - have.atMs < TTL_MS) continue
            runCatching {
                val conn = (
                    URL("https://huggingface.co/api/models/$repo?expand[]=downloadsAllTime&expand[]=likes")
                        .openConnection() as HttpURLConnection
                    ).apply { connectTimeout = 8_000; readTimeout = 10_000 }
                if (conn.responseCode == 200) {
                    val root = Json.parseToJsonElement(
                        conn.inputStream.use { it.reader().readText() },
                    ).jsonObject
                    val s = Stat(
                        downloads = root["downloadsAllTime"]?.jsonPrimitive?.longOrNull ?: 0L,
                        likes = root["likes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        atMs = now,
                    )
                    synchronized(this@HfStats) { _stats.value = _stats.value + (repo to s) }
                    changed = true
                }
                conn.disconnect()
            }.onFailure { android.util.Log.i(TAG, "hf stats skipped for $repo: ${it.message}") }
        }
        if (changed) synchronized(this@HfStats) { save() }
    }

    /** A repo-info answer fetched by someone else (browse resolve) — keep it,
     *  it's the same data a refresh would have cost a request for. */
    fun noteFromModelInfo(repo: String, downloads: Long?, likes: Long?) {
        if (downloads == null && likes == null) return
        ensureLoaded()
        synchronized(this) {
            _stats.value += (repo to Stat(downloads ?: 0L, likes ?: 0L, System.currentTimeMillis()))
            save()
        }
    }

    /** 18529497 → "18.5M", 201701 → "202k", 938 → "938". */
    fun fmt(n: Long): String = when {
        n >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000L -> "${(n + 500) / 1_000}k"
        n >= 1_000L -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
        else -> "$n"
    }
}
