package ai.eight24family.conch.ssh

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
 * One shape, one primitive — [LocalForward] opens a `direct-tcpip` channel on
 * the pooled client (the same connection the chat uses, no second
 * authentication, no second touch for a security key): one port on the phone
 * stands for one port on the server, `http://127.0.0.1:3000` in any browser
 * reaches the server's own :3000. (An HttpProxy sibling existed for the VPN
 * feature; both are gone — see the tombstones at the bottom.)
 */
object SshTunnel {

    /**
     * ⛔ A TUNNEL MAY NEVER KILL THE APP.
     *
     * Every coroutine here works on sockets and channels that die on their own
     * schedule — the phone switches network, the server drops the transport,
     * the other pump wins the race and closes the socket a microsecond before
     * this one asks it for a stream. `SupervisorJob` does NOT make that safe: it
     * stops a sibling from being cancelled, but an exception with no handler
     * still reaches the thread's default handler, and on Android that is a
     * process kill. It killed one: `java.net.SocketException: Socket is closed`
     * out of `bridge`, FATAL EXCEPTION, the whole app gone with every SSH
     * transport and every upload in flight (device log, 2026-08-30 09:47).
     *
     * So the scope carries a handler and the pumps carry their own catch. A
     * connection dying is the ordinary end of a connection — it gets a log line,
     * never a crash.
     */
    private fun tunnelScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            android.util.Log.w(
                "Conch-Tunnel",
                "tunnel coroutine ended on ${t.javaClass.simpleName}: ${t.message}",
            )
        },
    )

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
                SilentlyTry.fired("Conch-Tunnel", "close bridged sockets") { local.close() }
                SilentlyTry.fired("Conch-Tunnel", "close channel") { closer() }
                untrack(handle)
            }
        }
        handle = java.io.Closeable { closeBoth() }
        track(handle)
        // ⛔ THE CATCH IS THE POINT, NOT THE `finally`.
        //
        // [pump] swallows its own IO, so this looked covered — but the socket
        // accessors are evaluated OUTSIDE it, as the call's arguments. Whichever
        // pump finishes first calls `closeBoth`, and the other one then asks a
        // closed socket for its stream: `SocketException: Socket is closed`,
        // thrown before pump is even entered, straight out of a `launch` with
        // nothing to catch it. That is a FATAL EXCEPTION on Android — the app
        // dies, taking the SSH transports and any upload mid-flight with it
        // (device log, 2026-08-30 09:47). The race is normal operation; only the
        // crash was a bug.
        scope.launch(Dispatchers.IO) { pumpQuietly(closeBoth) { pump(local.getInputStream(), remoteOut) } }
        scope.launch(Dispatchers.IO) { pumpQuietly(closeBoth) { pump(remoteIn, local.getOutputStream()) } }
    }

    /** Run one direction of a bridge: any end-of-connection throw is a log line,
     *  and both directions close no matter how this ended. Cancellation is not an
     *  error — it is `stop()` doing its job, so it propagates (after the close,
     *  which the `finally` guarantees either way). */
    private inline fun pumpQuietly(closeBoth: () -> Unit, body: () -> Unit) {
        try {
            body()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.d(
                "Conch-Tunnel",
                "bridge direction ended: ${t.javaClass.simpleName}: ${t.message}",
            )
        } finally {
            closeBoth()
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
            copy.forEach { SilentlyTry.fired("Conch-Tunnel", "close tracked") { it.close() } }
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
        private var scope = tunnelScope()
        private var job: Job? = null
        private var server: ServerSocket? = null
        private val liveConnections = LiveSet()

        /** @return null on success, else why it could not start. */
        fun start(): String? {
            if (!scope.isActive) scope = tunnelScope()
            // Pre-flight only. The client used for actual traffic is looked up
            // per connection — see the accept loop.
            ServiceLocator.sshConnectionPool.peek(serverId)
                ?: return "not connected to this server"
            val socket = SilentlyTry.logged("Conch-Tunnel", "bind local port") {
                ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
            } ?: return "port $localPort is already in use on this phone"
            server = socket
            job = scope.launch {
                while (isActive && !socket.isClosed) {
                    val local = SilentlyTry.logged("Conch-Tunnel", "accept") { socket.accept() } ?: break
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
                        SilentlyTry.logged("Conch-Tunnel", "open channel to $remoteHost:$remotePort") {
                            it.newDirectConnection(remoteHost, remotePort)
                        }
                    }
                    if (conn == null) {
                        SilentlyTry.fired("Conch-Tunnel", "close unroutable") { local.close() }
                        continue
                    }
                    bridge(
                        scope, local, conn.inputStream, conn.outputStream, { conn.close() },
                        track = { liveConnections.add(it) },
                        untrack = { liveConnections.remove(it) },
                    )
                }
            }
            android.util.Log.i("Conch-Tunnel", "forwarding 127.0.0.1:$localPort → $remoteHost:$remotePort")
            return null
        }

        /** Off means OFF: the listener, every open connection, and the coroutines
         *  that were pumping them. Cancelling the accept job alone used to leave
         *  traffic flowing through a tunnel the user had just switched off. */
        fun stop() {
            job?.cancel()
            SilentlyTry.fired("Conch-Tunnel", "close listener") { server?.close() }
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

    // ⛔ AND THE HTTP PROXY THAT STOOD HERE IS GONE TOO (2026-09-01). It was
    // the VPN feature's carrier: VpnService.Builder.setHttpProxy handed apps
    // 127.0.0.1:8118 and every connection was dialled from the server. Play's
    // enforcement removed the VPN feature, and the proxy follows the same law
    // as the SOCKS server above: an unauthenticated loopback proxy that
    // nothing constructs is dead code with a real risk profile — on Android
    // any installed app can reach 127.0.0.1. If device routing ever returns,
    // it returns WITH per-session credentials and its own Play declaration
    // story, not by resurrecting this class from git.
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
