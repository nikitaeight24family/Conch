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
                // "Task" is the historical name; the shipped CLI spawns
                // subagents with a tool literally named "Agent" now (`Agent
                // {"description":…,"subagent_type":…}`), and the exact-name
                // check made the whole roster BLIND to every modern fan-out.
                // Match by SHAPE too: any tool whose input carries
                // `subagent_type` is an agent spawn, whatever its name.
                val nameIsAgentish = m.toolName.equals("Task", ignoreCase = true) ||
                    m.toolName.equals("Agent", ignoreCase = true)
                if (!nameIsAgentish && !m.input.contains("\"subagent_type\"")) continue
                var parsedAgent = false
                SilentlyTry.fired("SshAi-Subagents", "parse agent-spawn input") {
                    val o = rosterJson.parseToJsonElement(m.input).jsonObject
                    val type = o["subagent_type"]?.jsonPrimitive?.content
                    val task = (o["description"] ?: o["prompt"])?.jsonPrimitive?.content
                    // Shape gate for the non-named path: no subagent_type key in
                    // the PARSED input → not an agent spawn, skip silently.
                    if (!nameIsAgentish && type == null) return@fired
                    parsedAgent = true
                    val a = acc.getOrPut(m.id) { Acc() }
                    a.type = type ?: a.type
                    a.task = task ?: a.task
                }
                if (nameIsAgentish && !parsedAgent) acc.getOrPut(m.id) { Acc() }
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
                // The Task/Agent tool returning IS the completion signal — for
                // a SYNC run. An ASYNC launch acks instantly ("Async agent
                // launched successfully … run_in_background") while the agent
                // keeps working — flipping to ○ on that ack made every async
                // agent look finished the moment it started. Those complete
                // via SubagentActivity(done)/task events instead.
                val a = acc[m.toolUseId]
                if (a != null && !m.output.contains("Async agent launched")) a.done = true
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
