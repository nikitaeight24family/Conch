package ai.eight24family.conch.agent.spec

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.data.prefs.AgentApprovalMode

/**
 * Per-CLI contract. Each of Claude/Codex/Gemini ships **one implementation** of
 * this interface in its own sub-package (`agent.claude.ClaudeSpec`,
 * `agent.codex.CodexSpec`, `agent.gemini.GeminiSpec`).
 *
 * The split exists because the three CLIs disagree on every meaningful axis —
 * headless flag (`-p` vs `exec` subcommand), stream-JSON event shape, resume
 * mechanics, approval-mode vocabulary, model discovery, session file layout,
 * memory file name, slash-command support. The old `when (agent)` branches
 * scattered across `AgentSession.buildCommand`, `SessionDiscovery`,
 * `AgentStatusProbe`, `CodexMessageParser`, and `ChatViewModel.probeXxxModels`
 * couldn't keep up with upstream churn — Codex CLI in particular shipped a
 * brand-new event schema (`thread.started`/`turn.*`/`item.*`, May 2026) and
 * the old parser silently dropped every event → users saw "new chat doesn't
 * start, resume broken, model list empty".
 *
 * Bringing each agent under one spec means:
 *  - one file to read when the CLI evolves;
 *  - no chance Claude logic accidentally runs in a Codex chat (the long-fought
 *    "Opus 4.7" in the Codex dropdown bug);
 *  - parsers can be tested in isolation with a few sample JSONL lines.
 *
 * Implementations must be **stateless** — they're held as singletons in
 * [AgentSpecRegistry]. All per-chat state lives in `AgentSession`.
 *
 * See `docs/cli-research-2026-05.md` for the documentation that drove every
 * decision in the per-CLI implementations.
 */
interface AgentCliSpec {

    // ──────── Identity ────────

    /** Stable enum tag. Mirrors the `agent` column on the server row. */
    val agent: Agent

    /** Human-readable name shown in pickers ("Claude Code", "Codex CLI", …). */
    val displayName: String

    /** Binary on the server's PATH. Used by `command -v` probes. */
    val cliCommand: String

    /** npm package name, used by the "install hint" copy in error toasts. */
    val npmPackage: String

    /** Drawable resource id of the agent's brand mark. Used by hit rows in
     *  search results, the agent picker, and anywhere a compact visual
     *  identifier of "this is Claude / Codex / Gemini" reads better than
     *  the cli command string. Vector drawables in `res/drawable/`. */
    @get:androidx.annotation.DrawableRes
    val iconRes: Int

    // ──────── Capabilities ────────

    /** Whether `~/.<cli>/agents/` user-authored subagents are a thing. */
    val supportsSubagents: Boolean

    /**
     * Whether the CLI honours user-authored slash commands in **headless**
     * mode. Only Claude's auto-loaded skills qualify today; Codex has no user
     * slash commands; Gemini's TOML commands are interactive-only per docs
     * (verify empirically — see research report §3G).
     */
    val supportsCustomSlashCommands: Boolean

    /** Whether `--resume <id>` (or subcommand) can attach to an existing session. */
    val supportsResume: Boolean

    /**
     * Whether the CLI lets us **pre-set** the session UUID at creation so we
     * don't have to parse it out of the first event. Claude has
     * `--session-id <uuid>`; Codex and Gemini do not (we read
     * `thread.started.thread_id` and `init.session_id` from the first event
     * respectively).
     */
    val supportsPreSetSessionId: Boolean

    /**
     * Whether the CLI supports a PERSISTENT bidirectional stream-json
     * channel (`--input-format stream-json` + control protocol): user
     * turns written to a long-lived stdin, `control_request`s
     * (can_use_tool → live permission prompts, AskUserQuestion option
     * picking, interrupt) answered with `control_response`s. Claude
     * only today. When true, [buildPersistentCommand] and
     * [encodeUserTurn] must be implemented.
     */
    val supportsControlProtocol: Boolean get() = false

    /**
     * True when [probeAvailableModels] returns the CLI's OWN REGISTRY — a
     * complete, authoritative list — rather than something scraped or guessed.
     * Only such a result may CONFIRM model keys (see
     * `AppPreferences.registryModelKeysForAgent`): confirmed keys are the ones
     * the picker offers, unconfirmed leftovers survive for label resolution
     * only. Default false, so a spec must opt in deliberately.
     */
    val catalogIsAuthoritative: Boolean get() = false

