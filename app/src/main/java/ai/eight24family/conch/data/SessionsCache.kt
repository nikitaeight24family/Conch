package ai.eight24family.conch.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.RemoteSession
import kotlinx.coroutines.flow.first

/**
 * Persists the last-known list of remote rollouts per (serverId, agent)
 * so SessionsScreen can render instantly on open. The actual SSH listing
 * is then re-run in the background and the UI updates only when the new
 * list differs from the cached one.
 *
 * Format per cache entry (one DataStore string key per server+agent):
 * ```
 *   ts=<unix-millis>
 *   <id>\t<lastActiveAt>\t<path>\t<sizeBytes>\t<model>\t<reasoning>\t<preview>
 *   ...
 * ```
 * size/model/reasoning persist so a cold open renders them INSTANTLY (no
 * "size flashes in seconds late / disappears" — the cache used to drop them,
 * so every reload showed blanks until a fresh SSH listing landed). Legacy
 * 4-column entries (no size/model/reasoning) still parse. Tabs/newlines in any
 * field are stripped on write so the parser stays a trivial split.
 */
class SessionsCache(private val context: Context) {

    private val Context.sessionsDataStore by preferencesDataStore(name = "sessions_cache")

    data class Snapshot(val sessions: List<RemoteSession>, val cachedAt: Long?)

