package ai.eight24family.conch.agent.spec

/**
 * Per-agent strategy for the chat topbar's model picker.
 *
 * Previously this logic lived as a wad of `if (isClaude) … else if
 * (isCodex) …` branches inside `ChatScreen.TerminalTopBar`, which is
 * exactly the modularity leak the user has been complaining about:
 * Claude's `ALIAS_FALLBACK_LABELS` map (Opus 4.7 / Sonnet 4.6 / Haiku 4.5)
 * was sitting in a top-level constant in the shared UI file, so it
 * could (and did) bleed into Codex's topbar display until extra
 * `agentName.equals("codex", true)` guards were piled on top. Moving
 * each agent's behavior into its own [AgentTopbarUi] implementation
 * means:
 *
 *  - ChatScreen never branches on agent identity — it asks the spec.
 *  - Adding a fourth CLI is one new TopbarUi alongside the new spec, no
 *    edits to shared UI code.
 *  - Test surface is tiny — each impl is a pure function on
 *    [TopbarModelState].
 *
 * Implementations must be stateless (held as singletons on the spec).
 */
interface AgentTopbarUi {
    /**
     * The label to show next to the chevron in the topbar.
     *
     * Returns `null` (or blank) when the spec doesn't have ACCURATE
     * information about which model the chat is on yet — caller
     * MUST hide the entire model-picker row in that case. No
     * "codex" / "loading…" / agent-name placeholders allowed; the
     * user explicitly rejected those.
     *
     * Implementations decide their own fallback chain. Codex falls
     * through `selected → sessionInitial → observed → defaultModel`;
     * if all four are null/blank → returns null → button hidden until
     * the config.toml probe lands.
     */
    fun displayLabel(state: TopbarModelState): String?

    /**
     * Optional sub-label next to the model name in the topbar — e.g.
     * "Extra high" / "High" for the current reasoning effort. Null
     * means no sub-label rendered.
     */
    fun reasoningLabel(state: TopbarModelState): String? = null

    /**
     * Whether tapping the topbar should open the dropdown.
     *
     * Codex gates this on `!modelsProbing && availableModels.isNotEmpty()`
     * — opening an empty dropdown lets the user pick a model the CLI
     * doesn't accept, leading to silent failures on send. Claude
     * doesn't need the gate because its 3-entry list is bundled in
     * the CLI itself.
     */
    fun isMenuEnabled(state: TopbarModelState): Boolean

    /**
     * The dropdown items to render. Empty list = picker still opens
     * but shows nothing (used by Gemini today, which doesn't expose a
     * model flag we drive).
     *
     * `storedValue` is the value persisted to prefs and passed as
     * `--model <X>` (or used as an alias key) by the spec's
     * `buildExecCommand`. `null` means "no override, use CLI default"
     * — Claude uses it for the bundled-default alias.
     */
    fun menuItems(state: TopbarModelState): List<ModelMenuItem>
}

/**
 * Snapshot of every input the topbar derives its display from.
 *
 * Aggregated into one struct so [AgentTopbarUi] methods are pure
 * functions on a single argument — easy to test, no implicit
 * dependencies on Compose state.
 */