    /**
     * Build the **inner** shell command for the persistent channel —
     * like [buildExecCommand] but WITHOUT the prompt (turns arrive via
     * stdin) and with bidirectional stream-json flags. Null when
     * [supportsControlProtocol] is false.
     */
    fun buildPersistentCommand(input: ExecInput): String? = null

    /** Encode one user turn as a stdin JSON line for the persistent
     *  channel. Only meaningful when [supportsControlProtocol]. */
    fun encodeUserTurn(text: String): String = ""

    // ──────── Memory ────────

    val memoryFilename: String          // "CLAUDE.md", "AGENTS.md", "GEMINI.md"
    val memoryGlobalPath: String        // shell expression: "$HOME/.claude/CLAUDE.md"
    val memoryGlobalDisplay: String     // human path: "~/.claude/CLAUDE.md"

    // ──────── Command construction ────────

    /**
     * Build the **inner** shell command (no `bash -lc` wrapper, no `cd`
     * prefix). [AgentSession] wraps the result in a login shell and prepends
     * any cwd `cd …` itself.
     *
     * Each CLI shells out totally differently — see the per-spec docstrings.
     */
    fun buildExecCommand(input: ExecInput): String

    // ──────── Stream parsing ────────

    /**
     * Convert one JSONL line from the CLI's stdout into zero or more
     * `AgentMessage`s.
     *
     * Returns the empty list for events the UI shouldn't show (token-count
     * pings, queue bookkeeping, etc.). Returns a single `AgentMessage.Raw`
     * for non-JSON garbage (e.g. stderr leaks into stdout, login shell
     * sourcing noise).
     */
    fun parseStreamLine(line: String): List<AgentMessage>

    /**
     * Turn-scoped variant — `turnTag` is unique per `runOneShot`
     * invocation (typically `"t1_"`, `"t2_"`, …). Specs that derive
     * stable message ids from CLI-side item ids prepend this tag so
     * ids don't collide ACROSS turns.
     *
     * Codex specifically resets its `item.id` counter every `codex
     * exec` invocation, so `item_1` in turn 2 == `item_1` in turn 1.
     * Without a turn-tag prefix, the AssistantText emitted by turn 2
     * has the same stable id as turn 1's — `emitMsg` replaces turn
     * 1's bubble in place with turn 2's text, shifting the apparent
     * order of the chat.
     *
     * Default impl ignores the tag and delegates — specs that don't
     * use stable ids (Claude's `msg_xxx#blockIndex` is already
     * globally unique; Gemini's parser uses uuid) opt out by not
     * overriding.
     */
    fun parseStreamLine(line: String, turnTag: String): List<AgentMessage> =
        parseStreamLine(line)

    // ──────── Session discovery on disk ────────

    /**
     * Bash script body that lists this CLI's saved sessions on the server.
     *
     * Output contract: tab-separated lines, sorted by mtime descending:
     * `id<TAB>mtime<TAB>absolute_path<TAB>raw_preview`. The `raw_preview`
     * field is opaque to the caller — it's fed back into
     * [extractSessionPreview]. Null = CLI does not have on-disk sessions.
     *
     * Caller wraps in `bash -lc '<script>'`.
     */
    val listSessionsScript: String?

    /**
     * Turn the opaque `raw_preview` field from [listSessionsScript] into the
     * user-visible preview shown on session rows. Per-CLI because each one
     * encodes "the first user message" differently in its JSONL.
     */
    fun extractSessionPreview(rawPreview: String): String

    /**
     * The CLI's OWN session title (e.g. Claude's `ai-title`, shown in
     * `claude --resume`), if the agent has one and [listSessionsScript] encoded
     * it into `raw_preview`. Null = no title (the row falls back to the preview).
     * Lets the session row show the nice generated name as its accent header
     * while [extractSessionPreview] keeps returning the first-message text.
     */
    fun extractSessionTitle(rawPreview: String): String? = null

    /**
     * Shell command (run inside `bash -lc`) that permanently deletes one saved
     * session, given its resume [sessionId] and the on-disk [path] the listing
     * surfaced. Default removes that single file; CLIs whose sessions span
     * MULTIPLE files for one id (Gemini writes a fresh snapshot per resume)
     * override so a delete can't be resurrected by the next discovery sweep.
     */
    fun deleteSessionCommand(sessionId: String, path: String): String =
        "rm -f " + ai.eight24family.conch.agent.shellEscape(path)

    // ──────── Status probe (install + auth) ────────

