package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The **Servers** tab list. Pure infrastructure management now: it shows the
 * known hosts/users with their live-connection dot + cached agent badges, and
 * nothing more. Tapping a row opens [ServerDetailViewModel]'s page — which is
 * where connect / terminal / edit / delete live. The connect & SK-touch state
 * machine used to live HERE (tap → connect → agents); it moved to the detail
 * page so a list tap can never demand a key.
 */
class ServersViewModel : ViewModel() {

    private val repo = ServiceLocator.serverRepository
    private val prefetcher = ServiceLocator.globalPrefetcher

    val servers: StateFlow<List<Server>> = repo.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Server IDs with a GENUINELY live SSH transport right now → the cyan ● dot.
     * Sourced from the pool (`peek`, lock-free) plus any not-yet-migrated
     * AgentSession, recomputed on every active-list change and on a 3 s tick so
     * a silently-dropped transport darkens the dot without a row tap.
     */
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedServerIds: StateFlow<Set<String>> = _connectedServerIds.asStateFlow()

    /** Servers the user WANTS connected but whose transport is down right now
     *  (network blip / FIDO needs a re-tap) → amber "reconnect pending" dot.
     *  Distinct from "never connected" (plain ○) so a dropped session reads as
     *  recoverable, not gone. */
    private val _reconnectPendingIds = MutableStateFlow<Set<String>>(emptySet())
    val reconnectPendingIds: StateFlow<Set<String>> = _reconnectPendingIds.asStateFlow()

    // Per-server agent install/auth snapshot (from AgentStatusCache the picker
    // fills) → coloured (logged-in) vs grey (installed-only) badges on a row.
    private val statusCache = ai.eight24family.conch.data.AgentStatusCache(ServiceLocator.appContext)
    private val _agentStatuses =
        MutableStateFlow<Map<String, Map<Agent, ai.eight24family.conch.agent.AgentStatus>>>(emptyMap())
    val agentStatuses: StateFlow<Map<String, Map<Agent, ai.eight24family.conch.agent.AgentStatus>>> =
        _agentStatuses.asStateFlow()

    init {
        // Seed connectivity synchronously so a retained VM / warm open already
        // shows the right dots on the FIRST frame (no dark-then-green flash).
        recomputeConnectivity()
        // Pre-warm session/JSONL caches for authorized agents while the user is
        // here, so a later tap renders instantly. Cancelled in onCleared().
        prefetcher.start(viewModelScope)

        viewModelScope.launch {
            ServiceLocator.agentSessions.active.collect { recomputeConnectivity() }
        }
        // Recompute the INSTANT the pool's connection set changes. userConnect /
        // userConnectEphemeral update userHeldIds the moment a transport comes up
        // (peek is live by then), so the dot flips to green immediately — not up
        // to a poll-tick later. THE fix for "the connection is there but the dots
        // don't show it until I open this tab / they light up in front of me".
        viewModelScope.launch {
            ServiceLocator.sshConnectionPool.userHeldIds.collect { recomputeConnectivity() }
        }
        viewModelScope.launch {
            while (true) {
                recomputeConnectivity()
                delay(1_000)
            }
        }
        // Badge the rows the moment the server list arrives (react to the flow,
        // don't wait for a poll tick — rows used to sit badge-less ~4 s on cold
        // open when the first tick ran before servers loaded). Recompute dots too
        // so they're correct the instant the list is known.
        viewModelScope.launch {
            servers.collect { list ->
                recomputeConnectivity()
                if (list.isNotEmpty()) {
                    _agentStatuses.value = list.associate { it.id to statusCache.load(it.id).statuses }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(4_000)
                val list = servers.value
                if (list.isNotEmpty()) {
                    _agentStatuses.value = list.associate { it.id to statusCache.load(it.id).statuses }
                }
            }
        }
    }

    /** Refresh both the live-connection set and the reconnect-pending set.
     *  HONEST — the dot reflects the REAL transport (peek), nothing optimistic.
     *  It's never stale because [recomputeConnectivity] is re-run reactively the
     *  instant the pool's connection set changes (see init), so the real
     *  launch-time connection shows green the moment it's actually up. */
    private fun recomputeConnectivity() {
        _connectedServerIds.value = computeConnected()
        _reconnectPendingIds.value = ServiceLocator.sshConnectionPool.heldButDownIds()
    }

    private fun computeConnected(): Set<String> {
        val mgr = ServiceLocator.agentSessions
        val pool = ServiceLocator.sshConnectionPool
        val ids = mutableSetOf<String>()
        // tap-to-connect opens a pooled SSH WITHOUT an AgentSession, so iterate
        // the known servers and peek the pool (lock-free, O(servers)).
        for (server in servers.value) {
            if (pool.peek(server.id) != null) ids += server.id
        }
        // Belt-and-suspenders: any AgentSession not yet migrated to the pool.
        for (info in mgr.active.value) {
            if (info.serverId in ids) continue
            val alive = mgr.findAnyAlive(info.serverId, Agent.CLAUDE)
                ?: mgr.findAnyAlive(info.serverId, Agent.CODEX)
                ?: mgr.findAnyAlive(info.serverId, Agent.GEMINI)
            if (alive != null) ids += info.serverId
        }
        return ids
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    override fun onCleared() {
        super.onCleared()
        prefetcher.stop()
    }
}
