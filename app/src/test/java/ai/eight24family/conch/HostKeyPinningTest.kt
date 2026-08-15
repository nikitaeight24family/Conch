package ai.eight24family.conch

import ai.eight24family.conch.ssh.SshConnectionPool
import ai.eight24family.conch.ui.viewmodel.ChatViewModelReconnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-key pinning on the POOLED connect paths.
 *
 * The pool accepted a "new" host key on every single connect and never wrote
 * the fingerprint down — logcat showed
 * "TOFU: accepting new host key for 203.0.113.10:22" ~15×/min during the
 * 2026-08-16 reconnect storm. Trust-on-first-use that never records the first
 * use is trust-on-every-use: the app rendered a `fingerprint` field it had no
 * intention of ever checking. The fix pins after auth succeeds, refuses a
 * changed key with an actionable message, and gives the user a way to drop the
 * pin when the change was legitimate.
 */
class HostKeyPinningTest {

    @Test
    fun `unpinned server trusts on first use`() {
        assertEquals(
            SshConnectionPool.HostKeyVerdict.FIRST_USE,
            SshConnectionPool.hostKeyVerdict(expected = null, actual = "aa:bb:cc"),
        )
    }

    @Test
    fun `pinned server accepts the same key`() {
        assertEquals(
            SshConnectionPool.HostKeyVerdict.MATCH,
            SshConnectionPool.hostKeyVerdict(expected = "aa:bb:cc", actual = "aa:bb:cc"),
        )
    }

    @Test
    fun `pinned server refuses a changed key`() {
        // The whole point of the pin. Before the fix this branch was
        // unreachable in practice: nothing ever wrote `expected`.
        assertEquals(
            SshConnectionPool.HostKeyVerdict.MISMATCH,
            SshConnectionPool.hostKeyVerdict(expected = "aa:bb:cc", actual = "de:ad:be"),
        )
    }

    @Test
    fun `a changed host key never auto-retries`() {
        // A rotated key is permanent until a human decides what it means.
        // Retrying hammers the host with a question it cannot answer — and
        // fail2ban counts every attempt.
        val coord = ChatViewModelReconnect(CoroutineScope(Dispatchers.Unconfined)) {}
        assertFalse(
            coord.shouldAutoRetry(
                "Host key changed for example.com — this is either the server being rebuilt/moved, " +
                    "or someone sitting in the middle."
            )
        )
        // …while an ordinary drop still reconnects silently, as it must.
        assertTrue(coord.shouldAutoRetry("disconnected"))
    }
}
