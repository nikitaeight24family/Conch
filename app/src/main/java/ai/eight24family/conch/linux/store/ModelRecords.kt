package ai.eight24family.conch.linux.store

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.LocalLlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * What each model has ACTUALLY done on THIS phone — the store's trust layer.
 *
 * Three kinds of fact, all local, none phoned home (the no-telemetry stance
 * stands — community sharing, when it comes, will be an explicit opt-in
 * screen, not a default):
 *
 *  - `ran` / `ranGpu` — captured passively the moment the engine reaches
 *    healthy Up on a model. Zero cost, zero consent needed: it already
 *    happened on this device.
 *  - `tokS` — measured by the explicit [verify] button: a 32-token
 *    generation against the serving engine, speed taken from the engine's
 *    own timings. Feeds the row badge AND recalibrates every estimate
 *    (see DeviceProfile.bwGbps).
 *  - `rating` — the owner's own 1..5 stars, from the store row.
 */
object ModelRecords {

    data class Rec(
        val ran: Boolean = false,
        val ranGpu: Boolean = false,
        val tokS: Double? = null,
        val tokSAtMs: Long = 0L,
        val rating: Int? = null,
        /** The owner's written review — local, and the exact payload an
         *  opt-in community sync would carry if that ever ships. */
        val reviewText: String? = null,
        val reviewAtMs: Long = 0L,
        /** The engine tried to load this model on THIS device and it CRASHED /
         * never came up (e.g. an arch llama.cpp can't parse, a draft/corrupt
         * GGUF — the SIGSEGV in load_model). A model that can't run must not be
         * offered. Cleared the moment it DOES run healthy. */
        val failed: Boolean = false,
    )

    private val TAG = "Conch-ModelStore"
    private val _all = MutableStateFlow<Map<String, Rec>>(emptyMap())
    val flow: StateFlow<Map<String, Rec>> = _all.asStateFlow()
    @Volatile private var loaded = false

