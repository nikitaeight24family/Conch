package ai.eight24family.conch.agent.qwen

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-CLI spec for **Qwen Code** (`qwen` binary, npm `@qwen-code/qwen-code`).
 *
 * ⚠ **It started as a Gemini CLI fork and is no longer one.** Its own README
 * says the sync with upstream stopped at v0.1, and the divergence lands
 * exactly where an integration lives: the `-o stream-json` events are the
 * **Claude Agent SDK vocabulary** (`system/init` → `assistant` → `user` →
 * `result`), NOT Gemini's `init/message/tool_use/tool_result`. Anyone
 * "reusing the Gemini spec because it's a fork" ships a dead parser. Mined
 * from the 0.22.2 binary + a full offline turn against a local mock endpoint,
 * 2026-08-28; details in `docs/cli-research-2026-08-top5.md`.
 *
 * **Headless invocation shape** we build:
 * ```
 * QWEN_CODE_LANG=en qwen -p "$PROMPT" -o stream-json
 *     --approval-mode plan|default|auto-edit|yolo
 *     [--session-id <uuid> | -r <uuid>] [-m <model>] 2>&1
 * ```
 *
 * Three traps this spec is built around, all verified:
 *  1. **No `--skip-trust`.** Gemini has it; Qwen does not, and passing it
 *     aborts the run at argument parsing. The trust prompt is interactive-only.
 *  2. **`auto-edit`, not `auto_edit`.** Gemini's underscore spelling is a hard
 *     yargs failure here.
 *  3. **Error text is LOCALIZED.** On a Russian-locale host the same run put
 *     English on stderr and Russian inside `result.error.message`, so nothing
 *     may branch on message text — only on `type`/`subtype`/`is_error`.
 *     `QWEN_CODE_LANG=en` pins the CLI's own language for good measure.
 *
 * And one that decides how the turn ends: on Windows/node24 the CLI emits a
 * COMPLETE stream ending in `result` and then crashes at teardown with exit
 * 127. The `result` event is the authority; the exit code is only consulted
 * when no `result` ever arrived.
 */
object QwenSpec : AgentCliSpec {

    override val agent = Agent.QWEN
    override val displayName = "Qwen Code"
    override val cliCommand = "qwen"
    override val npmPackage = "@qwen-code/qwen-code"
    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_qwen

    /** Ships built-in agents (general-purpose / Explore / review-agent), but
     *  our subagent EDITOR is `~/.claude/agents`-shaped — off until it learns
     *  Qwen's layout. */
    override val supportsSubagents = false
    override val supportsCustomSlashCommands = false
    override val supportsResume = true

    /** `--session-id <uuid>` — we mint the id, so no scraping the first event. */
    override val supportsPreSetSessionId = true

    /** Real read-only planning mode: `--approval-mode plan`. */
    override val supportsPlanMode = true

