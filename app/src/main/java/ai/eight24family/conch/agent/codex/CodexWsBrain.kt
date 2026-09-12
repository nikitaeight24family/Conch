package ai.eight24family.conch.agent.codex

/**
 * THE SHARED BRAIN, FOR REAL THIS TIME — one `codex app-server` on a loopback
 * WebSocket, many clients.
 *
 * ## What was measured (owner's server, codex 0.151.0, 2026-09-12)
 *
 * The first attempt went through `codex app-server daemon start` +
 * `app-server proxy` and failed: the daemon needs a STANDALONE install, and
 * `proxy` forwards raw JSONL into a listener that wants a WebSocket upgrade, so
 * it answered nothing (see [CodexDaemon], kept for that record).
 *
 * `app-server --listen ws://IP:PORT` is the door that works, and it works on an
 * ORDINARY npm install — no vendor installer, nothing to update:
 *
 *  - a hand-written RFC6455 client got `HTTP/1.1 101 Switching Protocols`,
 *    then `initialize`, then `thread/start` — the full app-server API rides the
 *    socket unchanged;
 *  - ⛔ **AND TWO CLIENTS SHARE ONE THREAD.** Client A resumed a real rollout;
 *    client B resumed the SAME thread while A was still connected and got a
 *    normal result — no "already has an active writer". The writer lock is
 *    between app-server PROCESSES, not between clients of one. That is the
 *    whole feature: the phone and his terminal hold one session, and nobody has
 *    to be ended.
 *
 * ## Why loopback, and why the app never opens a port
 *
 * The server binds `127.0.0.1` only, so nothing is exposed on his network (the
 * CLI's own `--websocket-auth-mode` exists precisely for non-loopback listeners;
 * we stay off that road). The app reaches it through the SSH transport it
 * already has — a direct-tcpip channel to `127.0.0.1:<port>` — so the phone
 * opens no listening socket either, and the bytes never leave the encrypted
 * channel.
 *
 * ## Lifetime
 *
 * Started with `setsid nohup` so it OUTLIVES the SSH channel that spawned it —
 * a brain that dies with the phone's connection is not shared with anything.
 * Never reaped: the relay's kill-the-holder rule must not touch it, which is
 * why the probe classifies it as headless and the daemon guard exists.
 */
internal object CodexWsBrain {

    private const val P = "CONCH_CODEXWS_"

    /** Where the chosen port is recorded, so the shell hook and the app agree
     *  without either of them guessing. */
    const val PORT_FILE = "\$HOME/.conch/codex-ws.port"

    /** First port tried; the script walks upward if it is taken by something
     *  else (another user's server, an unrelated service). */
    const val DEFAULT_PORT = 47100

    data class Brain(val port: Int, val startedNow: Boolean)

    /**
     * Ensure a shared app-server is listening, and say on which port.
     *
     * Idempotent: an already-running brain is reused (that is the point), and
     * the port file makes the choice stable across launches. POSIX sh only —
     * same `sh -lc` fallback discipline as the holder probe.
     */
    fun ensureScript(): String =
        "mkdir -p \$HOME/.conch 2>/dev/null; " +
            "port=\$(cat $PORT_FILE 2>/dev/null); " +
            "case \"\$port\" in ''|*[!0-9]*) port=$DEFAULT_PORT ;; esac; " +
            // Already ours and alive? Then nothing to do — reuse it.
            "alive() { pgrep -f \"app-server --listen ws://127.0.0.1:\$1\" >/dev/null 2>&1; }; " +
            "taken() { (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q \"127.0.0.1:\$1 \"; }; " +
            "if alive \"\$port\"; then echo \"${P}PORT:\$port\"; echo \"${P}STARTED:0\"; exit 0; fi; " +
            // Port occupied by something that is not ours — walk up.
            "n=0; while taken \"\$port\" && [ \$n -lt 20 ]; do port=\$((port+1)); n=\$((n+1)); done; " +
            "if taken \"\$port\"; then echo \"${P}ERR:noport\"; exit 0; fi; " +
            "command -v codex >/dev/null 2>&1 || { echo \"${P}ERR:nocodex\"; exit 0; }; " +
            // ⛔ ASK THE BINARY, NOT THE VERSION NUMBER. `--listen` is absent on
            // older codex (0.80 on his own /usr/bin), and a silent failure here
            // would look like a dead brain instead of an old CLI.
            "codex app-server --help 2>&1 | grep -q -- '--listen' || " +
            "{ echo \"${P}ERR:nolisten\"; exit 0; }; " +
            // setsid + nohup: the brain must outlive this exec channel.
            "setsid nohup codex app-server --listen ws://127.0.0.1:\$port " +
            ">\$HOME/.conch/codex-ws.log 2>&1 </dev/null & " +
            "i=0; while [ \$i -lt 30 ]; do " +
            "  if taken \"\$port\"; then break; fi; " +
            "  i=\$((i+1)); sleep 0.3; " +
            "done; " +
            "if taken \"\$port\"; then " +
            "  printf '%s' \"\$port\" > $PORT_FILE; " +
            "  echo \"${P}PORT:\$port\"; echo \"${P}STARTED:1\"; " +
            "else echo \"${P}ERR:nostart\"; fi; " +
            "exit 0"