    suspend fun load(serverId: String, agent: Agent): Snapshot {
        val raw = context.sessionsDataStore.data.first()[key(serverId, agent)]
            ?: return Snapshot(emptyList(), null)
        val lines = raw.lineSequence().toList()
        val ts = lines.firstOrNull()?.removePrefix("ts=")?.toLongOrNull()
        val sessions = lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t', limit = 8)
            if (parts.size < 3) return@mapNotNull null
            // 8-col (current): id, mtime, path, size, model, reasoning, preview, title.
            // 7-col / 4-col legacy still parse (title → null).
            val sizeBytes: Long?
            val model: String?
            val reasoning: String?
            val preview: String
            val title: String?
            if (parts.size >= 7) {
                sizeBytes = parts[3].trim().toLongOrNull()
                model = parts[4].takeIf { it.isNotBlank() }
                reasoning = parts[5].takeIf { it.isNotBlank() }
                preview = parts[6]
                title = parts.getOrNull(7)?.takeIf { it.isNotBlank() }
            } else {
                sizeBytes = null; model = null; reasoning = null; title = null
                preview = parts.getOrNull(3).orEmpty()
            }
            RemoteSession(
                id = parts[0],
                lastActiveAt = parts[1].toLongOrNull() ?: 0L,
                path = parts[2],
                preview = preview,
                agent = agent,
                model = model,
                reasoning = reasoning,
                sizeBytes = sizeBytes,
                title = title,
            )
        }
        // Dedupe by session id. Claude keeps ONE logical session (same sessionId)
        // across MULTIPLE rollout files after a resume/compaction, so a listing —
        // and thus a persisted cache blob written before this guard — can carry the
        // same id twice. Both the unified home list and the per-agent list key
        // their LazyColumn by session id, and Compose HARD-CRASHES on a duplicate
        // key ("Key … was already used"). Collapsing here means even an
        // already-corrupted on-disk blob renders safely without waiting for a fresh
        // re-list (the app was crashing on launch, unable to re-list at all).
        // Listings are newest-first, so distinctBy keeps the most-recent file.
        return Snapshot(dedupeById(sessions), ts)
    }

    /** Upsert a SINGLE session into the cache without waiting for a server
     * re-list — used the moment a chat started on the phone learns its resume
     * id, so the new session shows in the list and STAYS. */
    suspend fun upsert(serverId: String, agent: Agent, session: RemoteSession) {
        val cur = load(serverId, agent).sessions
        // Already known → leave it untouched; the server listing owns that row
        // (richer path/title/size, and we don't want to re-order it on a mere
        // re-open). Only a genuinely NEW session gets prepended.
        if (cur.any { it.id == session.id }) return
        save(serverId, agent, listOf(session) + cur)
    }

    suspend fun save(serverId: String, agent: Agent, rawSessions: List<RemoteSession>) {
        // Collapse duplicate session ids up front — one logical Claude session can
        // surface as several rollout files (resume/compaction) so a single listing
        // may repeat an id. Storing the dup would re-crash the id-keyed lists on the
        // next load. Newest-first listing ⇒ keep the first (most recent) file.
        val sessions = dedupeById(rawSessions)
        // Preserve sessions created/active in the last few minutes that THIS
        // listing doesn't include yet. A just-created session lives in the cache
        // via upsert(); a stale in-flight listing that started before it existed
        // must NOT drop it (else it flickers out until the next listing catches
        // it). Once a listing includes it — or it ages out — normal last-snapshot
        // behaviour resumes. Bounded by RECENT_WINDOW_MS so the set can't grow.
        val incoming = sessions.mapTo(HashSet()) { it.id }
        val recent = runCatching {
            load(serverId, agent).sessions.filter { s ->
                if (s.id in incoming) return@filter false
                val act = ai.eight24family.conch.di.ServiceLocator.sessionActivity.lastActivity(serverId, s.id)
                act > 0L && System.currentTimeMillis() - act < RECENT_WINDOW_MS
            }
        }.getOrDefault(emptyList())
        val all = if (recent.isEmpty()) sessions else sessions + recent

        val ts = System.currentTimeMillis()
        val body = buildString {
            append("ts=").append(ts).append('\n')
            fun clean(v: String) = v.replace('\t', ' ').replace('\n', ' ')
            for (s in all) {
                append(s.id).append('\t')
                append(s.lastActiveAt).append('\t')
                append(clean(s.path)).append('\t')
                append(s.sizeBytes ?: "").append('\t')
                append(clean(s.model.orEmpty())).append('\t')
                append(clean(s.reasoning.orEmpty())).append('\t')
                append(clean(s.preview)).append('\t')
                // 8th col: the CLI's own session title (Claude ai-title). Last so
                // legacy 7-col entries still parse (title → null).
                append(clean(s.title.orEmpty()))
                append('\n')
            }
        }
        context.sessionsDataStore.edit { it[key(serverId, agent)] = body }
        // Feed the server file mtimes into the activity store — this is the
        // universal chokepoint EVERY listing path funnels through (the 3
        // SessionsViewModel discovery paths + the global prefetch sweep), so it's
        // the one place that keeps the store's "remote" half fresh. observeRemote
        // only advances (monotonic), so a stale re-list never drags a row backward
        // below a fresher local send. mtime is SECONDS → ×1000.
        for (s in sessions) {
            ai.eight24family.conch.di.ServiceLocator.sessionActivity
                .observeRemote(serverId, s.id, s.lastActiveAt * 1000L)
        }
        // Durable, cumulative owner log — the choke point EVERY listing path
        // funnels through (SessionsViewModel's 3 discovery paths, the global
        // prefetch sweep, …). SessionsCache itself is last-snapshot: this key gets
        // overwritten by the next refresh, dropping any session the server no
        // longer lists, so it CANNOT be the navigation source of truth. The
        // per-session sidecar in HistoryCache is — written here for every session
        // the server EVER returned and never pruned, so a search hit resolves its
        // (server, agent, path, date) even after the server compacted the rollout
        // away. Skip path-less rows (a freshly-upserted, not-yet-listed session
        // has path="") — recording an owner with no path could poison the durable
        // sidecar; the real owner lands when the server listing supplies the path.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ai.eight24family.conch.di.ServiceLocator.historyCache
                .recordOwners(serverId, agent, sessions.filter { it.path.isNotBlank() })
        }
    }

    suspend fun forget(serverId: String, agent: Agent) {
        context.sessionsDataStore.edit { it.remove(key(serverId, agent)) }
    }

    private fun key(serverId: String, agent: Agent) =
        stringPreferencesKey("sessions/$serverId/${agent.name}")

    companion object {
        /** How long a just-created session is preserved in the cache across a
         *  server listing that doesn't yet include it. Generous enough to bridge
         *  the gap until the next SSH listing catches the new rollout file. */
        private const val RECENT_WINDOW_MS = 10 * 60_000L

        /** Collapse rows sharing a session id, keeping the FIRST occurrence. One
         *  logical Claude session spans several rollout files after a resume/
         *  compaction, so a raw listing repeats the id; storing or rendering the
         *  dup hard-crashes the id-keyed LazyColumns ("Key … was already used").
         *  Listings are newest-first, so "keep first" = keep the most recent file.
         *  `internal` so it's unit-testable on the plain JVM (SessionsCache itself
         *  is DataStore-bound and needs Robolectric). */
        internal fun dedupeById(sessions: List<RemoteSession>): List<RemoteSession> =
            sessions.distinctBy { it.id }
    }
}
