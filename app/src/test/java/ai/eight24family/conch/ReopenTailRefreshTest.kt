package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staleness decision is the gate — and it MUST stay base-aware so a
 * tail-first cache (Workstream C) is not refreshed on every single open —
 * and, since 2026-09-12, so a PROJECTED cache (whose local length has no
 * remote meaning at all) is not either. The caller now passes
 * HistoryCache.remoteEnd, which is the one place that knows how to answer
 * "where are we in the remote file" for both kinds of body.
 */
class ReopenTailRefreshTest {

    @Test
    fun `a complete cache that matches the server is not stale`() {
        assertFalse(ChatViewModel.serverAheadOfCache(serverSize = 10_000L, cachedRemoteEnd = 10000L))
    }

    @Test
    fun `a live turn that grew the server file since caching is stale`() {
        // Cache saved at 10k; the reopened turn pushed the server to 12k.
        assertTrue(ChatViewModel.serverAheadOfCache(serverSize = 12_000L, cachedRemoteEnd = 10000L))
    }

    /** THE REGRESSION GUARD. A tail-first cache holds only the last ~2 MB of a
     *  much larger server file: base = 8 MB, local len = 2 MB, server = 10 MB.
     *  Comparing the 2 MB local length to the 10 MB server would refresh on
     *  EVERY open; the base-aware compare (8 MB + 2 MB == 10 MB) says "current". */
    @Test
    fun `a tail-first cache at its server frontier is NOT stale`() {
        assertFalse(
            ChatViewModel.serverAheadOfCache(
                serverSize = 10_000_000L,
                cachedRemoteEnd = 10000000L,
            ),
        )
    }

    @Test
    fun `a tail-first cache behind a grown server IS stale`() {
        assertTrue(
            ChatViewModel.serverAheadOfCache(
                serverSize = 10_500_000L,
                cachedRemoteEnd = 10000000L,
            ),
        )
    }

    /** A benign entrypoint rewrite makes the server SMALLER (−4 B/tag), never
     *  larger — so it never trips the refresh. */
    @Test
    fun `a server smaller than the cache is not stale`() {
        assertFalse(ChatViewModel.serverAheadOfCache(serverSize = 9_996L, cachedRemoteEnd = 10000L))
    }

    @Test
    fun `an unreadable server size never refreshes`() {
        assertFalse(ChatViewModel.serverAheadOfCache(serverSize = null, cachedRemoteEnd = 10000L))
    }
}
