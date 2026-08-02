package ai.eight24family.conch.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Per-session prompt queue + recent-send dedupe set, extracted from
 * `AgentSession.kt` so the send/drain plumbing lives in one place.
 *
 * Mid-turn send is what users expect from terminal Claude Code: type
 * a follow-up while the agent's still working, hit enter, the agent
 * picks it up after finishing the current turn. We get that behaviour
 * by NOT cancelling the in-flight job — instead, queue the new
 * prompt and let `runQueue` drain the queue serially. The chat
 * shows the new UserText right away (so the user knows it's
 * registered); the actual `claude --print` invocation runs when
 * the previous one returns.
 */
internal class AgentSessionPromptQueue(
    private val scope: CoroutineScope,
    private val runOneShot: suspend (String, List<String>) -> Unit,
    /** Called RIGHT BEFORE the drainer launches a prompt's turn (only when
     * This is how chat ordering stays correct when the user sends a second
     * prompt while the agent is still answering the first: the second
     * UserText is added to the chat list at the moment its turn starts (i.e.
     * AFTER the first reply has fully landed), not at send time. Without this
     * defer, the list ordered [user1, user2, reply1, reply2] instead of
     * [user1, reply1, user2, reply2] — user:. */
    private val emitOnTurnStart: (text: String) -> Unit = {},
) {
    /** A queued prompt + whether its UserText still needs to be added to the
     *  chat at turn-start. `true` for plain [enqueue] sends; `false` for a
     *  redeliver where the row is already visible from a prior run. */
    private data class QueuedPrompt(
        val text: String,
        val imagePaths: List<String>,
        val emitOnStart: Boolean,
    )

    private val pendingPrompts: ArrayDeque<QueuedPrompt> = ArrayDeque()
    private val queueLock = Any()

    /** The coroutine currently draining the queue. Held so the caller
     *  can poll/cancel it (e.g. `close()` cancels everything when the
     *  session is shut down). Public-ish — exposed via [drainerJob]. */
    @Volatile private var currentMessageJob: Job? = null
    val drainerJob: Job? get() = currentMessageJob

    /**
     * Texts the user just sent locally — keyed by exact prompt content to
     * its emit timestamp. The JSONL tail will replay the same text back to
     * us as a `user` event a few seconds later (Claude writes every prompt
     * into the session log). We dedupe by checking this set: if an incoming
     * UserText's body matches something we sent in the last 60 s, drop it
     * — the user's message is already on screen from the immediate
     * `emitMsg` in [enqueue].
     */
    private val recentSends = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun wasRecentlySent(text: String, resumeId: String? = null): Boolean {
        val now = System.currentTimeMillis()
        // Garbage-collect entries older than 60 s on every check so the
        // map doesn't grow forever in long sessions.
        recentSends.entries.removeAll { now - it.value > 60_000 }
        val key = text.trim()
        if (recentSends.containsKey(key)) return true
        // Survive a reconnect: retry() builds a FRESH AgentSession (with an empty
        // local recentSends), yet the JSONL echo of a just-sent prompt still
        // arrives afterwards — without this it would show up as a SECOND copy of
        // the user's message. The process-scoped store keyed by the stable
        // resumeId keeps the dedupe alive across session instances.
        if (resumeId != null) {
            globalRecent[resumeId]?.let { g ->
                g.entries.removeAll { now - it.value > 60_000 }
                if (g.isEmpty()) globalRecent.remove(resumeId)
                else if (g.containsKey(key)) return true
            }
        }
        return false
    }

    /** Mark [text] as just-sent. Future incoming `user` events with the
     *  same body within ~60 s are treated as JSONL replay and dropped — both in
     *  this session and (keyed by [resumeId]) across a reconnect. */
    fun markSent(text: String, resumeId: String? = null) {
        val key = text.trim()
        val now = System.currentTimeMillis()
        recentSends[key] = now
        if (resumeId != null) {
            globalRecent.getOrPut(resumeId) { java.util.concurrent.ConcurrentHashMap() }[key] = now
        }
    }

    companion object {
        /** resumeId → (trimmed prompt → epoch ms). PROCESS-scoped so the
         *  recent-send dedupe outlives a single [AgentSessionPromptQueue]
         *  instance across a reconnect (retry() rebuilds the session). Pruned by
         *  the 60 s GC in [wasRecentlySent]; empty inner maps are dropped. */
        private val globalRecent =
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Long>>()
    }

    /**
     * Add [text] to the queue. Starts the drainer coroutine if it
     * isn't already running. Returns the Job draining the queue (the
     * caller stores this so `close()` / `cancelCurrent()` can reach
     * it). Returns `null` and reuses the existing drainer if one's
     * already in flight.
     */
    fun enqueue(text: String, imagePaths: List<String> = emptyList(), emitOnStart: Boolean = true): Job? {
        val shouldStartDrainer: Boolean
        synchronized(queueLock) {
            pendingPrompts.addLast(QueuedPrompt(text, imagePaths, emitOnStart))
            shouldStartDrainer = currentMessageJob?.isActive != true
        }
        if (shouldStartDrainer) {
            val j = scope.launch { drainPromptQueue() }
            currentMessageJob = j
            return j
        }
        return null
    }

    /**
     * Stop = "cancel current turn AND drop everything queued behind
     * it". Cancelling just the in-flight turn while letting the
     * drainer roll to the next queued prompt would feel weird —
     * the user tapped Stop because they wanted everything to halt.
     */
    fun clearQueue() {
        synchronized(queueLock) { pendingPrompts.clear() }
    }

    /** Number of prompts still waiting (excludes the in-flight one).
     *  Used only for logcat breadcrumbs. */
    fun pendingCount(): Int = synchronized(queueLock) { pendingPrompts.size }

    /** Cancel the drainer coroutine (called from `close()`). */
    fun cancelDrainer() {
        currentMessageJob?.cancel()
    }

    /**
     * Sequentially executes `runOneShot` for every queued prompt. Loops
     * until the queue is empty, picking up anything added mid-turn
     * (which is the entire point — the user can throw in a "actually
     * also do X" while the agent is still on the previous turn, and
     * the agent will see it as soon as it's free).
     */
    private suspend fun drainPromptQueue() {
        while (true) {
            val next = synchronized(queueLock) {
                if (pendingPrompts.isEmpty()) null else pendingPrompts.removeFirst()
            } ?: return
            android.util.Log.d("SshAi-Turn", "queue: running next prompt (${pendingPrompts.size} more queued)")
            // Emit the UserText NOW (right before the turn starts) — not at
            // send() time — so the chat ordering is correct when a second
            // prompt was queued mid-turn. Redeliver bypasses this (its row is
            // already on screen).
            if (next.emitOnStart) emitOnTurnStart(next.text)
            runOneShot(next.text, next.imagePaths)
        }
    }
}
