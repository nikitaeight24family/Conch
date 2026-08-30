package ai.eight24family.conch.ssh

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Carrying ordinary traffic over the SSH connection this app already holds.
 *
 * ⚠ WHAT THIS IS FOR, AND WHAT IT IS NOT FOR. It does NOT make the agent
 * connection more reliable, and nothing here should ever be sold as if it did:
 * the SSH session already runs over whatever network the phone has, so pushing
 * more through it adds overhead and couples MORE things to the same link. One
 * dropped connection would take the browser down with the chat.
 *
 * What it does buy is access. The interesting machine is the server: a dev
 * server on its localhost, a database, an admin page bound to 127.0.0.1, a
 * service on its private network. None of that is reachable from a phone, and
 * "grab the laptop to look at it" is exactly the moment this product exists to
 * remove. Secondarily: traffic leaves from the server's address, and a café
 * network sees nothing but SSH.
 *
 * Two shapes, one primitive. Both open a `direct-tcpip` channel on the pooled
 * client — the same connection the chat uses, no second authentication, no
 * second touch for a security key:
 *
 *  • [LocalForward] — one port on the phone stands for one port on the server.
 *    `http://127.0.0.1:3000` in any browser reaches the server's own :3000.
 *  • [HttpProxy] — every connection through it is dialled FROM the server. This
 *    is what a device VPN hands to apps, so the phone's apparent address becomes
 *    the server's without a single packet being parsed.
 */
object SshTunnel {

