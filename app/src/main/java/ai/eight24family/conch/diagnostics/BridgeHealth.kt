package ai.eight24family.conch.diagnostics

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide liveness heartbeat for the phone↔server bridge, per `serverId`.
 *
 * The phone-side [AgentBridge] poller stamps [markAlive] on every SUCCESSFUL
 * inbox poll — which proves, right then, that (a) SSH to the server is up and
 * (b) the poller is actually running, i.e. the phone is reachable for bridge
 * requests. When the poller stops (app backgrounded → polling pauses, SSH drops,
 * or the bridge is torn down), no fresh stamp lands and [isAlive] ages out.
 *
 * This is the HONEST signal for the 📱 glyph. The old glyph lit on "wired flag +
 * SSH transit", which over-claimed: the user asked the agent and `conch-bridge
 * shell` TIMED OUT (the phone wasn't polling) yet the glyph still showed
 * (2026-06-28). Gating the glyph on a recent successful poll makes it go dark
 * exactly when the bridge can't actually be reached.
 */
object BridgeHealth {

    /** ~3× the 2s poll cadence (and tolerant of the 10s data-saver cadence's
     *  first tick) so the glyph doesn't flicker between polls, but still goes
     *  dark within a handful of seconds once the poller genuinely stops. */
    const val ALIVE_WINDOW_MS: Long = 25_000L

    private val lastAliveMs = ConcurrentHashMap<String, Long>()

    /** Stamp a successful bridge poll for [serverId]. Called from [AgentBridge.tick]. */
    fun markAlive(serverId: String) {
        lastAliveMs[serverId] = System.currentTimeMillis()
    }

    /** True iff a successful poll landed within [withinMs] — i.e. the phone is
     *  actively reachable for this server right now. */
    fun isAlive(serverId: String, withinMs: Long = ALIVE_WINDOW_MS): Boolean {
        val t = lastAliveMs[serverId] ?: return false
        return System.currentTimeMillis() - t <= withinMs
    }

    /** Bridge torn down / poller stopped → forget the heartbeat so the glyph
     *  drops immediately instead of coasting on a stale stamp. */
    fun clear(serverId: String) {
        lastAliveMs.remove(serverId)
    }
}
