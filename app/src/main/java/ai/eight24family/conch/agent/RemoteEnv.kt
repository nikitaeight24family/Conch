package ai.eight24family.conch.agent

/**
 * PATH repair + shell portability for remote invocations.
 *
 * Debian's stock `~/.bashrc` starts with `[ -z "$PS1" ] && return`, so every
 * PATH addition written there — nvm, `~/.local/bin` (where npm's default user
 * prefix and therefore `claude` / `codex` / `gemini` land), `/usr/local/bin` —
 * is INVISIBLE to a non-interactive shell. `bash -lc` is a login shell but not
 * an interactive one, so it inherits none of it.
 *
 * The consequence is never a clean error: the command simply isn't found, the
 * output is empty, and whatever parsed that output concludes "nothing there"
 * instead of "I couldn't look". That mode has cost real debugging time twice —
 * the agent-status probe (which is why this preamble was written in the first
 * place) and the `/model` PTY probe, which launched `claude` WITHOUT it and got
 * a blank screen, leaving the picker on a stale list because every live probe
 * silently returned nothing (user, 2026-07-29).
 *
 * So: any command we run over SSH that invokes a USER-INSTALLED binary must
 * carry this preamble. Coreutils (`stat`, `tail`, `date`, `gzip`, `base64`)
 * don't need it; anything installed by npm/nvm/pipx does.
 *
 * ── Play-facing portability (2026-08-17) ── The app was measured against
 * exactly one server shape: glibc Linux + bash + GNU coreutils. Play users
 * bring whatever they have. The preamble therefore covers every mainstream
 * install location — Homebrew on macOS (whose /opt/homebrew/bin is NOT on a
 * non-interactive PATH), volta, bun, pnpm, asdf shims, snap — and [portable]
 * keeps a `bash -lc` command runnable on servers with NO bash at all
 * (Alpine/BusyBox containers, stock FreeBSD): `sh` is POSIX-guaranteed; the
 * script rides through as a positional parameter so it is never double-escaped.
 */
internal object RemoteEnv {

    /** Install locations worth prepending, most specific first. Idempotent,
     *  silent, cheap — a missing dir is simply skipped by the shell. */
    private const val EXTRA_DIRS =
        "\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:" +
            "\$HOME/.volta/bin:\$HOME/.bun/bin:\$HOME/.asdf/shims:" +
            "\$HOME/.local/share/pnpm:/snap/bin"

    /**
     * Multi-line form — prepend to a script body built with newlines.
     * Idempotent and silent; a missing nvm or node dir is simply skipped.
     */
    val PATH_PREAMBLE: String = """
        export PATH="$EXTRA_DIRS:${'$'}PATH"
        for nd in ${'$'}HOME/.nvm/versions/node/*/bin ${'$'}HOME/.local/node-*/bin ${'$'}HOME/.local/share/fnm/node-versions/*/installation/bin; do
            [ -d "${'$'}nd" ] && export PATH="${'$'}nd:${'$'}PATH"
        done
        [ -s "${'$'}HOME/.nvm/nvm.sh" ] && . "${'$'}HOME/.nvm/nvm.sh" >/dev/null 2>&1
    """.trimIndent() + "\n"

    /**
     * Single-line form — prepend to a script built with `;` separators.
     * Written out explicitly rather than derived from [PATH_PREAMBLE]: the
     * `for … do … done` block does not survive a naive line-join, and a shell
     * preamble that silently mis-parses would take the whole command with it.
     */
    val PATH_PREAMBLE_INLINE: String =
        "export PATH=\"$EXTRA_DIRS:${'$'}PATH\"; " +
            "for nd in ${'$'}HOME/.nvm/versions/node/*/bin ${'$'}HOME/.local/node-*/bin " +
            "${'$'}HOME/.local/share/fnm/node-versions/*/installation/bin; do " +
            "[ -d \"${'$'}nd\" ] && export PATH=\"${'$'}nd:${'$'}PATH\"; done; " +
            "[ -s \"${'$'}HOME/.nvm/nvm.sh\" ] && . \"${'$'}HOME/.nvm/nvm.sh\" >/dev/null 2>&1; "

    /**
     * Make a `bash -lc <escaped>` command survive a server with no bash.
     *
     * Every remote invocation in the app is composed as the literal prefix
     * `bash -lc ` + one shell-escaped token. On Alpine/BusyBox containers and
     * stock BSDs that dies with "bash: not found" — which, through SilentlyTry,
     * reads as "no sessions / agent not installed / empty output" rather than
     * an error. This rewrites the command at the TRANSPORT chokepoints to try
     * bash and fall back to plain `sh -l`: the script travels as `$1` (a
     * positional parameter, so the existing escaping is used exactly once), and
     * `sh` is guaranteed by POSIX. Scripts are already written in portable
     * sh — bash is only the LOGIN-shell vehicle — so the fallback loses
     * nothing but bashisms we never used.
     *
     * Commands not shaped `bash -lc <token>` pass through untouched.
     */
    fun portable(cmd: String): String {
        val prefix = "bash -lc "
        if (!cmd.startsWith(prefix)) return cmd
        val token = cmd.removePrefix(prefix)
        return "sh -c 'if command -v bash >/dev/null 2>&1; " +
            "then exec bash -lc \"\$1\"; else exec sh -lc \"\$1\"; fi' conch $token"
    }

    /**
     * POSIX stand-in for GNU `timeout`. macOS ships no `timeout` binary at
     * all, so every probe built as `timeout 40 claude …` silently printed
     * nothing there — the same "empty output reads as absence" failure mode
     * the header describes, five sites' worth (2026-08-17 sweep).
     *
     * Defines `conch_timeout <secs> <cmd…>`: uses the real `timeout` when
     * present (identical semantics), else backgrounds the command, arms a
     * sleep-and-kill watchdog, and returns the command's own exit code. The
     * emulation kills only the direct child (no process groups — POSIX sh
     * has no portable way), which is exactly what our probes need: a wedged
     * CLI's own process ending is what unblocks the exec channel.
     *
     * Prepend to any script that uses `conch_timeout`. Stdin piping works —
     * the backgrounded command inherits the function's stdin (the pipe).
     */
    val TIMEOUT_FN: String =
        "conch_timeout() { _t=\"\$1\"; shift; " +
            "if command -v timeout >/dev/null 2>&1; then timeout \"\$_t\" \"\$@\"; " +
            "else \"\$@\" & _c=\$!; ( sleep \"\$_t\"; kill \"\$_c\" 2>/dev/null ) & _w=\$!; " +
            "wait \"\$_c\"; _r=\$?; kill \"\$_w\" 2>/dev/null; return \$_r; fi; }; "
}
