package ai.eight24family.conch.adb

import ai.eight24family.conch.util.SilentlyTry
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ADB conversation, one layer above [AdbProtocol]'s framing: the handshake a
 * client and `adbd` perform, and the streams they multiplex over it.
 *
 * Takes an already-connected [InputStream]/[OutputStream] pair instead of
 * opening a socket itself. That is what makes it testable against a scripted
 * peer on the JVM — and it is also what lets the transport be a plain socket
 * during the handshake and a TLS one immediately afterwards, which is exactly
 * what the modern protocol demands.
 *
 * WHAT THE PEER DOES, in the order it does it:
 *
 *  1. Both sides send `CNXN` announcing version, maximum payload and a banner.
 *  2. A device with Wireless Debugging armed answers `STLS` — "continue inside
 *     TLS". The client answers `STLS` too and both hand the socket to TLS, where
 *     the device checks the client certificate it was paired with. The handshake
 *     then restarts: `CNXN` again, now encrypted.
 *  3. A device on the legacy path answers `AUTH` and wants an RSA signature.
 *     Conch does not walk that path; it is named here only so the code can say
 *     which world it landed in instead of hanging.
 *  4. `OPEN` names a service (`shell,v2,raw:id`); the device replies `OKAY` with
 *     its own stream id, then `WRTE` for output, and `CLSE` at the end.
 *
 * Local stream ids start at 1 and never repeat: adbd keys its half of a stream
 * on the number we chose, so reusing one while the peer still holds it open
 * would deliver another stream's output into this one.
 */
