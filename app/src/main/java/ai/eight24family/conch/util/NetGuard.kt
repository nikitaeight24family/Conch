package ai.eight24family.conch.util

import android.content.Context
import android.net.ConnectivityManager

/**
 * One question, answered the platform's way: is the active network METERED
 * (mobile data, metered hotspots)? Gigabyte downloads — model weights, vision
 * packs — ask the user before spending a metered plan and start silently only
 * on Wi-Fi.
 */
object NetGuard {
    fun isMetered(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .isActiveNetworkMetered
    }.getOrDefault(false)
}
