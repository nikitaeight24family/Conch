package ai.eight24family.conch

import ai.eight24family.conch.linux.embed.ChatIndex
import ai.eight24family.conch.linux.embed.LocalEmbedApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure halves of on-device search: how text is cut into embeddable
 * units, and how vectors are compared.
 *
 * ⛔ THE CHUNK CAP IS NOT COSMETIC. An embedding input must fit ONE physical
 * batch — there is no chunked prefill for a non-causal model — and the first
 * indexing pass on the owner's phone died exactly there:
 * `input (517 tokens) is too large to process`. Cyrillic spends ~2-3
 * characters per token against English's ~4, so a cap that looks generous in
 * one language overflows in the other; these tests pin the cap and the
 * splitting so that failure cannot come back quietly.
 */
class ChatIndexTest {

    // ── chunking ──

    @Test
    fun `a short message is one chunk`() {
        assertEquals(listOf("what planet did I mention first?"),
            ChatIndex.chunk("what planet did I mention first?"))
    }

    @Test
    fun `noise too short to mean anything is dropped`() {
        assertTrue(ChatIndex.chunk("ok").isEmpty())
        assertTrue(ChatIndex.chunk("   ").isEmpty())
        assertTrue(ChatIndex.chunk("").isEmpty())
    }

    @Test
    fun `a long message is cut into pieces that all fit the batch`() {
        val text = (1..400).joinToString(" ") { "word$it" }
        val parts = ChatIndex.chunk(text)
        assertTrue("a 2800-character message must be split", parts.size > 1)
        parts.forEach {
            assertTrue("chunk of ${it.length} exceeds the cap", it.length <= LocalEmbedApi.MAX_CHARS)
        }
    }

    @Test
    fun `nothing is thrown away by the split`() {
        // ⛔ TRUNCATION WOULD LOSE THE TAIL, AND THE TAIL IS WHERE THE
        // SPECIFIC DETAIL USUALLY IS. Every word of the original has to
        // survive into some chunk.
        val words = (1..300).map { "w$it" }
        val parts = ChatIndex.chunk(words.joinToString(" "))
        val recovered = parts.joinToString(" ").split(Regex("\\s+"))
        assertEquals(words.size, recovered.size)
        assertEquals(words.first(), recovered.first())
        assertEquals(words.last(), recovered.last())
    }

    @Test
    fun `chunks end on word boundaries when there are any`() {
        val parts = ChatIndex.chunk((1..400).joinToString(" ") { "word$it" })
        parts.dropLast(1).forEach {
            assertTrue("chunk ends mid-word: ...${it.takeLast(12)}", it.last() != ' ')
            assertTrue("a boundary chunk should be a whole word", it.matches(Regex("[\\w\\s]+")))
        }
    }

    @Test
    fun `a wall with no spaces is cut hard rather than skipped`() {
        // Base64, a stack trace, a minified blob: no space to break on, and
        // still worth indexing.
        val blob = "A".repeat(LocalEmbedApi.MAX_CHARS * 2 + 50)
        val parts = ChatIndex.chunk(blob)
        assertTrue(parts.size >= 2)
        parts.forEach { assertTrue(it.length <= LocalEmbedApi.MAX_CHARS) }
    }

    @Test
    fun `a two-chars-per-token script is capped by the same character budget`() {
        // The cap exists FOR this case, not for English: 900 characters of a
        // script that costs ~2 characters per token on bge-m3's tokenizer —
        // Greek here, Russian and the other Cyrillic scripts identically — is
        // ~450 tokens, still under the engine's 1024-token batch.
        val nonLatin = "αναβάθμιση τηλεφώνου μέσω fastboot ".repeat(60)
        ChatIndex.chunk(nonLatin).forEach {
            assertTrue(it.length <= LocalEmbedApi.MAX_CHARS)
        }
    }

    // ── comparing ──

    @Test
    fun `cosine is one for the same vector and zero for an orthogonal one`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(1f, LocalEmbedApi.cosine(a, a), 1e-5f)
        assertEquals(0f, LocalEmbedApi.cosine(a, b), 1e-5f)
    }

    @Test
    fun `cosine ignores length, so an unnormalised index still ranks`() {
        val a = floatArrayOf(0.6f, 0.8f)
        val long = floatArrayOf(6f, 8f)
        assertEquals(1f, LocalEmbedApi.cosine(a, long), 1e-5f)
    }

    @Test
    fun `a mismatched dimension is not a similarity`() {
        // An index built by another embedder must never produce a score that
        // looks like an answer.
        assertTrue(LocalEmbedApi.cosine(FloatArray(384), FloatArray(1024)) < 0f)
        assertTrue(LocalEmbedApi.cosine(FloatArray(0), FloatArray(0)) < 0f)
    }

    @Test
    fun `a zero vector cannot be similar to anything`() {
        assertTrue(LocalEmbedApi.cosine(FloatArray(4), floatArrayOf(1f, 2f, 3f, 4f)) < 0f)
    }

    @Test
    fun `nothing is prepended to the text the model sees`() {
        // bge-m3 measured WORSE with e5's "query:"/"passage:" prefixes; the
        // prepare step only trims and caps.
        assertEquals("what was that about the lighthouse", LocalEmbedApi.prepare("  what was that about the lighthouse  "))
        assertEquals(LocalEmbedApi.MAX_CHARS, LocalEmbedApi.prepare("x".repeat(5_000)).length)
    }
}
