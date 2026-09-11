package ai.eight24family.conch.agent

/**
 * WHO ELSE HAS THIS SESSION OPEN — and is it our garbage or a person's terminal?
 *
 * ## Why this is agent-agnostic
 *
 * This started life inside [ai.eight24family.conch.agent.codex.CodexThreadLock],
 * where codex 0.153's own writer lock forced the question by refusing a second
 * opener out loud. Claude asks it far more quietly and far more dangerously: it
 * ships NO lock, so a second `claude --resume <same uuid>` simply succeeds —
 * and both processes then append to one rollout. Upstream has the bug filed
 * (anthropics/claude-code#48270): concurrent resumes anchor to a STALE branch of
 * the conversation tree, and every further resume extends the stale branch, so
 * the damage compounds instead of announcing itself. The owner's own case is the
 * ordinary one — `ssh` in from the laptop, run `claude`, later carry on from the
 * phone — and until now the phone launched its second process into that with
 * nothing detecting it, nothing warning, nothing blocking.
 *
 * So the discovery cannot be reactive to an error message: for Claude there is
 * no error. It has to be asked BEFORE the launch, by every agent, which is why
 * the mechanics live here and only the wording and the policy stay per-agent.
 *
 * ## Why discovery is by the FILE, not by argv
 *
 * [RemoteTurnKiller] finds processes by the session id in their command line and
 * says in its own kdoc that it cannot see a `codex app-server` or an interactive
 * REPL, because neither puts the id in argv. A console `claude` that MINTED the
 * session is the same blind spot — it was started as a bare `claude`, and the
 * uuid it minted appears nowhere on its command line. But the process is holding
 * the rollout OPEN, so `/proc/<pid>/fd` names it whatever argv says.
 *
 * ## Why the holder's KIND decides everything
 *
 * Conch's channels ride an sshd exec channel, so their fds are PIPES; a person's
 * session has them on `/dev/pts/N`. One `readlink` separates the two, and they
 * need opposite handling:
 *
 *  - **headless holder** (pipes) — our own orphan: a CLI whose SSH channel died
 *    while the remote process lived on. Ours to clean up, so we end it and carry
 *    on, silently (feedback_auto_fix_errors).
 *  - **tty holder** (`/dev/pts/N`) — a live session a person is sitting in.
 *    NEVER ended behind their back, and never launched over. The chat stays
 *    bound to the real session and says where it is held, so the next send
 *    continues it for real instead of forking a ghost.
 *
 * ## Why [Probe.Unreachable] is not [Probe.Free]
 *
 * A probe that failed to run tells us nothing, and acting on a guess here is
 * precisely what produced the phantom sessions of 2026-07..09. Unreachable means
 * "carry on as before" — never "nobody has it".
 */
internal object SessionHolder {

    /** Sentinels — the ONLY protocol with the caller. Prefixed so a chatty
     *  login shell (motd, profile echo) cannot fake them. */
    const val MARK_FREE = "CONCH_LOCK_FREE"
    const val MARK_HOLDER = "CONCH_LOCK_HOLDER:"

