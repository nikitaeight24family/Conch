package ai.eight24family.conch.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The one-time handshake that gives Conch shell access to this phone.
 *
 * Android shows a six-digit code in its own "Pair device with pairing code"
 * dialog. Typing it here binds the phone to a key Conch generated, and the phone
 * remembers that key **permanently** — across reboots, and with no code ever
 * again. What still costs something afterwards is arming Wireless Debugging
 * after each boot, which Android allows only while connected to Wi-Fi; that rule
 * is the platform's and no app avoids it without root.
 *
 * The port is found rather than asked for: while the dialog is open the phone
 * advertises `_adb-tls-pairing._tcp` over mDNS, and it advertises it to itself
 * as much as to anyone else. So the user types six digits and nothing else.
 */
object PhoneBridgePairing {

    sealed interface Outcome {
        /** Paired. [deviceInfo] is what the phone said about itself. */
        data class Paired(val deviceInfo: String) : Outcome

        /** The dialog is not open — nothing is advertising a pairing port. */
        object NoPairingDialog : Outcome

        /** The six digits did not match, or they belong to another connection. */
        object WrongCode : Outcome

        /** Anything else, with the reason as the phone or the stack reported it. */
        data class Failed(val reason: String) : Outcome
    }

    /** The port Android is advertising for pairing right now, if any. */
    suspend fun findPairingPort(context: Context): Int? =
        AdbDiscovery.find(context, AdbDiscovery.SERVICE_PAIRING, timeoutMs = 6_000)?.port

    /**
     * Run the exchange against the pairing port.
     *
     * [port] is normally what [findPairingPort] returned; it stays a parameter so
     * a phone whose mDNS is uncooperative can still be paired by reading the port
     * off the same dialog that shows the code.
     */
    suspend fun pair(context: Context, port: Int, code: String): Outcome = withContext(Dispatchers.IO) {
        val digits = code.trim()
        if (digits.isEmpty()) return@withContext Outcome.Failed("no code entered")
        val key = LocalAdbShell.identity()
        val socket = Socket()
        try {
            // Loopback on purpose: the dialog's own address belongs to the Wi-Fi
            // network, and this exchange has no business leaving the device.
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 20_000
            val tls = AdbTls.connect(
                socket.getInputStream(), socket.getOutputStream(), key.certificate, key.tlsPrivateKey,
            )
            val result = AdbPairingClient(key).pair(
                input = tls.input,
                output = tls.output,
                code = digits.toByteArray(Charsets.US_ASCII),
                exportKeyingMaterial = { label, length -> tls.exportKeyingMaterial(label, length) },
            )
            tls.close()
            Outcome.Paired(String(result.peerInfo, Charsets.UTF_8).trim())
        } catch (e: AdbPairingClient.WrongCodeException) {
            Outcome.WrongCode
        } catch (e: java.net.ConnectException) {
            // Nothing listening: the dialog was closed, or its port has already
            // rotated. Both mean "open it again", not "something is broken".
            Outcome.NoPairingDialog
        } catch (t: Throwable) {
            Outcome.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Has this phone ever accepted our key? */
    suspend fun verify(): Boolean = LocalAdbShell.available()
}
