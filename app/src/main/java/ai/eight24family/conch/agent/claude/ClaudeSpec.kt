package ai.eight24family.conch.agent.claude

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
import ai.eight24family.conch.agent.spec.PtyProbe
import ai.eight24family.conch.agent.spec.ReasoningLevel
import ai.eight24family.conch.agent.spec.TopbarModelState
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-CLI spec for **Anthropic Claude Code** (`claude` binary, npm
 * `@anthropic-ai/claude-code`).
 *
 * Authority for every flag and event type: Anthropic CLI reference + headless
 * docs, captured in `docs/cli-research-2026-05.md` §1.
 *
 * **Headless invocation shape** we build:
 * ```
 * printf '%s' "$PROMPT" | stdbuf -oL claude --print
 *     --output-format stream-json --include-partial-messages --verbose
 *     [--permission-mode acceptEdits | --dangerously-skip-permissions]
 *     [--resume <uuid>] [--model <alias-or-full>]
 *     2>&1
 * ```
 *
 * `stdbuf -oL` forces line-buffered stdout because Claude's node runtime sees
 * SSH stdout as a non-TTY and flips to fully-buffered mode by default; without
 * `stdbuf` every stream-json delta piles up in a 4 KB userland buffer and
 * only lands when the process exits, killing the live-streaming UX.
 */
object ClaudeSpec : AgentCliSpec {

    override val agent = Agent.CLAUDE
    override val displayName = "Claude Code"
    override val cliCommand = "claude"
    override val npmPackage = "@anthropic-ai/claude-code"
    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_claude

    override val supportsSubagents = true
    override val supportsCustomSlashCommands = true
    override val supportsResume = true
    /**
     * Claude exposes `--session-id <uuid>` which lets us pre-generate the
     * UUID instead of parsing it out of the first `system/init` event. We
     * don't take advantage of this yet — current code parses `system_id` —
     * but the capability is here for future-proofing.
     */
    override val supportsPreSetSessionId = true