    /** The id is interpolated into a shell script — accept only the UUID-ish
     *  shape the CLIs actually mint. Same guard as [RemoteTurnKiller]'s. */
    fun isSafeId(id: String?): Boolean =
        id != null && Regex("^[a-fA-F0-9-]{16,40}$").matches(id)

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
        /** Which layer found it — `fd`, `argv` or `cwd`. Diagnostic only; the
         *  three are equally binding, but a bug report that says WHICH one
         *  answered is the difference between one measurement and five. */
        val via: String = "",
    )

    /**
     * Nobody holds it (so a refusal was something else and must NOT be treated
     * as a conflict), or here is who does. [Unreachable] when the exec itself
     * failed — never confused with "free".
     */
    sealed interface Probe {
        object Free : Probe
        data class Held(val holders: List<Holder>) : Probe
        object Unreachable : Probe
    }

    /**
     * Find every process holding the session file named by [sessionId] open, in
     * ONE exec.
     *
     * [narrow] is a `pgrep -f` pattern that bounds the scan to processes that
     * could plausibly be a holder (`claude`, `codex`, …). It is a performance
     * bound, not the evidence: the `/proc/<pid>/fd` listing is. POSIX sh — no
     * bashisms — so it survives the `sh -lc` fallback on servers without bash.
     */
    /** Both halves are interpolated into a shell script, so both are checked
     *  before one is built. Callers that assemble [narrow] from data (an
     *  agent's `cliCommand`) ask this first rather than risking the `require`
     *  below — a probe we cannot build safely is simply a probe we skip. */
    fun canProbe(sessionId: String?, narrow: String): Boolean =
        isSafeId(sessionId) && Regex("^[a-z0-9-]{2,32}$").matches(narrow)

    /**
     * Enables the CWD layer — see [probeScript]. Built by [mintedInCwd] or
     * [mintedInFile] depending on which the caller happens to hold; both end up
     * setting the same four shell vars the layer reads (`slug0`, `dir`, `fm`,
     * `now`), so the layer itself has one implementation.
     */
    data class MintedIn(internal val prelude: String)

    /** For a caller that knows the session's WORKING DIRECTORY (a live chat —
     *  it must `cd` there to resume at all). [projectRoot] is a shell
     *  expression, e.g. `$HOME/.claude/projects`. */
    fun mintedInCwd(cwd: String, projectRoot: String): MintedIn = MintedIn(
        "cwd0=" + shellEscape(cwd) + "; " +
            "slug0=\$(echo \"\$cwd0\" | sed 's/[^a-zA-Z0-9]/-/g'); " +
            "dir=" + projectRoot + "/\$slug0; " +
            "f=\"\$dir/\$tid.jsonl\"; " +
            newestAndMtime(),
    )

    /** For a caller that knows the session FILE's path (a mirrored chat — the
     *  listing handed it one). The project directory's name IS the slug, so
     *  nothing has to be re-derived. */
    fun mintedInFile(sessionFile: String): MintedIn = MintedIn(
        "f=" + shellEscape(sessionFile) + "; " +
            "dir=\$(dirname \"\$f\"); " +
            "slug0=\$(basename \"\$dir\"); " +
            newestAndMtime(),
    )

    /** Shared tail of both preludes: a bare REPL can only be sitting on the
     *  NEWEST rollout in its directory, and `fm` (that file's last write) is
     *  what a candidate process's start time is later compared against. */
    private fun newestAndMtime(): String =
        "newest=\$(ls -1t \"\$dir\"/*.jsonl 2>/dev/null | head -1); " +
            "[ \"\$newest\" = \"\$f\" ] || dir=''; " +
            "fm=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null); " +
            "now=\$(date +%s); "

    /**
     * Find every process that holds session [sessionId], in ONE exec.
     *
     * [narrow] is a `pgrep -f` pattern bounding the scan to processes that
     * could plausibly be a holder (`claude`, `codex`, …).
     *
     * ## Three layers, because no single one finds every CLI
     *
     * **FD** — the process whose `/proc/<pid>/fd` points at the session file.
     * This is how codex is found, measured on the owner's server 2026-09-09:
     * the holder was pid 1051364 with its rollout open and `fd/0,1,2` all on
     * `/dev/pts/0`.
     *
     * ⚠ **It finds Claude never.** Measured on the owner's server 2026-09-11
     * (claude 2.1.268), a `claude` REPL under a real PTY with a 30-line session
     * it had just written: forty samples at 0.5 s across a running turn AND at
     * the idle prompt, `fd`-on-jsonl was 0 every time, `/proc/locks` carried no
     * entry for the file, and the app's OWN persistent channel (pid 1537) held
     * no jsonl either. Claude opens the rollout to append and closes it. Any
     * design that asks `/proc/<pid>/fd` about a Claude session gets silence and
     * cannot tell it from "nobody has it" — which is why this layer alone was
     * not enough and why the comment in `ClaudeSpec.listSessionsScript` that
     * assumes the opposite is wrong.
     *
     * **ARGV** — the session id on the command line. Exact when it is there:
     * `claude --resume <id>`, `codex exec resume <id>`. Blind to the two cases
     * that matter most, an app-server and a REPL that MINTED the session,
     * because neither puts an id in argv (see [RemoteTurnKiller]).
     *
     * **CWD** — for the REPL that minted it. Claude stores a session under
     * `<projectRoot>/<cwd with every non-alnum turned into '-'>/<id>.jsonl`, so
     * a bare `claude` sitting in that directory is the one that owns it. The
     * same measurement confirmed both halves: the TUI's cwd was the project
     * directory verbatim, and `fd/1` was still a pts, so the TTY-vs-pipe
     * classification survives even though the fd layer does not.
     *
     * The cwd alone would be too coarse — a directory holds many sessions —
     * so two conditions narrow it to one:
     *  - the session in question is the NEWEST rollout in that directory (a
     *    REPL owns the one it minted, and after `/clear` the newer one), and
     *  - the process STARTED BEFORE that file was last written, because a
     *    process that owns the file must have made that write. This is what
     *    stops a REPL that has not taken a turn yet from being blamed for an
     *    older session that merely happens to be the newest on disk.
     */
    fun probeScript(
        sessionId: String,
        narrow: String,
        mintedIn: MintedIn? = null,
    ): String {
        require(isSafeId(sessionId)) { "unsafe session id" }
        require(Regex("^[a-z0-9-]{2,32}$").matches(narrow)) { "unsafe pgrep pattern" }
        val cwdSetup = mintedIn?.prelude ?: "dir=''; fm=''; "
        return "tid='" + sessionId + "'; " +
            "hit=''; " +
            cwdSetup +
            "for p in \$(pgrep -f " + narrow + " 2>/dev/null); do " +
            "[ \"\$p\" = \"\$\$\" ] && continue; " +
            "via=''; " +
            // FD layer — the evidence codex leaves.
            "ls -l \"/proc/\$p/fd\" 2>/dev/null | grep -q \"\$tid\" && via=fd; " +
            // ARGV layer — exact whenever the id is on the command line.
            "[ -z \"\$via\" ] && tr '\\0' ' ' < \"/proc/\$p/cmdline\" 2>/dev/null | " +
            "grep -q \"\$tid\" && via=argv; " +
            // CWD layer — the REPL that minted it.
            "if [ -z \"\$via\" ] && [ -n \"\$dir\" ] && [ -n \"\$fm\" ]; then " +
            // ⛔ THE CWD LAYER MUST FIRST PROVE THE PROCESS IS THE CLI.
            //
            // `pgrep -f` matches the whole command line, so it catches anything
            // that merely MENTIONS the cli's name. On the owner's own server
            // that is not hypothetical: `/usr/bin/python3 ~/tgsend/tgclaude.py`
            // matches `pgrep -f claude`, and its cwd is `/home/user` — which
            // IS a Claude project directory, because he runs claude from his
            // home. Without this check that bridge would have been reported as
            // a HEADLESS holder of his newest session and reaped silently by
            // the orphan cleanup. The other two layers are evidence about the
            // SESSION (this process has the file open / names its id); the cwd
            // is only evidence about a DIRECTORY, so it has to be paired with
            // evidence about the program.
            "a0=\$(tr '\\0' '\\n' < \"/proc/\$p/cmdline\" 2>/dev/null | head -1); " +
            "a1=\$(tr '\\0' '\\n' < \"/proc/\$p/cmdline\" 2>/dev/null | sed -n 2p); " +
            "real=''; " +
            // argv[0] is the CLI itself, or it is an interpreter (node, bun)
            // and argv[1] is the CLI's own script path.
            "case \"\${a0##*/}\" in " + narrow + "|" + narrow + ".*) real=1 ;; esac; " +
            "case \"\${a1##*/}\" in " + narrow + "|" + narrow + ".*) real=1 ;; esac; " +
            "if [ -n \"\$real\" ]; then " +
            "c=\$(readlink \"/proc/\$p/cwd\" 2>/dev/null); " +
            "if [ -n \"\$c\" ]; then " +
            "cs=\$(echo \"\$c\" | sed 's/[^a-zA-Z0-9]/-/g'); " +
            "if [ \"\$cs\" = \"\$slug0\" ]; then " +
            "et=\$(ps -o etimes= -p \"\$p\" 2>/dev/null | tr -d ' '); " +
            "if [ -n \"\$et\" ] && [ \"\$((now - et))\" -le \"\$fm\" ]; then via=cwd; fi; " +
            "fi; fi; fi; fi; " +
            "[ -z \"\$via\" ] && continue; " +
            "s=\$(readlink \"/proc/\$p/fd/1\" 2>/dev/null); " +
            "case \"\$s\" in /dev/pts/*|/dev/tty*) k=TTY ;; *) k=HEADLESS ;; esac; " +
            "st=\$(ps -o lstart= -p \"\$p\" 2>/dev/null | sed 's/^ *//'); " +
            "echo \"$MARK_HOLDER\$p|\$k|\$s|\$st|\$via\"; " +
            "hit=1; " +
            "done; " +
            "[ -z \"\$hit\" ] && echo $MARK_FREE; " +
            "exit 0"
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
                via = f.getOrNull(4)?.trim().orEmpty(),
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
     * tty and the start time, because "session is busy" with no `where` is the
     * kind of sentence that sends the owner hunting through windows.
     *
     * [why] is the per-agent half — what the app will and will not do about it.
     */
    fun ttyHolderNote(holders: List<Holder>, cli: String, why: String): String {
        val h = holders.firstOrNull { it.kind == Kind.TTY } ?: holders.first()
        val where = h.stream.ifBlank { "a terminal" }
        val since = h.since.takeIf { it.isNotBlank() }?.let { ", since $it" }.orEmpty()
        return "session open in a $cli terminal on the server " +
            "($where, pid ${h.pid}$since) — $why — tap to take it over"
    }

    /**
     * Why taking it over loses nothing: a session's content IS its file on
     * disk, appended per turn, and the holder is only the process with the
     * write handle. Ending it frees the session; the history stays and the very
     * next resume reads it back whole.
     *
     * It still needs a deliberate tap and never a habitual gesture: the holder
     * can be a terminal someone is looking at.
     */
    fun takenOverNote(pids: List<Long>): String =
        "took the session over (ended pid${if (pids.size > 1) "s" else ""} " +
            "${pids.joinToString(" ")} on the server) — send again to continue it"

    fun takeoverFailedNote(cli: String): String =
        "could not take the session over — it is still held on the server; " +
            "close the $cli terminal there and send again"
}
