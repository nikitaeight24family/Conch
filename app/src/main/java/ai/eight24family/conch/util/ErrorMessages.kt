package ai.eight24family.conch.util

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Centralised conversion of low-level exceptions into the short, plain
 * sentences that show up in `SessionState.Failed.reason` and
 * `AgentMessage.Error.text`. No stack traces, no java class names, no
 * "Caused by:" chains — the user gets one sentence describing what
 * went wrong and (where it's obvious) what to try next.
 *
 * Why centralised: error-message strings used to be inlined at each
 * `catch` site (`e.message ?: e.javaClass.simpleName`), which leaked
 * "EOFException" / "Premature EOF" / "InvalidKeyException" into the
 * UI. Unit-testable here, reusable across SSH / agent / file paths.
 */
object ErrorMessages {

    /**
     * Best-effort, user-facing one-liner for any Throwable.
     *
     * Pass an optional [context] hint to bias the wording — `"bootstrap"`
     * (we were dialing SSH / running the CLI handshake), `"send"` (a
     * turn was mid-flight), or null for generic phrasing.
     */
    fun humanize(t: Throwable, context: String? = null): String {
        val msg = t.message.orEmpty()

        // Order matters: most specific first.
        return when {
            // The phone's own Linux already explains itself in a sentence the
            // owner can act on; anything this layer added would be a guess about
            // networks, and the machine is in his hand.
            t is ai.eight24family.conch.linux.LinuxSsh.NotReachable ->
                t.message ?: "This phone's Linux is not reachable right now."

            // The pool's host-key-changed sentence is crafted end to end: it
            // names both fingerprints and the exact way out (server →
            // // system → fingerprint → forget). At ~300 chars it outgrows the
            // length gate below, which would degrade THE security message that
            // most needs to arrive into a bare "IllegalStateException".
            msg.startsWith("Host key changed") -> msg

            t is UnknownHostException ->
                "Host not found. Check the address."

            t is ConnectException ->
                "Can't reach the server. Check the address, port, and that SSH is running."

            t is NoRouteToHostException ->
                "No route to the server. Check your network."

            t is SocketTimeoutException ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ->
                "Connection timed out. The server might be down or behind a firewall."

            // sshj surfaces channel teardown / user cancel as Premature EOF.
            // We swallow user-cancels at the call site; anything that
            // reaches here is the server (or transport) hanging up.
            t is EOFException || msg.contains("Premature EOF", ignoreCase = true) ->
                "Server closed the connection unexpectedly."

            // Auth failure family: sshj uses UserAuthException, and the
            // "Exhausted available authentication methods" message comes
            // through as a bare ConnectionException with that text.
            msg.contains("Exhausted available authentication methods", ignoreCase = true) ->
                "Server rejected every authentication method. Check the username and that the public key is in ~/.ssh/authorized_keys."

            t.javaClass.simpleName == "UserAuthException" ||
                msg.contains("auth fail", ignoreCase = true) ||
                msg.contains("authentication fail", ignoreCase = true) ->
                "Authentication failed. Wrong password, key, or PIN."

            msg.contains("No such file", ignoreCase = true) ||
                msg.contains("ENOENT", ignoreCase = true) ->
                "File not found on the server."

            msg.contains("Permission denied", ignoreCase = true) ||
                msg.contains("EACCES", ignoreCase = true) ->
                "Permission denied on the server."

            msg.contains("No space", ignoreCase = true) ||
                msg.contains("ENOSPC", ignoreCase = true) ->
                "Disk is full on the server."

            // Generic socket trouble after auth — usually a transient
            // network blip. Tell the user it's recoverable.
            t is SocketException ->
                "Network connection dropped. Retry to reconnect."

            t is IOException && msg.isNotBlank() -> msg

            // Last resort: the raw message if it's something readable,
            // otherwise the bare exception name. NEVER stack-trace.
            msg.isNotBlank() && msg.length <= 240 -> msg
            else -> t.javaClass.simpleName
        }.let { base ->
            // Prefix the context tag only when it makes the line clearer.
            when (context) {
                "bootstrap" -> base
                "send" -> base
                else -> base
            }
        }
    }
}
