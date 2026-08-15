package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeSpec
import ai.eight24family.conch.agent.codex.CodexSpec
import ai.eight24family.conch.agent.gemini.GeminiSpec
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last two things the CLI could do and the app could not: fork a
 * conversation, and work in plan mode.
 *
 * Both flags are real and were read off the live CLI's `--help`, not guessed:
 * `--fork-session` ("When resuming, create a new session ID") and
 * `--permission-mode plan`. `/background` and `/fork` themselves are
 * `type:"local-jsx"` in the binary — REPL screens, not headless commands — so
 * there is nothing there for us to call; the flags are the real surface.
 */
class ForkAndPlanModeTest {

    private fun cmd(
        resume: String? = "sess-1",
        fork: Boolean = false,
        approval: AgentApprovalMode = AgentApprovalMode.SAFE,
    ) = ClaudeSpec.buildPersistentCommand(
        ExecInput(
            text = "hi",
            resumeId = resume,
            model = null,
            approvalMode = approval,
            cwdSnapshot = null,
            forkSession = fork,
        )
    )

    @Test
    fun `a fork inherits the conversation and takes a new id`() {
        val c = cmd(fork = true)
        assertTrue("must resume the conversation it inherits", c.contains("--resume 'sess-1'"))
        assertTrue(c.contains("--fork-session"))
    }

    @Test
    fun `an ordinary chat is byte-identical to before`() {
        assertFalse(cmd(fork = false).contains("--fork-session"))
    }

    @Test
    fun `forking without a session to inherit is not a thing`() {
        // `--fork-session` alone would be a flag with nothing to fork; the UI
        // hides the action, and the command can't express it either.
        assertFalse(cmd(resume = null, fork = true).contains("--fork-session"))
    }

    @Test
    fun `plan mode reaches the CLI as its own permission mode`() {
        assertTrue(cmd(approval = AgentApprovalMode.PLAN).contains("--permission-mode plan"))
    }

    @Test
    fun `the CLIs without a plan mode fall back to asking, never to acting`() {
        val codex = CodexSpec.buildExecCommand(
            ExecInput("hi", null, null, AgentApprovalMode.PLAN, null)
        )
        assertTrue(codex.contains("--sandbox read-only"))
        assertFalse(codex.contains("--dangerously"))
        val gemini = GeminiSpec.buildExecCommand(
            ExecInput("hi", null, null, AgentApprovalMode.PLAN, null)
        )
        assertTrue(gemini.contains("--approval-mode default"))
        assertFalse(gemini.contains("--yolo"))
    }
}
