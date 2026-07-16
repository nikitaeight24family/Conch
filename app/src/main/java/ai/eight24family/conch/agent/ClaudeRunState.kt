package ai.eight24family.conch.agent

/** How a [ClaudeRunState] affects using the agent. */
enum class ClaudeSeverity { OK, WARN, BLOCK }

/**
 * The account's Claude Code run-readiness, as the Claude Code CLI itself models
 * it. A present OAuth credential does NOT mean a turn will run — the account can
 * be in any of these states, each detected SERVER-SIDE before a turn from
 * `api/oauth/profile` (+ `usage`). Mined from the CLI binary (see
 * memory `reference_claude_code_run_states` / docs/claude-run-states.md).
 *
 * ONE source of truth for the whole app: every surface (agent picker, chat
 * banner, send-gate, session list) renders [badge]/[line]/[severity] from here,
 * never a hand-written per-screen string — so a state can't look different (or
 * missing) on one screen. Data-bearing states ([TRIAL_ACTIVE], [RATE_LIMITED],
 * [NEAR_LIMIT]) fold a small datum (days-left / reset time) into [line] via
 * [lineWith].
 *
 * Only PRE-DETECTABLE states are modeled. Runtime-only refusals (trusted-device,
 * min-version policy, org-suspended, API credit balance, feature/model policy)
 * surface from the turn error instead — probes read OK for those.
 */
enum class ClaudeRunState(
    val severity: ClaudeSeverity,
    /** Short badge, e.g. "no subscription". Empty for [OK]/[UNKNOWN] (plain "ready"). */
    val badge: String,
    /** One-line explanation + remedy, shown as the row sub-line / chat banner. */
    val line: String,
) {
    /** Auth + entitlement verified; a turn will run (limits/policies may still bite at run time). */
    OK(ClaudeSeverity.OK, "", ""),

    /** Logged in, but no active Pro/Max subscription with Claude Code and no live
     *  trial (canceled/none). Covers a personal lapsed sub AND an org that
     *  disabled Claude-Code access — the CLI reports both identically. */
    NO_SUBSCRIPTION(
        ClaudeSeverity.BLOCK, "no subscription",
        "No active Claude subscription with Claude Code — subscribe at claude.ai, ask your admin to enable it, or use an API key.",
    ),

    /** Bundled Claude Code trial elapsed with no paid sub. */
    TRIAL_ENDED(
        ClaudeSeverity.BLOCK, "trial ended",
        "Your Claude Code trial has ended — upgrade to Pro/Max, then sign in again, or use an API key.",
    ),

    /** Pro-eligible but the trial was never activated. */
    TRIAL_START(
        ClaudeSeverity.BLOCK, "start trial",
        "Start your Claude Code trial to run — activate it on the server (claude), then reconnect.",
    ),

    /** A billing/payment problem (pending invoice / payment authorization) blocks Claude Code. */
    PAYMENT_DUE(
        ClaudeSeverity.BLOCK, "payment due",
        "A billing issue is blocking Claude Code — resolve payment at claude.ai, or use an API key.",
    ),

    /** OAuth token dead (expired/revoked, refresh gone) — profile 401. Re-login. */
    TOKEN_EXPIRED(
        ClaudeSeverity.BLOCK, "login expired",
        "Your Claude sign-in has expired — run login again on the server.",
    ),

    /** A usage window that gates our turns (5-hour / weekly / third-party-app) is
     *  fully spent. TRANSIENT — clears at the reset time (folded into [line]). */
    RATE_LIMITED(
        ClaudeSeverity.BLOCK, "rate limited",
        "Usage limit reached — resets %s. Switch model, or wait.",
    ),

    /** A usage window is close to full — enterable, just a heads-up. */
    NEAR_LIMIT(
        ClaudeSeverity.WARN, "near limit",
        "Usage almost spent — resets %s.",
    ),

    /** Pro account inside its live Claude Code trial — runnable; shows days left. */
    TRIAL_ACTIVE(
        ClaudeSeverity.WARN, "trial",
        "On the Claude Code trial — %s left.",
    ),

    /** Probe inconclusive (profile non-200 that isn't auth, usage 429, fresh
     *  session with no windows). Do NOT block — let the turn surface the truth. */
    UNKNOWN(ClaudeSeverity.OK, "", "");

    val isBlocked: Boolean get() = severity == ClaudeSeverity.BLOCK

    /** [line] with the state's datum (days-left / reset time) substituted; plain
     *  [line] when there's no `%s` or no datum. */
    fun lineWith(data: String?): String =
        if (line.contains("%s")) line.replace("%s", (data ?: "soon")) else line

    companion object {
        /** Parse the probe token `claude_run_state=<NAME>`; unknown/blank → null
         *  (not applicable — non-Claude, api-key mode, or not checked). */
        fun fromToken(token: String?): ClaudeRunState? =
            token?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
                entries.firstOrNull { it.name == t }
            }
    }
}
