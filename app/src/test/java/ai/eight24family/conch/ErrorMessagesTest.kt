package ai.eight24family.conch

import ai.eight24family.conch.util.ErrorMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Locks in the user-facing wording behind issue #15 — no stack trace
 * fragments, no `EOFException` or `InvalidKeyException` leaking into the
 * chat or the session header.
 *
 * If a wording changes intentionally, update the assertion. The point is
 * to catch *unintentional* regressions where a refactor removes the
 * humanizer and the raw exception text is back on screen.
 */
class ErrorMessagesTest {

    @Test
    fun `UnknownHostException becomes a host-not-found hint`() {
        val out = ErrorMessages.humanize(UnknownHostException("foo.bar"))
        assertEquals("Host not found. Check the address.", out)
    }

    @Test
    fun `ConnectException becomes an SSH-not-running hint`() {
        val out = ErrorMessages.humanize(ConnectException("refused"))
        assertTrue(out.startsWith("Can't reach the server"))
    }

    @Test
    fun `SocketTimeoutException becomes a timeout sentence`() {
        val out = ErrorMessages.humanize(SocketTimeoutException("connect timed out"))
        assertTrue(out.startsWith("Connection timed out"))
    }

    @Test
    fun `timeout in any message gets the same sentence even without the typed exception`() {
        val out = ErrorMessages.humanize(IOException("read operation timed out"))
        assertTrue(out.startsWith("Connection timed out"))
    }

    @Test
    fun `EOFException is reported as remote-closed`() {
        val out = ErrorMessages.humanize(EOFException("end"))
        assertEquals("Server closed the connection unexpectedly.", out)
    }

    @Test
    fun `premature EOF text matches the EOF branch even on a plain IOException`() {
        val out = ErrorMessages.humanize(IOException("Premature EOF"))
        assertEquals("Server closed the connection unexpectedly.", out)
    }

    @Test
    fun `exhausted auth methods gets the actionable hint`() {
        val out = ErrorMessages.humanize(
            RuntimeException("Exhausted available authentication methods")
        )
        assertTrue(out.contains("authorized_keys"))
    }

    @Test
    fun `permission denied is recognised even as a wrapped IOException`() {
        val out = ErrorMessages.humanize(IOException("scp: Permission denied"))
        assertEquals("Permission denied on the server.", out)
    }

    @Test
    fun `no space left on device is recognised`() {
        val out = ErrorMessages.humanize(IOException("write: No space left on device"))
        assertEquals("Disk is full on the server.", out)
    }

    @Test
    fun `SocketException is reported as a network drop with retry hint`() {
        val out = ErrorMessages.humanize(SocketException("Connection reset by peer"))
        assertTrue(out.contains("Retry"))
    }

    @Test
    fun `unknown exception with empty message falls back to a class name (NOT a stack trace)`() {
        class MysteryError : Throwable()
        val out = ErrorMessages.humanize(MysteryError())
        assertEquals("MysteryError", out)
        assertFalse("must not leak stack frames", out.contains("\n"))
    }

    @Test
    fun `unknown exception with a short readable message returns the message verbatim`() {
        val out = ErrorMessages.humanize(IllegalStateException("custom error"))
        assertEquals("custom error", out)
    }

    @Test
    fun `unreasonably long messages fall back to class name to avoid spam`() {
        val long = "x".repeat(500)
        val out = ErrorMessages.humanize(IllegalStateException(long))
        assertEquals("IllegalStateException", out)
    }
}
