package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import java.util.UUID

/**
 * RECOVER A SESSION'S WORKING DIRECTORY, once, for every path that resumes one.
 *
 * A resumed chat that does not know its cwd is not a cosmetic problem: Claude
 * stores sessions under a slug of the cwd, and Gemini buckets them by a hash of
 * it and REFUSES outright from the wrong directory ("No previous sessions found
 * for this project", exit 42 — measured on the owner's server 2026-09-12 while
 * driving his phone).
 *
 * The lookup is per-CLI (`AgentCliSpec.cwdBackfillScript`); what is shared is
 * everything around it — skip when the cwd is already known or the agent has no
 * script, run it over the live channel, parse the one `"cwd":"…"` pair, and
 * announce it as a `cwd_backfill` System record so the session adopts it.
 *
 * This existed twice (persistent stream, one-shot runner) and was MISSING from
 * the third caller, the Gemini ACP channel — which is exactly the agent that
 * cannot resume without it. One copy now, so the next channel that appears
 * cannot forget it the same way.
 */
internal object SessionCwd {

    suspend fun backfill(
        agent: Agent,
        resumeId: String?,
        currentCwd: String?,
        exec: suspend (String) -> String?,
        emit: (AgentMessage) -> Unit,
    ): String? {
        val rid = resumeId ?: return null
        if (currentCwd != null) return null
        val script = AgentSpecRegistry[agent].cwdBackfillScript(rid) ?: return null
        val raw = exec(script) ?: return null
        val cwd = Regex("\"cwd\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
        if (cwd.isNullOrBlank()) return null
        emit(
            AgentMessage.System(
                id = UUID.randomUUID().toString(),
                subtype = "cwd_backfill",
                cwd = cwd,
                sessionId = rid,
                raw = "{\"backfilled\":true,\"cwd\":\"$cwd\"}",
            )
        )
        return cwd
    }
}
