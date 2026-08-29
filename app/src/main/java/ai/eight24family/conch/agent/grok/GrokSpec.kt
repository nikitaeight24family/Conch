package ai.eight24family.conch.agent.grok

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.shellEscape
import ai.eight24family.conch.agent.spec.AgentCliSpec
import ai.eight24family.conch.agent.spec.AgentExec
import ai.eight24family.conch.agent.spec.AgentTopbarUi
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.agent.spec.ModelMenuItem
import ai.eight24family.conch.agent.spec.ModelReasoningInfo
import ai.eight24family.conch.agent.spec.ReasoningLevel
import ai.eight24family.conch.agent.spec.TopbarModelState
import ai.eight24family.conch.agent.spec.TurnSignals
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-CLI spec for **xAI Grok Build** (`grok` binary, npm `@xai-official/grok`,
 * a ~136 MB native Rust binary installed by the npm trampoline's postinstall).
 *
 * Authority for every flag and event type: the CLI's OWN bundled user guide
 * (it writes a 24-chapter manual to `~/.grok/docs/user-guide/` on first run)
 * plus live runs of v1.0.5 on 2026-08-28, captured in
 * `docs/cli-research-2026-08-grok-copilot.md` §1.
 *
 * **Headless invocation shape** we build:
 * ```
 * GROK_DISABLE_AUTOUPDATER=1 grok -p "$PROMPT"
 *     --output-format streaming-messages-json --include-partial-messages
 *     [--permission-mode default|acceptEdits|plan|bypassPermissions]
 *     [-r <uuid> [--fork-session] | -s <uuid>] [-m <model>]
 *     [--reasoning-effort <level>] 2>/dev/null
 * ```
 *
 * `streaming-messages-json` is — by Grok's own documentation — "the Anthropic
 * Messages API stream-json wire format": `system/init` → `assistant`/`user`
 * frames → terminal `result`, with `--include-partial-messages` adding the
 * same `stream_event` deltas Claude emits. Their docs state a Claude-format
 * consumer "works without changes", so [parseStreamLine] delegates the live
 * stream to the battle-tested ClaudeMessageParser instead of re-implementing
 * a parser for Grok's simpler native NDJSON (which carries NO ids on its
 * text/thought chunks — a stateless parser cannot accumulate those).
 * This is a WIRE-FORMAT reuse only: nothing about Grok's UI identity
 * (name, icon, spinner, usage display) borrows from Claude.
 *
 * The saved-session shape is DIFFERENT from the live stream: sessions live
 * in `~/.grok/sessions/<url-encoded-cwd>/<uuidv7>/` and the authoritative
 * file is `updates.jsonl` — consolidated ACP `session/update` records (one
 * whole message block per line, stable `_meta.eventId`), closed by an
 * `_x.ai/session/update` `turn_completed` record carrying `stop_reason` +
 * usage. [GrokMessageParser] handles those for history replay, and the
 * turn-state mirror below reads them for the spinner verdict.
 *
 * stderr is DROPPED (`2>/dev/null`), unlike Claude's `2>&1`: verified on
 * 1.0.5, every error that matters is duplicated on stdout as a JSON
 * `{"type":"error"}` / result-with-errors line, while stderr additionally
 * carries updater noise and a doubled copy of the error text — merging
 * streams would render every failure twice.
 */
object GrokSpec : AgentCliSpec {

    override val agent = Agent.GROK
    override val displayName = "Grok Build"
    override val cliCommand = "grok"
    override val npmPackage = "@xai-official/grok"
    override val guardHarnessId = "grok"
    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_grok

    /** Grok spawns subagents (spawn_subagent tool, `.grok/agents/`), but the
     *  app's subagent EDITOR is still `~/.claude/agents`-shaped — keep the
     *  icon off until that screen learns Grok's layout. */
    override val supportsSubagents = false

    /** User-invocable skills become `/name` commands, but discovery of
     *  `~/.grok/skills/` is a follow-up; unknown slash commands are sent to
     *  the CLI anyway (client never vets), so nothing is blocked meanwhile. */
    override val supportsCustomSlashCommands = false
    override val supportsResume = true

    /** `-s/--session-id <uuid>` pre-sets the id for NEW sessions (errors if
     *  the uuid already exists — exactly the pre-generation contract). */
    override val supportsPreSetSessionId = true

    /** Grok has a REAL plan mode: `--permission-mode plan`. */
    override val supportsPlanMode = true

