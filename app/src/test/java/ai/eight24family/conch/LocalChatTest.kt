package ai.eight24family.conch

import ai.eight24family.conch.linux.chat.LocalApiAccess
import ai.eight24family.conch.linux.chat.LocalChatApi
import ai.eight24family.conch.linux.chat.LocalChatStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire and the window of the in-app local chat, pinned.
 *
 * Everything here is what happens between "the user hit send" and "the engine
 * has a request": the JSON shape llama-server is handed, the stream it
 * answers with, and the arithmetic that decides how much of the conversation
 * fits in the window the engine actually got. All three failed silently when
 * they were wrong — a context overflow reads as a dead chat, a missed
 * `reasoning_content` reads as an empty answer.
 *
 * Pure JVM: every function under test takes what it needs as arguments.
 */
class LocalChatTest {

    private fun user(text: String) = LocalChatApi.Turn("user", text)
    private fun bot(text: String) = LocalChatApi.Turn("assistant", text)

    // ── the window ──

    @Test
    fun `a short conversation is sent whole`() {
        val turns = listOf(user("hi"), bot("hello"), user("how are you"))
        assertEquals(turns, LocalChatApi.fit(turns, ctx = 4096))
    }

    @Test
    fun `an overgrown conversation drops from the front, never the back`() {
        // 40 turns of ~500 tokens each cannot fit a 4K window.
        val turns = (1..40).map { user("x".repeat(1800)) }
        val kept = LocalChatApi.fit(turns, ctx = 4096)
        assertTrue("something had to be dropped", kept.size < turns.size)
        assertEquals("the newest turn must survive", turns.last(), kept.last())
    }

    @Test
    fun `one turn bigger than the whole window is still sent`() {
        // The engine's own error is the honest answer here — silently sending
        // NOTHING would be a chat that ignores the user.
        val huge = user("x".repeat(200_000))
        assertEquals(listOf(huge), LocalChatApi.fit(listOf(huge), ctx = 4096))
    }

    @Test
    fun `an empty conversation stays empty`() {
        assertTrue(LocalChatApi.fit(emptyList(), ctx = 4096).isEmpty())
    }

    @Test
    fun `a picture is charged against the window, not treated as free`() {
        // ⛔ A photo is ~1800 tokens of tiles. Charging it as its path length
        // would fit "four images and a novel" into 4K and bounce every send.
        val withPics = List(3) {
            LocalChatApi.Turn("user", "look", images = listOf("/tmp/a.jpg"))
        } + user("and this")
        val kept = LocalChatApi.fit(withPics, ctx = 4096)
        assertTrue(
            "three images (${3 * LocalChatApi.IMAGE_TOKENS} tokens) cannot fit a 4K window",
            kept.size < withPics.size,
        )
    }

    // ── the request ──

    @Test
    fun `a text turn carries a plain string content`() {
        val body = LocalChatApi.requestBody("qwen3-1p7b", listOf(user("hi")))
        assertTrue(body, body.contains("\"stream\":true"))
        assertTrue("the prefix KV must be reused between turns", body.contains("\"cache_prompt\":true"))
        assertTrue(body, body.contains("\"role\":\"user\""))
        assertTrue(body, body.contains("\"content\":\"hi\""))
        // No system prompt: a chat has no tools to describe, and Codex's ~7K
        // one is minutes of prefill on a phone.
        assertFalse("no system turn may be invented", body.contains("\"role\":\"system\""))
    }

    @Test
    fun `an unreadable picture is skipped, and the words still go`() {
        val body = LocalChatApi.requestBody(
            "gemma-3-4b",
            listOf(LocalChatApi.Turn("user", "what is this", images = listOf("/nope/gone.jpg"))),
        )
        assertTrue("the text part survives", body.contains("what is this"))
        assertFalse("no half-built image part", body.contains("image_url"))
    }

    @Test
    fun `every turn keeps its own role`() {
        val body = LocalChatApi.requestBody("m", listOf(user("a"), bot("b"), user("c")))
        assertEquals(2, Regex("\"role\":\"user\"").findAll(body).count())
        assertEquals(1, Regex("\"role\":\"assistant\"").findAll(body).count())
    }

    // ── the stream ──

    @Test
    fun `a content delta is text`() {
        val chunks = LocalChatApi.chunksOf(
            """{"choices":[{"delta":{"content":"hel"}}]}""",
        )
        assertEquals(listOf(LocalChatApi.Chunk.Text("hel")), chunks)
    }

