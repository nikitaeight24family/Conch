package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeSpec
import ai.eight24family.conch.agent.spec.TopbarModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a session must never flash an effort we made up.
 *
 * The label chain used to end in the catalog default, so a chat running at
 * xhigh printed **medium** for the first frames and snapped to the truth once
 * the transcript parsed. Two halves to the fix, one per test below: print
 * nothing when nothing is known, and know it sooner — the sessions listing now
 * carries the session's own effort, exactly like the model.
 */
class ClaudeEffortNoInventionTest {

    private fun state(
        pick: String? = null,
        seen: String? = null,
        initial: String? = null,
        default: String? = null,
    ) = TopbarModelState(
        agentDisplayName = "Claude Code",
        selectedModel = null,
        sessionInitialModel = null,
        observedModel = null,
        defaultModel = null,
        availableModels = emptyMap(),
        modelsProbing = false,
        selectedReasoning = pick,
        observedReasoning = seen,
        sessionInitialReasoning = initial,
        defaultReasoning = default,
    )

    @Test
    fun `nothing known prints nothing, not a catalog default`() {
        assertNull(ClaudeSpec.topbarUi.reasoningLabel(state()))
    }

    @Test
    fun `the session's own effort still wins the moment it is known`() {
        assertEquals("xhigh", ClaudeSpec.topbarUi.reasoningLabel(state(initial = "xhigh")))
        assertEquals("max", ClaudeSpec.topbarUi.reasoningLabel(state(seen = "max", initial = "xhigh")))
    }

    @Test
    fun `the listing reads the session's effort so the topbar opens correct`() {
        // Same shape as the model column: last match, bounded tail window.
        val script = ClaudeSpec.listSessionsScript.orEmpty()
        assertTrue("listing must extract the effort", script.contains("reasoning=\$("))
        assertTrue(script.contains("\"effort\""))
        assertTrue("must be the LAST record, i.e. the current effort", script.contains("| tail -1"))
        // Closed set — a stray key named "effort" in someone's chat text can't
        // become the session's setting.
        assertTrue(script.contains("xhigh|max|ultracode"))
    }

    @Test
    fun `the listing still emits every column in order`() {
        val script = ClaudeSpec.listSessionsScript.orEmpty()
        assertTrue(
            "7 columns: id, mtime, path, model, reasoning, size, preview",
            script.contains("""printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n'"""),
        )
    }
}
