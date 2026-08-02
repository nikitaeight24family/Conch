package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentSessionPersistentStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stop's 4-second escalation: may it kill the CLI process?
 *
 * This gate has cost the user twice in two days, once in each direction, which is
 * why it is pinned by a test rather than by care:
 *
 * - 2026-07-30: the escalation was armed against the SESSION ("is SOME turn
 * running in 4s?"). - 2026-07-31: fencing it on the stopped turn fixed that but
 * introduced the opposite failure — Stop pressed before the turn had a token to
 * fence on did NOTHING AT ALL, no interrupt, no escalation, spinner stuck forever
 *
 * Both directions are asserted below. The rule: kill only the turn Stop was aimed
 * at, but ALWAYS retain a way to un-stick the UI.
 */
class StopEscalationGateTest {

    private fun gate(
        sameTurn: Boolean = true,
        victimDone: Boolean = false,
        working: Boolean = true,
        alive: Boolean = true,
    ) = AgentSessionPersistentStream.shouldEscalateKill(
        sameTurn = sameTurn,
        victimDone = victimDone,
        working = working,
        alive = alive,
    )

    @Test
    fun `kills when the stopped turn is still running`() {
        assertTrue(gate())
    }

    /**
     * THE 2026-07-30 BUG. The stopped turn ended (interrupt honoured) and the user
     * started a new one inside the 4s window: `turnDone` no longer points at the
     * victim. Killing here takes the new turn's prompt down with the process, and
     * nothing respawns it.
     */
    @Test
    fun `never kills once a different turn owns the channel`() {
        assertFalse(gate(sameTurn = false))
    }

    /**
     * A Stop that WORKED must not escalate. The victim's deferred is completed by
     * the terminal `result` line before runTurn's `finally` has nulled it, so
     * `sameTurn` can still be true here — `victimDone` is the discriminator.
     */
    @Test
    fun `never kills a turn whose interrupt was already honoured`() {
        assertFalse(gate(victimDone = true))
    }

    @Test
    fun `never kills when the session is no longer working`() {
        assertFalse(gate(working = false))
    }

    @Test
    fun `never kills a process that is already gone`() {
        assertFalse(gate(alive = false))
    }

    /**
     * THE 2026-07-31 BUG, pinned at the level this predicate can express: every
     * false verdict must be caused by a NAMED condition, never by "there was no
     * turn to fence on". `cancelTurn` handles the null-victim case by falling back
     * to the session-level check (working && alive) instead of returning early —
     * that fallback maps onto this predicate's inputs as sameTurn=true,
     * victimDone=false, which must kill.
     */
    @Test
    fun `session-level fallback still kills when there is no victim to fence on`() {
        assertTrue(gate(sameTurn = true, victimDone = false, working = true, alive = true))
    }
}
