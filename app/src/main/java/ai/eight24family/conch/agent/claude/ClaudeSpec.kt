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
import ai.eight24family.conch.agent.spec.TurnSignals
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.util.SilentlyTry
import kotlin.math.abs
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

    override val supportsControlProtocol = true

    /**
     * Persistent bidirectional channel — the Agent SDK's exact flag set
     * (verified against claude-agent-sdk-python `subprocess_cli.py` and
     * the 2.1.170 binary): NO `--print`, `--input-format stream-json`
     * for the held-open stdin, `--permission-prompt-tool stdio` so the
     * CLI routes tool-permission decisions (and AskUserQuestion) back to
     * us as `can_use_tool` control_requests instead of auto-resolving.
     * Approval modes map to `--permission-mode` enum values (the
     * streaming path replaces `--dangerously-skip-permissions` with
     * `bypassPermissions`); IS_SANDBOX stays for root servers. Thinking
     * budget env mirrors [buildExecCommand] — an effort CHANGE mid-chat
     * restarts the process (env is launch-scoped), which
     * AgentSessionPersistentStream does via launch-param comparison.
     */
    override fun buildPersistentCommand(input: ExecInput): String {
        val resume = input.resumeId?.let { " --resume ${shellEscape(it)}" } ?: ""
        val modelArg = input.model?.takeIf { it.isNotBlank() }
            ?.let { " --model ${shellEscape(it)}" } ?: ""
        val approvalArg = when (input.approvalMode) {
            AgentApprovalMode.SAFE -> "" // default mode → every tool prompts via can_use_tool
            AgentApprovalMode.AUTO -> " --permission-mode acceptEdits"
            AgentApprovalMode.YOLO -> " --permission-mode bypassPermissions"
        }
        val sandboxEnv = if (input.approvalMode == AgentApprovalMode.YOLO)
            "IS_SANDBOX=1 CLAUDE_CODE_BUBBLEWRAP=1 "
        else ""
        val effort = input.reasoningEffort?.takeIf { it.isNotBlank() }
        val thinkingEnv = thinkingBudget(effort)
            ?.let { "CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING=1 MAX_THINKING_TOKENS=$it " } ?: ""
        val effortArg = if (thinkingEnv.isEmpty() && effort != null)
            " --effort ${shellEscape(effort)}" else ""
        return "${sandboxEnv}${thinkingEnv}stdbuf -oL claude" +
            " --output-format stream-json --input-format stream-json" +
            " --include-partial-messages --verbose" +
            " --permission-prompt-tool stdio" +
            "$approvalArg$resume$modelArg$effortArg 2>&1"
    }

    override fun encodeUserTurn(text: String): String = ClaudeControlWire.encodeUserTurn(text)

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
        val effort = input.reasoningEffort?.takeIf { it.isNotBlank() }
        val thinkingEnv = thinkingBudget(effort)
            ?.let { "CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING=1 MAX_THINKING_TOKENS=$it " } ?: ""
        // Levels the budget ladder doesn't know — xhigh, ultracode, and
        // whatever future ones the probed `/effort` slider reports — go to
        // the CLI verbatim as `--effort <x>`: the CLI understands its own
        // levels better than any client-side mapping ever could.
        val effortArg = if (thinkingEnv.isEmpty() && effort != null)
            " --effort ${shellEscape(effort)}" else ""
        // `--verbose` is REQUIRED alongside `stream-json` — without it Claude
        // emits only the final `result` event (silent JSONL), a common
        // gotcha that costs hours of "why isn't streaming working".
        return "printf '%s' $escapedText | ${sandboxEnv}${thinkingEnv}stdbuf -oL claude --print " +
            "--output-format stream-json --include-partial-messages --verbose" +
            "$approvalArg$resume$modelArg$sessionIdArg$effortArg 2>&1"
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
# Make conch's headless sessions show up in the native `claude --resume` picker.
# Proven on claude 2.1.191: the picker lists ONLY sessions whose JSONL lines carry
# "entrypoint":"cli" (interactive) and HIDES "entrypoint":"sdk-cli" — the tag every
# session conch creates (it drives claude via --print / --input-format stream-json,
# i.e. SDK mode). Rewriting that single field is the whole fix; `claude --resume <id>`
# keeps working (verified). NOT the session header, NOT history.jsonl — those were
# red herrings (see reference_claude_resume_headless_hidden / tmp_build/bisect.py).
# GUARD: never rewrite a file a live process still holds open — the persistent channel
# keeps the fd open for the whole session and an mv would drop that turn's appends; we
# skip any jsonl currently open per /proc (lsof/strace aren't on the box). Idempotent
# (post-fix the file has "cli", so the grep gate skips it). Silent — no stdout here.
CONCH_INUSE=${'$'}(for l in /proc/[0-9]*/fd/*; do readlink "${'$'}l" 2>/dev/null; done | grep '\.jsonl${'$'}' | sort -u)
for f in ~/.claude/projects/*/*.jsonl; do
  [ -f "${'$'}f" ] || continue
  case "${'$'}{f##*/}" in agent-*) continue;; esac
  head -n 40 "${'$'}f" 2>/dev/null | grep -q '"entrypoint":"sdk-cli"' || continue
  printf '%s\n' "${'$'}CONCH_INUSE" | grep -qxF "${'$'}f" && continue
  ctmp="${'$'}f.conchtmp"
  sed 's/"entrypoint":"sdk-cli"/"entrypoint":"cli"/g' "${'$'}f" > "${'$'}ctmp" 2>/dev/null && mv "${'$'}ctmp" "${'$'}f" 2>/dev/null || rm -f "${'$'}ctmp" 2>/dev/null
