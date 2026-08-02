package ai.eight24family.conch.ssh

import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cheap "is the SSH port even open, and does it speak SSH?" check used
 * as a pre-flight before we ask the user to physically tap their
 * security key.
 *
 * Returns a TYPED outcome — [Outcome] is a sealed hierarchy that
 * categorises the failure at the TCP layer (DnsFailed, Refused, Timeout,
 * NoRoute, Other) or returns [Outcome.Ok] with the first ≤16 banner
 * bytes the server sent. Callers that want to surface a structured
 * diagnostic ([ServerDiagnostics]) can do it WITHOUT re-running TCP —
 * they consume the outcome directly. This is the difference between a
 * 7-second double-probe and the ~4-second single-probe.
 *
 * Worst-case total budget: 3 s TCP connect + 1 s banner read = 4 s.
 * Banner read is best-effort: silence after 1 s = `bannerBytes = null`,
 * not a failure.
 */
object TcpProbe {

    sealed interface Outcome {
        /** TCP connect succeeded. `bannerBytes` is whatever the server
         *  sent within 1 s — possibly null (port open but silent),
         *  possibly an SSH banner ("SSH-2.0-..."), possibly something
         *  else (HTTP, TLS, etc). The next-stage classifier decides
         *  what to do with the bytes. */
        data class Ok(val bannerBytes: ByteArray?) : Outcome

        /** TCP couldn't even establish. [kind] gives the specific
         *  reason for downstream classification. */
        data class Failed(val kind: Kind, val cause: Throwable) : Outcome {
            enum class Kind {
                /** `UnknownHostException` — DNS / nodename resolution failed. */
                DnsFailed,
                /** `SocketTimeoutException` on connect — packets dropped silently. */
                Timeout,
                /** `ConnectException` — TCP RST received, port actively closed. */
                Refused,
                /** `NoRouteToHostException` — local routing table can't reach. */
                NoRoute,
                /** Everything else — wrap with the exception's class name. */
                Other,
            }
        }
    }

    suspend fun probe(host: String, port: Int, connectTimeoutMs: Int = 3000, bannerTimeoutMs: Int = 1000): Outcome =
        withContext(Dispatchers.IO) {
            val tag = "SshAi-TcpProbe"
            android.util.Log.d(tag, "probe $host:$port (connect=${connectTimeoutMs}ms banner=${bannerTimeoutMs}ms)")
            val t0 = System.currentTimeMillis()
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                val openMs = System.currentTimeMillis() - t0
                android.util.Log.d(tag, "  ✓ TCP open in ${openMs}ms")
                // Banner read — best-effort, capped at 16 bytes / bannerTimeoutMs.
                val banner: ByteArray? = readBanner(socket, bannerTimeoutMs)
                android.util.Log.d(
                    tag,
                    "  banner=${if (banner == null) "null/silent" else "${banner.size}B (${describeBytes(banner)})"}"
                )
                Outcome.Ok(banner)
            } catch (e: java.net.UnknownHostException) {
                android.util.Log.w(tag, "  ✗ DNS in ${System.currentTimeMillis() - t0}ms: ${e.message}")
                Outcome.Failed(Outcome.Failed.Kind.DnsFailed, e)
            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.w(tag, "  ✗ timeout in ${System.currentTimeMillis() - t0}ms")
                Outcome.Failed(Outcome.Failed.Kind.Timeout, e)
            } catch (e: java.net.ConnectException) {
                android.util.Log.w(tag, "  ✗ refused in ${System.currentTimeMillis() - t0}ms: ${e.message}")
                Outcome.Failed(Outcome.Failed.Kind.Refused, e)
            } catch (e: java.net.NoRouteToHostException) {
                android.util.Log.w(tag, "  ✗ no route in ${System.currentTimeMillis() - t0}ms: ${e.message}")
                Outcome.Failed(Outcome.Failed.Kind.NoRoute, e)
            } catch (e: Throwable) {
                android.util.Log.w(tag, "  ✗ ${e.javaClass.simpleName} in ${System.currentTimeMillis() - t0}ms: ${e.message}")
                Outcome.Failed(Outcome.Failed.Kind.Other, e)
            } finally {
                SilentlyTry.fired("SshAi-TcpProbe", "close probe socket") { socket.close() }
            }
        }

    private fun readBanner(socket: Socket, timeoutMs: Int): ByteArray? {
        return try {
            socket.soTimeout = timeoutMs
            val input = socket.getInputStream()
            val buf = ByteArray(16)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n <= 0) break
                read += n
                // Stop early on newline — SSH banners end in \n; an early
                // bail keeps the worst case fast.
                if (buf.copyOf(read).any { it == '\n'.code.toByte() }) break
            }
            if (read == 0) null else buf.copyOf(read)
        } catch (_: java.net.SocketTimeoutException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun describeBytes(bytes: ByteArray): String {
        val printable = bytes.takeWhile { it in 0x20.toByte()..0x7E.toByte() || it == '\r'.code.toByte() || it == '\n'.code.toByte() }
        if (printable.size >= 4) {
            return printable.toByteArray().decodeToString().trim().take(12)
        }
        return bytes.take(4).joinToString(" ") { "%02x".format(it) }
    }
}
