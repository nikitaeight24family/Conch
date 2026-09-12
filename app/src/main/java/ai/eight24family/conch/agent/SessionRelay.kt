package ai.eight24family.conch.agent

import ai.eight24family.conch.util.SilentlyTry

/**
 * THE RELAY, FOR EVERY AGENT — not just the two with a persistent channel.
 *
 * Conch ships ten CLIs (claude, codex, gemini, grok, copilot, qwen,
 * cursor-agent, opencode, crush, cn). Two of them grew the "who else has this
 * session open" question first, because their long-lived channels made the
 * collision loud: codex refuses a second writer out loud, Claude accepts one and
 * quietly forks the history. The other eight run through the one-shot path and
 * had NO protection at all — the phone resumed a session someone's terminal was
 * sitting in and both sides appended to it, which is the same corruption with no
 * error message.
 *
 * So the policy lives here, once, in the agent-agnostic terms [SessionHolder]
 * already speaks:
 *
 *  - a HEADLESS holder is our own orphan → reaped silently, always;
 *  - a TTY holder is a person's terminal → ended only when the owner SENT
 *    something into this chat (that send is the claim), and said so in one line;
 *  - anything unprovable (probe [SessionHolder.Probe.Unreachable], an unsafe id,
 *    a CLI whose name cannot be a `pgrep` pattern) → carry on exactly as before.
 *    Acting on a guess here is what produced the 2026-07..09 phantom sessions.
 *
 * ⛔ WHAT THIS CANNOT SEE, AND WHY IT IS STILL WORTH RUNNING. The generic probe
 * has the fd and argv layers, not the cwd one: the cwd layer needs to know where
 * that CLI stores a session (`ClaudeSessionLock` has it for Claude), and for
 * eight CLIs nobody has measured that. So a bare REPL that minted its own
 * session may go unseen — the answer is then "no holder", which is exactly the
 * behaviour these agents had before. A floor, never a ceiling.
 */
internal object SessionRelay {

    /**
     * Ensure nothing else holds [sessionId] before this chat resumes it.
     *
     * [userInitiated] gates the destructive half — see the twins in
     * `AgentSessionCodexAppServer.ensureReady` and
     * `AgentSessionPersistentStream.ensureProcess`. Returns the holders that
     * survived: empty means "go ahead".
     */
    suspend fun claim(
        cli: String,
        sessionId: String?,
        userInitiated: Boolean,
        exec: suspend (String) -> String?,
        reap: suspend (List<Long>) -> Unit,
        note: (String) -> Unit,
    ): List<SessionHolder.Holder> {
        if (!SessionHolder.canProbe(sessionId, cli)) return emptyList()
        val id = sessionId ?: return emptyList()
        val probe = SilentlyTry.logged("Conch-Relay", "probe holders of $id") {
            SessionHolder.parseProbe(exec(SessionHolder.probeScript(id, cli)))
        } ?: return emptyList()
        val holders = (probe as? SessionHolder.Probe.Held)?.holders ?: return emptyList()

        val ours = holders.filter { it.kind == SessionHolder.Kind.HEADLESS }
        if (ours.isNotEmpty()) {
            // Our own orphan: a CLI whose SSH channel died while the remote
            // process lived on. Silent — it is our mess (feedback_auto_fix_errors).
            android.util.Log.w("Conch-Relay", "reaping our orphans ${ours.map { it.pid }} of $id")
            reap(ours.map { it.pid })
        }
        val tty = holders.filter { it.kind == SessionHolder.Kind.TTY }
        if (tty.isEmpty()) return emptyList()
        if (!userInitiated) return tty

        android.util.Log.i("Conch-Relay", "relay: ending ${tty.map { it.pid }} holding $id")
        reap(tty.map { it.pid })
        val after = SilentlyTry.logged("Conch-Relay", "re-probe holders of $id") {
            SessionHolder.parseProbe(exec(SessionHolder.probeScript(id, cli)))
        }
        val left = (after as? SessionHolder.Probe.Held)?.holders
            ?.filter { it.kind == SessionHolder.Kind.TTY }
            .orEmpty()
        // ⛔ Report only what actually happened. A kill that did not land must
        // not print "continued here" — the caller decides what to do with the
        // survivors, and for a one-shot CLI that means carrying on rather than
        // blocking (it has no lock to violate; the risk it runs is the same one
        // it ran before this file existed).
        if (left.isEmpty()) note(SessionHolder.relayedNote(tty, cli))
        return left
    }
}
