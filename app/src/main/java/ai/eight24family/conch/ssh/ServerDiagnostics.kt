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
 *  - **Opens no connection of its own, and never did.** The caller hands in an
 *    outcome and this only translates it. That outcome now comes from the
 *    connection the app was making anyway ([fromConnectFailure]) rather than
 *    from a pre-flight probe — the probe's connect-and-hang-up was getting the
 *    owner's phone banned by his own fail2ban. See [TcpProbe] for the whole
 *    story; the type still lives there, the socket does not.
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

        /**
         * TCP refused (RST) — but this host was answering minutes ago.
         *
         * ⚠ The distinction that matters. A ban from fail2ban and friends
         * REJECTS rather than drops, so it arrives as "connection refused" and
         * is indistinguishable at the socket from a stopped daemon or a moved
         * port. The one thing that tells them apart is history: a port does not
         * move, and a daemon does not stop, in the four minutes since it last
         * let you in. Sending someone to check their hosting docs in that
         * situation costs them an hour (owner, 2026-08-29 — he ended up
         * rebooting his router for a new address, which is a real remedy, just
         * not one anything told him about).
         */
        data class LikelyIpBanned(
            val host: String,
            val port: Int,
            val workedAgoMs: Long,
        ) : Diagnosis(
            title = "Refused — but it was answering just now",
            reasons = buildList {
                add("$host accepted a connection ${humanAgo(workedAgoMs)} and is now refusing port $port outright.")
                add(
                    "**That is what a temporary IP ban looks like.** Tools like fail2ban reject the " +
                        "connection instead of dropping it, so it reads exactly like a closed port. A burst " +
                        "of connections or a couple of failed logins is enough to trigger one.",
                )
                add(
                    "**The ban is on your IP, not your account** — so every server on that machine stops " +
                        "working at the same moment.",
                )
                add("**It clears itself.** Bans usually last from ten minutes to an hour; waiting is the fix.")
                add(
                    "**A different address works immediately** — switch to mobile data, or reconnect your " +
                        "router if it picks up a new one.",
                )
                add(
                    "If you can reach the machine another way: `sudo fail2ban-client unban <your ip>` " +
                        "lifts it now.",
                )
                add("Only if none of that fits: SSH really did stop, or was moved to another port.")
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
        hostWorkedAgoMs: Long? = null,
    ): Diagnosis = classifyPure(
        host = host,
        port = port,
        outcome = outcome,
        hasNetwork = hasActiveNetwork(context),
        hostWorkedAgoMs = hostWorkedAgoMs,
    )

    /**
     * The same diagnosis, taken from a REAL connection attempt that failed
     * instead of from a probe.
     *
     * ⛔ WHY THERE IS NO PROBE ANY MORE. The pre-flight opened its own socket to
     * the SSH port and dropped it before authenticating — a textbook preauth
     * disconnect in the server's log, and the owner's own fail2ban banned his
     * phone for exactly three of them inside ten minutes, with no failed login
     * anywhere in the file. Rationing those would still have meant connecting in
     * order to hang up. The attempt the app was going to make anyway carries the
     * same information: TCP refusal, timeout, DNS and routing all surface as
     * exceptions long before any credential is offered — and when it succeeds
     * there is a live connection at the end of it rather than a closed one.
     *
     * Unwraps the cause chain because sshj wraps the socket failure in its own
     * transport exception; the useful class is underneath.
     */
    fun fromConnectFailure(
        host: String,
        port: Int,
        cause: Throwable,
        context: Context,
        hostWorkedAgoMs: Long? = null,
    ): Diagnosis = classify(
        host = host,
        port = port,
        outcome = TcpProbe.Outcome.Failed(connectFailureKind(cause), cause),
        context = context,
        hostWorkedAgoMs = hostWorkedAgoMs,
    )

    /** Walks the cause chain for the socket-level failure sshj wrapped. */
    fun connectFailureKind(cause: Throwable): TcpProbe.Outcome.Failed.Kind {
        var t: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (t != null && seen.add(t)) {
            when (t) {
                is java.net.UnknownHostException -> return TcpProbe.Outcome.Failed.Kind.DnsFailed
                is java.net.SocketTimeoutException -> return TcpProbe.Outcome.Failed.Kind.Timeout
                // These two are siblings under SocketException, not parent and
                // child, so neither can shadow the other — listed most specific
                // first anyway, because the next exception type added here might
                // not be so independent.
                is java.net.NoRouteToHostException -> return TcpProbe.Outcome.Failed.Kind.NoRoute
                is java.net.ConnectException -> return TcpProbe.Outcome.Failed.Kind.Refused
            }
            t = t.cause
        }
        return TcpProbe.Outcome.Failed.Kind.Other
    }

    /**
     * How long ago a refusal still counts as "it was working" — beyond this the
     * ordinary explanations (a stopped daemon, a moved port) are back on equal
     * footing. Half an hour comfortably covers a default ban, which is usually
     * ten minutes.
     */
    const val RECENTLY_WORKED_MS = 30 * 60 * 1000L

    /** Pure-JVM diagnosis tree. No Android dependencies — unit-testable. */
    fun classifyPure(
        host: String,
        port: Int,
        outcome: TcpProbe.Outcome,
        hasNetwork: Boolean,
        /** Age of this HOST's last successful connection, or null if never. */
        hostWorkedAgoMs: Long? = null,
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
                    if (hostWorkedAgoMs != null && hostWorkedAgoMs in 0..RECENTLY_WORKED_MS) {
                        Diagnosis.LikelyIpBanned(host, port, hostWorkedAgoMs)
                    } else {
                        Diagnosis.ServerUpSshDown(host, port, isPrivate)
                    }
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

    /** "four minutes ago" and friends — for a sentence, not a log line. */
    internal fun humanAgo(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 90 -> "moments ago"
            seconds < 3600 -> "${seconds / 60} minutes ago"
            else -> "${seconds / 3600}h ago"
        }
    }

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