    fun parseEnsure(raw: String?): Brain? {
        if (raw == null) return null
        var port: Int? = null
        var started = false
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith(P)) continue
            when {
                t.startsWith("${P}PORT:") -> port = t.removePrefix("${P}PORT:").trim().toIntOrNull()
                t.startsWith("${P}STARTED:") -> started = t.endsWith("1")
                t.startsWith("${P}ERR:") -> {
                    android.util.Log.w("Conch-CodexWs", "shared brain unavailable: $t")
                    return null
                }
            }
        }
        return port?.let { Brain(it, started) }
    }

    /**
     * REACH THE BRAIN THROUGH AN EXEC CHANNEL, NOT A FORWARDED PORT.
     *
     * ⛔ `direct-tcpip` IS NOT AVAILABLE ON REAL SERVERS. Measured on the
     * owner's box 2026-09-12: sshd there carries
     * `AllowTcpForwarding local` + `PermitOpen 127.0.0.1:1455 … 19800 … 19801`,
     * so a channel to 127.0.0.1:47100 is refused outright ("open failed") —
     * and that is a perfectly ordinary hardening, not a misconfiguration. An
     * app that ships to strangers cannot require an sshd edit before it works,
     * and must never ask anyone to widen `PermitOpen` for it.
     *
     * So the bytes ride an ordinary exec channel with a one-line relay at the
     * far end: stdin → the loopback socket, the socket → stdout. Every tool in
     * the cascade is a plain TCP pipe; the first one present wins, and python3
     * is the floor because a box with none of the others still has it.
     * stderr is dropped — a single warning line would corrupt the WebSocket
     * framing exactly as it corrupts JSONL.
     */
    fun bridgeCommand(port: Int): String =
        // ⛔ ORDER MATTERS, AND PLAIN `nc` IS LAST. Measured 2026-09-12: the
            // OpenBSD netcat closes the socket the moment its stdin reports
            // EOF and exits 0 — the app saw exactly that ("shared brain closed
            // the socket (bridge exit=0)") right after a good handshake. `-q -1`
            // tells it to hold the socket; socat and ncat never had the habit.
        "if command -v socat >/dev/null 2>&1; then exec socat - TCP:127.0.0.1:$port 2>/dev/null; " +
            "elif command -v ncat >/dev/null 2>&1; then exec ncat 127.0.0.1 $port 2>/dev/null; " +
            "elif nc -h 2>&1 | grep -q -- '-q'; then exec nc -q -1 127.0.0.1 $port 2>/dev/null; " +
            "else exec python3 -c \"" +
            "import socket,sys,threading;" +
            "s=socket.create_connection(('127.0.0.1',$port));" +
            "t=threading.Thread(target=lambda:[(sys.stdout.buffer.write(d),sys.stdout.buffer.flush()) " +
            "for d in iter(lambda:s.recv(65536),b'')],daemon=True);t.start();" +
            "[s.sendall(d) for d in iter(lambda:sys.stdin.buffer.read1(65536),b'')]" +
            "\" 2>/dev/null; fi"

    /** What a person types in the terminal to join the same brain. */
    fun terminalCommand(port: Int): String = "codex --remote ws://127.0.0.1:$port"

    /**
     * The shell hook, rewritten for the ws brain — same three gates as the
     * earlier unix-socket version, and the same reason for them: Conch itself
     * runs `codex exec`, `codex --version` and more over this account, so the
     * wrapper must touch ONLY a bare interactive `codex` typed by a person,
     * and only when the brain is actually up.
     */
    fun installHookScript(port: Int): String {
        val begin = CodexDaemon.MARK_BEGIN
        val end = CodexDaemon.MARK_END
        return "rc=''; sh=\"\$(basename \"\${SHELL:-sh}\")\"; " +
            "case \"\$sh\" in " +
            "bash) rc=\"\$HOME/.bashrc\" ;; " +
            "zsh) rc=\"\${ZDOTDIR:-\$HOME}/.zshrc\" ;; " +
            "fish) rc=\"\$HOME/.config/fish/config.fish\" ;; " +
            "ksh|ksh93|mksh) rc=\"\${ENV:-\$HOME/.kshrc}\" ;; " +
            "sh|dash|ash) rc=\"\$HOME/.profile\" ;; " +
            "esac; " +
            "[ -z \"\$rc\" ] && { echo \"${P}HOOK:unsupported:\$sh\"; exit 0; }; " +
            "mkdir -p \"\$(dirname \"\$rc\")\" 2>/dev/null; " +
            // Replace an older block rather than stacking a second one: the port
            // can change, and a stale wrapper would point at a dead brain.
            "if [ -f \"\$rc\" ] && grep -qF '$begin' \"\$rc\"; then " +
            "  sed -i.conch-bak '/conch: join the shared codex app-server/,/<<< conch <<</d' \"\$rc\" 2>/dev/null; " +
            "fi; " +
            "{ echo ''; echo '$begin'; " +
            "if [ \"\$sh\" = fish ]; then " +
            "echo 'function codex'; " +
            "echo '  if isatty stdout; and test (count \$argv) -eq 0; " +
            "and nc -z 127.0.0.1 $port 2>/dev/null'; " +
            "echo '    command codex --remote ws://127.0.0.1:$port'; " +
            "echo '  else'; " +
            "echo '    command codex \$argv'; " +
            "echo '  end'; " +
            "echo 'end'; " +
            "else " +
            "echo 'codex() {'; " +
            "echo '  if [ -t 1 ] && [ \"\$#\" -eq 0 ] && " +
            "(exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then'; " +
            "echo '    exec 3<&- 3>&-; command codex --remote ws://127.0.0.1:$port'; " +
            "echo '  else'; " +
            "echo '    command codex \"\$@\"'; " +
            "echo '  fi'; " +
            "echo '}'; " +
            "fi; " +
            "echo '$end'; } >> \"\$rc\" && " +
            "echo \"${P}HOOK:added:\$rc\" || echo \"${P}HOOK:failed\"; " +
            "exit 0"
    }

    fun parseHook(raw: String?): String? = raw?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("${P}HOOK:") }
        ?.removePrefix("${P}HOOK:")
}
