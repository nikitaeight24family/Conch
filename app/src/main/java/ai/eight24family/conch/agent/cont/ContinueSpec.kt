package ai.eight24family.conch.agent.cont

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
import ai.eight24family.conch.data.prefs.AgentApprovalMode

/**
 * Per-CLI spec for **Continue CLI** (`cn` binary, npm `@continuedev/cli`).
 *
 * Mined from the 1.5.47 package — which ships its full TypeScript `src/` — with
 * live turns against a local mock provider, 2026-08-28; write-up in
 * `docs/cli-research-2026-08-top5.md`.
 *
 * **Headless invocation shape** we build:
 * ```
 * CONTINUE_GLOBAL_DIR="$HOME/.conch/cn/<chat>" CONTINUE_CLI_ENABLE_TELEMETRY=0 \
 *   cn --resume [--auto|--readonly [--exclude Bash]] --format json -p "$PROMPT" 2>&1
 * ```
 *
 * **Why the per-chat `CONTINUE_GLOBAL_DIR`.** `--resume` takes no id: it
 * reopens the globally newest session by mtime, so on a shared home directory
 * it would silently attach this chat to whatever ran last — measured, a
 * session from one project resumed from another. Giving each chat its own
 * config dir makes "newest" mean "this chat's only session", which turns a
 * silent mix-up into a deterministic resume. It also isolates each chat's
 * history, which is what the session list here walks.
 *
 * **It is the best-behaved citizen of the batch**, and that is worth stating:
 * Hub/cloud sessions were REMOVED from the codebase (`login()` throws "Hub
 * authentication has been removed"), telemetry is opt-in, auto-update never
 * runs headless, and everything stays in files on the user's own machine.
 */
object ContinueSpec : AgentCliSpec {

    override val agent = Agent.CONTINUE
    override val displayName = "Continue CLI"
    override val cliCommand = "cn"
    override val npmPackage = "@continuedev/cli"
    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_continue

    override val supportsSubagents = false
    override val supportsCustomSlashCommands = false
    override val supportsResume = true

    /** Not a CLI flag, but ours in effect: the per-chat config dir plus
     *  `CONTINUE_CLI_TEST_SESSION_ID` fixes the id we will find afterwards. */
    override val supportsPreSetSessionId = true

    /** `--readonly --exclude Bash` genuinely removes the shell and the write
     *  tools from the model's reach — a real read-only mode, not a prompt. */
    override val supportsPlanMode = true

    /**
     * ⚠ Continue never ASKS in headless mode — its own source pushes
     * `{tool:"Bash",permission:"allow"}` and `{tool:"*",permission:"allow"}`
     * when headless, and a plain `cn -p` really did run a `Bash` call with no
     * confirmation. What the modes here change is which tools EXIST for the
     * model, which is a real restriction — so the caveat says exactly that
     * rather than implying the shield can interpose.
     */
    override val approvalsCaveat: String =
        "never asks for approval — these modes remove tools from its reach instead of prompting"

    /** Continue's own look: a three-cell braille density "breathing" spinner
     *  (not a rotation), and no status vocabulary at all. */
    override val spinnerGlyphs: List<String> = listOf("⠂", "⠶", "⣿", "⠶")

    /** Reads `AGENTS.md` (plus `CLAUDE.md`/`CODEX.md` and `.continue/rules`). */
    override val memoryFilename = "AGENTS.md"
    override val memoryGlobalPath = "\$HOME/.continue/AGENTS.md"
    override val memoryGlobalDisplay = "~/.continue/AGENTS.md"

