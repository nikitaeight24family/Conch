package ai.eight24family.conch.agent

/**
 * Process-scoped record of how many messages the user has SEEN in each chat
 * session, so the unified Sessions home can badge "N new" when an agent produced
 * messages while the user was looking elsewhere.
 *
 * Keyed by session id (the CLI resume id, == [RemoteSession.id]). Updated while a
 * chat is on-screen (its history collector is alive); when the user leaves, the
 * baseline freezes and any further growth in the still-running [AgentSession]
 * shows up as unread. NOT persisted — unread resets on process restart, which is
 * fine: a count of "new since you were last here" only makes sense within a run.
 */
object SessionSeenTracker {
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Record that the user has currently seen [count] messages in [sessionId]
     *  (called from the open chat's history collector). */
    fun markSeen(sessionId: String, count: Int) {
        seen[sessionId] = count
    }

    /** New-message count for the home badge: how many messages beyond the last
     *  baseline the user saw. Returns 0 when there's no baseline yet (the session
     *  hasn't been opened this run) — we only badge growth AFTER a first view, so
     *  old sessions don't all light up. */
    fun unread(sessionId: String, currentCount: Int): Int {
        val base = seen[sessionId] ?: return 0
        return (currentCount - base).coerceAtLeast(0)
    }
}
