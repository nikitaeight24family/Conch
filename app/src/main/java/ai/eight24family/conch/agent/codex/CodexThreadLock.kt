package ai.eight24family.conch.agent.codex

/**
 * Who is holding a Codex thread open — and is it OUR garbage or the owner's
 * live terminal?
 *
 * ## Why this exists
 *
 * codex 0.153 put a WRITER LOCK on every thread (`~/.codex/thread_history_*
 * .sqlite`). A second opener of the same thread is refused:
 *
 * ```
 * {"error":{"code":-32600,
 *   "message":"thread <id> already has an active writer"}}
 * ERROR codex_core::session: thread-store conflict: thread <id> already has
 *   an active writer
 * ```
 *
 * That message means **BUSY**, not gone. The app used to read it through
 * `looksLikeDeadSession` ("thread/resume failed" matched), conclude the
 * session was dead, drop the resume id and send the prompt into a BRAND-NEW
 * EMPTY THREAD — which is what the owner and a Reddit reporter both saw as
 * "it doubles the session instead of continuing it, with no context"
 * (2026-09-09, verified end to end on the owner's server: app-server refused
 * `thread/resume` at 18:33:11 and a fresh `codex_exec` rollout appeared at
 * 18:33:18, seven seconds later).
 *
 * ## Why argv discovery could not answer it
 *
 * [ai.eight24family.conch.agent.RemoteTurnKiller] finds processes by the
 * session id in their command line, and says so in its own kdoc: it can NOT
 * see a `codex app-server` or an interactive REPL, because neither puts the
 * id in argv. The writer holder is exactly one of those. So discovery here is
 * by the FILE: the process whose `/proc/<pid>/fd` still points at the
 * thread's rollout is the writer, whatever its argv says.
 *
 * ## Why the holder's KIND decides everything
 *
 * Measured on the owner's server the same night — the holder was pid 1051364
 * with `fd/0`, `fd/1`, `fd/2` all on `/dev/pts/0` and an interactive `-bash`
 * for a parent: **the owner's own codex TUI**, not a leak of ours. Conch's
 * channels ride an sshd exec channel, so their fds are PIPES. That one
 * readlink separates the two cases, and they need opposite handling:
 *
 *  - **headless holder** (pipes) — our own orphan: an app-server whose SSH
 *    channel died while the remote process lived on. Ours to clean up, so we
 *    kill it and retry the resume, silently (feedback_auto_fix_errors).
 *  - **tty holder** (`/dev/pts/N`) — a live session a person is sitting in.
 *    NEVER killed behind their back. We keep the chat bound to the real
 *    thread and say where it is held, so the next send resumes for real
 *    instead of forking a ghost.
 */
internal object CodexThreadLock {

    /** Sentinels — the ONLY protocol with the caller. Prefixed so a chatty
     *  login shell (motd, profile echo) cannot fake them. */
    const val MARK_FREE = "CONCH_LOCK_FREE"
    const val MARK_HOLDER = "CONCH_LOCK_HOLDER:"

    /**
     * Stable id of the "held elsewhere" row, so the chat can hang the
     * take-over action on it. Same mechanism the history-window marker uses
     * (ChatMessageLines dispatches a tap by marker id) — a note that offers a
     * way out beats a new message type, and beats bending an approval card
     * into an app-internal decision.
     *
     * One id, so a chat that hits the lock three times shows ONE row.
     */
    const val TAKEOVER_MARKER_ID = "conch-codex-thread-locked"

    /** The id is interpolated into a shell script — accept only the UUID-ish
     *  shape codex actually mints. Same guard as [RemoteTurnKiller]'s. */
    fun isSafeThreadId(id: String?): Boolean =
        id != null && Regex("^[a-fA-F0-9-]{16,40}$").matches(id)

    /**
     * Does this failure mean "someone else has it open" rather than "it is
     * gone"? Matched on codex's own wording, both the JSON-RPC message and
     * the `codex_core` log line, plus the TUI's bootstrap wrapper.
     */
    fun isWriterConflict(text: String?): Boolean {
        val t = text?.lowercase() ?: return false
        return "already has an active writer" in t || "thread-store conflict" in t
    }

    enum class Kind {
        /** fds on a pts/tty — a person's interactive session. Hands off. */
        TTY,

        /** fds on pipes — a headless opener, i.e. one of ours that outlived
         *  its SSH channel. Safe to reap. */
        HEADLESS,
    }

