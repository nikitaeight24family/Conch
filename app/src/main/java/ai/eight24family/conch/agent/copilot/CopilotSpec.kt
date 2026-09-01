package ai.eight24family.conch.agent.copilot

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.shellEscape
import ai.eight24family.conch.agent.spec.AgentCliSpec
import ai.eight24family.conch.agent.spec.AgentExec
import ai.eight24family.conch.agent.spec.AgentTopbarUi
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.agent.spec.ModelMenuItem
import ai.eight24family.conch.agent.spec.TopbarModelState
import ai.eight24family.conch.agent.spec.TurnSignals
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-CLI spec for **GitHub Copilot CLI** (`copilot` binary, npm
 * `@github/copilot` — a loader plus a per-platform native package carrying a
 * Node SEA binary AND the machine-readable event schema it emits:
 * `schemas/session-events.schema.json` + `copilot-sdk/generated/
 * session-events.d.ts` ship in the box).
 *
 * Authority for every flag and event type: the shipped schema + `copilot
 * --help` / `copilot help <topic>` of v1.0.80 + docs.github.com, captured in
 * `docs/cli-research-2026-08-grok-copilot.md` §2.
 *
 * **Headless invocation shape** we build:
 * ```
 * copilot -p "$PROMPT" --output-format json --no-auto-update --no-ask-user
 *     [--allow-all-tools | --plan | --yolo]
 *     [--resume=<uuid> | --session-id <uuid>]
 *     [--model <slug>] [--reasoning-effort <level>] 2>&1
 * ```
 *
 * `--output-format json` = JSONL, one event per line, envelope
 * `{type, data, id, timestamp, parentId, ephemeral?}`. `-p` needs no TTY, no
 * trust prompt fires, and tools are DENIED unless explicitly allowed —
 * `--allow-all-tools` is, per its own help text, "required for
 * non-interactive mode". `--no-ask-user` keeps the ask_user tool from
 * stalling a headless run. stderr carries the human-readable error text
 * (auth failures etc.) and is MERGED (`2>&1`) so failures reach the chat as
 * Raw lines — in JSON mode Copilot keeps stdout clean JSONL, so the merge
 * costs nothing on the happy path (verified live: events → stdout, error
 * prose → stderr).
 *
 * Sessions are GLOBAL (not cwd-locked): `~/.copilot/session-state/<uuid>/`
 * with `events.jsonl` (the same event vocabulary minus `ephemeral:true`
 * events), `workspace.yaml` (cwd/repo/branch), `plan.md`. `--session-id`
 * mints a new session with our UUID or resumes an existing one;
 * `--resume=<id|7+hex prefix|name>` and `--continue` also resume.
 */
object CopilotSpec : AgentCliSpec {

    override val agent = Agent.COPILOT
    override val displayName = "Copilot CLI"
    override val cliCommand = "copilot"
    override val npmPackage = "@github/copilot"
    override val guardHarnessId = "copilot"
    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_copilot

    /** Copilot has custom agents (`~/.copilot/agents`, `.github/agents`), but
     *  the app's subagent editor is Claude-shaped — keep off for now. */
    override val supportsSubagents = false
    override val supportsCustomSlashCommands = false
    override val supportsResume = true

    /** `--session-id <uuid>` mints a NEW session under our UUID (or resumes
     *  it if it exists — both behaviors are what the pre-set contract wants). */
    override val supportsPreSetSessionId = true

    /** Copilot has a REAL plan mode (`--plan` / `--mode plan`). */
    override val supportsPlanMode = true

    /** Copilot already defaults to device-code in an SSH session; the explicit
     *  flag pins that against a future default change, and `--no-auto-update`
     *  keeps a 100 MB self-replace out of the login path. */
    override val oauthLoginCommand = "copilot login --device-code --no-auto-update"

    /** The Copilot mascot's blinking eyes are drawn with quadrant blocks
     *  (▘▝ open → ╴╶ half → closed); the spinner cycles that family —
     *  recognizably Copilot, nobody else's sparkle. */
    override val spinnerGlyphs: List<String> = listOf("▘", "▝", "▖", "▗")

    /** Copilot's own status word — its TUI shows a shimmering "Thinking…"
     *  (→ "Thought for Ns"); one word, no gerund roulette. */
    override val spinnerVerbs: List<String> = listOf("Thinking")

