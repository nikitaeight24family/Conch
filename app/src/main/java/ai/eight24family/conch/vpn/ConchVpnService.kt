package ai.eight24family.conch.vpn

import ai.eight24family.conch.ssh.SshTunnel
import ai.eight24family.conch.util.SilentlyTry
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sending this phone's traffic out through the user's own server.
 *
 * ⭐ HOW THIS AVOIDS WRITING A TCP/IP STACK, which is the only reason it exists
 * at all. The obvious shape of a VPN — capture the packets, reassemble the
 * streams, forward them — means a userspace TCP implementation (what tun2socks
 * is), and that is either a native library or weeks of work with subtle bugs.
 * Android offers a second door: a VPN may hand every app a PROXY
 * ([VpnService.Builder.setHttpProxy], API 29+). Everything that honours the
 * system proxy then dials through us, which means out of the server — and no
 * packet is ever parsed.
 *
 * ⚠ SO SAY WHAT IT DOES NOT COVER, EVERY TIME. An app that ignores the system
 * proxy is untouched by this. It covers browsing and ordinary HTTP clients, not
 * the whole device — and a "VPN" that quietly leaves some traffic outside would
 * be the worst possible thing to be vague about.
 *
 * The tunnel interface itself is deliberately inert: one address, no default
 * route. Nothing is captured, so nothing can be black-holed by us not reading
 * it. The interface exists only because Android attaches the proxy to a VPN
 * session, not to an app.
 */
class ConchVpnService : VpnService() {

    companion object {
        const val ACTION_START = "ai.eight24family.conch.vpn.START"
        const val ACTION_STOP = "ai.eight24family.conch.vpn.STOP"
        const val EXTRA_SERVER_ID = "serverId"

        /** Port the phone-side proxy listens on. Loopback only. */
        const val PROXY_PORT = 8118

        private val _routedServerId = MutableStateFlow<String?>(null)

        /** Which server traffic is currently routed through, or null. Read by
         *  the UI so the switch reflects the SERVICE, never a local guess. */
        val routedServerId: StateFlow<String?> = _routedServerId.asStateFlow()

        /** Whether this Android version can hand apps a proxy at all. Below 29
         *  the door does not exist, and the honest answer is to say so rather
         *  than start something that routes nothing. */
        val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var proxy: SshTunnel.HttpProxy? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                if (serverId == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val err = bringUp(serverId)
                if (err != null) {
                    android.util.Log.w("SshAi-Vpn", "could not start: $err")
                    teardown()
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun bringUp(serverId: String): String? {
        if (!supported) return "this Android version cannot hand apps a proxy"
        teardown()

        val p = SshTunnel.HttpProxy(serverId, PROXY_PORT)
        val perr = p.start()
        if (perr != null) return perr
        proxy = p

        val fd = SilentlyTry.logged("SshAi-Vpn", "establish tunnel interface") {
            Builder()
                .setSession("Conch")
                // A single address on a link-local range, and NO default route:
                // we are not capturing traffic, only carrying the proxy setting.
                .addAddress("10.111.222.1", 32)
                .setMtu(1500)
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", PROXY_PORT))
                .also { b ->
                    // Never route Conch's own traffic through Conch. The SSH
                    // connection this proxy rides on must not depend on itself.
                    SilentlyTry.fired("SshAi-Vpn", "exclude self") {
                        b.addDisallowedApplication(packageName)
                    }
                }
                .establish()
        } ?: return "Android refused the tunnel interface"
        tunnel = fd
        _routedServerId.value = serverId
        android.util.Log.i("SshAi-Vpn", "routing app traffic through $serverId via 127.0.0.1:$PROXY_PORT")
        return null
    }

    private fun teardown() {
        SilentlyTry.fired("SshAi-Vpn", "close tunnel interface") { tunnel?.close() }
        tunnel = null
        proxy?.stop()
        proxy = null
        _routedServerId.value = null
    }

    override fun onRevoke() {
        // The user turned it off in Android's own settings, or another VPN took
        // over. Treat it exactly like a stop: the flow must never claim we are
        // routing when the system has taken the interface away.
        teardown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

}
