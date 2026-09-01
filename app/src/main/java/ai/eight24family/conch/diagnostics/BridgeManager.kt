package ai.eight24family.conch.diagnostics

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Spawns and reaps [AgentBridge] instances based on which servers
 * currently have a live pooled SSH client.
 *
 * One bridge per `serverId`, multiplexed by the manager:
 *   - `pool.peek(serverId) != null` → bridge running.
 *   - Pool drops the client → bridge stopped within ~3s.
 *
 * The manager itself is a singleton owned by [ai.eight24family.conch.service.ConchService]
 * (the foreground service), which means the bridges only run while
 * the user has at least one chat open OR has explicitly tap-to-
 * connected on the home screen. Closing all chats and disconnecting
 * the user-intent reference stops the foreground service, which
 * stops the bridge — as it should, since polling a server we have no
 * connection to is pointless.
 */
class BridgeManager(
    private val handler: BridgeHandler,
) {

    private val tag = "Conch-BridgeMgr"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridges = ConcurrentHashMap<String, AgentBridge>()
    private var watchJob: Job? = null

    fun start() {
        if (watchJob?.isActive == true) return
        android.util.Log.d(tag, "start")
        watchJob = scope.launch {
            while (isActive) {
                runCatching { reconcile() }
                    .onFailure { android.util.Log.w(tag, "reconcile failed: ${it.message}") }
                delay(POLL_RECONCILE_MS)
            }
        }
    }

    fun stop() {
        android.util.Log.d(tag, "stop — tearing down ${bridges.size} bridge(s)")
        watchJob?.cancel()
        watchJob = null
        bridges.values.forEach { SilentlyTry.fired("Conch-BridgeMgr", "stop bridge in stopAll") { it.stop() } }
        bridges.clear()
        scope.cancel()
    }

    /** Start a bridge for [serverId] RIGHT NOW if a transport is live and none
     *  runs yet — the self-test handshake must not wait out the reconcile tick. */
    fun ensure(serverId: String) {
        if (bridges.containsKey(serverId)) return
        if (ServiceLocator.sshConnectionPool.peek(serverId) == null) return
        android.util.Log.d(tag, "ensure: starting bridge for $serverId")
        val b = AgentBridge(serverId = serverId, handler = handler)
        bridges[serverId] = b
        b.start()
    }

    /**
     * Walk the pool's user-held set + alive session ids; start a
     * bridge for any new `serverId`, stop bridges whose servers no
     * longer have a live transport.
     */
    private fun reconcile() {
        val pool = ServiceLocator.sshConnectionPool
        // The pool exposes its "user-held" set (servers the user
        // tap-to-connected). For chat-open servers we don't have a
        // direct accessor; iterate AgentSessionManager.active and
        // dedupe.
        val alive = mutableSetOf<String>()
        alive += pool.userHeldIds()
        alive += ServiceLocator.agentSessions.active.value
            .map { it.serverId }
            .filter { pool.peek(it) != null }

        // Stop bridges for servers no longer alive.
        val toStop = bridges.keys.filterNot { it in alive }
        for (id in toStop) {
            bridges.remove(id)?.let {
                android.util.Log.d(tag, "stopping bridge for $id (no live transport)")
                SilentlyTry.fired("Conch-BridgeMgr", "stop dead bridge") { it.stop() }
            }
        }

        // Start bridges for newly-alive servers.
        for (id in alive) {
            if (!bridges.containsKey(id)) {
                android.util.Log.d(tag, "starting bridge for $id")
                val b = AgentBridge(serverId = id, handler = handler)
                bridges[id] = b
                b.start()
            }
        }
    }

    companion object {
        private const val POLL_RECONCILE_MS = 3_000L
    }
}