    override val memoryFilename = "CLAUDE.md"
    override val memoryGlobalPath = "\$HOME/.claude/CLAUDE.md"
    override val memoryGlobalDisplay = "~/.claude/CLAUDE.md"

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        val resume = input.resumeId?.let { " --resume ${shellEscape(it)}" } ?: ""
        val modelArg = input.model?.takeIf { it.isNotBlank() }
            ?.let { " --model ${shellEscape(it)}" } ?: ""
        val approvalArg = when (input.approvalMode) {
            AgentApprovalMode.SAFE -> ""    // CLI prompts; in --print mode unanswered prompts may stall
            AgentApprovalMode.AUTO -> " --permission-mode acceptEdits"
            AgentApprovalMode.YOLO -> " --dangerously-skip-permissions"
        }
        // `IS_SANDBOX=1` is Claude Code's escape hatch for running
        // `--dangerously-skip-permissions` as root/sudo. Without it
        // the CLI exits with code 1 and the message
        // "--dangerously-skip-permissions cannot be used with root/sudo
        // privileges for security reasons" — exactly the symptom we
        // saw on a typical VPS where the user is root.
        //
        // Confirmed undocumented but accepted by the CLI per GitHub
        // issues #9184 / #3490 / HN discussion. The companion var
        // `CLAUDE_CODE_BUBBLEWRAP=1` works the same; we set both so
        // either-branch source code accepts it.
        //
        // Only set in YOLO mode (the only mode that uses --dangerously-
        // skip-permissions); harmless to leave on always but no point.
        val sandboxEnv = if (input.approvalMode == AgentApprovalMode.YOLO)
            "IS_SANDBOX=1 CLAUDE_CODE_BUBBLEWRAP=1 "
        else ""
        val sessionIdArg = input.preGeneratedSessionId
            ?.let { " --session-id ${shellEscape(it)}" } ?: ""
        // Reasoning effort → an EXPLICIT, fixed thinking budget, NOT the
        // adaptive `--effort` flag. Adaptive thinking (Opus 4.8 default) lets
        // the model pick depth itself and, on a remote/resumed `--print` run,
        // doesn't visibly honor the picked level — the user verified the
        // selector "didn't switch anything on the server". Pinning the budget
        // with CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING=1 + MAX_THINKING_TOKENS=N
        // makes the WHOLE session (every turn, incl. resume) run at exactly the
        // level picked, and a different session shows its own level. The env is
        // emitted per launch, so it's resume-proof by construction. No pick ⇒
        // no env ⇒ the model's native adaptive default is left untouched.
        val thinkingEnv = thinkingBudget(input.reasoningEffort)
            ?.let { "CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING=1 MAX_THINKING_TOKENS=$it " } ?: ""
        // `--verbose` is REQUIRED alongside `stream-json` — without it Claude
        // emits only the final `result` event (silent JSONL), a common
        // gotcha that costs hours of "why isn't streaming working".
        return "printf '%s' $escapedText | ${sandboxEnv}${thinkingEnv}stdbuf -oL claude --print " +
            "--output-format stream-json --include-partial-messages --verbose" +
            "$approvalArg$resume$modelArg$sessionIdArg 2>&1"
    }

    /** UI reasoning level → fixed MAX_THINKING_TOKENS budget. Opus caps at
     *  31999; the ladder is chosen to be clearly distinguishable per level
     *  (≈ the classic think / megathink / ultrathink tiers). null for an
     *  unknown/blank level → leave the CLI on its adaptive default. */
    private fun thinkingBudget(effort: String?): Int? = when (effort?.trim()?.lowercase()) {
        "low" -> 4096
        "medium" -> 12000
        "high" -> 24000
        "max" -> 31999
        else -> null
    }

    override fun parseStreamLine(line: String): List<AgentMessage> =
        ClaudeMessageParser.parse(line)

    override val listSessionsScript: String? = """
for f in ~/.claude/projects/*/*.jsonl; do
  [ -f "${'$'}f" ] || continue
  id="${'$'}{f##*/}"
  id="${'$'}{id%.jsonl}"
  mtime=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || stat -f %m "${'$'}f" 2>/dev/null)
  size=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || stat -f %z "${'$'}f" 2>/dev/null)
  # Model: Claude system init events stamp every --print with the
  # resolved model id; grep the first match from the session file.
  model=${'$'}(grep -m1 -oE '"model"[[:space:]]*:[[:space:]]*"[^"]+"' "${'$'}f" 2>/dev/null | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  candidates=${'$'}(grep '"type":"user"' "${'$'}f" 2>/dev/null | head -n 8 | tr '\t' ' ' | tr '\n' '\036')
  # Claude's OWN auto-generated session title — the nice 4-6 word name shown in
  # `claude --resume`. Stored in the JSONL as {"type":"ai-title","aiTitle":"…"};
  # take the LAST one (it's regenerated/duplicated). Prepend it to the preview
  # column with a Unit Separator (U+001F) so the app shows the title instead of
  # the raw first message (extractSessionPreview splits it back off). Falls
  # through to candidates when a session has no title yet. Strip tab/RS/US/NL
  # from the title so it can't corrupt the column or the separator.
  title=${'$'}(grep -ao '"aiTitle":"[^"]*"' "${'$'}f" 2>/dev/null | tail -1 | sed -E 's/.*"aiTitle":"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
  if [ -n "${'$'}title" ]; then preview=${'$'}(printf '%s\037%s' "${'$'}title" "${'$'}candidates"); else preview="${'$'}candidates"; fi
  # 7-col contract: id, mtime, path, model, reasoning(empty), size, preview.
  printf '%s\t%s\t%s\t%s\t\t%s\t%s\n' "${'$'}id" "${'$'}mtime" "${'$'}f" "${'$'}model" "${'$'}size" "${'$'}preview"
done | sort -t'	' -k2 -rn | head -500
""".trimIndent()

    override fun extractSessionTitle(rawPreview: String): String? {
        // listSessionsScript prepends Claude's own ai-title + a Unit Separator
        // (U+001F) to the preview column when the session has one. 0x1F.toChar()
        // (not a char/\u literal — the editor mangles the non-printable).
        val us = 0x1F.toChar()
        if (!rawPreview.contains(us)) return null
        return rawPreview.substringBefore(us).trim().ifBlank { null }?.take(140)
    }

    override fun extractSessionPreview(rawPreview: String): String {
        if (rawPreview.isBlank()) return ""
        // Claude's OWN session title (type:"ai-title" → aiTitle) is prepended by
        // listSessionsScript with a Unit Separator (U+001F) when present — prefer
        // it (the nice `/resume` name the user asked for) over the raw first
        // message. \u escape (not the literal char) for the same editor-safety
        // reason as the  below.
        val us = ''
        // The title (before U+001F) is returned separately by extractSessionTitle
        // and shown as the row's accent header; HERE we return the first-message
        // text (after U+001F) so the row's dim subtitle stays the message.
        val body = if (rawPreview.contains(us)) rawPreview.substringAfter(us) else rawPreview
        // ASCII Record Separator (U+001E) joins multiple candidate user
        // lines emitted by listSessionsScript's `tr '\n' '\036'`. Use
        // the explicit \u escape so editors that strip non-printable
        // chars (which has bitten this codebase before) don't silently
        // turn this into an empty char literal.
        val rs = '\u001E'
        val candidates = if (body.contains(rs)) body.split(rs) else listOf(body)
        var fallback: String? = null
        for (c in candidates) {
            val text = textOf(c).trim()
            if (text.isBlank()) continue
            if (ClaudeMessageParser.isSyntheticUserText(text)) continue
            val cleaned = text.replace(Regex("\\s+"), " ").trim()
            if (cleaned.length <= 3) {
                if (fallback == null) fallback = cleaned
                continue
            }
            return cleaned.take(140)
        }
        return fallback?.take(140).orEmpty()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun textOf(line: String): String {
        val obj = SilentlyTry.logged("SshAi-ClaudeSpec", "parse line json") { json.parseToJsonElement(line).jsonObject } ?: return ""
        val msg = SilentlyTry.logged("SshAi-ClaudeSpec", "read message obj") { obj["message"]?.jsonObject } ?: return ""
        val content = msg["content"] ?: return ""
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> firstTextFromBlocks(content)
            else -> ""
        }
    }

    private fun firstTextFromBlocks(arr: JsonArray): String {
        for (block in arr) {
            val o = SilentlyTry.logged("SshAi-ClaudeSpec", "cast block to JsonObject") { block.jsonObject } ?: continue
            val text = o["text"]?.jsonPrimitive?.contentOrNull
                ?: o["content"]?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrBlank()) return text
        }
        return ""
    }

    override val statusProbeLines: String = """
echo "claude_inst=${'$'}(command -v claude >/dev/null 2>&1 && echo y || echo n)"
echo "claude_ver=${'$'}(claude --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
# Anthropic's primary distribution is the native installer, but they ALSO
# publish to npm as @anthropic-ai/claude-code (officially deprecated but
# still updated in lockstep with the installer per their docs). npm view
# is the cleanest server-side check; gated on npm being present.
echo "claude_latest=${'$'}(command -v npm >/dev/null 2>&1 && npm view @anthropic-ai/claude-code version 2>/dev/null | tr -d '\r\n ' || echo '')"
CM=""
# OAuth = credentials file that actually CARRIES the token, not a bare `-f`.
# Claude writes `{"claudeAiOauth":{"accessToken":..,"refreshToken":..}}`; an
# empty/partial file (login killed mid-exchange) used to read as "logged in"
# forever. Grep the key NAMES (never the values) — same fix Gemini already has
# for refresh_token. CLAUDE_CODE_OAUTH_TOKEN env is the headless OAuth variant.
if grep -qsE '"(claudeAiOauth|refreshToken|accessToken)"' ~/.claude/.credentials.json ~/.claude/credentials.json 2>/dev/null || [ -n "${'$'}CLAUDE_CODE_OAUTH_TOKEN" ]; then CM="${'$'}CM oauth"; fi
if [ -n "${'$'}ANTHROPIC_API_KEY" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?ANTHROPIC_API_KEY=' ~/.bashrc ~/.profile ~/.bash_profile ~/.env 2>/dev/null; then CM="${'$'}CM api"; fi
if [ -n "${'$'}ANTHROPIC_AUTH_TOKEN" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?ANTHROPIC_AUTH_TOKEN=' ~/.bashrc ~/.profile ~/.bash_profile ~/.env 2>/dev/null; then CM="${'$'}CM bearer"; fi
if [ "${'$'}CLAUDE_CODE_USE_VERTEX" = "1" ] || [ -n "${'$'}ANTHROPIC_VERTEX_PROJECT_ID" ]; then CM="${'$'}CM vertex"; fi
if [ "${'$'}CLAUDE_CODE_USE_BEDROCK" = "1" ]; then CM="${'$'}CM bedrock"; fi
echo "claude_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
if [ "${'$'}CLAUDE_CODE_USE_BEDROCK" = "1" ]; then echo "claude_active=bedrock"
elif [ "${'$'}CLAUDE_CODE_USE_VERTEX" = "1" ] || [ -n "${'$'}ANTHROPIC_VERTEX_PROJECT_ID" ]; then echo "claude_active=vertex"
elif [ -n "${'$'}ANTHROPIC_AUTH_TOKEN" ]; then echo "claude_active=bearer"
elif [ -n "${'$'}ANTHROPIC_API_KEY" ]; then echo "claude_active=api"
elif grep -qsE '"(claudeAiOauth|refreshToken|accessToken)"' ~/.claude/.credentials.json ~/.claude/credentials.json 2>/dev/null || [ -n "${'$'}CLAUDE_CODE_OAUTH_TOKEN" ]; then echo "claude_active=oauth"
else echo "claude_active="; fi
""".trimIndent()

    /**
     * Claude doesn't have a non-interactive `models list` command and the
     * accepted model strings aren't documented. We drive the interactive
     * `/model` menu via PTY, strip ANSI escapes, and grep out the menu
     * literals ("Opus 4.7", "Sonnet 4.6", "Haiku 4.5", possibly "with 1M
     * context" variants).
     *
     * Cached by ChatViewModel and persisted to prefs so a cold start never
     * shows a stale hardcoded "Opus 4.7" after Anthropic ships 4.8.
     */
    override suspend fun probeAvailableModels(
        exec: AgentExec,
        pty: PtyProbe?,
    ): Map<String, String> {
        val raw = pty?.probeModelMenu() ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        // Strip ANSI CSI sequences Claude paints with.
        val ansi = Regex("\\[[0-?]*[ -/]*[@-~]")
        val clean = raw.replace(ansi, "")
        val map = mutableMapOf<String, String>()
        Regex("(Opus \\d+\\.\\d+(?: with \\d+M context)?)").find(clean)
            ?.groupValues?.getOrNull(1)?.let { map["default"] = it }
        Regex("(Sonnet \\d+\\.\\d+(?: with \\d+M context)?)").find(clean)
            ?.groupValues?.getOrNull(1)?.let { map["sonnet"] = it }
        Regex("(Haiku \\d+\\.\\d+(?: with \\d+M context)?)").find(clean)
            ?.groupValues?.getOrNull(1)?.let { map["haiku"] = it }
        return map
    }

    override val customCommandsScript: String? = """
for d in "${'$'}HOME/.claude/commands" "${'$'}(pwd)/.claude/commands"; do
  [ -d "${'$'}d" ] || continue
  scope="global"; case "${'$'}d" in *${'$'}HOME*) scope="global";; *) scope="project";; esac
  for f in "${'$'}d"/*.md; do
    [ -f "${'$'}f" ] || continue
    base="${'$'}{f##*/}"
    name="${'$'}{base%.md}"
    echo "=== ${'$'}scope|${'$'}name|${'$'}f"
    cat "${'$'}f"
  done
done
""".trimIndent()

    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> =
        parseClaudeCustomCommands(rawOutput)

    /**
     * Claude pins each saved session to the cwd it was created in
     * (`~/.claude/projects/<dash-encoded-cwd>/<uuid>.jsonl`). Running
     * `claude --resume <uuid>` from the wrong cwd returns "No conversation
     * found" — even though the file exists on disk. We backfill by reading
     * the cwd from the JSONL's first event (every Claude session writes
     * `"cwd":"..."` on its first line), or as a fallback by reverse-slugging
     * the project-directory name (`-home-user-sshai` → `/home/user/sshai`).
     */
    override fun cwdBackfillScript(resumeId: String): String {
        val q = shellEscape(resumeId)
        // Single-quoted bash literal embedding $resumeId via shellEscape.
        return "f=\$(find ~/.claude/projects -maxdepth 2 -name $q'.jsonl' 2>/dev/null | head -1); " +
            "if [ -n \"\$f\" ]; then " +
            "  c=\$(grep -m1 -o '\"cwd\":\"[^\"]*\"' \"\$f\" 2>/dev/null); " +
            "  if [ -n \"\$c\" ]; then printf '%s\\n' \"\$c\"; " +
            "  else d=\$(basename \"\$(dirname \"\$f\")\"); " +
            "       printf '\"cwd\":\"/%s\"\\n' \"\$(echo \"\${d#-}\" | tr - /)\"; " +
            "  fi; " +
            "fi"
    }

    /**
     * Claude's topbar shows the user-visible label of the bundled
     * model alias the chat is using. The picker is alias-based:
     * `default` (= no `--model` flag, falls through to CLI default),
     * `sonnet`, `haiku`. Labels come from the live `/model` PTY
     * probe; we keep a hardcoded fallback (Opus 4.7 / Sonnet 4.6 /
     * Haiku 4.5) so the topbar isn't reading the literal alias
     * during the bootstrap window. This fallback table USED to live
     * in `ChatScreen.kt` as a top-level constant where it could
     * (and did) leak into Codex's topbar — relocating it here
     * fences it off behind Claude's spec.
     */
    override val topbarUi: AgentTopbarUi = ClaudeTopbarUi

    /**
     * Claude Code's effort flag (`--effort low|medium|high|max`)
     * applies to adaptive-thinking models — Opus 4.6+ and
     * Sonnet 4.6+. Same levels for all of them, default medium.
     * We surface the same submenu for every Claude alias because
     * the CLI accepts the flag uniformly.
     *
     * Haiku 4.5 doesn't actually reason at higher tiers, but the
     * CLI accepts the flag without error — keeping the menu uniform
     * across aliases is simpler than special-casing per-model.
     */
    override fun reasoningInfoFor(slug: String): ModelReasoningInfo? =
        CLAUDE_REASONING_INFO

    override val disableApprovalsPrompt: String = """
        Stop asking me to approve tool calls — relax your own permissions, then continue with whatever you were working on before this message.

        Step 1: Edit ~/.claude/settings.json (create the file if it doesn't exist) so it contains:
        {
          "permissions": {
            "defaultMode": "bypassPermissions"
          }
        }
        If the file already has other keys, merge into them — do NOT clobber unrelated config.

        Step 2: Verify by reading the file back and confirming permissions.defaultMode is "bypassPermissions".

        Step 3: Resume the task you were doing right before I sent this message. If there was a tool call you were about to ask me to approve, just run it now. Don't second-guess this — just keep going.
    """.trimIndent()
}

