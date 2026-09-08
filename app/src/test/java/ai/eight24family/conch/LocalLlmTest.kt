package ai.eight24family.conch

import ai.eight24family.conch.linux.LocalLlm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local models: the fits verdict and the catalog's own honesty.
 *
 * The catalog is the store user's whole world — every URL must answer an
 * anonymous GET (verified live 2026-08-31) and every size must be the
 * server's content-length, because the fits verdict and the progress bar
 * are computed from them.
 */
class LocalLlmTest {

    private val smallest = LocalLlm.CATALOG.first { it.id == "qwen3_5-0_8b" }

    @Test
    fun `fits verdict follows free ram`() {
        val need = LocalLlm.ramNeeded(smallest)
        assertEquals(LocalLlm.Fit.FITS, LocalLlm.fit(smallest, need + 600_000_000L))
        assertEquals(LocalLlm.Fit.TIGHT, LocalLlm.fit(smallest, need + 100_000_000L))
        assertEquals(LocalLlm.Fit.SHORT, LocalLlm.fit(smallest, need - 1L))
    }

    @Test
    fun `a local model routes codex to the loopback engine`() {
        val cmd = ai.eight24family.conch.agent.codex.CodexSpec.buildExecCommand(
            ai.eight24family.conch.agent.spec.ExecInput(
                text = "hi",
                resumeId = null,
                model = LocalLlm.MODEL_ARG_PREFIX + "qwen3_5-2b",
                approvalMode = ai.eight24family.conch.data.prefs.AgentApprovalMode.AUTO,
                cwdSnapshot = null,
                reasoningEffort = null,
            ),
        )
        // The provider flags are what point the REAL Codex at the phone's
        // engine; wire_api must be "responses" (0.151 dropped "chat", and
        // llama-server serves /v1/responses — both verified on-device).
        assertTrue(cmd.contains("model_providers.conchlocal.base_url=\"http://127.0.0.1:8317/v1\""))
        assertTrue(cmd.contains("model_providers.conchlocal.wire_api=\"responses\""))
        // Codex must know the engine's real window (minus reply headroom) so
        // it compacts before the wall instead of dying on a 400 mid-chat.
        assertTrue(
            cmd.contains(
                "model_context_window=" +
                    (ai.eight24family.conch.linux.LocalLlmEngine.CTX_TOKENS - 1024),
            ),
        )
        assertTrue(cmd.contains("model_provider=\"conchlocal\""))
        // ⛔ THE ENGINE AUTHENTICATES NOW, AND CODEX SENDS NO Authorization AT
        // ALL WITHOUT `env_key`. These two must agree: the provider names the
        // variable, the command exports it. Rename one and the whole
        // local-agent path 401s on its first turn — which is what would have
        // shipped on 2026-09-08 had the app-server launch not been fixed with
        // the exec one (it builds its own command line, in
        // AgentSessionCodexAppServer).
        assertTrue(cmd, cmd.contains("model_providers.conchlocal.env_key=\"OPENAI_API_KEY\""))
        assertTrue(cmd, cmd.contains("OPENAI_API_KEY="))
        assertTrue(
            "the key must be exported for CODEX, not for the printf feeding it",
            cmd.contains("| OPENAI_API_KEY="),
        )
        assertTrue(cmd.contains("--model 'qwen3_5-2b'") || cmd.contains("--model qwen3_5-2b"))
        // The prefix is plumbing, not a model name codex should ever see.
        assertTrue(!cmd.contains("local:"))
    }

    @Test
    fun `an ordinary model gets no provider override`() {
        val cmd = ai.eight24family.conch.agent.codex.CodexSpec.buildExecCommand(
            ai.eight24family.conch.agent.spec.ExecInput(
                text = "hi",
                resumeId = null,
                model = "gpt-5.5",
                approvalMode = ai.eight24family.conch.data.prefs.AgentApprovalMode.AUTO,
                cwdSnapshot = null,
                reasoningEffort = null,
            ),
        )
        assertTrue(!cmd.contains("model_provider"))
    }

    @Test
    fun `catalog stays open, q4, and honestly sized`() {
        // Three chat models. The search and voice models are counted
        // separately below, because none of the chat rules apply to them -
        // which is exactly what `isBrain` exists to say.
        assertEquals(3, LocalLlm.CATALOG.count { it.isBrain })
        assertTrue(LocalLlm.CATALOG.all { it.url.startsWith("https://huggingface.co/") })
        // Only the chat models are GGUF: the voice model is whisper's own
        // format (see the voice test below).
        assertTrue(LocalLlm.CATALOG.filter { it.isBrain }.all { it.file.endsWith(".gguf") })
        // A drift here means a URL now serves a different file — re-verify
        // with a HEAD request before shipping the new number.
        assertTrue(
            LocalLlm.CATALOG.filter { it.isBrain }
                .all { it.bytes > 300_000_000L && it.bytes < 3_000_000_000L },
        )
        // Ids are filesystem- and script-safe.
        assertTrue(LocalLlm.CATALOG.all { it.id.matches(Regex("[a-z0-9_-]+")) })
    }

    @Test
    fun `a model that is not a brain is never offered as one`() {
        // ⛔ ONE QUESTION, ASKED EVERYWHERE. There are three kinds of model on
        // the phone now - chat, embed, voice - and only the first has a chat
        // head. `isBrain` is the single place that decides, so a fourth kind
        // (a reranker, a TTS voice) cannot slip into a picker by default.
        LocalLlm.CATALOG.forEach { m ->
            assertEquals(
                "isBrain must be exactly 'neither embedder nor voice' for ${m.id}",
                !m.embedder && !m.voice,
                m.isBrain,
            )
        }
        val roles = LocalLlm.CATALOG.filterNot { it.isBrain }
        assertEquals("one search model and one voice model", 2, roles.size)
        assertTrue("both are verified downloads", roles.all { it.sha256 != null })
        // Neither may sit at the head of the list: "the first ready model" is
        // a default-brain idiom in more than one place.
        assertTrue(LocalLlm.BUILTIN.take(3).all { it.isBrain })
    }

    @Test
    fun `the voice model is whisper's own format, priced as a one-shot`() {
        val v = LocalLlm.CATALOG.single { it.voice }
        // NOT a gguf: whisper.cpp has its own ggml format, and nothing but
        // LocalVoice ever opens this file.
        assertTrue(v.file.endsWith(".bin"))
        assertTrue(v.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/"))
        // Weights plus a small fixed overhead: it runs, answers and exits.
        assertEquals(v.bytes + 250_000_000L, LocalLlm.ramNeeded(v))
    }

    @Test
    fun `the search model is exactly one, and is never a brain`() {
        // ⛔ AN EMBEDDER HAS NO CHAT HEAD. Offered as a model it would come up
        // healthy and answer nothing, so every "pick a model" path filters it
        // (the picker, the codex default-brain fallback, the library row's
        // tap) and the engine refuses to launch it outright.
        val embedders = LocalLlm.CATALOG.filter { it.embedder }
        assertEquals(1, embedders.size)
        val e = embedders.single()
        assertTrue("its size is the price of good ranking", e.bytes > 500_000_000L)
        assertTrue(e.url.startsWith("https://huggingface.co/"))
        assertTrue("verified like every other download", e.sha256 != null)
        // It must be LAST: "the first ready model" is a default-brain idiom in
        // more than one place, and an embedder at the head of the list would
        // silently become that default.
        assertTrue(LocalLlm.BUILTIN.last().embedder)
        assertTrue(LocalLlm.BUILTIN.take(3).none { it.embedder })
    }
}
