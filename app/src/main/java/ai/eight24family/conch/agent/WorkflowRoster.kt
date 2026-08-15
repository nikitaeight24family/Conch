package ai.eight24family.conch.agent

/**
 * A background Workflow (the ultracode `Workflow` tool) as the chat should show
 * it — the phone twin of the CLI footer's
 * «hikari-web-review · 32/35 agents done · 6m 21s».
 *
 * The live agent counts are NOT in the session rollout — the workflow writes
 * them to its OWN run dir on the server
 * (`<session>/subagents/workflows/wf_<runId>/journal.jsonl`, one
 * `{"type":"started"}` / `{"type":"result"}` line per agent). This class holds
 * only what the TRANSCRIPT gives us (name, runId, whether it already finished);
 * the ViewModel polls the journal over the pooled SSH for `done`/`total` and
 * merges them in. Kept pure + Android-free so it unit-tests.
 */
data class WorkflowRun(
    /** `wf_<runId>` directory name — the join key to the on-server journal. */
    val runId: String,
    /** `meta.name` from the workflow script, e.g. "hikari-web-review". */
    val name: String,
    /** Launch time (epoch ms) from the Workflow tool_use — drives elapsed. */
    val startedAtMs: Long,
)

private val WF_NAME_RX = Regex("""name:\s*['"]([^'"]+)['"]""")
// tool_result: "Workflow launched in background. Task ID wf_b70c3793-ab3 …"
private val WF_RUNID_RX = Regex("""\b(wf_[A-Za-z0-9_-]+)""")

private class WfAcc {
    var name: String? = null
    var runId: String? = null
    var startedAtMs: Long = 0L
}

/**
 * Fold the chat transcript into the workflow roster.
 *
 * A workflow is one `Workflow` [AgentMessage.ToolUse] (`meta.name` in its
 * `script` input) plus the [AgentMessage.ToolResult] that reports its
 * background run id ("Task ID wf_…"). Launch order preserved. Whether a run is
 * still LIVE is decided by the ViewModel from the journal itself (done<total,
 * or the journal still growing) — the completion notification's wording is not
 * stable enough to key on, and the journal is ground truth anyway.
 */
fun foldWorkflows(messages: List<AgentMessage>): List<WorkflowRun> {
    val byTool = LinkedHashMap<String, WfAcc>()   // toolUseId -> acc
    for (m in messages) {
        when (m) {
            is AgentMessage.ToolUse -> {
                if (!m.toolName.equals("Workflow", ignoreCase = true)) continue
                val a = byTool.getOrPut(m.id) { WfAcc() }
                a.name = WF_NAME_RX.find(m.input)?.groupValues?.get(1) ?: a.name
                a.startedAtMs = System.currentTimeMillis()
            }
            is AgentMessage.ToolResult -> {
                val a = byTool[m.toolUseId] ?: continue
                WF_RUNID_RX.find(m.output)?.groupValues?.get(1)?.let { rid -> a.runId = rid }
            }
            else -> {}
        }
    }
    return byTool.values.mapNotNull { a ->
        val rid = a.runId ?: return@mapNotNull null
        WorkflowRun(rid, a.name ?: rid.removePrefix("wf_"), a.startedAtMs)
    }
}
