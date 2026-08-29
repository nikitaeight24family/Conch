package ai.eight24family.conch.agent.crush

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
 * Per-CLI spec for **Crush** (Charm's Go agent, npm `@charmland/crush`).
 *
 * Mined from the 0.91.2 binary against a local mock provider, 2026-08-28; full
 * write-up in `docs/cli-research-2026-08-top5.md`.
 *
 * **Headless invocation shape** we build:
 * ```
 * CRUSH_DISABLE_PROVIDER_AUTO_UPDATE=1 CRUSH_DISABLE_METRICS=1 DO_NOT_TRACK=1 \
 *   crush run -q [-y] [-m provider/model] "$PROMPT" < /dev/null
 * ```
 *
 * ⛔ **`< /dev/null` IS NOT OPTIONAL.** `crush run` reads stdin to EOF before
 * it does anything — even with the prompt in argv. On an SSH exec channel,
 * whose stdin stays open, the turn never starts: measured 0 bytes out until
 * the process was killed at 25 s, versus 963 ms for the identical command with
 * stdin closed. Every launch this spec builds ends with the redirect.
 *
 * ⛔ **And never invoke `crush` with no arguments** — it opens the full-screen
 * TUI, blocks forever, and sprays terminal-capability probes (alt-screen,
 * mouse tracking, Kitty graphics queries) onto stdout.
 *
 * **What this CLI does NOT give us, stated plainly:** there is no JSON on
 * `run`'s stdout — it prints the assistant's final message as raw markdown
 * (verified free of ANSI). So tool calls are invisible live, and the
 * structured view comes afterwards from `crush session show --json`, which is
 * also what [sessionReadCommand] uses to replay a chat. History lives in a
 * per-project SQLite database (`<project>/.crush/crush.db`), not in files.
 */
object CrushSpec : AgentCliSpec {

    override val agent = Agent.CRUSH
    override val displayName = "Crush"
    override val cliCommand = "crush"
    override val npmPackage = "@charmland/crush"
    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_crush

    override val supportsSubagents = false
    override val supportsCustomSlashCommands = false
    override val supportsResume = true

    /** ⛔ Verified refusal: `--session conch-abc-123` answers "Session not
     *  found". Crush mints its own dual ids (a uuid and a 16-hex short hash). */
    override val supportsPreSetSessionId = false

    /** No read-only planning mode exists in the CLI. */
    override val supportsPlanMode = false

    /** Crush's look is half-blocks and a gradient; this is a fill ramp in that
     *  spirit — its own family, not a copy of its letterforms. It deliberately
     *  does NOT rotate verbs: the phrases in its binary ("Brrrrr…") are
     *  textarea placeholders, not status words, and using them would be
     *  inventing a behaviour the CLI does not have. */
    override val spinnerGlyphs: List<String> = listOf("░", "▒", "▓", "█", "▓", "▒")

    /**
     * ⚠ **Crush does not enforce approvals in headless mode at all.** `crush
     * run` auto-approves every tool with no flag whatsoever — a `bash` call
     * executed unprompted in testing. `--yolo` therefore changes nothing about
     * what a turn is allowed to do, and the ONLY real guard is a `permissions`
     * deny list in the user's own config, which removes the tool from the
     * model's list entirely (26 tools → 25, verified).
     *
     * The app must not present a mode it cannot deliver, so this flag makes
     * the shield say so instead of implying a protection that isn't there.
     */
    override val approvalsCaveat =
        "runs every tool unprompted — these modes cannot restrict it; only a deny list in crush's own config can"

    /** Charm's own convention, alongside the cross-vendor `AGENTS.md`. */
    override val memoryFilename = "CRUSH.md"
    override val memoryGlobalPath = "\$HOME/.config/crush/CRUSH.md"
    override val memoryGlobalDisplay = "~/.config/crush/CRUSH.md"

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        val modelArg = input.model?.takeIf { it.isNotBlank() }
            ?.let { " -m ${shellEscape(it)}" } ?: ""
        // ⛔ NO `-y` HERE. `--yolo` is a ROOT flag: `crush run -y …` is
        // rejected outright with "Unknown shorthand flag: 'y' in -y" (measured
        // on 0.91.2). It would also be meaningless — headless `run` approves
        // every tool anyway, which is what [approvalsEnforced] tells the UI.
        // So no mode adds a flag, and the modes differ only in what the shield
        // is honest about.
        val resumeArg = input.resumeId?.let { " --session ${shellEscape(it)}" } ?: ""
        val cwdArg = input.cwdSnapshot?.takeIf { it.isNotBlank() }
            ?.let { " --cwd ${shellEscape(it)}" } ?: ""
        // -q hides the spinner, leaving the assistant's text alone on stdout.
        // The env trio stops the provider-catalog fetch and the PostHog
        // telemetry ping, both of which only add latency on a server.
        return "CRUSH_DISABLE_PROVIDER_AUTO_UPDATE=1 CRUSH_DISABLE_METRICS=1 DO_NOT_TRACK=1 " +
            "crush run -q" + modelArg + resumeArg + cwdArg +
            " $escapedText < /dev/null 2>&1"
    }

    override fun parseStreamLine(line: String): List<AgentMessage> =
        CrushMessageParser.parse(line)

    /**
     * Sessions are rows in a per-PROJECT SQLite database, so the listing asks
     * the CLI: `crush sessions list --json` inside each project Crush knows
     * about. `path` carries a `crush://<id>@<dir>` MARKER — there is no file
     * to read or tail, and the directory has to travel with the id because the
     * database is the project's, not the user's.
     */
    override val listSessionsScript: String? = """
# Crush keeps ONE database per project, so the projects come first:
# `crush projects --json` → {"projects":[{"path":…,"data_dir":…,…}]}.
# (`crush dirs` is NOT this — it prints the config and data dirs.)
# Every subcommand takes --cwd, so no `cd` is needed anywhere here.
crush projects --json 2>/dev/null | tr '{' '\n' | while IFS= read -r p; do
  case "${'$'}p" in *'"path"'*) ;; *) continue;; esac
  d=${'$'}(printf '%s' "${'$'}p" | grep -oE '"path":"([^"\\]|\\.)*"' | head -1 | sed -E 's/^"path":"//; s/"${'$'}//' | sed 's/\\\\/\\/g')
  [ -n "${'$'}d" ] || continue
  [ -d "${'$'}d" ] || continue
  crush session list --json --cwd "${'$'}d" 2>/dev/null | tr '{' '\n' | while IFS= read -r o; do
    case "${'$'}o" in *'"id"'*) ;; *) continue;; esac
    id=${'$'}(printf '%s' "${'$'}o" | grep -oE '"id":"[^"]*"' | head -1 | sed -E 's/.*:"([^"]*)"/\1/')
    [ -n "${'$'}id" ] || continue
    title=${'$'}(printf '%s' "${'$'}o" | grep -oE '"title":"([^"\\]|\\.)*"' | head -1 | sed -E 's/^"title":"//; s/"${'$'}//' | tr '\011\036\037\012' '    ')
    # updated_at is epoch SECONDS — the DDL comment says milliseconds, but the
    # triggers write strftime('%s','now') and the values agree with seconds.
    upd=${'$'}(printf '%s' "${'$'}o" | grep -oE '"updated_at":[0-9]+' | head -1 | sed -E 's/.*:([0-9]+)/\1/')
    [ -n "${'$'}upd" ] || upd=${'$'}(stat -c %Y "${'$'}d/.crush/crush.db" 2>/dev/null || echo 0)
    printf '%s\t%s\t%s\t\t\t%s\t%s\n' "${'$'}id" "${'$'}upd" "crush://${'$'}id@${'$'}d" "0" "${'$'}title"
  done
done | sort -t'	' -k2 -rn | head -300
""".trimIndent()

    /** The listing has a generated title and no first-message text; the title
     *  is the row's accent header and the subtitle stays empty rather than
     *  repeating it. */
    override fun extractSessionTitle(rawPreview: String): String? =
        rawPreview.trim().ifBlank { null }?.take(140)

    override fun extractSessionPreview(rawPreview: String): String = ""

    /**
     * `crush://<id>@<dir>` → the structured transcript, read from the project's
     * own database. `--cwd` is load-bearing: the database is per-project, so
     * asking from anywhere else finds nothing.
     */
    override fun sessionReadCommand(path: String): String? {
        if (!path.startsWith(PATH_MARKER)) return null
        val body = path.removePrefix(PATH_MARKER)
        val id = body.substringBefore('@')
        val dir = body.substringAfter('@', "")
        if (id.isBlank()) return null
        val cwd = if (dir.isNotBlank()) " --cwd ${shellEscape(dir)}" else ""
        // Collapsed to one line for the same reason as opencode's export: the
        // caller feeds this to the parser line by line, and a pretty-printed
        // document would arrive as a shower of unparseable fragments.
        // Lossless — a raw newline cannot appear inside a JSON string.
        return "crush session show ${shellEscape(id)} --json$cwd 2>/dev/null | tr -d '\\r\\n'"
    }

    override fun deleteSessionCommand(sessionId: String, path: String): String {
        val dir = path.removePrefix(PATH_MARKER).substringAfter('@', "")
        val cwd = if (dir.isNotBlank()) " --cwd ${shellEscape(dir)}" else ""
        return "crush session delete ${shellEscape(sessionId)}$cwd 2>/dev/null"
    }

    override val statusProbeLines: String = """
echo "crush_inst=${'$'}(command -v crush >/dev/null 2>&1 && echo y || echo n)"
echo "crush_ver=${'$'}(conch_ver crush crush)"
echo "crush_latest=${'$'}(conch_latest crush @charmland/crush)"
CM=""
# Crush is provider-agnostic and configured by environment: any of the
# provider keys makes it runnable. Presence only — never the value.
for v in ANTHROPIC_API_KEY OPENAI_API_KEY GEMINI_API_KEY GROQ_API_KEY OPENROUTER_API_KEY XAI_API_KEY DEEPSEEK_API_KEY; do
  eval "val=\${'$'}${'$'}v"
  if [ -n "${'$'}val" ]; then CM="api"; break; fi
done
if [ -z "${'$'}CM" ] && grep -qsE '^[[:space:]]*(export[[:space:]]+)?(ANTHROPIC|OPENAI|GEMINI|GROQ|OPENROUTER|XAI|DEEPSEEK)_API_KEY=' ~/.bashrc ~/.profile ~/.bash_profile ~/.env 2>/dev/null; then CM="api"; fi
# Its own verdict, which also covers a key configured in crush's config file
# rather than the environment. The not-ready string is exact and stable:
# "No providers configured - please run 'crush' to set up a provider…"
if [ -z "${'$'}CM" ]; then
  CO=${'$'}(conch_timeout 20 crush run -q "conch probe" < /dev/null 2>&1 | head -c 200)
  case "${'$'}CO" in *"No providers configured"*) ;; *) [ -n "${'$'}CO" ] && CM="api";; esac
fi
echo "crush_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
case "${'$'}CM" in api) echo "crush_active=api";; *) echo "crush_active=";; esac
""".trimIndent()

    /** `crush models` prints `provider/model` lines — the CLI's own registry
     *  of what `-m` accepts, offline. It is a LONG list (1546 rows measured),
     *  which the picker handles as any other catalog. */
    override val catalogIsAuthoritative = true

    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> {
        val raw = exec.exec(
            "bash -lc " + shellEscape(
                ai.eight24family.conch.agent.RemoteEnv.PATH_PREAMBLE_INLINE +
                    "conch_timeout 25 crush models 2>/dev/null",
            ),
        ).orEmpty()
        val out = linkedMapOf<String, String>()
        for (line in raw.lineSequence()) {
            val slug = line.trim()
            if (!Regex("^[a-z0-9._-]+/[A-Za-z0-9._:-]+$").matches(slug)) continue
            out[slug] = slug
        }
        return out
    }

    override val customCommandsScript: String? = null
    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> = emptyList()

    /** The project directory travels inside the session marker, so the cwd is
     *  recovered from there rather than from a second round-trip. */
    override fun cwdBackfillScript(resumeId: String): String? = null

    override val topbarUi: AgentTopbarUi = CrushTopbarUi

    override val disableApprovalsPrompt: String = """
        Note: in non-interactive mode you already run tools without asking, so there is nothing to relax — and that is worth saying plainly rather than pretending otherwise.

        Reply with exactly this, then continue your previous task:

        "Crush runs tool calls without prompting in headless mode, so there is no approval to switch off. To RESTRICT it instead, add a permissions deny list to the project's crush config — that removes the tool from my available set entirely."
    """.trimIndent()

    internal const val PATH_MARKER = "crush://"
}

private object CrushTopbarUi : AgentTopbarUi {
    override fun displayLabel(state: TopbarModelState): String? =
        state.selectedModel?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialModel?.takeIf { it.isNotBlank() }
            ?: state.observedModel?.takeIf { it.isNotBlank() }

    override fun isMenuEnabled(state: TopbarModelState): Boolean =
        !state.modelsProbing && state.availableModels.isNotEmpty()

    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> =
        state.availableModels.map { (slug, label) -> ModelMenuItem(display = label, storedValue = slug) }
}
