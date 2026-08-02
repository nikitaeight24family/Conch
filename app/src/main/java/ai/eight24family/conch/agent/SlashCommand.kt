package ai.eight24family.conch.agent

/**
 * What a slash command DOES on our side. The CLI's `/clear`/`/cost`/etc. are
 * REPL-only — they don't pass through `claude --print`. So we re-implement
 * each one ourselves and dispatch via [SlashCommandKind].
 *
 * Catalog policy: every built-in here MUST also have a first-class UI
 * affordance somewhere in the app. Slash commands are a power-user typing
 * shortcut, not a substitute for proper UI. Anything that was REPL-only or
 * niche-on-mobile (`/help`, `/init`, `/agents`, `/mcp`, `/cost`,
 * `/compact`, `/login`, `/logout`) has been removed entirely.
 */
enum class SlashCommandKind {
    NEW_SESSION,        // /clear, /new — also: [+ new session] on SessionsScreen
    INJECT_DIFF,        // /diff       — also: diff item in attach sheet
    INIT_REPO,          // /init       — also: init item in attach sheet (per-CLI prompt)
    OPEN_MEMORY,        // /memory     — also: memory icon in chat topbar
    OPEN_AGENTS,        // /agents     — also: subagents icon in chat topbar
    OPEN_MODEL_PICKER,  // /model      — also: model dropdown in topbar
    REVIEW,             // /review     — Codex code review (uncommitted, or vs a base branch)
    CUSTOM,             // user-defined custom command files
    /**
     * A command the CLI ITSELF owns — its built-ins and its skills, exactly as
     * the `initialize` handshake reports them (45 on a stock install: /compact,
     * /context, /usage, /doctor, /security-review, plus every skill). We never
     * reimplemented these and never listed them either, so they were
     * unreachable from the phone. Dispatch is the honest one: send `/name args`
     * as a turn, which is how the CLI runs them anyway (verified over our own
     * channel with /compact, 2026-08-03).
     */
    AGENT_BUILTIN,
}

data class SlashCommand(
    val name: String,
    val description: String,
    val kind: SlashCommandKind,
    val acceptsArgs: Boolean = false,
    /** For CUSTOM: the prompt body, with $ARGUMENTS substitution support. */
    val customPrompt: String? = null,
    /** For CUSTOM: source file path (project vs global, for grouping). */
    val source: String? = null,
)

object SlashCommands {
    val BUILT_IN: List<SlashCommand> = listOf(
        SlashCommand("clear",  "start a fresh chat session",                  SlashCommandKind.NEW_SESSION),
        SlashCommand("new",    "alias for /clear",                            SlashCommandKind.NEW_SESSION),
        SlashCommand("diff",   "send the current `git diff` as context",      SlashCommandKind.INJECT_DIFF),
        SlashCommand("init",   "scaffold the memory file for this repo",      SlashCommandKind.INIT_REPO),
        SlashCommand("memory", "edit memory file (global + project)",         SlashCommandKind.OPEN_MEMORY),
        SlashCommand("agents", "manage subagents (Claude only)",              SlashCommandKind.OPEN_AGENTS),
        SlashCommand("model",  "switch model (use the topbar dropdown)",      SlashCommandKind.OPEN_MODEL_PICKER),
        SlashCommand("review", "code review · /review [base-branch] (Codex)", SlashCommandKind.REVIEW, acceptsArgs = true),
    )

    /**
     * Parse `/name args...` from raw input. Returns null if not a slash
     * command (no leading slash, just `/`, leading space, etc).
     */
    fun parse(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("/")) return null
        if (trimmed.startsWith("//")) return null  // markdown / comment
        val body = trimmed.removePrefix("/")
        val space = body.indexOf(' ')
        return if (space < 0) body to ""
        else body.substring(0, space) to body.substring(space + 1).trim()
    }

    /** Find a built-in or custom command by name (case-insensitive). */
    fun find(name: String, custom: List<SlashCommand> = emptyList()): SlashCommand? {
        val lc = name.lowercase()
        return BUILT_IN.firstOrNull { it.name == lc }
            ?: custom.firstOrNull { it.name == lc }
    }

    /**
     * Merge the CLI's own commands into the palette WITHOUT shadowing ours.
     *
     * Where we have a native handler the native one wins: /model opens the
     * picker, /memory the editor, /agents the roster — sending those as text
     * would land a useless line in the chat instead. Everything else the CLI
     * offers (its skills included) becomes reachable for the first time.
     */
    fun mergeAgentCommands(
        agentCommands: List<SlashCommand>,
        custom: List<SlashCommand>,
    ): List<SlashCommand> {
        val taken = (BUILT_IN.map { it.name } + custom.map { it.name }).toHashSet()
        return agentCommands.filter { it.name !in taken }
    }

    /** Filter for autocomplete by prefix. Built-ins first, then custom. */
    fun matchPrefix(prefix: String, custom: List<SlashCommand>): List<SlashCommand> {
        val lc = prefix.lowercase()
        return (BUILT_IN + custom).filter { it.name.startsWith(lc) }
    }
}
