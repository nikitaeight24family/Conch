package ai.eight24family.conch

import ai.eight24family.conch.ssh.SshTunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/**
 * The set a tunnel closes when it is switched off.
 *
 * ⚠ THIS EXISTS BECAUSE THE FIX FOR "a switched-off tunnel keeps pumping"
 * REINTRODUCED THAT EXACT BUG. The set only ever added, so it counted every
 * connection the tunnel had ever carried, and a size cap then evicted the
 * OLDEST entry WITHOUT closing it — the oldest being precisely the long-lived
 * one (a websocket, a database session). Untracked, it survived stop().
 *
 * So the property under test is not "closeAll works". It is: an entry that
 * closes must LEAVE, so the set only ever holds what is genuinely open and
 * nothing is ever dropped from tracking while still alive.
 */
class TunnelLiveSetTest {

    private class Spy : Closeable {
        var closed = 0
        override fun close() { closed++ }
    }

    @Test
    fun `an entry that closes leaves the set`() {
        val set = SshTunnel.LiveSet()
        val a = Spy()
        val b = Spy()
        set.add(a)
        set.add(b)
        assertEquals(2, set.size())

        set.remove(a) // what a connection's own teardown does
        assertEquals(1, set.size())

        set.closeAll()
        assertEquals("the one that left must not be closed again", 0, a.closed)
        assertEquals("the one still open must be closed", 1, b.closed)
        assertEquals(0, set.size())
    }

    @Test
    fun `ordinary traffic does not make the set grow`() {
        // A browsing session opens and closes hundreds of connections. Under the
        // old code the set reached its cap and started silently dropping live
        // entries; here it must simply stay small.
        val set = SshTunnel.LiveSet()
        repeat(2_000) {
            val c = Spy()
            set.add(c)
            set.remove(c)
        }
        assertEquals("nothing is open, so nothing is tracked", 0, set.size())
    }

    @Test
    fun `long-lived connections are still tracked after a thousand short ones`() {
        // The exact shape of the regression: the oldest entry is the one that
        // matters most, and it must survive any amount of churn around it.
        val set = SshTunnel.LiveSet()
        val websocket = Spy()
        set.add(websocket)
        repeat(1_000) {
            val c = Spy()
            set.add(c)
            set.remove(c)
        }
        assertEquals(1, set.size())

        set.closeAll()
        assertEquals("the long-lived connection must be closed by stop()", 1, websocket.closed)
    }

    @Test
    fun `closeAll is safe to call twice`() {
        val set = SshTunnel.LiveSet()
        val a = Spy()
        set.add(a)
        set.closeAll()
        set.closeAll()
        assertEquals(1, a.closed)
        assertTrue(set.size() == 0)
    }
}
