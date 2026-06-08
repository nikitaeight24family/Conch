package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.ui.terminal.VtScreen
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin view over the persistent [ai.eight24family.conch.ui.terminal.TerminalSession]
 * for this server. The shell itself lives in
 * [ai.eight24family.conch.ui.terminal.TerminalSessionManager] (process-wide),
 * so leaving the terminal screen does NOT tear it down — re-opening attaches
 * to the SAME shell (cwd, scrollback, running process intact). The session is
 * closed only when the user disconnects the server.
 */
class TerminalViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val session = ServiceLocator.terminalSessions.get(serverId)

    val screen: StateFlow<VtScreen> = session.screen
    val connected: StateFlow<Boolean> = session.connected

    init { session.ensureStarted() }

    fun send(text: String) = session.sendText(text)
    fun sendBytes(bytes: ByteArray) = session.sendBytes(bytes)
    fun sendArrow(dir: Char) = session.sendArrow(dir)
    fun resize(cols: Int, rows: Int) = session.resize(cols, rows)

    // NOTE: deliberately NO close() in onCleared — the shell must outlive
    // this screen. It is closed via ServiceLocator.terminalSessions
    // .closeForServer(serverId) when the user disconnects the server.
}
