package ai.eight24family.conch

import ai.eight24family.conch.agent.LoopWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a `/loop` off the live stream.
 *
 * The inputs below are verbatim from a real loop run against the CLI on the
 * user's own box (2026-08-03): the model armed a wakeup, the CLI woke the same
 * process 120s later and ran a whole turn nobody asked for, and the second turn
 * ended the loop with `{"stop": true}`. That turn is free to arrive while the
 * phone is in someone's pocket — which is why the state has to be right.
 */
class LoopWatchTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `an armed wakeup carries its delay and the model's reason`() {
        val input = """{"delaySeconds": 120, "reason": "Polling for a flag file's appearance.", """ +
            """"prompt": "/loop check the deploy"}"""
        val armed = LoopWatch.read(input, now)
        assertNotNull(armed)
        assertEquals(120, armed!!.delaySeconds)
        assertEquals("Polling for a flag file's appearance.", armed.reason)
        assertEquals(now + 120_000L, armed.dueAtMs)
    }

    @Test
    fun `stop ends the loop`() {
        assertNull(LoopWatch.read("""{"stop": true}""", now))
    }

    @Test
    fun `stop wins even if a delay rides along`() {
        // Reading the delay out of a stopping call would leave a countdown
        // ticking over a loop that is already over.
        assertNull(LoopWatch.read("""{"stop": true, "delaySeconds": 600}""", now))
    }

    @Test
    fun `the delay we show is the delay the CLI will honour`() {
        // The CLI clamps to 60…3600 before scheduling, so anything else on
        // screen would be a countdown to the wrong moment.
        assertEquals(60, LoopWatch.read("""{"delaySeconds": 5}""", now)!!.delaySeconds)
        assertEquals(3600, LoopWatch.read("""{"delaySeconds": 99999}""", now)!!.delaySeconds)
        assertEquals(60, LoopWatch.read("""{"delaySeconds": -1}""", now)!!.delaySeconds)
    }

    @Test
    fun `a call with neither field arms nothing`() {
        assertNull(LoopWatch.read("""{"prompt": "/loop x"}""", now))
        assertNull(LoopWatch.read("", now))
    }

    @Test
    fun `a quoted reason with escapes survives`() {
        val armed = LoopWatch.read(
            """{"delaySeconds": 1200, "reason": "waiting on \"main\" to go green"}""", now,
        )
        assertEquals("waiting on \"main\" to go green", armed!!.reason)
    }
}
