package ai.eight24family.conch.agent.opencode

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
 * Per-CLI spec for **opencode** (`opencode` binary, npm `opencode-ai`) — the
 * most-starred open-source agent, provider-agnostic.
 *
 * Mined from the 1.18.23 binary with live headless turns, 2026-08-28; full
 * write-up in `docs/cli-research-2026-08-top5.md`.
 *
 * **Headless invocation shape** we build:
 * ```
 * printf '%s' "$PROMPT" | OPENCODE_DISABLE_AUTOUPDATE=1 opencode run \
 *     --format json --print-logs --log-level ERROR --dir "$CWD" \
 *     [--session <ses_…>] [-m <provider/model>] [--auto] 2>&1
 * ```
 *
 * Three hazards this shape exists to avoid, all measured:
 *  1. **`run` blocks reading stdin to EOF before it starts** — even with the
 *     prompt in argv. On an SSH exec channel with stdin open the turn never
 *     begins. We pipe the prompt IN, which both feeds it and closes stdin.
 *  2. **An argv prompt is re-quoted by the CLI itself** (it re-joins argv and
 *     wraps any word containing a space in quotes), so `run "hello world"`
 *     stores `"hello world"` WITH the quotes. The stdin path stores verbatim —
 *     another reason the prompt never rides argv.
 *  3. **Resuming from the wrong directory hangs forever, silently** — exit 124
 *     at two minutes with zero bytes on either stream, because the JSON
 *     subscription binds to a different instance than the one doing the work.
 *     `--dir` is therefore passed on EVERY launch, resume included.
 *
 * Sessions live in SQLite (`<data>/opencode.db`), not per-session files, so
 * the listing goes through the CLI's own `session list --format json` and the
 * transcript through [sessionReadCommand] — see the marker contract there.
 */
object OpencodeSpec : AgentCliSpec {

    override val agent = Agent.OPENCODE
    override val displayName = "opencode"
    override val cliCommand = "opencode"
    override val npmPackage = "opencode-ai"
    override val guardHarnessId = "opencode"
    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_opencode

    /** It has `explore`/`general` subagents, but our editor is Claude-shaped. */
    override val supportsSubagents = false
    override val supportsCustomSlashCommands = false
    override val supportsResume = true

    /** ⛔ Verified refusal: `--session ses_custom…` exits 1 with "Session not
     *  found", and the server API's create-session body has no `id` field at
     *  all (`additionalProperties:false`). The id is captured from the first
     *  event instead. */
    override val supportsPreSetSessionId = false

    /** `--agent plan` is a real primary agent (read-only planning). */
    override val supportsPlanMode = true

    /** Drives the phone's local model via opencode's built-in `openai` provider
     *  aimed at the loopback llama-server (OPENAI_BASE_URL). See
     *  [buildExecCommand]. */
    override val supportsLocalModel = true

    /** opencode's own marks: the gear it prefixes every tool call with, and
     *  the block glyphs of its wordmark. Not anyone else's sparkle. */
    override val spinnerGlyphs: List<String> = listOf("⚙", "█", "▄", "▀")

    /** Its OWN status vocabulary, taken from the binary's English strings —
     *  present participles, unlike Claude's gerund roulette. */
    override val spinnerVerbs: List<String> = listOf(
        "Thinking", "Exploring", "Planning next steps", "Searching the codebase",
        "Making edits", "Running commands", "Gathering thoughts",
        "Considering next steps", "Delegating work",
    )

