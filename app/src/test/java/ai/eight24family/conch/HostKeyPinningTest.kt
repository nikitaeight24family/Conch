package ai.eight24family.conch

import ai.eight24family.conch.linux.LinuxSsh
import ai.eight24family.conch.ssh.SshConnectionPool
import ai.eight24family.conch.ui.viewmodel.ChatViewModelReconnect
import ai.eight24family.conch.util.ErrorMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `the host-key sentence arrives whole`() {
        // Built the way hostKeyMismatchError builds it. It is longer than
        // humanize's generic 240-char gate ON PURPOSE — that gate used to
        // degrade exactly this message to "IllegalStateException", and the
        // ServerDetail dialog's take(120) used to cut it at "Expect", right
        // before the fingerprints and the way out (owner's phone, 2026-08-31).
        val msg = "Host key changed for 127.0.0.1 — this is either the server being rebuilt/moved, " +
            "or someone sitting in the middle. Expected 3b:1f:b5:2a:8a:34:2c:43, " +
            "got e0:dc:dd:f7:42:a3:1a:bc. Nothing will connect until you decide: if you rebuilt it, " +
            "open the server → // system → fingerprint → forget, then reconnect."
        assertTrue(msg.length > 240)
        assertEquals(msg, ErrorMessages.humanize(IllegalStateException(msg)))
    }

    @Test
    fun `env host key pub maps to the pool's fingerprint format`() {
        // Vector from `ssh-keygen -t ed25519`; expected value is MD5 over the
        // decoded blob — the same bytes sshj hashes at the handshake, because
        // the wire host key IS this blob. This is what lets "this phone"'s pin
        // follow the environment's own key file instead of demanding the
        // forget ritual after every daemon swap or rootfs rebuild.
        assertEquals(
            "e0:dc:dd:f7:42:a3:1a:bc:29:5d:a4:13:13:3e:2f:d9",
            LinuxSsh.opensshPubMd5Fingerprint(
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIu+bGAQoW/50rbnFrrE8ZEn5XqWY9OuaelZk8cnmiRp vector",
            ),
        )
    }

    @Test
    fun `garbage never yields a fingerprint`() {
        // A null here means "leave TOFU / mismatch in charge" — an unreadable
        // key file must never quietly re-pin the row.
        assertNull(LinuxSsh.opensshPubMd5Fingerprint(""))
        assertNull(LinuxSsh.opensshPubMd5Fingerprint("just-one-field"))
        assertNull(LinuxSsh.opensshPubMd5Fingerprint("ssh-ed25519 %%%not-base64%%% x"))
    }
}
