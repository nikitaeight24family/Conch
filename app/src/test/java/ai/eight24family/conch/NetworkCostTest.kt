package ai.eight24family.conch

import ai.eight24family.conch.util.NetworkCost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metered gate decides whether the background prefetcher may pull MB-sized
 * session bodies. Getting it wrong on the "free" side spends the user's money —
 * he lost a whole monthly plan to background traffic (2026-07-23).
 *
 * Tested here on the JVM precisely so nobody ever "checks it" by putting his
 * phone on cellular; he has no data package.
 */
class NetworkCostTest {

    @Test
    fun `platform says NOT_METERED - link is free`() {
        assertFalse(NetworkCost.decideMetered(true))
    }

    @Test
    fun `platform withheld NOT_METERED - link is billed`() {
        assertTrue(NetworkCost.decideMetered(false))
    }

    @Test
    fun `unknown state counts as billed, never as free`() {
        // No active network / no capabilities / no ConnectivityManager. Being
        // wrong here must cost freshness, not money.
        assertTrue(NetworkCost.decideMetered(null))
    }
}