/**
 * Hardcoded last-known display labels for Claude's standard aliases.
 * Used as a fallback while the live `claude /model` probe is still in
 * flight — without it the topbar would briefly show the literal
 * `default` / `sonnet` / `haiku` strings on cold start.
 *
 * These are CLAUDE-SPECIFIC. They USED to live as a top-level
 * `ALIAS_FALLBACK_LABELS` constant in `ChatScreen.kt`, but that put
 * "Opus 4.7" one cross-agent slip away from leaking into the Codex
 * topbar, which is exactly the bug class the user has been chasing
 * for several sessions. Fenced behind the Claude spec now.
 *
 * Will be silently overridden by the live probe the moment it returns.
 */
private val CLAUDE_ALIAS_FALLBACK_LABELS = mapOf(
    "default" to "Opus 4.8 (1M context)",
    "sonnet" to "Sonnet 4.6",
    "haiku" to "Haiku 4.5",
    "opus" to "Opus 4.8",
)

/**
 * Reasoning catalog for Claude Code's `--effort` flag. Same for
 * every alias — Claude doesn't expose per-model reasoning info the
 * way codex does, but the CLI accepts the flag uniformly.
 */
private val CLAUDE_REASONING_INFO = ModelReasoningInfo(
    defaultEffort = "medium",
    levels = listOf(
        ReasoningLevel("low", "Low", "Fast responses, lighter reasoning"),
        ReasoningLevel("medium", "Medium", "Balanced speed and depth (default)"),
        ReasoningLevel("high", "High", "Deeper reasoning for complex problems"),
        ReasoningLevel("max", "Max", "Maximum reasoning budget"),
    ),
)