    /**
     * Copilot's GLOBAL instructions file is `~/.copilot/copilot-instructions.md`
     * (its own convention — the repo-level twin lives at
     * `.github/copilot-instructions.md`, and it also reads repo AGENTS.md /
     * CLAUDE.md / GEMINI.md). The distinct filename keeps the memory editor
     * from colliding with Codex's AGENTS.md.
     */
    override val memoryFilename = "copilot-instructions.md"
    override val memoryGlobalPath = "\$HOME/.copilot/copilot-instructions.md"
    override val memoryGlobalDisplay = "~/.copilot/copilot-instructions.md"

    /** The repo-level file Copilot actually reads lives under .github/ —
     *  the default hint would scaffold a root file it ignores. */
    override val initRepoMemoryHint: String
        get() = "Scaffold .github/copilot-instructions.md for this repository"

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        val modelArg = input.model?.takeIf { it.isNotBlank() }
            ?.let { " --model ${shellEscape(it)}" } ?: ""
        // Accepted levels: none|minimal|low|medium|high|xhigh|max (matches the
        // CLI's --reasoning-effort help). Passed verbatim.
        val effortArg = input.reasoningEffort?.takeIf { it.isNotBlank() }
            ?.let { " --reasoning-effort ${shellEscape(it)}" } ?: ""
        // Headless approval vocabulary:
        //   PLAN → --plan (real plan mode; tools restricted to planning)
        //   SAFE → no allow flags: mutations are DENIED automatically (deny
        //          beats everything in -p mode) — read-only-ish, never hangs.
        //   AUTO → --allow-all-tools: writes allowed but PATH-SANDBOXED to
        //          cwd + temp (Copilot's default sandbox) — workspace-write
        //          semantics, the closest native match to "auto-edit".
        //   YOLO → --yolo (= allow all tools + all paths + all urls).
        val approvalArg = when (input.approvalMode) {
            AgentApprovalMode.PLAN -> " --plan"
            AgentApprovalMode.SAFE -> ""
            AgentApprovalMode.AUTO -> " --allow-all-tools"
            AgentApprovalMode.YOLO -> " --yolo"
        }
        // Resume wins over pre-set: --session-id also resumes when the id
        // exists, but --resume is the documented resume path (id, 7+ hex
        // prefix, or exact name). Copilot has no fork flag — forkSession is
        // ignored (the /fork command is interactive-only).
        val sessionArg = when {
            input.resumeId != null -> " --resume=${shellEscape(input.resumeId)}"
            input.preGeneratedSessionId != null ->
                " --session-id ${shellEscape(input.preGeneratedSessionId)}"
            else -> ""
        }
        return "copilot -p $escapedText --output-format json --no-auto-update" +
            " --no-ask-user" + approvalArg + sessionArg + modelArg + effortArg + " 2>&1"
    }

    override fun parseStreamLine(line: String): List<AgentMessage> =
        CopilotMessageParser.parse(line)

    /**
     * Sessions live flat under `~/.copilot/session-state/<uuid>/`. The
     * `path` column is the session's `events.jsonl` — history replay parses
     * it (same vocabulary as the live stream, minus ephemeral events) and
     * the turn-state mirror projects it. Activity = events.jsonl mtime.
     * Model/reasoning come from the last `session.start` /
     * `session.model_change` in the tail window; the session's own title —
     * `session.title_changed` — rides the preview column behind a Unit
     * Separator, exactly like Claude's ai-title.
     */
    override val listSessionsScript: String? = """
for d in ~/.copilot/session-state/*/; do
  f="${'$'}{d}events.jsonl"
  [ -f "${'$'}f" ] || continue
  id=${'$'}(basename "${'$'}d")
  case "${'$'}id" in
    *[!0-9a-fA-F-]*) continue;;
  esac
  mtime=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || stat -f %m "${'$'}f" 2>/dev/null)
  size=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || stat -f %z "${'$'}f" 2>/dev/null)
  # A session that never got a user turn is a launch artifact, not a chat.
  grep -q '"type":"user.message"' "${'$'}f" 2>/dev/null || continue
  # Current model: the LAST selectedModel (session.start) or model_change in
  # the trailing window; head fallback for tiny files.
  model=${'$'}(tail -c 131072 "${'$'}f" 2>/dev/null | grep -oE '"selectedModel"[[:space:]]*:[[:space:]]*"[^"]+"' | tail -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  [ -z "${'$'}model" ] && model=${'$'}(head -c 65536 "${'$'}f" 2>/dev/null | grep -oE '"selectedModel"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  reasoning=${'$'}(head -c 65536 "${'$'}f" 2>/dev/null | grep -oE '"reasoningEffort"[[:space:]]*:[[:space:]]*"(none|minimal|low|medium|high|xhigh|max)"' | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  # Copilot's own session title (session.title_changed → data.title).
  title=${'$'}(tail -c 131072 "${'$'}f" 2>/dev/null | grep '"type":"session.title_changed"' | tail -1 | grep -oE '"title"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed -E 's/.*:[[:space:]]*"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
  preview=${'$'}(grep -m1 '"type":"user.message"' "${'$'}f" 2>/dev/null | cut -b 1-700 | tr '\t' ' ')
  if [ -n "${'$'}title" ]; then preview=${'$'}(printf '%s\037%s' "${'$'}title" "${'$'}preview"); fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}id" "${'$'}mtime" "${'$'}f" "${'$'}model" "${'$'}reasoning" "${'$'}size" "${'$'}preview"
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
        // The candidate is a (possibly cut) user.message event — its prompt
        // text is data.content. Tolerate a missing closing quote from the cut.
        val m = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(body) ?: return ""
        return m.groupValues[1]
            .replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
            .replace("\\\"", "\"").replace("\\\\", "\\")
            .replace(Regex("\\s+"), " ").trim().take(140)
    }

    /**
     * One Copilot session = one directory under session-state (events,
     * workspace.yaml, plan.md, checkpoints). Remove the whole dir. The
     * sessions table in session-store.db keeps a row — harmless for OUR
     * listing (it walks directories), and the CLI tolerates a missing dir.
     */
    override fun deleteSessionCommand(sessionId: String, path: String): String {
        val p = shellEscape(path)
        return "d=\$(dirname $p); case \"\$d\" in */session-state/*) rm -rf \"\$d\";; *) rm -f $p;; esac"
    }

    override val statusProbeLines: String = """
