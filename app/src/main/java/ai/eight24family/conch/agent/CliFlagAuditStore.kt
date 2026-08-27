package ai.eight24family.conch.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest flag-audit verdict per (server, agent), so the mode sheet can say
 * what was actually checked instead of implying it.
 *
 * Deliberately in memory only. A verdict is about the binary that is on the
 * server RIGHT NOW; persisting it across restarts would let a stale "verified"
 * outlive the version it was about — the same one-way-preservation trap that
 * made a wrong "login expired" permanent (see AgentStatusCache's
 * BLOCK_VERDICT_TTL_MS). Absent simply reads as "not audited yet", which is
 * true and harmless: the audit re-runs on the next install or update.
 */
object CliFlagAuditStore {

    private val _reports = MutableStateFlow<Map<String, CliFlagAudit.Report>>(emptyMap())
    val reports: StateFlow<Map<String, CliFlagAudit.Report>> = _reports.asStateFlow()

    private fun key(serverId: String, agent: Agent) = "$serverId:${agent.name}"

    fun put(serverId: String, report: CliFlagAudit.Report) {
        _reports.value = _reports.value + (key(serverId, report.agent) to report)
    }

    fun get(serverId: String, agent: Agent): CliFlagAudit.Report? =
        _reports.value[key(serverId, agent)]

    /** True when the audit RAN and this mode was rejected by the installed CLI.
     *  Unknown (never audited) is NOT a rejection — the app must not block a
     *  mode on the strength of never having looked. */
    fun isModeRejected(serverId: String, agent: Agent, mode: ai.eight24family.conch.data.prefs.AgentApprovalMode): Boolean =
        get(serverId, agent)?.modes?.firstOrNull { it.mode == mode }?.accepted == false
}
