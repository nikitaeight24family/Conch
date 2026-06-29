package ai.eight24family.conch.diagnostics

/**
 * Tri-state for the 📱 glyph.
 *
 *  • NONE — never wired to a phone → no glyph at all.
 *  • IDLE — wired before (the tag lives in `phoneBridgeSessions`, which persists
 *           and is never auto-removed) but the bridge isn't live right now →
 *           DIM glyph.
 *  • LIVE — wired AND the channel is polling ([BridgeHealth.isAlive]) AND Shizuku
 *           is granted right now ([ShizukuShell.available]) → COLORED glyph. Same
 *           two honest layers as PHONE-GLYPH-SHIZUKU-2.
 */
enum class BridgePresence { NONE, IDLE, LIVE }

/**
 * The tri-state state machine, given the persisted wiring flag and an already-
 * computed "is the bridge live right now" boolean. The ONE place that maps the
 * two inputs to [BridgePresence], so every surface agrees. Callers that hold a
 * server-global live flag (e.g. the per-server list's `phoneBridgeLive`) use
 * this directly; [bridgePresenceOf] is the convenience that computes `isLive`
 * from [BridgeHealth] + [ShizukuShell].
 */
fun bridgePresenceFromLiveState(wired: Boolean, isLive: Boolean): BridgePresence = when {
    !wired -> BridgePresence.NONE
    isLive -> BridgePresence.LIVE
    else -> BridgePresence.IDLE
}

/**
 * Compute [BridgePresence] from the persisted wiring flag plus the two live
 * layers (channel heartbeat + Shizuku). Pass [shizukuOk] when you've already
 * sampled [ShizukuShell.available] once (e.g. per list-reload) to avoid a binder
 * ping per row; omit it to sample here.
 */
fun bridgePresenceOf(
    wired: Boolean,
    serverId: String,
    shizukuOk: Boolean = ShizukuShell.available(),
): BridgePresence = bridgePresenceFromLiveState(
    wired, BridgeHealth.isAlive(serverId) && shizukuOk,
)
