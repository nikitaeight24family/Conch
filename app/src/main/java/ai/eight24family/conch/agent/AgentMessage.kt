package ai.eight24family.conch.agent

/**
 * Cross-CLI normalized view of one event from a chat stream.
 *
 * Every [ai.eight24family.conch.agent.spec.AgentCliSpec] implementation
 * parses its CLI's native JSONL into instances of this sealed interface.
 * The UI layer only ever sees these — it never knows whether the message
 * originated from Claude's `system/init`, Codex's `thread.started`, or
 * Gemini's `init`.
 */
/**
 * How the user answered a [AgentMessage.PermissionRequest] card.
 * `ALLOW_SESSION` maps to Codex `acceptForSession` / Gemini `allow_always`;
 * agents without a per-call session scope (Claude) treat it like `ALLOW_ONCE`.
 */
enum class PermissionDecision { DENY, ALLOW_ONCE, ALLOW_SESSION }

sealed interface AgentMessage {
    val id: String

    /**
     * Session bookkeeping. `subtype` carries the CLI's event name
     * (`"init"`, `"cwd_backfill"`, etc.) so consumers can render
     * different banners; `sessionId` (when present) is what we feed
     * back into `--resume` next time.
     */
    data class System(
        override val id: String,
        val subtype: String,
        val sessionId: String? = null,
        val model: String? = null,
        /** Reasoning effort the session is actually running at, mirrored
         *  from the session file (e.g. Claude's `ultra_effort_enter`
         *  attachment → "ultracode"). Drives the topbar's effort label the
         *  same way [model] drives the model label — NEVER a hardcoded
         *  default, only what the session reports. */
        val reasoning: String? = null,
        /** Claude's auto-generated session title (the `ai-title` event's
         *  `aiTitle`) — the topbar shows THIS instead of the first user message. */
        val title: String? = null,
        val cwd: String? = null,
        val version: String? = null,
        val toolCount: Int = 0,
        val raw: String
    ) : AgentMessage

    /**
     * A compact session-event line — the visible form of every
     * `system/<subtype>` (and attachment) event the CLI emits. NOTHING
     * is silently swallowed anymore: known subtypes get tailored
     * labels, unknown ones a generic `subtype · summary` line.
     * [detail] expands on tap; progress-ish events (task_… / hook_… /
     * thinking_tokens) reuse a stable [id] so they update IN PLACE
     * instead of spamming rows.
     */
    data class EventNote(
        override val id: String,
        val label: String,
        val detail: String? = null,
        val tone: Tone = Tone.DIM,
    ) : AgentMessage {
        enum class Tone { DIM, INFO, WARN }
    }

    /** Visible assistant turn (one block of text). */
    data class AssistantText(override val id: String, val text: String) : AgentMessage

    /**
     * Visible user turn — locally-rendered or replayed from the saved session.
     *
     * [recordUuid] is the JSONL record's own `uuid` when this row came from
     * the session file; null for a locally-rendered optimistic bubble that
     * the CLI hasn't written yet. It is the ANCHOR the rewind protocol takes
     * (`rewind_conversation.target_message_uuid` /
     * `rewind_files.user_message_id`), so a row without one simply cannot
     * offer rewind — which is honest: the turn does not exist server-side yet.
     * Default null keeps every existing construction site valid.
     */
    data class UserText(
        override val id: String,
        val text: String,
        val recordUuid: String? = null,
    ) : AgentMessage

    /**
     * One observation about a SUBAGENT (Claude's Task tool spawns these; the
     * CLI's own footer counts them as "← 1 agent" and lists them under
     * "↓ to manage"). NEVER rendered as a chat row: the CLI deliberately keeps
     * subagent turns out of the main transcript (`filtered from /resume:
     * isSidechain=true`), and a fan-out of eight agents would otherwise bury
     * the conversation. The ChatViewModel folds these into the live agent
     * panel instead.
     *
     * [parentToolUseId] is the join key — it points at the `Task` tool_use that
     * spawned this agent, which is where [subagentType] and [task] come from.
     * [agentId] is the CLI's own per-agent handle (carried by `agent_progress`
     * records).
     */
    data class SubagentActivity(
        override val id: String,
        val agentId: String?,
        val parentToolUseId: String?,
        val subagentType: String? = null,
        val task: String? = null,
        /** Cumulative tokens this observation reports, 0 when unknown. */
        val tokens: Long = 0,
        val elapsedSeconds: Long? = null,
        val done: Boolean = false,
        /**
         * The subagent's own text for this record — kept so a Task fan-out stays
         * SEARCHABLE. Before subagent turns were folded into this type they
         * parsed as AssistantText/ToolUse/ToolResult and got indexed; collapsing
         * them to metadata alone would silently drop tens of thousands of tokens
         * of research out of search. Indexed, never rendered as a chat row.
         */
        val text: String? = null,
    ) : AgentMessage

