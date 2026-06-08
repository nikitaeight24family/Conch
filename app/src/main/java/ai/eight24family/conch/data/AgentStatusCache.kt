package ai.eight24family.conch.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentStatus
import ai.eight24family.conch.agent.isVersionLessThan
import kotlinx.coroutines.flow.first

/**
 * Persists per-server agent install/auth probe results so that re-entering
 * AgentPickerScreen doesn't fire an SSH probe on every visit. The user has
 * to actually `npm uninstall` an agent or `claude logout` for the result
 * to change — a 1.5 s probe each time we open the screen is wasteful.
 *
 * Cache invalidation: only via explicit pull-to-refresh on the screen, or
 * a successful manual refresh. There's no TTL — staleness is rare and the
 * picker UI shows "last checked X ago" so the user can decide.
 */
// TOP-LEVEL so this DataStore is a process-wide SINGLETON. As a class member
// the delegate was created once PER AgentStatusCache instance — and the moment
// a second instance touched it (ServersViewModel's badge poll + the agent
// picker each holding one) DataStore threw "multiple DataStores active for the
// same file" and crashed the app on open. One delegate per Context = safe for
// any number of AgentStatusCache instances.
private val Context.statusDataStore by preferencesDataStore(name = "agent_status_cache")

class AgentStatusCache(private val context: Context) {

    data class Snapshot(val statuses: Map<Agent, AgentStatus>, val lastCheckedAt: Long?)

    suspend fun load(serverId: String): Snapshot {
        val prefs = context.statusDataStore.data.first()
        val map = mutableMapOf<Agent, AgentStatus>()
        var newestTs: Long? = null
        for (agent in Agent.entries) {
            // Registry-global latest version (server-independent), learned from
            // whichever server's probe could reach npm/installer. Used as a
            // fallback when THIS server's own probe couldn't fetch it (npm not on
            // PATH / offline / registry hiccup) — otherwise an offline/cached
            // server with a behind CLI shows a misleading "log in" instead of
            // "update".
            val globalLatest = prefs[globalLatestKey(agent)]?.takeIf { it.isNotBlank() }
            val raw = prefs[key(serverId, agent)] ?: continue
            // Format: installed|loggedIn|ts[|methodsCsv|active|instVer|latestVer|liveAuthPending]
            // (older shorter rows still parse — missing fields default sanely;
            // versions are cached now so the picker shows full status — incl.
            // "ready · 2.1.150" — instantly on re-entry, not a bare "ready".)
            val parts = raw.split('|')
            if (parts.size < 3) continue
            val installed = parts[0] == "true"
            val loggedIn = parts[1] == "true"
            val ts = parts[2].toLongOrNull() ?: continue
            val methods = parts.getOrNull(3)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
            val active = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
            val cachedLatest = parts.getOrNull(6)?.takeIf { it.isNotEmpty() }
            map[agent] = AgentStatus(
                installed = installed,
                loggedIn = loggedIn,
                installedVersion = parts.getOrNull(5)?.takeIf { it.isNotEmpty() },
                // Prefer THIS server's own fetched latest; fall back to the
                // registry-global only when it's blank (don't override a real
                // per-server value — Claude's installer channel can differ).
                latestVersion = cachedLatest ?: globalLatest,
                methods = methods,
                activeMethod = active,
                // Carry the "live-auth not yet verified" flag across the cache
                // round-trip. Without it a fast-probed-but-unverified OAuth (e.g.
                // Gemini with an OAuth cred file that isn't actually usable)
                // reloaded as liveAuthPending=false → the row rendered a FALSE "[
                // ready ] OAuth" instead of "[ checking ]" until a manual refresh
                // ran the live-auth. A connect-triggered refresh then resolves
                // checking→ready/login.
                liveAuthPending = parts.getOrNull(7) == "true",
            )
            if (newestTs == null || ts > newestTs) newestTs = ts
        }
        return Snapshot(statuses = map, lastCheckedAt = newestTs)
    }

    suspend fun save(serverId: String, statuses: Map<Agent, AgentStatus>) {
        val ts = System.currentTimeMillis()
        context.statusDataStore.edit { prefs ->
            for ((agent, status) in statuses) {
                prefs[key(serverId, agent)] =
                    "${status.installed}|${status.loggedIn}|$ts|" +
                        "${status.methods.joinToString(",")}|${status.activeMethod.orEmpty()}|" +
                        "${status.installedVersion.orEmpty()}|${status.latestVersion.orEmpty()}|" +
                        "${status.liveAuthPending}"
                // Persist the registry-global latest per agent (server-independent),
                // taking the newest seen. A server whose own `npm view` failed
                // reuses this in load() → correct "update" verdict even offline.
                val lv = status.latestVersion?.takeIf { it.isNotBlank() }
                if (lv != null) {
                    val prev = prefs[globalLatestKey(agent)]
                    if (prev.isNullOrBlank() || isVersionLessThan(prev, lv)) {
                        prefs[globalLatestKey(agent)] = lv
                    }
                }
            }
        }
    }

    suspend fun forget(serverId: String) {
        context.statusDataStore.edit { prefs ->
            for (agent in Agent.entries) prefs.remove(key(serverId, agent))
        }
    }

    private fun key(serverId: String, agent: Agent) =
        stringPreferencesKey("status/$serverId/${agent.name}")

    /** Global (server-independent) latest published version per agent. */
    private fun globalLatestKey(agent: Agent) =
        stringPreferencesKey("latest/${agent.name}")
}
