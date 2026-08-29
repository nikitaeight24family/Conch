package ai.eight24family.conch.adb

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ADB wire protocol, written here rather than pulled in.
 *
 * WHY THIS EXISTS. Privileged work on the phone — reading system logs, taking a
 * screenshot, running a shell command for an agent — needs the *shell* uid, and
 * that used to arrive through a helper app the user had to install and re-arm
 * after every reboot. Android's own Wireless Debugging already hands out exactly
 * that uid to whoever can speak ADB to the device, and the device can speak it
 * to ITSELF over loopback. So Conch can be its own adb client and drop the
 * dependency entirely.
 *
 * ⚠ Measured on the owner's phone (OPPO CPH2671, Android 16, 2026-08-29):
 * `adbd` listens on the wildcard address while Wireless Debugging is on, and
 * `127.0.0.1` reaches it — but the moment Wi-Fi goes off the platform clears
 * `adb_wifi_enabled` itself and the listener disappears. Loopback is therefore
 * available whenever Wireless Debugging is armed, and arming it is a Wi-Fi-gated
 * platform decision we do not control.
 *
 * THIS FILE IS THE FRAMING ONLY — the 24-byte message header every ADB peer
 * exchanges. No sockets, no crypto, no Android: pure bytes in, pure bytes out,
 * so it is testable on the JVM and cannot lie about what went over the wire.
 *
 * Every command id is DERIVED from its four-character name instead of being
 * written as a hex literal. The constants are famously easy to transpose, and a
 * wrong one fails as a silent hang rather than an error; `commandOf("CNXN")`
 * cannot be mistyped without also being unreadable.
 */
object AdbProtocol {

    /** Header length in bytes: six little-endian 32-bit fields. */
    const val HEADER_SIZE = 24

    /** Four ASCII chars, little-endian, as the protocol writes them. */
    fun commandOf(name: String): Int {
        require(name.length == 4) { "an ADB command is four characters, got '$name'" }
        var v = 0
        for (i in 3 downTo 0) {
            val c = name[i].code
            require(c in 0..0x7F) { "non-ASCII in command name '$name'" }
            v = (v shl 8) or c
        }
        return v
    }

    val A_CNXN = commandOf("CNXN")
    val A_AUTH = commandOf("AUTH")
    val A_OPEN = commandOf("OPEN")
    val A_OKAY = commandOf("OKAY")
    val A_CLSE = commandOf("CLSE")
    val A_WRTE = commandOf("WRTE")
    val A_STLS = commandOf("STLS")

    /** Protocol version we announce in CNXN (the TLS-capable one). */
    const val VERSION = 0x0100_0001

    /** Version announced in an STLS message. */
    const val STLS_VERSION = 0x0100_0000

    /** Largest payload we accept in one message (adbd's modern default, 1 MiB).
     *  Also the guard against a hostile length field: anything larger is refused
     *  before a single byte is allocated. */
    const val MAX_PAYLOAD = 1024 * 1024

    /**
     * One protocol message.
     *
     * [magic] is not stored: it is always `command xor -1`, and keeping it as a
     * field would only create a way for the two to disagree.
     */
    data class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray = ByteArray(0),
    ) {
        fun name(): String = buildString {
            var v = command
            repeat(4) { append(((v and 0xFF).toChar())); v = v ushr 8 }
        }

        /** Header + payload, ready for the socket. */
        fun encode(): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(command)
            buf.putInt(arg0)
            buf.putInt(arg1)
            buf.putInt(payload.size)
            buf.putInt(checksum(payload))
            buf.putInt(command xor -1)
            buf.put(payload)
            return buf.array()
        }

        // data class + ByteArray: equals/hashCode must compare CONTENTS, or two
        // identical messages read off the wire would test as different.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Message) return false
            return command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var h = command
            h = 31 * h + arg0
            h = 31 * h + arg1
            h = 31 * h + payload.contentHashCode()
            return h
        }

        override fun toString(): String = "${name()}(arg0=$arg0, arg1=$arg1, ${payload.size}B)"
    }

    /**
     * The payload check ADB carries. Despite the field's name in the reference
     * implementation it is NOT a CRC — it is the sum of the payload's bytes,
     * each taken UNSIGNED. Signed bytes here would produce a number adbd
     * rejects for any payload containing a byte above 0x7F, which is every
     * payload that carries a shell command's output.
     */
    fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) sum += (b.toInt() and 0xFF)
        return sum
    }

    /** A message whose payload is a NUL-terminated string, as ADB writes them. */
    fun stringMessage(command: Int, arg0: Int, arg1: Int, text: String): Message =
        Message(command, arg0, arg1, (text + "\u0000").toByteArray(Charsets.UTF_8))

    /**
     * Read exactly one message. Returns null at a clean end of stream (the peer
     * hung up between messages, which is normal); throws on a stream that ends
     * mid-message or contradicts itself, because continuing to parse after that
     * would invent data.
     */
    fun read(input: InputStream): Message? {
        val header = ByteArray(HEADER_SIZE)
        val first = input.read(header, 0, 1)
        if (first < 0) return null
        readFully(input, header, 1, HEADER_SIZE - 1)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val length = buf.int
        val check = buf.int
        val magic = buf.int
        if (magic != (command xor -1)) {
            throw IllegalStateException("ADB header failed its own magic check (command=$command)")
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw IllegalStateException("ADB payload length out of range: $length")
        }
        val payload = ByteArray(length)
        readFully(input, payload, 0, length)
        // ⚠ THE CHECK FIELD IS NOT VERIFIED, ON PURPOSE. It is vestigial: modern
        // `adbd` stopped filling it and sends zero, and its own reader ignores
        // what arrives there. Verifying it therefore rejects every message a real
        // device sends — measured on the owner's phone, 2026-08-29: the first
        // connection after a successful pairing failed with "payload checksum
        // mismatch" on adbd's very first reply, in a loop.
        //
        // The mocked tests could never have caught that: both ends of them were
        // written here, so both computed the same sum. We still WRITE the sum, in
        // case an older peer checks it, and the header's magic — which adbd does
        // maintain — remains a hard check.
        @Suppress("UNUSED_EXPRESSION") check
        return Message(command, arg0, arg1, payload)
    }

    fun write(output: OutputStream, message: Message) {
        output.write(message.encode())
        output.flush()
    }

    private fun readFully(input: InputStream, dst: ByteArray, offset: Int, count: Int) {
        var read = 0
        while (read < count) {
            val n = input.read(dst, offset + read, count - read)
            if (n < 0) throw EOFException("stream ended ${count - read}B into an ADB message")
            read += n
        }
    }
}
