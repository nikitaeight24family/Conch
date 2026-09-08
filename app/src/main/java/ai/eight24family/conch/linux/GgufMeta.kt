package ai.eight24family.conch.linux

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * What a GGUF says about itself, read straight off its header.
 *
 * ⛔ WITHOUT THIS, EVERY MODEL THE CATALOG DID NOT HAND-CURATE IS PRICED BY A
 * GUESS. `LocalLlm.ramNeeded` charges `kvPerTok × context` when it knows the
 * architecture and otherwise falls back to a flat 1.8 GB — which was wrong in
 * both directions on the curated shelf (it over-charged a 0.8B by 0.4 GB and
 * under-charged a 4B by its whole vision projector) and is exactly as wrong
 * for a Hugging Face browse hit or a file the owner imported himself. The
 * numbers are IN the file: every GGUF's header carries the layer count, the
 * KV-head count and the head dimension, which is the entire formula.
 *
 * Reads the header only, and skips the tokenizer arrays without materialising
 * them — `tokenizer.ggml.tokens` alone is often 150 000 strings, and a store
 * that had to load them to size a row would stall on every tap.
 *
 * Returns null rather than throwing on anything unexpected: an unreadable
 * header means "architecture unknown", which the caller already handles
 * honestly.
 */
object GgufMeta {

    private const val TAG = "Conch-Gguf"
    private const val MAGIC = 0x46554747 // "GGUF", little-endian

    /** Guardrails against a malformed or hostile file: a header is small, and
     *  nothing here should ever read a gigabyte or allocate a huge string. */
    private const val MAX_KV = 4_096
    private const val MAX_STRING = 1 shl 16
    private const val MAX_ARRAY = 4_000_000
    private const val MAX_HEADER_BYTES = 256L * 1024 * 1024

    data class Meta(
        val arch: String,
        /** `general.name`, when the file carries one. */
        val name: String?,
        val layers: Int,
        /** Layers that actually hold a per-token KV cache. Hybrid families
         *  (Qwen3.5, Granite-H, LFM2) keep attention on only some layers and
         *  publish `head_count_kv` as an ARRAY with zeros for the rest —
         *  charging all layers would price such a model far above its real
         *  cost, which is the difference between "fits" and "hidden". */
        val kvLayers: Int,
        val kvHeads: Int,
        val headDim: Int,
    ) {
        /** The store's arithmetic: 2 (K and V) × layers × kv-heads × head-dim
         *  × 2 bytes (f16). Zero when the header did not carry enough to say,
         *  which keeps [LocalLlm.ramNeeded] on its conservative flat guess. */
        val kvPerTok: Long
            get() = if (kvLayers > 0 && kvHeads > 0 && headDim > 0) {
                2L * kvLayers * kvHeads * headDim * 2L
            } else {
                0L
            }
    }

    fun read(file: File): Meta? = runCatching {
        if (!file.isFile || file.length() < 32) return null
        FileInputStream(file).use { read(it) }
    }.getOrElse {
        android.util.Log.i(TAG, "could not read ${file.name}: ${it.message}")
        null
    }

    /**
     * Visible for tests: the same parse over any stream.
     *
     * Returns null on ANY malformed input rather than throwing — the promise
     * this object makes to its callers, and one the file wrapper alone used to
     * keep: a truncated header walked straight out as an EOFException (caught
     * by GgufMetaTest, 2026-09-08), which for the importer would have been a
     * crash where "architecture unknown" was the honest answer.
     */
    internal fun read(raw: InputStream): Meta? = runCatching { parse(raw) }.getOrNull()

