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
 * ● general-purpose · sonnet  Inventory HPAF gateway core  49s · 6 tools · Grep · 36.6k
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
    /** Model the agent actually ran on, alias already resolved by the CLI. */
    val model: String? = null,
    /** Tools the agent has invoked so far (`usage.tool_uses`). */
    val toolUses: Int? = null,
    /** The tool it called last — what it is doing right now. */
    val lastTool: String? = null,
    /**
     * CLI status: running · completed · failed · killed · queued · paused ·
     * cancelled. Null when no status event has arrived yet, which is NOT the
     * same as "running" — the UI shows the distinction rather than guessing.
     */
    val status: String? = null,
    /** The agent's own one-line result. */
    val summary: String? = null,
    /** Failure text, when the CLI reported one. */
    val error: String? = null,
    /** The agent kept running after the main turn ended. */
    val backgrounded: Boolean = false,
)

private val rosterJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Id prefix of the chat note the parser writes for a `task_*` event. */
private const val TASK_NOTE_PREFIX = "sysevt-task-"

/**
 * Who owns the background tasks in this chat.
 *
 * @param ownTaskNoteIds ids of the `task_*` chat notes that belong to THIS
 *   session's own work and may therefore be shown in the transcript.
 * @param agentTaskCount how many background tasks are running for the AGENTS —
 *   the number the agent panel reports instead of the chat.
 */
data class TaskOwnership(
    val ownTaskNoteIds: Set<String>,
    val agentTaskCount: Int,
)

/**
 * Split background tasks into "the session's own" and "the agents'".
 *
 * ⚠ THE CLI DOES NOT TELL US. Its task registry records the owning `agentId`
 * (verified in 2.1.220), but the emitted `task_started` carries only task_id ·
 * tool_use_id · description · subagent_type · task_type · prompt ·
 * skip_transcript. With one registry for the whole session, a fan-out's shell
 * commands arrive on the main stream looking exactly like the main agent's own —
 * which is how the transcript ended up as a wall of `task · completed ·
 * Background command "…"` rows belonging to twenty agents.
 *
 * So ownership is INFERRED, from the one difference that is real: a background
 * command the SESSION ran has its `tool_use` block in the main transcript,
 * because the main agent had to call the tool to start it. An agent's does not —
 * subagent traffic folds into [AgentMessage.SubagentActivity] and never becomes
 * a [AgentMessage.ToolUse] row. A task whose `tool_use_id` we cannot find among
 * our own tool calls is therefore somebody else's.
 *
 * Unknown ⇒ the agents'. That is the safe direction: the cost of hiding one of
 * our own task lines is a line the user can still see in the agent panel; the
 * cost of the opposite is the flood this exists to stop.
 *
 * Pure, so the rule is pinned by tests rather than living inside the display
 * pipeline.
 */
fun foldTaskOwnership(messages: List<AgentMessage>): TaskOwnership {
    val ownToolUseIds = HashSet<String>()
    // task_id → tool_use_id, learned from whichever events carry both.
    val taskTool = HashMap<String, String>()
    // task_id → is it an agent (vs a background command)?
    val agentTasks = HashSet<String>()
    val seenTasks = LinkedHashSet<String>()
    val finishedTasks = HashSet<String>()
    var snapshot: List<AgentMessage.BackgroundTasks.Entry>? = null

    for (m in messages) {
        when (m) {
            is AgentMessage.ToolUse -> ownToolUseIds += m.id
            is AgentMessage.BackgroundTasks -> snapshot = m.tasks
            is AgentMessage.SubagentActivity -> {
                val tid = m.taskId ?: continue
                seenTasks += tid
                m.parentToolUseId?.let { taskTool[tid] = it }
                if (m.taskType == "local_agent" || m.taskType == "remote_agent" ||
                    m.subagentType != null
                ) {
                    agentTasks += tid
                }
                if (m.done) finishedTasks += tid
            }
            else -> Unit
        }
    }

    val ownNotes = HashSet<String>()
    for (tid in seenTasks) {
        val tool = taskTool[tid]
        // An AGENT is never "the session's own task line" — it belongs in the
        // roster, which shows it far better than a note could.
        if (tid in agentTasks) continue
        if (tool != null && tool in ownToolUseIds) ownNotes += TASK_NOTE_PREFIX + tid
    }

    // Prefer the CLI's own snapshot for the live count (REPLACE semantics: it IS
    // the set of what is running now). Fall back to started-minus-finished for a
    // stream that has not sent one yet.
    val running = snapshot
        ?.filter { it.taskType != "local_agent" && it.taskType != "remote_agent" }
        ?.map { it.taskId }
        ?: seenTasks.filter { it !in finishedTasks && it !in agentTasks }
    val agentRunning = running.count { TASK_NOTE_PREFIX + it !in ownNotes }

    return TaskOwnership(ownTaskNoteIds = ownNotes, agentTaskCount = agentRunning)
}