class AdbConnection(
    private val input: InputStream,
    private val output: OutputStream,
    /** Announced to the peer; informational, but adbd logs it. */
    private val banner: String = "host::conch",
) : Closeable {

    /** What the device answered our opening CNXN with. */
    enum class Greeting {
        /** `CNXN` — connected, no TLS asked for (already inside TLS, or legacy). */
        CONNECTED,

        /** `STLS` — the device wants the rest of the conversation inside TLS. */
        WANTS_TLS,

        /** `AUTH` — the legacy RSA path. Not a road Conch takes. */
        WANTS_RSA_AUTH,
    }

    private val nextLocalId = AtomicInteger(1)

    private val TAG = "Conch-AdbLocal"

    /** AUTH message types, from the reference implementation. */
    private companion object {
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
    }

    /**
     * adbd wants this connection inside TLS before it will serve anything.
     *
     * ⛔ IT CAN SAY SO AT OPEN TIME, NOT ONLY AS A GREETING. Measured on the
     * owner's phone (CPH2671, 2026-09-03): adbd answered our CNXN with its own
     * CNXN — the full device banner, so the connection looked established and
     * authenticated — and then answered the FIRST `OPEN` with `A_STLS`. Every
     * command failed on a session that had just reported the device by name,
     * and because a failed command is indistinguishable from no shell at all,
     * the app told the owner to go and set up a phone bridge that was already
     * paired and answering. Typed, so the caller can do the one correct thing:
     * upgrade and try again.
     */
    class NeedsTls : IllegalStateException("adbd wants this connection inside TLS")

    /** The device's banner from its CNXN, once it has sent one. */
    var deviceBanner: String? = null
        private set

    /**
     * Send our `CNXN` and read the answer. Returns what the device wants next
     * rather than deciding for the caller: the two branches need completely
     * different machinery (a TLS upgrade versus a signature), and hiding that
     * behind one call is how a connection ends up hanging with no explanation.
     */
    fun greet(): Greeting {
        AdbProtocol.write(
            output,
            AdbProtocol.stringMessage(
                AdbProtocol.A_CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, banner,
            ),
        )
        val reply = AdbProtocol.read(input)
            ?: throw IllegalStateException("adbd closed the connection without answering CNXN")
        return when (reply.command) {
            AdbProtocol.A_CNXN -> {
                deviceBanner = reply.payload.toString(Charsets.UTF_8).trimEnd(Char(0))
                Greeting.CONNECTED
            }
            AdbProtocol.A_STLS -> Greeting.WANTS_TLS
            AdbProtocol.A_AUTH -> {
                pendingAuthToken = reply.payload
                Greeting.WANTS_RSA_AUTH
            }
            else -> throw IllegalStateException("unexpected answer to CNXN: " + reply.name())
        }
    }

    /**
     * Wait for the daemon's OWN `CNXN` after a finished TLS handshake — without
     * sending one.
     *
     * ⛔ SENDING A SECOND `CNXN` IS WHAT BROKE THE PHONE'S SHELL, AND IT LOOKED
     * LIKE A REVOKED KEY FOR A DAY. adbd announces itself the moment the tunnel
     * is up. A `CNXN` arriving on a transport it already considers established
     * means, in the protocol, "the peer restarted" — so adbd takes the
     * transport OFFLINE, re-requests TLS, and from then on silently ignores
     * every `OPEN`. Measured on the owner's phone (CPH2651/CPH2671, 2026-09-03),
     * in adbd's own log, all inside one millisecond:
     *
     *     [server]: Handshake succeeded.
     *     adbd_wifi_secure_connect: connected host-22
     *     AdbDebuggingManager: Received WIFI TLS connected key ... conch@android
     *     ADB wifi device disconnected
     *
     * and then a thirty-second read timeout on `shell,v2`. The key was never
     * revoked — `dumpsys adb` lists it as authorised throughout, and the
     * handshake succeeds every time. We were knocking ourselves offline with
     * the greeting, one line after being let in.
     *
     * Returns true once the banner is in hand. A stray `A_STLS` (the daemon's
     * leftover request from before the tunnel) is skipped rather than treated
     * as failure.
     */
    fun awaitBanner(): Boolean {
        while (true) {
            val m = AdbProtocol.read(input) ?: return false
            when (m.command) {
                AdbProtocol.A_CNXN -> {
                    deviceBanner = m.payload.toString(Charsets.UTF_8).trimEnd(Char(0))
                    return true
                }
                AdbProtocol.A_STLS -> Unit
                else -> {
                    android.util.Log.w(TAG, "waiting for the banner, got " + m.name())
                    return false
                }
            }
        }
    }

    /** The challenge from a [Greeting.WANTS_RSA_AUTH], kept because the answer
     *  is built from it. */
    private var pendingAuthToken: ByteArray? = null

    /**
     * The legacy handshake, for an adbd that is NOT running the TLS/pairing
     * flow — the mode `adb tcpip` leaves it in.
     *
     * WHY THIS MATTERS MORE THAN IT LOOKS: this listener does not care about
     * Wi-Fi. Android's wireless debugging is gated on a Wi-Fi association and is
     * torn down the moment it drops; this one keeps running until the phone
     * reboots. It is the only way an app on the device can hold shell access
     * across a network change without a helper app or root.
     *
     * Two steps, and the second is the one the user sees:
     *  1. Sign the challenge. If the device already trusts this key, it answers
     *     CNXN and we are in — no dialog, nothing to tap.
     *  2. If it does not, it challenges again. We then OFFER the public key,
     *     which is what makes Android show its "Allow debugging?" dialog. After
     *     the tap, the device answers CNXN and remembers the key.
     */
    fun legacyAuth(key: AdbKey): Boolean {
        val token = pendingAuthToken ?: return false
        AdbProtocol.write(
            output,
            AdbProtocol.Message(AdbProtocol.A_AUTH, AUTH_SIGNATURE, 0, key.signAdbToken(token)),
        )
        var reply = AdbProtocol.read(input) ?: return false
        if (reply.command == AdbProtocol.A_CNXN) {
            deviceBanner = reply.payload.toString(Charsets.UTF_8).trimEnd(Char(0))
            return true
        }
        if (reply.command != AdbProtocol.A_AUTH) return false
        // Not trusted yet — offer the key and let the phone ask its owner.
        AdbProtocol.write(
            output,
            AdbProtocol.Message(
                AdbProtocol.A_AUTH, AUTH_RSAPUBLICKEY, 0,
                key.publicKeyBlob() + byteArrayOf(0),
            ),
        )
        reply = AdbProtocol.read(input) ?: return false
        if (reply.command == AdbProtocol.A_CNXN) {
            deviceBanner = reply.payload.toString(Charsets.UTF_8).trimEnd(Char(0))
            return true
        }
        return false
    }

    /** Answer a [Greeting.WANTS_TLS] so the peer starts its TLS handshake. */
    fun acceptTls() {
        AdbProtocol.write(
            output,
            AdbProtocol.Message(AdbProtocol.A_STLS, AdbProtocol.STLS_VERSION, 0),
        )
    }

    /**
     * Open [service] and return the stream. Blocks until the device accepts it
     * (`OKAY`) or refuses it (`CLSE`); a refusal raises rather than returning an
     * empty stream, because "no such service" and "the command printed nothing"
     * must never look the same to a caller.
     */
    fun open(service: String): AdbStream {
        val localId = nextLocalId.getAndIncrement()
        AdbProtocol.write(output, AdbProtocol.stringMessage(AdbProtocol.A_OPEN, localId, 0, service))
        var m = AdbProtocol.read(input)
            ?: throw IllegalStateException("adbd closed while opening '" + service + "'")
        // ⛔ A STREAM THAT ENDED CAN STILL HAVE A WORD LEFT. adbd closes its
        // half AFTER the last payload, so the `CLSE` for the PREVIOUS command
        // routinely arrives while the next `OPEN` is in flight - and this used
        // to be reported as "unexpected CLSE while opening 'shell,v2,raw:...'",
        // i.e. a working phone failing on its second command (measured
        // 2026-09-03, mid-way through starting the phone's Linux). Ids exist
        // exactly so this can be told apart: anything addressed to a stream
        // that is not the one we just asked for is not ours to choke on.
        while (m.arg1 != localId && m.command != AdbProtocol.A_STLS) {
            android.util.Log.i(TAG, "skipping late " + m.name() + " for stream " + m.arg1)
            m = AdbProtocol.read(input)
                ?: throw IllegalStateException("adbd closed while opening '" + service + "'")
        }
        // ⛔ DRAIN THE DAEMON'S SECOND ANSWER TO THE IN-TUNNEL `CNXN`.
        //
        // adbd keeps its `use_tls` flag set for the life of the transport, so
        // the CNXN we send INSIDE the finished tunnel is answered twice: with
        // the device banner, and with another `A_STLS`. The banner satisfied the
        // handshake and the STLS stayed in the pipe — where the very next read
        // belongs to the first `OPEN`. Measured on the owner's phone
        // (CPH2671, 2026-09-03): "unexpected STLS while opening
        // 'shell,v2,raw:…'" on a connection that had just named the device, on
        // a key the device still lists as authorised (`dumpsys adb` —
        // `conch@android` in user_keys). Nothing was revoked and nothing needed
        // pairing: we were reading the daemon's leftover word.
        //
        // Drained HERE, not with a timed peek after the handshake: a bounded
        // wait against a TLS record is a coin toss, and this is the one place
        // where the stray packet can be told apart with certainty.
        if (m.command == AdbProtocol.A_STLS) {
            android.util.Log.i(TAG, "queued STLS drained before '" + service + "'")
            m = AdbProtocol.read(input)
                ?: throw IllegalStateException("adbd closed after its queued STLS ('" + service + "')")
        }
        return when {
            m.command == AdbProtocol.A_OKAY && m.arg1 == localId ->
                AdbStream(localId, remoteId = m.arg0)
            m.command == AdbProtocol.A_CLSE && m.arg1 == localId ->
                throw IllegalStateException("adbd refused the service '" + service + "'")
            // Not a protocol error and not addressed elsewhere: the daemon is
            // telling us to finish the handshake it did not insist on earlier.
            // TWICE is not a leftover: the daemon is refusing us.
            m.command == AdbProtocol.A_STLS -> {
                android.util.Log.w(TAG, "STLS twice while opening '" + service + "' - the key is refused")
                throw NeedsTls()
            }
            // Anything addressed elsewhere is not ours to consume. With one
            // stream in flight it cannot legitimately happen, and swallowing it
            // would hide a protocol bug behind an empty result.
            else -> throw IllegalStateException(
                "unexpected " + m.name() + " while opening '" + service +
                    "' (arg0=" + m.arg0 + ", arg1=" + m.arg1 + ")",
            )
        }
    }

    /** An open service. Output arrives as `WRTE`, each of which we must `OKAY`. */
    inner class AdbStream(private val localId: Int, private val remoteId: Int) {

        /**
         * Read the service's output until the device closes the stream.
         *
         * [limit] bounds what one call will hold in memory. The caller is a
         * phone and `logcat` on a busy device is effectively endless, so on
         * reaching the limit the stream is closed and what was read is returned
         * — truncated on purpose, rather than growing until the process dies.
         */
        fun readAll(limit: Int = 4 * 1024 * 1024): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val m = AdbProtocol.read(input) ?: break
                when (m.command) {
                    AdbProtocol.A_WRTE -> {
                        val room = limit - out.size()
                        if (room > 0) out.write(m.payload, 0, minOf(room, m.payload.size))
                        // Flow control: adbd waits for this before sending more.
                        AdbProtocol.write(
                            output, AdbProtocol.Message(AdbProtocol.A_OKAY, localId, remoteId),
                        )
                        if (out.size() >= limit) {
                            close()
                            break
                        }
                    }
                    // Answer the close, so the daemon frees its half now rather
                    // than trailing a `CLSE` into whatever we open next.
                    AdbProtocol.A_CLSE -> {
                        SilentlyTry.fired(TAG, "acknowledge the stream close") { close() }
                        break
                    }
                    AdbProtocol.A_OKAY -> Unit // acknowledgement of our own write
                    else -> throw IllegalStateException(
                        "unexpected " + m.name() + " on an open stream",
                    )
                }
            }
            return out.toByteArray()
        }

        /** Send bytes to the service (a shell's stdin). */
        fun write(bytes: ByteArray) {
            AdbProtocol.write(
                output, AdbProtocol.Message(AdbProtocol.A_WRTE, localId, remoteId, bytes),
            )
        }

        fun close() {
            AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_CLSE, localId, remoteId))
        }
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }
}