    private fun parse(raw: InputStream): Meta? {
        val counted = CountingStream(BufferedInputStream(raw, 1 shl 16))
        val d = DataInputStream(counted)
        if (readU32(d) != MAGIC.toLong()) return null
        readU32(d) // version — v2 and v3 share this header layout
        readU64(d) // tensor count
        val kvCount = readU64(d)
        if (kvCount <= 0 || kvCount > MAX_KV) return null

        val kv = HashMap<String, Any>(64)
        for (i in 0 until kvCount) {
            if (counted.count > MAX_HEADER_BYTES) return null
            val key = readString(d) ?: return null
            val type = readU32(d).toInt()
            val wanted = key == "general.architecture" || key == "general.name" ||
                (key.contains('.') && WANTED_SUFFIXES.any { key.endsWith(it) })
            val value = readValue(d, type, keep = wanted) ?: return null
            if (wanted) kv[key] = value
        }

        val arch = kv["general.architecture"] as? String ?: return null
        fun num(suffix: String): Int? = (kv["$arch.$suffix"] as? Number)?.toInt()

        val layers = num("block_count") ?: return null
        // head_count_kv is a single number on a dense model and a per-layer
        // array on a hybrid one.
        val kvHeadsRaw = kv["$arch.attention.head_count_kv"]
        val perLayer = (kvHeadsRaw as? LongArray)?.map { it.toInt() }
        val kvHeads = perLayer?.firstOrNull { it > 0 } ?: (kvHeadsRaw as? Number)?.toInt() ?: 0
        val kvLayers = perLayer?.count { it > 0 } ?: layers

        val embedding = num("embedding_length") ?: 0
        val heads = num("attention.head_count") ?: 0
        val headDim = num("attention.key_length")
            ?: if (heads > 0 && embedding > 0) embedding / heads else 0

        return Meta(
            arch = arch,
            name = kv["general.name"] as? String,
            layers = layers,
            kvLayers = kvLayers,
            kvHeads = kvHeads,
            headDim = headDim,
        )
    }

    /** Suffixes worth keeping — everything else is skipped unread. */
    private val WANTED_SUFFIXES = listOf(
        ".block_count",
        ".embedding_length",
        ".attention.head_count",
        ".attention.head_count_kv",
        ".attention.key_length",
        ".attention.value_length",
    )

    // ── the wire format (little-endian throughout) ──

    private fun readU32(d: DataInputStream): Long {
        val b = ByteArray(4); d.readFully(b)
        return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
            ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
    }

    private fun readU64(d: DataInputStream): Long {
        val b = ByteArray(8); d.readFully(b)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xff)
        return v
    }

    private fun readString(d: DataInputStream): String? {
        val len = readU64(d)
        if (len < 0 || len > MAX_STRING) {
            // Too long to be a key or a name — skip it rather than allocate.
            if (len in 0..Int.MAX_VALUE.toLong()) skip(d, len) else return null
            return ""
        }
        val b = ByteArray(len.toInt()); d.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun skip(d: DataInputStream, n: Long) {
        var left = n
        val buf = ByteArray(1 shl 16)
        while (left > 0) {
            val chunk = minOf(left, buf.size.toLong()).toInt()
            d.readFully(buf, 0, chunk)
            left -= chunk
        }
    }

    /** Fixed width of a scalar type, or null for the variable-length ones. */
    private fun width(type: Int): Int? = when (type) {
        0, 1, 7 -> 1      // u8 / i8 / bool
        2, 3 -> 2         // u16 / i16
        4, 5, 6 -> 4      // u32 / i32 / f32
        10, 11, 12 -> 8   // u64 / i64 / f64
        else -> null      // 8 = string, 9 = array
    }

    /**
     * One value. [keep] false means "consume it, return a placeholder" — that
     * is how the tokenizer's hundred-thousand-string arrays cost nothing.
     */
    private fun readValue(d: DataInputStream, type: Int, keep: Boolean): Any? {
        width(type)?.let { w ->
            val b = ByteArray(w); d.readFully(b)
            if (!keep) return Unit
            var v = 0L
            for (i in w - 1 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        if (type == 8) {
            val s = readString(d) ?: return null
            return if (keep) s else Unit
        }
        if (type != 9) return null // unknown type: the header is not parseable
        val elemType = readU32(d).toInt()
        val count = readU64(d)
        if (count < 0 || count > MAX_ARRAY) return null
        val w = width(elemType)
        if (w != null) {
            if (!keep) { skip(d, count * w); return Unit }
            val out = LongArray(count.toInt())
            for (i in 0 until count.toInt()) {
                out[i] = (readValue(d, elemType, keep = true) as? Long) ?: 0L
            }
            return out
        }
        if (elemType == 8) {
            // An array of strings: read each length and step over it. Kept
            // values are not needed anywhere, so it always skips.
            for (i in 0 until count) {
                val len = readU64(d)
                if (len < 0) return null
                skip(d, len)
            }
            return Unit
        }
        return null // nested arrays are not a thing GGUF emits
    }

    /** Counts what the parse has consumed, so a malformed header cannot walk
     *  us through a five-gigabyte file. */
    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int = inner.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) count += it }
    }
}
