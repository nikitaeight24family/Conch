package ai.eight24family.conch.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Is the network the user is paying for by the byte?
 *
 * The app had NO notion of this: `dataSaverEnabled` was a manual toggle,
 * default off, so on cellular the background prefetcher happily downloaded
 * session bodies ("MB per session") and re-listed every 30s. A four-hour taxi
 * ride with the app merely BACKGROUNDED burned an entire monthly plan (user,
 * 2026-07-23) — he never even opened it.
 *
 * A phone knows perfectly well when it is on a metered link, so nobody should
 * have to find a setting first. Treat metered as data-saver automatically; the
 * manual toggle stays as a way to force thrift on unmetered wifi too.
 *
 * Deliberately conservative: if the state can't be read we answer TRUE
 * (assume expensive). Being wrong that way costs a little freshness; being
 * wrong the other way costs the user money.
 */
object NetworkCost {

    /**
     * The whole decision, as a pure function so it can be tested on the JVM.
     *
     * [notMeteredCapability] is what the platform said about the active link:
     * `true` = it granted NET_CAPABILITY_NOT_METERED (free), `false` = it did
     * not (billed), `null` = we could not find out (no active network, no
     * capabilities, service missing).
     *
     * Unknown resolves to METERED on purpose. Guessing "free" wrongly spends
     * the user's money; guessing "billed" wrongly only costs some freshness.
     *
     * ⚠ Verify this by unit test, NEVER by switching the phone onto cellular —
     * he has no data package and every byte is billed.
     */
    fun decideMetered(notMeteredCapability: Boolean?): Boolean =
        notMeteredCapability?.not() ?: true

    fun isMetered(context: Context): Boolean = decideMetered(
        SilentlyTry.loggedOrElse<Boolean?>("SshAi-Net", "read metered state", null) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@loggedOrElse null
            val net = cm.activeNetwork ?: return@loggedOrElse null
            val caps = cm.getNetworkCapabilities(net) ?: return@loggedOrElse null
            // NOT_METERED is granted to links that are genuinely free (most
            // wifi/ethernet). A metered wifi hotspot reports metered too, which
            // is exactly right — tethering off a phone is the same wallet.
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        },
    )

    /** Convenience for callers that already depend on ServiceLocator. */
    fun isMetered(): Boolean =
        isMetered(ai.eight24family.conch.di.ServiceLocator.appContext)

    // ── Online / offline ──────────────────────────────────────────────────
    // A prompt typed with no connectivity must not be lost or silently fail:
    // the app says there's no internet, parks the message in the existing
    // outbox, and sends it the moment the link is back (user, 2026-07-27).

    private val _online = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** True while the device has a validated internet-capable network. */
    val online: kotlinx.coroutines.flow.StateFlow<Boolean> = _online

    /** Snapshot for callers that can't collect a flow. */
    fun isOnline(): Boolean = _online.value

    /**
     * Start listening for connectivity changes. Idempotent-safe to call once
     * from Application.onCreate.
     *
     * NET_CAPABILITY_VALIDATED (not merely "a network exists") is the honest
     * signal — a captive-portal wifi that can't reach anything must count as
     * offline, otherwise we'd hand the send path a link that silently fails.
     */
    fun install(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        fun validated(net: android.net.Network?): Boolean {
            val caps = net?.let { cm.getNetworkCapabilities(it) } ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        _online.value = validated(cm.activeNetwork)
        SilentlyTry.fired("SshAi-Net", "register connectivity callback") {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    _online.value = validated(network)
                }
                override fun onLost(network: android.net.Network) {
                    _online.value = false
                }
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: NetworkCapabilities,
                ) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            })
        }
    }
}
