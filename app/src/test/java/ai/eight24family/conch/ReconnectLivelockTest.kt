package ai.eight24family.conch

import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.agent.SessionStateMachine
import ai.eight24family.conch.ssh.SshConnectionPool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression net for the 2026-08-16 reconnect LIVELOCK.
 *
 * Observed on device: the chat cycled «⚡ connection lost — reconnecting · 1
 * message will send when it's back» ⇄ «↻ 1 message waiting to send» every few
 * seconds for as long as the user watched it, the queued prompt was never
 * delivered, and logcat showed one full teardown+reconnect+574 KB re-parse per
 * cycle (15 evictions/min, CPU 55→72 °C).
 *
 * Two independent defects fed each other:
 *
 *  1. `retry()` evicted the pooled transport by serverId, so it destroyed the
 *     healthy connection the ephemeral reconnect had just built — including on
 *     the path whose entry condition is literally "the pool is live".
 *  2. `findByResume` adopted a session parked in `Failed("disconnected")`,
 *     because `isAlive()` re-binds a dead session onto the pool's newest
 *     transport and then answers "alive". The chat inherited a terminal state
 *     on every rebuild, so it never reached Running — and the parked message
 *     only leaves on a Running edge.
 *
 * Both rules live in pure functions precisely so they can be pinned here.
 */
class ReconnectLivelockTest {

    // ── 1. A terminal session may never be adopted as a chat's live session ──

    @Test
    fun `failed session is not adoptable`() {
        assertFalse(SessionStateMachine.isAdoptable(SessionState.Failed("disconnected")))
    }

    @Test
    fun `closed session is not adoptable`() {
        assertFalse(SessionStateMachine.isAdoptable(SessionState.Closed))
    }

    @Test
    fun `live and bootstrapping sessions stay adoptable`() {
        // Reuse must keep working for the case it exists for — popping the chat
        // off the back stack and re-entering must not pay a second handshake
        // (or lose the in-memory history) just because we tightened the filter.
        assertTrue(SessionStateMachine.isAdoptable(SessionState.Idle))
        assertTrue(SessionStateMachine.isAdoptable(SessionState.Bootstrapping("connecting")))
        assertTrue(SessionStateMachine.isAdoptable(SessionState.Running))
        assertTrue(SessionStateMachine.isAdoptable(SessionState.Working))
    }

    // ── 2. Never evict a transport that postdates the failure ──

    @Test
    fun `transport built after the failure survives eviction`() {
        // The exact shape of the livelock: the ladder fires ~1 s after the
        // failure, by which time the ephemeral reconnect has a 1.2 s-old
        // healthy transport in the pool. Killing it is what restarts the loop.
        assertFalse(
            SshConnectionPool.shouldEvictPoisoned(
                entryAgeMs = 1_200L,
                minAgeMs = SshConnectionPool.EVICT_MIN_AGE_MS,
            )
        )
    }

    @Test
    fun `genuinely poisoned old transport is still evicted`() {
        // The 2026-08-12 half-open-socket bug must stay fixed: that transport
        // has been up for minutes, so the age guard never protects it.
        assertTrue(
            SshConnectionPool.shouldEvictPoisoned(
                entryAgeMs = 120_000L,
                minAgeMs = SshConnectionPool.EVICT_MIN_AGE_MS,
            )
        )
    }

    @Test
    fun `caller that failed on the pooled client itself evicts unconditionally`() {
        // The MaxSessions path proved THAT transport bad by failing on it right
        // now, so it passes minAge=0 and no age can save it.
        assertTrue(SshConnectionPool.shouldEvictPoisoned(entryAgeMs = 0L, minAgeMs = 0L))
    }

    @Test
    fun `age guard is inclusive at the boundary`() {
        assertTrue(
            SshConnectionPool.shouldEvictPoisoned(
                entryAgeMs = SshConnectionPool.EVICT_MIN_AGE_MS,
                minAgeMs = SshConnectionPool.EVICT_MIN_AGE_MS,
            )
        )
    }
}