/**
 * Fold a chat's raw message list into the live subagent roster.
 *
 * Pure on purpose: no Android, no coroutines, no clock — so the whole
 * behaviour is unit-testable, and the UI just renders whatever this returns.
 *
 * Signals combined, all named after the shipped CLI (verified on 2.1.220 by
 * capturing a real `--print --output-format stream-json` run):
 *  - a `Task`/`Agent` [AgentMessage.ToolUse] carries `subagent_type` +
 *    `description` / `prompt`. Its tool_use id is the roster key.
 *  - [AgentMessage.SubagentActivity] carries the running totals. It arrives
 *    from three places and points back with whichever key it has: the live
 *    stream's `parent_tool_use_id` turns, `agent_progress` records, and the
 *    `task_started/progress/updated/notification` system events.
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
        /** Sum of per-record `message.usage`. Our own count. */
        var tokens: Long = 0,
        /** The CLI's own cumulative total, when it reported one. */
        var totalTokens: Long? = null,
        var toolUses: Int? = null,
        var durationMs: Long? = null,
        var lastTool: String? = null,
        var model: String? = null,
        var status: String? = null,
        var summary: String? = null,
        var error: String? = null,
        var backgrounded: Boolean = false,
        var elapsed: Long? = null,
        var done: Boolean = false,
    )

    val acc = LinkedHashMap<String, Acc>()
    // task_id → roster key. `task_updated` — the record that carries the
    // terminal status and the error — ships task_id ONLY, so without this
    // pairing (learned from task_started/task_notification, which carry both)
    // every completion would land nowhere and agents would stay ● forever.
    val taskKeys = HashMap<String, String>()

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
                    // The spawn itself is the CLI's own "model" hint: an
                    // explicit `model` on the Agent tool input overrides the
                    // inherited one, and it is the only model signal available
                    // before the agent's first turn reports.
                    a.model = o["model"]?.jsonPrimitive?.content ?: a.model
                }
                if (nameIsAgentish && !parsedAgent) acc.getOrPut(m.id) { Acc() }
            }

            is AgentMessage.SubagentActivity -> {
                // A BACKGROUND COMMAND IS NOT AN AGENT. `local_bash` tasks share
                // the session's one task registry with agent tasks and arrive
                // through the same events; folding them here would grow a
                // phantom agent row per shell command a fan-out runs. They are
                // counted by [foldTaskOwnership] instead.
                if (m.taskType == "local_bash") continue
                // Resolve which row this observation belongs to. A record with
                // a tool_use id names its row directly; a task_id-only record
                // (task_updated) needs the pairing learned earlier — and if
                // that task was never introduced as an AGENT, it is not ours to
                // show.
                val direct = m.parentToolUseId ?: m.agentId
                val key = direct ?: m.taskId?.let { taskKeys[it] } ?: continue
                if (direct != null) m.taskId?.let { taskKeys[it] = direct }
                // ⚠ ONLY AN AGENT MAY OPEN A ROW.
                //
                // Checking `taskType == "local_bash"` above is not enough: a
                // background command's `task_notification` carries NO task_type
                // and no subagent_type, yet it does carry `tool_use_id` — so it
                // walked straight in and `getOrPut` gave a shell command a
                // nameless agent row (type null, task null), which its later
                // `task_updated` then updated. A row may be CREATED only by
                // something that identifies itself as an agent (an Agent/Task
                // tool_use, or a record with an agent type); anything else may
                // only UPDATE a row that already exists.
                // An `agentId` counts: only agents have one. The CLI's own
                // `agent_progress` records carry it and may arrive with no
                // parentToolUseID, and dropping those would make the agent
                // invisible — the case `SubagentRosterTest` pins.
                val identifiesAsAgent = m.subagentType != null || m.agentId != null ||
                    m.taskType == "local_agent" || m.taskType == "remote_agent"
                if (key !in acc && !identifiesAsAgent) continue
                val a = acc.getOrPut(key) { Acc() }
                // Per-record usage is incremental → sum. `total_tokens` is
                // ALREADY a total → last-wins. Mixing the two up bills the
                // agent once per progress tick.
                a.tokens += m.tokens
                m.totalTokens?.let { a.totalTokens = it }
                m.toolUses?.let { a.toolUses = it }
                m.durationMs?.let { a.durationMs = it }
                m.lastTool?.let { a.lastTool = it }
                m.model?.let { a.model = it }
                m.status?.let { a.status = it }
                m.summary?.let { a.summary = it }
                m.error?.let { a.error = it }
                m.backgrounded?.let { a.backgrounded = it }
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
            // Prefer the CLI's own number so the panel agrees with the CLI;
            // fall back to what we counted off the stream when no task_progress
            // has landed yet (short agents finish before the first tick).
            tokens = a.totalTokens ?: a.tokens,
            // `duration_ms` keeps ticking for an agent that hasn't produced a
            // turn in a while, so it beats the per-record elapsed stamp.
            elapsedSeconds = a.durationMs?.let { it / 1000 } ?: a.elapsed,
            done = a.done,
            model = a.model,
            toolUses = a.toolUses,
            lastTool = a.lastTool,
            status = a.status,
            summary = a.summary?.trim()?.replace('\n', ' '),
            error = a.error?.trim()?.replace('\n', ' '),
            backgrounded = a.backgrounded,
        )
    }
}
