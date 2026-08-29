package ai.eight24family.conch.diagnostics

/**
 * Tri-state for the 📱 glyph.
 *
 *  • NONE — never wired to a phone → no glyph at all.
 *  • IDLE — wired before (the tag lives in `phoneBridgeSessions`, which persists
 *           and is never auto-removed) but the bridge isn't live right now →
 *           DIM glyph.
 *  • LIVE — wired AND the channel is polling ([BridgeHealth.isAlive]) AND a
 *           shell connection is open right now → COLORED glyph. Two honest
 *           layers, neither of which implies the other.
 */
enum class BridgePresence { NONE, IDLE, LIVE }

/**
 * The tri-state state machine, given the persisted wiring flag and an already-
 * computed "is the bridge live right now" boolean. The ONE place that maps the
 * two inputs to [BridgePresence], so every surface agrees. Callers that hold a
 * server-global live flag (e.g. the per-server list's `phoneBridgeLive`) use
 * this directly; [bridgePresenceOf] is the convenience that computes `isLive`
 * from [BridgeHealth] plus whether a shell connection is actually open.
 */
fun bridgePresenceFromLiveState(wired: Boolean, isLive: Boolean): BridgePresence = when {
    !wired -> BridgePresence.NONE
    isLive -> BridgePresence.LIVE
    else -> BridgePresence.IDLE
}

/**
 * Compute [BridgePresence] from the persisted wiring flag plus the two live
 * layers: the channel heartbeat, and whether privileged commands can actually
 * run right now.
 *
 * [shellOk] is a parameter so a caller rendering a whole list samples it ONCE
 * rather than per row — the answer cannot change between two rows of the same
 * frame, and asking repeatedly is pure cost.
 */
fun bridgePresenceOf(
    wired: Boolean,
    serverId: String,
    shellOk: Boolean = ai.eight24family.conch.adb.LocalAdbShell.hasLiveSession(),
): BridgePresence = bridgePresenceFromLiveState(
    wired, BridgeHealth.isAlive(serverId) && shellOk,
)
