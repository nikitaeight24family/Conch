package ai.eight24family.conch.adb

import ai.eight24family.conch.util.SilentlyTry
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

    /** How long to wait for the daemon's own banner after the TLS handshake
     *  before falling back to greeting it. It arrives in milliseconds on
     *  loopback; this is slack, and it is paid once per connection. */
    private const val BANNER_WAIT_MS = 3_000

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
            // ⛔ AN `A_STLS` HERE CANNOT BE ANSWERED, ONLY PREVENTED. By the time
            // it arrives we have already written an `OPEN` into a socket where
            // the daemon is waiting for a TLS ClientHello, and those bytes are
            // not recoverable — the upgrade has to happen during the handshake,
            // which is what [handshake]'s peek is for. Measured 2026-09-03: an
            // upgrade attempted from here connected, greeted, and was refused
            // all over again. So this stays a plain throw with a name on it.
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
        /** Sets the transport's read timeout in ms — used to bound the wait for
         *  the daemon's banner. Null on a transport that cannot be timed out
         *  (the in-process test peer); the wait is then unbounded, which is
         *  correct there because the peer is scripted. */
        setReadTimeout: ((Int) -> Unit)? = null,
        /** What to put the read timeout back to afterwards. */
        restoreReadTimeoutMs: Int = 30_000,
    ): Session {
        val plain = AdbConnection(input, output)
        when (val greeting = plain.greet()) {
            AdbConnection.Greeting.WANTS_TLS -> Unit
            AdbConnection.Greeting.CONNECTED ->
                // Already inside TLS, or a device with no TLS at all. A daemon
                // that still wants the upgrade says so on the first service
                // open, and [AdbConnection.open] knows what to do with that —
                // which is cheaper and surer than timing a peek here.
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

        val (secure, closers) = tlsUpgrade(input, output, key, plain, setReadTimeout, restoreReadTimeoutMs)
        return Session(closers + extraCloseables, secure)
    }

    /**
     * Answer `STLS`, hand the socket to TLS, and restart the ADB conversation
     * inside it. Returns the encrypted connection and what has to be closed for
     * it. Shared by both paths that reach TLS: the daemon that asks in its
     * greeting, and the one that asks at the first service open.
     */
    private fun tlsUpgrade(
        input: InputStream,
        output: OutputStream,
        key: AdbKey,
        plain: AdbConnection,
        setReadTimeout: ((Int) -> Unit)? = null,
        restoreReadTimeoutMs: Int = 30_000,
    ): Pair<AdbConnection, List<Closeable>> {
        plain.acceptTls()
        val tls = AdbTls.connect(input, output, key.certificate, key.tlsPrivateKey)
        val secure = AdbConnection(tls.input, tls.output)
        // ⛔ LISTEN FIRST — BUT ONLY WHERE THE WAIT CAN BE BOUNDED.
        //
        // The daemon announces itself the moment the tunnel is up, and greeting
        // it again takes the transport straight back offline (see
        // [AdbConnection.awaitBanner], which carries the measurement). That is
        // true of a real adbd. It is NOT true of a peer that waits to be spoken
        // to — and one of those is the in-process test peer, which reads the
        // client's `CNXN` before writing its banner.
        //
        // With no socket there is no read timeout to set, so a listen there is
        // not "a bounded wait that may find nothing", it is a DEADLOCK: both
        // sides waiting for the other's first word. Shipped in 0.6.1, and it did
        // not fail loudly — it hung the unit-test task, so CI sat in "Run unit
        // tests" for an hour and a half instead of going red (2026-09-03).
        // No knob, no listening: greet, exactly as before.
        val heard = if (setReadTimeout == null) {
            false
        } else {
            try {
                setReadTimeout(BANNER_WAIT_MS)
                secure.awaitBanner()
            } catch (_: java.net.SocketTimeoutException) {
                false
            } finally {
                setReadTimeout(restoreReadTimeoutMs)
            }
        }
        if (!heard) {
            // A daemon that waits to be spoken to (and the in-process test peer).
            // Only reached when nothing was announced, so no transport is
            // disturbed by asking.
            val after = secure.greet()
            if (after != AdbConnection.Greeting.CONNECTED) {
                throw IllegalStateException(
                    "the device did not accept our certificate — it answered $after inside TLS. " +
                        "The key is probably not paired with this device.",
                )
            }
        }
        android.util.Log.i(
            "Conch-AdbLocal",
            "inside TLS (${if (heard) "announced" else "greeted"}), banner=${secure.deviceBanner?.take(40)}",
        )
        return secure to listOf(Closeable { tls.close() })
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
                setReadTimeout = { ms -> socket.soTimeout = ms },
                restoreReadTimeoutMs = readTimeoutMs,
            )
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }
}
