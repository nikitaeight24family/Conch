package ai.eight24family.conch.linux.chat

import ai.eight24family.conch.linux.LocalLlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talking to the phone's own model DIRECTLY — no CLI, no Linux, no bridge.
 *
 * ⛔ WHY THIS EXISTS AT ALL. Downloading and starting a model needed nothing
 * (the engine ships in the APK), but SAYING SOMETHING to it went through the
 * Codex CLI, which lives in the phone's Alpine, which is installed over the
 * phone bridge, which is wireless debugging behind developer options. Seven
 * steps to the first token, two of them rituals a normal person never
 * performs — for a model already sitting on disk with its server up on
 * 127.0.0.1. This file is the missing hop: the app's own client for the
 * OpenAI-compatible endpoint [LocalLlmEngine] already serves.
 *
 * The agent path is NOT replaced and not diminished — a real shell, real
 * tools and real sessions still mean a real CLI, and the local chat links
 * straight into it. What goes away is the CLI being the ENTRY.
 *
 * ── WHY NOT A LIBRARY ──
 *
 * Checked first (the standing rule): every Kotlin OpenAI client on offer
 * (openai-kotlin, s3ss10n, the Leap SDK client) brings Ktor or OkHttp with
 * it. That is a whole second HTTP stack in an APK that is 8.9 MB and whose
 * store listing promises no network but your own servers — bought for ~40
 * lines of SSE line-reading against OUR OWN loopback port. The store, the
 * HF browse and the speed probe already speak `HttpURLConnection`; this is
 * the fourth caller, not a new pattern.
 *
 * ── NO SYSTEM PROMPT, DELIBERATELY ──
 *
 * Codex's own system prompt is ~7K tokens, which on a 4B at CPU pace is
 * minutes of prefill before the first word (measured: ~40 s per 2048
 * tokens). A chat has no tools to describe, so it sends the conversation and
 * nothing else: the model's own GGUF template wraps it, and the first token
 * arrives as fast as this phone can go.
 */
object LocalChatApi {

    /** One turn, as the engine wants it. */
    data class Turn(
        /** "user" or "assistant" — the two roles a plain chat has. */
        val role: String,
        val text: String,
        /**
         * Absolute paths of images this turn shows the model. Only ever
         * non-empty for a model whose vision pack is installed (the caller
         * gates on `LocalLlm.hasVision`); the bytes ride as data: URLs
         * because the engine is a separate process with no access to our
         * app-private files.
         */
        val images: List<String> = emptyList(),
    )

    /** What arrives while the model generates. */
    sealed interface Chunk {
        data class Text(val delta: String) : Chunk
        /** Reasoning, when the engine was launched with thinking on. The
         *  server splits it out of `content` whenever the model's template
         *  emits a think block, so it can be shown as a foldaway note
         *  instead of being pasted into the reply. */
        data class Thinking(val delta: String) : Chunk
        /** The engine's OWN timings, so the chat shows a measured speed
         *  rather than an estimate. llama-server puts these in the FINAL
         *  chunk (the one with an empty delta and a stop reason), and per
         *  token as well when asked — so they are read wherever they appear. */
        data class Speed(val tokPerSec: Double, val predicted: Int) : Chunk
    }

