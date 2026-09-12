package ai.eight24family.conch.agent.codex

import ai.eight24family.conch.agent.SessionHolder

/**
 * Codex's half of "who is holding this session" — the wording, the marker id,
 * and the one thing that really is codex-specific: an error string that means
 * BUSY.
 *
 * The mechanics moved to [SessionHolder] when Claude needed the same answer.
 * Nothing about `/proc/<pid>/fd` discovery, or the tty-vs-pipe classification,
 * was ever about codex — see that class for why they exist and why a failed
 * probe is not "free". What stays here is what only codex can say.
 *
 * ## Why codex needs an error string and Claude does not
 *
 * codex 0.153 put a WRITER LOCK on every thread
 * (`~/.codex/thread_history_*.sqlite`). A second opener is refused:
 *
 * ```
 * {"error":{"code":-32600,
 *   "message":"thread <id> already has an active writer"}}
 * ERROR codex_core::session: thread-store conflict: thread <id> already has
 *   an active writer
 * ```
 *
 * That message means **BUSY**, not gone. The app used to read it through
 * `looksLikeDeadSession` ("thread/resume failed" matched), conclude the session
 * was dead, drop the resume id and send the prompt into a BRAND-NEW EMPTY
 * THREAD — which is what the owner and a Reddit reporter both saw as "it
 * doubles the session instead of continuing it, with no context" (2026-09-09,
 * verified end to end on the owner's server: app-server refused `thread/resume`
 * at 18:33:11 and a fresh `codex_exec` rollout appeared at 18:33:18, seven
 * seconds later).
 *
 * Claude ships no such lock, so there is no refusal to key off and the probe
 * has to run BEFORE the launch instead of after a failure. That is the whole
 * difference between the two agents' policies.
 */
internal object CodexThreadLock {

    /** `pgrep -f` pattern that bounds [SessionHolder.probeScript]'s scan. */
    const val PGREP = "codex"

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

    fun isSafeThreadId(id: String?): Boolean = SessionHolder.isSafeId(id)

    /**
     * Does this failure mean "someone else has it open" rather than "it is
     * gone"? Matched on codex's own wording, both the JSON-RPC message and
     * the `codex_core` log line, plus the TUI's bootstrap wrapper.
     */
    fun isWriterConflict(text: String?): Boolean {
        val t = text?.lowercase() ?: return false
        return "already has an active writer" in t || "thread-store conflict" in t
    }

    fun probeScript(threadId: String): String = SessionHolder.probeScript(threadId, PGREP)

    fun parseProbe(raw: String?): SessionHolder.Probe = SessionHolder.parseProbe(raw)

    /** codex has no thread-level release — `thread/unsubscribe` answers
     *  "unsubscribed" and changes nothing (measured on 0.153.4), so the writer
     *  moves only when a process ends. That is what the row has to say. */
    fun ttyHolderNote(holders: List<SessionHolder.Holder>): String =
        SessionHolder.ttyHolderNote(holders, cli = "codex", why = "codex allows one writer")

    /** The relay line — the app already continued the session here; this says
     *  what it ended to do it. */
    fun relayedNote(holders: List<SessionHolder.Holder>): String =
        SessionHolder.relayedNote(holders, cli = "codex")

    fun takenOverNote(pids: List<Long>): String = SessionHolder.takenOverNote(pids)

    fun takeoverFailedNote(): String = SessionHolder.takeoverFailedNote("codex")
}
