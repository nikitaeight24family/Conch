package ai.eight24family.conch.ssh

/**
 * Strip the noise users paste into the "host" field so the
 * connect / diagnostic layer always sees a clean (host, port?).
 *
 * Real-world inputs we observed:
 *   - `ssh://root@1.2.3.4:22`
 *   - `root@server.com`
 *   - `server.com:2222`
 *   - `[2001:db8::1]:22`
 *   - `2001:db8::1`
 *   - `  1.2.3.4 ` (trailing whitespace)
 *
 * Without normalisation, every one of these except the bare IP would
 * have failed DNS resolution and surfaced as "Host not found" — when
 * the user actually pasted something perfectly valid in a different
 * format.
 */
object HostNormalizer {

    data class Normalized(val host: String, val port: Int?)

    fun normalize(input: String): Normalized {
        var s = input.trim()
        if (s.isEmpty()) return Normalized("", null)

        // 1. Strip "ssh://" scheme.
        if (s.startsWith("ssh://", ignoreCase = true)) s = s.removePrefix("ssh://").removePrefix("SSH://")

        // 2. Strip "user@" prefix. Last '@' wins in case '@' appears in
        //    the user portion (rare but possible: user@with@at).
        val atIdx = s.lastIndexOf('@')
        if (atIdx > 0) s = s.substring(atIdx + 1)

        // 3. IPv6 bracketed form `[addr]:port` or `[addr]`.
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close > 0) {
                val ipv6 = s.substring(1, close)
                val rest = s.substring(close + 1)
                val port = parsePortSuffix(rest)
                return Normalized(ipv6, port)
            }
            // Malformed — fall through.
        }

        // 4. IPv6 unbracketed (has multiple colons). Treat the whole thing
        //    as the host; we can't tell where port would be.
        if (s.count { it == ':' } >= 2) return Normalized(s, null)

        // 5. IPv4 / hostname with optional `:port` suffix.
        val colonIdx = s.indexOf(':')
        if (colonIdx > 0) {
            val host = s.substring(0, colonIdx)
            val portStr = s.substring(colonIdx + 1)
            val port = portStr.toIntOrNull()
            if (port != null && port in 1..65_535) return Normalized(host, port)
            // Unparseable :port — treat as part of hostname (will likely fail DNS).
            return Normalized(s, null)
        }

        return Normalized(s, null)
    }

    private fun parsePortSuffix(s: String): Int? {
        if (s.isEmpty()) return null
        if (!s.startsWith(":")) return null
        return s.substring(1).toIntOrNull()?.takeIf { it in 1..65_535 }
    }
}
