package ai.eight24family.conch.agent.codex

/**
 * THE SHARED BRAIN — one `codex app-server` daemon, many clients.
 *
 * ## Why this exists
 *
 * codex allows ONE writer per thread and hands it over only when a process
 * exits (no thread-level release; `thread/unsubscribe` moves nothing, measured
 * on 0.153.4, and openai/codex#37403 / #41849 / #38297 are other clients filing
 * the same complaint). That is why continuing on the phone a session a terminal
 * holds has to END that terminal's copy — see `SessionHolder.relayedNote`.
 *
 * It only has to end it because the two sides are two SEPARATE app-servers.
 * codex ships the way out of that, and the owner asked for it by name
 *
 *  - `codex app-server daemon start` — one long-lived app-server per user.
 *  - `codex app-server proxy` — pipe our stdio into that daemon's control
 *    socket, so OUR channel is a client of it rather than a second server.
 *  - `codex --remote unix://` — the TUI does the same, from his terminal.
 *
 * With both sides on one daemon there is one writer (the daemon), two views,
 * and nobody has to end anybody. The bare `codex` in a terminal is what makes
 * the relay necessary, and that is exactly what the advice dialog explains.
 *
 * ## Why every step is probed and nothing is assumed
 *
 * His own 824 box runs **codex 0.80.0** (measured 2026-09-12) — no `daemon`, no
 * `proxy`, no `--remote`. A newer server has all three. So the capability is
 * asked for, per server, and everything degrades to the old single-process
 * launch in silence. `daemon` lifecycle is Unix-only (the CLI says so out loud
 * on Windows), which costs nothing here: every agent server is Linux.
 */
internal object CodexDaemon {

    /**
     * ⛔ OFF UNTIL CONCH CAN SPEAK WEBSOCKET. MEASURED 2026-09-12 on the owner's
     * server, codex 0.154.0 installed standalone in an isolated CODEX_HOME:
     *
     *  - `codex app-server daemon start` WORKS — it answers
     *    `{"status":"started","socketPath":"…/app-server-control.sock",
     *    "appServerVersion":"0.154.0"}` and runs `app-server --listen unix://`.
     *    It requires the STANDALONE install (`chatgpt.com/codex/install.sh`);
     *    an npm-installed codex refuses with "managed standalone Codex install
     *    not found", so updating through npm would never have produced a daemon.
     *  - `codex app-server proxy` DOES NOT SPEAK THAT SOCKET'S PROTOCOL. A
     *    unix-socket spy between the two captured our `initialize` arriving as
     *    plain JSONL and the server answering NOTHING: the listener wants a
     *    WebSocket upgrade ("failed to upgrade control socket websocket
     *    connection" is in the binary), and proxy forwards raw bytes.
     *
     * ⛔ AND SHIPPING THE HALF THAT WORKS WOULD BE WORSE THAN SHIPPING NOTHING.
     * The shell hook would send his terminal into the daemon; the app cannot
     * join that daemon; and the daemon is HEADLESS, which the relay is forbidden
     * to reap (it would kill his terminal with it). The phone would then be
     * unable to continue its own session at all — a dead end built out of two
     * correct pieces.
     *
     * The way in is `app-server --listen ws://IP:PORT` (supported, per its own
     * --help) plus an SSH port-forward and a WebSocket client in the app. Until
     * that exists: the relay, which works today.
     */
    const val SHARED_BRAIN_ENABLED = false

    private const val P = "CONCH_CODEXD_"

    /**
     * What this server's codex can do. [sharedBrain] is the only combination
     * that buys anything: a daemon we can start, a proxy to reach it, and a TUI
     * flag so his terminal can join the same one.
     */
    data class Support(
        val version: String = "",
        val hasDaemon: Boolean = false,
        val hasProxy: Boolean = false,
        val hasRemoteTui: Boolean = false,
    ) {
        /** Capability of the BINARY. [usable] is what the app may act on. */
        val sharedBrain: Boolean get() = hasDaemon && hasProxy && hasRemoteTui

        /** What the app is allowed to do with it — see [SHARED_BRAIN_ENABLED]. */
        val usable: Boolean get() = SHARED_BRAIN_ENABLED && sharedBrain
    }

