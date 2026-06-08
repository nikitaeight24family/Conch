package ai.eight24family.conch

import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.agent.SessionStateMachine
import ai.eight24family.conch.agent.SessionStateMachine.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the [SessionStateMachine] transition table behind
 * issue #11. One assertion per legal-or-illegal-or-idempotent path,
 * keyed by the comment block at the top of the SUT.
 *
 * Pure JUnit — no Robolectric, no Android, no coroutines. Lives
 * under `app/src/test/` and runs in the standard
 * `testDebugUnitTest` suite.
 */
class SessionStateMachineTest {

    private fun trans(c: SessionState, e: Event): SessionState =
        SessionStateMachine.transition(c, e)

    // ───────────────── Bootstrap ─────────────────

    @Test
    fun `Bootstrap from Idle enters Bootstrapping with the step label`() {
        val out = trans(SessionState.Idle, Event.Bootstrap("connecting"))
        assertEquals(SessionState.Bootstrapping("connecting"), out)
    }

    @Test
    fun `Bootstrap from Bootstrapping advances the step label`() {
        val out = trans(SessionState.Bootstrapping("connecting"), Event.Bootstrap("checking codex"))
        assertEquals(SessionState.Bootstrapping("checking codex"), out)
    }

    @Test
    fun `Bootstrap from Running is ignored (stale event)`() {
        assertSame(SessionState.Running, trans(SessionState.Running, Event.Bootstrap("any")))
    }

    @Test
    fun `Bootstrap from Working is ignored (stale event)`() {
        assertSame(SessionState.Working, trans(SessionState.Working, Event.Bootstrap("any")))
    }

    @Test
    fun `Bootstrap from Failed is ignored (must Reset first)`() {
        val failed = SessionState.Failed("auth bad")
        assertEquals(failed, trans(failed, Event.Bootstrap("any")))
    }

    @Test
    fun `Bootstrap from Closed is ignored`() {
        assertSame(SessionState.Closed, trans(SessionState.Closed, Event.Bootstrap("any")))
    }

    // ───────────────── Connected ─────────────────

    @Test
    fun `Connected from Bootstrapping promotes to Running`() {
        val out = trans(SessionState.Bootstrapping("anything"), Event.Connected)
        assertSame(SessionState.Running, out)
    }

    @Test
    fun `Connected from Running is idempotent`() {
        assertSame(SessionState.Running, trans(SessionState.Running, Event.Connected))
    }

    @Test
    fun `Connected from Working keeps Working`() {
        assertSame(SessionState.Working, trans(SessionState.Working, Event.Connected))
    }

    @Test
    fun `Connected from Failed is ignored (no zombie resurrection)`() {
        val failed = SessionState.Failed("boom")
        assertEquals(failed, trans(failed, Event.Connected))
    }

    @Test
    fun `Connected from Closed is ignored`() {
        assertSame(SessionState.Closed, trans(SessionState.Closed, Event.Connected))
    }

    @Test
    fun `Connected from Idle is ignored (must Bootstrap first)`() {
        assertSame(SessionState.Idle, trans(SessionState.Idle, Event.Connected))
    }

    // ───────────────── TurnStart ─────────────────

    @Test
    fun `TurnStart from Running flips to Working`() {
        assertSame(SessionState.Working, trans(SessionState.Running, Event.TurnStart))
    }

    @Test
    fun `TurnStart from Bootstrapping is buffered (state unchanged)`() {
        val b = SessionState.Bootstrapping("connecting")
        assertEquals(b, trans(b, Event.TurnStart))
    }

    @Test
    fun `TurnStart from Working is idempotent (CLI queues additional prompts)`() {
        assertSame(SessionState.Working, trans(SessionState.Working, Event.TurnStart))
    }

    @Test
    fun `TurnStart from Failed is dropped`() {
        val failed = SessionState.Failed("network")
        assertEquals(failed, trans(failed, Event.TurnStart))
    }

    @Test
    fun `TurnStart from Closed is dropped`() {
        assertSame(SessionState.Closed, trans(SessionState.Closed, Event.TurnStart))
    }

    @Test
    fun `TurnStart from Idle is dropped`() {
        assertSame(SessionState.Idle, trans(SessionState.Idle, Event.TurnStart))
    }

    // ───────────────── TurnEnd ─────────────────

    @Test
    fun `TurnEnd from Working returns to Running`() {
        assertSame(SessionState.Running, trans(SessionState.Working, Event.TurnEnd))
    }

    @Test
    fun `TurnEnd from Running is a no-op`() {
        assertSame(SessionState.Running, trans(SessionState.Running, Event.TurnEnd))
    }

    @Test
    fun `TurnEnd from Failed leaves the Failed reason intact`() {
        val failed = SessionState.Failed("auth")
        assertEquals(failed, trans(failed, Event.TurnEnd))
    }

