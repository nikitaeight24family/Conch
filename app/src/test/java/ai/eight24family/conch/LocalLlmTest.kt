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
        assertEquals(3, LocalLlm.CATALOG.size)
        assertTrue(LocalLlm.CATALOG.all { it.url.startsWith("https://huggingface.co/") })
        assertTrue(LocalLlm.CATALOG.all { it.file.endsWith(".gguf") })
        // A drift here means a URL now serves a different file — re-verify
        // with a HEAD request before shipping the new number.
        assertTrue(LocalLlm.CATALOG.all { it.bytes > 300_000_000L && it.bytes < 3_000_000_000L })
        // Ids are filesystem- and script-safe.
        assertTrue(LocalLlm.CATALOG.all { it.id.matches(Regex("[a-z0-9_-]+")) })
    }
}
