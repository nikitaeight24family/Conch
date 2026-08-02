package ai.eight24family.conch.data

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.spec.AgentExec
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.ui.viewmodel.ChatViewModelModels
import ai.eight24family.conch.util.SilentlyTry
import net.schmizz.sshj.SSHClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Warms the model + reasoning catalogs for a (server, agent) pair over an
 * already-authenticated pooled SSH client — at APP STARTUP / server
 * connect, BEFORE any chat is opened. The user's requirement
 * (2026-06-10):.
 *
 * Triggered from [GlobalPrefetcher]'s sweep (app start + every server
 * connect). Results land in EXACTLY the caches the chat coordinator
 * hydrates from (prefs labels + in-memory label mirror + persisted
 * reasoning catalog + the spec's own probe stash), so a chat opened
 * afterwards paints the real picker on frame zero. The chat-open probe
 * checks [isFresh] and skips its own heavy PTY pass while a warm-up
 * result is recent.
 */
object ModelCatalogPrefetcher {

    private const val TAG = "SshAi-Models"

    /** How long a probe result is considered current. The warm-up re-runs
     *  on every app start and server connect anyway; this only suppresses
     *  redundant heavy PTY probes when chats open right after. */
    private const val FRESH_MS = 10 * 60 * 1000L

    private val lastProbeAt = ConcurrentHashMap<String, Long>()

    private fun key(serverId: String, agent: Agent) = "$serverId|${agent.name}"

    fun isFresh(serverId: String, agent: Agent): Boolean =
        System.currentTimeMillis() - (lastProbeAt[key(serverId, agent)] ?: 0L) < FRESH_MS

    fun markProbed(serverId: String, agent: Agent) {
        lastProbeAt[key(serverId, agent)] = System.currentTimeMillis()
    }

    /**
     * Probe models + reasoning for one (client, agent) and persist.
     * Rides the given pooled client only — never initiates a handshake
     * (SK servers would need a FIDO touch). Returns the parsed model map
     * Returns the MERGED catalog (empty = probe failed; nothing is overwritten
     * in that case) — callers must not see this probe's raw answer, or they
     * would re-introduce the regression the merge exists to prevent.
     */
    suspend fun probeAndPersist(client: SSHClient, agent: Agent, serverId: String): Map<String, String> {
        val spec = AgentSpecRegistry[agent]
        val exec = AgentExec { cmd ->
            SilentlyTry.logged(TAG, "catalog exec on pooled client") {
                val sess = client.startSession()
                try {
                    val proc = sess.exec(cmd)
                    val out = java.io.ByteArrayOutputStream()
                    proc.inputStream.copyTo(out)
                    proc.join(60, java.util.concurrent.TimeUnit.SECONDS)
                    String(out.toByteArray(), Charsets.UTF_8)
                } finally {
                    SilentlyTry.fired(TAG, "close catalog exec session") { sess.close() }
                }
            }
        }
        val map = runCatching { spec.probeAvailableModels(exec) }
            .onFailure { android.util.Log.w(TAG, "catalog warm-up failed for ${spec.agent}", it) }
            .getOrDefault(emptyMap())
        if (map.isEmpty()) return map
        // Same persistence the chat coordinator does after ITS probe. Both go
        // through the monotonic merge, so a warm-up against a server running an
        // older CLI can't roll the catalog back for every other server.
        val merged = ChatViewModelModels.rememberLabels(agent.name, map)
        ServiceLocator.preferences.setModelLabelsForAgent(agent.name, map)
        val rmap = merged.keys.mapNotNull { slug ->
            spec.reasoningInfoFor(slug)?.let { slug to it }
        }.toMap()
        spec.serializeReasoningCatalog(rmap)?.let {
            ServiceLocator.preferences.setReasoningCatalogForAgent(agent.name, it)
        }
        markProbed(serverId, agent)
        android.util.Log.d(
            TAG,
            "catalog warm ${spec.agent}@$serverId: probed=${map.keys} merged=${merged.keys} " +
                "levels=${rmap.values.firstOrNull()?.levels?.map { l -> l.effort }}",
        )
        return merged
    }
}
