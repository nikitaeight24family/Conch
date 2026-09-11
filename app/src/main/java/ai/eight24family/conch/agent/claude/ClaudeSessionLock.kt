package ai.eight24family.conch.agent.claude

import ai.eight24family.conch.agent.SessionHolder

/**
 * Claude's half of "who is holding this session" — see [SessionHolder] for the
 * mechanics, and [ai.eight24family.conch.agent.codex.CodexThreadLock] for the
 * agent whose refusal made the question visible in the first place.
 *
 * ## Claude's version of the problem is the quiet one
 *
 * codex refuses a second opener out loud. Claude does not refuse at all: a
 * second `claude --resume <same uuid>` starts, and both processes append to one
 * rollout. Upstream (anthropics/claude-code#48270) reports what that costs —
 * concurrent resumes anchor to a STALE branch of the conversation tree and each
 * further resume extends the stale branch, so the history degrades quietly and
 * the damage compounds instead of announcing itself.
 *
 * The everyday way in is exactly the owner's workflow: `ssh` in from the laptop,
 * run `claude`, walk away, carry on from the phone.
 *
 * ## Why the probe cannot be reactive, and cannot ask about file handles
 *
 * There is no error to key off, so the question has to be asked BEFORE the
 * launch, on every resume.
 *
 * And it cannot be asked the way codex's is. MEASURED on the owner's server
 * 2026-09-11 (claude 2.1.268): a `claude` REPL under a real PTY, holding a
 * 30-line session it had just written, showed ZERO fds on that jsonl — forty
 * samples at half-second intervals across a running turn and again at the idle
 * prompt — and `/proc/locks` had no entry for it either. Claude opens the
 * rollout to append and closes it. The app's own persistent channel behaves the
 * same way, which also means the `CONCH_INUSE` guard in
 * `ClaudeSpec.listSessionsScript` never matches anything and the comment there
 * claiming the channel "keeps the fd open for the whole session" is wrong; what
 * protects that rewrite is its one-minute mtime cool-down.
 *
 * What the same measurement DID find is the working directory: the TUI's cwd was
 * the project directory verbatim, and its `fd/1` was still a pts. So the REPL is
 * found by where it is standing, and classified by what it is talking to — see
 * [SessionHolder]'s CWD layer. The tail poll's `CONCH_WRITER` tick already
 * mangles a cwd this way; it just never fed anything but the spinner.
 *
 * ## What the app does about it, and what it refuses to do
 *
 * A headless holder is one of ours — a persistent channel whose SSH transport
 * died while the remote `claude` lived on — and it is ended silently. A tty
 * holder is a terminal a person may be looking at: the app does NOT launch over
 * it, does NOT end it, and says where it is, with a tap that ends it if the
 * person decides so. Unlike codex, Claude would have let us barge in; declining
 * to is the whole point.
 */
internal object ClaudeSessionLock {

    /** `pgrep -f` pattern that bounds [SessionHolder.probeScript]'s scan. Both
     *  a bare interactive `claude` and our own `claude --output-format …` match
     *  it; the per-process layers are what actually decide. */
    const val PGREP = "claude"

    /** Stable id of the "held elsewhere" row — ONE row however many times a
     *  chat hits it, and the handle [ChatMessageLines] hangs the tap on. */
    const val TAKEOVER_MARKER_ID = "conch-claude-session-held"

    /** Where Claude keeps its per-project session directories. The directory
     *  name is the session's cwd with every non-alphanumeric character turned
     *  into `-`; that mangling is the CLI's own and was re-confirmed on the
     *  owner's server 2026-09-11 (`/tmp/cfd2.3899601` → `-tmp-cfd2-3899601`). */
    const val PROJECT_ROOT = "\$HOME/.claude/projects"

    fun isSafeSessionId(id: String?): Boolean = SessionHolder.isSafeId(id)

    /**
     * [cwd] is the directory this session was created in — the chat already
     * knows it, because a resume has to `cd` there or the CLI answers "No
     * conversation found". Passing it turns on the layer that can actually see
     * a console REPL; without it the probe is blind to exactly the case this
     * class exists for (Claude keeps no fd on its rollout — see [SessionHolder]).
     */
    fun probeScript(sessionId: String, cwd: String?): String = SessionHolder.probeScript(
        sessionId,
        PGREP,
        cwd?.takeIf { it.isNotBlank() }?.let { SessionHolder.mintedInCwd(it, PROJECT_ROOT) },
    )

    fun parseProbe(raw: String?): SessionHolder.Probe = SessionHolder.parseProbe(raw)

    /** Claude allows the second writer — which is precisely why we don't take
     *  it. The row has to say that this is a choice, not a refusal we hit. */
    fun ttyHolderNote(holders: List<SessionHolder.Holder>): String =
        SessionHolder.ttyHolderNote(
            holders,
            cli = "claude",
            why = "a second writer would fork its history",
        )

    fun takenOverNote(pids: List<Long>): String = SessionHolder.takenOverNote(pids)

    fun takeoverFailedNote(): String = SessionHolder.takeoverFailedNote("claude")
}