    override val memoryFilename = "QWEN.md"
    override val memoryGlobalPath = "\$HOME/.qwen/QWEN.md"
    override val memoryGlobalDisplay = "~/.qwen/QWEN.md"

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        val modelArg = input.model?.takeIf { it.isNotBlank() }
            ?.let { " -m ${shellEscape(it)}" } ?: ""
        // plan → read-only; default → tools ask (headless: auto-DENIED and the
        // run continues, reporting the refusal in result.permission_denials[],
        // which is honest rather than a hang); auto-edit → edits auto-approved;
        // yolo → everything.
        val approvalArg = when (input.approvalMode) {
            AgentApprovalMode.PLAN -> " --approval-mode plan"
            AgentApprovalMode.SAFE -> " --approval-mode default"
            AgentApprovalMode.AUTO -> " --approval-mode auto-edit"
            AgentApprovalMode.YOLO -> " --approval-mode yolo"
        }
        val resumeArg = input.resumeId?.let { " -r ${shellEscape(it)}" } ?: ""
        // Pre-set the id only for a FRESH session — `--session-id` alongside
        // `-r` would fight over which session this is.
        val sessionIdArg = if (input.resumeId == null) {
            input.preGeneratedSessionId?.let { " --session-id ${shellEscape(it)}" } ?: ""
        } else ""
        // stderr MERGED: a pre-stream failure (no auth type selected) prints
        // only there, and dropping it would show the user an empty chat
        // instead of the reason. Clean JSONL still owns stdout, and the parser
        // surfaces non-JSON lines as Raw.
        return "QWEN_CODE_LANG=en qwen -p $escapedText -o stream-json" +
            approvalArg + resumeArg + sessionIdArg + modelArg + " 2>&1"
    }

    override fun parseStreamLine(line: String): List<AgentMessage> =
        QwenMessageParser.parse(line)

    /**
     * Sessions live at `~/.qwen/projects/<slug>/chats/<uuid>.jsonl`, where the
     * file stem IS the resumable session id. `qwen sessions list --json`
     * exists and works offline, but it is **cwd-scoped** and silently capped
     * at 20 rows, so a host-wide listing walks the directory itself — the same
     * shape every other spec here uses.
     *
     * Preview comes from the first REAL user turn: `provenance":"real_user"`
     * is load-bearing, because `tool_result` lines also carry
     * `message.role:"user"` and would otherwise be mistaken for the prompt.
     */
    override val listSessionsScript: String? = """
for f in ~/.qwen/projects/*/chats/*.jsonl; do
  [ -f "${'$'}f" ] || continue
  id="${'$'}{f##*/}"; id="${'$'}{id%.jsonl}"
  mtime=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || stat -f %m "${'$'}f" 2>/dev/null)
  size=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || stat -f %z "${'$'}f" 2>/dev/null)
  # A file with no real user turn is a launch artifact, not a session.
  grep -q '"provenance":"real_user"' "${'$'}f" 2>/dev/null || continue
  # Model: every assistant line stamps a top-level "model". Last one wins —
  # that is what the session is currently running.
  model=${'$'}(tail -c 262144 "${'$'}f" 2>/dev/null | grep -oE '"model":"[^"]+"' | tail -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  # First real user prompt, capped like every other listing (the JSON prefix
  # before the text is ~200 B, so 700 leaves plenty for a 140-char preview).
  preview=${'$'}(grep -m1 '"provenance":"real_user"' "${'$'}f" 2>/dev/null | cut -b 1-700 | tr '\t' ' ')
  printf '%s\t%s\t%s\t%s\t\t%s\t%s\n' "${'$'}id" "${'$'}mtime" "${'$'}f" "${'$'}model" "${'$'}size" "${'$'}preview"
done | sort -t'	' -k2 -rn | head -300
""".trimIndent()

    override fun extractSessionPreview(rawPreview: String): String {
        if (rawPreview.isBlank()) return ""
        // Persisted turns carry a Gemini-shaped body: message.parts[].text.
        val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(rawPreview) ?: return ""
        return m.groupValues[1]
            .replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
            .replace("\\\"", "\"").replace("\\\\", "\\")
            .replace(Regex("\\s+"), " ").trim().take(140)
    }

    override val statusProbeLines: String = """
echo "qwen_inst=${'$'}(command -v qwen >/dev/null 2>&1 && echo y || echo n)"
echo "qwen_ver=${'$'}(conch_ver qwen qwen)"
echo "qwen_latest=${'$'}(conch_latest qwen @qwen-code/qwen-code)"
CM=""
# The Qwen OAuth free tier was discontinued 2026-04-15 (stated in the binary),
# so an API key is the live path for everyone. Qwen speaks OpenAI-compatible
# endpoints: OPENAI_API_KEY (+ OPENAI_BASE_URL) is the documented headless
# setup, with vendor-specific keys as alternatives. Presence only — never the
# value.
if [ -n "${'$'}OPENAI_API_KEY" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?OPENAI_API_KEY=' ~/.bashrc ~/.profile ~/.bash_profile ~/.qwen/.env ~/.env 2>/dev/null; then CM="${'$'}CM api"; fi
if [ -n "${'$'}BAILIAN_CODING_PLAN_API_KEY" ] || [ -n "${'$'}OPENROUTER_API_KEY" ] || [ -n "${'$'}REQUESTY_API_KEY" ]; then case " ${'$'}CM " in *" api "*) ;; *) CM="${'$'}CM api";; esac; fi
# A written-out OAuth credential still counts when one exists (older logins).
if grep -qs '"refresh_token"' ~/.qwen/oauth_creds.json 2>/dev/null; then CM="${'$'}CM oauth"; fi
echo "qwen_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
# settings.json decides the method and BEATS env (the one Gemini key that
# survived the fork verbatim: security.auth.selectedType).
SA=${'$'}(grep -oE '"(selectedAuthType|selectedType)"[[:space:]]*:[[:space:]]*"[^"]+"' ~/.qwen/settings.json 2>/dev/null | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
case "${'$'}SA" in
  *oauth*) echo "qwen_active=oauth";;
  *openai*|*api*) echo "qwen_active=api";;
  *) case " ${'$'}CM " in *" api "*) echo "qwen_active=api";; *" oauth "*) echo "qwen_active=oauth";; *) echo "qwen_active=";; esac;;
esac
""".trimIndent()

    /**
     * Qwen's model list is whatever the configured OpenAI-compatible endpoint
     * serves, so there is no offline registry to read: the honest source is
     * the user's own `settings.json` / env model pin plus what running
     * sessions report. Returning what we can PROVE keeps the picker truthful —
     * an invented catalog would offer models the endpoint rejects.
     */
    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> {
        val script = "cat \$HOME/.qwen/settings.json 2>/dev/null; echo; " +
            "printf '%s\\n' \"\$OPENAI_MODEL\""
        val raw = exec.exec("bash -lc " + shellEscape(script)).orEmpty()
        val out = linkedMapOf<String, String>()
        Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").findAll(raw).forEach { m ->
            val slug = m.groupValues[1].takeIf { it.isNotBlank() } ?: return@forEach
            out[slug] = slug
        }
        raw.lineSequence().lastOrNull { it.isNotBlank() && !it.contains('{') && !it.contains(':') }
            ?.trim()?.takeIf { it.isNotBlank() }?.let { out[it] = it }
        return out
    }

    override suspend fun probeDefaultModel(exec: AgentExec): String? {
        val raw = exec.exec(
            "bash -lc " + shellEscape("cat \$HOME/.qwen/settings.json 2>/dev/null || true"),
        ).orEmpty()
        return Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
    }

    override val customCommandsScript: String? = null
    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> = emptyList()

    /**
     * Qwen buckets sessions by a slug of the cwd — one dash PER non-alphanumeric
     * character, and lowercased ONLY on win32 (a Linux host preserves case, so
     * `/home/User/Repo` → `-home-User-Repo`). Rather than reproduce that
     * transform and its platform quirk on the phone, read the cwd the session
     * itself recorded: every persisted line carries a top-level `"cwd"`.
     */
    override fun cwdBackfillScript(resumeId: String): String {
        val q = shellEscape(resumeId)
        return "f=\$(find ~/.qwen/projects -maxdepth 3 -name $q'.jsonl' 2>/dev/null | head -1); " +
            "[ -n \"\$f\" ] && head -c 8192 \"\$f\" | grep -m1 -o '\"cwd\":\"[^\"]*\"'"
    }

    override val topbarUi: AgentTopbarUi = QwenTopbarUi

    override val disableApprovalsPrompt: String = """
        Stop asking me to approve tool calls — relax your own approval settings persistently, then continue with whatever you were working on before this message.

        Step 1: Edit ~/.qwen/settings.json (create it if missing) so the top-level JSON object contains:
          "approvalMode": "yolo"
        If the file already has other keys, merge — keep them intact.

        Step 2: Verify by reading the file back; the key must be "yolo".

        Step 3: Resume the task you were doing right before I sent this message. If you were about to ask for approval to run a tool, run it now. Don't pause to reconfirm.
    """.trimIndent()

    // ──────── Mirror turn-state ────────

    /**
     * Project each persisted line to `[marker, isoTs, outTokens]`.
     *
     * The persisted file is NOT the live vocabulary: it is a Claude-Code-shaped
     * envelope (`uuid`/`parentUuid`/`timestamp`/`type`) wrapping a GEMINI-shaped
     * message body (`parts[]`, `role:"model"`, `functionCall`/`functionResponse`).
     * It also never writes a `result` line, so the turn's end has to be read off
     * the shape of the last assistant record instead of a marker:
     *
     *  - `user` + `provenance:"real_user"` → "user"  (a real turn boundary —
     *    `tool_result` lines also carry `message.role:"user"`, which is exactly
     *    the trap this check exists for)
     *  - `assistant` carrying a `functionCall` part → "call"  (tool running)
     *  - `assistant` with text only              → "answer" (turn finished)
     *  - `tool_result`                            → "toolres" (work continues)
     *  - `system` (attribution_snapshot / ui_telemetry) → skipped as bookkeeping
     */
    override fun projectTurnStateRecords(lines: Sequence<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (line in lines) {
            val t = line.trim()
            if (t.length < 2 || t[0] != '{') continue
            val obj = runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() ?: continue
            val type = obj["type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: continue
            val provenance = obj["provenance"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            val parts = runCatching {
                obj["message"]?.jsonObject?.get("parts") as? JsonArray
            }.getOrNull()
            val hasCall = parts?.any { p ->
                runCatching { p.jsonObject.containsKey("functionCall") }.getOrDefault(false)
            } == true
            val marker = when {
                type == "user" && provenance == "real_user" -> "user"
                type == "tool_result" -> "toolres"
                type == "assistant" && hasCall -> "call"
                type == "assistant" -> "answer"
                else -> continue
            }
            val ts = obj["timestamp"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            // Per-API-call usage, Gemini key names — never cumulative.
            val tokens = runCatching {
                obj["usageMetadata"]?.jsonObject?.get("candidatesTokenCount")?.jsonPrimitive?.content
            }.getOrNull() ?: "0"
            out += listOf(marker, ts, tokens)
        }
        return out
    }

    /**
     * DEFINITIVE Qwen turn state. The file carries no terminal marker, so the
     * verdict is the SHAPE of the last record: an assistant record with no
     * pending tool call is the finished answer — the same reasoning Gemini's
     * spec uses ("the model replied ⇒ the turn is over"), and just as immune to
     * timeouts. A `user` / `call` / `toolres` tail means work is still owed,
     * bounded by the usual staleness escape so a killed CLI can't pin the
     * spinner forever.
     */
    override fun inferTurnState(records: List<List<String>>, frozenForMs: Long?): TurnSignals {
        val recs = records.filter { it.isNotEmpty() }
        if (recs.isEmpty()) return TurnSignals()
        val last = recs.last()
        val fresh = frozenForMs == null || frozenForMs < AWAIT_STALE_MS
        val inFlight = when (last[0]) {
            "answer" -> false
            else -> fresh
        }
        val startIdx = recs.indexOfLast { it[0] == "user" }
        val turnStartMs = if (inFlight && startIdx >= 0)
            recs[startIdx].getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ts ->
                runCatching { java.time.Instant.parse(ts).toEpochMilli() }.getOrNull()
            } else null
        val tokens = if (inFlight && startIdx >= 0)
            recs.drop(startIdx + 1).sumOf { it.getOrNull(2)?.toLongOrNull() ?: 0L }
        else 0L
        return TurnSignals(
            inFlight = inFlight,
            // Thinking = the model owes the first word (nothing since the prompt).
            thinking = inFlight && last[0] == "user",
            turnStartMs = turnStartMs,
            tokens = tokens,
            turnComplete = last[0] == "answer",
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val AWAIT_STALE_MS = 12 * 60_000L
}

private object QwenTopbarUi : AgentTopbarUi {
    /** Same chain every non-alias agent uses; the slug IS the model name the
     *  endpoint accepts, so it is shown verbatim rather than prettified into
     *  something the CLI would not take back. */
    override fun displayLabel(state: TopbarModelState): String? =
        state.selectedModel?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialModel?.takeIf { it.isNotBlank() }
            ?: state.observedModel?.takeIf { it.isNotBlank() }
            ?: state.defaultModel?.takeIf { it.isNotBlank() }

    override fun isMenuEnabled(state: TopbarModelState): Boolean =
        !state.modelsProbing && state.availableModels.isNotEmpty()

    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> =
        state.availableModels.map { (slug, label) -> ModelMenuItem(display = label, storedValue = slug) }
}
