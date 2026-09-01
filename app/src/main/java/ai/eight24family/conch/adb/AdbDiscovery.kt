package ai.eight24family.conch.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finding the port `adbd` is listening on — on the phone we are running on.
 *
 * The port is not fixed and not guessable: Android picks a fresh one every time
 * Wireless Debugging is armed, and it changes again on the next reboot. It is
 * published two ways, and an app can only reach one of them:
 *
 *  - the system property `service.adb.tls.port` — readable with `getprop`,
 *    which needs the very shell access this is trying to obtain, and through
 *    `SystemProperties` only via APIs an app is not allowed to touch;
 *  - **mDNS**, where `adbd` advertises `_adb-tls-connect._tcp` (and, while a
 *    pairing dialog is open, `_adb-tls-pairing._tcp`). That is a public API.
 *
 * So mDNS it is. The device discovers its OWN advertisement — the packets go out
 * and come back on the same interface — which is why this works with nothing but
 * the phone.
 *
 * ⚠ It needs a network interface to multicast on, which is the same Wi-Fi
 * requirement that gates Wireless Debugging itself. Nothing is lost by that: if
 * the toggle is off there is no port to find anyway.
 */
object AdbDiscovery {

    /** What `adbd` advertises for an ordinary connection. */
    const val SERVICE_CONNECT = "_adb-tls-connect._tcp"

    /** What it advertises only while the pairing dialog is on screen. */
    const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"

    data class Endpoint(val host: String, val port: Int, val name: String)

    /**
     * Wait for the first advertisement of [serviceType], or give up.
     *
     * Returns the first one found rather than a list: a phone advertises exactly
     * one of each, and waiting for a second would only ever add delay.
     */
    suspend fun find(
        context: Context,
        serviceType: String = SERVICE_CONNECT,
        timeoutMs: Long = 8_000,
    ): Endpoint? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val found = CompletableDeferred<Endpoint>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                found.completeExceptionally(IllegalStateException("mDNS discovery refused to start ($code)"))
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // The discovery callback carries a NAME, not an address — the
                // port only exists after a resolve, and skipping that step is
                // how this reads back as port 0.
                SilentlyTry.fired("Conch-AdbDiscovery", "resolve mDNS service") {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(
                        info,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo, code: Int) = Unit
                            override fun onServiceResolved(i: NsdServiceInfo) {
                                val host = i.host?.hostAddress ?: return
                                if (i.port > 0 && !found.isCompleted) {
                                    found.complete(Endpoint(host, i.port, i.serviceName ?: ""))
                                }
                            }
                        },
                    )
                }
            }
        }

        return try {
            @Suppress("DEPRECATION")
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) { found.await() }
        } catch (t: Throwable) {
            android.util.Log.w("Conch-AdbDiscovery", "discovery of $serviceType failed: ${t.message}")
            null
        } finally {
            SilentlyTry.fired("Conch-AdbDiscovery", "stop mDNS discovery") {
                @Suppress("DEPRECATION")
                nsd.stopServiceDiscovery(listener)
            }
        }
    }

    /**
     * Prefer the loopback address whatever mDNS reported.
     *
     * The advertisement carries the phone's address on its Wi-Fi network,
     * because that is who it is talking to. We are on the same device, so the
     * connection should never leave it: going out to the LAN address and back
     * would work on a permissive network and fail on one with client isolation,
     * and would put an ADB handshake on the air for no reason at all.
     */
    fun onLoopback(endpoint: Endpoint): Endpoint = endpoint.copy(host = "127.0.0.1")
}
