package ai.eight24family.conch

import ai.eight24family.conch.adb.AdbKey
import ai.eight24family.conch.adb.AdbPairing
import ai.eight24family.conch.adb.AdbPairingClient
import ai.eight24family.conch.adb.Spake2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The pairing exchange run end to end against a peer that plays the device's
 * half — the same sequence, mirrored, built from the same pieces.
 *
 * What this can prove: that the two halves agree. Ordering (each side writes
 * its own message before reading the other's — reverse it and both sides block
 * forever), the role split, the channel binding, and that a wrong code is
 * reported as a wrong code rather than as a raw cipher failure.
 *
 * What it cannot prove: that a real `adbd` agrees with us. That needs a phone
 * showing a real pairing code, and until that has been done none of this may be
 * described as working.
 */
class AdbPairingClientTest {

    private val clientKey = AdbKey.generate("client@conch")
    private val pool = Executors.newSingleThreadExecutor()

    /** The device's half of the exchange, from the same building blocks. */
    private fun deviceHalf(
        input: InputStream,
        output: OutputStream,
        code: ByteArray,
        binding: ByteArray,
        deviceInfo: ByteArray,
    ): ByteArray {
        val password = AdbPairing.passwordWithChannelBinding(code, binding)
        val spake = Spake2(Spake2.Role.BOB, AdbPairing.SERVER_NAME, AdbPairing.CLIENT_NAME)
        // Write ours first, exactly as the client does — the exchange is
        // symmetric, and a peer that reads first would deadlock.
        AdbPairing.writePacket(
            output,
            AdbPairing.Packet(AdbPairing.TYPE_SPAKE2_MSG, spake.generateMessage(password)),
        )
        val theirs = AdbPairing.readPacket(input)
        val cipher = AdbPairing.Cipher(spake.processMessage(theirs.payload))

        AdbPairing.writePacket(
            output,
            AdbPairing.Packet(
                AdbPairing.TYPE_PEER_INFO,
                cipher.encrypt(AdbPairing.encodePeerInfo(AdbPairing.PEER_INFO_DEVICE_GUID, deviceInfo)),
            ),
        )
        val clientPacket = AdbPairing.readPacket(input)
        val (_, blob) = AdbPairing.decodePeerInfo(cipher.decrypt(clientPacket.payload))
        return blob
    }

    private class Wires {
        val clientToDevice = PipedOutputStream()
        val deviceIn = PipedInputStream(clientToDevice, 1 shl 16)
        val deviceToClient = PipedOutputStream()
        val clientIn = PipedInputStream(deviceToClient, 1 shl 16)
    }

    private fun run(
        clientCode: ByteArray,
        deviceCode: ByteArray = clientCode,
        clientBinding: ByteArray = ByteArray(64) { it.toByte() },
        deviceBinding: ByteArray = clientBinding,
        deviceInfo: ByteArray = "device-guid-1234".toByteArray(),
    ): Pair<AdbPairingClient.Result, Future<ByteArray>> {
        val w = Wires()
        val device = pool.submit<ByteArray> {
            deviceHalf(w.deviceIn, w.deviceToClient, deviceCode, deviceBinding, deviceInfo)
        }
        val result = AdbPairingClient(clientKey).pair(
            input = w.clientIn,
            output = w.clientToDevice,
            code = clientCode,
        ) { label, length ->
            assertArrayEquals("the client must export with ADB's label", AdbPairing.TLS_EXPORT_LABEL, label)
            assertEquals(AdbPairing.TLS_EXPORT_SIZE, length)
            clientBinding
        }
        return result to device
    }

    @Test
    fun `the exchange completes and each side gets the other's identity`() {
        val (result, device) = run("642099".toByteArray())
        assertEquals(AdbPairing.PEER_INFO_DEVICE_GUID, result.peerInfoType)
        assertEquals("device-guid-1234", String(result.peerInfo))
        // And the device received OUR public key, byte for byte.
        assertArrayEquals(clientKey.publicKeyBlob(), device.get(30, TimeUnit.SECONDS))
    }

    @Test
    fun `a wrong code is reported as a wrong code`() {
        // SPAKE2 does not fail on a bad password — both sides finish and simply
        // hold different keys. The first thing that can notice is the tag on the
        // encrypted identity, and the user must be told which of the two it was.
        assertThrows(AdbPairingClient.WrongCodeException::class.java) {
            run("642099".toByteArray(), deviceCode = "642098".toByteArray())
        }
    }

    @Test
    fun `a different channel binding fails just like a wrong code`() {
        // This is what the binding is for: someone who learns the code but is on
        // another connection derives a different password and gets nowhere.
        assertThrows(AdbPairingClient.WrongCodeException::class.java) {
            run(
                "642099".toByteArray(),
                clientBinding = ByteArray(64) { 1 },
                deviceBinding = ByteArray(64) { 2 },
            )
        }
    }

    @Test
    fun `the client sends its own key, in the record the protocol expects`() {
        val (_, device) = run("111111".toByteArray())
        val sent = device.get(30, TimeUnit.SECONDS)
        val text = String(sent, Charsets.US_ASCII)
        assertEquals("client@conch", text.substringAfter(' '))
    }
}