echo "copilot_inst=${'$'}(command -v copilot >/dev/null 2>&1 && echo y || echo n)"
echo "copilot_ver=${'$'}(conch_ver copilot copilot)"
echo "copilot_latest=${'$'}(conch_latest copilot @github/copilot)"
CM=""
# Env tokens, the CLI's highest-precedence auth (COPILOT_GITHUB_TOKEN >
# GH_TOKEN > GITHUB_TOKEN). Presence only — values never read out.
if [ -n "${'$'}COPILOT_GITHUB_TOKEN" ] || [ -n "${'$'}GH_TOKEN" ] || [ -n "${'$'}GITHUB_TOKEN" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?(COPILOT_GITHUB_TOKEN|GH_TOKEN|GITHUB_TOKEN)=' ~/.bashrc ~/.profile ~/.bash_profile ~/.env 2>/dev/null; then CM="${'$'}CM token"; fi
# Stored OAuth: on a keyring-less server (the typical VPS) `copilot login`
# falls back to "a plain text config file under ~/.copilot/" (its own help
# text). The secret rides the settings store under a copilotToken key —
# grep for the KEY only, never echo content.
if grep -qs '"copilotToken"' ~/.copilot/config.json ~/.copilot/settings.json 2>/dev/null; then CM="${'$'}CM oauth"; fi
# gh CLI token: copilot auto-detects it when nothing above is set.
# `gh auth token` exits 0 iff a token exists; output is discarded unread.
if command -v gh >/dev/null 2>&1 && gh auth token >/dev/null 2>&1; then CM="${'$'}CM gh"; fi
echo "copilot_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
case " ${'$'}CM " in
  *" token "*) echo "copilot_active=token";;
  *" oauth "*) echo "copilot_active=oauth";;
  *" gh "*) echo "copilot_active=gh";;
  *) echo "copilot_active=";;
