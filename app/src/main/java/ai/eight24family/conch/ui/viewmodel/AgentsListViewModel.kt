package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentDoc
import ai.eight24family.conch.data.SubagentService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [AgentsListScreen]. Receives serverId, agent, and (optional) chatId
 * via SavedStateHandle. The chatId is just a fast-path hint — the
 * [SubagentService] tries any live session for the same (server, agent) and
 * finally falls back to a fresh handshake, so this screen is always usable.
 */
class AgentsListViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val agent: Agent = Agent.valueOf(checkNotNull(savedStateHandle["agent"]))
    private val chatId: String? = savedStateHandle["chatId"]

    private val service = SubagentService(serverId, agent, chatId)

    private val _agents = MutableStateFlow<List<AgentDoc>>(emptyList())
    val agents: StateFlow<List<AgentDoc>> = _agents.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Cwd of the live session, surfaced so the screen can show it. */
    private val _cwd = MutableStateFlow<String?>(null)
    val cwd: StateFlow<String?> = _cwd.asStateFlow()

    val agentName: String = agent.name
    val agentCli: String = agent.cliCommand

    init {
        _cwd.value = service.cwdSnapshot()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _cwd.value = service.cwdSnapshot()
                _agents.value = service.list(_cwd.value)
            } finally {
                _loading.value = false
            }
        }
    }

    fun delete(path: String) {
        viewModelScope.launch {
            val ok = service.delete(path)
            if (ok) {
                _agents.value = _agents.value.filterNot { it.path == path }
            }
        }
    }
}
