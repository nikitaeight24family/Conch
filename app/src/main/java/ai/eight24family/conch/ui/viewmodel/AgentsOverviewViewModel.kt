package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The **Agents** tab overview: just the list of servers + a live connection dot
 * per server. Each server's agents are rendered inline by
 * [ai.eight24family.conch.ui.screens.ServerAgentPanel], which owns its own
 * per-server [AgentPickerViewModel] (install / update / login / method switch /
 * touch-connect) — so this VM stays a thin list provider. Re-reads on a 2.5 s
 * tick so the connection dots track tap-to-connect / drops.
 */
class AgentsOverviewViewModel : ViewModel() {

    private val repo = ServiceLocator.serverRepository
    private val pool = ServiceLocator.sshConnectionPool

    data class Entry(val server: Server, val connected: Boolean)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _loadedOnce = MutableStateFlow(false)
    val loadedOnce: StateFlow<Boolean> = _loadedOnce.asStateFlow()

    // Pull-to-refresh on the Agents tab. Each server's agents live in a SEPARATE
    // per-server AgentPickerViewModel (owned by its ServerSection), so this VM
    // can't call their refresh() directly — instead it bumps [refreshTick], which
    // every visible ServerSection observes and turns into a userTriggered
    // re-probe. [refreshing] drives the PullToRefreshBox spinner: a short fixed
    // window (the badges self-update live as each panel's probe lands, and each
    // panel also shows its own "refreshing…" bar — we don't gate the gesture on N
    // independent VMs all finishing). Before this, the tab had NO pull-to-refresh
    // at all (it lived only in the full-screen picker) — the swipe did nothing,
    // no animation.
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refreshAll() {
        _refreshTick.value += 1
        _refreshing.value = true
        viewModelScope.launch {
            delay(1_500)
            _refreshing.value = false
        }
    }

    init {
        viewModelScope.launch {
            while (true) {
                // Foreground only — backgrounded there is no tab to paint, and
                // this VM outlives the screen (battery).
                if (ai.eight24family.conch.util.AppForeground.isForeground) reload()
                delay(2_500)
            }
        }
    }

    private suspend fun reload() {
        // Sort by host then user so multiple users on the same machine
        // (user@x, user@example.com) cluster together, each with its own agents.
        val servers = repo.observeServers().first()
            .sortedWith(compareBy({ it.host.lowercase() }, { it.port }, { it.username.lowercase() }))
        _entries.value = servers.map { s -> Entry(server = s, connected = pool.peek(s.id) != null) }
        _loadedOnce.value = true
    }
}
