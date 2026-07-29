package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.claudeDefaultModel
import ai.eight24family.conch.agent.claude.parseClaudeModelMenu
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Default (recommended)" is Anthropic's SUGGESTION. The ✔ row is what the CLI
 * is actually set to. Reading the former as the effective model made the topbar
 * announce "Sonnet 5" on a box whose own banner said Opus (user, 2026-07-25).
 */
class ClaudeCheckedRowWinsTest {

    private val realMenu = """
        Select model
        Switch between Claude models. Your pick becomes the default for new sessions.

            1. Default (recommended)  Sonnet 5 · Efficient for routine tasks
            2. Sonnet                 Sonnet 5 · Efficient for routine tasks
            3. Fable                  Fable 5 · Most capable for your hardest tasks
          ❯ 4. Opus ✔                 Opus 4.8 · Best for everyday, complex tasks
            5. Haiku                  Haiku 4.5 · Fastest for quick answers
    """.trimIndent()

    @Test
    fun `the checked row wins over the recommended row`() {
        parseClaudeModelMenu(realMenu)
        assertEquals("Opus 4.8", claudeDefaultModel)
    }

    @Test
    fun `with no checkmark the recommended row is used`() {
        val noCheck = realMenu.replace(" ✔", "")
        parseClaudeModelMenu(noCheck)
        assertEquals("Sonnet 5", claudeDefaultModel)
    }

    @Test
    fun `a brand-new model family parses with no code change`() {
        // The whole point: an unknown family must just work when the CLI ships it.
        val future = """
            Select model
                1. Default (recommended)  Sonnet 5 · Efficient for routine tasks
              ❯ 2. Opus ✔                 Opus 5 · Best for everyday, complex tasks
        """.trimIndent()
        parseClaudeModelMenu(future)
        assertEquals("Opus 5", claudeDefaultModel)
    }
}
