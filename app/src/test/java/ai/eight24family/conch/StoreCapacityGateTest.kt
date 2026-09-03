package ai.eight24family.conch

import ai.eight24family.conch.linux.LocalLlmEngine
import ai.eight24family.conch.linux.store.HfBrowse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic the model store gates on, pinned.
 *
 * These numbers decide whether a phone is shown a shelf or a blank page, and
 * they were wrong in both directions before 2026-09-03: a 4 GB phone saw ONE
 * row out of eighteen (and not one that could drive an agent) because a 16K
 * context nobody asked for was charged to every model, while an 8 GB phone was
 * OFFERED a 4B whose vision projector the old flat overhead never counted.
 *
 * Pure JVM: [LocalLlmEngine.ctxWithin] and [HfBrowse.paramsB] take everything
 * they need as arguments, so no Context, no device and no catalog is involved.
 */
class StoreCapacityGateTest {

    private val compute = 1_200_000_000L

    /** The three built-in Qwen3.5 sizes, from their published configs. */
    private val q08 = Triple(507_154_688L + 204_987_232L, 12_288L, LocalLlmEngine.CTX_CHAT_FLOOR)
    private val q2 = Triple(1_214_873_856L + 668_227_264L, 12_288L, LocalLlmEngine.CTX_AGENT_FLOOR)
    private val q4 = Triple(2_583_221_408L + 672_423_616L, 32_768L, LocalLlmEngine.CTX_AGENT_FLOOR)

    private fun budgetOf(marketedGb: Double): Long =
        (marketedGb * 1_000_000_000L * 0.92 * 0.62).toLong()

    private fun needAt(model: Triple<Long, Long, Int>, ctx: Int): Long =
        model.first + model.second * ctx + compute

    // ── the window follows the phone ──

    @Test
    fun `a roomy budget keeps the full window`() {
        val ctx = LocalLlmEngine.ctxWithin(
            budget = budgetOf(16.0), fixedBytes = q4.first + compute,
            kvPerTok = q4.second, floor = q4.third,
        )
        assertEquals(LocalLlmEngine.CTX_MAX, ctx)
    }

    @Test
    fun `a tight budget steps the window down, never past the floor`() {
        // Just enough for the fixed cost plus a 4K window and nothing more.
        val budget = q2.first + compute + q2.second * 4096
        val ctx = LocalLlmEngine.ctxWithin(
            budget = budget, fixedBytes = q2.first + compute,
            kvPerTok = q2.second, floor = q2.third,
        )
        // q2 is agent-capable: its floor is 8K, and the floor wins over the fit.
        assertEquals(LocalLlmEngine.CTX_AGENT_FLOOR, ctx)
    }

    @Test
    fun `an unknown architecture cannot be sized, so it keeps the ceiling`() {
        assertEquals(
            LocalLlmEngine.CTX_MAX,
            LocalLlmEngine.ctxWithin(budgetOf(4.0), 999_000_000_000L, 0L, 4096),
        )
    }

    // ── the gate, at the four RAM classes that matter ──

    @Test
    fun `the app's own default pick fits a 4 GB phone`() {
        // It missed by 30 MB under the old flat 1.8 GB overhead — the store hid
        // the model the app ships as its first suggestion.
        assertTrue(needAt(q08, q08.third) <= budgetOf(4.0))
    }

    @Test
    fun `no agent-capable builtin is offered to a 4 GB phone`() {
        val budget = budgetOf(4.0)
        assertTrue(needAt(q2, q2.third) > budget)
        assertTrue(needAt(q4, q4.third) > budget)
    }

    @Test
    fun `the 4B is not offered to an 8 GB phone once its projector is counted`() {
        // Old pricing said 4.38G and let it through an 8 GB budget of 4.56G;
        // it really needs ~4.7G before the first token.
        val need = needAt(q4, q4.third)
        assertTrue(need > budgetOf(8.0))
        assertTrue(need <= budgetOf(12.0))
    }

    @Test
    fun `the everyday builtin fits a 6 GB phone`() {
        assertTrue(needAt(q2, q2.third) <= budgetOf(6.0))
    }

    // ── the long tail names its own size ──

    @Test
    fun `parameter counts are read off repo names`() {
        assertEquals(4.0, HfBrowse.paramsB("unsloth/Qwen3-4B-GGUF")!!, 0.001)
        assertEquals(8.0, HfBrowse.paramsB("bartowski/Meta-Llama-3.1-8B-Instruct-GGUF")!!, 0.001)
        assertEquals(1.5, HfBrowse.paramsB("x/DeepSeek-R1-Distill-Qwen-1.5B-GGUF")!!, 0.001)
    }

    @Test
    fun `a mixture of experts prices on the total, not the active share`() {
        // 30B-A3B: every expert is resident even when only 3B compute.
        assertEquals(30.0, HfBrowse.paramsB("unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF")!!, 0.001)
    }

    @Test
    fun `models that name themselves in millions are sized too`() {
        // The sizes a small phone most needs to see, and the ones a B-only
        // pattern reads as no size at all.
        assertEquals(0.270, HfBrowse.paramsB("ggml-org/gemma-3-270m-GGUF")!!, 0.001)
        assertEquals(0.135, HfBrowse.paramsB("x/SmolLM2-135M-Instruct-GGUF")!!, 0.001)
    }

    @Test
    fun `a name with no size at all estimates nothing rather than guessing`() {
        assertNull(HfBrowse.paramsB("someone/my-cool-model-GGUF"))
    }

    @Test
    fun `a 70B is priced far above any phone`() {
        val p = HfBrowse.paramsB("TheBloke/Llama-2-70B-Chat-GGUF")!!
        val est = (p * 620_000_000.0).toLong() + compute
        assertTrue(est > budgetOf(24.0))
    }
}
