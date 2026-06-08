package ai.eight24family.conch

import ai.eight24family.conch.ssh.ServerDiagnostics
import ai.eight24family.conch.ssh.ServerDiagnostics.Diagnosis
import ai.eight24family.conch.ssh.TcpProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Lock down the connectivity-diagnosis decision tree. Pure JVM — uses
 * the dependency-injected `classifyPure` so we never need a Context.
 */
class ServerDiagnosticsTest {

    @Test
    fun `SSH banner returns Ok`() {
        val r = classify(
            outcome = TcpProbe.Outcome.Ok("SSH-2.0-OpenSSH_8.4p1\r\n".toByteArray()),
        )
        assertEquals(Diagnosis.Ok, r)
    }

    @Test
    fun `HTTP banner returns WrongPort with HTTP description`() {
        val r = classify(
            outcome = TcpProbe.Outcome.Ok("HTTP/1.1 200 OK\r\n".toByteArray()),
        )
        assertTrue(r is Diagnosis.WrongPort)
        assertTrue("'HTTP' should be detected", (r as Diagnosis.WrongPort).detected.contains("HTTP"))
    }

    @Test
    fun `TLS handshake bytes return WrongPort with TLS description`() {
        val r = classify(
            outcome = TcpProbe.Outcome.Ok(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x42, 0x10, 0x00, 0x00)),
        )
        assertTrue(r is Diagnosis.WrongPort)
        assertTrue((r as Diagnosis.WrongPort).detected.contains("TLS"))
    }

    @Test
    fun `null banner returns SilentSsh`() {
        val r = classify(outcome = TcpProbe.Outcome.Ok(bannerBytes = null))
        assertTrue(r is Diagnosis.SilentSsh)
    }

    @Test
    fun `DNS fail returns HostNotFound`() {
        val r = classify(
            host = "totally-made-up-name.example.invalid",
            outcome = TcpProbe.Outcome.Failed(
                TcpProbe.Outcome.Failed.Kind.DnsFailed,
                java.net.UnknownHostException("nope"),
            ),
        )
        assertTrue(r is Diagnosis.HostNotFound)
        assertTrue(r.title.contains("totally-made-up-name.example.invalid"))
    }

    @Test
    fun `TCP refused returns ServerUpSshDown (public host)`() {
        val r = classify(
            host = "1.2.3.4",
            outcome = TcpProbe.Outcome.Failed(
                TcpProbe.Outcome.Failed.Kind.Refused,
                java.net.ConnectException("refused"),
            ),
        )
        assertTrue(r is Diagnosis.ServerUpSshDown)
        assertFalse((r as Diagnosis.ServerUpSshDown).isPrivate)
    }

    @Test
    fun `TCP refused on private host flags isPrivate true and offers LAN-style copy`() {
        val r = classify(
            host = "192.168.1.42",
            outcome = TcpProbe.Outcome.Failed(
                TcpProbe.Outcome.Failed.Kind.Refused,
                java.net.ConnectException("refused"),
            ),
        )
        assertTrue(r is Diagnosis.ServerUpSshDown)
        assertTrue((r as Diagnosis.ServerUpSshDown).isPrivate)
        assertTrue("private hosts should mention LAN router",
            r.reasons.any { it.contains("Local router") || it.contains("LAN") })
    }

    @Test
    fun `TCP timeout returns ServerNotResponding without network hint when phone online`() {
        val r = classify(
            outcome = TcpProbe.Outcome.Failed(
                TcpProbe.Outcome.Failed.Kind.Timeout,
                java.net.SocketTimeoutException("connect timed out"),
            ),
            hasNetwork = true,
        )
        assertTrue(r is Diagnosis.ServerNotResponding)
        assertFalse((r as Diagnosis.ServerNotResponding).networkHint)
    }

    @Test
    fun `TCP timeout returns PhoneOffline when phone has no network`() {
        val r = classify(
            outcome = TcpProbe.Outcome.Failed(
                TcpProbe.Outcome.Failed.Kind.Timeout,
                java.net.SocketTimeoutException("connect timed out"),
            ),
            hasNetwork = false,
        )
        assertEquals(Diagnosis.PhoneOffline, r)
    }

    @Test
    fun `NoRoute returns ServerNotResponding`() {
        val r = classify(
            outcome = TcpProbe.Outcome.Failed(
                TcpProbe.Outcome.Failed.Kind.NoRoute,
                java.net.NoRouteToHostException(),
            ),
        )
        assertTrue(r is Diagnosis.ServerNotResponding)
    }

    @Test
    fun `Other error returns ServerNotResponding (catchall)`() {
        val r = classify(
            outcome = TcpProbe.Outcome.Failed(
                TcpProbe.Outcome.Failed.Kind.Other,
                IOException("weird"),
            ),
        )
        assertTrue(r is Diagnosis.ServerNotResponding)
    }

    @Test
    fun `private IP detection — 10dot8`() {
        assertTrue(ServerDiagnostics.isPrivateOrLocal("10.0.0.1"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("10.255.255.255"))
    }

    @Test
    fun `private IP detection — 192dot168`() {
        assertTrue(ServerDiagnostics.isPrivateOrLocal("192.168.1.1"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("192.168.255.254"))
    }

    @Test
    fun `private IP detection — 172dot16-31`() {
        assertTrue(ServerDiagnostics.isPrivateOrLocal("172.16.0.1"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("172.31.255.255"))
        assertFalse(ServerDiagnostics.isPrivateOrLocal("172.15.0.1"))
        assertFalse(ServerDiagnostics.isPrivateOrLocal("172.32.0.1"))
    }

    @Test
    fun `private IP detection — loopback and link-local`() {
        assertTrue(ServerDiagnostics.isPrivateOrLocal("127.0.0.1"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("169.254.1.1"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("localhost"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("LOCALHOST"))
    }

    @Test
    fun `private IP detection — ipv6 ULA and link-local`() {
        assertTrue(ServerDiagnostics.isPrivateOrLocal("fd00::1"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("fc00::1"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("fe80::1"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("::1"))
    }

    @Test
    fun `private IP detection — public addresses`() {
        assertFalse(ServerDiagnostics.isPrivateOrLocal("8.8.8.8"))
        assertFalse(ServerDiagnostics.isPrivateOrLocal("1.1.1.1"))
        assertFalse(ServerDiagnostics.isPrivateOrLocal("203.0.113.10"))
        assertFalse(ServerDiagnostics.isPrivateOrLocal("2001:db8::1"))
        assertFalse(ServerDiagnostics.isPrivateOrLocal("server.example.com"))
    }

    @Test
    fun `private IP detection — local TLDs`() {
        assertTrue(ServerDiagnostics.isPrivateOrLocal("myserver.local"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("box.lan"))
        assertTrue(ServerDiagnostics.isPrivateOrLocal("nas.home"))
    }

    @Test
    fun `protocol detection — HTTP request methods`() {
        assertEquals("an HTTP web server",
            ServerDiagnostics.identifyProtocol("GET / HTTP/1.1\r\n".toByteArray()))
        assertEquals("an HTTP web server",
            ServerDiagnostics.identifyProtocol("POST /api HTTP/1.1".toByteArray()))
    }

    @Test
    fun `protocol detection — mail servers`() {
        assertEquals("an SMTP or FTP server",
            ServerDiagnostics.identifyProtocol("220 smtp.example.com".toByteArray()))
        assertEquals("a POP3 mail server",
            ServerDiagnostics.identifyProtocol("+OK ready\r\n".toByteArray()))
        assertEquals("an IMAP mail server",
            ServerDiagnostics.identifyProtocol("* OK IMAP4 ready".toByteArray()))
    }

    @Test
    fun `protocol detection — binary fallback shows hex`() {
        val out = ServerDiagnostics.identifyProtocol(byteArrayOf(0x00, 0x00, 0x00, 0x42))
        assertTrue("should mention binary or hex bytes", out.contains("binary"))
    }

    // ─────── helpers ───────

    private fun classify(
        host: String = "1.2.3.4",
        port: Int = 22,
        outcome: TcpProbe.Outcome,
        hasNetwork: Boolean = true,
    ): Diagnosis = ServerDiagnostics.classifyPure(host, port, outcome, hasNetwork)
}