    /**
     * Bash lines that emit:
     *   <agent>_inst=y|n   — `which <cli>` succeeded
     *   <agent>_auth=y|n   — credential file present
     *
     * Concatenated by [ai.eight24family.conch.agent.AgentStatusProbe] into
     * one round-trip. Lines must be valid sh; no leading/trailing newlines.
     */
    val statusProbeLines: String

    /**
     * OPTIONAL bash lines that VALIDATE the OAuth login for real by running the
     * CLI (creds-on-disk presence lies — token can be present but revoked / not
     * provisioned). Emits `<agent>_authok=y|n` (absent ⇒ nothing to validate).
     * Run SEPARATELY and ASYNC by [AgentStatusProbe.probeLiveAuth] (it spawns
     * the CLI, so it's slow) and merged into the already-shown fast-probe status
     * — never on the blocking fast path. Default empty = no live check.
     */
    val liveAuthProbeLines: String get() = ""

    // ──────── Model discovery ────────

    /**
     * Discover the universe of model names this CLI accepts.
     *
     * Returns `alias → human-readable label`. For Claude these come from the
     * `initialize` control handshake of a headless stream-json launch (the
     * CLI's own registry — the old interactive `/model` PTY scrape is gone).
     * For Codex it's the union of `~/.codex/config.toml` + bundled default
     * catalog. For Gemini it's a hardcoded alias table.
     *
     * Empty map = caller falls back to "type whatever, the CLI will reject
     * bad values".
     */
    suspend fun probeAvailableModels(exec: AgentExec): Map<String, String>

    /**
     * The CLI's effective default model — the one used when `--model`
     * isn't passed. For Codex, parsed from `~/.codex/config.toml`
     * top-level `model = "..."`. For Claude, the alias `default`
     * always points to the configured default in the CLI bundle so
     * the topbar can just show "Opus 4.7" / whatever. Null = no way
     * to know, caller shows something neutral.
     */
    suspend fun probeDefaultModel(exec: AgentExec): String? = null

    /**
     * Reasoning-effort metadata for one model. Returns null when the
     * spec doesn't support reasoning switching at all (Gemini), OR
     * when the cache hasn't been populated yet (codex before its
     * first probe).
     *
     * Specs implement this off either a hardcoded table (Claude has
     * fixed levels per family) or a memoised parse of the spec's
     * own catalog file (Codex reads `~/.codex/models_cache.json`'s
     * `supported_reasoning_levels` per slug). It's a synchronous
     * lookup so the topbar can populate submenus without a second
     * round-trip after the model list has loaded.
     */
    fun reasoningInfoFor(slug: String): ai.eight24family.conch.agent.spec.ModelReasoningInfo? = null

    /**
     * The CLI's effective default reasoning effort — what gets used
     * when neither the user picks one in our UI nor we pass an
     * explicit flag. For Codex this is `model_reasoning_effort` from
     * `~/.codex/config.toml`. For Claude / Gemini there's no
     * equivalent, return null.
     *
     * Surfaced in the topbar so the displayed effort matches what
     * codex actually runs, instead of falsely showing the model's
     * intrinsic default ("Medium") while codex silently uses the
     * user's `xhigh` from config.toml.
     */
    suspend fun probeDefaultReasoning(exec: AgentExec): String? = null

    /**
     * Serialize the probed reasoning catalog for cold-start persistence
     * (prefs). Null = this agent doesn't persist reasoning — Codex
     * rebuilds its per-slug catalog from `models_cache.json` on every
     * probe; Claude persists its uniform per-server effort catalog so a
     * cold app start doesn't regress to the hardcoded fallback ladder
     * until the (~8s) live probe lands.
     */
    fun serializeReasoningCatalog(catalog: Map<String, ModelReasoningInfo>): String? = null

    /**
     * Inverse of [serializeReasoningCatalog]: rebuild the per-slug
     * catalog for the given cached model keys. Default empty = no
     * hydrate for this agent.
     */
    fun deserializeReasoningCatalog(
        raw: String,
        modelKeys: Collection<String>,
    ): Map<String, ModelReasoningInfo> = emptyMap()

    // ──────── Slash command discovery ────────

    /**
     * Bash script that emits every user-authored slash command directory
     * (project + global). Null = CLI doesn't have user-authored slash
     * commands. The output format is whatever [parseCustomCommands]
     * understands.
     */
    val customCommandsScript: String?

    /** Convert the raw output of [customCommandsScript] into a typed list. */
    fun parseCustomCommands(rawOutput: String): List<SlashCommand>

    // ──────── Topbar / model picker UI strategy ────────

