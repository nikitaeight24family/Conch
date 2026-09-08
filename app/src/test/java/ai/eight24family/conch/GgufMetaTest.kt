package ai.eight24family.conch

import ai.eight24family.conch.linux.GgufMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The GGUF header reader, pinned against hand-built headers.
 *
 * ⛔ THIS ARITHMETIC DECIDES WHETHER A MODEL IS OFFERED AT ALL. `ramNeeded`
 * charges `kvPerTok × context`, and the store hides a row that does not fit —
 * so a parser that returns a wrong KV size, or silently gives up, is a store
 * that lies in one direction or the other. The two shapes that matter are
 * both here: a dense model (one `head_count_kv` number) and a HYBRID one
 * (a per-layer array with zeros, where charging every layer would price the
 * model far above its real cost).
 *
 * Pure JVM: the parser takes a stream.
 */
class GgufMetaTest {

    // ── a minimal GGUF writer, little-endian like the format ──

    private class Header {
        private val body = ByteArrayOutputStream()
        private var count = 0

        fun u32(k: String, v: Int) = kv(k, 4) { u32(v) }
        fun str(k: String, v: String) = kv(k, 8) { str(v) }
        fun u32Array(k: String, v: List<Int>) = kv(k, 9) {
            u32(4); u64(v.size.toLong()); v.forEach { u32(it) }
        }

        /** An array of strings, the tokenizer's shape — the thing the parser
         *  must step over without loading. */
        fun strArray(k: String, v: List<String>) = kv(k, 9) {
            u32(8); u64(v.size.toLong()); v.forEach { str(it) }
        }

        private fun kv(key: String, type: Int, value: Header.() -> Unit) {
            str(key); u32(type); value(); count++
        }

        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write("GGUF".toByteArray(Charsets.US_ASCII))
            out.write(le32(3))              // version
            out.write(le64(0))              // tensor count
            out.write(le64(count.toLong())) // kv count
            out.write(body.toByteArray())
            return out.toByteArray()
        }

        private fun u32(v: Int) = body.write(le32(v))
        private fun u64(v: Long) = body.write(le64(v))
        private fun str(v: String) {
            val b = v.toByteArray(Charsets.UTF_8)
            body.write(le64(b.size.toLong())); body.write(b)
        }

        private fun le32(v: Int) = ByteArray(4) { ((v shr (8 * it)) and 0xff).toByte() }
        private fun le64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xff).toByte() }
    }

    private fun read(h: Header) = GgufMeta.read(h.bytes().inputStream())

    // ── dense ──

    @Test
    fun `a dense model is priced from layers, kv heads and head dim`() {
        val h = Header().apply {
            str("general.architecture", "llama")
            str("general.name", "Llama 3.2 3B Instruct")
            u32("llama.block_count", 28)
            u32("llama.attention.head_count", 24)
            u32("llama.attention.head_count_kv", 8)
            u32("llama.embedding_length", 3072)
        }
        val m = read(h)!!
        assertEquals("llama", m.arch)
        assertEquals("Llama 3.2 3B Instruct", m.name)
        assertEquals(28, m.layers)
        assertEquals(28, m.kvLayers)
        assertEquals(8, m.kvHeads)
        // No key_length in the header → embedding / heads = 3072 / 24.
        assertEquals(128, m.headDim)
        // 2 (K+V) x 28 layers x 8 heads x 128 dim x 2 bytes.
        assertEquals(2L * 28 * 8 * 128 * 2, m.kvPerTok)
    }

    @Test
    fun `an explicit key_length wins over the embedding division`() {
        val h = Header().apply {
            str("general.architecture", "qwen3")
            u32("qwen3.block_count", 36)
            u32("qwen3.attention.head_count", 32)
            u32("qwen3.attention.head_count_kv", 8)
            u32("qwen3.embedding_length", 2560)   // /32 would give 80
            u32("qwen3.attention.key_length", 128)
        }
        assertEquals(128, read(h)!!.headDim)
    }

    // ── hybrid: the case that decides whether a small phone sees a model ──

    @Test
    fun `a hybrid model is charged only for the layers that hold a KV cache`() {
        // Qwen3.5's shape: attention on every fourth layer, zero on the rest.
        val perLayer = (0 until 24).map { if (it % 4 == 3) 2 else 0 }
        val h = Header().apply {
            str("general.architecture", "qwen35")
            u32("qwen35.block_count", 24)
            u32("qwen35.attention.head_count", 16)
            u32Array("qwen35.attention.head_count_kv", perLayer)
            u32("qwen35.attention.key_length", 256)
        }
        val m = read(h)!!
        assertEquals(24, m.layers)
        assertEquals("only the attention layers count", 6, m.kvLayers)
        assertEquals(2, m.kvHeads)
        assertEquals(2L * 6 * 2 * 256 * 2, m.kvPerTok)
        // The whole point: charging all 24 layers would be four times as much.
        assertTrue(m.kvPerTok < 2L * 24 * 2 * 256 * 2)
    }

    // ── robustness ──

    @Test
    fun `the tokenizer's string army is stepped over, not loaded`() {
        val h = Header().apply {
            str("general.architecture", "llama")
            strArray("tokenizer.ggml.tokens", List(20_000) { "tok$it" })
            u32("llama.block_count", 4)
            u32("llama.attention.head_count", 4)
            u32("llama.attention.head_count_kv", 2)
            u32("llama.embedding_length", 512)
        }
        // The keys AFTER the array are what proves it skipped exactly right.
        val m = read(h)!!
        assertEquals(4, m.layers)
        assertEquals(2, m.kvHeads)
        assertEquals(128, m.headDim)
    }

    @Test
    fun `a file that is not a GGUF is not a model`() {
        assertNull(GgufMeta.read("this is a zip, not weights".toByteArray().inputStream()))
        assertNull(GgufMeta.read(ByteArray(0).inputStream()))
    }

    @Test
    fun `a header with no architecture yields nothing`() {
        val h = Header().apply { str("general.name", "mystery") }
        assertNull(read(h))
    }

    @Test
    fun `an unknown architecture costs the caller nothing but the flat guess`() {
        // Present but incomplete: the store must fall back, not invent a number.
        val h = Header().apply {
            str("general.architecture", "brandnew")
            u32("brandnew.block_count", 12)
        }
        val m = read(h)!!
        assertEquals(0L, m.kvPerTok)
    }
}
