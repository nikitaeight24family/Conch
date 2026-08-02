package ai.eight24family.conch.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Translates a [TcpProbe.Outcome] into a [Diagnosis] aimed at a
 * non-technical user who DOES NOT have shell access on the server
 * (otherwise they'd already be logged in). All actions are
 * achievable from outside the box: check IP / restart power / ask
 * the hosting provider / ask the admin.
 *
 * Design decisions (post-critique):
 *  - **Does NOT re-probe TCP.** Caller hands in the outcome from
 *    [TcpProbe.probe], we just translate. No 3 s + 3 s double wait.
 *  - **Internet check is a soft hint**, not a hard branch. Public
 *    Wi-Fi without OS-validated internet still routes SSH fine;
 *    captive portals route nothing. We only force [Diagnosis.PhoneOffline]
 *    when `activeNetwork` is null (genuinely no link at all), otherwise
 *    we hedge the relevant copy.
 *  - **Private-IP detection.** Hosts in RFC1918 / link-local / loopback
 *    ranges get a different recommendation set — "make sure your phone
 *    is on the same Wi-Fi/VPN" instead of "check your hosting panel".
 *  - **Every diagnosis copy uses probabilistic language** — "Most
 *    likely", "Usually means" — because the network often won't tell us
 *    the exact reason and false confidence is worse than honest
 *    uncertainty.
 */
object ServerDiagnostics {

    sealed class Diagnosis(val title: String, val reasons: List<String>) {

        /** Probe outcome was good (TCP open + SSH banner). Caller
         *  should proceed to auth; any further failure is credential-side. */
        object Ok : Diagnosis(
            title = "Server responds normally",
            reasons = emptyList(),
        )

        /** Phone has no active network at all. Soft signal — we only
         *  fall here when `ConnectivityManager.activeNetwork == null`,
         *  not on weaker "not validated" cues. */
        object PhoneOffline : Diagnosis(
            title = "Your phone has no network",
            reasons = listOf(
                "Connect to Wi-Fi or check your mobile data, then retry.",
                "If you're on Wi-Fi with a captive portal (hotel / café), open a browser first to sign in.",
            ),
        )

        /** Hostname couldn't be resolved. Typo / dead domain / DNS broken. */
        data class HostNotFound(val host: String) : Diagnosis(
            title = "Can't find «$host»",
            reasons = listOf(
                "Most likely a typo — hostnames are easy to mistype.",
                "Try the server's IP address directly (your hosting panel or `ip addr` output on the server).",
                "Or your network's DNS might be broken — try switching between Wi-Fi and mobile data.",
            ),
        )

        /** TCP timed out / no route — host not reachable from this network. */
        data class ServerNotResponding(
            val host: String,
            val port: Int,
            val isPrivate: Boolean,
            val networkHint: Boolean,
        ) : Diagnosis(
            title = "Server isn't responding",
            reasons = buildList {
                add("Tried to reach $host:$port — got no answer at all.")
                if (isPrivate) {
                    add("**That's a local-network address.** Make sure your phone is on the same Wi-Fi / VPN as the server.")
                    add("Then check the server is powered on and on the network.")
                } else {
                    add("**The server might be turned off.** If it's yours, check it's running. If it's a VPS, log in to your hosting panel.")
                    add("**Wrong IP.** Double-check the address — VPS IPs can change after a reboot.")
                    add("**Firewall blocking your network.** Try a different network (switch Wi-Fi / mobile data / disable VPN). If that doesn't help and you know the admin, ask them to allow your IP through to port $port.")
                }
                if (networkHint) {
                    add("Your phone's network looks unstable — that might be the real problem.")
                }
            },
        )

        /** TCP refused (RST) — port closed. SSH daemon down OR router/firewall rejecting. */
        data class ServerUpSshDown(
            val host: String,
            val port: Int,
            val isPrivate: Boolean,
        ) : Diagnosis(
            title = "Server is up, but port $port is closed",
            reasons = buildList {
                add("$host answered, but actively refused port $port — nothing's listening there.")
                add("**SSH might not be running** on the server. Restart it if you can reach the box another way, or ask whoever set it up.")
                add("**Wrong port.** SSH often lives on `22`, but providers sometimes move it to `2222`, `2200`, or a custom port — check your hosting docs.")
                if (isPrivate) {
                    add("**Local router blocking.** On the same LAN as the server, a router-level firewall or VLAN can also reject the connection.")
                } else {
                    add("**A firewall is actively rejecting** the connection. Ask the admin to allow incoming SSH from your network.")
                }
            },
        )

        /** TCP opened and the server sent something — but it's not SSH. */
        data class WrongPort(
            val host: String,
            val port: Int,
            val detected: String,
        ) : Diagnosis(
            title = "That's not SSH on port $port",
            reasons = listOf(
                "Something is running on $host:$port — but it's $detected, not SSH.",
                "Most likely the **SSH port number is different**. Providers sometimes move SSH off `22` to `2222`, `2200`, or a high random port.",
                "Check your hosting panel, the welcome email from your provider, or `/etc/ssh/sshd_config` if you have access.",
            ),
        )

        /** TCP opened but no banner — proxy / overload / soft-ban. */
        data class SilentSsh(val host: String, val port: Int) : Diagnosis(
            title = "Port is open but the server is silent",
            reasons = listOf(
                "$host:$port accepted the connection, but didn't say anything within 1 second — SSH servers usually identify themselves immediately.",
                "**fail2ban / firewall rate-limit** might have soft-banned your IP after previous failures. Wait a few minutes before retrying.",
                "**A proxy or middlebox** between you and the server might be stripping the SSH banner.",
                "**Server overload** — try again in a minute.",
            ),
        )
    }

    /**
     * Public Android entrypoint — looks up phone connectivity from
     * [Context] then delegates to the pure [classifyPure] for the
     * actual decision tree. Split deliberately so tests can drive
     * `classifyPure` without faking a `Context`.
     */
    fun classify(
        host: String,
        port: Int,
        outcome: TcpProbe.Outcome,
        context: Context,
    ): Diagnosis = classifyPure(
        host = host,
        port = port,
        outcome = outcome,
        hasNetwork = hasActiveNetwork(context),
    )

    /** Pure-JVM diagnosis tree. No Android dependencies — unit-testable. */
    fun classifyPure(
        host: String,
        port: Int,
        outcome: TcpProbe.Outcome,
        hasNetwork: Boolean,
    ): Diagnosis {
        if (!hasNetwork && outcome is TcpProbe.Outcome.Failed) {
            // Truly no link — anything else we'd say is misleading.
            return Diagnosis.PhoneOffline
        }
        val isPrivate = isPrivateOrLocal(host)
        return when (outcome) {
            is TcpProbe.Outcome.Ok -> classifyBanner(host, port, outcome.bannerBytes)
            is TcpProbe.Outcome.Failed -> when (outcome.kind) {
                TcpProbe.Outcome.Failed.Kind.DnsFailed ->
                    Diagnosis.HostNotFound(host)
                TcpProbe.Outcome.Failed.Kind.Refused ->
                    Diagnosis.ServerUpSshDown(host, port, isPrivate)
                TcpProbe.Outcome.Failed.Kind.Timeout,
                TcpProbe.Outcome.Failed.Kind.NoRoute,
                TcpProbe.Outcome.Failed.Kind.Other ->
                    Diagnosis.ServerNotResponding(
                        host = host,
                        port = port,
                        isPrivate = isPrivate,
                        networkHint = !hasNetwork,
                    )
            }
        }
    }

    private fun classifyBanner(host: String, port: Int, banner: ByteArray?): Diagnosis = when {
        banner == null -> Diagnosis.SilentSsh(host, port)
        banner.startsWith("SSH-") -> Diagnosis.Ok
        else -> Diagnosis.WrongPort(host, port, identifyProtocol(banner))
    }

    // ─────────────── helpers (internal for testability) ───────────────

    /** True when phone has any active default network. Loose check — captive
     *  portals and LAN-only networks return true; only "no link at all" is false. */
    internal fun hasActiveNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return cm.activeNetwork != null
    }

    /** RFC1918 (10/8, 172.16-31/12, 192.168/16) + loopback + link-local +
     *  IPv6 ULA / link-local + literal "localhost". */
    internal fun isPrivateOrLocal(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) return true
        // IPv6
        if (h.contains(':')) {
            if (h.startsWith("::1") || h == "::") return true
            if (h.startsWith("fd") || h.startsWith("fc")) return true       // ULA
            if (h.startsWith("fe80:")) return true                          // link-local
            return false
        }
        // IPv4
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 10 -> true                                                 // 10.0.0.0/8
            a == 172 && b in 16..31 -> true                                 // 172.16.0.0/12
            a == 192 && b == 168 -> true                                    // 192.168.0.0/16
            a == 127 -> true                                                // loopback
            a == 169 && b == 254 -> true                                    // 169.254.0.0/16 link-local
            else -> false
        }
    }

    /** Friendly name for a non-SSH banner. Returns something the user
     *  can correlate with "ah, that's my web server" rather than hex. */
    internal fun identifyProtocol(bytes: ByteArray): String {
        val asString = bytes.decodeToString(throwOnInvalidSequence = false).take(16).trim()
        return when {
            asString.startsWith("HTTP/") -> "an HTTP web server"
            asString.startsWith("GET ") || asString.startsWith("POST ") || asString.startsWith("HEAD ") ->
                "an HTTP web server"
            asString.startsWith("220 ") -> "an SMTP or FTP server"
            asString.startsWith("+OK ") -> "a POP3 mail server"
            asString.startsWith("* OK ") -> "an IMAP mail server"
            bytes.size >= 3 && bytes[0] == 0x16.toByte() && bytes[1] == 0x03.toByte() ->
                "TLS / HTTPS (try port 443?)"
            else -> {
                val printable = asString.filter { it.code in 0x20..0x7E }
                if (printable.length >= 4) "an unknown protocol (`$printable…`)"
                else "an unknown binary protocol (`${bytes.take(4).joinToString(" ") { "%02x".format(it) }}…`)"
            }
        }
    }

    private fun ByteArray.startsWith(prefix: String): Boolean {
        val pb = prefix.toByteArray(Charsets.US_ASCII)
        if (this.size < pb.size) return false
        for (i in pb.indices) if (this[i] != pb[i]) return false
        return true
    }
}