    /**
     * Per-agent strategy for how the chat topbar renders its model
     * label + dropdown. Centralises the "what to show" decisions that
     * used to live as `if (agentName == "claude") … else if
     * (agentName == "codex") …` branches inside the shared
     * `ChatScreen.TerminalTopBar` composable.
     *
     * See [AgentTopbarUi] for the contract.
     */
    val topbarUi: AgentTopbarUi

    // ──────── "Relax permissions" prompt ────────

    /**
     * Text the user sends from the shield-dropdown to ask THE AGENT (not us)
     * to relax its own approval settings persistently. Each CLI stores
     * approval policy in a different file with different keys — the prompt
     * spells those out so the model doesn't have to guess.
     */
    val disableApprovalsPrompt: String

    /**
     * One-line markdown describing what users get when they tap
     * "scaffold memory file for this repo" / `/init`. The action itself is
     * the same body across CLIs; only the filename changes.
     */
    val initRepoMemoryHint: String
        get() = "Scaffold $memoryFilename for this repository"

    /**
     * Bash command body that finds the cwd of a saved session by its
     * `resumeId`, printing a single line `"cwd":"<absolute-path>"` if
     * the session exists. Used by [ai.eight24family.conch.agent.AgentSession]
     * to `cd` into the session's recorded working directory **before**
     * spawning `--resume` — without it the CLI can fail with "No
     * conversation found" (Claude) or silently start a new thread
     * (Gemini's project_hash mismatch).
     *
     * Returns null when the CLI isn't cwd-locked (Codex resumes by global
     * id regardless of cwd).
     *
     * Caller wraps in `bash -lc '<script>'`.
     */
    fun cwdBackfillScript(resumeId: String): String?

    // ──────── Mirror turn-state (working / done / waiting) ────────

    /**
     * Projects this CLI's session JSONL into ONE record per RELEVANT line,
     * oldest→newest, as an ordered field list. The layout is private to the
     * spec — only [inferTurnState] (same spec) reads it back.
     *
     * Runs ON THE PHONE, against bytes the tail-poll has already downloaded.
     * It used to be a `jq` program executed on the user's server, which made
     * the single most important signal in the app — "is a turn still running?"
     * — depend on a binary that may not be installed, may not be on a
     * non-interactive login shell's PATH, or may be built without the regex
     * support the program needed. In every one of those cases jq printed
     * nothing, `stderr` was discarded, the record list came back EMPTY, and
     * [inferTurnState] answered all-false — including `turnComplete`, the one
     * flag the stuck-turn reconcile is gated on. The thinking indicator then
     * ran forever with nothing able to clear it (2026-07-29, ground-truthed:
     * `recs=0` on every tick for an entire session while `stat` on the same
     * command line parsed fine). Local projection also costs LESS, since the
     * bytes were already on the phone.
     *
     * Empty ⇒ this CLI has no file-based turn-state detection; the mirror then
     * relies only on the app-driven `curWorking` flag. Each CLI's JSONL is
     * shaped totally differently (Claude: `type:user/assistant` +
     * `message.stop_reason`; Codex: `type:event_msg` with
     * `payload.type:task_started/task_complete`), so this MUST be per-spec.
     *
     * Implementations MUST skip blank and malformed lines rather than throw:
     * the tail of a file being appended to is routinely a partial line, and
     * losing the whole window to one bad line is exactly what jq did.
     *
     * @param lines raw JSONL lines, oldest→newest.
     */

    /**
     * Implementations project one JSONL line per record; see the contract above.
     *
     * Why it moved here: the remote projection needed `jq` on the user's server.
     * When `jq` is absent from a non-interactive `bash -lc` PATH — or present but
     * built without oniguruma, so the `gsub` in the program is an undefined
     * function — jq emits NOTHING and exits. `2>/dev/null` hid it, records came
     * back empty, [inferTurnState] returned all-false including `turnComplete`,
     * and the stuck-turn reconcile could therefore NEVER fire: the thinking
     * indicator ran forever with no way for the user to clear it (2026-07-29,
     * ground-truthed from logcat — `recs=0` on every single tick while `stat` on
     * the same line parsed fine).
     *
     * Computing it locally also costs LESS: the tail-poll already downloads every
     * byte of the session file, so shipping a 400-record projection back on top
     * of that, every 5 seconds, was pure duplicate traffic on a metered link.
     *
     * Implementations MUST return the identical field layout their jq emits —
     * [inferTurnState] reads both by index, and an off-by-one silently corrupts
     * the turn verdict rather than failing loudly.
     *
     * @param lines raw JSONL lines, oldest→newest. Blank and malformed lines
     *   must be skipped, not throw: the tail of a file being appended to is
     *   routinely a partial line.
     */
    fun projectTurnStateRecords(lines: Sequence<String>): List<List<String>> = emptyList()

