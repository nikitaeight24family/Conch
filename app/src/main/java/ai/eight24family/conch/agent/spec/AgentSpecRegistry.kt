package ai.eight24family.conch.agent.spec

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.claude.ClaudeSpec
import ai.eight24family.conch.agent.codex.CodexSpec
import ai.eight24family.conch.agent.gemini.GeminiSpec

/**
 * Single source of truth that maps `Agent` enum values to their per-CLI
 * [AgentCliSpec] implementations. Held as a singleton because specs are
 * stateless — all per-chat state lives in `AgentSession`.
 *
 * Callers everywhere (AgentSession.buildCommand, SessionDiscovery,
 * AgentStatusProbe, ChatViewModel.probeAvailableModels) reach a spec via
 * `AgentSpecRegistry[agent]`. Adding a fourth agent in future means
 * dropping a new `XxxSpec.kt` under `agent.xxx` and adding one line here.
 */
object AgentSpecRegistry {

    private val specs: Map<Agent, AgentCliSpec> = mapOf(
        Agent.CLAUDE to ClaudeSpec,
        Agent.CODEX to CodexSpec,
        Agent.GEMINI to GeminiSpec,
    )

    /** Spec for [agent]. Throws if a future Agent enum value is added without
     *  a spec wired in — surfaces the omission at startup rather than as a
     *  silently-broken chat. */
    operator fun get(agent: Agent): AgentCliSpec =
        specs[agent] ?: error("No AgentCliSpec registered for $agent. " +
            "Add one under agent.${agent.name.lowercase()} and wire it in AgentSpecRegistry.")

    /** All registered specs. Used by the unified status probe to concat
     *  their statusProbeLines in one round-trip. */
    val all: Collection<AgentCliSpec> get() = specs.values
}
