package ai.eight24family.conch.linux.store

import ai.eight24family.conch.di.ServiceLocator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The models' REAL faces — each publisher's own avatar from its Hugging Face
 * organization page (Meta's logo on meta-llama, OpenAI's on openai, IBM's on
 * ibm-granite…), fetched once and cached on disk forever. The owner's law: —
 * no hand-faked brand art, the logo the org itself published, or the
 * monogram fallback when it can't be had.
 *
 * Same network class as the rest of the store: anonymous read-only GETs to
 * Hugging Face, fired when the store is open, nothing sent. Hosts are pinned
 * (huggingface.co for the org lookup, its cdn-avatars host for the image),
 * the image is size-capped and decoded defensively — a poisoned avatar can
 * cost at most a skipped icon.
 */
object BrandIcons {

    private const val TAG = "Conch-ModelStore"
    private const val MAX_IMAGE_BYTES = 512 * 1024
    private val ORG_RE = Regex("^[A-Za-z0-9_.\\-]{2,42}$")

    private val _flow = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val flow: StateFlow<Map<String, Bitmap>> = _flow.asStateFlow()

    /** Orgs that failed this process run — don't hammer a 404 every recompose. */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var diskLoaded = false

    fun of(org: String?): Bitmap? = org?.let { _flow.value[it] }

    private fun dir(): File =
        File(ServiceLocator.appContext.filesDir, "llm/brand-icons").apply { mkdirs() }

    private fun ensureDiskLoaded() {
        if (diskLoaded) return
        synchronized(this) {
            if (diskLoaded) return
            runCatching {
                dir().listFiles()?.forEach { f ->
                    if (f.length() in 1..MAX_IMAGE_BYTES.toLong()) {
                        BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                            _flow.value += (f.name to bmp)
                        }
                    }
                }
            }
            diskLoaded = true
        }
    }

    /** Load ONLY what's already on disk — for screens that show marks but
     *  must not open network (the local-models panel is not the store). */
    suspend fun loadCached() = withContext(Dispatchers.IO) { ensureDiskLoaded() }

    /** Fetch every org we don't have yet. Silent per-org failure: the
     *  monogram keeps standing in, and we retry next app run, not sooner. */
    suspend fun refresh(orgs: List<String>) = withContext(Dispatchers.IO) {
        ensureDiskLoaded()
        for (org in orgs.distinct()) {
            if (!ORG_RE.matches(org)) continue
            if (_flow.value.containsKey(org) || org in failed) continue
            runCatching { fetchOne(org) }.onFailure {
                failed += org
                android.util.Log.i(TAG, "brand icon skipped for $org: ${it.message}")
            }
        }
    }

    private fun fetchOne(org: String) {
        val meta = (URL("https://huggingface.co/api/organizations/$org/overview")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 10_000
        }
        require(meta.responseCode == 200) { "org lookup ${meta.responseCode}" }
        val avatarUrl = Json.parseToJsonElement(meta.inputStream.use { it.reader().readText() })
            .jsonObject["avatarUrl"]?.jsonPrimitive?.contentOrNull
        meta.disconnect()
        require(!avatarUrl.isNullOrBlank()) { "no avatar" }
        val u = java.net.URI(avatarUrl)
        require(u.scheme == "https") { "avatar scheme" }
        require(u.host == "cdn-avatars.huggingface.co" || u.host == "huggingface.co") {
            "avatar host ${u.host}"
        }
        val img = (URL(avatarUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 15_000
        }
        require(img.responseCode == 200) { "avatar ${img.responseCode}" }
        require(img.contentType?.startsWith("image/") == true) { "not an image: ${img.contentType}" }
        val bytes = img.inputStream.use { it.readBytes() }
        img.disconnect()
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "avatar too large: ${bytes.size}" }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("avatar did not decode")
        // Store-tile sized is all we ever draw; a 1024px avatar is wasted heap.
        val bmp = if (raw.width > 192) {
            Bitmap.createScaledBitmap(raw, 192, (192f * raw.height / raw.width).toInt().coerceAtLeast(1), true)
                .also { if (it !== raw) raw.recycle() }
        } else raw
        runCatching {
            val out = File(dir(), org)
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        _flow.value += (org to bmp)
    }
}