data class TopbarModelState(
    /** Human-readable agent name ("Claude Code", "Codex CLI", …). Last-resort
     *  fallback label when every model source is null. */
    val agentDisplayName: String,
    /** User's explicit pick this session — `null` = "use CLI default". */
    val selectedModel: String?,
    /** Model parsed from the session JSONL header during discovery, set
     *  the instant the user taps a session row so the topbar shows the
     *  real model from frame zero. `null` for fresh chats. */
    val sessionInitialModel: String?,
    /** Model reported by the live session via `system.init` (Claude) or
     *  `session_meta`/`thread.started` (Codex) events. `null` until the
     *  first event lands. */
    val observedModel: String?,
    /** The CLI's effective default model (Codex parses `~/.codex/config.toml`
     *  top-level `model = "..."`). `null` for specs that don't expose a
     *  notion of "default". */
    val defaultModel: String?,
    /** `alias → human-readable label` map from the spec's
     *  `probeAvailableModels`. Empty until the probe returns.
     *
     *  ⚠ COMPLETE ON PURPOSE — it must keep entries that are no longer
     *  offered, because a chat pinned to an old alias still has to resolve
     *  its LABEL (a pinned chat showing a raw slug is a regression). What
     *  the user may PICK is [availableModels] minus [hiddenModels]. */
    val availableModels: Map<String, String>,
    /**
     * Keys that exist in [availableModels] for label resolution only and must
     * NOT be offered in the picker: catalog entries left over from the era
     * when models were scraped off the `/model` TUI, which no CLI registry has
     * since confirmed. Empty = we have no provenance data yet, and the picker
     * shows everything (fail-open — an empty picker is worse than a stale row).
     */
    val hiddenModels: Set<String> = emptySet(),
    /** Model NAMES the running session reported as unavailable (parsed from the
     *  "<Model> is currently unavailable" banner). The topbar must not advertise
     *  one of these as the chat's model — mirror the CLI's fallback. */
    val unavailableModels: Set<String> = emptySet(),
    /** False while [observedModel] predates the user's explicit [selectedModel]
     *  pick. A pick can never equal a PAST observation, so treating "differs"
     *  as "the session force-switched" pinned the chip to the old model on
     *  every pick (user, 2026-07-25: picked Opus, chip stayed Sonnet). Only an
     *  observation that arrived AFTER the pick may override it. */
    val observationNewerThanPick: Boolean = true,
    /** True only while the user's EFFORT pick is newer than the last effort the
     *  session reported. Defaults to false — i.e. what the session actually
     *  runs at wins — because showing a stale pick is how the topbar came to
     *  print "low" over an xhigh session (2026-08-02). */
    val reasoningPickIsNewer: Boolean = false,
    /** True while [AgentCliSpec.probeAvailableModels] is in flight. */
    val modelsProbing: Boolean,
    /** Per-chat reasoning effort pick. `null` = use the model's
     *  default. Codex uses `low|medium|high|xhigh`, Claude uses
     *  `low|medium|high|max`. */
    val selectedReasoning: String? = null,
    /** Reasoning effort the session was running on, parsed from the
     *  JSONL header at chat-open time. Like `sessionInitialModel`
     *  but for `model_reasoning_effort` — used as a fallback in
     *  the topbar so a resumed chat shows the effort IT was using,
     *  not whatever config.toml currently has. */
    val sessionInitialReasoning: String? = null,
    /** Reasoning effort the LIVE session is running at, mirrored from
     *  the session file (Claude's `ultra_effort_enter` → "ultracode").
     *  Analogous to [observedModel]: it's "what the session actually
     *  runs", so it beats the stale PTY probe / config default but
     *  yields to an explicit user pick ([selectedReasoning]). */
    val observedReasoning: String? = null,
    /** Per-model reasoning catalog: `slug → list of ReasoningLevel`.
     *  Drives the per-model submenu in the picker. Empty list per
     *  slug means that model doesn't support reasoning switching. */
    val reasoningCatalog: Map<String, ModelReasoningInfo> = emptyMap(),
    /** Effective CLI-side default reasoning effort — what the CLI
     *  actually uses when no `-c model_reasoning_effort` is passed.
     *  For Codex it's parsed from `~/.codex/config.toml`'s
     *  `model_reasoning_effort = "..."`. Surfaced in the topbar so
     *  the visible label matches reality when the user hasn't picked
     *  anything (the user's config.toml might say `xhigh` while the
     *  model's intrinsic default is `medium` — the topbar should
     *  show what's running, which is the user's xhigh). */
    val defaultReasoning: String? = null,
)

/**
 * One entry in the topbar dropdown.
 *
 * @param display Human-visible label.
 * @param storedValue Value to write to prefs / pass to `--model`. `null`
 *  means "no override" (used by Claude's bundled-default alias).
 * @param reasoning Reasoning levels supported by this model. Empty
 *  list = no reasoning sub-picker for this entry (model has only one
 *  reasoning level, or CLI doesn't expose reasoning switching).
 * @param defaultReasoning The reasoning level codex / claude uses for
 *  this model when no `-c model_reasoning_effort` / `--effort` flag is
 *  passed. Used to mark "(default)" in the sub-menu so the user can
 *  identify the no-op choice.
 */
data class ModelMenuItem(
    val display: String,
    val storedValue: String?,
    val reasoning: List<ReasoningLevel> = emptyList(),
    val defaultReasoning: String? = null,
    /** False when the agent reports this model currently unavailable
     *  (e.g. Claude's `/model` row description "Claude Fable 5 is currently
     *  unavailable …" after an export-control suspension). The picker
     *  renders it dimmed + non-selectable, matching the CLI's own menu. */
    val available: Boolean = true,
)

/**
 * One reasoning-effort option for a given model.
 *
 * Values are the literal strings each CLI accepts:
 *  - Codex: `low | medium | high | xhigh` (via `-c model_reasoning_effort="X"`)
 *  - Claude: `low | medium | high | max` (via `--effort X`)
 *
 * [description] is the human-readable explanation codex's own
 * cache stores for each level ("Fast responses with lighter
 * reasoning" etc.). Shown in the submenu under the level name so the
 * user picks intentionally instead of guessing what "xhigh" means.
 */
data class ReasoningLevel(
    val effort: String,
    val displayName: String,
    val description: String,
)

/**
 * Reasoning metadata for one model, mirroring the codex-cache
 * `default_reasoning_level` + `supported_reasoning_levels` fields.
 *
 * For Claude we synthesize this manually since Claude Code CLI
 * doesn't ship a comparable cache file — the levels are baked into
 * [ai.eight24family.conch.agent.claude.ClaudeSpec].
 */
data class ModelReasoningInfo(
    val defaultEffort: String,
    val levels: List<ReasoningLevel>,
)
