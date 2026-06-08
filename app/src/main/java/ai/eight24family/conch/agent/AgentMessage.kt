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
        val cwd: String? = null,
        val version: String? = null,
        val toolCount: Int = 0,
        val raw: String
    ) : AgentMessage

    /** Visible assistant turn (one block of text). */
    data class AssistantText(override val id: String, val text: String) : AgentMessage

    /** Visible user turn — locally-rendered or replayed from the saved session. */
    data class UserText(override val id: String, val text: String) : AgentMessage

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

    /** Agent is asking the user to approve a tool invocation. */
    data class PermissionRequest(
        override val id: String,
        val requestId: String,
        val toolName: String,
        val description: String,
        val input: String,
        val raw: String,
        val resolved: Resolution = Resolution.PENDING
    ) : AgentMessage {
        enum class Resolution { PENDING, ALLOWED, DENIED }
    }

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
