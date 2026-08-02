package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ModelSwitchWarning
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins Anthropic's own gating rules (read out of claude.exe 2.1.219, see
 * [ModelSwitchWarning]). The user asked for their behaviour exactly — a warning
 * that fires "abi kogda" would be worse than none, because he would learn to
 * dismiss it and then eat the one that mattered.
 */
class ModelSwitchWarningTest {

    private fun warn(
        next: String? = "sonnet",
        current: String? = "fable",
        hasMessages: Boolean = true,
        outputTokens: Long = 5_000,
        ackedAtTokens: Long? = null,
        resolve: (String) -> String = { it },
    ) = ModelSwitchWarning.shouldWarn(
        next = next,
        current = current,
        hasMessages = hasMessages,
        outputTokens = outputTokens,
        ackedAtTokens = ackedAtTokens,
        resolve = resolve,
    )

    @Test
    fun `warns on a real switch in a conversation that has cached history`() {
        assertTrue(warn())
    }

    @Test
    fun `never warns in an empty chat — nothing is cached yet`() {
        assertFalse(warn(hasMessages = false))
    }

    @Test
    fun `never warns before anything has been generated`() {
        assertFalse(warn(outputTokens = 0))
    }

    @Test
    fun `never warns twice at the same point once acknowledged`() {
        assertFalse(warn(outputTokens = 5_000, ackedAtTokens = 5_000))
    }

    @Test
    fun `warns again once the conversation has grown past the acknowledgement`() {
        assertTrue(warn(outputTokens = 9_000, ackedAtTokens = 5_000))
    }

    @Test
    fun `never warns when the model is effectively unchanged`() {
        assertFalse(warn(next = "sonnet", current = "sonnet"))
    }

    /** An alias and the id it resolves to are the SAME model — the CLI compares
     *  resolved values (`Goe`), and so must we, or picking the row that is
     *  already active would warn about a switch that isn't one. */
    @Test
    fun `never warns when an alias resolves to the running model`() {
        val resolve: (String) -> String = { s -> if (s == "sonnet") "claude-sonnet-5" else s }
        assertFalse(warn(next = "sonnet", current = "claude-sonnet-5", resolve = resolve))
    }

    @Test
    fun `clearing the pick is not a switch`() {
        assertFalse(warn(next = null))
    }
}
