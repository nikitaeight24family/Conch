package ai.eight24family.conch.linux.embed

import ai.eight24family.conch.linux.chat.LocalApiAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turning text into vectors, on this phone.
 *
 * One call, one batch: `/v1/embeddings` takes an array and answers in the
 * same order, which is what makes indexing a chat archive one request per
 * few dozen messages instead of one per message.
 */
object LocalEmbedApi {

    private const val TAG = "Conch-Embed"

    /** Batch size for an indexing pass. Bounded because the whole batch is
     *  one JSON body and one prefill: 32 short messages is ~4K tokens, which
     *  fits the 512-token-per-item window with room and keeps the request
     *  under a second on CPU. */
    const val BATCH = 32

    /** ⛔ THE MODEL HAS OPINIONS ABOUT WHAT IT IS EMBEDDING. bge-m3 (unlike
     *  e5) wants no prefix at all - measured on the owner's phone: with the
     *  e5-style "query:"/"passage:" prefixes its ranking was WORSE, and
     *  without them a Russian query put both the Russian and the English
     *  answer on top (0.535 / 0.443) against 0.38 and below for everything
     *  else. So nothing is prepended; if a model that needs prefixes is ever
     *  added, that belongs here, keyed by model. */
    fun prepare(text: String): String = text.trim().take(MAX_CHARS)

    /**
     * The longest single input, in CHARACTERS.
     *
     * ⛔ CYRILLIC IS NOT ENGLISH ON A TOKENIZER. bge-m3's XLM-RoBERTa
     * vocabulary spends ~2-3 characters per token on Russian against ~4 on
     * English, so a cap that is safe for one is a 500-token overflow for the
     * other - which is exactly how the first indexing pass died
     * ("input (517 tokens) is too large"). 900 characters is under the
     * engine's 1024-token batch even at the Cyrillic rate, and [ChatIndex]
     * CHUNKS anything longer instead of throwing the tail away.
     */
    const val MAX_CHARS = 900

    sealed interface Result {
        data class Ok(val vectors: List<FloatArray>) : Result
        data class Failed(val reason: String) : Result
    }

    /** Embed a batch. The engine must already be up ([LocalEmbedEngine.ensureUp]). */
    suspend fun embed(texts: List<String>): Result = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext Result.Ok(emptyList())
        val body = buildJsonObject {
            put(
                "input",
                buildJsonArray { texts.forEach { add(JsonPrimitive(prepare(it))) } },
            )
        }.toString().toByteArray()
        runCatching {
            val c = (URL("${LocalEmbedEngine.BASE_URL}/v1/embeddings")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                // A batch of 32 on two CPU threads: seconds, not minutes, but
                // a cold cache and a big model deserve room.
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                LocalApiAccess.authorize(this)
                setFixedLengthStreamingMode(body.size)
            }
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) {
                val raw = runCatching { c.errorStream?.bufferedReader()?.readText() }
                    .getOrNull().orEmpty()
                c.disconnect()
                return@runCatching Result.Failed(
                    raw.take(200).ifBlank { "embedder answered HTTP ${c.responseCode}" },
                )
            }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val data = Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray
                ?: return@runCatching Result.Failed("embedder returned no data")
            val out = data.map { el ->
                val arr = el.jsonObject["embedding"]?.jsonArray
                    ?: return@runCatching Result.Failed("embedder returned no vector")
                FloatArray(arr.size) { i -> arr[i].jsonPrimitive.floatOrNull ?: 0f }
            }
            LocalEmbedEngine.touch()
            Result.Ok(out)
        }.getOrElse {
            // ⛔ A SILENT FAILURE HERE COST AN HOUR. The first indexing pass
            // died with the engine's log showing "cancel task" and the app
            // showing nothing at all - because this line used to swallow the
            // reason. Every exit from a network call gets a sentence.
            android.util.Log.w(TAG, "embed batch of ${texts.size} failed: ${it.message}", it)
            if (it is kotlinx.coroutines.CancellationException) throw it
            Result.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    /**
     * Cosine similarity. The server L2-normalises by default
     * (`--embd-normalize 2`), so this is a dot product in practice — but the
     * norms are divided out anyway, because an index built by a future model
     * or a future flag must not silently start ranking by vector length.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0f || nb <= 0f) return -1f
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}
