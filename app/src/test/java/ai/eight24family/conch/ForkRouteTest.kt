package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.ui.navigation.Routes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fork survives the trip through navigation.
 *
 * Forking is decided in the menu and acted on much later, at the first launch
 * of the new chat — with a nav argument as the only thing connecting them. If
 * the emitted query key and the route's placeholder ever drift apart, the flag
 * silently arrives null and the "fork" quietly continues the ORIGINAL session,
 * with two chats writing to one file and no error anywhere. Cheap to pin, ugly
 * to debug.
 */
class ForkRouteTest {

    @Test
    fun `a fork is asked for explicitly and carries the session it inherits`() {
        val r = Routes.chat("srv1", Agent.CLAUDE, resumeId = "sess-1", fork = true)
        assertTrue(r.contains("resume=sess-1"))
        assertTrue(r.contains("fork=1"))
    }

    @Test
    fun `an ordinary open says nothing about forking`() {
        val r = Routes.chat("srv1", Agent.CLAUDE, resumeId = "sess-1")
        assertFalse("absent, not fork=0 — the VM reads it as 'is it exactly 1'", r.contains("fork"))
    }

    @Test
    fun `the route template declares the argument the builder emits`() {
        // The failure this exists for: emitting `fork=1` into a template that
        // has no {fork} placeholder. The value is dropped and nothing says so.
        assertTrue(Routes.CHAT.contains("fork={fork}"))
    }
}
