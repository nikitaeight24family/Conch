package ai.eight24family.conch.agent.spec

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.data.prefs.AgentApprovalMode

/**
 * What SAFE / AUTO / YOLO were actually TESTED against, per agent.
 *
 * ⛔ WHY THIS FILE EXISTS. The mode→flag mapping is a plain `when (mode)` in
 * each spec, unconditional on the installed CLI. The app installs the CLIs and
 * offers to update them, so the binary underneath a mode label can change at
 * any time — and the label does not. Asked in public (2026-08-27): *"do you pin
 * the CLI versions those modes were tested against? those flags drift, and an
 * app that auto-installs the CLIs could quietly change the permissions a mode
 * grants after an update."*
 *
 * The answer at the time was no, nothing was pinned and nothing was recorded.
 * Worse, a live audit of the installed binaries found the drift had ALREADY
 * happened, in the direction that matters:
 *
 *  - `codex exec` at 0.149.1 rejects `--ask-for-approval` outright ("unexpected
 *    argument"), and `untrusted` is no longer a valid approval policy at all —
 *    the values are now `on-request` and `never`. So the one-shot fallback's
 *    SAFE and AUTO invocations died at parse time with exit 2, while **YOLO,
 *    the one mode that grants everything, still worked**. The safe modes broke
 *    and the unsafe one didn't.
 *  - `claude --permission-prompt-tool` has vanished from `--help` but is still
 *    accepted, which is exactly why this file's audit RUNS the flags instead of
 *    grepping help text (see [ai.eight24family.conch.agent.CliFlagAudit]).
 *
 * So: the version each mapping was checked against is recorded here, the
 * required flags are declared here, and the installer pins [pinnedVersion]
 * instead of reaching for whatever `@latest` happens to be. An update is a
 * deliberate tap, and after it the audit re-runs.
 */
data class CliModeContract(
    val mode: AgentApprovalMode,
    /**
     * Arguments this mode adds, EXACTLY as the invocation passes them. The
     * audit replays these verbatim, so a value the CLI has retired (codex's
     * `untrusted`) fails here the same way it fails in a real turn.
     *
     * Empty = the mode adds no arguments (Claude's SAFE is the CLI default).
     */
    val args: List<String>,
)

data class CliContract(
    val agent: Agent,
    /**
     * The CLI version the mode mapping in this app was verified against, by
     * running every mode's flags through the CLI's own parser. Not a guess and
     * not the newest — the one that was actually checked.
     */
    val testedVersion: String,
    /**
     * The version a fresh install lands on. Held at [testedVersion] so an
     * install cannot silently pick up a CLI whose flags nobody has replayed.
     * An explicit "Update" tap overrides it.
     */
    val pinnedVersion: String,
    val modes: List<CliModeContract>,
) {
    fun argsFor(mode: AgentApprovalMode): List<String> =
        modes.firstOrNull { it.mode == mode }?.args.orEmpty()
}

/**
 * The contracts. Every entry here was produced by replaying the flags through
 * the installed binary's parser on 2026-08-27 — see the audit results quoted in
 * this file's header for what that found.
 */
object CliContracts {

    private val claude = CliContract(
        agent = Agent.CLAUDE,
        testedVersion = "2.1.247",
        pinnedVersion = "2.1.247",
        modes = listOf(
            CliModeContract(AgentApprovalMode.PLAN, listOf("--permission-mode", "plan")),
            // SAFE is the CLI's own default; the one-shot path adds the prompt
            // tool so unanswered prompts route back over stdio. That flag is
            // undocumented in --help at 2.1.247 yet accepted — verified.
            CliModeContract(AgentApprovalMode.SAFE, listOf("--permission-prompt-tool", "stdio")),
            CliModeContract(AgentApprovalMode.AUTO, listOf("--permission-mode", "acceptEdits")),
            CliModeContract(AgentApprovalMode.YOLO, listOf("--dangerously-skip-permissions")),
        ),
    )

