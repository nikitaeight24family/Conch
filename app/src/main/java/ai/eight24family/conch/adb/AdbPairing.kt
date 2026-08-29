package ai.eight24family.conch.adb

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The pairing conversation Android's Wireless Debugging expects, above SPAKE2.
 *
 * The shape of it: connect over TLS (any certificate — the channel is
 * authenticated by the code, not by the certificate), exchange one SPAKE2
 * message each, derive a key, then exchange one encrypted packet each carrying
 * the peer's identity. When it ends, the device trusts our key and will let us
 * connect to it over ADB from then on, no computer and no second app involved.
 *
 * ⚠ THREE DETAILS THAT FAIL SILENTLY. Each is a place where the reference
 * implementation's C idiom decides the bytes, and getting it wrong produces a
 * perfectly working client that simply never pairs:
 *
 *  1. The SPAKE2 password is NOT just the six digits. The exported keying
 *     material of the TLS connection (64 bytes) is appended to it, binding the
 *     exchange to that exact channel. See [passwordWithChannelBinding].
 *  2. The identity strings are passed with `sizeof`, so their NUL terminator is
 *     part of the hashed name: sixteen bytes, not fifteen. Same for the TLS
 *     export label, which is "adb-label" plus its NUL — ten bytes.
 *  3. The AES key's HKDF info is passed with `sizeof(info) - 1`, so that one
 *     does NOT include its NUL. Two constants, two different conventions, in
 *     the same protocol.
 */
object AdbPairing {

    /** Packet version we speak, and the minimum we accept from the device. */
    const val VERSION = 1

    /** Packet kinds, in the order the exchange uses them. */
    const val TYPE_SPAKE2_MSG = 0
    const val TYPE_PEER_INFO = 1

    /** A PeerInfo is a fixed 8192-byte record: one type byte, then its data. */
    const val PEER_INFO_SIZE = 8192

    /** Peer identity kinds. We send our ADB public key; the device sends its id. */
    const val PEER_INFO_RSA_PUBLIC_KEY = 0
    const val PEER_INFO_DEVICE_GUID = 1

    /** Ceiling the device puts on a packet payload. */
    const val MAX_PAYLOAD = PEER_INFO_SIZE * 2

    /** Header: version, type, then the payload size in NETWORK byte order. */
    const val HEADER_SIZE = 6

    /** Identity bound into SPAKE2 — WITH the terminator, as `sizeof` yields. */
    val CLIENT_NAME: ByteArray = "adb pair client".toByteArray(Charsets.US_ASCII) + 0
    val SERVER_NAME: ByteArray = "adb pair server".toByteArray(Charsets.US_ASCII) + 0

    /** RFC 5705 label for the channel binding — also WITH its terminator. */
    val TLS_EXPORT_LABEL: ByteArray = "adb-label".toByteArray(Charsets.US_ASCII) + 0

    /** How many bytes of keying material get appended to the pairing code. */
    const val TLS_EXPORT_SIZE = 64

    /** HKDF info for the AES key — WITHOUT a terminator, unlike the names. */
    val AES_KEY_INFO: ByteArray = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

    /**
     * The six digits the phone shows, with the TLS channel binding appended.
     *
     * This is what makes the pairing code useless to anyone who is not on this
     * exact TLS connection: someone who steals the code and races us gets a
     * different password, because their channel exports different material.
     */
    fun passwordWithChannelBinding(code: ByteArray, exportedKeyingMaterial: ByteArray): ByteArray {
        require(exportedKeyingMaterial.size == TLS_EXPORT_SIZE) {
            "the channel binding is $TLS_EXPORT_SIZE bytes, got ${exportedKeyingMaterial.size}"
        }
        return code + exportedKeyingMaterial
    }

    /** One framed packet. */
    data class Packet(val type: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Packet && type == other.type && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * type + payload.contentHashCode()
    }

    fun writePacket(output: OutputStream, packet: Packet) {
        require(packet.payload.isNotEmpty()) { "a pairing packet with no payload is refused by the peer" }
        require(packet.payload.size <= MAX_PAYLOAD) {
            "payload of ${packet.payload.size} exceeds the peer's limit of $MAX_PAYLOAD"
        }
        val header = ByteArray(HEADER_SIZE)
        header[0] = VERSION.toByte()
        header[1] = packet.type.toByte()
        // Big-endian: the reference puts this through htonl.
        val n = packet.payload.size
        header[2] = (n ushr 24).toByte()
        header[3] = (n ushr 16).toByte()
        header[4] = (n ushr 8).toByte()
        header[5] = n.toByte()
        output.write(header)
        output.write(packet.payload)
        output.flush()
    }

