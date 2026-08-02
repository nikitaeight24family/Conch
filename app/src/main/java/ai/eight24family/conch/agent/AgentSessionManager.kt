package ai.eight24family.conch.agent

import android.content.Context
import ai.eight24family.conch.data.ServerRepository
import ai.eight24family.conch.service.SshAiService
import ai.eight24family.conch.ssh.SshClient
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Stable description of one live agent session, for the Settings list. */
data class ActiveSessionInfo(
    val key: String,
    val serverId: String,
    val agent: Agent,
    val chatSessionId: String
)

class AgentSessionManager(
    private val appContext: Context,
    private val repository: ServerRepository,
    private val ssh: SshClient
) {
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val _active = MutableStateFlow<List<ActiveSessionInfo>>(emptyList())
    val active: StateFlow<List<ActiveSessionInfo>> = _active.asStateFlow()

    // Session last-activity time used to live here as a `touched` SharedFlow +
    // an in-memory `_lastTouchedAt` map. Both are gone — superseded by the
    // persisted, monotonic `ServiceLocator.sessionActivity` store, which fixes
    // the bump-lost-on-restart bug this in-memory map could never survive.

    suspend fun openOrGet(
        serverId: String,
        agent: Agent,
        chatSessionId: String,
        resumeId: String? = null,
        /**
         * Hardware-token signer, supplied by the caller when the server
         * uses an SK key. Set on the [AgentSession] BEFORE `start()`
         * fires the first SSH handshake — without it, a server keyed
         * by SK would auth-fail on the first packet. Ignored for
         * software-key servers.
         */
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null,
    ): AgentSession? {
        val key = key(serverId, agent, chatSessionId)
        sessions[key]?.let { existing ->
            // Refresh the signer on each open: NFC tags go away when the
            // user lifts the phone, so the cached signer's underlying
            // device is dead by the time a reconnect fires. The chat-open
            // flow always discovers a fresh device before calling us.
            if (skSigner != null) existing.skSigner = skSigner
            return existing
        }
        val server = repository.getById(serverId) ?: return null
        val secrets = repository.getSecrets(serverId)
        val session = AgentSession(server.copy(agent = agent), secrets, ssh, chatSessionId, resumeId)
        if (skSigner != null) session.skSigner = skSigner
        sessions[key] = session
        updateCount()
        SshAiService.start(appContext)
        session.start()
        return session
    }

    fun get(serverId: String, agent: Agent, chatSessionId: String): AgentSession? =
        sessions[key(serverId, agent, chatSessionId)]

    fun close(serverId: String, agent: Agent, chatSessionId: String) {
        sessions.remove(key(serverId, agent, chatSessionId))?.close()
        updateCount()
        // Don't force-stop the service here. SshAiService's own observer
        // watches both activeCount AND pool.userHeldCount and stops only
        // when BOTH are zero — closing an AgentSession shouldn't drop a
        // user-intent SSH ref.
    }

    suspend fun terminate(serverId: String, agent: Agent, chatSessionId: String) {
        val s = sessions.remove(key(serverId, agent, chatSessionId)) ?: return
        s.terminate()
        updateCount()
        // Don't force-stop the service here. SshAiService's own observer
        // watches both activeCount AND pool.userHeldCount and stops only
        // when BOTH are zero — closing an AgentSession shouldn't drop a
        // user-intent SSH ref.
    }

    fun closeAllForServer(serverId: String) {
        val prefix = "$serverId:"
        val toClose = sessions.keys.filter { it.startsWith(prefix) }
        toClose.forEach { sessions.remove(it)?.close() }
        updateCount()
        // Don't force-stop the service here. SshAiService's own observer
        // watches both activeCount AND pool.userHeldCount and stops only
        // when BOTH are zero — closing an AgentSession shouldn't drop a
        // user-intent SSH ref.
    }

    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        updateCount()
        // Service decides whether to stop based on activeCount + held.
        // If user-intent SSH refs are still in place, the foreground
        // service keeps the process alive even after we close all
        // AgentSessions — by design.
    }

    private fun updateCount() {
        _activeCount.value = sessions.size
        _active.value = sessions.keys.mapNotNull { k ->
            val parts = k.split(":", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val agent = SilentlyTry.logged("SshAi-AgentMgr", "parse agent from key") { Agent.valueOf(parts[1]) } ?: return@mapNotNull null
            ActiveSessionInfo(key = k, serverId = parts[0], agent = agent, chatSessionId = parts[2])
        }
    }
    private fun key(serverId: String, agent: Agent, sessionId: String) =
        "$serverId:${agent.name}:$sessionId"

    /** Close a session by its composite key (used from Settings list). */
    fun closeByKey(key: String) {
        sessions.remove(key)?.close()
        updateCount()
        // Don't force-stop the service here. SshAiService's own observer
        // watches both activeCount AND pool.userHeldCount and stops only
        // when BOTH are zero — closing an AgentSession shouldn't drop a
        // user-intent SSH ref.
    }

    /**
     * Snapshot of resume ids for sessions that are GENUINELY connected and
     * running. Read-only: never closes anything along the way — destructive
     * cleanup mid-iteration was racing with `ChatViewModel.activeSessions`
     * cache and turning a transiently-bootstrapping session into a zombie
     * (closed scope + still-cached reference + state pushed back to Running
     * by the reconnect coroutine). Sweeping dead entries is now an explicit
     * caller responsibility (none currently — the manager doesn't need it).
     */
    fun activeResumeIds(serverId: String, agent: Agent): Set<String> {
        val prefix = "$serverId:${agent.name}:"
        val ids = mutableSetOf<String>()
        for ((key, session) in sessions) {
            if (!key.startsWith(prefix)) continue
            if (!session.isAlive()) continue
            session.agentSessionId?.let { ids += it }
        }
        return ids
    }

    /**
     * First live session matching (serverId, agent), regardless of which
     * chat owns it. Lets non-chat screens (the subagents browser) reuse
     * an already-authenticated SSH channel instead of paying handshake
     * cost on every operation.
     */
    fun findAnyAlive(serverId: String, agent: Agent): AgentSession? {
        val prefix = "$serverId:${agent.name}:"
        return sessions.entries.firstOrNull { (k, v) ->
            k.startsWith(prefix) && v.isAlive()
        }?.value
    }

    /**
     * Pick the session most likely to be the user's "current focus" —
     * used by Picture-in-Picture to decide whose stream to render in
     * the floating window when the app gets backgrounded.
     *
     * Preference order:
     *  1. A session whose state is `Running` or `Working` (the agent
     *     is actively generating right now — that's literally what
     *     the user wants to watch from PiP).
     *  2. Otherwise, any alive session — best-effort fallback so
     *     "background while idle" still shows the most recent chat
     *     instead of nothing.
     *
     * Returns null only if every session has died or none exist.
     */
    fun findMostRecentlyActive(): AgentSession? {
        val alive = sessions.values.filter { it.isAlive() }
        return alive.firstOrNull {
            val s = it.state.value
            s == SessionState.Running || s == SessionState.Working
        } ?: alive.firstOrNull()
    }

    /**
     * Find an alive session that's already attached to the given CLI-side
     * `resumeId`. Lets ChatViewModel reuse an existing AgentSession when
     * the user pops the chat off the back stack and re-opens it — without
     * this lookup the new ChatViewModel would generate a fresh random
     * `chatSessionId`, [openOrGet] would key on it, miss the cached
     * AgentSession, and create a NEW one — silently losing any in-memory
     * `_history` (including pending UserText that hadn't been ack'd by
     * the CLI yet, the exact bug behind "I sent a message, came back, my
     * message is gone").
     */
    fun findByResume(serverId: String, agent: Agent, resumeId: String): AgentSession? {
        val prefix = "$serverId:${agent.name}:"
        return sessions.entries.firstOrNull { (k, v) ->
            k.startsWith(prefix) && v.agentSessionId == resumeId && v.isAlive()
        }?.value
    }

    /**
     * Find an alive session for (serverId, agent) that hasn't been
     * assigned a CLI-side session id yet — the "brand-new chat" slot.
     *
     * Use case (issue #38): user taps "+ new session" on the agent
     * picker, types "hello", presses send while SSH is still doing
     * userauth, then pops back to the sessions list. The
     * `ChatViewModel` instance dies but the `AgentSession` it spawned
     * keeps living in this manager (the foreground service holds
     * onto it). When the user re-enters "+ new session" we adopt the
     * existing AgentSession instead of spawning a second one — its
     * in-memory `_history` already has the pending UserText, and the
     * SSH handshake it's already paid for shouldn't be repeated.
     *
     * At most one such orphan is expected per (serverId, agent) —
     * if there are several we return the first match; the caller's
     * draft-text restore (`HistoryCache.loadDrafts`) plus the
     * AgentSession's own `_history` together cover the user's
     * pending text either way.
     */
    fun findOrphanBrandNew(serverId: String, agent: Agent): AgentSession? {
        val prefix = "$serverId:${agent.name}:"
        return sessions.entries.firstOrNull { (k, v) ->
            k.startsWith(prefix) && v.isAlive() && v.agentSessionId == null
        }?.value
    }

    /**
     * Find ANY in-memory session for (serverId, agent) — alive or not —
     * whose CLI-side id matches [resumeId]. Used by ChatViewModel as the
     * second-chance lookup when [findByResume] missed because `isAlive()`
     * returned false (SSH socket dropped, but the AgentSession object
     * still has the in-memory message history we'd like to recover).
     * Caller is responsible for re-starting the session.
     */
    fun findByResumeIncludingDead(serverId: String, agent: Agent, resumeId: String): AgentSession? {
        val prefix = "$serverId:${agent.name}:"
        return sessions.entries.firstOrNull { (k, v) ->
            k.startsWith(prefix) && v.agentSessionId == resumeId
        }?.value
    }

    /**
     * No-op shim. Used to actively `close()` and remove sessions whose
     * `isAlive()` returned false, which raced with ChatViewModel's
     * own cached AgentSession reference (`activeSessions[localId]`) and
     * produced zombies — scope cancelled, sshClient still connected,
     * StateFlow still says Running, every `s.send()` silently dropped.
     * Now sessions only ever leave the map through explicit `close` /
     * `terminate` / `closeAllForServer` paths driven by the user.
     */
    fun reapDeadSessions(): Int = 0
}