    /** Per-chat config dir — see the class doc for why `--resume` needs it. */
    private fun homeFor(key: String?): String =
        if (key.isNullOrBlank()) "\$HOME/.continue"
        else "\$HOME/.conch/cn/" + key.replace(Regex("[^A-Za-z0-9._-]"), "-")

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        val modelArg = input.model?.takeIf { it.isNotBlank() }
            ?.let { " --model ${shellEscape(it)}" } ?: ""
        // PLAN strips the shell as well as the writers — `--readonly` ALONE is
        // not the safe option people assume: it keeps Bash, which headless
        // auto-runs. SAFE is the CLI's own default reach (read + shell, no
        // writes), AUTO/YOLO add Write and Edit via `--auto`.
        val approvalArg = when (input.approvalMode) {
            AgentApprovalMode.PLAN -> " --readonly --exclude Bash"
            AgentApprovalMode.SAFE -> " --readonly"
            AgentApprovalMode.AUTO -> " --auto"
            AgentApprovalMode.YOLO -> " --auto"
        }
        val key = input.resumeId ?: input.preGeneratedSessionId
        val home = homeFor(key)
        // `--resume` with nothing to resume is a clean no-op (exit 0, empty
        // stderr), so ONE command serves both the first turn and every one
        // after it — no branch, nothing to get out of step.
        val idPin = input.preGeneratedSessionId?.takeIf { input.resumeId == null }
            ?.let { " CONTINUE_CLI_TEST_SESSION_ID=${shellEscape(it)}" } ?: ""
        // ⚠ Always pass a prompt: `cn -p` with no argument hangs waiting for
        // input (measured >25 s), while `cn -p "…"` never reads stdin at all.
        return "CONTINUE_GLOBAL_DIR=${shellEscape(home)}$idPin CONTINUE_CLI_ENABLE_TELEMETRY=0 " +
            "cn --resume --format json" + approvalArg + modelArg +
            " -p $escapedText 2>&1"
    }

    override fun parseStreamLine(line: String): List<AgentMessage> =
        ContinueMessageParser.parse(line)

    /**
     * One plain-JSON file per session under `<config>/sessions/<id>.json`.
     * Both the default home and the per-chat homes this spec creates are
     * walked, so a session started before the isolation scheme (or by the
     * user's own terminal) still shows up.
     */
    override val listSessionsScript: String? = """
for f in ~/.continue/sessions/*.json ~/.conch/cn/*/sessions/*.json; do
  [ -f "${'$'}f" ] || continue
  case "${'$'}{f##*/}" in sessions.json) continue;; esac
  id="${'$'}{f##*/}"; id="${'$'}{id%.json}"
  mtime=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || stat -f %m "${'$'}f" 2>/dev/null)
  size=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || stat -f %z "${'$'}f" 2>/dev/null)
  # A session file with no history is a launch artefact, not a chat.
  grep -q '"history"' "${'$'}f" 2>/dev/null || continue
  # The model is stamped on each assistant usage block.
  model=${'$'}(grep -oE '"model":"[^"]+"' "${'$'}f" 2>/dev/null | tail -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  title=${'$'}(head -c 4096 "${'$'}f" 2>/dev/null | grep -oE '"title":"([^"\\]|\\.)*"' | head -1 | sed -E 's/^"title":"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
  case "${'$'}title" in "Untitled Session") title="";; esac
  # First user turn — `editorState` carries it as plain text next to the
  # message, which is cheaper to pull than walking the message objects.
  preview=${'$'}(head -c 65536 "${'$'}f" 2>/dev/null | grep -oE '"editorState":"([^"\\]|\\.)*"' | head -1 | cut -b 1-700)
  if [ -n "${'$'}title" ]; then preview=${'$'}(printf '%s\037%s' "${'$'}title" "${'$'}preview"); fi
  printf '%s\t%s\t%s\t%s\t\t%s\t%s\n' "${'$'}id" "${'$'}mtime" "${'$'}f" "${'$'}model" "${'$'}size" "${'$'}preview"
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
        val m = Regex("\"editorState\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(body) ?: return ""
        return m.groupValues[1]
            .replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
            .replace("\\\"", "\"").replace("\\\\", "\\")
            .replace(Regex("\\s+"), " ").trim().take(140)
    }

    override val statusProbeLines: String = """
echo "continue_inst=${'$'}(command -v cn >/dev/null 2>&1 && echo y || echo n)"
echo "continue_ver=${'$'}(conch_ver continue cn)"
echo "continue_latest=${'$'}(conch_latest continue @continuedev/cli)"
CM=""
# No vendor account exists any more — Hub auth was REMOVED from the CLI, and
# `cn login` throws. Readiness means: a config that names a model, or a
# provider key in the environment. Presence only, never the value.
if [ -s ~/.continue/config.yaml ] || [ -s ~/.continue/config.json ]; then CM="${'$'}CM config"; fi
for v in ANTHROPIC_API_KEY OPENAI_API_KEY GEMINI_API_KEY MISTRAL_API_KEY OPENROUTER_API_KEY; do
  eval "val=\${'$'}${'$'}v"
  if [ -n "${'$'}val" ]; then case " ${'$'}CM " in *" api "*) ;; *) CM="${'$'}CM api";; esac; fi
done
echo "continue_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
case " ${'$'}CM " in
  *" config "*) echo "continue_active=config";;
  *" api "*) echo "continue_active=api";;
  *) echo "continue_active=";;
esac
""".trimIndent()

    /**
     * Models come from the user's own `config.yaml` (Continue is a harness, not
     * a provider), so the catalog is read from there rather than invented.
     */
    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> {
        val raw = exec.exec(
            "bash -lc " + shellEscape("cat \$HOME/.continue/config.yaml 2>/dev/null || true"),
        ).orEmpty()
        val out = linkedMapOf<String, String>()
        // `models:` entries carry `model: <name>` lines; take them in order.
        Regex("(?m)^\\s*-?\\s*model:\\s*\"?([A-Za-z0-9._:/-]+)\"?").findAll(raw).forEach {
            val slug = it.groupValues[1]
            if (slug.isNotBlank()) out[slug] = slug
        }
        return out
    }

    override val customCommandsScript: String? = null
    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> = emptyList()

    /** Each session file records the workspace it ran in. */
    override fun cwdBackfillScript(resumeId: String): String {
        val q = shellEscape(resumeId)
        return "f=\$(ls ~/.continue/sessions/$q'.json' ~/.conch/cn/*/sessions/$q'.json' 2>/dev/null | head -1); " +
            "[ -n \"\$f\" ] && grep -m1 -o '\"workspaceDirectory\":\"[^\"]*\"' \"\$f\" | " +
            "sed -E 's/^\"workspaceDirectory\"/\"cwd\"/'"
    }

    override val topbarUi: AgentTopbarUi = ContinueTopbarUi

    override val disableApprovalsPrompt: String = """
        Note: in non-interactive mode you already run tools without asking, so there is no approval prompt to switch off.

        Reply with exactly this, then continue your previous task:

        "Continue CLI doesn't prompt for approvals in headless mode — it decides which tools exist up front. In this app, the shield's modes change that reach: Plan removes the shell and the writers, Auto adds file editing."
    """.trimIndent()
}

private object ContinueTopbarUi : AgentTopbarUi {
    override fun displayLabel(state: TopbarModelState): String? =
        state.selectedModel?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialModel?.takeIf { it.isNotBlank() }
            ?: state.observedModel?.takeIf { it.isNotBlank() }

    override fun isMenuEnabled(state: TopbarModelState): Boolean =
        !state.modelsProbing && state.availableModels.isNotEmpty()

    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> =
        state.availableModels.map { (slug, label) -> ModelMenuItem(display = label, storedValue = slug) }
}
