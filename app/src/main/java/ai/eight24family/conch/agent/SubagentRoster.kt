package ai.eight24family.conch.agent

import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One subagent as the chat should display it — the row behind the CLI's own
 * "← 1 agent · ↓ to manage" footer:
 *
 * ```
 * ○ general-purpose  Inventory HPAF gateway core   49s · ↓ 36.6k tokens
 * ```
 */
data class SubagentRun(
    /** Join key: the `Task` tool_use that spawned it (falls back to agentId). */
    val key: String,
    /** `subagent_type` from the Task tool input, e.g. "general-purpose". */
    val type: String?,
    /** The task itself — Task's `description`, else its `prompt`. */
    val task: String?,
    val tokens: Long,
    val elapsedSeconds: Long?,
    val done: Boolean,
)

private val rosterJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Fold a chat's raw message list into the live subagent roster.
 *
 * Pure on purpose: no Android, no coroutines, no clock — so the whole
 * behaviour is unit-testable, and the UI just renders whatever this returns.
 *
 * Three signals are combined, all named after the shipped CLI (2.1.218):
 *  - a `Task` [AgentMessage.ToolUse] carries `subagent_type` + `description`
 *    / `prompt`. Its tool_use id is the roster key.
 *  - [AgentMessage.SubagentActivity] carries the running totals; it points
 *    back with `parentToolUseID`, so it lands on the right row.
 *  - the [AgentMessage.ToolResult] for that same tool_use id means the agent
 *    finished — that is what flips ● (running) to ○ (done).
 *
 * Order is preserved: agents appear in launch order, like the CLI's list.
 */
fun foldSubagents(messages: List<AgentMessage>): List<SubagentRun> {
    // key -> mutable accumulator, insertion-ordered.
    data class Acc(
        var type: String? = null,
        var task: String? = null,
        var tokens: Long = 0,
        var elapsed: Long? = null,
        var done: Boolean = false,
    )

    val acc = LinkedHashMap<String, Acc>()

    for (m in messages) {
        when (m) {
            is AgentMessage.ToolUse -> {
                if (!m.toolName.equals("Task", ignoreCase = true)) continue
                val a = acc.getOrPut(m.id) { Acc() }
                SilentlyTry.fired("SshAi-Subagents", "parse Task input") {
                    val o = rosterJson.parseToJsonElement(m.input).jsonObject
                    a.type = o["subagent_type"]?.jsonPrimitive?.content ?: a.type
                    a.task = (o["description"] ?: o["prompt"])?.jsonPrimitive?.content ?: a.task
                }
            }

            is AgentMessage.SubagentActivity -> {
                val key = m.parentToolUseId ?: m.agentId ?: continue
                val a = acc.getOrPut(key) { Acc() }
                // Activity records report cumulative usage per turn, so the
                // agent's total is their sum.
                a.tokens += m.tokens
                m.elapsedSeconds?.let { a.elapsed = it }
                m.subagentType?.let { a.type = it }
                m.task?.let { a.task = it }
                if (m.done) a.done = true
            }

            is AgentMessage.ToolResult -> {
                // The Task tool returning IS the agent's completion signal.
                acc[m.toolUseId]?.done = true
            }

            else -> Unit
        }
    }

    return acc.map { (key, a) ->
        SubagentRun(
            key = key,
            type = a.type,
            task = a.task?.trim()?.replace('\n', ' '),
            tokens = a.tokens,
            elapsedSeconds = a.elapsed,
            done = a.done,
        )
    }
}
