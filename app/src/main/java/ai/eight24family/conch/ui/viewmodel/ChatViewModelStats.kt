package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.ServerStats
import ai.eight24family.conch.data.ServerRepository
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Server stats sheet coordinator.
 *
 * Owns [serverStats] / [statsLoading] flows and the on-demand refresh that the user
 * triggers from the stats bottom sheet.
 *
 * Reuses the chat's already-authenticated SSH client when one is open (avoids the
 * handshake-inflated 10× RTT of a fresh `ssh.execute`). Falls back to the slow path
 * when no live session is open yet.
 */
internal class ChatViewModelStats(
    private val scope: CoroutineScope,
    private val serverId: String,
    private val repo: ServerRepository,
    private val server: () -> Server?,
    private val liveSession: () -> AgentSession?,
) {
    private val _serverStats = MutableStateFlow<ServerStats?>(null)
    val serverStats: StateFlow<ServerStats?> = _serverStats.asStateFlow()

    private val _statsLoading = MutableStateFlow(false)
    val statsLoading: StateFlow<Boolean> = _statsLoading.asStateFlow()

    fun refresh() {
        val s = server() ?: return
        scope.launch(Dispatchers.IO) {
            _statsLoading.value = true
            try {
                val live = liveSession()
                val result = if (live != null) {
                    // Reuse the already-authenticated SSH client → real channel RTT,
                    // not handshake-inflated 10× RTT.
                    ServiceLocator.serverStatsProbe.probe { cmd -> live.execOnLive(cmd) }
                } else {
                    val secrets = repo.getSecrets(serverId)
                    ServiceLocator.serverStatsProbe.probe(s, secrets)
                }
                result.onSuccess { _serverStats.value = it }
            } finally {
                _statsLoading.value = false
            }
        }
    }
}