    @Test
    fun `reasoning arrives apart from the answer`() {
        // llama-server splits `reasoning_content` out of `content` whenever the
        // model's template emits a think block. Pasting it into the reply is
        // how a thinking model looks like it is talking to itself.
        val chunks = LocalChatApi.chunksOf(
            """{"choices":[{"delta":{"reasoning_content":"hmm"}}]}""",
        )
        assertEquals(listOf(LocalChatApi.Chunk.Thinking("hmm")), chunks)
    }

    @Test
    fun `the final chunk's timings are the measured speed`() {
        // The engine reports its own numbers; this is what the store records
        // instead of waiting for someone to press a probe button.
        val chunks = LocalChatApi.chunksOf(
            """{"choices":[{"delta":{},"finish_reason":"stop"}],""" +
                """"timings":{"predicted_n":64,"predicted_per_second":13.5}}""",
        )
        assertEquals(listOf(LocalChatApi.Chunk.Speed(13.5, 64)), chunks)
    }

    @Test
    fun `an empty delta carries nothing`() {
        assertTrue(LocalChatApi.chunksOf("""{"choices":[{"delta":{}}]}""").isEmpty())
        assertTrue(LocalChatApi.chunksOf("not json at all").isEmpty())
    }

    @Test
    fun `an error in the stream is read, not swallowed`() {
        val msg = LocalChatApi.errorOf(
            """{"error":{"code":400,"message":"the request exceeds the available context size"}}""",
        )
        assertEquals("the request exceeds the available context size", msg)
        assertTrue(LocalChatApi.isContextOverflow(msg!!))
        assertNull(LocalChatApi.errorOf("""{"choices":[]}"""))
    }

    @Test
    fun `a context overflow is told apart from an ordinary failure`() {
        assertTrue(LocalChatApi.isContextOverflow("prompt is too long for this context"))
        assertTrue(LocalChatApi.isContextOverflow("input is larger than the context size"))
        assertFalse(LocalChatApi.isContextOverflow("failed to load model"))
        assertFalse(LocalChatApi.isContextOverflow("connection refused"))
    }

    // ── the library ──

    @Test
    fun `a chat is titled by its first question`() {
        val msgs = listOf(
            LocalChatStore.Msg("user", "how do I flash a phone?"),
            LocalChatStore.Msg("assistant", "carefully"),
        )
        assertEquals("how do I flash a phone?", LocalChatStore.titleFrom(msgs))
    }

    @Test
    fun `a long first question is clipped to one row`() {
        val title = LocalChatStore.titleFrom(
            listOf(LocalChatStore.Msg("user", "word ".repeat(40))),
        )
        assertTrue(title, title.length <= 42)
        assertTrue("clipping must be visible", title.endsWith("…"))
    }

    @Test
    fun `a multiline question becomes one line`() {
        val title = LocalChatStore.titleFrom(
            listOf(LocalChatStore.Msg("user", "first\n\n   second")),
        )
        assertEquals("first second", title)
    }

    @Test
    fun `a chat with no question of its own has a name anyway`() {
        assertEquals("new chat", LocalChatStore.titleFrom(emptyList()))
        assertEquals(
            "new chat",
            LocalChatStore.titleFrom(listOf(LocalChatStore.Msg("assistant", "hello?"))),
        )
    }

    // ── who may talk to the engine ──

    private fun grant(pkg: String, token: String) =
        LocalApiAccess.Grant(pkg, pkg, token, 0L)

    @Test
    fun `the key file is one key per line, conch first`() {
        // ⛔ THE FORMAT IS A CONTRACT WITH llama-server: `--api-key-file` reads
        // one key per line and treats `#` lines as comments. A wrapped line or
        // a stray comma turns one key into two that authenticate nothing, and
        // the failure mode is a 401 on a port that looks configured.
        val text = LocalApiAccess.renderKeyFile(
            "OWNKEY",
            listOf(grant("com.example.keyboard", "TOKEN1"), grant("net.tasker", "TOKEN2")),
        )
        val keys = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("OWNKEY", "TOKEN1", "TOKEN2"), keys)
        assertTrue("every key must sit alone on its line", keys.none { "," in it || " " in it })
    }

    @Test
    fun `every grant is named in the file, for whoever debugs it`() {
        val text = LocalApiAccess.renderKeyFile("OWNKEY", listOf(grant("com.example.notes", "T")))
        assertTrue(text, text.lines().any { it.startsWith("#") && "com.example.notes" in it })
    }

    @Test
    fun `no grants means only conch can use the engine`() {
        val keys = LocalApiAccess.renderKeyFile("OWNKEY", emptyList())
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("OWNKEY"), keys)
    }
}