    sealed interface Outcome {
        data object Done : Outcome
        /** The prompt did not fit the window the engine is serving. The caller
         *  drops the oldest turns and retries — the one error a long chat
         *  hits by simply continuing, and the reason [fit] exists. */
        data class TooLong(val message: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private const val TAG = "Conch-LocalChat"

    /** Loopback: a connect either happens at once or the engine is not up. */
    private const val CONNECT_TIMEOUT_MS = 4_000

    /**
     * Between two SSE lines. Generously long because the gap before the FIRST
     * one is the whole prefill: a long conversation on a slow CPU launch can
     * legitimately chew for minutes, and killing that as a timeout would look
     * exactly like the hang it is not. The user's Stop is the real abort —
     * it closes the socket from under the read (see [stream]).
     */
    private const val READ_TIMEOUT_MS = 600_000

    /** Rough tokens for a piece of text: ~3.6 chars per token holds for
     *  English prose and code alike, and the number only has to be good
     *  enough to keep a window from overflowing. */
    fun estimateTokens(text: String): Int = (text.length / 3.6).toInt() + 4

    /**
     * A 1.4 MP photo (what [ai.eight24family.conch.util.LocalImageShrink]
     * caps at) through a CLIP tiler. Derived from the measured 12 MP → 13.4K
     * tokens on the owner's phone, scaled by pixels and rounded up. Honest
     * approximation, and it errs high on purpose: over-charging an image
     * costs one dropped old turn, under-charging costs a failed send.
     */
    const val IMAGE_TOKENS = 1_800

    /**
     * The longest SUFFIX of [turns] whose estimated prompt fits [ctx], leaving
     * [reserve] tokens for the answer.
     *
     * ⛔ TRIMMING IS NOT OPTIONAL. The engine cannot shift its own context
     * (hybrid/recurrent models don't support it), so a conversation that
     * outgrows the window does not degrade — every send bounces off a 400,
     * which is exactly how the codex path died on an 8K window until the
     * number was raised (2026-09-01). A chat has no compaction step to lean
     * on, so it drops from the FRONT and says so.
     *
     * Always keeps at least the final turn: a single message bigger than the
     * whole window is the engine's error to report, not ours to hide.
     */
    fun fit(turns: List<Turn>, ctx: Int, reserve: Int = 512): List<Turn> {
        if (turns.isEmpty()) return turns
        val budget = (ctx - reserve).coerceAtLeast(256)
        var used = 0
        val kept = ArrayDeque<Turn>()
        for (t in turns.asReversed()) {
            val cost = estimateTokens(t.text) + t.images.size * IMAGE_TOKENS
            if (kept.isNotEmpty() && used + cost > budget) break
            kept.addFirst(t)
            used += cost
        }
        return kept.toList()
    }

    /** The request body — pure, so its shape is unit-testable without a phone. */
    fun requestBody(model: String, turns: List<Turn>): String = buildJsonObject {
        put("model", JsonPrimitive(model))
        put("stream", JsonPrimitive(true))
        // Reuse the KV of the shared prefix across turns: without it every
        // send re-digests the whole conversation from scratch.
        put("cache_prompt", JsonPrimitive(true))
        // Ask for timings on the stream as well as in the final chunk — the
        // chat shows the measured speed, and the store records it.
        put("timings_per_token", JsonPrimitive(true))
        put(
            "messages",
            buildJsonArray {
                turns.forEach { t ->
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive(t.role))
                            if (t.images.isEmpty()) {
                                put("content", JsonPrimitive(t.text))
                            } else {
                                // Multimodal content array — the shape mtmd
                                // reads: the text part first, then one
                                // image_url per picture, each a self-contained
                                // data URL.
                                put(
                                    "content",
                                    buildJsonArray {
                                        if (t.text.isNotBlank()) {
                                            add(
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("text"))
                                                    put("text", JsonPrimitive(t.text))
                                                },
                                            )
                                        }
                                        t.images.forEach { path ->
                                            dataUrl(path)?.let { url ->
                                                add(
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive("image_url"))
                                                        put(
                                                            "image_url",
                                                            buildJsonObject {
                                                                put("url", JsonPrimitive(url))
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }.toString()

    /** `data:<mime>;base64,…` for a picture on disk; null when unreadable. */
    private fun dataUrl(path: String): String? = runCatching {
        val f = File(path)
        if (!f.isFile || f.length() <= 0L) return null
        val mime = when (f.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        "data:$mime;base64," + android.util.Base64.encodeToString(
            f.readBytes(), android.util.Base64.NO_WRAP,
        )
    }.getOrNull()

    /** One `data:` payload → the chunks it carries. Pure, for tests. */
    fun chunksOf(payload: String): List<Chunk> = runCatching {
        val root = Json.parseToJsonElement(payload).jsonObject
        val out = mutableListOf<Chunk>()
        val delta = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("delta")?.jsonObject
        delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { out += Chunk.Thinking(it) }
        delta?.get("content")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { out += Chunk.Text(it) }
        root["timings"]?.jsonObject?.let { t ->
            val tps = t["predicted_per_second"]?.jsonPrimitive?.doubleOrNull
            val n = t["predicted_n"]?.jsonPrimitive?.intOrNull
            if (tps != null && tps > 0.0) out += Chunk.Speed(tps, n ?: 0)
        }
        out
    }.getOrDefault(emptyList())

    /** The engine's error message out of a body, or null when there is none. */
    fun errorOf(payload: String): String? = runCatching {
        val root = Json.parseToJsonElement(payload).jsonObject
        (root["error"] as? JsonObject)?.let { e ->
            e["message"]?.jsonPrimitive?.contentOrNull ?: e.toString()
        }
    }.getOrNull()

    /** llama.cpp's phrasing when the prompt outgrew `-c`. Matched on the two
     *  stable words rather than the whole sentence, which has been reworded
     *  between builds. */
    fun isContextOverflow(message: String): Boolean {
        val m = message.lowercase()
        return "context" in m && ("exceed" in m || "larger than" in m || "too long" in m)
    }

    /**
     * Stream one turn. Returns when the model is done, the caller cancels, or
     * something fails — never throws for an ordinary failure.
     *
     * ⛔ CANCELLATION MUST REACH THE SOCKET, AND ONLY A SECOND COROUTINE CAN
     * TAKE IT THERE. `HttpURLConnection`'s read does not observe coroutine
     * cancellation, so Stop can only work by closing the connection under it.
     * The first attempt hung that close off `job.invokeOnCompletion` — which
     * fires when the job COMPLETES, and this job cannot complete while its
     * thread is parked in `readLine()`. Measured on the owner's phone
     * (2026-09-08): the chat said "stopped", and Qwen wrote all five hundred
     * words of the story anyway, at full speed, to the end. A Stop that
     * doesn't stop is worse than no Stop.
     *
     * A CHILD coroutine parked in [awaitCancellation] is cancelled the moment
     * the caller cancels — before this body unwinds — so its `finally` closes
     * the connection, the blocked read throws, and the turn ends where the
     * user said it should. The reference crosses threads, hence the atomic.
     */
    suspend fun stream(
        model: String,
        turns: List<Turn>,
        onChunk: (Chunk) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val body = requestBody(model, turns).toByteArray()
        val live = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        val closer = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                runCatching { live.get()?.disconnect() }
            }
        }
        try {
            val c = (
                URL("${LocalLlmEngine.BASE_URL}/v1/chat/completions")
                    .openConnection() as HttpURLConnection
                ).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                // The engine demands a key now — the port is shared with every
                // other app on the device (see [LocalApiAccess]).
                LocalApiAccess.authorize(this)
                // Keeps the JSON (a photo makes it megabytes) from being
                // buffered whole and lets the engine start reading at once.
                setFixedLengthStreamingMode(body.size)
            }
            live.set(c)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code != 200) {
                val raw = runCatching { c.errorStream?.bufferedReader()?.readText() }
                    .getOrNull().orEmpty()
                val msg = errorOf(raw) ?: raw.take(300).ifBlank { "engine answered HTTP $code" }
                android.util.Log.w(TAG, "send failed: HTTP $code — $msg")
                return@withContext if (isContextOverflow(msg)) Outcome.TooLong(msg)
                else Outcome.Failed(msg)
            }
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") return@withContext Outcome.Done
                    errorOf(payload)?.let { msg ->
                        android.util.Log.w(TAG, "stream carried an error: $msg")
                        return@withContext if (isContextOverflow(msg)) Outcome.TooLong(msg)
                        else Outcome.Failed(msg)
                    }
                    chunksOf(payload).forEach(onChunk)
                }
            }
            Outcome.Done
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A refused connection is the honest common case: the engine is
            // off, or it died (OOM / the phantom-process killer) mid-turn.
            val why = t.message ?: t.javaClass.simpleName
            android.util.Log.w(TAG, "stream failed: $why")
            Outcome.Failed(
                if ("ECONNREFUSED" in why || "Connection refused" in why) {
                    "the engine is not serving — start the model and try again"
                } else {
                    why
                },
            )
        } finally {
            // Cancelling the watcher is what lets `withContext` return: it is
            // a child, and a parent does not finish while a child is parked.
            closer.cancel()
            runCatching { live.get()?.disconnect() }
        }
    }
}
