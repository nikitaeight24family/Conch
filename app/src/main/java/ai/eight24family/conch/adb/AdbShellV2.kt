package ai.eight24family.conch.adb

import java.io.ByteArrayOutputStream

/**
 * The sub-protocol inside an ADB `shell,v2` stream.
 *
 * The plain `shell:` service hands back one undifferentiated blob: stdout and
 * stderr already mixed, and no exit status at all. The bridge's contract has
 * carried all three separately since it was written — the agent gets clean
 * stdout to parse, with the exit code and a stderr snippet in metadata — so the
 * v2 framing is the one worth speaking.
 *
 * Each packet is a one-byte id, a four-byte LITTLE-endian length, then that many
 * bytes. Note the endianness: the ADB message header two layers up writes its
 * own lengths little-endian too, but the pairing packets in between are BIG
 * endian. Three framings in one protocol family, two conventions.
 */
object AdbShellV2 {

    const val ID_STDIN = 0
    const val ID_STDOUT = 1
    const val ID_STDERR = 2
    const val ID_EXIT = 3
    const val ID_CLOSE_STDIN = 4

    /** What a command left behind. */
    data class Result(val stdout: String, val stderr: String, val exitCode: Int, val truncated: Boolean)

    /** Wrap bytes as one v2 packet. */
    fun packet(id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = id.toByte()
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = ((payload.size ushr 16) and 0xFF).toByte()
        out[4] = ((payload.size ushr 24) and 0xFF).toByte()
        payload.copyInto(out, 5)
        return out
    }

    /**
     * Split a v2 stream into its three channels.
     *
     * ⚠ Packets do NOT align with the ADB messages that carried them: one
     * message can hold several packets, and one packet can straddle two
     * messages. Anything that assumed alignment would work in testing, where
     * output is small, and truncate real command output at the first boundary.
     * So this consumes a single concatenated buffer.
     *
     * A trailing partial packet is dropped rather than guessed at: it means the
     * stream ended mid-packet, and inventing the rest would put made-up bytes
     * into an agent's hands.
     */
    fun demux(stream: ByteArray, limit: Int = 4 * 1024 * 1024): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        var exit = -1
        var truncated = false
        var i = 0
        while (i + 5 <= stream.size) {
            val id = stream[i].toInt() and 0xFF
            val length = (stream[i + 1].toInt() and 0xFF) or
                ((stream[i + 2].toInt() and 0xFF) shl 8) or
                ((stream[i + 3].toInt() and 0xFF) shl 16) or
                ((stream[i + 4].toInt() and 0xFF) shl 24)
            if (length < 0 || i + 5 + length > stream.size) break
            val from = i + 5
            when (id) {
                ID_STDOUT -> {
                    val room = limit - out.size()
                    if (room > 0) out.write(stream, from, minOf(room, length)) else truncated = true
                    if (length > room) truncated = true
                }
                ID_STDERR -> {
                    val room = limit - err.size()
                    if (room > 0) err.write(stream, from, minOf(room, length)) else truncated = true
                }
                // The exit packet is a single byte. Anything else in that slot is
                // a protocol the caller does not speak, so it is left as -1
                // rather than read as a number that happens to be there.
                ID_EXIT -> if (length >= 1) exit = stream[from].toInt() and 0xFF
            }
            i = from + length
        }
        return Result(
            stdout = out.toString(Charsets.UTF_8.name()),
            stderr = err.toString(Charsets.UTF_8.name()),
            exitCode = exit,
            truncated = truncated,
        )
    }
}