done
for f in ~/.claude/projects/*/*.jsonl; do
  [ -f "${'$'}f" ] || continue
  id="${'$'}{f##*/}"
  id="${'$'}{id%.jsonl}"
  mtime=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || stat -f %m "${'$'}f" 2>/dev/null)
  size=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || stat -f %z "${'$'}f" 2>/dev/null)
  # Model: every assistant turn stamps `message.model`. Take the LAST REAL
  # match — the model the session is CURRENTLY on. EXCLUDE synthetic markers:
  # Claude stamps service rows (compaction, injected context) with
  # `"model":"<synthetic>"`, and a `tail -1` without the filter grabbed THAT,
  # so the topbar flashed "<synthetic>" on every open (user, 2026-06-13).
  # `grep -v '"<'` drops any <...> marker; tail -1 then gives the real model.
  # The CURRENT model is in the LAST assistant turn → near the END of the file.
  # Read only the last 256KB (covers thousands of turns) instead of the whole
  # rollout; fall back to a full scan only if that window has no real model
  # (tiny/edge file), so correctness never regresses. A mid-line cut at the
  # window start can only corrupt the FIRST match, never the `tail -1` we keep.
  model=${'$'}(tail -c 262144 "${'$'}f" 2>/dev/null | grep -oE '"model"[[:space:]]*:[[:space:]]*"[^"]+"' | grep -v '"<' | tail -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  [ -z "${'$'}model" ] && model=${'$'}(grep -oE '"model"[[:space:]]*:[[:space:]]*"[^"]+"' "${'$'}f" 2>/dev/null | grep -v '"<' | tail -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  # First user message lives at the TOP of the file — bound the read to the
  # first 500 lines instead of grepping a possibly-100MB rollout end to end
  # (the listing's biggest per-file cost). 500 lines >> the first 8 user turns.
  candidates=${'$'}(head -n 500 "${'$'}f" 2>/dev/null | grep '"type":"user"' | head -n 8 | tr '\t' ' ' | tr '\n' '\036')
  # Claude's OWN auto-generated session title — the nice 4-6 word name shown in
  # `claude --resume`. Stored in the JSONL as {"type":"ai-title","aiTitle":"…"};
  # take the LAST one (it's regenerated/duplicated). Prepend it to the preview
  # column with a Unit Separator (U+001F) so the app shows the title instead of
  # the raw first message (extractSessionPreview splits it back off). Falls
  # through to candidates when a session has no title yet. Strip tab/RS/US/NL
  # from the title so it can't corrupt the column or the separator.
  # ai-title is regenerated late → also near the END. Same 256KB window + full
  # fallback as model, so a session that has a title never loses it.
  title=${'$'}(tail -c 262144 "${'$'}f" 2>/dev/null | grep -ao '"aiTitle":"[^"]*"' | tail -1 | sed -E 's/.*"aiTitle":"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
  [ -z "${'$'}title" ] && title=${'$'}(grep -ao '"aiTitle":"[^"]*"' "${'$'}f" 2>/dev/null | tail -1 | sed -E 's/.*"aiTitle":"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
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
     * `/model` menu via PTY, strip ANSI escapes, and parse the menu rows
     * GENERICALLY — any `<Family> <version>` name the menu paints becomes
     * a picker entry, keyed by its synthesized public model id (see
     * [parseClaudeModelMenu] for the exact contract).
     *
     * History: the first implementation grepped for the hardcoded families
     * Opus/Sonnet/Haiku. The 2026-06 Anthropic release added the new
     * "Fable" family and the picker silently never showed it — the
     * original requirement regressed by construction. NEVER hardcode
     * family names here again; parse whatever the menu shows.
     *
     * Cached by ChatViewModel and persisted to prefs so a cold start never
     * shows a stale list after Anthropic ships a new family.
     */
    override suspend fun probeAvailableModels(
        exec: AgentExec,
        pty: PtyProbe?,
    ): Map<String, String> {
        val raw = pty?.probeModelMenu() ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        // ALWAYS keep the tail of the RENDERED screen in logcat at DEBUG —
        // every /model format change so far had to be diagnosed blind.
        // `adb logcat -s SshAi-Models` shows exactly what the parser saw.
        // Chunked: logcat truncates ~4K per entry.
        val screen = renderClaudeTerminal(raw)
        val cleanTail = screen.takeLast(9000)
        var offset = 0
        var chunk = 0
        while (offset < cleanTail.length) {
            val end = minOf(offset + 3000, cleanTail.length)
            android.util.Log.d(
                "SshAi-Models",
                "rendered /model screen [$chunk]: ${cleanTail.substring(offset, end)}",
            )
            offset = end
            chunk++
        }
        // The same capture carries the `/effort` slider (when the probe
        // managed to open it) and the current-effort status line — stash
        // both so reasoningInfoFor/probeDefaultReasoning serve SERVER
        // truth instead of a hardcoded ladder.
        val effort = parseClaudeEffortScreen(screen)
        if (effort.info != null) probedEffortInfo = effort.info
        if (effort.current != null) probedCurrentEffort = effort.current
        android.util.Log.d(
            "SshAi-Models",
            "claude effort: current=${effort.current} levels=${effort.info?.levels?.map { it.effort }}",
        )
        val parsed = parseClaudeModelMenu(raw)
        if (parsed.isEmpty()) {
            // Parse miss = the menu format changed AGAIN. The raw frame is
            // already in logcat above — this line just makes it loud.
            android.util.Log.w("SshAi-Models", "claude /model menu parse found nothing")
        }
        return parsed
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
     * Last `/effort` capture from the live probe: the level catalog the
     * server's CLI actually offers + the level it's currently on. Same
     * probe-scoped-cache pattern Codex uses for its models_cache parse —
     * refreshed on every [probeAvailableModels] run, hardcoded ladder
     * only as the pre-probe fallback.
     */
    @Volatile
    private var probedEffortInfo: ModelReasoningInfo? = null

    @Volatile
    private var probedCurrentEffort: String? = null

    /**
     * Effort levels come from the PROBED `/effort` slider (low/medium/
     * high/xhigh/max/ultracode on 2.1.170 — whatever the server's CLI
     * ships, including future ones). The same catalog is served for
     * every Claude alias because the CLI applies effort uniformly.
     * [CLAUDE_REASONING_INFO] is only the pre-probe fallback — NEVER
     * treat it as the source of truth (2026-06-10: the hardcoded ladder
     * was missing xhigh/ultracode and lied about the default).
     */
    override fun reasoningInfoFor(slug: String): ModelReasoningInfo? =
        probedEffortInfo ?: CLAUDE_REASONING_INFO

    /** The effort the server ACTUALLY runs at — parsed from the `/effort`
     *  slider marker or the `● high · /effort` status line during
     *  [probeAvailableModels] (which always runs first in the probe
     *  sequence, so the stash is warm). */
    override suspend fun probeDefaultReasoning(exec: AgentExec): String? = probedCurrentEffort

    /** Claude's effort catalog is uniform across models — persist the
     *  single probed [ModelReasoningInfo] so cold starts hydrate the real
     *  server levels instead of the hardcoded fallback ladder. */
    override fun serializeReasoningCatalog(catalog: Map<String, ModelReasoningInfo>): String? {
        val info = catalog.values.firstOrNull() ?: return null
        return buildString {
            append(info.defaultEffort)
            info.levels.forEach { l ->
                append('\n').append(l.effort).append('\t')
                    .append(l.displayName).append('\t')
                    .append(l.description.replace('\n', ' ').replace('\t', ' '))
            }
        }
    }

    override fun deserializeReasoningCatalog(
        raw: String,
        modelKeys: Collection<String>,
    ): Map<String, ModelReasoningInfo> {
        if (raw.isBlank()) return emptyMap()
        val lines = raw.lines()
        val default = lines.firstOrNull()?.trim().orEmpty()
        val levels = lines.drop(1).mapNotNull { line ->
            val p = line.split('\t')
            val effort = p.getOrNull(0)?.trim().orEmpty()
            if (effort.isEmpty()) null
            else ReasoningLevel(
                effort = effort,
                displayName = p.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: effort.replaceFirstChar { it.uppercase() },
                description = p.getOrNull(2).orEmpty(),
            )
        }
        if (default.isEmpty() || levels.isEmpty()) return emptyMap()
        val info = ModelReasoningInfo(default, levels)
        // Warm the spec-level stash too: menuItems' fallback chain and a
        // probe-less session must agree with the hydrated catalog.
        probedEffortInfo = info
        return modelKeys.associateWith { info }
    }

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

    // ──────── Mirror turn-state ────────

    /**
     * Project each `user`/`assistant` JSONL line to a 9-field TSV record:
     * `[type, isMeta, isToolResult, isToolUse, out_tokens, msgId, ts, text(0..160),
     * stop_reason]`. The text/stop_reason fields carry the DEFINITIVE done signal;
     * the rest feed the timer + token counter. Robust to string OR array message
     * content. Never a nested type. (Moved verbatim from the old hardcoded probe.)
     */
    override val turnStateRecordJq: String =
        "select(.type==\"user\" or .type==\"assistant\") | [.type, " +
            "((.isMeta // false)|tostring), " +
            "(((.message.content // [])|if type==\"array\" then any(.[]?; .type==\"tool_result\") else false end)|tostring), " +
            "(((.message.content // [])|if type==\"array\" then any(.[]?; .type==\"tool_use\") else false end)|tostring), " +
            "((.message.usage.output_tokens // 0)|tostring), (.message.id // \"\"), (.timestamp // \"\"), " +
            "(((.message.content) | if type==\"array\" then ([.[]? | (.text // \"\")] | join(\" \")) elif type==\"string\" then . else \"\" end | gsub(\"[\\t\\n\\r]\"; \" \")) | .[0:160]), " +
            "(.message.stop_reason // \"\")] | @tsv"

    /**
     * DEFINITIVE turn state — second-accurate, NO timeouts (verified empirically
     * against a live `claude` session 2026-06-27). The LAST MEANINGFUL user/
     * assistant event says exactly what's up:
     *   • assistant + terminal stop_reason (end_turn / stop_sequence / max_tokens) → DONE
     *   • "[Request interrupted by user]" / "No response requested."               → DONE
     *   • user prompt OR user tool_result (no assistant after)                     → WORKING (thinking)
     *   • assistant + stop_reason "tool_use"                                       → WORKING (tool running)
     *   • assistant + no stop_reason yet (mid-stream) → WORKING, cleared only if long-frozen
     * Empty attachment-carrier user rows / snapshots are ignored (`lm` skips them).
     * Claude's live AskUserQuestion is NOT here — it never hits the file (held in
     * the persistent stream's pendingControls), so waitingForUser stays false and
     * the app detects it separately.
     */
    override fun inferTurnState(records: List<List<String>>, frozenForMs: Long?): TurnSignals {
        val recs = records.filter { it.size >= 7 }
        if (recs.isEmpty()) return TurnSignals()
        val lm = recs.lastOrNull {
            (it.getOrNull(7)?.isNotBlank() == true) || it[2] == "true" || it[3] == "true"
        }
        val lmText = lm?.getOrNull(7).orEmpty()
        val lmStop = lm?.getOrNull(8).orEmpty()
        val inFlight: Boolean = when {
            lm == null -> false
            lm[0] == "user" && lmText.contains("[Request interrupted by user]") -> false
            lm[0] == "assistant" && lmText.trimStart().startsWith("No response requested") -> false
            lm[0] == "assistant" && lmStop in TERMINAL_STOP_REASONS -> false
            lm[0] == "user" -> true
            lm[0] == "assistant" && lmStop == "tool_use" -> true
            lm[0] == "assistant" -> // no stop_reason yet → mid-stream; clear only if long-frozen
                frozenForMs == null || frozenForMs < AWAIT_STALE_MS
            else -> false
        }
        val thinking = inFlight && lm != null && lm[0] == "user" &&
            !lmText.contains("[Request interrupted by user]")
        val startIdx = recs.indexOfLast { it[0] == "user" && it[2] != "true" }
        val turnStartMs = if (startIdx >= 0) recs[startIdx][6].takeIf { it.isNotBlank() }?.let { ts ->
            SilentlyTry.logged("SshAi-ClaudeSpec", "parse turn-start ts") {
                java.time.Instant.parse(ts).toEpochMilli()
            }
        } else null
        val tokenStart = if (startIdx >= 0) startIdx + 1 else 0
        val tokens = recs.drop(tokenStart).filter { it[0] == "assistant" }
            .withIndex()
            .groupBy { (i, r) -> r[5].ifBlank { "##idx$i" } }
            .values.sumOf { g -> g.maxOf { (_, r) -> r[4].toLongOrNull() ?: 0L } }
        // DEFINITIVE completion: the last meaningful record is an assistant whose
        // stop_reason is terminal. This is the ONLY safe signal for force-completing
        // a stuck live turn — unlike `!inFlight`, it never flips true on the 12-min
        // stale-mid-stream fallback, so a long silent research turn is never killed.
        val turnComplete = lm != null && lm[0] == "assistant" && lmStop in TERMINAL_STOP_REASONS
        return TurnSignals(
            inFlight = inFlight,
            thinking = thinking,
            turnStartMs = turnStartMs,
            tokens = tokens,
            turnComplete = turnComplete,
        )
    }

    /** Assistant `stop_reason` values that mean the TURN IS COMPLETE — the
     *  definitive, second-accurate "done" signal (no timeout). */
    private val TERMINAL_STOP_REASONS = setOf("end_turn", "stop_sequence", "max_tokens")

    /** FALLBACK ONLY: last event is an assistant with NO stop_reason yet
     *  (mid-stream / malformed). Treat as working unless frozen longer than this,
     *  so a wedged/dead stream still clears. */
    private val AWAIT_STALE_MS = 12 * 60_000L
}

/**
 * Render a raw PTY byte stream into the FINAL SCREEN TEXT — a minimal
 * VT/ANSI terminal emulator (grid of lines, cursor, CSI moves/erases).
 *
 * Why an emulator and not a strip-the-escapes regex: ink (Claude's TUI)
 * paints with cursor-motion ops — `ESC[nC` instead of runs of spaces,
 * absolute/relative jumps to rewrite only the cells that changed between
 * frames. Deleting those sequences GLUES the surviving glyphs together
 * and drops every character the diff skipped: the real capture rendered
 * "Sonnet 4.6" as "onnt 4.6" and "1. Default" as "1.Default", which broke
 * row parsing and corrupted the picker labels (2026-06-10). Interpreting
 * the ops is lossless by construction — we read the screen the way a
 * human does.
 *
 * Supported: CSI cursor (H/f, A/B/C/D, G, d), erase (J/K), CR/LF/BS/TAB,
 * OSC (skipped), other CSI/SGR and stray controls ignored. Later writes
 * overwrite earlier cells, so the LAST paint of each row wins — exactly
 * the dedupe the old "richer capture" heuristic approximated.
 */
internal fun renderClaudeTerminal(raw: String): String {
    val maxRows = 500
    val maxCols = 1000
    val grid = ArrayList<StringBuilder>()
    var row = 0
    var col = 0
    fun line(r: Int): StringBuilder {
        while (grid.size <= r) grid.add(StringBuilder())
        return grid[r]
    }
    fun put(ch: Char) {
        if (col >= maxCols) return
        val l = line(row)
        while (l.length < col) l.append(' ')
        if (col < l.length) l.setCharAt(col, ch) else l.append(ch)
        col++
    }
    var i = 0
    val n = raw.length
    while (i < n) {
        when (val c = raw[i]) {
            '\u001B' -> {
                if (i + 1 >= n) break
                when (raw[i + 1]) {
                    '[' -> {
                        var j = i + 2
                        while (j < n && raw[j] !in '@'..'~') j++
                        if (j >= n) break
                        val params = raw.substring(i + 2, j)
                        fun p(idx: Int, def: Int): Int = params.split(';')
                            .getOrNull(idx)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: def
                        when (raw[j]) {
                            'H', 'f' -> {
                                row = (p(0, 1) - 1).coerceIn(0, maxRows - 1)
                                col = (p(1, 1) - 1).coerceIn(0, maxCols - 1)
                            }
                            'A' -> row = (row - p(0, 1)).coerceAtLeast(0)
                            'B' -> row = (row + p(0, 1)).coerceAtMost(maxRows - 1)
                            'C' -> col = (col + p(0, 1)).coerceAtMost(maxCols - 1)
                            'D' -> col = (col - p(0, 1)).coerceAtLeast(0)
                            'G' -> col = (p(0, 1) - 1).coerceIn(0, maxCols - 1)
                            'd' -> row = (p(0, 1) - 1).coerceIn(0, maxRows - 1)
                            'J' -> when (p(0, 0)) {
                                // 0 = clear below, 1 = clear above, 2/3 = all
                                0 -> {
                                    line(row).setLength(minOf(line(row).length, col))
                                    for (r in row + 1 until grid.size) grid[r].setLength(0)
                                }
                                1 -> for (r in 0 until minOf(row, grid.size)) grid[r].setLength(0)
                                2, 3 -> grid.forEach { it.setLength(0) }
                            }
                            'K' -> when (p(0, 0)) {
                                // 0 = to end of line, 1 = to start, 2 = whole line
                                0 -> line(row).setLength(minOf(line(row).length, col))
                                1 -> {
                                    val l = line(row)
                                    for (k in 0 until minOf(col + 1, l.length)) l.setCharAt(k, ' ')
                                }
                                2 -> line(row).setLength(0)
                            }
                            else -> { /* SGR (m), modes (h/l), etc — no layout effect */ }
                        }
                        i = j + 1
                        continue
                    }
                    ']' -> {
                        // OSC … BEL or ESC\
                        var j = i + 2
                        while (j < n && raw[j] != '\u0007' &&
                            !(raw[j] == '\u001B' && j + 1 < n && raw[j + 1] == '\\')
                        ) j++
                        i = if (j < n && raw[j] == '\u0007') j + 1 else minOf(j + 2, n)
                        continue
                    }
                    else -> {
                        i += 2 // single-char escape (ESC 7/8/=/> …)
                        continue
                    }
                }
            }
            '\r' -> { col = 0; i++ }
            '\n' -> { row = (row + 1).coerceAtMost(maxRows - 1); col = 0; i++ }
            '\b' -> { col = (col - 1).coerceAtLeast(0); i++ }
            '\t' -> { col = ((col / 8 + 1) * 8).coerceAtMost(maxCols - 1); i++ }
            else -> {
                if (c.code >= 0x20 && c.code != 0x7F) put(c)
                i++
            }
        }
    }
    return grid.joinToString("\n") { it.toString().trimEnd() }
}

/**
 * A resolved model display name: `<Family> <ver>[ <1M marker>]`. Family is
 * one capitalized word ("Opus", "Fable", "Mythos"); version may be dotless
 * ("5"). Group 3 captures the 1M-context marker in the three spellings the
 * menu has used across CLI versions.
 */
internal val CLAUDE_MODEL_NAME_RX = Regex(
    "\\b([A-Z][a-z]+) (\\d+(?:\\.\\d+)?)( with \\d+M context| \\d+M\\b| \\(\\d+M(?: context)?\\))?",
)

/**
 * Parse the raw `/model` TUI buffer into an ordered
 * `storedValue → display label` map — mirroring the CLI's own menu 1:1.
 *
 * Real frame this is built against (claude 2.1.170, captured by the user):
 * ```
 *   Select model
 *   Switch between Claude models. Your pick becomes the default for new
 *   sessions. For other/previous model names, specify with --model.
 *
 *     1. Default (recommended)  Opus 4.8 with 1M context · Best for everyday, complex tasks
 *   ❯ 2. Fable ✔                Fable 5 · Most capable for your hardest and longest-running tasks · Uses your limits ~2×
 *                               faster than Opus
 *     3. Sonnet                 Sonnet 4.6 · Efficient for routine tasks
 *     4. Sonnet (1M context)    Sonnet 4.6 with 1M context · Draws from usage credits · $3/$15 per Mtok
 *     5. Haiku                  Haiku 4.5 · Fastest for quick answers
 * ```
 *
 * Structure, NOT literals, drives the parse:
 *  - a row = `N.` + alias label column + (2+ space gap) + resolved model
 *    name + `·` + description. Wrapped description lines carry no `N.`
 *    and are skipped.
 *  - the LEFT column is the CLI's own alias for `--model`: label letters
 *    lowercased with parentheticals dropped ("Default (recommended)" →
 *    `default`, "Fable" → `fable`, "Opus Plan" → `opusplan`); a `(1M …)`
 *    parenthetical appends the documented `[1m]` suffix ("Sonnet (1M
 *    context)" → `sonnet[1m]`). A label that IS a full model name
 *    ("Opus 4.5") becomes its public id (`claude-opus-4-5`).
 *  - the RIGHT column may be cut off by a narrow PTY — rows still survive
 *    with the label as their display, so the picker never loses entries
 *    to terminal width (the exact failure mode of the first attempt).
 *  - key `"default"` (when present) is NOT a menu row — it's the chip
 *    label for un-pinned chats: the `Using <X> (from settings.json)` pin
 *    from the startup banner when the server has one, else the model the
 *    "Default (recommended)" row resolves to. [ClaudeTopbarUi.menuItems]
 *    skips it — users pick concrete models, never a "Default" word.
 *  - the "Default (recommended)" row's RESOLVED model is surfaced as a
 *    normal concrete entry keyed by public id ("Opus 4.8 with 1M
 *    context" → `claude-opus-4-8[1m]`) — the CLI menu has no other path
 *    to that model.
 *  - insertion order mirrors menu row order (order survives the
 *    StateFlow → labelMemory → prefs round-trip). TUI repaints repeat
 *    rows — the RICHER capture wins (a partial first paint may miss the
 *    resolved column).
 *
 * Everything menu-shaped is gated on the menu header ("Select model") so
 * startup banners ("Fable 5 · Claude Team") and confirm prompts ("1. Yes,
 * proceed") can't masquerade as models. No numbered rows under the header
 * (older CLI) → fall back to scanning for bare model names. No header at
 * all (menu never opened) → at most the `Using <X>` pin, never junk.
 */
internal fun parseClaudeModelMenu(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val clean = renderClaudeTerminal(raw)
    // Everything menu-shaped is gated on the menu header: without it,
    // startup banners ("Fable 5 · Claude Team") and confirm prompts ("1.
    // Yes, proceed") would masquerade as models. The settings.json `Using
    // <X>` pin is deliberately IGNORED — we never surface a "default"
    // anywhere.
    val headerIdx = sequenceOf("Select model", "Switch between Claude models")
        .map { clean.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull()
        ?: return emptyMap()  // no menu → no model list; topbar uses the session model
    val section = clean.substring(headerIdx)
    var parsed = parseClaudeNumberedRows(section)
    if (parsed.rows.isEmpty()) parsed = parseClaudeLooseModelNames(section)
    // Publish the unavailable set (by label) so the topbar skips it and the
    // picker greys it. Snapshot swap — singleton spec, no per-chat state.
    claudeUnavailableLabels = parsed.unavailableLabels
    // NO "default" entry — the picker shows ONLY concrete models. Drop the
    // Default-row-SYNTHESIZED entry IFF an explicit row already lists the
    // same model (that's the twin "Opus 4.8" the user saw). Distinct models
    // that merely share a resolved label are NOT collapsed.
    val synthKey = parsed.defaultSynthKey
    val synthLabel = synthKey?.let { parsed.rows[it] }
    val dropSynth = synthKey != null && synthLabel != null &&
        parsed.rows.any { (k, v) -> k != synthKey && v == synthLabel }
    val out = LinkedHashMap<String, String>()
    for ((key, label) in parsed.rows) {
        if (dropSynth && key == synthKey) continue
        out[key] = label
    }
    return out
}

/** Labels the CLI flagged unavailable in the last `/model` parse. Read by
 *  [ClaudeTopbarUi] to skip them in the chip and grey them in the picker.
 *  Singleton (spec is stateless per chat); volatile for the UI-thread read. */
@Volatile
internal var claudeUnavailableLabels: Set<String> = emptySet()

/** Rows in menu order + what the "Default (recommended)" row resolved to +
 *  the set of model LABELS the CLI marked unavailable (description column
 *  carried "is currently unavailable" — export-control suspension etc.). */
private class ClaudeMenuParse(
    val rows: LinkedHashMap<String, String>,
    val defaultResolved: String?,
    val unavailableLabels: Set<String> = emptySet(),
    /** Key synthesized from the "Default (recommended)" row's resolved model
     *  (the menu has no explicit row for it on older CLIs). Dropped by
     *  [parseClaudeModelMenu] IFF an explicit row already lists the same
     *  model — that's the twin-"Opus 4.8" the user saw. */
    val defaultSynthKey: String? = null,
)

/** The CLI's "<model> is currently unavailable" marker (Fable 5 / Mythos 5
 *  export-control suspension, deprecations). Lives in the row's description. */
private fun String.marksUnavailable(): Boolean =
    contains("is currently unavailable", ignoreCase = true)

/** Primary path: structured numbered rows (see [parseClaudeModelMenu] doc). */
private fun parseClaudeNumberedRows(section: String): ClaudeMenuParse {
    val rowRx = Regex("^[\\s❯›>]*(\\d+)\\.\\s+(\\S.*)$")
    val rows = LinkedHashMap<String, String>()
    var defaultResolved: String? = null
    var defaultSynthKey: String? = null
    val unavailable = HashSet<String>()
    fun put(key: String, display: String) {
        val prev = rows[key]
        // Repaints repeat rows — keep the richer capture.
        if (prev == null || display.length > prev.length) rows[key] = display
    }
    for (line in section.lineSequence()) {
        val row = rowRx.find(line)?.groupValues?.get(2) ?: continue
        val cleaned = row.replace('✔', ' ').replace('✓', ' ')
        // Columns separated by a run of 2+ spaces; the label itself only
        // ever contains single spaces ("Default (recommended)").
        val parts = cleaned.split(Regex(" {2,}"), limit = 2)
        val label: String
        val right: String
        if (parts.size == 2) {
            label = parts[0].trim()
            right = parts[1].trim()
        } else {
            // Narrow PTY can squeeze the gap to one space — split at the
            // resolved model name if one is present mid-row.
            val m = CLAUDE_MODEL_NAME_RX.find(cleaned)
            if (m != null && m.range.first > 0) {
                label = cleaned.substring(0, m.range.first).trim()
                right = cleaned.substring(m.range.first).trim()
            } else {
                label = cleaned.trim()
                right = ""
            }
        }
        if (label.isEmpty() || label.none { it.isLetter() }) continue
        val key = claudeMenuRowKey(label) ?: continue
        val resolvedMatch = CLAUDE_MODEL_NAME_RX.find(right)
        val resolved = (
            resolvedMatch?.value?.trim()
                ?: right.substringBefore('·').trim().ifBlank { null }
            )?.replace(Regex("\\s+"), " ")
        // CLI flagged this model unavailable in its description column
        // ("Claude Fable 5 is currently unavailable …"). Record the
        // resolved label so the topbar skips it and the picker greys it.
        if (right.marksUnavailable() && resolved != null) unavailable.add(resolved)
        if (key == "default") {
            // The "Default (recommended)" ABSTRACTION is never surfaced as a
            // row/chip — but its RESOLVED model IS a real pickable model, and
            // on older menus it's the ONLY path to Opus (no explicit "Opus"
            // row). Surface that concrete model; parseClaudeModelMenu dedups
            // it against an explicit row of the same model so the picker never
            // shows twins.
            if (resolvedMatch != null && resolved != null) {
                val synthKey = claudeIdFromName(resolvedMatch)
                put(synthKey, resolved)
                defaultSynthKey = synthKey
            }
            continue
        }
        put(key, resolved ?: label)
    }
    return ClaudeMenuParse(rows, defaultResolved, unavailable, defaultSynthKey)
}

/**
 * Fallback path for menus without numbered rows: scan for bare model
 * names. Pre-2.1.x menus printed full names per row, so this degrades to
 * the original behavior — minus the hardcoded family list.
 */
private fun parseClaudeLooseModelNames(section: String): ClaudeMenuParse {
    // Words that pass the family pattern but are never model families.
    // "Claude Fable 5" in a description must yield "Fable 5", not a
    // phantom "Claude" family.
    val stopFamilies = setOf("claude", "version")
    val rows = LinkedHashMap<String, String>()
    var defaultResolved: String? = null
    for (line in section.lineSequence()) {
        // Case-sensitive \bDefault\b: the header subtitle says "…becomes
        // the default for new sessions" in lowercase and must not count.
        val isDefaultRow = line.contains("Default (recommended)") ||
            Regex("\\bDefault\\b").containsMatchIn(line)
        for (m in CLAUDE_MODEL_NAME_RX.findAll(line)) {
            if (m.groupValues[1].lowercase() in stopFamilies) continue
            val display = m.value.trim().replace(Regex("\\s+"), " ")
            if (isDefaultRow && defaultResolved == null) defaultResolved = display
            val id = claudeIdFromName(m)
            if (!rows.containsKey(id)) rows[id] = display
        }
    }
    return ClaudeMenuParse(rows, defaultResolved)
}

/**
 * Derive the `--model` value for a menu row from its label column.
 * Generic by construction — a future "Mythos" row maps to `mythos`
 * with zero code changes. Returns null for labels with no letters.
 */
private fun claudeMenuRowKey(label: String): String? {
    // A label that IS a full model name ("Opus 4.5") → public id.
    CLAUDE_MODEL_NAME_RX.matchEntire(label.trim())?.let { return claudeIdFromName(it) }
    val parens = Regex("\\(([^)]*)\\)").findAll(label).map { it.groupValues[1] }.toList()
    val base = label.replace(Regex("\\([^)]*\\)"), " ")
        .filter { it.isLetter() }
        .lowercase()
    if (base.isEmpty()) return null
    if (base == "default") return "default"
    val oneM = parens.any { it.contains("1m", ignoreCase = true) }
    return if (oneM) "$base[1m]" else base
}

/** Parsed `/effort` state: the level the server is currently on + the
 *  full catalog when the slider frame was captured. */
internal class ClaudeEffortParse(
    val current: String?,
    val info: ModelReasoningInfo?,
)

/** Prose for levels the slider doesn't annotate inline. DESCRIPTIONS
 *  only — the level LIST itself always comes from the server. */
private val CLAUDE_EFFORT_DESCRIPTIONS = mapOf(
    "low" to "Fast responses, lighter reasoning",
    "medium" to "Balanced speed and depth",
    "high" to "Deeper reasoning for complex problems",
    "max" to "Maximum reasoning budget",
)

/**
 * Parse the `/effort` slider out of a rendered screen.
 *
 * Real frame (claude 2.1.170, captured by the user):
 * ```
 * Effort
 *
 *         Faster                                              Smarter
 *         ──────────▲──────────────────────────┊──────────────
 *         low     medium     high     xhigh     max     ultracode
 *                                                       xhigh + workflows
 *
 *  ←/→ to adjust · Enter to confirm · Esc to cancel
 * ```
 * Structure-driven: the ruler line carries `▲` above the ACTIVE level;
 * the line below lists every level word in columns; an optional
 * sub-label line annotates a level by column overlap ("xhigh +
 * workflows" under ultracode). Level words are taken AS-IS — a future
 * level appears with zero code changes.
 *
 * When the slider frame isn't in the capture, the CURRENT level still
 * resolves from the `/model` footer ("● High effort (default)") or the
 * main status line ("● high · /effort").
 */
internal fun parseClaudeEffortScreen(screen: String): ClaudeEffortParse {
    val lines = screen.lines()
    var current: String? = null
    var info: ModelReasoningInfo? = null

    val rulerIdx = lines.indexOfFirst { it.contains('▲') }
    if (rulerIdx >= 0) {
        for (k in rulerIdx + 1..minOf(rulerIdx + 3, lines.lastIndex)) {
            val tokens = Regex("[a-z][a-z]+").findAll(lines[k])
                .map { it.value to it.range }
                .toList()
            if (tokens.size < 3) continue
            val markerCol = lines[rulerIdx].indexOf('▲')
            current = tokens.minByOrNull { (_, r) ->
                if (markerCol in r) 0
                else minOf(abs(r.first - markerCol), abs(r.last - markerCol))
            }?.first
            // Sub-label right under the level words ("xhigh + workflows"
            // under ultracode) — attached by column overlap.
            val subLine = lines.getOrNull(k + 1).orEmpty()
            val sub = Regex("\\S.*\\S|\\S").find(subLine)
            fun describe(token: Pair<String, IntRange>): String {
                if (sub != null &&
                    sub.range.first <= token.second.last &&
                    sub.range.last >= token.second.first
                ) {
                    return sub.value.trim()
                }
                return CLAUDE_EFFORT_DESCRIPTIONS[token.first].orEmpty()
            }
            info = ModelReasoningInfo(
                defaultEffort = current ?: tokens.first().first,
                levels = tokens.map { t ->
                    ReasoningLevel(
                        effort = t.first,
                        displayName = t.first.replaceFirstChar { it.uppercase() },
                        description = describe(t),
                    )
                },
            )
            break
        }
    }
    if (current == null) {
        Regex("●\\s*([A-Za-z]+)\\s+effort\\b").find(screen)?.let {
            current = it.groupValues[1].lowercase()
        }
    }
    if (current == null) {
        Regex("●\\s*([a-z]+)\\s*·\\s*/effort").find(screen)?.let {
            current = it.groupValues[1].lowercase()
        }
    }
    return ClaudeEffortParse(current, info)
}

/** `<Family> <ver>` match → public model id (`claude-fable-5`,
 *  `claude-opus-4-8[1m]`) — the documented `--model`-accepted form. */
private fun claudeIdFromName(m: MatchResult): String = buildString {
    append("claude-")
    append(m.groupValues[1].lowercase())
    append('-')
    append(m.groupValues[2].replace('.', '-'))
    if (m.groupValues[3].isNotBlank()) append("[1m]")
}

/** Normalize a model display label for unavailable-matching: drop the 1M-context
 *  marker (so "Fable 5" from the banner matches "Fable 5 1M" from id-resolution),
 *  lowercase, collapse whitespace. */
private fun normModel(s: String): String = s.lowercase()
    .replace(Regex("\\(?\\s*(with\\s+)?1m(\\s+context)?\\)?"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

/**
 * Best-effort reverse of the id synthesis in [parseClaudeModelMenu] —
 * resolves a stored pick OR a raw model id reported by the session
 * (`system.init`'s `"model"`, the JSONL header) to a readable label.
 * Handles dated ids too: `claude-sonnet-4-5-20250929` → "Sonnet 4.5".
 */
internal fun claudeLabelFromId(value: String): String? {
    val m = Regex("^claude-([a-z]+)-(\\d+(?:-\\d+)*?)(?:-(\\d{6,}))?(\\[1m\\])?$")
        .matchEntire(value) ?: return null
    val family = m.groupValues[1].replaceFirstChar { it.uppercase() }
    val version = m.groupValues[2].replace('-', '.')
    val oneM = if (m.groupValues[4].isNotEmpty()) " 1M" else ""
    return "$family $version$oneM"
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
 * NOTE: this is a LABEL-RESOLUTION table for legacy/known aliases, not
 * the menu source — the menu itself is dynamic (see `menuItems`).
 */
private val CLAUDE_ALIAS_FALLBACK_LABELS = mapOf(
    "default" to "Opus 4.8 (1M context)",
    "sonnet" to "Sonnet 4.6",
    "haiku" to "Haiku 4.5",
    "opus" to "Opus 4.8",
    "fable" to "Fable 5",
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
    /** Resolve any storedValue / model id to a human label: live or cached
     *  probe map → known-alias fallback table → reverse id synthesis →
     *  the raw value. */
    private fun resolve(state: TopbarModelState, value: String): String =
        state.availableModels[value]
            ?: CLAUDE_ALIAS_FALLBACK_LABELS[value]
            ?: claudeLabelFromId(value)
            ?: value

    override fun displayLabel(state: TopbarModelState): String? {
        // TWO sources, nothing else. Claude itself auto-picks a model when a session
        // starts and records it per turn; 1. the user's explicit pick for this chat;
        // 2. else the model the session ACTUALLY runs on — the live `message.model`
        // from the latest assistant turn (observedModel), else the model parsed from
        // the session header at open. NO "default", NO settings.json pin, NO
        // hardcoded fallback. Null → the topbar simply shows nothing until the
        // session reports a model. Skip a model that's UNAVAILABLE (e.g. Fable 5
        // suspended): claude itself ignores the dead settings.json pin and runs its
        // recommended default, so a fresh chat must NOT advertise the disabled model
        // as its model (user, 2026-06-26: topbar showed "Fable 5" on a new chat
        // while claude actually falls back to Opus). Once the live /model probe
        // marks it unavailable, fall through to what claude will really use — the
        // first available model in menu order, which is the recommended Opus.
        // App-side; never touches the server. Unavailable from BOTH sources: the
        // session's own banner (state.unavailable Models — reactive, lands before
        // any probe) and the live /model probe (claudeUnavailableLabels). Normalized
        // compare so "Fable 5" matches the id-resolved "Fable 5 1M".
        val unavail = state.unavailableModels + claudeUnavailableLabels
        fun isUnavail(label: String): Boolean {
            val n = normModel(label)
            return unavail.any { normModel(it) == n }
        }
        fun usable(v: String?): String? = v?.takeIf { it.isNotBlank() }
            ?.takeIf { !isUnavail(resolve(state, it)) }
        val pick = usable(state.selectedModel)
            ?: usable(state.observedModel)
            ?: usable(state.sessionInitialModel)
            ?: state.availableModels.entries.firstOrNull { (k, label) ->
                k != "default" && !isUnavail(label)
            }?.key
            ?: return null
        return resolve(state, pick)
    }

    /**
     * Opening the picker before the live probe lands is harmless — the
     * cold-start fallback list below keeps the items readable, and the
     * dynamic list swaps in the moment the probe (or prefs cache) lands.
     */
    override fun isMenuEnabled(state: TopbarModelState): Boolean = true

    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> {
        // Dynamic path: render EXACTLY the concrete models the live
        // `/model` probe (or its prefs/in-memory cache) reported, in menu
        // order. New model families (Fable 5, …) appear with zero code
        // changes — that's the auto-pickup requirement this picker
        // regressed on in 2026-06. Same pattern as CodexTopbarUi.
        // The "default" key is chip metadata, not a row (see
        // parseClaudeModelMenu) — users pick models, not a "Default" word.
        val items = state.availableModels.entries
            .filter { it.key != "default" }
            .map { (key, label) ->
                // reasoningInfoFor returns the same catalog for every slug,
                // so any key resolves; the chain is just defensive.
                val info = state.reasoningCatalog[key]
                    ?: state.reasoningCatalog.values.firstOrNull()
                    ?: CLAUDE_REASONING_INFO
                ModelMenuItem(
                    display = label,
                    // Passed verbatim as `--model <key>` on every send.
                    storedValue = key,
                    reasoning = info.levels,
                    defaultReasoning = info.defaultEffort,
                    available = label !in claudeUnavailableLabels,
                )
            }
        if (items.isNotEmpty()) return items
        // Cold-start fallback (no probe, no cache yet — or the probe only
        // recovered the settings.json pin): last-known bundled trio.
        // Deliberately NO Fable row here — we don't yet KNOW the server's
        // CLI is new enough to accept `--model fable`, and an honest
        // short list beats a send-killing guess.
        val info = state.reasoningCatalog.values.firstOrNull() ?: CLAUDE_REASONING_INFO
        return listOf(
            ModelMenuItem(
                CLAUDE_ALIAS_FALLBACK_LABELS.getValue("opus"), "opus",
                reasoning = info.levels, defaultReasoning = info.defaultEffort,
            ),
            ModelMenuItem(
                CLAUDE_ALIAS_FALLBACK_LABELS.getValue("sonnet"), "sonnet",
                reasoning = info.levels, defaultReasoning = info.defaultEffort,
            ),
            ModelMenuItem(
                CLAUDE_ALIAS_FALLBACK_LABELS.getValue("haiku"), "haiku",
                reasoning = info.levels, defaultReasoning = info.defaultEffort,
            ),
        )
    }

    override fun reasoningLabel(state: TopbarModelState): String? {
        // Claude's reasoning catalog is identical for every slug, so any
        // entry works; CLAUDE_REASONING_INFO covers the pre-probe window.
        val info = state.reasoningCatalog.values.firstOrNull() ?: CLAUDE_REASONING_INFO
        // Chain (mirror the SESSION, never impose a default): explicit
        // user pick → what the LIVE session reports (observedReasoning,
        // e.g. ultracode) → what THIS chat opened on → the PROBED current
        // effort → the catalog default. observedReasoning sits right under
        // the user pick because it's ground truth from the running session
        // — it's why a `/effort ultracode` typed in the CLI now shows
        // "Ultracode" instead of the stale probe's "xHigh" (user, 2026-06-13).
        val effort = state.selectedReasoning?.takeIf { it.isNotBlank() }
            ?: state.observedReasoning?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialReasoning?.takeIf { it.isNotBlank() }
            ?: state.defaultReasoning?.takeIf { it.isNotBlank() }
            ?: info.defaultEffort
        return info.levels.firstOrNull { it.effort == effort }?.displayName
            ?: effort.replaceFirstChar { it.uppercase() }
    }
}