    /**
     * Decide the turn signals from the records [projectTurnStateRecords] produced
     * (oldest→newest) + how long the session file has been frozen ([frozenForMs],
     * null if unknown). Verdict must be DETERMINISTIC from the file content, not a
     * timeout (timeouts are at most a last-resort fallback for a malformed tail).
     * Default (no records / no support) = nothing running.
     */
    fun inferTurnState(records: List<List<String>>, frozenForMs: Long?): TurnSignals =
        TurnSignals()
}

/**
 * What the tail-poll needs to know about a mirrored turn's state, derived by the
 * per-CLI [AgentCliSpec.inferTurnState] from the session file's tail.
 *
 *  - [inFlight]       — a turn is actively running (model generating / a tool
 *                       running / a subagent working). Drives the spinner.
 *  - [waitingForUser] — the turn is BLOCKED on a human answer that is VISIBLE IN
 *                       THE FILE (e.g. a Codex approval the file records). Claude's
 *                       live AskUserQuestion is NOT here — it never hits the file
 *                       and is detected separately via the persistent stream's
 *                       pendingControls. Default false.
 *  - [thinking]       — the model is about to GENERATE (vs running a tool) — drives
 *                       the «with X effort» suffix.
 *  - [turnStartMs]    — epoch-ms of the current turn's start (for the elapsed timer).
 *  - [tokens]         — cumulative output tokens this turn (the «↓ N» counter).
 *  - [turnComplete]   — the file shows a DEFINITIVE terminal completion (Claude
 *                       assistant stop_reason ∈ end_turn/stop_sequence/max_tokens;
 *                       analogous done marker per agent). DISTINCT from `!inFlight`:
 *                       inFlight also goes false on the 12-min stale-mid-stream
 *                       fallback and on interrupts, which are NOT clean completions.
 *                       Use this — never `!inFlight` — to force-complete a STUCK
 *                       live turn, so a long silent research turn is never torn
 *                       down at the stale threshold. Default false.
 */
data class TurnSignals(
    val inFlight: Boolean = false,
    val waitingForUser: Boolean = false,
    val thinking: Boolean = false,
    val turnStartMs: Long? = null,
    val tokens: Long = 0L,
    val turnComplete: Boolean = false,
)

/**
 * Inputs to [AgentCliSpec.buildExecCommand]. Carried as a data class because
 * different specs read different subsets — Gemini ignores cwdSnapshot (CLI
 * has no `cd` semantics), Claude uses preGeneratedSessionId, Codex/Gemini
 * ignore it. Keeping all of them on one parameter object makes it cheap to
 * add a new field without touching every spec call site.
 */
data class ExecInput(
    /** Raw user prompt — spec is responsible for shell-escaping. */
    val text: String,
    /** CLI-side session id to resume, or null for a fresh session. */
    val resumeId: String?,
    /** Model override (`--model` value), or null to use CLI default. */
    val model: String?,
    /** Normalized approval/sandbox tier. Spec translates to per-CLI flags. */
    val approvalMode: AgentApprovalMode,
    /** Snapshot of cwd from history, or null for fresh chat. Spec usually
     *  ignores this — [AgentSession] handles `cd` itself. */
    val cwdSnapshot: String?,
    /**
     * For specs that support pre-setting a UUID (Claude). When non-null the
     * spec adds `--session-id <uuid>` and the caller can use the same uuid
     * for resume without ever parsing a system/init event.
     */
    val preGeneratedSessionId: String? = null,
    /**
     * Reasoning effort override. Codex translates to
     * `-c model_reasoning_effort="<X>"` (values `low|medium|high|xhigh`);
     * Claude translates to `--effort <X>` (`low|medium|high|max`);
     * Gemini ignores. Null = use whatever the CLI/model defaults to.
     */
    val reasoningEffort: String? = null,
)

/**
 * Minimal "run one command over SSH" closure handed to spec methods that need
 * to talk to the server (model probes, slash-command discovery). Lets the
 * spec stay ignorant of `SshClient` / `SshConnectionPool` / sshj
 * internals — and lets callers pass a closure that rides the
 * already-authenticated channel (no fresh handshake / no extra FIDO touch on
 * SK servers).
 */
fun interface AgentExec {
    suspend fun exec(command: String): String?
}