    data class Holder(
        val pid: Long,
        val kind: Kind,
        /** `/dev/pts/0`, or the pipe target — verbatim, for the message. */
        val stream: String,
        /** `ps -o lstart=` of the holder, e.g. "Wed Sep 9 01:30:10 2026". */
        val since: String,
    )

    /**
     * Find every process holding the rollout of [threadId] open, in ONE exec.
     *
     * `pgrep -f codex` narrows the scan (a writer is always a codex process)
     * and the `/proc/<pid>/fd` listing is the actual evidence. `readlink` on
     * `fd/1` classifies the holder. POSIX sh — no bashisms — so it survives
     * the `sh -lc` fallback for servers without bash.
     */
    fun probeScript(threadId: String): String {
        require(isSafeThreadId(threadId)) { "unsafe thread id" }
        return "tid='" + threadId + "'; " +
            "hit=''; " +
            "for p in \$(pgrep -f codex 2>/dev/null); do " +
            "[ \"\$p\" = \"\$\$\" ] && continue; " +
            "ls -l \"/proc/\$p/fd\" 2>/dev/null | grep -q \"\$tid\" || continue; " +
            "s=\$(readlink \"/proc/\$p/fd/1\" 2>/dev/null); " +
            "case \"\$s\" in /dev/pts/*|/dev/tty*) k=TTY ;; *) k=HEADLESS ;; esac; " +
            "st=\$(ps -o lstart= -p \"\$p\" 2>/dev/null | sed 's/^ *//'); " +
            "echo \"$MARK_HOLDER\$p|\$k|\$s|\$st\"; " +
            "hit=1; " +
            "done; " +
            "[ -z \"\$hit\" ] && echo $MARK_FREE; " +
            "exit 0"
    }

    /**
     * Nobody holds it (so the refusal was something else and must NOT be
     * treated as a writer conflict), or here is who does. [Unreachable] when
     * the exec itself failed — never confused with "free", because acting on
     * a guess here is what created the ghost sessions.
     */
    sealed interface Probe {
        object Free : Probe
        data class Held(val holders: List<Holder>) : Probe
        object Unreachable : Probe
    }

    fun parseProbe(raw: String?): Probe {
        if (raw == null) return Probe.Unreachable
        val holders = ArrayList<Holder>()
        var sawFree = false
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t == MARK_FREE) { sawFree = true; continue }
            if (!t.startsWith(MARK_HOLDER)) continue
            val f = t.removePrefix(MARK_HOLDER).split('|')
            val pid = f.getOrNull(0)?.trim()?.toLongOrNull() ?: continue
            holders += Holder(
                pid = pid,
                kind = if (f.getOrNull(1)?.trim() == "TTY") Kind.TTY else Kind.HEADLESS,
                stream = f.getOrNull(2)?.trim().orEmpty(),
                since = f.getOrNull(3)?.trim().orEmpty(),
            )
        }
        return when {
            holders.isNotEmpty() -> Probe.Held(holders)
            sawFree -> Probe.Free
            // Ran but printed no sentinel — a mangled shell or a truncated
            // stream. Unreachable, never Free.
            else -> Probe.Unreachable
        }
    }

    /**
     * One line for the chat when the holder is a person's terminal. Names the
     * tty and the start time, because "session is busy" with no `where` is
     * the kind of sentence that sends the owner hunting through windows.
     */
    fun ttyHolderNote(holders: List<Holder>): String {
        val h = holders.firstOrNull { it.kind == Kind.TTY } ?: holders.first()
        val where = h.stream.ifBlank { "a terminal" }
        val since = h.since.takeIf { it.isNotBlank() }?.let { ", since $it" }.orEmpty()
        return "session open in a codex terminal on the server " +
            "($where, pid ${h.pid}$since) — codex allows one writer — tap to take it over"
    }

    /**
     * Why taking it over loses nothing: a thread's content IS its rollout
     * file, appended per turn, and the holder is only the process with the
     * write handle. Ending it frees the thread; the history stays on disk and
     * the very next resume reads it back. Measured on the owner's server
     * 2026-09-09 — the TUI-held thread was 3474 lines on disk while its
     * holder was refusing every other opener.
     *
     * It still needs a deliberate tap and never a habitual gesture: the
     * holder can be a terminal someone is looking at.
     */
    fun takenOverNote(pids: List<Long>): String =
        "took the session over (ended pid${if (pids.size > 1) "s" else ""} " +
            "${pids.joinToString(" ")} on the server) — send again to continue it"

    fun takeoverFailedNote(): String =
        "could not take the session over — it is still held on the server; " +
            "close the codex terminal there and send again"
}
