package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.spec.AgentSpecRegistry

/**
 * Identifier for one of the CLIs we drive: Claude Code, OpenAI Codex, Google
 * Gemini, xAI Grok Build, GitHub Copilot CLI, Qwen Code, Cursor CLI.
 *
 * **Per-CLI behavior lives in [ai.eight24family.conch.agent.spec.AgentCliSpec]
 * implementations** (one in each `agent.<cli>` sub-package). This enum
 * carries just the identity tag — the same value stored in the server row's
 * `agent` column — and a handful of convenience properties that forward to
 * the spec so legacy call sites (`agent.memoryFilename`,
 * `agent.cliCommand`, …) keep working after the per-CLI module refactor.
 *
 * Adding a fourth agent in future is three steps:
 *   1. Add the enum value here (e.g. `MISTRAL`).
 *   2. Drop a `MistralSpec` into `agent.mistral` implementing `AgentCliSpec`.
 *   3. Register the spec in [AgentSpecRegistry].
 *
 * Nothing else in the app needs to change — every site that branches on
 * agent goes through the registry.
 */
enum class Agent {
    CLAUDE,
    CODEX,
    GEMINI,
    GROK,
    COPILOT,
    QWEN,
    CURSOR,
    OPENCODE,
    CRUSH,
    CONTINUE,
    ;

    /** Human-readable name shown in pickers. Delegates to spec. */
    val displayName: String get() = AgentSpecRegistry[this].displayName

    /** Binary on the server's PATH. Delegates to spec. */
    val cliCommand: String get() = AgentSpecRegistry[this].cliCommand

    /** npm package name (e.g. `@anthropic-ai/claude-code`), or null when the
     *  CLI ships outside npm. Delegates to spec. */
    val npmPackage: String? get() = AgentSpecRegistry[this].npmPackage

    /** Whether `~/.<cli>/agents/` user-authored subagents are a thing.
     *  Delegates to spec. */
    val supportsSubagents: Boolean get() = AgentSpecRegistry[this].supportsSubagents

    /** Per-CLI memory filename (`CLAUDE.md` / `AGENTS.md` / `GEMINI.md`).
     *  Delegates to spec. */
    val memoryFilename: String get() = AgentSpecRegistry[this].memoryFilename

    /** Shell-expression path to the global memory file (with `$HOME`).
     *  Delegates to spec. */
    val memoryGlobalPath: String get() = AgentSpecRegistry[this].memoryGlobalPath

    /** Display-friendly path to the global memory file (with `~`).
     *  Delegates to spec. */
    val memoryGlobalDisplay: String get() = AgentSpecRegistry[this].memoryGlobalDisplay

    /** Memory editor is now wired up for all three CLIs. Kept around in
     *  case we ever need to short-circuit it again per-agent. */
    val supportsMemory: Boolean get() = true
}
