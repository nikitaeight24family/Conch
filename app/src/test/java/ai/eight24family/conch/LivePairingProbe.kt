package ai.eight24family.conch

import ai.eight24family.conch.adb.AdbKey
import ai.eight24family.conch.adb.AdbPairing
import ai.eight24family.conch.adb.AdbPairingClient
import ai.eight24family.conch.adb.AdbTls
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Pairs with a REAL device, to answer the one question the other tests cannot:
 * does `adbd` agree with us?
 *
 * Everything else in this package proves the halves agree with each other. That
 * is worth having and it is not the same claim. A single byte wrong in the
 * export label, the identity strings, the length prefixes or the password
 * scalar produces code that passes every round-trip test and never pairs with a
 * phone — because SPAKE2 does not fail on a mismatch, it just yields two keys
 * that differ.
 *
 * SKIPPED unless pointed at a device, because it needs a pairing dialog open on
 * a real phone and a code that expires:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*LivePairingProbe*' \
 *         -Dconch.pair.host=192.168.1.39 -Dconch.pair.port=41234 -Dconch.pair.code=642099
 *
 * The port and code both come from the phone's own "Pair device with pairing
 * code" dialog, and both change every time it is opened.
 */
class LivePairingProbe {

    @Test
    fun `pair with a device that is showing a pairing code`() {
        val host = System.getProperty("conch.pair.host")
        val port = System.getProperty("conch.pair.port")?.toIntOrNull()
        val code = System.getProperty("conch.pair.code")
        assumeTrue("no device pointed at; set -Dconch.pair.host/port/code to run", host != null && port != null && code != null)

        val key = AdbKey.generate("conch@livetest")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port!!), 10_000)
            socket.soTimeout = 30_000
            val session = AdbTls.connect(
                socket.getInputStream(), socket.getOutputStream(), key.certificate, key.tlsPrivateKey,
            )
            println("[live] TLS up with $host:$port")
            val result = AdbPairingClient(key).pair(
                input = session.input,
                output = session.output,
                code = code!!.toByteArray(Charsets.US_ASCII),
                exportKeyingMaterial = { label, length -> session.exportKeyingMaterial(label, length) },
            )
            println(
                "[live] PAIRED. device peer-info type=${result.peerInfoType} " +
                    "(${if (result.peerInfoType == AdbPairing.PEER_INFO_DEVICE_GUID) "device guid" else "rsa key"}) " +
                    "value=${String(result.peerInfo).take(120)}",
            )
            session.close()
        }
    }
}
