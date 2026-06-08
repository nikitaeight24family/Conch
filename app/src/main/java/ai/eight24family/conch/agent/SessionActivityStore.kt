package ai.eight24family.conch.agent

import ai.eight24family.conch.data.prefs.AppPreferences
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * THE single source of truth for "when was each session last active" — the one
 * value that orders the sessions list and stamps each row's time.
 *
 * ## Why this exists (the bug it kills)
 *
 * Before this, three half-sources fought each other and got `max()`'d at read
 * time in every ViewModel:
 *
 * 1. `RemoteSession.lastActiveAt` — the server file mtime (`stat %Y`), but only
 * re-read on a background prefetch sweep, so it lagged reality by minutes-to- days
 * and didn't move at all on SK servers with no live connection. 2. An in-memory
 * "touched" bump on send — accurate, but **lost on every app restart** (it was a
 * plain `ConcurrentHashMap`, never persisted). After a restart the list fell back
 * to the stale mtime → the chat the user wrote in today showed and sank below older
 * rows. 3.
 *
 * Three sources, `max()`'d at read time, is exactly the kludge that produced the
 * rage.
 *
 * ## The design
 *
 * One persisted, monotonic store. Every activity signal — local (the user sent a
 * turn / the agent's reply just landed) and remote (a listing sweep re-stat'd the
 * rollout file) — funnels through [advance], which only ever moves a session's
 * timestamp **forward**. Readers ask [lastActivity]; there is nothing to reconcile.
 *
 *   - **Monotonic** — append-only rollouts mean mtime only grows, and local
 *     observations are wall-clock `now()`. Taking the max at WRITE time (not read)
 *     means one number per session, no ordering ambiguity, no backward jumps from
 *     a stale sweep landing after a fresh local send.
 *   - **Persisted** — the map is loaded from [AppPreferences] on init and flushed
 *     back (debounced) on change, so the just-active chat keeps its real time
 *     across a process death. This is the actual fix for the restart bug.
 *   - **Reactive** — [changes] ticks on every advance so lists reload/re-sort the
 *     instant something moves, without polling.
 *
 * Keying: `"serverId|sessionId"`, where `sessionId` is the CLI resume id — the
 * SAME value the listing emits as [RemoteSession.id] (Claude: filename uuid;
 * Codex: `thread_id`; Gemini: filename uuid) and the SAME value [AgentSession]
 * reports as its `resumeId`. Verified consistent end-to-end, so a local bump and
 * a remote re-stat land on one key.
 */
class SessionActivityStore(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** key "serverId|sessionId" → best-known last-activity epoch MILLIS. */
    private val map = ConcurrentHashMap<String, Long>()

    /** Monotonic tick; increments on every real advance. Lists collect this to
     *  reload/re-sort. Value itself is meaningless — only the change matters. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Coalesces a burst of advances (e.g. a full listing sweep firing
     *  observeRemote for every session) into a single debounced DataStore write. */
    private val flushSignal = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        // Load the persisted snapshot BEFORE the first read can matter. merge with
        // max so any observe() that races ahead of the load isn't clobbered.
        scope.launch {
            val persisted = SilentlyTry.loggedOrElse(TAG, "load persisted activity", emptyMap<String, Long>()) {
                prefs.sessionTouchedAt.first()
            }
            persisted.forEach { (k, v) -> map.merge(k, v) { a, b -> maxOf(a, b) } }
            if (persisted.isNotEmpty()) _changes.value += 1
        }
        // Debounced persist writer: after the last advance in a burst settles,
        // flush the whole map once.
        scope.launch {
            flushSignal.collect {
                delay(FLUSH_DEBOUNCE_MS)
                SilentlyTry.fired(TAG, "persist activity snapshot") {
                    prefs.replaceSessionTouchedAt(HashMap(map))
                }
            }
        }
    }

    /**
     * Authoritative local observation: the user sent a turn, the agent's reply
     * just finished, or a brand-new session was just assigned its id. Defaults to
     * `now()` — that IS the activity time, to the second.
     */
    fun observeLocal(serverId: String, sessionId: String, epochMs: Long = System.currentTimeMillis()) =
        advance(serverId, sessionId, epochMs)

    /**
     * Remote observation: a listing sweep read this session's file mtime. Catches
     * activity we DIDN'T originate (the CLI run directly on the server, or from
     * another device) and seeds the store on first run so untouched sessions still
     * sort by a real time. Pass MILLIS (mtime is seconds — caller multiplies).
     */
    fun observeRemote(serverId: String, sessionId: String, epochMs: Long) =
        advance(serverId, sessionId, epochMs)

    /** Best-known last-activity for a session, epoch MILLIS, or 0 if never seen. */
    fun lastActivity(serverId: String, sessionId: String): Long =
        map[key(serverId, sessionId)] ?: 0L

    private fun advance(serverId: String, sessionId: String, epochMs: Long) {
        if (epochMs <= 0L || serverId.isBlank() || sessionId.isBlank()) return
        val k = key(serverId, sessionId)
        // compute() so the read-test-write is atomic per key under concurrent
        // observeLocal/observeRemote — a lost update here would silently drop a bump.
        val changed = booleanArrayOf(false)
        map.compute(k) { _, prev ->
            if (prev == null || epochMs > prev) { changed[0] = true; epochMs } else prev
        }
        if (changed[0]) {
            _changes.value += 1
            flushSignal.tryEmit(Unit)
        }
    }

    private fun key(serverId: String, sessionId: String) = "$serverId|$sessionId"

    companion object {
        private const val TAG = "SshAi-Activity"
        private const val FLUSH_DEBOUNCE_MS = 750L
    }
}
