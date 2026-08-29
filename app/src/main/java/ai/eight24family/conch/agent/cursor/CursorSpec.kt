package ai.eight24family.conch.agent.cursor

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
 * Per-CLI spec for **Cursor CLI** (Anysphere), binary `cursor-agent`.
 *
 * ⛔ **THERE IS NO NPM PACKAGE — and the name that looks like one is a trap.**
 * `npm i cursor-agent` installs an unrelated third-party library (a dead 2025
 * "task sequence creator", maintainer `zalab-inc`, no `bin` field, no
 * executable in the tarball) that predates Cursor's real CLI by months.
 * `@cursor/cli`, `@cursor/agent` and the `@anysphere` scope are all 404; the real
 * package's own manifest is `{"name":"@anysphere/agent-cli-runtime",
 * "private":true}` — Anysphere deliberately does not publish. Hence
 * [npmPackage] is null and [officialInstallCommand] carries the real channel.
 * Mined from the 2026.08.25-3e8eec8 binary, 2026-08-28; see
 * `docs/cli-research-2026-08-top5.md`.
 *
 * **Binary name.** The installer unconditionally creates BOTH `~/.local/bin/agent`
 * and `~/.local/bin/cursor-agent` as symlinks to the same file, and the
 * executable inside the package is itself named `cursor-agent`. We drive (and
 * pgrep) `cursor-agent`: a bare `agent` on a user's PATH is generic enough to
 * collide with unrelated tools, and the liveness probe greps that name.
 *
 * **Headless invocation shape** we build:
 * ```
 * cursor-agent -p "$PROMPT" --output-format stream-json
 *     [--mode plan | --auto-review | --force]
 *     [--resume=<id> | --new-session-id <uuid>] [--model <m>] 2>&1
 * ```
 *
 * ⚠ **Never call `cursor-agent ls`, `resume` without a value, or the bare
 * binary over SSH.** Those are Ink TUIs: with no TTY they print "Raw mode is
 * not supported" and then HANG until killed. Session listing therefore reads
 * the transcript directory directly, and resume always passes an explicit id.
 */
object CursorSpec : AgentCliSpec {

    override val agent = Agent.CURSOR
    override val displayName = "Cursor CLI"
    override val cliCommand = "cursor-agent"

    /** Not on npm — see the class doc. Guessing a name here would point the
     *  installer at the squatted package. */
    override val npmPackage: String? = null
    override val guardHarnessId = "cursor"

    override val officialInstallCommand = "curl https://cursor.com/install -fsS | bash"

    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_cursor

    override val supportsSubagents = false
    override val supportsCustomSlashCommands = false
    override val supportsResume = true

    /** `--new-session-id <uuid>` lets us mint the id before the first event. */
    override val supportsPreSetSessionId = true

    /** `--mode plan` is a real read-only planning mode (there is also
     *  `--mode ask`, a second read-only flavour we don't surface). */
    override val supportsPlanMode = true

    /**
     * `login` takes no flags at all — its ONE control is the env var named in
     * its own description. With `NO_OPEN_BROWSER=1` the CLI takes the branch
     * that PRINTS the link ("Open a browser and navigate to this link: …")
     * instead of trying to spawn one, then blocks polling until the flow
     * completes. That is exactly our relay-to-phone shape, and it cannot hang
     * on a browser: the spawn attempt is wrapped in an empty catch, and an SSH
     * session is detected and refused anyway.
     *
     * There is no device code here — one long URL, no short code to type — and
     * no callback port to curl back (unlike Codex/Gemini): the CLI polls the
     * result itself, so the creds poller closing the dialog is the whole flow.
     */
    override val oauthLoginCommand = "NO_OPEN_BROWSER=1 cursor-agent login"

    /**
     * Cursor reads the cross-vendor `AGENTS.md` natively (verified in the
     * bundle's rule loader, alongside the `.mdc` files under `.cursor/rules`,
     * `.cursorrules`, and — behind the third-party toggle — `CLAUDE.md`).
     * Sharing that
     * filename with the other AGENTS.md CLIs is correct: pointing Cursor at an
     * invented private file would make the memory editor write something the
     * CLI never reads. The GLOBAL path stays under its own config dir, so no
     * two agents can ever collide there.
     */
    override val memoryFilename = "AGENTS.md"
    override val memoryGlobalPath = "\$HOME/.cursor/AGENTS.md"
    override val memoryGlobalDisplay = "~/.cursor/AGENTS.md"

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        val modelArg = input.model?.takeIf { it.isNotBlank() }
            ?.let { " --model ${shellEscape(it)}" } ?: ""
        // All four modes are REAL flags here (resolver `{force,yolo,autoReview}`
        // in the bundle yields exactly four kinds), so nothing is collapsed:
        //   PLAN → --mode plan   : read-only, proposes, never edits
        //   SAFE → (no flag)     : allowlist. ⚠ Headless this AUTO-REJECTS every
        //                          tool ("User Rejected") rather than prompting —
        //                          effectively read-only, never a hang.
        //   AUTO → --auto-review : a server classifier auto-runs the safe calls
        //   YOLO → --force       : run everything
        // ⚠ --force and --auto-review together are a hard exit 1 ("pick one"),
        // which is why these are mutually exclusive branches and never merged.
        val approvalArg = when (input.approvalMode) {
            AgentApprovalMode.PLAN -> " --mode plan"
            AgentApprovalMode.SAFE -> ""
            AgentApprovalMode.AUTO -> " --auto-review"
            AgentApprovalMode.YOLO -> " --force"
        }
        // `--resume=<id>` with an EXPLICIT value only: a bare `--resume` opens
        // the interactive picker, which hangs without a TTY.
        val resumeArg = input.resumeId?.let { " --resume=${shellEscape(it)}" } ?: ""
        val sessionIdArg = if (input.resumeId == null) {
            input.preGeneratedSessionId?.let { " --new-session-id ${shellEscape(it)}" } ?: ""
        } else ""
        // stderr merged: auth failures print there and nowhere else
        // ("Authentication required. Please run 'agent login' first…"), and a
        // silent empty chat would be the alternative. stdout stays clean NDJSON.
        return "cursor-agent -p $escapedText --output-format stream-json" +
            approvalArg + resumeArg + sessionIdArg + modelArg + " 2>&1"
    }

    override fun parseStreamLine(line: String): List<AgentMessage> =
        CursorMessageParser.parse(line)

    /**
     * Transcripts live at
     * `~/.cursor/projects/<slug>/agent-transcripts/<chatId>/<chatId>.jsonl`,
     * where `<slug>` is the workspace path with every non-alphanumeric run
     * collapsed to a single `-`. We walk the tree rather than ask the CLI:
     * `cursor-agent ls` is a TUI that hangs without a TTY.
     *
     * ⚠ The RECORD shapes inside were read out of the bundle, not sampled (no
     * authenticated run was made), so the preview extractor is deliberately
     * shape-tolerant: it takes the first `"text"` value of the first user
     * record rather than trusting an exact schema.
     */
    override val listSessionsScript: String? = """
for f in ~/.cursor/projects/*/agent-transcripts/*/*.jsonl; do
  [ -f "${'$'}f" ] || continue
  id="${'$'}{f##*/}"; id="${'$'}{id%.jsonl}"
  mtime=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || stat -f %m "${'$'}f" 2>/dev/null)
  size=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || stat -f %z "${'$'}f" 2>/dev/null)
  [ "${'$'}{size:-0}" -lt 64 ] && continue
  model=${'$'}(tail -c 131072 "${'$'}f" 2>/dev/null | grep -oE '"model":"[^"]+"' | tail -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  preview=${'$'}(grep -m1 '"role":"user"' "${'$'}f" 2>/dev/null | cut -b 1-700 | tr '\t' ' ')
  printf '%s\t%s\t%s\t%s\t\t%s\t%s\n' "${'$'}id" "${'$'}mtime" "${'$'}f" "${'$'}model" "${'$'}size" "${'$'}preview"
done | sort -t'	' -k2 -rn | head -300
""".trimIndent()

    override fun extractSessionPreview(rawPreview: String): String {
        if (rawPreview.isBlank()) return ""
        val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(rawPreview) ?: return ""
        return m.groupValues[1]
            .replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
            .replace("\\\"", "\"").replace("\\\\", "\\")
            .replace(Regex("\\s+"), " ").trim().take(140)
    }

    /** One chat = one directory under `agent-transcripts/`. */
    override fun deleteSessionCommand(sessionId: String, path: String): String {
        val p = shellEscape(path)
        return "d=\$(dirname $p); case \"\$d\" in */agent-transcripts/*) rm -rf \"\$d\";; *) rm -f $p;; esac"
    }

    override val statusProbeLines: String = """
echo "cursor_inst=${'$'}(command -v cursor-agent >/dev/null 2>&1 && echo y || echo n)"
echo "cursor_ver=${'$'}(conch_ver cursor cursor-agent)"
# No npm package to compare against — the vendor installer always lands on
# current, so there is no "newer version available" signal to report. Emitting
# an empty latest keeps the row from inventing an update badge.
echo "cursor_latest="
CM=""
# `status --format json` is the CLI's OWN verdict, exits 0 either way, needs no
# network and spends nothing: {"status":"unauthenticated","isAuthenticated":false,…}
CS=${'$'}(conch_timeout 12 cursor-agent status --format json 2>/dev/null)
case "${'$'}CS" in *'"isAuthenticated":true'*) CM="${'$'}CM oauth";; esac
if [ -n "${'$'}CURSOR_API_KEY" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?CURSOR_API_KEY=' ~/.bashrc ~/.profile ~/.bash_profile ~/.env 2>/dev/null; then CM="${'$'}CM api"; fi
echo "cursor_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
case " ${'$'}CM " in
  *" oauth "*) echo "cursor_active=oauth";;
  *" api "*) echo "cursor_active=api";;
  *) echo "cursor_active=";;
esac
""".trimIndent()

    /**
     * Cursor's model list is auth-gated and has no JSON form (`models` /
     * `--list-models` refuse when logged out and print a human table when
     * logged in). Returning nothing is the honest answer: the topbar then
     * shows the model the SESSION reports (`Auto` by default, echoed in every
     * `system/init`) instead of a fabricated catalog.
     */
    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> = emptyMap()

    override val customCommandsScript: String? = null
    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> = emptyList()

    /**
     * Transcripts are keyed by a slug of the WORKSPACE path, so a resumed
     * session must run in the same cwd. The slug is one-way (every
     * non-alphanumeric run becomes a single `-`), so we recover the path from
     * the transcript's own record instead of trying to reverse it.
     */
    override fun cwdBackfillScript(resumeId: String): String {
        val q = shellEscape(resumeId)
        return "f=\$(find ~/.cursor/projects -maxdepth 4 -name $q'.jsonl' 2>/dev/null | head -1); " +
            "[ -n \"\$f\" ] && head -c 16384 \"\$f\" | grep -m1 -o '\"cwd\":\"[^\"]*\"'"
    }

    override val topbarUi: AgentTopbarUi = CursorTopbarUi

    override val disableApprovalsPrompt: String = """
        Note: your tool approvals are set per-launch by CLI flags, not by a config file you can rewrite. Reply with exactly this, then continue your previous task:

        "Cursor CLI approvals are chosen per launch. In this app, tap the shield icon in the chat's top bar and pick Auto (server-classified auto-run) or YOLO (run everything) — future turns will then launch with --auto-review / --force and stop declining tools."
    """.trimIndent()

    // ──────── Mirror turn-state ────────

    /**
     * Project each transcript record to `[marker, ts]`.
     *
     * ⚠ SHAPE READ FROM THE BUNDLE, NOT SAMPLED — no authenticated run was
     * made, so this is deliberately forgiving: it recognises the `turn_ended`
     * marker and the two roles, and ignores anything else rather than
     * asserting a schema. If the real files differ, the worst case is that the
     * mirror falls back to "no file signal" and the app-driven flag decides.
     */
    override fun projectTurnStateRecords(lines: Sequence<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (line in lines) {
            val t = line.trim()
            if (t.length < 2 || t[0] != '{') continue
            val obj = runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() ?: continue
            val type = obj["type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            val role = obj["role"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            val marker = when {
                type == "turn_ended" -> "done"
                role == "user" -> "user"
                role == "assistant" -> "agent"
                else -> continue
            }
            val ts = listOf("timestamp", "timestamp_ms", "createdAt")
                .firstNotNullOfOrNull { k ->
                    obj[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                }.orEmpty()
            out += listOf(marker, ts)
        }
        return out
    }

    override fun inferTurnState(records: List<List<String>>, frozenForMs: Long?): TurnSignals {
        val recs = records.filter { it.isNotEmpty() }
        if (recs.isEmpty()) return TurnSignals()
        val last = recs.last()
        val inFlight = when (last[0]) {
            "done" -> false
            else -> frozenForMs == null || frozenForMs < AWAIT_STALE_MS
        }
        val startIdx = recs.indexOfLast { it[0] == "user" }
        val turnStartMs = if (inFlight && startIdx >= 0)
            recs[startIdx].getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ts ->
                ts.toLongOrNull()?.let { if (it < 1_000_000_000_000L) it * 1000 else it }
                    ?: runCatching { java.time.Instant.parse(ts).toEpochMilli() }.getOrNull()
            } else null
        return TurnSignals(
            inFlight = inFlight,
            thinking = inFlight && last[0] == "user",
            turnStartMs = turnStartMs,
            tokens = 0L,
            turnComplete = last[0] == "done",
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val AWAIT_STALE_MS = 12 * 60_000L
}

private object CursorTopbarUi : AgentTopbarUi {
    /** No catalog to offer (auth-gated, no machine-readable form), so the chip
     *  mirrors what the session itself reports and the dropdown stays shut —
     *  an empty picker that opens is worse than none. */
    override fun displayLabel(state: TopbarModelState): String? =
        state.selectedModel?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialModel?.takeIf { it.isNotBlank() }
            ?: state.observedModel?.takeIf { it.isNotBlank() }

    override fun isMenuEnabled(state: TopbarModelState): Boolean =
        state.availableModels.isNotEmpty() && !state.modelsProbing

    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> =
        state.availableModels.map { (slug, label) -> ModelMenuItem(display = label, storedValue = slug) }
}