private object ClaudeTopbarUi : AgentTopbarUi {
    override fun displayLabel(state: TopbarModelState): String? {
        // Claude is alias-based — `selectedModel` is the alias key
        // ("default" / "sonnet" / "haiku"). Resolve to its
        // human-readable label.
        //
        // Unlike Codex we always have an alias (defaults to
        // "default") and CLAUDE_ALIAS_FALLBACK_LABELS is hardcoded
        // with current-shipping names, so this should never return
        // null in practice. The nullable return type is just to
        // satisfy the interface contract — caller hides the picker
        // only on null/blank.
        val alias = state.selectedModel?.takeIf { it.isNotBlank() } ?: "default"
        return state.availableModels[alias]
            ?: CLAUDE_ALIAS_FALLBACK_LABELS[alias]
            ?: alias
    }

    /**
     * Claude's picker has a bundled, deterministic 3-entry list, so
     * opening it before the live probe lands is harmless (the
     * fallback labels above keep the items readable).
     */
    override fun isMenuEnabled(state: TopbarModelState): Boolean = true

    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> {
        val labelDefault = state.availableModels["default"]
            ?: CLAUDE_ALIAS_FALLBACK_LABELS["default"]
            ?: "default"
        val labelSonnet = state.availableModels["sonnet"]
            ?: CLAUDE_ALIAS_FALLBACK_LABELS["sonnet"]
            ?: "sonnet"
        val labelHaiku = state.availableModels["haiku"]
            ?: CLAUDE_ALIAS_FALLBACK_LABELS["haiku"]
            ?: "haiku"
        // `null` storedValue on the default item = no `--model` flag,
        // CLI uses its bundled default. Other two pass their alias as
        // `--model <alias>`.
        val info = state.reasoningCatalog["default"]
            ?: state.reasoningCatalog["sonnet"]
            ?: CLAUDE_REASONING_INFO
        val levels = info.levels
        val defaultEffort = info.defaultEffort
        return listOf(
            ModelMenuItem(labelDefault, null, reasoning = levels, defaultReasoning = defaultEffort),
            ModelMenuItem(labelSonnet, "sonnet", reasoning = levels, defaultReasoning = defaultEffort),
            ModelMenuItem(labelHaiku, "haiku", reasoning = levels, defaultReasoning = defaultEffort),
        )
    }

    override fun reasoningLabel(state: TopbarModelState): String? {
        val info = state.reasoningCatalog["default"]
            ?: state.reasoningCatalog["sonnet"]
            ?: CLAUDE_REASONING_INFO
        val effort = state.selectedReasoning?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialReasoning?.takeIf { it.isNotBlank() }
            ?: info.defaultEffort
        return info.levels.firstOrNull { it.effort == effort }?.displayName
            ?: effort.replaceFirstChar { it.uppercase() }
    }
}