    fun readPacket(input: InputStream): Packet {
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header)
        val version = header[0].toInt() and 0xFF
        if (version < VERSION) {
            throw IllegalStateException("pairing packet version $version is older than ours ($VERSION)")
        }
        val type = header[1].toInt() and 0xFF
        if (type != TYPE_SPAKE2_MSG && type != TYPE_PEER_INFO) {
            throw IllegalStateException("unknown pairing packet type $type")
        }
        val size = ((header[2].toInt() and 0xFF) shl 24) or
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or
            (header[5].toInt() and 0xFF)
        // The device refuses both extremes; so do we, before allocating anything
        // on a number the peer chose.
        if (size == 0 || size > MAX_PAYLOAD) {
            throw IllegalStateException("pairing payload size $size is out of range")
        }
        val payload = ByteArray(size)
        readFully(input, payload)
        return Packet(type, payload)
    }

    /** Our identity packet: a type byte, then the key, padded to a fixed record. */
    fun encodePeerInfo(type: Int, data: ByteArray): ByteArray {
        require(data.size <= PEER_INFO_SIZE - 1) { "peer info data does not fit in a record" }
        val out = ByteArray(PEER_INFO_SIZE)
        out[0] = type.toByte()
        data.copyInto(out, 1)
        return out
    }

    /** The device's identity, trimmed of the record's zero padding. */
    fun decodePeerInfo(record: ByteArray): Pair<Int, ByteArray> {
        require(record.size == PEER_INFO_SIZE) {
            "a peer info record is $PEER_INFO_SIZE bytes, got ${record.size}"
        }
        var end = record.size
        while (end > 1 && record[end - 1] == 0.toByte()) end--
        return (record[0].toInt() and 0xFF) to record.copyOfRange(1, end)
    }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var read = 0
        while (read < dst.size) {
            val n = input.read(dst, read, dst.size - read)
            if (n < 0) throw EOFException("stream ended ${dst.size - read}B into a pairing packet")
            read += n
        }
    }

    /**
     * HKDF-SHA256 (RFC 5869). Written here rather than reached for because the
     * platform's own KDF classes arrived late and this is twenty lines that can
     * be checked against the RFC's vectors directly.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val hashLen = mac.macLength
        require(length in 1..(255 * hashLen)) { "HKDF cannot produce $length bytes" }
        // Extract: an absent salt is a string of zeros the length of the hash.
        mac.init(SecretKeySpec(salt ?: ByteArray(hashLen), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand.
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - produced)
            previous.copyInto(out, produced, 0, take)
            produced += take
            counter++
        }
        return out
    }

    /**
     * The AES-128-GCM channel the identity packets travel in, keyed by the
     * SPAKE2 output.
     *
     * ⚠ The nonce is a COUNTER, not random: eight little-endian bytes of a
     * sequence number, then four zeros. Each direction keeps its own count and
     * both start at zero. This is safe only because the key is fresh for every
     * pairing — and it means the two sides must stay in step: decrypt the
     * packets in the order they were encrypted, or the tag simply will not
     * verify.
     */
    class Cipher(keyMaterial: ByteArray) {
        private val key: SecretKeySpec

        init {
            require(keyMaterial.isNotEmpty()) { "no key material from the exchange" }
            key = SecretKeySpec(hkdfSha256(keyMaterial, null, AES_KEY_INFO, 16), "AES")
        }

        private var encryptSequence = 0L
        private var decryptSequence = 0L

        fun encrypt(plaintext: ByteArray): ByteArray {
            val out = cipher(javax.crypto.Cipher.ENCRYPT_MODE, encryptSequence).doFinal(plaintext)
            encryptSequence++
            return out
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val out = cipher(javax.crypto.Cipher.DECRYPT_MODE, decryptSequence).doFinal(ciphertext)
            decryptSequence++
            return out
        }

        private fun cipher(mode: Int, sequence: Long): javax.crypto.Cipher {
            val nonce = ByteArray(12)
            var s = sequence
            for (i in 0 until 8) {
                nonce[i] = (s and 0xFF).toByte()
                s = s ushr 8
            }
            return javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, key, GCMParameterSpec(128, nonce))
            }
        }
    }
}
