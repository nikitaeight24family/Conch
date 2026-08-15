package ai.eight24family.conch.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentStatus
import ai.eight24family.conch.agent.ClaudeRunState
import ai.eight24family.conch.agent.isVersionLessThan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

    suspend fun load(serverId: String): Snapshot = parse(serverId, context.statusDataStore.data.first())

    /** Reactive view of this server's cached statuses — emits on every probe
     *  write, so any screen (chat, home list, usage bar) reflects a state change
     *  (e.g. a Claude "no Code" verdict) the instant it lands, not just on the
     *  next manual re-entry. Same parse as [load]. */
    fun observeStatuses(serverId: String): Flow<Map<Agent, AgentStatus>> =
        context.statusDataStore.data.map { parse(serverId, it).statuses }

    private fun parse(serverId: String, prefs: androidx.datastore.preferences.core.Preferences): Snapshot {
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
                // Claude Code run-state (9th field) + its datum (10th). Persisted so
                // a BLOCK verdict (no subscription / trial ended / …) survives
                // restart/re-entry instead of reverting to a false "ready" until the
                // next probe. Missing (older rows) / empty ⇒ null (unknown).
                // RATE_LIMITED/NEAR_LIMIT are TRANSIENT: expire them at read
                // time the moment their own resets_at passes — this cache has
                // no TTL, so a spent window otherwise blocked every surface
                // (chat banner, send gate, picker badge) long after the reset.
                claudeState = expireTransient(
                    ClaudeRunState.fromToken(parts.getOrNull(8)),
                    parts.getOrNull(9)?.takeIf { it.isNotEmpty() },
                ),
                claudeStateData = parts.getOrNull(9)?.takeIf { it.isNotEmpty() },
            )
            if (newestTs == null || ts > newestTs) newestTs = ts
        }
        return Snapshot(statuses = map, lastCheckedAt = newestTs)
    }

    /**
     * Persist a probe result and return the EFFECTIVE map that was written
     * (which may differ from [statuses] — see run-state preservation below).
     * Callers that also drive the picker UI should feed the RETURN into their
     * in-memory state so it matches what a later cache read (chat banner, home
     * list) will see.
     *
     * **Run-state preservation.** A Claude run-state probe curls the server's
     * `oauth/profile` (+ `usage`); that call can be truncated by the exec
     * timeout (slow network) or read no token — emitting NO `claude_run_state`
     * line, so the parsed [AgentStatus.claudeState] comes back `null` (or an
     * inconclusive `UNKNOWN`). Because every probe path overwrites the whole
     * row, such a probe would silently DOWNGRADE a previously-detected
     * "no subscription" back to a false "ready" the moment it lands last
     * (exactly the bug: two probes 2.7 s apart, the second missing the
     * run-state line, clobbering `NO_SUBSCRIPTION` → `[ ready ]`).
     *
     * A REAL state change always yields a concrete value (OK / NO_SUBSCRIPTION
     * / TRIAL_ENDED / …) that legitimately overwrites; only "couldn't check"
     * is indeterminate. So: while still in the OAuth regime, an indeterminate
     * new reading keeps the last KNOWN run-state instead of nulling it. If
     * oauth is gone (user switched to an API key), run-state no longer applies
     * and we let it clear.
     */
    suspend fun save(serverId: String, statuses: Map<Agent, AgentStatus>): Map<Agent, AgentStatus> {
        val ts = System.currentTimeMillis()
        val effective = LinkedHashMap<Agent, AgentStatus>(statuses.size)
        context.statusDataStore.edit { prefs ->
            for ((agent, status) in statuses) {
                var s = status
                if (agent == Agent.CLAUDE && "oauth" in status.methods) {
                    val newSt = status.claudeState
                    if (newSt == null || newSt == ClaudeRunState.UNKNOWN) {
                        val old = prefs[key(serverId, agent)]?.split('|')
                        val oldData = old?.getOrNull(9)?.takeIf { it.isNotEmpty() }
                        // Never resurrect a limit verdict whose reset already
                        // passed — preserving it here was exactly how a stale
                        // "rate limited" outlived its own reset time.
                        val oldSt = expireTransient(ClaudeRunState.fromToken(old?.getOrNull(8)), oldData)
                        if (oldSt != null && oldSt != ClaudeRunState.UNKNOWN) {
                            s = status.copy(
                                claudeState = oldSt,
                                claudeStateData = oldData,
                            )
                        }
                    }
                }
                effective[agent] = s
                prefs[key(serverId, agent)] =
                    "${s.installed}|${s.loggedIn}|$ts|" +
                        "${s.methods.joinToString(",")}|${s.activeMethod.orEmpty()}|" +
                        "${s.installedVersion.orEmpty()}|${s.latestVersion.orEmpty()}|" +
                        "${s.liveAuthPending}|" +
                        "${s.claudeState?.name.orEmpty()}|" +
                        (s.claudeStateData.orEmpty())
                // Persist the registry-global latest per agent (server-independent),
                // taking the newest seen. A server whose own `npm view` failed
                // reuses this in load() → correct "update" verdict even offline.
                val lv = s.latestVersion?.takeIf { it.isNotBlank() }
                if (lv != null) {
                    val prev = prefs[globalLatestKey(agent)]
                    if (prev.isNullOrBlank() || isVersionLessThan(prev, lv)) {
                        prefs[globalLatestKey(agent)] = lv
                    }
                }
            }
        }
        return effective
    }

    suspend fun forget(serverId: String) {
        context.statusDataStore.edit { prefs ->
            for (agent in Agent.entries) prefs.remove(key(serverId, agent))
        }
    }

    /** Null out a transient limit state whose own reset moment has passed
     *  (see [ClaudeRunState.isExpired]); every other state passes through. */
    private fun expireTransient(state: ClaudeRunState?, data: String?): ClaudeRunState? =
        if (ClaudeRunState.isExpired(state, data)) null else state

    private fun key(serverId: String, agent: Agent) =
        stringPreferencesKey("status/$serverId/${agent.name}")

    /** Global (server-independent) latest published version per agent. */
    private fun globalLatestKey(agent: Agent) =
        stringPreferencesKey("latest/${agent.name}")
}