    private fun file(): File = File(ServiceLocator.appContext.filesDir, "llm/records.json")

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val root = Json.parseToJsonElement(file().readText()).jsonObject
                _all.value = root.mapValues { (_, v) ->
                    val o = v.jsonObject
                    Rec(
                        ran = o["ran"]?.jsonPrimitive?.booleanOrNull ?: false,
                        ranGpu = o["ranGpu"]?.jsonPrimitive?.booleanOrNull ?: false,
                        tokS = o["tokS"]?.jsonPrimitive?.doubleOrNull,
                        tokSAtMs = o["tokSAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                        rating = o["rating"]?.jsonPrimitive?.longOrNull?.toInt(),
                        reviewText = o["review"]?.jsonPrimitive?.contentOrNull,
                        reviewAtMs = o["reviewAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                        failed = o["failed"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                }
            }
            loaded = true
        }
    }

    fun all(): Map<String, Rec> { ensureLoaded(); return _all.value }
    fun of(id: String): Rec? = all()[id]

    private fun save() = runCatching {
        // Built with the serializer, not string glue — review text is free
        // prose and must be escaped correctly.
        val root = kotlinx.serialization.json.buildJsonObject {
            _all.value.forEach { (id, r) ->
                put(
                    id,
                    kotlinx.serialization.json.buildJsonObject {
                        put("ran", kotlinx.serialization.json.JsonPrimitive(r.ran))
                        put("ranGpu", kotlinx.serialization.json.JsonPrimitive(r.ranGpu))
                        r.tokS?.let { put("tokS", kotlinx.serialization.json.JsonPrimitive(it)) }
                        if (r.tokSAtMs > 0) put("tokSAtMs", kotlinx.serialization.json.JsonPrimitive(r.tokSAtMs))
                        r.rating?.let { put("rating", kotlinx.serialization.json.JsonPrimitive(it)) }
                        r.reviewText?.let { put("review", kotlinx.serialization.json.JsonPrimitive(it)) }
                        if (r.reviewAtMs > 0) put("reviewAtMs", kotlinx.serialization.json.JsonPrimitive(r.reviewAtMs))
                        if (r.failed) put("failed", kotlinx.serialization.json.JsonPrimitive(true))
                    },
                )
            }
        }
        file().apply { parentFile?.mkdirs() }.writeText(root.toString())
    }.let { }

    private fun update(id: String, f: (Rec) -> Rec) {
        ensureLoaded()
        synchronized(this) {
            _all.value = _all.value + (id to f(_all.value[id] ?: Rec()))
            save()
        }
    }

    /** Called by the engine at every healthy Up — the passive "it runs" fact.
     *  Never downgrades: once seen on gpu, `ranGpu` stays. */
    fun markRan(id: String, gpu: Boolean) = runCatching {
        // A healthy Up clears any prior failure — it demonstrably runs now.
        update(id) { it.copy(ran = true, ranGpu = it.ranGpu || gpu, failed = false) }
    }.let { }

    /** The engine could not load this model on THIS device (crashed / never came
     *  up). Hides it from the store browse and marks it unusable in the library
     *  so the owner is never offered a model that can't run. */
    fun markFailed(id: String) = runCatching {
        update(id) { it.copy(failed = true) }
    }.let { }

    fun rate(id: String, stars: Int) = update(id) { it.copy(rating = stars.coerceIn(1, 5)) }

    /** Save the written review (blank clears it). */
    fun review(id: String, text: String) = update(id) {
        val t = text.trim().take(2000)
        it.copy(
            reviewText = t.ifEmpty { null },
            reviewAtMs = if (t.isEmpty()) 0L else System.currentTimeMillis(),
        )
    }

    // ── the measured verify ──

    sealed interface VerifyResult {
        data class Done(val tokS: Double, val gpu: Boolean) : VerifyResult
        data class Refused(val why: String) : VerifyResult
    }

    /**
     * Measure real generation speed of a READY model on this phone: bring the
     * engine up on it, generate 32 tokens, take the engine's own
     * `timings.predicted_per_second`, put the engine back the way it was.
     *
     * Refuses rather than yanks: if the engine is serving a DIFFERENT model,
     * a live chat may be riding it — its ram is not ours to take.
     */
    suspend fun verify(m: LocalLlm.Model): VerifyResult = withContext(Dispatchers.IO) {
        if (!LocalLlm.isReady(m)) return@withContext VerifyResult.Refused("model not downloaded")
        val before = LocalLlmEngine.state.value
        (before as? LocalLlmEngine.State.Up)?.let {
            if (it.modelId != m.id) {
                return@withContext VerifyResult.Refused(
                    "engine is serving ${LocalLlm.byId(it.modelId)?.label ?: it.modelId} — stop it first",
                )
            }
        }
        if (before is LocalLlmEngine.State.Starting) {
            return@withContext VerifyResult.Refused("engine is starting — try again in a moment")
        }
        val wasOff = before !is LocalLlmEngine.State.Up
        if (!LocalLlmEngine.start(m)) {
            return@withContext VerifyResult.Refused(
                (LocalLlmEngine.state.value as? LocalLlmEngine.State.Failed)?.reason
                    ?: "engine did not start",
            )
        }
        val gpu = (LocalLlmEngine.state.value as? LocalLlmEngine.State.Up)?.gpu == true
        val tokS = runCatching { bench() }.getOrNull()
        // Leave the phone as found: a verify tap must not park gigabytes of
        // engine in ram behind the user's back.
        if (wasOff) runCatching { LocalLlmEngine.stop() }
        if (tokS == null || tokS <= 0.0) {
            return@withContext VerifyResult.Refused("the engine answered without timings")
        }
        update(m.id) { it.copy(ran = true, ranGpu = it.ranGpu || gpu, tokS = tokS, tokSAtMs = System.currentTimeMillis()) }
        android.util.Log.i(TAG, "verified ${m.id}: $tokS tok/s (gpu=$gpu)")
        VerifyResult.Done(tokS, gpu)
    }

    private fun bench(): Double? {
        val conn = (URL("${LocalLlmEngine.BASE_URL}/completion").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 180_000 // 32 tokens on a big cpu model is real time
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use {
            it.write(
                """{"prompt":"Write one sentence about the sea.","n_predict":32,"temperature":0}"""
                    .toByteArray(),
            )
        }
        val started = System.currentTimeMillis()
        val body = conn.inputStream.use { s -> s.reader().readText() }
        val wallMs = System.currentTimeMillis() - started
        conn.disconnect()
        val root = Json.parseToJsonElement(body).jsonObject
        root["timings"]?.jsonObject?.get("predicted_per_second")?.jsonPrimitive?.doubleOrNull
            ?.let { return it }
        // Engine builds without timings: fall back to tokens over wall time —
        // prefill of the 8-token prompt pollutes it a little; labeled the same.
        val n = root["tokens_predicted"]?.jsonPrimitive?.longOrNull ?: 32L
        return if (wallMs > 0) n * 1000.0 / wallMs else null
    }
}
