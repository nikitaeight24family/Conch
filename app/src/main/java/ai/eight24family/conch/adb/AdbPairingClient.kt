package ai.eight24family.conch.adb

import java.io.InputStream
import java.io.OutputStream

/**
 * The whole pairing exchange, start to finish.
 *
 * Given a connected TLS session and the six digits the phone is showing, this
 * hands the device our public key and comes back with the device's own — after
 * which the phone lists Conch among its paired computers and will accept ADB
 * connections from it. No desktop, and no second app on the phone.
 *
 * Deliberately takes streams and an exporter rather than a socket, so the whole
 * sequence can be driven against a peer that lives in the same process. The
 * exchange is symmetric in both phases — write ours, then read theirs — and
 * getting that order backwards deadlocks against a peer that does the same.
 */
class AdbPairingClient(private val key: AdbKey) {

    /** What the device told us about itself once the exchange succeeded. */
    data class Result(val peerInfoType: Int, val peerInfo: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Result && peerInfoType == other.peerInfoType && peerInfo.contentEquals(other.peerInfo)

        override fun hashCode(): Int = 31 * peerInfoType + peerInfo.contentHashCode()
    }

    /** The code did not match — told apart from every other failure on purpose. */
    class WrongCodeException(cause: Throwable?) : Exception(
        "the pairing code did not match — check the six digits on the phone and try again",
        cause,
    )

    /**
     * Run the exchange.
     *
     * [exportKeyingMaterial] is the TLS session's RFC 5705 export; it is a
     * parameter rather than a hidden dependency because it is the one thing that
     * binds this exchange to that connection, and a test has to be able to prove
     * both ends agree on it.
     */
    fun pair(
        input: InputStream,
        output: OutputStream,
        code: ByteArray,
        exportKeyingMaterial: (label: ByteArray, length: Int) -> ByteArray,
    ): Result {
        val binding = exportKeyingMaterial(AdbPairing.TLS_EXPORT_LABEL, AdbPairing.TLS_EXPORT_SIZE)
        val password = AdbPairing.passwordWithChannelBinding(code, binding)

        // We are the client, which is SPAKE2's Alice; the device is Bob. Swap
        // these and both sides mask with the same point, so no key is ever shared.
        val spake = Spake2(Spake2.Role.ALICE, AdbPairing.CLIENT_NAME, AdbPairing.SERVER_NAME)

        AdbPairing.writePacket(
            output,
            AdbPairing.Packet(AdbPairing.TYPE_SPAKE2_MSG, spake.generateMessage(password)),
        )
        val theirSpake = AdbPairing.readPacket(input)
        if (theirSpake.type != AdbPairing.TYPE_SPAKE2_MSG) {
            throw IllegalStateException("expected a SPAKE2 message, got type ${theirSpake.type}")
        }
        val cipher = AdbPairing.Cipher(spake.processMessage(theirSpake.payload))

        // Our identity: the ADB public key, in the fixed-size record.
        val ourRecord = AdbPairing.encodePeerInfo(
            AdbPairing.PEER_INFO_RSA_PUBLIC_KEY, key.publicKeyBlob(),
        )
        AdbPairing.writePacket(
            output,
            AdbPairing.Packet(AdbPairing.TYPE_PEER_INFO, cipher.encrypt(ourRecord)),
        )

        val theirInfo = AdbPairing.readPacket(input)
        if (theirInfo.type != AdbPairing.TYPE_PEER_INFO) {
            throw IllegalStateException("expected peer info, got type ${theirInfo.type}")
        }
        // ⚠ THIS is where a wrong code surfaces, and it surfaces as a failed
        // authentication tag rather than as anything that says "wrong code".
        // SPAKE2 completes happily on both sides with mismatched passwords — it
        // just yields two different keys — so the first thing that can possibly
        // notice is this decryption. Saying so plainly here is the difference
        // between "check the digits" and an unexplained crypto exception.
        val record = try {
            cipher.decrypt(theirInfo.payload)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongCodeException(e)
        } catch (e: javax.crypto.BadPaddingException) {
            throw WrongCodeException(e)
        }
        val (type, data) = AdbPairing.decodePeerInfo(record)
        return Result(type, data)
    }
}