    // ───────────────── Fail ─────────────────

    @Test
    fun `Fail from any live state captures the reason`() {
        val live = listOf(
            SessionState.Idle,
            SessionState.Bootstrapping("x"),
            SessionState.Running,
            SessionState.Working,
        )
        for (s in live) {
            val out = trans(s, Event.Fail("boom"))
            assertEquals("Fail from $s", SessionState.Failed("boom"), out)
        }
    }

    @Test
    fun `Fail from Failed overwrites the previous reason (retry pattern)`() {
        val out = trans(SessionState.Failed("first"), Event.Fail("second"))
        assertEquals(SessionState.Failed("second"), out)
    }

    @Test
    fun `Fail from Closed is a no-op (dead session can't fail)`() {
        assertSame(SessionState.Closed, trans(SessionState.Closed, Event.Fail("ignored")))
    }

    // ───────────────── Disconnect ─────────────────

    @Test
    fun `Disconnect from a live state surfaces a 'disconnected' Failed`() {
        val live = listOf(
            SessionState.Bootstrapping("x"),
            SessionState.Running,
            SessionState.Working,
        )
        for (s in live) {
            val out = trans(s, Event.Disconnect)
            assertEquals("Disconnect from $s", SessionState.Failed("disconnected"), out)
        }
    }

    @Test
    fun `Disconnect from Failed preserves the original reason`() {
        // Specifically: don't overwrite "wrong password" with "disconnected".
        val failed = SessionState.Failed("Wrong password for this user.")
        assertEquals(failed, trans(failed, Event.Disconnect))
    }

    @Test
    fun `Disconnect from Closed is a no-op`() {
        assertSame(SessionState.Closed, trans(SessionState.Closed, Event.Disconnect))
    }

    @Test
    fun `Disconnect from Idle is a no-op (nothing to disconnect from)`() {
        assertSame(SessionState.Idle, trans(SessionState.Idle, Event.Disconnect))
    }

    // ───────────────── Close ─────────────────

    @Test
    fun `Close from any live state collapses to Closed`() {
        val live = listOf(
            SessionState.Idle,
            SessionState.Bootstrapping("x"),
            SessionState.Running,
            SessionState.Working,
        )
        for (s in live) {
            assertSame("Close from $s", SessionState.Closed, trans(s, Event.Close))
        }
    }

    @Test
    fun `Close from Failed keeps the Failed reason visible in the UI`() {
        val failed = SessionState.Failed("Authentication failed.")
        assertEquals(failed, trans(failed, Event.Close))
    }

    @Test
    fun `Close from Closed is idempotent`() {
        assertSame(SessionState.Closed, trans(SessionState.Closed, Event.Close))
    }

    // ───────────────── Reset ─────────────────

    @Test
    fun `Reset from Failed clears to Idle (user retry path)`() {
        assertSame(SessionState.Idle, trans(SessionState.Failed("x"), Event.Reset))
    }

    @Test
    fun `Reset from non-Failed states is a no-op`() {
        val others = listOf(
            SessionState.Idle,
            SessionState.Bootstrapping("x"),
            SessionState.Running,
            SessionState.Working,
            SessionState.Closed,
        )
        for (s in others) {
            assertEquals("Reset from $s", s, trans(s, Event.Reset))
        }
    }

    // ───────────────── Cross-state sanity ─────────────────

    @Test
    fun `full happy path Idle → Bootstrap → Connected → TurnStart → TurnEnd → Close`() {
        var s: SessionState = SessionState.Idle
        s = trans(s, Event.Bootstrap("connecting")); assertTrue(s is SessionState.Bootstrapping)
        s = trans(s, Event.Bootstrap("checking codex")); assertEquals("checking codex", (s as SessionState.Bootstrapping).step)
        s = trans(s, Event.Connected); assertSame(SessionState.Running, s)
        s = trans(s, Event.TurnStart); assertSame(SessionState.Working, s)
        s = trans(s, Event.TurnEnd); assertSame(SessionState.Running, s)
        s = trans(s, Event.Close); assertSame(SessionState.Closed, s)
    }

    @Test
    fun `failure-then-retry path Bootstrap → Fail → Reset → Bootstrap → Connected`() {
        var s: SessionState = SessionState.Idle
        s = trans(s, Event.Bootstrap("connecting")); assertTrue(s is SessionState.Bootstrapping)
        s = trans(s, Event.Fail("Host not found.")); assertEquals(SessionState.Failed("Host not found."), s)
        s = trans(s, Event.Reset); assertSame(SessionState.Idle, s)
        s = trans(s, Event.Bootstrap("connecting")); assertTrue(s is SessionState.Bootstrapping)
        s = trans(s, Event.Connected); assertSame(SessionState.Running, s)
    }
}
