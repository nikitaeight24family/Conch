package ai.eight24family.conch.agent.codex

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * A MINIMAL RFC6455 CLIENT — handshake plus text frames, nothing else.
 *
 * ## Why not a WebSocket library
 *
 * The socket this talks to is not reachable by a URL: it is `127.0.0.1:<port>`
 * ON THE SERVER, reached through the SSH transport the app already holds (a
 * direct-tcpip channel). A library would want to dial a host itself, so it would
 * force a listening port on the PHONE to tunnel into — a socket any other app on
 * the device could connect to, for a protocol that carries the user's whole
 * session. Handing sshj's streams to ~120 lines of framing keeps the bytes
 * inside the encrypted channel and adds no dependency to an APK that ships to
 * strangers.
 *
 * ## What is deliberately NOT implemented
 *
 * No extensions (we never offer `Sec-WebSocket-Extensions`, so the server cannot
 * negotiate `permessage-deflate` and RSV1 stays clear), no continuation across
 * fragments beyond reassembly, no binary payloads (app-server speaks JSON text).
 * Pings are answered because a long idle chat will get one; close frames end the
 * read loop. Anything else is an error, and an error here means the channel is
 * rebuilt — never silently mis-framed, which is how a JSON-RPC stream turns into
 * garbage that looks like a protocol bug for weeks.
 */
internal object CodexWsFrames {

    private val rng = SecureRandom()

    /** Send the upgrade request and consume the response headers.
     *  Throws when the server does not answer `101`. */
    fun handshake(input: InputStream, output: OutputStream, host: String, port: Int) {
        val key = ByteArray(16).also { rng.nextBytes(it) }
            .let { Base64.getEncoder().encodeToString(it) }
        val req = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        output.write(req.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        // Read headers byte-by-byte: whatever follows the blank line is already
        // frame data and must NOT be swallowed by a buffered reader.
        val head = StringBuilder()
        var state = 0
        while (state < 4) {
            val b = input.read()
            if (b < 0) throw IllegalStateException("websocket handshake: stream closed")
            val c = b.toChar()
            head.append(c)
            state = when {
                c == '\r' && (state == 0 || state == 2) -> state + 1
                c == '\n' && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
        }
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!status.contains(" 101")) {
            throw IllegalStateException("websocket handshake refused: ${status.trim()}")
        }
    }

    /** One masked text frame — every client frame MUST be masked (RFC6455 §5.1);
     *  an unmasked one is a protocol error the server closes on. */
    fun writeText(output: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(payload.size + 14)
        out.write(0x81) // FIN + text
        val n = payload.size
        when {
            n < 126 -> out.write(0x80 or n)
            n < 65536 -> {
                out.write(0x80 or 126)
                out.write((n ushr 8) and 0xFF); out.write(n and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) out.write(((n.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { rng.nextBytes(it) }
        out.write(mask)
        for (i in payload.indices) out.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
        synchronized(output) {
            output.write(out.toByteArray())
            output.flush()
        }
    }

    /**
     * Read one complete TEXT message, reassembling fragments. Returns null at a
     * clean close. Control frames are handled inline: a ping is answered with a
     * pong through [output] so an idle chat is not dropped by the server.
     */
    fun readText(input: DataInputStream, output: OutputStream): String? {
        val message = java.io.ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val b1 = input.read()
            if (b1 < 0) return null
            // A server frame is never masked; if one is, the stream is not what
            // we think it is and guessing would corrupt the JSON-RPC stream.
            if ((b1 and 0x80) != 0) throw IllegalStateException("masked server frame")
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = ((input.read().toLong() and 0xFF) shl 8) or (input.read().toLong() and 0xFF)
            } else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or (input.read().toLong() and 0xFF) }
            }
            if (len > 64L * 1024 * 1024) {
                // Desync, not a big message: dump what we are actually looking
                // at, because "frame too large" alone sends you hunting the
                // wrong bug (measured 2026-09-12 — the stream was mid-JSON).
                val peek = ByteArray(24)
                val got = input.read(peek, 0, peek.size).coerceAtLeast(0)
                throw IllegalStateException(
                    "frame desync: b0=0x%02x b1=0x%02x len=%d next=%s".format(
                        b0, b1, len,
                        String(peek, 0, got, Charsets.ISO_8859_1)
                            .map { if (it.code in 32..126) it else '.' }
                            .joinToString(""),
                    ),
                )
            }
            val payload = ByteArray(len.toInt())
            input.readFully(payload)
            when (opcode) {
                0x0, 0x1 -> {
                    message.write(payload)
                    if (fin) return message.toString("UTF-8")
                }
                0x8 -> return null // close
                0x9 -> writePong(output, payload) // ping → pong, same payload
                0xA -> Unit // pong: nothing to do
                else -> throw IllegalStateException("unsupported opcode $opcode")
            }
        }
    }

    private fun writePong(output: OutputStream, payload: ByteArray) {
        val out = java.io.ByteArrayOutputStream(payload.size + 8)
        out.write(0x8A)
        out.write(0x80 or payload.size.coerceAtMost(125))
        val mask = ByteArray(4).also { rng.nextBytes(it) }
        out.write(mask)
        for (i in payload.indices) out.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
        synchronized(output) {
            output.write(out.toByteArray())
            output.flush()
        }
    }
}
