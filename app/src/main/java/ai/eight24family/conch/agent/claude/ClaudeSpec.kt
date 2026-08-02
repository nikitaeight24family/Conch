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
import ai.eight24family.conch.agent.spec.ReasoningLevel
import ai.eight24family.conch.agent.spec.TopbarModelState
import ai.eight24family.conch.agent.spec.TurnSignals
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

    override val supportsControlProtocol = true

    /** The catalog comes from the `initialize` handshake — the CLI's own
     *  registry, complete by construction. */
    override val catalogIsAuthoritative = true

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
     * budget env mirrors [buildExecCommand] and seeds the LAUNCH level;
     * a mid-chat effort change goes over the wire live via
     * `set_max_thinking_tokens` (budget-mapped levels), falling back to a
     * launch-param restart only for levels the wire can't express.
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
        // ⚠ `--permission-prompt-tool stdio` IS NOT FREE. It adds a tool to the tool
        // set, the tool set is part of the prompt prefix, and a changed prefix re-caches
        // the ENTIRE conversation. MEASURED on the user's own server (2026-08-03), same
        // session, alternating laptop CLI / phone: bare CLI cache_read=38732
        // cache_creation=13 Conch WITH stdio cache_read=25073 cache_creation=15195 ←
        // full re-read bare CLI again cache_read=38745 cache_creation=260 ← and back
        // Conch WITHOUT stdio cache_read=39005 cache_creation=15 ← free bare CLI again
        // cache_read=39020 cache_creation=15 ← free So every switch between the terminal
        // and the phone was re-billing the whole history.
        //
        // The flag exists to route tool permissions to us as live cards. In
        // BYPASS mode there are no permissions to route — every tool is
        // auto-approved — so there it is pure cost and we drop it, which makes
        // Conch's prefix identical to the plain CLI's and the two share one
        // cache. In SAFE/AUTO it stays: that is the mode where the cards ARE
        // the feature, and the user chose to be asked.
        // ⚠ KNOWN TRADE-OFF, stated rather than hidden: in BYPASS an
        // AskUserQuestion can no longer be answered from the app (it renders as
        // the read-only mirrored card and the CLI proceeds on its own).
        val permissionToolArg =
            if (input.approvalMode == AgentApprovalMode.YOLO) "" else " --permission-prompt-tool stdio"
        return "${sandboxEnv}${CHECKPOINT_ENV}${thinkingEnv}stdbuf -oL claude" +
            " --output-format stream-json --input-format stream-json" +
            " --include-partial-messages --verbose" +
            permissionToolArg +
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

    /**
     * FILE CHECKPOINTS — what makes "the agent trashed my repo and I'm not at
     * my laptop" recoverable from the phone.
     *
     * Interactive `claude` snapshots edited files by default
     * (`fileCheckpointingEnabled`), but in SDK/headless mode — which is every
     * launch Conch makes — the same feature is OFF unless this env var is set
     * (binary-verified gate: `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING &&
     * !CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING`). Without it `rewind_files`
     * answers "File rewinding is not enabled." forever — proven live, then
     * proven fixed: with the var set, a dry run reported the changed file and
     * the apply really restored its previous content (2026-08-02).
     *
     * So the app turns it ON: the user gets the same safety net they would
     * have sitting at the machine. The CLI's own kill switch
     * (`CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING=1` in their shell profile)
     * still wins, because it is checked second.
     */
    private const val CHECKPOINT_ENV = "CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=1 "

    /** UI reasoning level → fixed MAX_THINKING_TOKENS budget. Opus caps at
     *  31999; the ladder is chosen to be clearly distinguishable per level
     *  (≈ the classic think / megathink / ultrathink tiers). null for an
     *  unknown/blank level → leave the CLI on its adaptive default.
     *  Internal: the SAME ladder feeds the live `set_max_thinking_tokens`
     *  control request (mid-chat effort switch without a restart). */
    internal fun thinkingBudget(effort: String?): Int? = when (effort?.trim()?.lowercase()) {
        "low" -> 4096
        "medium" -> 12000
        "high" -> 24000
        "max" -> 31999
        else -> null
    }

    override fun parseStreamLine(line: String): List<AgentMessage> {
        val msgs = ClaudeMessageParser.parse(line)
        // THE SESSION'S OWN EFFORT, from the record the CLI writes itself.
        //
        // The parser only ever recognised `ultra_effort_enter`, on a note from
        // 2026-06-13 that "the regular levels never appear in the file". That
        // stopped being true: the CLI now stamps a top-level `"effort":"…"` on
        // its records (2424 of them in one real session). Because we did not
        // read it, the app fell back to its own stored pick — so a session the
        // CLI was running at xhigh was DISPLAYED as low and, worse, relaunched
        // with the low thinking budget (user, 2026-08-02). Read the truth.
        ClaudeMessageParser.effortOf(line)?.let { eff ->
            return msgs + AgentMessage.System(
                id = "claude-effort-observed",
                subtype = "reasoning_observed",
                reasoning = eff,
                raw = "",
            )
        }
        // Stamp user rows with the JSONL record's own uuid — the anchor the
        // rewind protocol addresses. Done HERE, at the single Claude parse
        // choke point, rather than in both parser paths (fast JsonReader +
        // slow AST). Costs one substring scan, and only for lines that
        // actually produced a user row.
        if (msgs.none { it is AgentMessage.UserText }) return msgs
        val uuid = recordUuidOf(line) ?: return msgs
        return msgs.map { m ->
            if (m is AgentMessage.UserText && m.recordUuid == null) m.copy(recordUuid = uuid) else m
        }
    }

    /** Top-level `"uuid":"…"` of a JSONL record. Cheap substring read — ids
     *  are plain hex/dashes and never escaped. */
    private fun recordUuidOf(line: String): String? {
        val at = line.indexOf("\"uuid\":\"")
        if (at < 0) return null
        val from = at + 8
        val end = line.indexOf('"', from)
        if (end <= from) return null
        return line.substring(from, end)
    }

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
  # Skip HOT files — modified within the last minute is almost certainly the
  # session the user is actively in (every turn appends to it), and the /proc
  # guard misses it because our own tail-poll reader holds NO fd (it re-execs
  # tail each poll). Rewriting a live file (sed shrinks it 4 bytes/tag + mv
  # swaps the inode) makes that poll misread the shrink as a compaction and
  # scramble the chat. This rewrite only needs to happen once the session is
  # IDLE (it just makes it show in `claude --resume`), so deferring on a hot
  # file is harmless — the next refresh converts it once cold.
  [ -n "${'$'}(find "${'$'}f" -mmin -1 2>/dev/null)" ] && continue
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
  # Effort, exactly like the model: the CLI stamps a top-level `"effort":"…"`
  # on its records, so the LAST one is what this session is running at. Read it
  # HERE, at listing time, or the topbar has nothing to show for the first
  # second of a chat and prints an invented catalog default instead — the
  # "medium → xhigh" flicker on open (user, 2026-08-03). Closed set of tokens
  # so a stray key named "effort" elsewhere can't poison the column.
  reasoning=${'$'}(tail -c 262144 "${'$'}f" 2>/dev/null | grep -oE '"effort"[[:space:]]*:[[:space:]]*"(none|minimal|low|medium|high|xhigh|max|ultracode)"' | tail -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
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
  # A USER-SET title (rename_session / the CLI's own rename — stored as
  # {"type":"custom-title","customTitle":"…"}) BEATS the auto ai-title:
  # the user explicitly chose that name.
  title=${'$'}(tail -c 262144 "${'$'}f" 2>/dev/null | grep -ao '"customTitle":"[^"]*"' | tail -1 | sed -E 's/.*"customTitle":"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
  [ -z "${'$'}title" ] && title=${'$'}(grep -ao '"customTitle":"[^"]*"' "${'$'}f" 2>/dev/null | tail -1 | sed -E 's/.*"customTitle":"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
  [ -z "${'$'}title" ] && title=${'$'}(tail -c 262144 "${'$'}f" 2>/dev/null | grep -ao '"aiTitle":"[^"]*"' | tail -1 | sed -E 's/.*"aiTitle":"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
  [ -z "${'$'}title" ] && title=${'$'}(grep -ao '"aiTitle":"[^"]*"' "${'$'}f" 2>/dev/null | tail -1 | sed -E 's/.*"aiTitle":"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
  if [ -n "${'$'}title" ]; then preview=${'$'}(printf '%s\037%s' "${'$'}title" "${'$'}candidates"); else preview="${'$'}candidates"; fi
  # 7-col contract: id, mtime, path, model, reasoning, size, preview.
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}id" "${'$'}mtime" "${'$'}f" "${'$'}model" "${'$'}reasoning" "${'$'}size" "${'$'}preview"
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
        // Cheap shape check FIRST. Callers hand us split fragments, and an empty
        // one used to reach kotlinx and throw, which SilentlyTry then logged —
        // a warn per fragment plus an exception's cost, forever ("JSON input: "
        // with nothing after it, 2026-07-29).
        if (line.isBlank() || !line.trimStart().startsWith("{")) return ""
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
# OAuth = a credentials file that actually CARRIES a usable token — NOT merely
# the presence of the key names. A dead / logged-out session leaves the file with
# the `claudeAiOauth` keys but EMPTY values (accessToken:"", refreshToken: "",
# expiresAt:0). That is NOT a login — the CLI refuses ("OAuth session expired and
# could not be refreshed") — so it must read as "not logged in", never a phantom
# OAuth account with a "login expired" badge. Require a NON-EMPTY access OR
# refresh token value (either suffices — the CLI silently renews an empty access
# token from a live refresh token). Never read/emit the value itself, only its
# presence. CLAUDE_CODE_OAUTH_TOKEN env is the headless variant.
claude_oauth_live() {
  for f in ~/.claude/.credentials.json ~/.claude/credentials.json ~/.config/claude/.credentials.json; do
    [ -f "${'$'}f" ] || continue
    grep -qE '"(access_?[Tt]oken|refresh_?[Tt]oken)"[[:space:]]*:[[:space:]]*"[^"]+"' "${'$'}f" 2>/dev/null && return 0
  done
  [ -n "${'$'}CLAUDE_CODE_OAUTH_TOKEN" ] && return 0
  return 1
}
if claude_oauth_live; then CM="${'$'}CM oauth"; fi
if [ -n "${'$'}ANTHROPIC_API_KEY" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?ANTHROPIC_API_KEY=' ~/.bashrc ~/.profile ~/.bash_profile ~/.env 2>/dev/null; then CM="${'$'}CM api"; fi
if [ -n "${'$'}ANTHROPIC_AUTH_TOKEN" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?ANTHROPIC_AUTH_TOKEN=' ~/.bashrc ~/.profile ~/.bash_profile ~/.env 2>/dev/null; then CM="${'$'}CM bearer"; fi
if [ "${'$'}CLAUDE_CODE_USE_VERTEX" = "1" ] || [ -n "${'$'}ANTHROPIC_VERTEX_PROJECT_ID" ]; then CM="${'$'}CM vertex"; fi
if [ "${'$'}CLAUDE_CODE_USE_BEDROCK" = "1" ]; then CM="${'$'}CM bedrock"; fi
echo "claude_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
if [ "${'$'}CLAUDE_CODE_USE_BEDROCK" = "1" ]; then echo "claude_active=bedrock"
elif [ "${'$'}CLAUDE_CODE_USE_VERTEX" = "1" ] || [ -n "${'$'}ANTHROPIC_VERTEX_PROJECT_ID" ]; then echo "claude_active=vertex"
elif [ -n "${'$'}ANTHROPIC_AUTH_TOKEN" ]; then echo "claude_active=bearer"
elif [ -n "${'$'}ANTHROPIC_API_KEY" ]; then echo "claude_active=api"
elif claude_oauth_live; then echo "claude_active=oauth"
else echo "claude_active="; fi
# Claude Code RUN-STATE — OAuth mode only (an API key path always works, so we
# don't gate it). A present OAuth cred does NOT mean a turn will run: the account
# can be in many states (no subscription, trial ended/not-started, payment due,
# login expired, rate limited) that the CLI refuses turns on. Detected SERVER-SIDE
# from Anthropic's oauth/profile (+usage) — token never leaves the box. Classifier
# validated live against a lapsed-subscription account. jq is NOT assumed (pure
# sed/grep). Runs in a subshell so its vars/`exit` can't leak into the shared
# probe. Emits `claude_run_state=<NAME>` (+ optional `claude_run_data`) →
# AgentStatus.claudeState. Full taxonomy: reference_claude_code_run_states.
case " ${'$'}CM " in
  *" oauth "*) (
    CRED="${'$'}HOME/.claude/.credentials.json"; [ -f "${'$'}CRED" ] || CRED="${'$'}HOME/.config/claude/.credentials.json"
    TOK=${'$'}(sed -n -E 's/.*"access_?[Tt]oken"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' "${'$'}CRED" 2>/dev/null | head -1)
    # A `claude setup-token` login lives in CLAUDE_CODE_OAUTH_TOKEN (we persist it
    # to ~/.profile; the CLI reads the same var) — it writes NO credentials file,
    # so a dead/empty credentials.json can sit right next to a perfectly live env
    # token. File token first (the CLI's refresh cycle keeps it current), env as
    # fallback — otherwise a fresh setup-token login probes as "login expired"
    # off the stale file while claude itself runs fine.
    [ -z "${'$'}TOK" ] && TOK="${'$'}CLAUDE_CODE_OAUTH_TOKEN"
    if [ -z "${'$'}TOK" ]; then
      # Empty access token. This is a DEAD credential, not "logged in & ready":
      # the file can carry the claudeAiOauth KEYS with empty VALUES + expiresAt:0
      # after a session dies (methods-detection still sees the keys → shows oauth,
      # but no turn can run). No refresh token either ⇒ unrefreshable ⇒ the CLI
      # itself refuses with "OAuth session expired and could not be refreshed" →
      # TOKEN_EXPIRED (re-login on the server). A refresh token PRESENT ⇒ the CLI
      # renews silently on next use, so we can't judge the subscription from here
      # without consuming/rotating it (unsafe in a status probe) → UNKNOWN (don't
      # block; the turn / live-auth surfaces the truth). Previously this whole
      # branch just `exit 0`ed → no run_state → a dead session read as "ready".
      RTOK=${'$'}(sed -n -E 's/.*"refresh_?[Tt]oken"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' "${'$'}CRED" 2>/dev/null | head -1)
      if [ -z "${'$'}RTOK" ]; then echo "claude_run_state=TOKEN_EXPIRED"; else echo "claude_run_state=UNKNOWN"; fi
      exit 0
    fi
    VER=${'$'}(claude --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    UA="claude-code/${'$'}{VER:-2.0.0} (external, cli)"
    prof=${'$'}(curl -sS -m 6 -w '\nHTTP:%{http_code}' -H "Authorization: Bearer ${'$'}TOK" -H "anthropic-beta: oauth-2025-04-20" -H "User-Agent: ${'$'}UA" "https://api.anthropic.com/api/oauth/profile" 2>/dev/null)
    PC=${'$'}(printf '%s' "${'$'}prof" | sed -n 's/^HTTP://p' | tail -1)
    PJ=${'$'}(printf '%s' "${'$'}prof" | sed '${'$'}d')
    # profile 200 itself proves the token is live (validate is 405 on GET).
    # 401 = auth dead. 403 needs the BODY: a `claude setup-token` login mints an
    # INFERENCE-ONLY token (authorize URL literally has scope=user:inference), so
    # profile/usage answer 403 permission_error "does not meet scope requirement"
    # while the token is perfectly LIVE and turns run (verified: claude -p exit 0
    # on exactly this state). That's OK — the subscription simply can't be
    # pre-checked with it; the turn surfaces any problem. Any OTHER 403 = auth dead.
    if [ "${'$'}PC" = "401" ]; then echo "claude_run_state=TOKEN_EXPIRED"; exit 0; fi
    if [ "${'$'}PC" = "403" ]; then
      if printf '%s' "${'$'}PJ" | grep -qE 'permission_error|scope requirement'; then echo "claude_run_state=OK"; else echo "claude_run_state=TOKEN_EXPIRED"; fi
      exit 0
    fi
    if [ "${'$'}PC" != "200" ]; then echo "claude_run_state=UNKNOWN"; exit 0; fi
    h() { printf '%s' "${'$'}PJ" | grep -qE "${'$'}1"; }
    mx=n; h '"has_claude_max"[[:space:]]*:[[:space:]]*true' && mx=y
    pr=n; h '"has_claude_pro"[[:space:]]*:[[:space:]]*true' && pr=y
    sa=n; h '"subscription_status"[[:space:]]*:[[:space:]]*"(active|trialing)"' && sa=y
    pd=n; { h '"payment_auth_hosted_invoice_url"[[:space:]]*:[[:space:]]*"http' || h '"pending_invoice"[[:space:]]*:[[:space:]]*("|\{|true)'; } && pd=y
    TE=${'$'}(printf '%s' "${'$'}PJ" | sed -n -E 's/.*"claude_code_trial_ends_at"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' | head -1)
    NW=${'$'}(date +%s); TS=; [ -n "${'$'}TE" ] && TS=${'$'}(date -d "${'$'}TE" +%s 2>/dev/null)
    ST=OK; DA=
    if [ "${'$'}mx" = y -o "${'$'}pr" = y ] && [ "${'$'}sa" = y ]; then ST=OK
    elif [ -n "${'$'}TS" ] && [ "${'$'}TS" -gt "${'$'}NW" ]; then ST=TRIAL_ACTIVE; DA="${'$'}(( (${'$'}TS-${'$'}NW)/86400 )) days"
    elif [ -n "${'$'}TS" ] && [ "${'$'}TS" -le "${'$'}NW" ] && [ "${'$'}mx" = n ] && [ "${'$'}pr" = n ]; then ST=TRIAL_ENDED
    elif [ "${'$'}pr" = y ] && [ -z "${'$'}TE" ]; then ST=TRIAL_START
    elif [ "${'$'}pd" = y ]; then ST=PAYMENT_DUE
    elif [ "${'$'}mx" = n ] && [ "${'$'}pr" = n ]; then ST=NO_SUBSCRIPTION
    fi
    # Usage overlay only for a runnable state; skip when usage itself is 429/empty
    # (do NOT fake rate-limit). Windows that HARD-block OUR turns: five_hour,
    # seven_day, and seven_day_oauth_apps (the third-party-OAuth-app bucket = our
    # own access path). Model-scoped opus/sonnet are a per-model degrade, excluded.
    if [ "${'$'}ST" = OK ] || [ "${'$'}ST" = TRIAL_ACTIVE ]; then
      usg=${'$'}(curl -sS -m 6 -w '\nHTTP:%{http_code}' -H "Authorization: Bearer ${'$'}TOK" -H "anthropic-beta: oauth-2025-04-20" -H "User-Agent: ${'$'}UA" "https://api.anthropic.com/api/oauth/usage" 2>/dev/null)
      UC=${'$'}(printf '%s' "${'$'}usg" | sed -n 's/^HTTP://p' | tail -1)
      UJ=${'$'}(printf '%s' "${'$'}usg" | sed '${'$'}d')
      if [ "${'$'}UC" = "200" ]; then
        MU=0; RS=
        for w in five_hour seven_day seven_day_oauth_apps; do
          bd=${'$'}(printf '%s' "${'$'}UJ" | grep -oE "\"${'$'}w\"[[:space:]]*:[[:space:]]*\{[^{}]*\}" | head -1)
          [ -z "${'$'}bd" ] && continue
          u=${'$'}(printf '%s' "${'$'}bd" | sed -n -E 's/.*"utilization"[[:space:]]*:[[:space:]]*([0-9.]+).*/\1/p' | head -1)
          [ -z "${'$'}u" ] && continue
          ui=${'$'}{u%.*}
          if [ "${'$'}ui" -gt "${'$'}MU" ] 2>/dev/null; then MU=${'$'}ui; RS=${'$'}(printf '%s' "${'$'}bd" | sed -n -E 's/.*"resets_at"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' | head -1); fi
        done
        if [ "${'$'}MU" -ge 100 ] 2>/dev/null; then ST=RATE_LIMITED; DA=${'$'}RS
        elif [ "${'$'}MU" -ge 80 ] 2>/dev/null; then ST=NEAR_LIMIT; DA=${'$'}RS
        fi
      fi
    fi
    # Plan tier for the limits sheet header — only knowable from a 200 profile
    # (an inference-only setup-token 403s above and never reaches here, so its
    # tier stays unknown and the sheet just omits it).
    PLAN=
    if [ "${'$'}mx" = y ]; then PLAN=Max; elif [ "${'$'}pr" = y ]; then PLAN=Pro; fi
    [ -z "${'$'}PLAN" ] && { [ "${'$'}ST" = TRIAL_ACTIVE ] || [ "${'$'}ST" = TRIAL_START ]; } && PLAN="Pro trial"
    [ -z "${'$'}PLAN" ] && [ "${'$'}ST" = NO_SUBSCRIPTION ] && PLAN=Free
    [ -n "${'$'}PLAN" ] && echo "claude_plan=${'$'}PLAN"
    echo "claude_run_state=${'$'}ST"
    [ -n "${'$'}DA" ] && echo "claude_run_data=${'$'}DA"
  ) ;;
esac
""".trimIndent()

    /**
     * The model the CLI starts on when we pass no `--model` — the resolved
     * id of the initialize response's "default" row, published by
     * [adoptInitState]. Costs no extra round-trip.
     */
    override suspend fun probeDefaultModel(exec: AgentExec): String? = claudeDefaultModel

    /**
     * Model catalog straight from the CLI's OWN registry: launch a headless
     * stream-json process in a THROWAWAY cwd, send the `initialize` control
     * request, and read its response — `models[{value,resolvedModel,
     * displayName,disabled,supportedEffortLevels}]`, exactly what the Agent
     * SDK gets. No PTY, no ANSI, no menu scraping, no parse drift when the
     * TUI restyles (the whole class of «menu format changed AGAIN» bugs).
     *
     * The old path drove the interactive `/model` menu over a PTY through a
     * ~200-line terminal emulator; a live chat now gets this same data FREE
     * from its persistent channel's handshake ([adoptInitState] is fed by
     * AgentSessionPersistentStream), and this probe covers the no-chat-open
     * warm-up (ModelCatalogPrefetcher) with one plain exec.
     *
     * The throwaway cwd + project-dir purge preserve the 2026-06-25
     * invariant: a probe must never litter `claude --resume`.
     */
    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> {
        val tag = "SshAi-Models"
        val initJson =
            "{\"type\":\"control_request\",\"request_id\":\"cat-probe\"," +
                "\"request\":{\"subtype\":\"initialize\"}}"
        val probeCwd = "/tmp/.conch-ctlprobe-${java.util.UUID.randomUUID()}"
        val script = ai.eight24family.conch.agent.RemoteEnv.PATH_PREAMBLE_INLINE +
            ai.eight24family.conch.agent.AuthSelector.claudeFullScopePrefix() +
            "mkdir -p $probeCwd 2>/dev/null; cd $probeCwd; " +
            "out=\$(printf '%s\\n' ${shellEscape(initJson)} | " +
            "timeout 40 claude --output-format stream-json --input-format stream-json" +
            " --verbose 2>/dev/null | grep -m1 '\"control_response\"'); " +
            "cd /; rm -rf $probeCwd \$HOME/.claude/projects/*conch-ctlprobe* 2>/dev/null; " +
            "printf '%s\\n' \"\$out\""
        val raw = exec.exec("bash -lc " + shellEscape(script))
        val line = raw?.lineSequence()?.firstOrNull { it.contains("\"control_response\"") }?.trim()
        if (line.isNullOrBlank()) {
            // Loud on purpose: an empty answer here is indistinguishable from
            // "no models" downstream, and the caller keeps its cached catalog.
            android.util.Log.w(tag, "claude initialize probe got no control_response (raw=${raw?.length ?: 0}B)")
            return emptyMap()
        }
        val resp = ClaudeControlWire.parseControlResponse(line)
        val payload = resp?.takeIf { it.ok }?.payload
        if (payload == null) {
            android.util.Log.w(tag, "claude initialize probe error: ${resp?.error ?: "unparseable"}")
            return emptyMap()
        }
        val st = ClaudeInitState.parse(payload)
        android.util.Log.d(tag, "initialize probe: models=${st.models.map { it.value }}")
        adoptInitState(st)
        return ClaudeInitState.toPickerMap(st)
    }

    /**
     * Publish an initialize handshake's catalog into the spec-level state the
     * topbar/picker read: the CLI default (label + wire key), the unavailable
     * set, and the effort ladder. Called from BOTH sources — the live
     * persistent channel's handshake and [probeAvailableModels].
     */
    internal fun adoptInitState(st: ClaudeInitState) {
        val (label, key) = ClaudeInitState.defaultModel(st)
        if (label != null) claudeDefaultModel = label
        if (key != null) claudeDefaultModelKey = key
        claudeUnavailableLabels = ClaudeInitState.unavailableLabels(st)
        val levels = ClaudeInitState.effortLevels(st)
        if (levels.isNotEmpty()) {
            // The handshake carries the LADDER but not the current level —
            // keep whatever default we already knew when it's still offered.
            val prevDefault = probedEffortInfo?.defaultEffort
                ?: CLAUDE_REASONING_INFO.defaultEffort
            probedEffortInfo = ModelReasoningInfo(
                defaultEffort = when {
                    prevDefault in levels -> prevDefault
                    "medium" in levels -> "medium"
                    else -> levels.first()
                },
                levels = levels.map { l ->
                    ReasoningLevel(
                        effort = l,
                        displayName = l.replaceFirstChar { it.uppercase() },
                        description = CLAUDE_EFFORT_DESCRIPTIONS[l].orEmpty(),
                    )
                },
            )
        }
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
    /**
     * Field layout, read by index in [inferTurnState]:
     *   0 type · 1 isMeta · 2 hasToolResult · 3 hasToolUse · 4 outputTokens
     *   5 messageId · 6 timestamp · 7 text(≤160, tabs/newlines flattened)
     *   8 stopReason
     * Malformed / partial lines are skipped silently — the last line of a file
     * being appended to is routinely half-written, and one bad line must not
     * cost us the whole window (which is exactly what remote jq did: it aborts).
     */
    override fun projectTurnStateRecords(lines: Sequence<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (line in lines) {
            val t = line.trim()
            if (t.length < 2 || t[0] != '{') continue
            // Cheap pre-filter before the real parse: the window can hold
            // multi-hundred-KB tool_result lines and we only care about two
            // top-level types.
            if (!t.contains("\"type\":\"user\"") && !t.contains("\"type\":\"assistant\"") &&
                !t.contains("\"type\": \"user\"") && !t.contains("\"type\": \"assistant\"")
            ) continue
            val obj = runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() ?: continue
            val type = obj["type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: continue
            if (type != "user" && type != "assistant") continue
            val isMeta = obj["isMeta"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } == "true"
            val msg = runCatching { obj["message"]?.jsonObject }.getOrNull()
            val content = msg?.get("content")

            var hasToolResult = false
            var hasToolUse = false
            val text = StringBuilder()
            when {
                content is JsonArray -> {
                    // jq: [.[]? | (.text // "")] | join(" ") — EVERY element
                    // contributes, non-text ones as "", hence the plain join.
                    val parts = ArrayList<String>(content.size)
                    for (el in content) {
                        val o = runCatching { el.jsonObject }.getOrNull()
                        val bt = o?.get("type")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                        if (bt == "tool_result") hasToolResult = true
                        if (bt == "tool_use") hasToolUse = true
                        parts += o?.get("text")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
                    }
                    text.append(parts.joinToString(" "))
                }
                content != null -> {
                    runCatching { content.jsonPrimitive.content }.getOrNull()?.let { text.append(it) }
                }
            }
            val flat = text.toString()
                .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                .take(160)
            val tokens = runCatching {
                msg?.get("usage")?.jsonObject?.get("output_tokens")?.jsonPrimitive?.content
            }.getOrNull() ?: "0"
            val id = msg?.get("id")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            val ts = obj["timestamp"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            val stop = msg?.get("stop_reason")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?.takeIf { it != "null" }.orEmpty()
            out += listOf(
                type, isMeta.toString(), hasToolResult.toString(), hasToolUse.toString(),
                tokens, id, ts, flat, stop,
            )
        }
        return out
    }

    override fun inferTurnState(records: List<List<String>>, frozenForMs: Long?): TurnSignals {
        val recs = records.filter { it.size >= 7 }
        if (recs.isEmpty()) return TurnSignals()
        // ⚠ SKIP isMeta ROWS (field 1). They are LOCAL bookkeeping the CLI writes
        // into the same file — a slash command's `<local-command-caveat>` /
        // `<command-name>` echo, `<local-command-stdout>`, an image-dimensions
        // carrier — not events of an agent turn. They carry TEXT, so they used to
        // win `lm`, and a `user` lm has NO staleness escape below: the app reported
        // inFlight=true FOREVER, the spinner never stopped, and `turnComplete` went
        // false so the stuck-turn reconcile couldn't rescue it either. Running
        // `/model opus` mid-session is exactly that: the CLI appends a meta row
        // AFTER the assistant's end_turn and the chat "thinks" forever. Measured on
        // a real 15 150-row session: 19 file positions where a meta row falsely
        // held inFlight while the last real event was `assistant end_turn`.
        // `[Request interrupted by user]` is NOT meta (verified over 60
        // occurrences), so the interrupt verdict is untouched.
        val lm = recs.lastOrNull {
            it[1] != "true" &&
                ((it.getOrNull(7)?.isNotBlank() == true) || it[2] == "true" || it[3] == "true")
        }
        val lmText = lm?.getOrNull(7).orEmpty()
        val lmStop = lm?.getOrNull(8).orEmpty()
        val inFlight: Boolean = when {
            lm == null -> false
            lm[0] == "user" && lmText.contains("[Request interrupted by user]") -> false
            lm[0] == "assistant" && lmText.trimStart().startsWith("No response requested") -> false
            lm[0] == "assistant" && lmStop in TERMINAL_STOP_REASONS -> false
            // A user prompt / tool_result with no assistant after it means the model
            // owes us a reply. Bounded by the SAME staleness escape as the
            // mid-stream case: without one, any record shape that lands last and
            // isn't an assistant pins the spinner on for the life of the chat with
            // no reconcile possible (turnComplete is false here by construction).
            // 12 min of a frozen file after a tool_result is a dead turn, not a
            // slow one — the model's own reply latency is seconds.
            lm[0] == "user" -> frozenForMs == null || frozenForMs < AWAIT_STALE_MS
            lm[0] == "assistant" && lmStop == "tool_use" -> true
            lm[0] == "assistant" -> // no stop_reason yet → mid-stream; clear only if long-frozen
                frozenForMs == null || frozenForMs < AWAIT_STALE_MS
            else -> false
        }
        val thinking = inFlight && lm != null && lm[0] == "user" &&
            !lmText.contains("[Request interrupted by user]")
        // Turn start = the last REAL user prompt: not a tool_result (field 2), and
        // not a meta row (field 1) — a slash command's echo would otherwise restart
        // the working timer and the per-turn token counter at the command.
        val startIdx = recs.indexOfLast { it[0] == "user" && it[2] != "true" && it[1] != "true" }
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
 * A resolved model display name: `<Family> <ver>[ <1M marker>]`. Family is
 * one capitalized word ("Opus", "Fable", "Mythos"); version may be dotless
 * ("5"). Group 3 captures the 1M-context marker in the three spellings the
 * menu has used across CLI versions.
 */
internal val CLAUDE_MODEL_NAME_RX = Regex(
    "\\b([A-Z][a-z]+) (\\d+(?:\\.\\d+)?)( with \\d+M context| \\d+M\\b| \\(\\d+M(?: context)?\\))?",
)

/** Labels the CLI flagged unavailable in the last `/model` parse. Read by
 *  [ClaudeTopbarUi] to skip them in the chip and grey them in the picker.
 *  Singleton (spec is stateless per chat); volatile for the UI-thread read. */
@Volatile
internal var claudeUnavailableLabels: Set<String> = emptySet()

/**
 * What the menu's "Default (recommended)" row RESOLVES TO — a concrete model,
 * e.g. "Opus 4.8". Published for the topbar so a brand-new chat opens with the
 * model it will actually start on and the user can just type instead of being
 * made to pick.
 *
 * This does NOT walk back (user, 2026-06-13): that ruled out surfacing the
 * ABSTRACTION — a picker row literally called "Default", which told the user
 * nothing. The picker still lists only concrete models. What we show here is
 * the resolved model NAME; the word "Default" appears nowhere.
 */
@Volatile
internal var claudeDefaultModel: String? = null

/**
 * The MENU KEY of that same row (e.g. `opus`) — what actually goes on the wire
 * as `--model`.
 *
 * Published next to the label so the topbar and the command line read ONE
 * value. Resolving the label back to a key by string match was fragile: an
 * exact compare misses when the row resolves to "Opus 5 with 1M context" while
 * the picker entry reads "Opus 5 1M", and it silently degraded to the first map
 * entry — which is `sonnet`.
 */
@Volatile
internal var claudeDefaultModelKey: String? = null

/** Prose for levels the slider doesn't annotate inline. DESCRIPTIONS
 *  only — the level LIST itself always comes from the server. */
private val CLAUDE_EFFORT_DESCRIPTIONS = mapOf(
    "low" to "Fast responses, lighter reasoning",
    "medium" to "Balanced speed and depth",
    "high" to "Deeper reasoning for complex problems",
    "max" to "Maximum reasoning budget",
)

/** Normalize a model display label for unavailable-matching: drop the 1M-context
 *  marker (so "Fable 5" from the banner matches "Fable 5 1M" from id-resolution),
 *  lowercase, collapse whitespace. */
private fun normModel(s: String): String = s.lowercase()
    .replace(Regex("\\(?\\s*(with\\s+)?1m(\\s+context)?\\)?"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

/**
 * Best-effort reverse of the CLI id scheme —
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
/** CLI aliases that every Claude Code build accepts as `--model <alias>`.
 *  Names only — NEVER version labels: a baked-in "Opus 4.8" goes stale the day
 *  a new family ships, and the real labels arrive from the live /model probe
 *  (or its prefs cache) anyway. */
private val CLAUDE_BASE_ALIASES = listOf("opus", "sonnet", "haiku")

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
            // NO hardcoded label table here either — a baked-in "Opus 4.8"
            // mislabels the alias the moment a new family ships.
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
        val sel = usable(state.selectedModel)
        val obs = usable(state.observedModel)
        // A running session can be FORCE-SWITCHED mid-turn: Claude's safeguard
        // fallback swaps the model (e.g. Fable 5 → Opus 4.8 after it flags a
        // message) and records the NEW model in message.model (observedModel).
        // When observedModel diverges from the user's pick, the session is
        // ACTUALLY running the observed model — so the topbar must show THAT, not
        // the stale pick, or it lies. Compare RESOLVED labels so an alias vs its
        // resolved id (e.g. "sonnet" ↔ claude-sonnet-4-6) is never mistaken for a
        // switch. Absent a real observation (obs==null, pre-first-turn) the pick
        // still wins, so there's no flicker on open. ⚠ THE CHIP SHOWS WHAT IS
        // SET, NOT WHAT WE ONCE WANTED. A pick is displayed ONLY while it is the
        // thing actually in force — which is the SAME predicate the launch uses
        // to decide whether to send `--model` at all. A pick the session has
        // since overruled (or that we deliberately stopped sending, so the
        // session kept its own model) must not be shown: that is the difference
        // between a label and a fact.
        val pickStillInForce = sel != null && !state.observationNewerThanPick
        val switched = sel != null && obs != null && state.observationNewerThanPick &&
            resolve(state, obs) != resolve(state, sel)
        val pick = (if (switched || !pickStillInForce) (obs ?: sel) else (sel ?: obs))
            ?: usable(state.sessionInitialModel)
            // NEVER invent the arbitrary first map entry (which is "sonnet" → "Sonnet
            // 5") when NOTHING is known. A transient state wipe (e.g. a false-positive
            // compaction briefly clearing observedModel) leaves all three sources null
            // — returning null makes the topbar HOLD its last label instead of lying
            // "Sonnet 5". The recommended-model substitution below fires ONLY when a
            // model was genuinely REPORTED but is unavailable (e.g. suspended Fable 5)
            // — gated strictly on `reported!= null`. Everything known is UNAVAILABLE —
            // typically the server pins a dead model in settings.json (proven live
            // 2026-07-23: the box pinned "claude-fable-5[1m]" while the session's last
            // 60 turns all ran claude-opus-4-8). Do NOT guess from menu order:
            // availableModels is a map whose first entry is "sonnet" -> "Sonnet 5",
            // which has nothing to do with claude's real fallback, so the old
            // substitution advertised Sonnet 5 on a session that was actually Opus 4.8
            // every single reopen. Nothing observed at all -> null, so the topbar
            // HOLDS its last label rather than inventing one
            // (TOPBAR-MODEL-NEVER-INVENTED-1).
            ?: state.observedModel?.takeIf { it.isNotBlank() }
            // A FRESH chat has no pick, no observation and no session header — but
            // it is NOT unknown. The `/model` menu's "Default (recommended)" row
            // says exactly what the CLI starts with when we pass no --model, so
            // show THAT: a new chat then opens with its real default already
            // selected and the user can just start typing instead of being made to
            // choose. This is NOT the invention TOPBAR-MODEL-NEVER-INVENTED-1
            // forbids — that was pulling availableModels' arbitrary first entry
            // out of thin air. This is a PROBED fact about the CLI's own default.
            ?: usable(state.defaultModel)
            // Claude exposes no `defaultModel` — that field is Codex's
            // config.toml notion — and `availableModels` deliberately carries no
            // "default" key either. The resolved default is published separately
            // from the initialize handshake as [claudeDefaultModel]: a concrete model
            // name, which is the honest answer to "what starts when we pass no
            // --model".
            ?: usable(claudeDefaultModel)
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
        // The "default" key is chip metadata, not a row — users pick
        // models, not a "Default" word.
        val items = state.availableModels.entries
            .filter { it.key != "default" }
            // Entries no CLI registry has ever confirmed are leftovers from the
            // TUI-scraping era — the CLI does not offer them, so picking one is
            // a coin flip. They stay in availableModels (a chat pinned to an old
            // alias must still resolve its label) but they are not choices.
            // Fail-open: with no provenance data at all, hiddenModels is empty
            // and the picker behaves exactly as before.
            .filter { it.key !in state.hiddenModels }
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
        // NO HARDCODED MODEL LIST. This used to fall back to a baked-in trio
        // ("Opus 4.8" / "Sonnet 4.6" / "Haiku 4.5"), which is a lie the moment
        // Anthropic ships anything new: the box was already on CLI 2.1.220
        // serving Opus 5 while the picker still offered Opus 4.8, and picking a
        // row sent an alias whose LABEL was fiction. The aliases themselves are
        // stable CLI input, so offer THEM unlabelled rather than invent version
        // numbers
        val info = state.reasoningCatalog.values.firstOrNull() ?: CLAUDE_REASONING_INFO
        return CLAUDE_BASE_ALIASES.map { alias ->
            ModelMenuItem(
                alias.replaceFirstChar { it.uppercase() }, alias,
                reasoning = info.levels, defaultReasoning = info.defaultEffort,
            )
        }
    }

    override fun reasoningLabel(state: TopbarModelState): String? {
        // Chain (mirror the SESSION, never impose a default): explicit
        // user pick → what the LIVE session reports (observedReasoning,
        // e.g. ultracode) → what THIS chat opened on → the PROBED current
        // effort. observedReasoning sits right under
        // the user pick because it's ground truth from the running session
        // — it's why a `/effort ultracode` typed in the CLI now shows
        // "Ultracode" instead of the stale probe's "xHigh" (user, 2026-06-13).
        //
        // ⚠ AND THE PICK DOES NOT OUTRANK REALITY. It used to win outright, so a
        // stale `selected_reasoning_chat_<id>` printed "low" under the model
        // name while the session was demonstrably running at xhigh (user,
        // 2026-08-02 — 2424 `"effort":"xhigh"` records on the box). A pick only
        // wins while it is NEWER than the session's own last report; the same
        // law the model chip follows.
        val pick = state.selectedReasoning?.takeIf { it.isNotBlank() }
        val seen = state.observedReasoning?.takeIf { it.isNotBlank() }
        val effort = when {
            pick != null && (seen == null || state.reasoningPickIsNewer) -> pick
            seen != null -> seen
            else -> state.sessionInitialReasoning?.takeIf { it.isNotBlank() }
                ?: state.defaultReasoning?.takeIf { it.isNotBlank() }
        }
        // ⚠ AND NOTHING IS PRINTED WHEN NOTHING IS KNOWN. The catalog default
        // used to sit at the end of that chain, so opening a session flashed
        // "medium" for the first frames and then snapped to the truth once the
        // transcript parsed — an invented value shown as fact. Same law the
        // model chip already follows: show the fact or show nothing. The listing
        // now carries the session's own effort, so "nothing" is rare and brief
        // rather than wrong. Topbar sub-label mirrors the CLI FOOTER, which
        // prints the raw effort token («48s · xhigh») — never a capitalized
        // invention. The dropdown still uses the PROBED menu names; this label
        // is footer-authentic raw.
        return effort
    }
}