    /** Agent invoked a tool. Input is opaque JSON serialized to string. */
    data class ToolUse(
        override val id: String,
        val toolName: String,
        val input: String
    ) : AgentMessage

    /** Tool produced output. May be an error. */
    data class ToolResult(
        override val id: String,
        val toolUseId: String,
        val output: String,
        val isError: Boolean
    ) : AgentMessage

    /**
     * The agent asks the user to pick option(s) — Claude Code's
     * AskUserQuestion tool surfaced through the control protocol
     * (`control_request{can_use_tool}` on the persistent stream-json
     * channel). 1-4 questions, each with 2-4 options; the chosen
     * labels go back as the control response and the turn continues.
     */
    data class AskUserQuestion(
        override val id: String,
        /** control_request id the answer must reference. */
        val requestId: String,
        val questions: List<Question>,
        /** question index → chosen option labels. Null until answered;
         *  non-null renders the card resolved (chips frozen). */
        val answers: Map<Int, List<String>>? = null,
        /** True when parsed from the session FILE of a MIRRORED (console-driven)
         *  turn: the card shows the question + options for reading, but the answer
         *  can only be given in the CLI session (the app isn't driving that turn),
         *  so the options aren't tappable. */
        val readOnly: Boolean = false,
    ) : AgentMessage {
        data class Question(
            val question: String,
            val header: String,
            val options: List<Option>,
            val multiSelect: Boolean,
        )
        data class Option(val label: String, val description: String)
    }

    /** Agent is asking the user to approve a tool invocation. */
    data class PermissionRequest(
        override val id: String,
        val requestId: String,
        val toolName: String,
        val description: String,
        val input: String,
        val raw: String,
        val resolved: Resolution = Resolution.PENDING,
        /** Whether THIS agent's protocol can grant "allow for the rest of the
         *  session" (Codex `acceptForSession`, Gemini `allow_always`). When true
         *  the card shows a third "always allow" button so the user isn't
         *  re-tapping the same approval over and over on a phone (audit
         *  2026-06-14). Claude's control protocol has no per-call session scope
         *  → false, button hidden. */
        val canAllowSession: Boolean = false,
    ) : AgentMessage {
        enum class Resolution { PENDING, ALLOWED, DENIED }
    }

    /**
     * NON-RENDERING marker: "this line ended the turn."
     *
     * The single authority on turn completion. It exists because there used to
     * be two — the parser dispatching on the real top-level `type`, and a
     * separate substring scan in the stream reader deciding whether the same
     * line was terminal. They disagreed on Claude's `result` envelope and the
     * app rendered a finished answer while the spinner ran on top of it, with
     * nothing in the log to say why (2026-07-29).
     *
     * The parser knows what it recognised; nothing downstream should
     * re-derive it. Consumed by the reader and dropped — never enters history.
     */
    data class TurnEnd(
        override val id: String,
        /** Why it ended, for the log: "result", "result:error", "error", … */
        val reason: String,
    ) : AgentMessage

    /** Final aggregated turn result with summary text + status. */
    data class Result(
        override val id: String,
        val subtype: String,
        val text: String?
    ) : AgentMessage

    /** Surfaced error (non-fatal warning or stream-level error event).
     *
     * [kind] is an OPTIONAL discriminator the chat UI uses to render certain
     * errors as PROMINENT cards instead of a plain `! …` line. Known values: -
     * `"overloaded"` — the upstream model API is busy (Anthropic 529 /
     * `overloaded_error`). Renders as a styled "Service is busy" card with a
     * "switch model" affordance, matching the claude.ai web treatment. The
     * [details] field carries the body text shown under the title. - `null`
     * (default) — plain Error line render.
     *
     *  Both new fields are non-required and default-friendly so existing
     *  emitters (file-transfer, run one-shot, codex/gemini parsers) keep
     *  working without changes.
     */
    data class Error(
        override val id: String,
        val text: String,
        val kind: String? = null,
        val details: String? = null,
    ) : AgentMessage

    /** Everything we couldn't categorize. Includes one-line `· event` markers
     *  and stderr leaks that landed on stdout. */
    data class Raw(override val id: String, val text: String) : AgentMessage
}
