package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ONLY words the chat says about the connection (Workstream E sign-off):
 * "server unreachable — retrying quietly", and only after a CONTINUOUS outage.
 * A banner that fires on a blip is exactly the noise the 2026-08-17 doctrine
 * removed, so the gate is pinned.
 */
class UnreachableBannerTest {

    private val quiet = ChatViewModel.UNREACHABLE_QUIET_MS

    @Test
    fun `never shows while the link is up`() {
        assertFalse(ChatViewModel.unreachableBannerShown(lost = false, downSince = 0L, now = quiet * 10))
    }

    @Test
    fun `silent through the first five minutes of a drop`() {
        val downSince = 1_000_000L
        assertFalse(ChatViewModel.unreachableBannerShown(true, downSince, downSince))                 // just dropped
        assertFalse(ChatViewModel.unreachableBannerShown(true, downSince, downSince + quiet - 1))     // 4:59
    }

    @Test
    fun `shows once the outage passes the quiet window`() {
        val downSince = 1_000_000L
        assertTrue(ChatViewModel.unreachableBannerShown(true, downSince, downSince + quiet))          // exactly 5:00
        assertTrue(ChatViewModel.unreachableBannerShown(true, downSince, downSince + quiet + 999_999))
    }

    /** downSince == 0 means the caller has seen the link up since — a blip
     *  reset the clock, so a long-lost `now` must NOT retro-fire the banner. */
    @Test
    fun `a reset clock (blip) never fires the banner`() {
        assertFalse(ChatViewModel.unreachableBannerShown(lost = true, downSince = 0L, now = quiet * 100))
    }
}
