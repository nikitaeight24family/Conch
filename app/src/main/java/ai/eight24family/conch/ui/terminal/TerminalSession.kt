package ai.eight24family.conch.ui.terminal

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A long-lived interactive shell for one server. **Lives in
 * [TerminalSessionManager], NOT in a ViewModel** — so leaving the terminal
 * screen (or bouncing to a chat and back) keeps the SAME shell: same cwd,
 * same scrollback, same running process. The session is only torn down when
 * the user disconnects the server (mirrors how agent sessions are closed)
 * or the remote shell exits.
 *
 * Rides the pooled SSH transport — no new sshj client, no extra FIDO touch.
 * Bytes from the shell drive a real [VtEmulator] (full screen grid) so
 * vim/htop/tmux render; keystrokes are written straight back to stdin.
 */
class TerminalSession(
    val serverId: String,
    private val scope: CoroutineScope,
) {
    val emulator = VtEmulator(cols = 80, rows = 24)

    private val _screen = MutableStateFlow(emulator.snapshot())
    val screen: StateFlow<VtScreen> = _screen.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var stdin: java.io.OutputStream? = null
    @Volatile private var session: net.schmizz.sshj.connection.channel.direct.Session? = null
    @Volatile private var shellRef: net.schmizz.sshj.connection.channel.direct.Session.Shell? = null
    @Volatile private var started = false

    // Trailing bytes of an incomplete UTF-8 sequence carried to the next read.
    private var carry = ByteArray(0)

    init {
        // The emulator replies to a few queries (cursor position report) by
        // writing back to the shell.
        emulator.respond = { s -> sendText(s) }
    }

    /** Idempotent: spins up the shell once. Safe to call on every screen entry. */
    fun ensureStarted() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch(Dispatchers.IO) { run() }
    }

    private fun run() {
        val client = ServiceLocator.sshConnectionPool.peek(serverId)
        if (client == null || !client.isConnected) {
            emulator.feed("\r\n  [ not connected — open the server (tap it) first, then re-open the terminal ]\r\n")
            _screen.value = emulator.snapshot()
            started = false // allow a retry once the user connects
            return
        }
        try {
            val s = client.startSession()
            session = s
            SilentlyTry.fired("SshAi-Term", "allocate pty") {
                s.allocatePTY("xterm-256color", emulator.cols, emulator.rows, 0, 0, java.util.Collections.emptyMap())
            }
            val shell = s.startShell()
            shellRef = shell
            stdin = shell.outputStream
            _connected.value = true
            android.util.Log.d("SshAi-Term", "shell started for $serverId (${emulator.cols}x${emulator.rows})")
            val ins = shell.inputStream
            val buf = ByteArray(8192)
            while (true) {
                val n = try { ins.read(buf) } catch (_: Throwable) { -1 }
                if (n < 0) break
                if (n > 0) {
                    val text = decode(buf, n)
                    if (text.isNotEmpty()) {
                        emulator.feed(text)
                        _screen.value = emulator.snapshot()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("SshAi-Term", "shell error: ${t.javaClass.simpleName}: ${t.message}")
            emulator.feed("\r\n  [ session ended: ${t.message ?: t.javaClass.simpleName} ]\r\n")
            _screen.value = emulator.snapshot()
        } finally {
            _connected.value = false
            stdin = null
            shellRef = null
            SilentlyTry.fired("SshAi-Term", "close session") { session?.close() }
            session = null
            started = false
        }
    }

    /** Decode a chunk as UTF-8, carrying any incomplete trailing sequence. */
    private fun decode(chunk: ByteArray, n: Int): String {
        val data = if (carry.isEmpty()) chunk.copyOf(n) else carry + chunk.copyOf(n)
        val complete = utf8CompleteLen(data)
        val text = String(data, 0, complete, Charsets.UTF_8)
        carry = if (complete < data.size) data.copyOfRange(complete, data.size) else EMPTY
        return text
    }

    // ─────────────────────────── input ───────────────────────────
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        sendBytes(bytes)
    }

    fun sendBytes(bytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            SilentlyTry.fired("SshAi-Term", "write stdin") {
                stdin?.apply { write(bytes); flush() }
            }
        }
    }

    /** Arrow key honouring DECCKM (application-cursor-keys) mode: normal
     *  `ESC [ A`, application `ESC O A` — vim/readline need the distinction. */
    fun sendArrow(dir: Char) {
        val prefix = if (emulator.applicationCursorKeys) "\u001BO" else "\u001B["
        sendText(prefix + dir)
    }

    /** New viewport geometry: resize the emulator grid AND tell the remote
     *  PTY (SIGWINCH) so the shell/app reflows. */
    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (cols == emulator.cols && rows == emulator.rows) return
        emulator.resize(cols, rows)
        _screen.value = emulator.snapshot()
        scope.launch(Dispatchers.IO) {
            SilentlyTry.fired("SshAi-Term", "resize pty") {
                shellRef?.changeWindowDimensions(cols, rows, 0, 0)
            }
        }
    }

    fun close() {
        SilentlyTry.fired("SshAi-Term", "close session (explicit)") { session?.close() }
        session = null
        shellRef = null
        stdin = null
        started = false
        _connected.value = false
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /** Length of the longest UTF-8-complete prefix of [b] — i.e. cut off a
         *  dangling multi-byte sequence so we don't decode half a codepoint. */
        fun utf8CompleteLen(b: ByteArray): Int {
            if (b.isEmpty()) return 0
            var i = b.size - 1
            while (i >= 0 && (b[i].toInt() and 0xC0) == 0x80) i-- // skip continuation bytes
            if (i < 0) return b.size
            val x = b[i].toInt() and 0xFF
            val need = when {
                x and 0x80 == 0 -> 1
                x and 0xE0 == 0xC0 -> 2
                x and 0xF0 == 0xE0 -> 3
                x and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            return if (b.size - i >= need) b.size else i
        }
    }
}
