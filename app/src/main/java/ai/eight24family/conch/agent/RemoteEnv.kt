package ai.eight24family.conch.agent

/**
 * PATH repair for remote `bash -lc` invocations.
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
 */
internal object RemoteEnv {

    /**
     * Multi-line form — prepend to a script body built with newlines.
     * Idempotent and silent; a missing nvm or node dir is simply skipped.
     */
    val PATH_PREAMBLE: String = """
        export PATH="${'$'}HOME/.local/bin:/usr/local/bin:${'$'}PATH"
        for nd in ${'$'}HOME/.nvm/versions/node/*/bin ${'$'}HOME/.local/node-*/bin; do
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
        "export PATH=\"${'$'}HOME/.local/bin:/usr/local/bin:${'$'}PATH\"; " +
            "for nd in ${'$'}HOME/.nvm/versions/node/*/bin ${'$'}HOME/.local/node-*/bin; do " +
            "[ -d \"${'$'}nd\" ] && export PATH=\"${'$'}nd:${'$'}PATH\"; done; " +
            "[ -s \"${'$'}HOME/.nvm/nvm.sh\" ] && . \"${'$'}HOME/.nvm/nvm.sh\" >/dev/null 2>&1; "
}