esac
""".trimIndent()

    /** Copilot's own registry, printed by its help — complete and version-
     *  matched to the installed binary, so it may confirm registry keys. */
    override val catalogIsAuthoritative = true

    /**
     * Model catalog from the CLI's OWN `copilot help config` — the `model`
     * setting section lists every accepted slug, one `- "slug"` line each
     * (verified on 1.0.80). That is the registry the /model picker uses, so
     * it is authoritative for the installed version. `auto` (documented on
     * `--model`: "'auto' lets Copilot pick") is prepended as its own row.
     */
    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> {
        val script = ai.eight24family.conch.agent.RemoteEnv.PATH_PREAMBLE_INLINE +
            "conch_timeout 20 copilot help config 2>/dev/null | sed -n '/`model`/,/^\$/p'"
        val raw = exec.exec("bash -lc " + shellEscape(script)).orEmpty()
        val out = linkedMapOf<String, String>()
        out["auto"] = "Auto"
        for (m in Regex("-\\s+\"([^\"]+)\"").findAll(raw)) {
            val slug = m.groupValues[1]
            out[slug] = copilotLabelFromId(slug)
        }
        android.util.Log.d("Conch-Models", "copilot help-config probe: ${out.size - 1} models")
        // Only the synthetic "auto" ⇒ the probe failed — return empty so the
        // caller keeps its cached catalog instead of a one-row picker.
        return if (out.size > 1) out else emptyMap()
    }

    /**
     * The model Copilot runs when we pass no `--model`: the persisted
     * `model` setting in `~/.copilot/settings.json` (written by /model).
     * Absent ⇒ null — the CLI then picks its own current default, which we
     * deliberately don't hardcode (it drifts between releases).
     */
    override suspend fun probeDefaultModel(exec: AgentExec): String? {
        val raw = exec.exec(
            "bash -lc " + shellEscape("cat \$HOME/.copilot/settings.json 2>/dev/null || true"),
        ).orEmpty()
        return Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
    }

    override val customCommandsScript: String? = null
    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> = emptyList()

    /**
     * Copilot resumes globally, but the session's WORK happens in the cwd
     * recorded in its workspace.yaml — backfill so the relaunch cd's there.
     * The yaml is tiny; its `cwd:` line is unquoted or single-quoted.
     */
    override fun cwdBackfillScript(resumeId: String): String {
        val q = shellEscape(resumeId)
        return "w=\"\$HOME/.copilot/session-state/\"$q\"/workspace.yaml\"; " +
            "if [ -f \"\$w\" ]; then " +
            "  c=\$(grep -m1 -E '^cwd:' \"\$w\" | sed -E \"s/^cwd:[[:space:]]*'?//; s/'?[[:space:]]*\$//\"); " +
            "  [ -n \"\$c\" ] && printf '\"cwd\":\"%s\"\\n' \"\$c\"; " +
            "fi"
    }

    override val topbarUi: AgentTopbarUi = CopilotTopbarUi

    /**
     * Copilot has NO persistent "always allow" config — approvals are
     * per-launch flags that Conch itself passes (`--allow-all-tools` /
     * `--yolo`). So the honest "relax" prompt tells the agent to tell the
     * USER where the real switch is, instead of pretending it can edit a
     * config that doesn't exist.
     */
    override val disableApprovalsPrompt: String = """
        Note: your tool approvals are controlled per-launch by CLI flags, not by a config file you can edit. Reply with exactly this, then continue your previous task:

        "Copilot CLI approvals are set per-launch. In this app, tap the shield icon in the chat's top bar and pick Auto or YOLO — future turns will then launch with --allow-all-tools / --yolo and stop asking."
    """.trimIndent()

    // ──────── Mirror turn-state (events.jsonl) ────────

    /**
     * Project each persisted event to `[marker, isoTs, outTokens]`:
     *  - `user.message`            → "user"  (turn begins)
     *  - `assistant.*` / `tool.*`  → "agent" (work in progress)
     *  - `assistant.usage`         → "tokens" (data.outputTokens per response)
     *  - `session.idle`            → "done"  (the CLI's terminal done signal)
     *  - `session.error`           → "done"  (turn over, albeit badly)
     */
    override fun projectTurnStateRecords(lines: Sequence<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (line in lines) {
            val t = line.trim()
            if (t.length < 2 || t[0] != '{') continue
            val obj = runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() ?: continue
            val type = obj["type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: continue
            val marker = when {
                type == "user.message" -> "user"
                type == "session.idle" || type == "session.error" ||
                    type == "session.task_complete" -> "done"
                type == "assistant.usage" -> "tokens"
                type.startsWith("assistant.") || type.startsWith("tool.") -> "agent"
                else -> continue
            }
            val ts = obj["timestamp"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            val tokens = if (marker == "tokens") runCatching {
                obj["data"]?.jsonObject?.get("outputTokens")?.jsonPrimitive?.content
            }.getOrNull() ?: "0" else "0"
            out += listOf(marker, ts, tokens)
        }
        return out
    }

    /**
     * DEFINITIVE Copilot turn state: `session.idle` is the CLI's own "done"
     * event (and `session.error`/`task_complete` also close the turn), so
     * the last boundary decides — with the standard staleness escape for a
     * turn whose completion never landed.
     */
    override fun inferTurnState(records: List<List<String>>, frozenForMs: Long?): TurnSignals {
        val recs = records.filter { it.isNotEmpty() }
        if (recs.isEmpty()) return TurnSignals()
        val lastBoundaryIdx = recs.indexOfLast { it[0] == "user" || it[0] == "done" }
        val startIdx = recs.indexOfLast { it[0] == "user" }
        val inFlight = lastBoundaryIdx >= 0 && recs[lastBoundaryIdx][0] == "user" &&
            (frozenForMs == null || frozenForMs < AWAIT_STALE_MS)
        // Thinking = no assistant/tool activity since the prompt yet.
        val thinking = inFlight && startIdx == recs.lastIndex
        val turnStartMs = if (inFlight && startIdx >= 0)
            recs[startIdx].getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ts ->
                runCatching { java.time.Instant.parse(ts).toEpochMilli() }.getOrNull()
            } else null
        val tokens = if (inFlight && startIdx >= 0)
            recs.drop(startIdx + 1).filter { it[0] == "tokens" }
                .sumOf { it.getOrNull(2)?.toLongOrNull() ?: 0L }
        else 0L
        return TurnSignals(
            inFlight = inFlight,
            thinking = thinking,
            turnStartMs = turnStartMs,
            tokens = tokens,
            turnComplete = lastBoundaryIdx >= 0 && recs[lastBoundaryIdx][0] == "done",
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val AWAIT_STALE_MS = 12 * 60_000L
}

/** "claude-sonnet-4.5" → "Claude Sonnet 4.5", "gpt-5.6-sol" → "GPT-5.6 Sol",
 *  "kimi-k2.7-code" → "Kimi K2.7 Code". Unknown shapes fall through as-is. */
internal fun copilotLabelFromId(value: String): String {
    if (value == "auto") return "Auto"
    val parts = value.split('-')
    if (parts.isEmpty()) return value
    return when (parts[0]) {
        // GPT keeps the family glued to its version: gpt-5.6-sol → GPT-5.6 Sol.
        "gpt" -> "GPT-" + parts.drop(1).joinToString(" ") { cap(it) }
        else -> parts.joinToString(" ") { cap(it) }
    }
}

private fun cap(p: String): String =
    if (p.firstOrNull()?.isDigit() == true) p
    else p.replaceFirstChar { it.uppercase() }

private object CopilotTopbarUi : AgentTopbarUi {
    /** Codex-style chain; "Auto" (the CLI picking per-turn) is a real,
     *  pickable value, not a placeholder. */
    override fun displayLabel(state: TopbarModelState): String? {
        val slug = state.selectedModel?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialModel?.takeIf { it.isNotBlank() }
            ?: state.observedModel?.takeIf { it.isNotBlank() }
            ?: state.defaultModel?.takeIf { it.isNotBlank() }
            ?: state.availableModels.keys.firstOrNull()
            ?: return null
        return state.availableModels[slug] ?: copilotLabelFromId(slug)
    }

    override fun isMenuEnabled(state: TopbarModelState): Boolean =
        !state.modelsProbing && state.availableModels.isNotEmpty()

    /** No reasoning submenu: Copilot's own /model picker has none either
     *  (the flag exists but per-model support isn't published — offering a
     *  slider we can't ground would invent facts). */
    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> =
        state.availableModels.entries.map { (slug, label) ->
            ModelMenuItem(display = label, storedValue = slug)
        }

    /** Show the session's own effort when known; never invent one. */
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
