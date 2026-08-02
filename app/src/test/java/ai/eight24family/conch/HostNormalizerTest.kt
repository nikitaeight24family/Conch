package ai.eight24family.conch

import ai.eight24family.conch.ssh.HostNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real users paste SSH URLs / user@host / host:port — not just bare
 * IPs. Without normalisation the connect layer turns those into
 * "DNS not found" errors. Lock down the parser here.
 */
class HostNormalizerTest {

    @Test fun `bare ipv4`() =
        check("1.2.3.4", host = "1.2.3.4", port = null)

    @Test fun `whitespace stripped`() =
        check("  1.2.3.4  ", host = "1.2.3.4", port = null)

    @Test fun `bare hostname`() =
        check("server.example.com", host = "server.example.com", port = null)

    @Test fun `ssh url with user and port`() =
        check("ssh://root@1.2.3.4:22", host = "1.2.3.4", port = 22)

    @Test fun `ssh url uppercase scheme`() =
        check("SSH://user@host.example.com:2222", host = "host.example.com", port = 2222)

    @Test fun `user prefix only`() =
        check("root@server.com", host = "server.com", port = null)

    @Test fun `host with port suffix`() =
        check("server.com:2222", host = "server.com", port = 2222)

    @Test fun `ipv4 with port suffix`() =
        check("10.0.0.1:2222", host = "10.0.0.1", port = 2222)

    @Test fun `ipv6 bracketed without port`() =
        check("[2001:db8::1]", host = "2001:db8::1", port = null)

    @Test fun `ipv6 bracketed with port`() =
        check("[2001:db8::1]:22", host = "2001:db8::1", port = 22)

    @Test fun `ipv6 unbracketed treated as host`() =
        check("2001:db8::1", host = "2001:db8::1", port = null)

    @Test fun `ipv6 link-local unbracketed`() =
        check("fe80::1", host = "fe80::1", port = null)

    @Test fun `user prefix plus ssh url`() =
        check("ssh://user@host.example.com", host = "host.example.com", port = null)

    @Test fun `multiple at-signs — last wins`() =
        // Quirky but legal: pick the last @ so e.g. "u@s@h.com" → "h.com".
        check("foo@bar@host.com:22", host = "host.com", port = 22)

    @Test fun `garbage port falls back to no port`() =
        check("host.com:notanumber", host = "host.com:notanumber", port = null)

    @Test fun `port out of range falls back to no port`() =
        check("host.com:99999", host = "host.com:99999", port = null)

    @Test fun `empty input`() =
        check("", host = "", port = null)

    @Test fun `bracketed ipv6 with garbage port suffix`() =
        check("[2001:db8::1]:notnum", host = "2001:db8::1", port = null)

    private fun check(input: String, host: String, port: Int?) {
        val out = HostNormalizer.normalize(input)
        assertEquals("host mismatch for input '$input'", host, out.host)
        if (port == null) assertNull("expected no port for '$input'", out.port)
        else assertEquals("port mismatch for input '$input'", port, out.port)
    }
}
