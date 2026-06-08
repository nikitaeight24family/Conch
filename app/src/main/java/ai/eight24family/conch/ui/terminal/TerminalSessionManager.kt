package ai.eight24family.conch.ui.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of live [TerminalSession]s, keyed by `serverId`.
 * Holds them OUTSIDE any ViewModel so a terminal shell survives navigation
 * (leave the screen, open a chat, come back → same shell). One session per
 * server — re-opening the terminal re-attaches to it.
 *
 * Lifetime mirrors agent sessions: a session is closed only when the user
 * disconnects the server (see ServersScreen onDisconnect →
 * [closeForServer]) or the remote shell exits on its own.
 */
class TerminalSessionManager {

    // Own supervisor scope — outlives every ViewModel; one failed read loop
    // never cancels another server's shell.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    /** The persistent shell for [serverId], created on first use. */
    fun get(serverId: String): TerminalSession =
        sessions.computeIfAbsent(serverId) { TerminalSession(it, scope) }

    /** Close + forget the shell for one server (called on user-disconnect). */
    fun closeForServer(serverId: String) {
        sessions.remove(serverId)?.close()
    }

    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
