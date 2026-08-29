package ai.eight24family.conch.adb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talking ADB to the phone we are running on.
 *
 * This is the point of everything else in this package: with a key the device
 * has been paired with, Conch opens a connection to `adbd` over the loopback
 * interface and runs commands at the SHELL uid — the same level `adb shell`
 * gives a desktop, with no desktop, no root, and no second app installed.
 *
 * The sequence is not obvious and the order matters:
 *
 *  1. Plain socket. Send `CNXN`; the device answers `STLS` — it wants TLS.
 *  2. Answer `STLS` and hand the socket to TLS, presenting the paired
 *     certificate. This is where the device decides whether it knows us.
 *  3. The ADB conversation then STARTS AGAIN inside TLS: a second `CNXN`, whose
 *     answer carries the device banner.
 *  4. Only now can services be opened.
 *
 * Skipping step 3 — treating the TLS handshake as the end of the handshake —
 * leaves the peer waiting for a connection message that never comes, and the
 * first `OPEN` is answered with silence.
 */
object AdbLocal {

    /** The port `adbd` listens on is not fixed; see the discovery this pairs with. */
    class Session internal constructor(
        private val closeables: List<Closeable>,
        private val connection: AdbConnection,
    ) : Closeable {

        /** What the device said about itself in its encrypted `CNXN`. */
        val deviceBanner: String? get() = connection.deviceBanner

        /**
         * Run one command and wait for it to finish.
         *
         * Returns stdout, stderr and the exit code separately — the contract the
         * phone bridge has always had with the agent on the server, kept intact
         * so nothing above this layer has to change when it stops going through
         * a second app.
         */
        fun exec(command: String, limit: Int = 4 * 1024 * 1024): AdbShellV2.Result {
            // `raw` because there is no terminal on this side; a pty would wrap
            // the output in escape sequences the caller would have to strip.
            val stream = connection.open("shell,v2,raw:$command")
            return AdbShellV2.demux(stream.readAll(limit), limit)
        }

        override fun close() {
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /**
     * Do the handshake over an already-open transport.
     *
     * Separated from socket handling so the whole sequence — including the TLS
     * upgrade and the second `CNXN` — can be driven against a peer in the same
     * process.
     */
    fun handshake(
        input: InputStream,
        output: OutputStream,
        key: AdbKey,
        extraCloseables: List<Closeable> = emptyList(),
    ): Session {
        val plain = AdbConnection(input, output)
        when (val greeting = plain.greet()) {
            AdbConnection.Greeting.WANTS_TLS -> Unit
            AdbConnection.Greeting.CONNECTED ->
                // Already inside TLS, or a device with no TLS at all: nothing to
                // upgrade, so the connection we have is the one to use.
                return Session(extraCloseables, plain)
            AdbConnection.Greeting.WANTS_RSA_AUTH -> {
                // The `adb tcpip` listener. No TLS on this path: authenticate
                // with the key and the connection we already have IS the session.
                if (!plain.legacyAuth(key)) {
                    throw IllegalStateException(
                        "this device did not accept our key on the legacy port — " +
                            "answer its \"Allow debugging?\" dialog and try again",
                    )
                }
                return Session(extraCloseables, plain)
            }
            else -> throw IllegalStateException("unexpected greeting: $greeting")
        }

        plain.acceptTls()
        val tls = AdbTls.connect(input, output, key.certificate, key.tlsPrivateKey)
        val secure = AdbConnection(tls.input, tls.output)
        val after = secure.greet()
        if (after != AdbConnection.Greeting.CONNECTED) {
            throw IllegalStateException(
                "the device did not accept our certificate — it answered $after inside TLS. " +
                    "The key is probably not paired with this device.",
            )
        }
        return Session(listOf(Closeable { tls.close() }) + extraCloseables, secure)
    }

    /** Connect to `adbd` on this device and complete the handshake. */
    fun connect(
        port: Int,
        key: AdbKey,
        host: String = "127.0.0.1",
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 30_000,
    ): Session {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            return handshake(
                socket.getInputStream(),
                socket.getOutputStream(),
                key,
                extraCloseables = listOf(socket),
            )
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }
}