    private val codex = CliContract(
        agent = Agent.CODEX,
        testedVersion = "0.149.1",
        pinnedVersion = "0.149.1",
        modes = listOf(
            // ⚠ NO `--ask-for-approval` HERE. `codex exec` rejects it as an
            // unexpected argument at 0.149.1 (it is a top-level flag now), and
            // in a non-interactive run there is nobody to answer an approval
            // anyway — read-only IS the honest SAFE. The old
            // `--ask-for-approval untrusted` was doubly dead: wrong position
            // AND a retired value.
            CliModeContract(AgentApprovalMode.PLAN, listOf("--sandbox", "read-only")),
            CliModeContract(AgentApprovalMode.SAFE, listOf("--sandbox", "read-only")),
            CliModeContract(AgentApprovalMode.AUTO, listOf("--sandbox", "workspace-write")),
            CliModeContract(AgentApprovalMode.YOLO, listOf("--dangerously-bypass-approvals-and-sandbox")),
        ),
    )

    private val gemini = CliContract(
        agent = Agent.GEMINI,
        testedVersion = "0.57.0",
        pinnedVersion = "0.57.0",
        modes = listOf(
            CliModeContract(AgentApprovalMode.PLAN, listOf("--approval-mode", "default")),
            CliModeContract(AgentApprovalMode.SAFE, listOf("--approval-mode", "default")),
            CliModeContract(AgentApprovalMode.AUTO, listOf("--approval-mode", "auto_edit")),
            CliModeContract(AgentApprovalMode.YOLO, listOf("--approval-mode", "yolo")),
        ),
    )

    private val grok = CliContract(
        agent = Agent.GROK,
        testedVersion = "1.0.5",
        pinnedVersion = "1.0.5",
        // All four replayed through `grok <args> --help` on 1.0.5 (exit 0 each,
        // 2026-08-28); bypassPermissions additionally verified by a REAL turn
        // the same day (live run, result subtype=success). Grok's mode
        // vocabulary is Claude-compatible by design (its own docs, ch. 22).
        modes = listOf(
            CliModeContract(AgentApprovalMode.PLAN, listOf("--permission-mode", "plan")),
            CliModeContract(AgentApprovalMode.SAFE, listOf("--permission-mode", "default")),
            CliModeContract(AgentApprovalMode.AUTO, listOf("--permission-mode", "acceptEdits")),
            CliModeContract(AgentApprovalMode.YOLO, listOf("--permission-mode", "bypassPermissions")),
        ),
    )

    private val copilot = CliContract(
        agent = Agent.COPILOT,
        testedVersion = "1.0.80",
        pinnedVersion = "1.0.80",
        // Replayed through `copilot <args> --help` on 1.0.80 (exit 0 each,
        // 2026-08-28). SAFE adds nothing: in -p mode tools are DENIED unless
        // allowed — deny-by-default IS the honest safe. AUTO's allow-all-tools
        // stays path-sandboxed to cwd+temp (workspace-write semantics); YOLO's
        // --yolo = allow all tools + paths + urls, the CLI's own alias.
        modes = listOf(
            CliModeContract(AgentApprovalMode.PLAN, listOf("--plan")),
            CliModeContract(AgentApprovalMode.SAFE, emptyList()),
            CliModeContract(AgentApprovalMode.AUTO, listOf("--allow-all-tools")),
            CliModeContract(AgentApprovalMode.YOLO, listOf("--yolo")),
        ),
    )

    /**
     * null = no contract recorded for this agent, so nothing here may claim its
     * modes were verified. Callers must show "not audited" rather than invent a
     * version — a fabricated "tested against" is worse than an honest blank.
     */
    operator fun get(agent: Agent): CliContract? = when (agent) {
        Agent.CLAUDE -> claude
        Agent.CODEX -> codex
        Agent.GEMINI -> gemini
        Agent.GROK -> grok
        Agent.COPILOT -> copilot
    }
}