    /** Device-code flow — the smoothest of all: the CLI prints a URL with the
     *  user code embedded and POLLS by itself, so there is no callback to
     *  capture and no code to paste back. The creds poller sees the fresh
     *  `~/.grok/auth.json` and closes the dialog. */
    override val oauthLoginCommand = "GROK_DISABLE_AUTOUPDATER=1 grok login --device-code"

    /** Grok's own tool bullets: `◆` is its default block marker; the cycle
     *  pulses through its documented bullet charset — deliberately NOT
     *  Claude's sparkle and not the generic `| / - \`. */
    override val spinnerGlyphs: List<String> = listOf("·", "•", "◆", "•")

    /** Grok's own status vocabulary is a flat "Working" (its TUI shows
     *  "Working" / "Loading…"); one word, no gerund roulette. */
    override val spinnerVerbs: List<String> = listOf("Working")

    /**
     * Grok has NO GROK.md. Its native project-instruction lineup is
     * `Agents.md / Claude.md / CLAUDE.md / CLAUDE.local.md / AGENT.md /
     * AGENTS.md` (all matching files load), and its GLOBAL rules are the
     * `.md` files under `~/.grok/rules/` — always scanned, applies to all
     * projects (user guide ch. 12). `AGENT.md` is picked as OUR canonical name:
     * natively read at project level, valid as a global rules file, and
     * distinct from Codex's `AGENTS.md` (each agent's memory file must
     * stay unique so the memory editor never edits a foreign agent's file).
     */
    override val memoryFilename = "AGENT.md"
    override val memoryGlobalPath = "\$HOME/.grok/rules/AGENT.md"
    override val memoryGlobalDisplay = "~/.grok/rules/AGENT.md"

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        val modelArg = input.model?.takeIf { it.isNotBlank() }
            ?.let { " -m ${shellEscape(it)}" } ?: ""
        // Canonical accepted levels: none|minimal|low|medium|high|xhigh|max
        // (per-model menu decides which apply — grok-4.6 offers low..xhigh,
        // default high). Passed verbatim; the CLI rejects what it doesn't know.
        val effortArg = input.reasoningEffort?.takeIf { it.isNotBlank() }
            ?.let { " --reasoning-effort ${shellEscape(it)}" } ?: ""
        // Grok's permission modes are Claude-vocabulary compatible
        // (default | acceptEdits | plan | auto | dontAsk | bypassPermissions).
        // Headless `-p` streams are READ-ONLY — no approval round-trip exists
        // (user guide ch. 14) — so SAFE means "read-only tools run, mutations
        // are ask-gated with nobody to answer": honest, never hangs.
        val approvalArg = when (input.approvalMode) {
            AgentApprovalMode.PLAN -> " --permission-mode plan"
            AgentApprovalMode.SAFE -> " --permission-mode default"
            AgentApprovalMode.AUTO -> " --permission-mode acceptEdits"
            AgentApprovalMode.YOLO -> " --permission-mode bypassPermissions"
        }
        // `--fork-session` alongside `-r` = inherit the conversation, write to
        // a NEW id (same contract as Claude's flag of the same name).
        val fork = if (input.forkSession) " --fork-session" else ""
        val resumeArg = input.resumeId?.let { " -r ${shellEscape(it)}$fork" } ?: ""
        // Pre-set uuid only for FRESH sessions — `-s` + `-r` without
        // `--fork-session` is rejected by the CLI.
        val sessionIdArg = if (input.resumeId == null) {
            input.preGeneratedSessionId?.let { " -s ${shellEscape(it)}" } ?: ""
        } else ""
        // GROK_DISABLE_AUTOUPDATER: update checks print to stderr (suppressed
        // on non-TTY, but belt-and-braces per the CLI's own headless guide).
        // No stdbuf: the Rust binary line-flushes NDJSON on pipes (verified
        // live — thought deltas streamed through a pipe word by word).
        return "GROK_DISABLE_AUTOUPDATER=1 grok -p $escapedText" +
            " --output-format streaming-messages-json --include-partial-messages" +
            approvalArg + resumeArg + sessionIdArg + modelArg + effortArg +
            " 2>/dev/null"
    }

    override fun parseStreamLine(line: String): List<AgentMessage> =
        GrokMessageParser.parse(line)

    /**
     * One row per saved session: `~/.grok/sessions/<encoded-cwd>/<uuid>/`
     * holds `summary.json` (id, cwd, current_model_id, reasoning_effort,
     * generated_title) + `updates.jsonl` (the activity file the tail-poll
     * watches — every turn appends here, and `turn_completed` closes it).
     *
     * The `path` column is updates.jsonl: it is what the mirror projects
     * turn-state from AND what history replay parses ([GrokMessageParser]
     * reads the ACP record shapes). Activity = updates.jsonl mtime — Grok
     * appends on every message, and summary.json is rewritten on unrelated
     * housekeeping, so the updates file is the honest clock.
     */
    override val listSessionsScript: String? = """
for d in ~/.grok/sessions/*/*/; do
  s="${'$'}{d}summary.json"
  u="${'$'}{d}updates.jsonl"
  [ -f "${'$'}s" ] || continue
  [ -f "${'$'}u" ] || continue
  # summary.json: {"info":{"id":"<uuid>","cwd":"…"},…} — info.id is the FIRST
  # "id" in the file and is exactly what `grok -r <id>` accepts.
  id=${'$'}(head -c 4096 "${'$'}s" 2>/dev/null | grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  [ -n "${'$'}id" ] || continue
  mtime=${'$'}(stat -c %Y "${'$'}u" 2>/dev/null || stat -f %m "${'$'}u" 2>/dev/null)
  size=${'$'}(stat -c %s "${'$'}u" 2>/dev/null || stat -f %z "${'$'}u" 2>/dev/null)
  # An updates file with no real turn (a launch that died before the first
  # prompt) is not a session — 64 bytes is far below any real user turn.
  [ "${'$'}{size:-0}" -lt 64 ] && continue
  model=${'$'}(grep -oE '"current_model_id"[[:space:]]*:[[:space:]]*"[^"]+"' "${'$'}s" 2>/dev/null | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  reasoning=${'$'}(grep -oE '"reasoning_effort"[[:space:]]*:[[:space:]]*"(none|minimal|low|medium|high|xhigh|max)"' "${'$'}s" 2>/dev/null | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  # Grok's OWN generated session title (regenerated as the chat grows; a
  # user rename sets title_is_manual but reuses the same field). Prepended
  # to the preview column with a Unit Separator so extractSessionTitle can
  # split it back off — same mechanism as Claude's ai-title.
  title=${'$'}(grep -oE '"generated_title"[[:space:]]*:[[:space:]]*"[^"]*"' "${'$'}s" 2>/dev/null | head -1 | sed -E 's/.*:[[:space:]]*"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
  # First user prompt = the first user_message_chunk record. Cap the cut at
  # 700 bytes — the JSON prefix before the text measures ~230 B, leaving
  # plenty for a 140-char preview; extractSessionPreview regex-salvages a
  # mid-JSON cut.
  preview=${'$'}(grep -m1 '"sessionUpdate":"user_message_chunk"' "${'$'}u" 2>/dev/null | cut -b 1-700 | tr '\t' ' ')
  if [ -n "${'$'}title" ]; then preview=${'$'}(printf '%s\037%s' "${'$'}title" "${'$'}preview"); fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}id" "${'$'}mtime" "${'$'}u" "${'$'}model" "${'$'}reasoning" "${'$'}size" "${'$'}preview"
done | sort -t'	' -k2 -rn | head -300
""".trimIndent()

    override fun extractSessionTitle(rawPreview: String): String? {
        val us = 0x1F.toChar()
        if (!rawPreview.contains(us)) return null
        return rawPreview.substringBefore(us).trim().ifBlank { null }?.take(140)
    }

    override fun extractSessionPreview(rawPreview: String): String {
        if (rawPreview.isBlank()) return ""
        val us = 0x1F.toChar()
        val body = if (rawPreview.contains(us)) rawPreview.substringAfter(us) else rawPreview
        // The candidate is a (possibly cut) user_message_chunk record — pull
        // the first "text" value, tolerating a missing closing quote.
        val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(body) ?: return ""
        return m.groupValues[1]
            .replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
            .replace("\\\"", "\"").replace("\\\\", "\\")
            .replace(Regex("\\s+"), " ").trim().take(140)
    }

    /**
     * One Grok session = one whole DIRECTORY (summary.json, updates.jsonl,
     * chat_history.jsonl, rewind points, subagent snapshots…). The surfaced
     * [path] is the updates.jsonl inside it — removing only that file would
     * leave a summary.json the next sweep… skips (no updates file), but the
     * CLI's own `grok sessions list` would still show a ghost. Remove the
     * directory, after checking the surfaced path really is a session file.
     */
    override fun deleteSessionCommand(sessionId: String, path: String): String {
        val p = shellEscape(path)
        return "d=\$(dirname $p); case \"\$d\" in */sessions/*) rm -rf \"\$d\";; *) rm -f $p;; esac"
    }

    override val statusProbeLines: String = """
echo "grok_inst=${'$'}(command -v grok >/dev/null 2>&1 && echo y || echo n)"
echo "grok_ver=${'$'}(conch_ver grok grok)"
echo "grok_latest=${'$'}(conch_latest grok @xai-official/grok)"
CM=""
# OAuth = a non-trivial ~/.grok/auth.json (0600, hot-reloaded by the CLI).
# The file is created only by a real login; a bare lock file sits NEXT to
# it (auth.json.lock) and never matches -s. GROK_HOME relocations are a
# power-user setup we don't chase in the fast probe.
if [ -s "${'$'}HOME/.grok/auth.json" ]; then CM="${'$'}CM oauth"; fi
if [ -n "${'$'}XAI_API_KEY" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?XAI_API_KEY=' ~/.bashrc ~/.profile ~/.bash_profile ~/.env 2>/dev/null; then CM="${'$'}CM api"; fi
echo "grok_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
# Per-request precedence in the CLI: session token (auth.json) beats
# XAI_API_KEY — so oauth wins the "active" verdict whenever present.
case " ${'$'}CM " in
  *" oauth "*) echo "grok_active=oauth";;
  *" api "*) echo "grok_active=api";;
  *) echo "grok_active=";;
esac
""".trimIndent()

    /** LIVE auth validation: `grok models` prints "You are logged in with
     *  grok.com." / "You are not authenticated." as its FIRST line, exits 0
     *  either way, and answers from local state in well under a second —
     *  the cheapest truth available (creds-on-disk presence can lie after a
     *  revocation). Only run when the fast probe saw an oauth cred, same
     *  gating as Gemini's live check. */
    override val liveAuthProbeLines: String = """
if command -v grok >/dev/null 2>&1 && [ -s "${'$'}HOME/.grok/auth.json" ]; then
  GLO=${'$'}(GROK_DISABLE_AUTOUPDATER=1 conch_timeout 20 grok models 2>&1 | head -c 400)
  case "${'$'}GLO" in
    *"not authenticated"*) echo "grok_authok=n";;
    *"logged in"*) echo "grok_authok=y";;
  esac
fi
""".trimIndent()

    /** The catalog is `grok models`' own list — complete for THIS account
     *  (entitlements included), so it may confirm registry keys. */
    override val catalogIsAuthoritative = true

    /**
     * Model catalog from the CLI's own `grok models` (works headless, no
     * TTY, answers even unauthenticated — then with the full public list):
     * ```
     * You are logged in with grok.com.
     *
     * Default model: grok-4.6
     *
     * Available models:
     *   * grok-4.6 (default)
     *   - grok-4.5
     * ```
     * The `*` row is the CLI default — stashed for [probeDefaultModel] so
     * a fresh chat's topbar shows the model Grok will actually start on.
     */
    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> {
        val raw = exec.exec(
            "bash -lc " + shellEscape(
                ai.eight24family.conch.agent.RemoteEnv.PATH_PREAMBLE_INLINE +
                    "GROK_DISABLE_AUTOUPDATER=1 grok models 2>/dev/null",
            ),
        ).orEmpty()
        val out = linkedMapOf<String, String>()
        var default: String? = null
        val rowRe = Regex("^\\s*([*-])\\s+(\\S+)")
        var inList = false
        for (line in raw.lineSequence()) {
            if (line.contains("Available models")) { inList = true; continue }
            if (!inList) continue
            val m = rowRe.find(line) ?: continue
            val slug = m.groupValues[2]
            out[slug] = grokLabelFromId(slug)
            if (m.groupValues[1] == "*" || line.contains("(default)")) default = slug
        }
        // "Default model: X" header is a second, list-independent source.
        if (default == null) {
            default = Regex("Default model:\\s*(\\S+)").find(raw)?.groupValues?.getOrNull(1)
        }
        if (out.isNotEmpty()) grokDefaultModel = default ?: out.keys.first()
        android.util.Log.d("SshAi-Models", "grok models probe: ${out.keys} default=$grokDefaultModel")
        return out
    }

    override suspend fun probeDefaultModel(exec: AgentExec): String? = grokDefaultModel

    /**
     * Grok's effort ladder for its own models — the canonical CLI levels the
     * `/effort` picker offers for grok-4.x (low/medium/high/xhigh; high is
     * the default per the ACP `initialize` modelState of 1.0.5). Hardcoded
     * fallback only until an ACP-handshake probe ships; the CLI accepts the
     * flag uniformly, so one catalog serves every slug (same shape Claude
     * used before its live probe).
     */
    override fun reasoningInfoFor(slug: String): ModelReasoningInfo = GROK_REASONING_INFO

    override val customCommandsScript: String? = null
    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> = emptyList()

    /**
     * `grok -r <uuid>` resolves ids globally, but the session's WORK happens
     * in its recorded cwd — backfill it from summary.json (`info.cwd`) so
     * the relaunch cd's to where the session actually lives. The session
     * dir name is the uuid, so the lookup is a cheap find.
     */
    override fun cwdBackfillScript(resumeId: String): String {
        val q = shellEscape(resumeId)
        return "s=\$(find ~/.grok/sessions -maxdepth 2 -type d -name $q 2>/dev/null | head -1); " +
            "if [ -n \"\$s\" ] && [ -f \"\$s/summary.json\" ]; then " +
            "  head -c 4096 \"\$s/summary.json\" | grep -oE '\"cwd\"[[:space:]]*:[[:space:]]*\"[^\"]*\"' | head -1; " +
            "fi"
    }

    override val topbarUi: AgentTopbarUi = GrokTopbarUi

    override val disableApprovalsPrompt: String = """
        Stop asking me to approve tool calls — relax your own permission mode persistently, then continue with whatever you were working on before this message.

        Step 1: Edit ~/.grok/config.toml (create it if missing) so it contains:
        [ui]
        permission_mode = "always-approve"
        If the file already has other sections/keys, merge — do NOT clobber unrelated config.

        Step 2: Verify by reading the file back; [ui] permission_mode must be "always-approve".

        Step 3: Resume the task you were doing right before I sent this message. If you were about to ask for approval to run a tool, just run it now. Don't pause to reconfirm.
    """.trimIndent()

    // ──────── Mirror turn-state (updates.jsonl) ────────

    /**
     * Project each consolidated ACP record to `[marker, epochMs, outTokens]`:
     *  - `user_message_chunk`  → "user"  (turn begins; `_meta.agentTimestampMs`)
     *  - `agent_message_chunk` / `agent_thought_chunk` / `tool_call*` → "agent"
     *  - `turn_completed` (`_x.ai/session/update`) → "done" + usage.outputTokens
     * Blank/malformed/foreign lines are skipped, never thrown on — the tail of
     * a file being appended to is routinely a partial line.
     */
    override fun projectTurnStateRecords(lines: Sequence<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (line in lines) {
            val t = line.trim()
            if (t.length < 2 || t[0] != '{') continue
            if (!t.contains("\"sessionUpdate\"")) continue
            val obj = runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() ?: continue
            val params = runCatching { obj["params"]?.jsonObject }.getOrNull() ?: continue
            val update = runCatching { params["update"]?.jsonObject }.getOrNull() ?: continue
            val kind = update["sessionUpdate"]?.let {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            } ?: continue
            val marker = when (kind) {
                "user_message_chunk" -> "user"
                "agent_message_chunk", "agent_thought_chunk",
                "tool_call", "tool_call_update", "plan" -> "agent"
                "turn_completed" -> "done"
                else -> continue
            }
            // Timestamps: `_meta.agentTimestampMs` (millis) on the update, else
            // the top-level `timestamp` (epoch SECONDS) every line carries.
            val ms = runCatching {
                update["_meta"]?.jsonObject?.get("agentTimestampMs")?.jsonPrimitive?.content?.toLongOrNull()
            }.getOrNull()
                ?: runCatching {
                    params["_meta"]?.jsonObject?.get("agentTimestampMs")?.jsonPrimitive?.content?.toLongOrNull()
                }.getOrNull()
                ?: obj["timestamp"]?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() }
                    ?.let { if (it < 1_000_000_000_000L) it * 1000 else it }
            val tokens = if (marker == "done") runCatching {
                update["usage"]?.jsonObject?.get("outputTokens")?.jsonPrimitive?.content
            }.getOrNull() ?: "0" else "0"
            out += listOf(marker, ms?.toString().orEmpty(), tokens)
        }
        return out
    }

    /**
     * DEFINITIVE Grok turn state: `turn_completed` IS the terminal record the
     * CLI writes for every finished turn (verified live, 1.0.5), so the last
     * boundary decides — same structure as Codex's task_started/task_complete
     * verdict, with the same staleness escape for a turn whose completion
     * never landed (killed CLI, truncated file).
     */
    override fun inferTurnState(records: List<List<String>>, frozenForMs: Long?): TurnSignals {
        val recs = records.filter { it.isNotEmpty() }
        if (recs.isEmpty()) return TurnSignals()
        val last = recs.last()
        val startIdx = recs.indexOfLast { it[0] == "user" }
        val inFlight = when (last[0]) {
            "done" -> false
            else -> frozenForMs == null || frozenForMs < AWAIT_STALE_MS
        }
        val thinking = inFlight && last[0] == "user"
        val turnStartMs = if (inFlight && startIdx >= 0)
            recs[startIdx].getOrNull(1)?.toLongOrNull() else null
        return TurnSignals(
            inFlight = inFlight,
            thinking = thinking,
            turnStartMs = turnStartMs,
            // The consolidated chunks carry no per-record output counts, so a
            // MIRRORED in-flight grok turn shows elapsed time without the ↓N
            // counter (the live stream feeds tokens for our own turns).
            tokens = 0L,
            // The CLI's own terminal record — safe for the stuck-turn
            // reconcile, never inferred from a timeout.
            turnComplete = last[0] == "done",
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** FALLBACK ONLY: last record isn't `turn_completed` and the file has
     *  frozen — treat as a wedged/abandoned turn past this. */
    private val AWAIT_STALE_MS = 12 * 60_000L
}

/** "grok-4.6" → "Grok 4.6"; unknown ids fall through unchanged (honest). */
internal fun grokLabelFromId(value: String): String {
    val m = Regex("^grok-(\\d+(?:\\.\\d+)*)(?:-(.+))?$").matchEntire(value) ?: return value
    val suffix = m.groupValues[2].takeIf { it.isNotEmpty() }
        ?.split('-')?.joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
        ?.let { " $it" } ?: ""
    return "Grok ${m.groupValues[1]}$suffix"
}

/** The `*` row of the last `grok models` probe — what a fresh chat starts on.
 *  Volatile spec-level stash, same pattern as [claudeDefaultModel]. */
@Volatile
internal var grokDefaultModel: String? = null

/** Canonical grok-4.x effort ladder (ACP initialize modelState, 1.0.5):
 *  low / medium / high (default) / xhigh. Descriptions are Grok's own
 *  level semantics, phrased neutrally. */
private val GROK_REASONING_INFO = ModelReasoningInfo(
    defaultEffort = "high",
    levels = listOf(
        ReasoningLevel("low", "low", "Fast responses, lighter reasoning"),
        ReasoningLevel("medium", "medium", "Balanced speed and depth"),
        ReasoningLevel("high", "high", "Deeper reasoning (default)"),
        ReasoningLevel("xhigh", "xhigh", "Maximum reasoning budget"),
    ),
)

private object GrokTopbarUi : AgentTopbarUi {
    /** Same five-source chain as Codex: explicit pick → session header →
     *  live observation → CLI default → first catalog row; resolve the slug
     *  to its label, never invent a placeholder. */
    override fun displayLabel(state: TopbarModelState): String? {
        val slug = state.selectedModel?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialModel?.takeIf { it.isNotBlank() }
            ?: state.observedModel?.takeIf { it.isNotBlank() }
            ?: state.defaultModel?.takeIf { it.isNotBlank() }
            ?: state.availableModels.keys.firstOrNull()
            ?: return null
        return state.availableModels[slug] ?: grokLabelFromId(slug)
    }

    override fun isMenuEnabled(state: TopbarModelState): Boolean =
        !state.modelsProbing && state.availableModels.isNotEmpty()

    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> =
        state.availableModels.entries.map { (slug, label) ->
            val info = state.reasoningCatalog[slug]
            ModelMenuItem(
                display = label,
                storedValue = slug,
                reasoning = info?.levels.orEmpty(),
                defaultReasoning = info?.defaultEffort,
            )
        }

    /** Raw effort token, exactly as the CLI spells it (the session file's
     *  own `reasoning_effort` field) — pick beats observation only while
     *  newer, and nothing is printed when nothing is known. */
    override fun reasoningLabel(state: TopbarModelState): String? {
        val pick = state.selectedReasoning?.takeIf { it.isNotBlank() }
        val seen = state.observedReasoning?.takeIf { it.isNotBlank() }
        return when {
            pick != null && (seen == null || state.reasoningPickIsNewer) -> pick
            seen != null -> seen
            else -> state.sessionInitialReasoning?.takeIf { it.isNotBlank() }
        }
    }
}
