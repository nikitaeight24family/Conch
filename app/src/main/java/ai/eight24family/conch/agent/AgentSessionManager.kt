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
     * A session with a turn ACTUALLY IN FLIGHT — not merely one that exists.
     *
     * ⚠ `Running` means "the session is up and idle"; `Working` means "a turn is
     * generating". [findMostRecentlyActive] deliberately accepts both, which is
     * right for "whose chat should the floating window show" and WRONG for
     * "should there be a floating window at all". Picture-in-Picture was gated on
     * `activeCount > 0` — the number of session OBJECTS — so it opened on every
     * swipe home for the rest of the app's life once any chat had ever been
     * opened, showing a window about nothing.
     *
     * `drainerBusy` is included because our own turn tracking can desync off
     * `Working` (a chat reopened mid-turn) while the prompt drainer is provably
     * still inside a turn.
     */
    fun findWorkingSession(): AgentSession? = sessions.values.firstOrNull {
        it.isAlive() && (it.state.value == SessionState.Working || it.drainerBusy)
    }

    /**
     * Work worth keeping the Activity alive for, which is a WIDER question than
     * [findWorkingSession].
     *
     * Picture-in-Picture is not decorative: it keeps this process resumed, which
     * is what lets an in-flight handshake, a security-key touch, an upload or a
     * download over the SSH channel survive the user swiping home. Gating PiP on
     * "a turn is generating" alone dropped every one of those — and the gap
     * between pressing send and the state actually flipping to `Working`.
     *
     * `Bootstrapping` is included for exactly that reason and costs nothing in
     * the "window about nothing" direction it replaced: it is a transient state
     * measured in seconds, not the permanent "a session object exists" that used
     * to open PiP on every swipe home for the life of the process.
     */
    fun anyWorkWorthFloating(): Boolean = sessions.values.any {
        it.isAlive() && (
            it.state.value == SessionState.Working ||
                it.state.value is SessionState.Bootstrapping ||
                it.drainerBusy
            )
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
     *
     * ⚠ ADOPTABLE, not merely alive — see [SessionStateMachine.isAdoptable].
     * `isAlive()` alone re-binds a dead session onto the pool's newest
     * transport and reports "alive" for a session parked in
     * `Failed("disconnected")`; adopting that fed the chat a terminal state on
     * every reconnect and livelocked the app (2026-08-16).
     */
    fun findByResume(serverId: String, agent: Agent, resumeId: String): AgentSession? {
        val prefix = "$serverId:${agent.name}:"
        return sessions.entries.firstOrNull { (k, v) ->
            k.startsWith(prefix) && v.agentSessionId == resumeId &&
                v.isAlive() && SessionStateMachine.isAdoptable(v.state.value)
        }?.value
    }

    /**
     * Close every in-memory session for (serverId, agent) attached to
     * [resumeId] except the chat slot [keepChatId], and report how many died.
     *
     * `close(serverId, agent, chatSessionId)` only ever removes ONE key, and a
     * chat that adopts a session via [findByResume] keeps it under the key of
     * the chat that CREATED it — so the ViewModel's rebuild path closed a key
     * that held nothing while the real session object stayed in this map. Each
     * reconnect left another one behind (four were rebinding themselves onto
     * the pool's transport per cycle in the 2026-08-16 logcat), every one of
     * them still holding a pool reference the matching `release` will never
     * balance. Rebuilding a chat has to reap them by resume id.
     */
    fun closeStaleForResume(
        serverId: String,
        agent: Agent,
        resumeId: String,
        keepChatId: String? = null,
    ): Int {
        val prefix = "$serverId:${agent.name}:"
        val stale = sessions.entries
            .filter { (k, v) ->
                k.startsWith(prefix) && v.agentSessionId == resumeId &&
                    k != key(serverId, agent, keepChatId ?: "")
            }
            .map { it.key }
        for (k in stale) sessions.remove(k)?.close()
        if (stale.isNotEmpty()) {
            android.util.Log.d(
                "SshAi-AgentMgr",
                "reaped ${stale.size} stale session(s) for resume=${resumeId.take(8)}",
            )
            updateCount()
        }
        return stale.size
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
            k.startsWith(prefix) && v.agentSessionId == null &&
                v.isAlive() && SessionStateMachine.isAdoptable(v.state.value)
        }?.value
    }

    /**
     * Close ONE session — [chatId] — if and only if it never got a CLI-side id
     * and isn't mid-turn. Reports whether it died.
     *
     * ⚠ THIS IS THE OTHER HALF OF NOT ADOPTING AN ORPHAN.
     *
     * "+ new chat" used to hand the user back the PREVIOUS brand-new chat via
     * [findOrphanBrandNew] — same session object, same history, same stuck
     * state. Adoption is now refused for a user-initiated new chat — but the
     * refused orphan would be unreachable forever (that lookup WAS the only way
     * back to it) while still holding a live `claude --print` process, an SSH
     * channel and an `SshConnectionPool.acquire` reference nothing will ever
     * release. Abandon one per new chat and the server-side session ceiling is
     * only a matter of time.
     *
     * ⚠ CALLER MUST OWN [chatId]. This deliberately does NOT sweep by
     * (serverId, agent): a second brand-new chat sitting further down the back
     * stack looks identical from here, and killing it would leave that screen
     * holding a corpse. Each ChatViewModel closes ITS OWN slot — when it moves
     * on to another one, and again in `onCleared`.
     *
     * A session mid-TURN is left alone. A brand-new chat that is actually
     * answering gets its CLI id within a second of `system.init`, so anything
     * still `Working` without one is a first turn in flight, and the user
     * leaving the screen is not a reason to cancel their reply — the foreground
     * service exists to keep exactly that running. It stops being brand-new on
     * its own.
     *
     * Typed-but-unsent text is NOT lost by this: `onCleared` persists it to the
     * (server, agent) draft slot first, and the next new chat offers it back
     * through the composer — the durable half of invariant #38, and the only
     * half that survives process death anyway.
     */
    fun closeIfBrandNew(serverId: String, agent: Agent, chatId: String): Boolean {
        val k = key(serverId, agent, chatId)
        val s = sessions[k] ?: return false
        if (s.agentSessionId != null) return false
        // ⚠ `Working` ALONE IS NOT ENOUGH. Our own turn tracking can desync off
        // Working while a turn is provably still in flight — `reconcileStuckTurn`
        // forces Working→Running when the reader wedges, and a brand-new chat
        // whose `system.init` was missed never gets an id either. Both at once is
        // exactly this method's input: it would then cancel the drainer, close the
        // channel and kill the user's first turn, with the prompt recoverable from
        // nowhere (it is already past `pendingRedelivery` and `consumeUndelivered`).
        // `drainerBusy` stays true for the whole of a turn we launched, which is
        // the same test `findWorkingSession` uses for "is anything running".
        if (s.state.value == SessionState.Working || s.drainerBusy) return false
        sessions.remove(k)?.close()
        android.util.Log.d(
            "SshAi-AgentMgr",
            "closed unreachable brand-new session on $serverId/${agent.name}",
        )
        updateCount()
        return true
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