    /** Copy until either side closes. Returns quietly: a closed tunnel is the
     *  normal end of every connection, not an error to report. */
    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Throwable) {
            // Either end going away is how this always finishes.
        }
    }

    /**
     * Wire a phone-side socket to a server-side channel, both directions.
     *
     * ⛔ EITHER END ENDS IT, AND THE SWITCH ENDS IT TOO. Two earlier faults
     * lived here, both of the same family — a thing that keeps running after
     * the reason for it is gone:
     *
     *  • the teardown waited on the OUTBOUND pump alone, so a connection the
     *    server closed while the phone stayed quiet held its socket, its channel
     *    and a coroutine for as long as the app lived;
     *  • and none of it was registered anywhere, so switching the tunnel off
     *    cancelled the accept loop and left every open connection pumping.
     *
     * Now whichever side finishes first closes both, and every live connection
     * is handed to [track] so the switch can close them for real.
     */
    private fun bridge(
        scope: CoroutineScope,
        local: Socket,
        remoteIn: InputStream,
        remoteOut: OutputStream,
        closer: () -> Unit,
        track: (java.io.Closeable) -> Unit,
        untrack: (java.io.Closeable) -> Unit,
    ) {
        val shut = java.util.concurrent.atomic.AtomicBoolean(false)
        // Declared first so the teardown can take ITSELF out of the tracking set
        // — without that the set only ever grows, which is what broke it.
        lateinit var handle: java.io.Closeable
        val closeBoth = {
            if (shut.compareAndSet(false, true)) {
                SilentlyTry.fired("SshAi-Tunnel", "close bridged sockets") { local.close() }
                SilentlyTry.fired("SshAi-Tunnel", "close channel") { closer() }
                untrack(handle)
            }
        }
        handle = java.io.Closeable { closeBoth() }
        track(handle)
        scope.launch(Dispatchers.IO) {
            try { pump(local.getInputStream(), remoteOut) } finally { closeBoth() }
        }
        scope.launch(Dispatchers.IO) {
            try { pump(remoteIn, local.getOutputStream()) } finally { closeBoth() }
        }
    }

    /**
     * The connections a running tunnel must be able to close when it is
     * switched off.
     *
     * ⛔ AN ENTRY LEAVES WHEN IT CLOSES. The first version only ever added, so
     * the set counted every connection the tunnel had EVER carried — hundreds
     * within minutes of ordinary browsing — and a 512-entry cap then evicted the
     * OLDEST without closing it. The oldest is precisely the long-lived one: a
     * websocket, an SSE stream, an open session to a database. Untracked, it
     * survived stop() and went on pumping through a tunnel the user had switched
     * off: exactly the bug the previous commit set out to kill, reintroduced by
     * its own fix (audit, 2026-08-30).
     *
     * With removal in place the set holds only what is actually open, so no cap
     * is needed — and a cap would be wrong anyway: silently dropping a live
     * connection from tracking is how the fault happened in the first place.
     */
    internal class LiveSet {
        private val items = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<java.io.Closeable, Boolean>(),
        )

        fun add(c: java.io.Closeable) { items.add(c) }

        fun remove(c: java.io.Closeable) { items.remove(c) }

        fun size(): Int = items.size

        fun closeAll() {
            val copy = ArrayList(items)
            items.clear()
            copy.forEach { SilentlyTry.fired("SshAi-Tunnel", "close tracked") { it.close() } }
        }
    }

    /**
     * One port on this phone, standing for one port on the server.
     *
     * The port is bound on LOOPBACK only. Binding the wildcard would put the
     * server's private services on the café's Wi-Fi for anyone to reach — the
     * opposite of the point.
     */
    class LocalForward(
        private val serverId: String,
        val localPort: Int,
        private val remoteHost: String,
        private val remotePort: Int,
    ) {
        // ⚠ RECREATED ON EVERY START. stop() cancels this scope, and a cancelled
        // scope launches nothing — so a second start() would bind the port, log
        // "forwarding …", return success, and never accept a connection. The UI
        // happens to build a new object each time, which hides it; the next
        // caller would not be so lucky (audit, 2026-08-30).
        private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var job: Job? = null
        private var server: ServerSocket? = null
        private val liveConnections = LiveSet()

        /** @return null on success, else why it could not start. */
        fun start(): String? {
            if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            // Pre-flight only. The client used for actual traffic is looked up
            // per connection — see the accept loop.
            ServiceLocator.sshConnectionPool.peek(serverId)
                ?: return "not connected to this server"
            val socket = SilentlyTry.logged("SshAi-Tunnel", "bind local port") {
                ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
            } ?: return "port $localPort is already in use on this phone"
            server = socket
            job = scope.launch {
                while (isActive && !socket.isClosed) {
                    val local = SilentlyTry.logged("SshAi-Tunnel", "accept") { socket.accept() } ?: break
                    // ⛔ RESOLVED HERE, NEVER CAPTURED. Switching Wi-Fi to mobile
                    // kills the transport; the app reconnects silently and the
                    // pool hands out a NEW client. A forward holding the old one
                    // would keep its port bound and look open while every
                    // connection through it died — the same stale claim that cost
                    // a whole day on the phone shell (2026-08-30). Asking the pool
                    // per connection means the tunnel comes back by itself the
                    // moment the reconnect lands.
                    val live = ServiceLocator.sshConnectionPool.peek(serverId)
                    val conn = live?.let {
                        SilentlyTry.logged("SshAi-Tunnel", "open channel to $remoteHost:$remotePort") {
                            it.newDirectConnection(remoteHost, remotePort)
                        }
                    }
                    if (conn == null) {
                        SilentlyTry.fired("SshAi-Tunnel", "close unroutable") { local.close() }
                        continue
                    }
                    bridge(
                        scope, local, conn.inputStream, conn.outputStream, { conn.close() },
                        track = { liveConnections.add(it) },
                        untrack = { liveConnections.remove(it) },
                    )
                }
            }
            android.util.Log.i("SshAi-Tunnel", "forwarding 127.0.0.1:$localPort → $remoteHost:$remotePort")
            return null
        }

        /** Off means OFF: the listener, every open connection, and the coroutines
         *  that were pumping them. Cancelling the accept job alone used to leave
         *  traffic flowing through a tunnel the user had just switched off. */
        fun stop() {
            job?.cancel()
            SilentlyTry.fired("SshAi-Tunnel", "close listener") { server?.close() }
            server = null
            liveConnections.closeAll()
            scope.cancel()
        }
    }

    // ⛔ THERE WAS A SOCKS5 SERVER HERE AND IT IS GONE ON PURPOSE. It was written
    // as the substrate a device-wide VPN would need, then the VPN turned out to
    // need an HTTP proxy instead (Android hands apps a proxy, which is what
    // removed the need for a userspace TCP stack). That left a complete,
    // UNAUTHENTICATED SOCKS5 server shipping in the APK that nothing created —
    // and on Android any installed app can reach 127.0.0.1, so an unauthenticated
    // proxy is not made safe by binding loopback. Dead code with a real risk
    // profile is worse than no code (audit, 2026-08-30). If a SOCKS endpoint is
    // ever needed, it comes back WITH per-session credentials.

    /**
     * An HTTP proxy on the phone whose connections are made BY THE SERVER.
     *
     * ⭐ THIS IS THE PIECE THAT ACTUALLY CHANGES THE PHONE'S ADDRESS, and it is
     * why it exists next to the SOCKS one. Android lets a VPN hand apps a proxy
     * (`VpnService.Builder.setHttpProxy`, API 29+), and everything that honours
     * the system proxy — browsers, most HTTP stacks — then dials through here,
     * which means out of the server. No packet capture, no userspace TCP stack,
     * no native library: the whole reason a device-wide route was out of reach.
     *
     * ⚠ AND ITS LIMIT IS REAL, so the UI must say it: an app that ignores the
     * system proxy is untouched. This covers browsing, not the whole device.
     *
     * Two verbs, which is all a proxy needs:
     *  • `CONNECT host:port` — every HTTPS request. We answer 200 and become a
     *    pipe; the TLS handshake happens end-to-end between the app and the
     *    site, so nothing here can read it.
     *  • an absolute-URI request line (`GET http://host/path`) — plain HTTP.
     *    The request is passed on with the origin form restored, which is what
     *    the far side expects.
     *
     * The hostname is resolved BY THE SERVER in both cases. Resolving here would
     * hand every lookup to the local network and undo the point.
     */
    class HttpProxy(private val serverId: String, val port: Int) {
        // ⚠ RECREATED ON EVERY START. stop() cancels this scope, and a cancelled
        // scope launches nothing — so a second start() would bind the port, log
        // "forwarding …", return success, and never accept a connection. The UI
        // happens to build a new object each time, which hides it; the next
        // caller would not be so lucky (audit, 2026-08-30).
        private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var job: Job? = null
        private var server: ServerSocket? = null
        private val liveConnections = LiveSet()

        fun start(): String? {
            if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ServiceLocator.sshConnectionPool.peek(serverId)
                ?: return "not connected to this server"
            val socket = SilentlyTry.logged("SshAi-Tunnel", "bind http proxy") {
                ServerSocket(port, 200, InetAddress.getByName("127.0.0.1"))
            } ?: return "port $port is already in use on this phone"
            server = socket
            job = scope.launch {
                while (isActive && !socket.isClosed) {
                    val local = SilentlyTry.logged("SshAi-Tunnel", "proxy accept") { socket.accept() } ?: break
                    scope.launch(Dispatchers.IO) { serve(local) }
                }
            }
            android.util.Log.i("SshAi-Tunnel", "http proxy on 127.0.0.1:$port via $serverId")
            return null
        }

        fun stop() {
            job?.cancel()
            SilentlyTry.fired("SshAi-Tunnel", "close proxy listener") { server?.close() }
            server = null
            liveConnections.closeAll()
            scope.cancel()
        }

        private fun serve(local: Socket) {
            val handled = SilentlyTry.logged("SshAi-Tunnel", "proxy request") {
                val inp = java.io.BufferedInputStream(local.getInputStream())
                val out = local.getOutputStream()
                val requestLine = readLine(inp) ?: return@logged null
                val parts = requestLine.split(" ")
                if (parts.size < 3) return@logged null

                // The transport in use NOW, never one captured at start-up: a
                // network change hands the pool a new client.
                val client = ServiceLocator.sshConnectionPool.peek(serverId) ?: run {
                    out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); out.flush()
                    return@logged null
                }

                if (parts[0].equals("CONNECT", ignoreCase = true)) {
                    val hostPort = parts[1]
                    val host = hostPort.substringBeforeLast(':')
                    val p = hostPort.substringAfterLast(':').toIntOrNull() ?: 443
                    while (true) { val l = readLine(inp) ?: break; if (l.isEmpty()) break } // drain headers
                    val conn = try {
                        client.newDirectConnection(host, p)
                    } catch (t: Throwable) {
                        out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); out.flush()
                        return@logged null
                    }
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    out.flush()
                    bridge(
                        scope, local, conn.inputStream, conn.outputStream, { conn.close() },
                        track = { liveConnections.add(it) },
                        untrack = { liveConnections.remove(it) },
                    )
                    return@logged true
                }

                // Plain HTTP: GET http://host[:port]/path HTTP/1.1
                val uri = parts[1]
                if (!uri.startsWith("http://", ignoreCase = true)) return@logged null
                val rest = uri.removePrefix("http://").removePrefix("HTTP://")
                val authority = rest.substringBefore('/')
                val path = "/" + rest.substringAfter('/', "")
                val host = authority.substringBefore(':')
                val p = authority.substringAfter(':', "80").toIntOrNull() ?: 80
                val conn = try {
                    client.newDirectConnection(host, p)
                } catch (t: Throwable) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); out.flush()
                    return@logged null
                }
                // Re-send the request in origin form, then let the rest stream.
                val head = StringBuilder("${'$'}{parts[0]} ${'$'}path ${'$'}{parts[2]}\r\n")
                while (true) {
                    val l = readLine(inp) ?: break
                    head.append(l).append("\r\n")
                    if (l.isEmpty()) break
                }
                conn.outputStream.write(head.toString().toByteArray())
                conn.outputStream.flush()
                bridge(
                    scope, local, conn.inputStream, conn.outputStream, { conn.close() },
                    track = { liveConnections.add(it) },
                    untrack = { liveConnections.remove(it) },
                )
                true
            }
            if (handled == null) SilentlyTry.fired("SshAi-Tunnel", "close proxy client") { local.close() }
        }

        /** One CRLF-terminated line, without allocating a reader that would
         *  buffer past the headers and swallow the body. */
        private fun readLine(inp: java.io.InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = inp.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().removeSuffix("\r")
                sb.append(c.toChar())
                if (sb.length > 8192) return sb.toString()
            }
        }
    }
}

/** Where a forward points, for the UI and for persistence. */
data class TunnelSpec(
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
) {
    /** What the user opens in a browser. */
    fun localUrl(): String = "http://127.0.0.1:$localPort"

    override fun toString(): String = "127.0.0.1:$localPort → $remoteHost:$remotePort"
}

/** Ports worth offering by default: what a dev server, a database admin page or
 *  a preview server usually binds to on the machine the agent works on. */
val COMMON_FORWARDS: List<TunnelSpec> = listOf(
    TunnelSpec(3000, "127.0.0.1", 3000),
    TunnelSpec(5173, "127.0.0.1", 5173),
    TunnelSpec(8000, "127.0.0.1", 8000),
    TunnelSpec(8080, "127.0.0.1", 8080),
)