    /**
     * ONE exec: version + the three capabilities, read from the binary's own
     * `--help` rather than from a version comparison. Version numbers are for
     * the message shown to a human; a feature is present when the CLI says it
     * is (feedback_cli_is_source_of_truth).
     */
    fun probeScript(): String =
        "v=\$(codex --version 2>/dev/null | tr -d '\\r'); " +
            "h=\$(codex app-server --help 2>/dev/null); " +
            "t=\$(codex --help 2>/dev/null); " +
            "echo \"${P}VER:\$v\"; " +
            "case \"\$h\" in *' daemon'*|*'daemon '*) echo \"${P}DAEMON:1\";; *) echo \"${P}DAEMON:0\";; esac; " +
            "case \"\$h\" in *proxy*) echo \"${P}PROXY:1\";; *) echo \"${P}PROXY:0\";; esac; " +
            "case \"\$t\" in *'--remote '*|*'--remote<'*) echo \"${P}REMOTE:1\";; *) echo \"${P}REMOTE:0\";; esac; " +
            "exit 0"

    fun parseProbe(raw: String?): Support? {
        if (raw.isNullOrBlank()) return null
        var seen = false
        var v = ""
        var d = false
        var p = false
        var r = false
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith(P)) continue
            seen = true
            val body = t.removePrefix(P)
            when {
                // `codex --version` answers "codex-cli 0.80.0" — the dialog says
                // "this server runs codex <v>", so keep the number, drop the
                // product name it would otherwise say twice.
                body.startsWith("VER:") ->
                    v = body.removePrefix("VER:").trim().substringAfterLast(' ')
                body.startsWith("DAEMON:") -> d = body.endsWith("1")
                body.startsWith("PROXY:") -> p = body.endsWith("1")
                body.startsWith("REMOTE:") -> r = body.endsWith("1")
            }
        }
        // No marker at all = the exec did not run (dead channel, mangled login
        // shell). That is NOT "no support" — same rule as SessionHolder's
        // Unreachable: a probe that failed is evidence of nothing.
        return if (seen) Support(v, d, p, r) else null
    }

    /**
     * Bring the daemon up, idempotently, and say whether it is there.
     *
     * `daemon start` is documented as "start … if it is not already running",
     * so this is safe to run before every launch; `daemon version` is the proof
     * (it prints the RUNNING app-server's version), which is what keeps a
     * failed start from silently becoming a channel that never answers.
     *
     * ⛔ Deliberately NOT `bootstrap`. That one installs durable management
     * (service files, autostart) — a persistent change to his box, and this is
     * allowed to be silent only because it is not one: the daemon is an
     * ordinary process he can stop with `codex app-server daemon stop`.
     */
    fun startScript(): String =
        "codex app-server daemon start >/dev/null 2>&1; " +
            "codex app-server daemon version >/dev/null 2>&1 && echo \"${P}UP:1\" || echo \"${P}UP:0\"; " +
            "exit 0"

    fun parseStart(raw: String?): Boolean = raw?.lineSequence()?.any { it.trim() == "${P}UP:1" } == true

    /** Our channel, as a CLIENT of the daemon. stderr dropped for the same
     *  reason the direct app-server launch drops it: any line on stdout's
     *  sibling breaks JSONL framing. */
    const val CHANNEL_COMMAND = "codex app-server proxy 2>/dev/null"

    /** What HE types in the terminal so his side joins the same daemon instead
     *  of starting a second app-server. `unix://` with no path means the
     *  default socket — nothing to look up, nothing to get wrong. */
    const val TERMINAL_COMMAND = "codex --remote unix://"

    /** Last-resort text, shown only when the app could not do it itself. */
    const val UPDATE_COMMAND = "npm install -g @openai/codex@latest"

    // ── The terminal side, without the user memorising a flag ──────────────

    const val MARK_BEGIN = "# >>> conch: join the shared codex app-server >>>"
    const val MARK_END = "# <<< conch <<<"

    /**
     * TEACH HIS SHELL THE FLAG, so plain `codex` joins the daemon.
     *
     * "Type `codex --remote unix://` from now on" is an instruction, and an app
     * shipped to strangers cannot hand out instructions — it has to do the
     * thing. So the app writes one idempotent block into the rc file of
     * whatever shell that account actually uses, and the block is a wrapper
     * function, not an alias.
     *
     * ⛔ THE WRAPPER MUST NOT SWALLOW OUR OWN CALLS. Conch itself runs
     * `codex app-server proxy`, `codex exec`, `codex --version` over this same
     * account. A blanket alias would rewrite every one of them into
     * `codex --remote unix:// app-server proxy` and break the app on the spot.
     * Hence three conditions, all required: an interactive stdout, NO arguments
     * (the bare `codex` a person types), and a daemon that actually answers.
     * Anything else falls through to `command codex "$@"` untouched — including
     * the case where the daemon is down, which then starts a normal private
     * app-server exactly as before.
     *
     * Idempotent by marker, so re-running it is a no-op rather than a pile of
     * duplicate functions, and [removeHookScript] takes it back out cleanly.
     */
    fun installHookScript(): String =
        "rc=''; sh=\"\$(basename \"\${SHELL:-sh}\")\"; " +
            "case \"\$sh\" in " +
            "bash) rc=\"\$HOME/.bashrc\" ;; " +
            "zsh) rc=\"\${ZDOTDIR:-\$HOME}/.zshrc\" ;; " +
            "fish) rc=\"\$HOME/.config/fish/config.fish\" ;; " +
            // ksh reads $ENV; dash/sh/ash read ~/.profile on a login shell.
            // Both take the same POSIX function body as bash, so they cost one
            // case arm each — and they are what a minimal container or a BSD
            // box actually ships (there is no "everyone runs bash" server).
            "ksh|ksh93|mksh) rc=\"\${ENV:-\$HOME/.kshrc}\" ;; " +
            "sh|dash|ash) rc=\"\$HOME/.profile\" ;; " +
            "esac; " +
            "[ -z \"\$rc\" ] && { echo \"${P}HOOK:unsupported:\$sh\"; exit 0; }; " +
            "mkdir -p \"\$(dirname \"\$rc\")\" 2>/dev/null; " +
            "if [ -f \"\$rc\" ] && grep -qF '" + MARK_BEGIN + "' \"\$rc\"; then " +
            "echo \"${P}HOOK:present\"; exit 0; fi; " +
            "{ echo ''; echo '" + MARK_BEGIN + "'; " +
            "if [ \"\$sh\" = fish ]; then " +
            "echo 'function codex'; " +
            "echo '  if isatty stdout; and test (count \$argv) -eq 0; " +
            "and command codex app-server daemon version >/dev/null 2>&1'; " +
            "echo '    command codex --remote unix://'; " +
            "echo '  else'; " +
            "echo '    command codex \$argv'; " +
            "echo '  end'; " +
            "echo 'end'; " +
            "else " +
            "echo 'codex() {'; " +
            "echo '  if [ -t 1 ] && [ \"\$#\" -eq 0 ] && " +
            "command codex app-server daemon version >/dev/null 2>&1; then'; " +
            "echo '    command codex --remote unix://'; " +
            "echo '  else'; " +
            "echo '    command codex \"\$@\"'; " +
            "echo '  fi'; " +
            "echo '}'; " +
            "fi; " +
            "echo '" + MARK_END + "'; } >> \"\$rc\" && " +
            "echo \"${P}HOOK:added:\$rc\" || echo \"${P}HOOK:failed\"; " +
            "exit 0"

    /** Take the block back out — same markers, so nothing else in the rc file
     *  is touched. Offered wherever the hook is offered: what an app installs,
     *  it must be able to uninstall. */
    fun removeHookScript(): String =
        "rc=''; sh=\"\$(basename \"\${SHELL:-sh}\")\"; " +
            "case \"\$sh\" in " +
            "bash) rc=\"\$HOME/.bashrc\" ;; " +
            "zsh) rc=\"\${ZDOTDIR:-\$HOME}/.zshrc\" ;; " +
            "fish) rc=\"\$HOME/.config/fish/config.fish\" ;; " +
            "ksh|ksh93|mksh) rc=\"\${ENV:-\$HOME/.kshrc}\" ;; " +
            "sh|dash|ash) rc=\"\$HOME/.profile\" ;; " +
            "esac; " +
            "[ -z \"\$rc\" ] || [ ! -f \"\$rc\" ] && { echo \"${P}HOOK:absent\"; exit 0; }; " +
            "sed -i.conch-bak '/" + "conch: join the shared codex app-server" + "/,/<<< conch <<</d' \"\$rc\" && " +
            "echo \"${P}HOOK:removed\" || echo \"${P}HOOK:failed\"; " +
            "exit 0"

    /** `added` / `present` both mean the shell will join from now on. */
    fun parseHook(raw: String?): String? = raw?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("${P}HOOK:") }
        ?.removePrefix("${P}HOOK:")
}
