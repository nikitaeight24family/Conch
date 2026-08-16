package ai.eight24family.conch.agent

/**
 * Kill a server-side CLI turn this process does not own.
 *
 * Two callers, ONE implementation (2026-08-17 — they had drifted: the VM copy
 * stopped at TERM with no liveness re-check, the runner copy at TERM with a
 * different delay, and neither could say whether the turn actually died):
 *  - [AgentSessionRunOneShot.killZombieRemoteTurn] — Stop pressed on a zombie
 *    turn (we own the AgentSession but not the exec channel; the app was
 *    force-stopped mid-turn earlier).
 *  - ChatViewModel.stopMirroredRemoteTurn — Stop pressed on a purely mirrored
 *    turn (no app-side session at all; the chat paints from the file).
 *
 * The script is POSIX sh on purpose — it rides `bash -lc` through
 * [RemoteEnv.portable], whose no-bash fallback re-runs it under `sh -lc`.
 *
 * Discovery is BY ARGV: any process whose command line carries the resume id
 * AND names one of the CLIs. That finds `claude --resume <id>` (headless or
 * another device's REPL — killing the user's own PC session from the phone is
 * BY DESIGN, same as 36bfc4e) and `codex exec resume <id>`. It can NOT find a
 * `codex app-server` or `gemini --experimental-acp` serving the turn (no
 * session id in argv) or a console `claude` REPL that MINTED the session (no
 * --resume in argv) — for those the caller must tell the user the truth
 * instead of showing a button that silently does nothing.
 */
internal object RemoteTurnKiller {

    /** The id is interpolated into a shell script — accept only the UUID-ish
     *  shape every CLI actually mints. Same guard the /context fetch uses. */
    fun isKillableResumeId(id: String?): Boolean =
        id != null && Regex("^[a-fA-F0-9-]{16,40}$").matches(id)

    /** Sentinel lines the script prints — the ONLY protocol with the caller.
     *  Prefixed so a chatty login shell (motd, profile echo) can't fake them. */
    const val MARK_NONE = "CONCH_KILL_NONE"
    const val MARK_DONE = "CONCH_KILL_DONE:"
    const val MARK_SURVIVED = "CONCH_KILL_SURVIVED:"

    /**
     * Discovery + full INT→TERM→KILL ladder + post-ladder liveness check, all
     * server-side in ONE exec (three round-trips of `kill` from the phone over
     * a flaky link is exactly how the old ladder lost races).
     *
     * Every rung re-checks `kill -0` first, so a turn that died politely at
     * INT never eats a TERM, and only a genuine survivor meets KILL — the rung
     * the 08-11 version lacked, which is why a CLI wedged in an uninterruptible
     * write could shrug the whole ladder off and keep the spinner alive.
     *
     * The awk filter drops shell wrappers by argv[0] — including `/bin/bash`
     * and login `-bash`, which the old `$2 != "bash"` string-compare missed —
     * because the sshd-orphaned `bash -lc … claude --resume <id> …` wrapper
     * matches the argv probe too, and THIS VERY SCRIPT is such a wrapper.
     * The `pgrep -f` fallback (BusyBox pgrep has no `-a`) can't see argv[0],
     * so it excludes itself by PID instead; other matches there may include
     * wrappers, which is harmless — killing a wrapper alongside its CLI child
     * changes nothing, the danger was ever only killing the wrapper INSTEAD.
     */
    fun killScript(resumeId: String): String {
        require(isKillableResumeId(resumeId)) { "unsafe resume id" }
        return "rid='" + resumeId + "'; " +
            "pids=\$(pgrep -af \"\$rid\" 2>/dev/null | " +
            "awk '\$2 !~ /(^|\\/|-)((ba|da|a|z)?sh)\$/ && /(claude|codex|gemini)/ {print \$1}'); " +
            "if [ -z \"\$pids\" ]; then " +
            "pids=\$(pgrep -f \"(claude|codex|gemini).*\$rid\" 2>/dev/null | grep -v \"^\$\$\\\$\"); fi; " +
            // pgrep output is one pid PER LINE; the sentinel echo below quotes
            // $pids, so without this flatten the verdict line would carry only
            // the first pid and the rest would arrive as bare-number lines.
            "pids=\$(echo \$pids); " +
            "if [ -z \"\$pids\" ]; then echo $MARK_NONE; exit 0; fi; " +
            "kill -INT \$pids 2>/dev/null; sleep 1; " +
            "alive=''; for p in \$pids; do kill -0 \"\$p\" 2>/dev/null && alive=\"\$alive \$p\"; done; " +
            "if [ -n \"\$alive\" ]; then kill -TERM \$alive 2>/dev/null; sleep 1; fi; " +
            "alive=''; for p in \$pids; do kill -0 \"\$p\" 2>/dev/null && alive=\"\$alive \$p\"; done; " +
            "if [ -n \"\$alive\" ]; then kill -KILL \$alive 2>/dev/null; sleep 1; fi; " +
            "left=''; for p in \$pids; do kill -0 \"\$p\" 2>/dev/null && left=\"\$left \$p\"; done; " +
            "if [ -n \"\$left\" ]; then echo \"$MARK_SURVIVED\$left\"; " +
            "else echo \"$MARK_DONE\$pids\"; fi"
    }

    sealed interface Outcome {
        /** Every discovered process is confirmed dead (`kill -0` says gone). */
        data class Killed(val pids: List<Long>) : Outcome
        /** KILL was sent and something STILL answers `kill -0` — D-state or a
         *  PID race; the turn must be treated as running. */
        data class Survived(val pids: List<Long>) : Outcome
        /** No process carries this resume id — either the turn already ended
         *  or its owner never put the id in argv (app-server / ACP / REPL). */
        object NoneFound : Outcome
        /** The exec itself failed (transport down / channel refused). */
        object Unreachable : Outcome
    }

    fun parseOutcome(raw: String?): Outcome {
        if (raw == null) return Outcome.Unreachable
        for (line in raw.lineSequence()) {
            val t = line.trim()
            when {
                t == MARK_NONE -> return Outcome.NoneFound
                t.startsWith(MARK_DONE) -> return Outcome.Killed(pidsOf(t.removePrefix(MARK_DONE)))
                t.startsWith(MARK_SURVIVED) -> return Outcome.Survived(pidsOf(t.removePrefix(MARK_SURVIVED)))
            }
        }
        // Ran but printed no sentinel — a mangled shell (or truncated stream).
        // Claiming "killed" here would repaint over a live turn; Unreachable
        // keeps the spinner honest and the poll re-evaluates the file anyway.
        return Outcome.Unreachable
    }

    private fun pidsOf(s: String): List<Long> =
        s.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
}
