package ai.eight24family.conch.ssh

/**
 * The vocabulary for "why couldn't we reach this server" — and nothing else.
 *
 * ⛔ THIS USED TO OPEN A SOCKET. It was a pre-flight: connect to the SSH port,
 * read sixteen bytes of banner, hang up, and hand [ServerDiagnostics] a typed
 * outcome so the user learned the host was dead before paying a security-key tap
 * for it.
 *
 * That connect-and-hang-up is a preauth disconnect in the server's log, and the
 * owner's own fail2ban banned his phone over three of them inside ten minutes —
 * with no failed login anywhere in the file (2026-08-29). Sending our own
 * identification string first had already been tried; it changed the log line
 * from "did not receive identification string" to an ordinary preauth
 * disconnect, and the jail counted those too. Rationing the probe was the next
 * idea and it was the wrong shape of answer: it still meant connecting in order
 * to hang up, just less often.
 *
 * So the probe is gone. The diagnosis now comes from the connection the app was
 * going to make anyway — see [ServerDiagnostics.fromConnectFailure]. TCP
 * refusal, timeouts, DNS and routing all raise long before any credential is
 * offered, so nothing had to be given up to stop making throwaway connections,
 * and a successful attempt now ends with a connection that stays up instead of
 * one that is closed and immediately reopened.
 *
 * ⚠ Do not add a socket back to this file. If something needs to know whether a
 * host is reachable, connect to it for real and keep the connection.
 */
object TcpProbe {

    /**
     * What was learned about reaching a host. Produced from a real connection
     * attempt's outcome, then translated by [ServerDiagnostics].
     */
    sealed interface Outcome {
        /**
         * The transport came up. [bannerBytes] carries the first bytes the peer
         * sent when something read them — an SSH banner, or something that is
         * plainly not SSH — and is null when nobody looked or the peer stayed
         * silent. Null therefore means "no evidence", never "silent server".
         */
        data class Ok(val bannerBytes: ByteArray?) : Outcome

        /** The transport never came up. [kind] is what the socket layer said. */
        data class Failed(val kind: Kind, val cause: Throwable) : Outcome {
            enum class Kind {
                /** `UnknownHostException` — the name did not resolve. */
                DnsFailed,

                /** `SocketTimeoutException` — packets went nowhere, silently. */
                Timeout,

                /** `ConnectException` — a reset came back; the port is closed
                 *  or something is refusing us on purpose. */
                Refused,

                /** `NoRouteToHostException` — the network cannot get there. */
                NoRoute,

                /** Anything else; the exception itself carries the detail. */
                Other,
            }
        }
    }
}