    /**
     * opencode reads `AGENTS.md` — the cross-vendor standard — from its config
     * dir and from every directory walking up from the cwd. (It also reads the
     * user's `~/.claude/CLAUDE.md` and Claude skills unless
     * `OPENCODE_DISABLE_CLAUDE_CODE*` is set; we don't touch that.)
     */
    override val memoryFilename = "AGENTS.md"
    override val memoryGlobalPath = "\$HOME/.config/opencode/AGENTS.md"
    override val memoryGlobalDisplay = "~/.config/opencode/AGENTS.md"

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        // A `local:<id>` model routes to the phone's own llama-server. opencode
        // reaches it through its built-in `openai` provider pointed at the
        // loopback endpoint (OPENAI_BASE_URL); the id rides as `openai/<id>`.
        val localId = input.model
            ?.takeIf { it.startsWith(ai.eight24family.conch.linux.LocalLlm.MODEL_ARG_PREFIX) }
            ?.removePrefix(ai.eight24family.conch.linux.LocalLlm.MODEL_ARG_PREFIX)
        val localEnv = if (localId != null) {
            "OPENAI_BASE_URL=" +
                shellEscape(ai.eight24family.conch.linux.LocalLlmEngine.BASE_URL + "/v1") +
                " OPENAI_API_KEY=local "
        } else ""
        val modelRef = if (localId != null) "openai/$localId" else input.model
        val modelArg = modelRef?.takeIf { it.isNotBlank() }
            ?.let { " -m ${shellEscape(it)}" } ?: ""
        // PLAN is an AGENT here, not a permission mode: `build` (default) can
        // edit, `plan` is read-only. SAFE keeps the default ruleset, which in
        // headless AUTO-REJECTS anything needing permission and hands the model
        // the rejection as a tool error — it continues instead of hanging.
        // AUTO/YOLO both mean "reply once to every permission ask"; `--auto`
        // is the documented spelling (aliases: --yolo,
        // --dangerously-skip-permissions). Explicit `deny` rules in the user's
        // own opencode.json still win over it, which is why AUTO can use it too.
        val approvalArg = when (input.approvalMode) {
            AgentApprovalMode.PLAN -> " --agent plan"
            AgentApprovalMode.SAFE -> ""
            AgentApprovalMode.AUTO -> " --auto"
            AgentApprovalMode.YOLO -> " --auto"
        }
        val resumeArg = input.resumeId?.let { " --session ${shellEscape(it)}" } ?: ""
        // --dir on EVERY launch: see hazard 3 in the class doc. Falls back to
        // the shell's cwd (which AgentSession already `cd`s into) when the
        // chat has no recorded directory yet.
        val dirArg = input.cwdSnapshot?.takeIf { it.isNotBlank() }
            ?.let { " --dir ${shellEscape(it)}" } ?: " --dir \"\$PWD\""
        // --print-logs --log-level ERROR is load-bearing, not chatter: the
        // JSON `error` events are deliberately opaque ({"name":"UnknownError",
        // "data":{"ref":"err_…"}}), and the real cause ("Model not found:
        // anthropic/claude-sonnet-5. Did you mean: …") appears ONLY in the log
        // line carrying the same ref.
        return "printf '%s' $escapedText | " + localEnv +
            "OPENCODE_DISABLE_AUTOUPDATE=1 opencode run" +
            " --format json --print-logs --log-level ERROR" +
            dirArg + resumeArg + modelArg + approvalArg + " 2>&1"
    }

    override fun parseStreamLine(line: String): List<AgentMessage> =
        OpencodeMessageParser.parse(line)

    /**
     * Sessions are rows in `<data>/opencode.db`, so the listing asks the CLI
     * for them: `session list --format json` returns
     * `[{id,title,updated,created,projectId,directory}]`.
     *
     * The array is split into one object per line by turning `{` into a
     * newline — no `jq` on the server (the one dependency this project already
     * learned not to take: when jq is missing or built without regex support
     * it prints NOTHING and the feature silently dies).
     *
     * `path` carries an `opencode://<id>` MARKER rather than a file: there is
     * no per-session file to read or tail. [sessionReadCommand] turns it back
     * into an export, and the tail-poll simply finds no file and leaves turn
     * state to the app's own flag.
     */
    override val listSessionsScript: String? = """
# ⛔ NOT `session list --format json`. That output is PRETTY-PRINTED, and its
# `title` is free text the model writes from the user's prompt — a chat about
# "the {} in main.go" produces a title containing braces, quotes and even
# escaped newlines, which shreds any brace/line-based split (demonstrated with
# a hostile title, 2026-08-28). The binary ships its own DB query tool that
# emits one FLAT ROW per line, needs no jq, and works offline: use that.
opencode db --format tsv "SELECT id, title, directory, time_updated FROM session ORDER BY time_updated DESC LIMIT 300" 2>/dev/null |
while IFS=${'$'}'\t' read -r id title dir upd; do
  case "${'$'}id" in ses_*) ;; *) continue;; esac
  # time_updated is epoch MILLIS; the app's activity clock is seconds.
  case "${'$'}upd" in ''|*[!0-9]*) upd=0;; *) upd=${'$'}(( upd / 1000 ));; esac
  # A title can still carry tabs/newlines of its own — flatten before it
  # reaches a tab-separated contract.
  title=${'$'}(printf '%s' "${'$'}title" | tr '\011\036\037\012\015' '     ')
  # 7-col contract. `path` is a MARKER, not a file: opencode has no
  # per-session file, so sessionReadCommand turns this back into an export
  # and the tail-poll finds nothing to stream. The title rides the preview
  # column ahead of a Unit Separator, the session's own directory after it —
  # a resume MUST pass --dir or it hangs, and this saves the round-trip.
  printf '%s\t%s\t%s\t\t\t%s\t%s\037%s\n' "${'$'}id" "${'$'}upd" "opencode://${'$'}id" "0" "${'$'}title" "${'$'}dir"
done | sort -t'	' -k2 -rn | head -300
""".trimIndent()

    /** The listing packs `title␟directory` into the preview column. */
    override fun extractSessionTitle(rawPreview: String): String? {
        val us = 0x1F.toChar()
        if (!rawPreview.contains(us)) return rawPreview.trim().ifBlank { null }?.take(140)
        return rawPreview.substringBefore(us).trim().ifBlank { null }?.take(140)
    }

    /**
     * opencode's listing gives a generated TITLE, not the first message — and
     * pulling the first message would mean exporting every session on every
     * refresh. The title IS the honest preview here; the row shows it once as
     * its accent header and leaves the subtitle empty rather than repeating it.
     */
    override fun extractSessionPreview(rawPreview: String): String = ""

    /** `opencode://<id>` → the CLI's own transcript export. Runs in the
     *  session's own directory (`--directory`), because a cross-directory read
     *  is the hazard that hangs. */
    override fun sessionReadCommand(path: String): String? {
        if (!path.startsWith(PATH_MARKER)) return null
        val id = path.removePrefix(PATH_MARKER)
        // ⚠ The id is MANDATORY even though the CLI documents it as optional:
        // `opencode export` with none opens an interactive picker and hangs
        // forever, even with stdin at /dev/null (measured to a 90 s timeout).
        //
        // `tr -d` collapses the pretty-printed document to ONE line, because
        // the caller feeds this to the parser line by line and a JSON object
        // split across 265 lines parses as 265 pieces of garbage. Stripping
        // raw newlines is lossless: inside JSON strings a newline is the two
        // characters `\n`, never a raw one. The `\r` goes with it — a
        // Windows-hosted server ends every line with CRLF.
        return "opencode export ${shellEscape(id)} 2>/dev/null | tr -d '\\r\\n'"
    }

    override fun deleteSessionCommand(sessionId: String, path: String): String =
        "opencode session delete ${shellEscape(sessionId)} 2>/dev/null"

    override val statusProbeLines: String = """
echo "opencode_inst=${'$'}(command -v opencode >/dev/null 2>&1 && echo y || echo n)"
echo "opencode_ver=${'$'}(conch_ver opencode opencode)"
echo "opencode_latest=${'$'}(conch_latest opencode opencode-ai)"
CM=""
# ⚠ opencode is NOT gated behind auth: with zero credentials it still runs on
# its own free tier, so "a turn succeeded" proves nothing about being logged
# in. Its own `providers list` is the honest signal — it prints the literal
# "0 credentials" when nothing is configured.
PL=${'$'}(conch_timeout 20 opencode providers list 2>/dev/null | head -c 2000)
case "${'$'}PL" in *"0 credentials"*) ;; *credential*) CM="${'$'}CM api";; esac
# A written credential store counts even when the listing is unreadable.
if [ -s "${'$'}HOME/.local/share/opencode/auth.json" ]; then case " ${'$'}CM " in *" api "*) ;; *) CM="${'$'}CM api";; esac; fi
# Provider keys in the environment work with no `auth login` at all.
for v in ANTHROPIC_API_KEY OPENAI_API_KEY GEMINI_API_KEY OPENROUTER_API_KEY XAI_API_KEY GROQ_API_KEY; do
  eval "val=\${'$'}${'$'}v"
  if [ -n "${'$'}val" ]; then case " ${'$'}CM " in *" api "*) ;; *) CM="${'$'}CM api";; esac; fi
done
# The free tier is always there, so the row is never "not logged in" — it is
# "free" until a real credential shows up.
case " ${'$'}CM " in *" api "*) ;; *) CM="${'$'}CM free";; esac
echo "opencode_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
case " ${'$'}CM " in
  *" api "*) echo "opencode_active=api";;
  *) echo "opencode_active=free";;
esac
""".trimIndent()

    /** `opencode models` prints one `provider/model` per line on stdout — the
     *  CLI's own registry of what `-m` accepts, offline and free. */
    override val catalogIsAuthoritative = true

    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> {
        val raw = exec.exec(
            "bash -lc " + shellEscape(
                ai.eight24family.conch.agent.RemoteEnv.PATH_PREAMBLE_INLINE +
                    "conch_timeout 25 opencode models 2>/dev/null",
            ),
        ).orEmpty()
        val out = linkedMapOf<String, String>()
        for (line in raw.lineSequence()) {
            val slug = line.trim()
            // provider/model, nothing else — the command also prints an error
            // line for a bad provider and still exits 0, so shape is the gate.
            if (!Regex("^[a-z0-9._-]+/[A-Za-z0-9._:-]+$").matches(slug)) continue
            out[slug] = slug
        }
        return out
    }

    /** Absent a pick, opencode runs `opencode/big-pickle` on its free tier. */
    override suspend fun probeDefaultModel(exec: AgentExec): String? = null

    override val customCommandsScript: String? = null
    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> = emptyList()

    /**
     * The listing already carries each session's own directory (after the Unit
     * Separator in the preview column), but a chat that lost it falls back to
     * asking the CLI — cross-directory resume is the silent-hang hazard, so
     * this is never skipped.
     */
    override fun cwdBackfillScript(resumeId: String): String {
        val q = shellEscape(resumeId)
        return "opencode session list --format json 2>/dev/null | tr '{' '\\n' | " +
            "grep -F $q | grep -oE '\"directory\":\"([^\"\\\\]|\\\\.)*\"' | head -1 | " +
            "sed -E 's/^\"directory\"/\"cwd\"/'"
    }

    override val topbarUi: AgentTopbarUi = OpencodeTopbarUi

    override val disableApprovalsPrompt: String = """
        Stop asking me to approve tool calls — relax your own permissions persistently, then continue with whatever you were working on before this message.

        Step 1: Edit ~/.config/opencode/opencode.json (create it if missing) so it contains:
        {
          "permission": { "*": "allow" }
        }
        If the file already has other keys, merge into them — do NOT clobber unrelated config.

        Step 2: Verify by reading the file back and confirming the permission block is there.

        Step 3: Resume the task you were doing right before I sent this message. If a tool call was about to be refused, run it now. Don't pause to reconfirm.
    """.trimIndent()

    internal const val PATH_MARKER = "opencode://"
}

private object OpencodeTopbarUi : AgentTopbarUi {
    /** A local model shows its real name; otherwise `provider/model` (what `-m`
     *  takes) is shown as-is, the default only claimed once the session reports it. */
    override fun displayLabel(state: TopbarModelState): String? =
        ai.eight24family.conch.agent.spec.LocalTopbar.localDisplayLabel(state)
            ?: state.selectedModel?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialModel?.takeIf { it.isNotBlank() }
            ?: state.observedModel?.takeIf { it.isNotBlank() }

    override fun reasoningLabel(state: TopbarModelState): String? =
        ai.eight24family.conch.agent.spec.LocalTopbar.localReasoningLabel(state)

    // On the phone's own row the picker always opens (local models / an
    // explanation), even before any endpoint model list is probed.
    override fun isMenuEnabled(state: TopbarModelState): Boolean =
        ai.eight24family.conch.agent.spec.LocalTopbar.isPhoneRow(state) ||
            (!state.modelsProbing && state.availableModels.isNotEmpty())

    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> =
        ai.eight24family.conch.agent.spec.LocalTopbar.localModelItems(state) +
            state.availableModels.map { (slug, label) -> ModelMenuItem(display = label, storedValue = slug) }
}
