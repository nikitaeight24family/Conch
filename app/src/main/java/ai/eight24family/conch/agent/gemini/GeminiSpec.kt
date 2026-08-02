package ai.eight24family.conch.agent.gemini

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
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-CLI spec for Google Gemini CLI (binary `gemini`, npm `@google/gemini-cli`).
 *
 * Authority for every flag and event type: Google's CLI reference and
 * headless docs, captured in `docs/cli-research-2026-05.md` section 3.
 *
 * **Why this was rewritten.** The old build path was effectively broken:
 *
 *  1. It passed `--output-format json` (single final-blob) instead of
 *     `stream-json` (JSONL events), so the chat sat blank until the agent
 *     finished, with no incremental rendering.
 *  2. It passed `--yolo`, which is deprecated; the supported form is
 *     `--approval-mode yolo`.
 *  3. There was no parser at all — Gemini's events fell through to the
 *     Claude parser which knew nothing about them.
 *
 * **Resume:** `gemini --resume <session-uuid>` (or `latest` / numeric
 * index). Cwd-bound via the project-hash bucketing in
 * `~/.gemini/tmp/<hash>/chats/`.
 */
object GeminiSpec : AgentCliSpec {

    override val agent = Agent.GEMINI
    override val displayName = "Gemini CLI"
    override val cliCommand = "gemini"
    override val npmPackage = "@google/gemini-cli"
    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_gemini

    override val supportsSubagents = false
    /** Gemini ships TOML user commands under `~/.gemini/commands/` but their
     *  headless behavior is undocumented; treat as unsupported. */
    override val supportsCustomSlashCommands = false
    override val supportsResume = true
    override val supportsPreSetSessionId = false

    override val memoryFilename = "GEMINI.md"
    override val memoryGlobalPath = "\$HOME/.gemini/GEMINI.md"
    override val memoryGlobalDisplay = "~/.gemini/GEMINI.md"

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        val modelArg = input.model?.takeIf { it.isNotBlank() }
            ?.let { " --model " + shellEscape(it) } ?: ""
        // Gemini approval modes: default / auto_edit / yolo / plan.
        // SAFE/AUTO/YOLO map onto default/auto_edit/yolo.
        val approvalArg = when (input.approvalMode) {
            AgentApprovalMode.SAFE -> " --approval-mode default"
            AgentApprovalMode.AUTO -> " --approval-mode auto_edit"
            AgentApprovalMode.YOLO -> " --approval-mode yolo"
        }
        val resumeArg = input.resumeId?.let { " --resume " + shellEscape(it) } ?: ""
        // Gemini's API-key auth (GEMINI_API_KEY / GOOGLE_API_KEY env) beats the
        // OAuth creds on disk. The catch: that key very often lives ONLY in
        // ~/.bashrc, behind Debian's `case $- in *i*` interactive guard — so a
        // non-interactive `bash -lc` (which is what EVERY chat turn is) launches
        // the CLI with an EMPTY key and the backend 400s with "API key not
        // valid". The status probe still reports "api" because it greps the rc
        // files BY NAME, which masks the runtime mismatch entirely.
        //
        // Fix: when no key is already in the env AND there's no OAuth cred to
        // honour, pull the `export KEY=…` line(s) out of the usual rc files BY
        // NAME and eval them so the CLI sees the key it would see in a real
        // login shell. The value is materialised only inside this remote shell
        // and piped straight into the CLI — it never crosses back into the app
        // (presence-only credential contract). The no-key + no-OAuth guard keeps
        // OAuth sessions byte-identical to before (refresh_token present ⇒ skip).
        val loadApiKey = apiKeyPreload
        // `--output-format stream-json` is what gives us JSONL events. The
        // old code used `--output-format json` which emits a single blob at
        // end of turn — no streaming, looked exactly like a hung CLI. PR
        // 10883 added stream-json; on older Gemini installs the flag is
        // unknown and stdout falls back to text. The parser surfaces text
        // lines as Raw messages so the user still sees the reply.
        // `--skip-trust` is mandatory in headless mode: without it the
        // CLI complains "Gemini CLI is not running in a trusted
        // directory" and then forcibly downgrades any approval-mode
        // setting to "default" — which would re-introduce interactive
        // prompts that have nowhere to be answered (we're in --print
        // mode, no TTY). Setting --skip-trust auto-trusts the cwd for
        // this single invocation, which is the right semantic for our
        // case (the user has already paid for the SSH + the directory
        // they're working in by tapping into this chat).
        return loadApiKey + "printf '%s' " + escapedText +
            " | gemini --skip-trust --output-format stream-json" +
            approvalArg + modelArg + resumeArg + " 2>&1"
    }

    /**
     * Shell prefix that materialises GEMINI_API_KEY / GOOGLE_API_KEY from
     * the rc files when they're hidden behind Debian's interactive guard —
     * shared by [buildExecCommand] AND the ACP launcher
     * ([ai.eight24family.conch.agent.AgentSessionGeminiAcp]). The value
     * never crosses back into the app (presence-only credential contract).
     */
    internal val apiKeyPreload: String =
        "if [ -z \"\$GEMINI_API_KEY\" ] && [ -z \"\$GOOGLE_API_KEY\" ] && " +
        "! grep -qs refresh_token \$HOME/.gemini/oauth_creds.json \$HOME/.config/gemini/oauth_creds.json 2>/dev/null; then " +
        "eval \"\$(grep -hE \"^[[:space:]]*(export[[:space:]]+)?(GEMINI_API_KEY|GOOGLE_API_KEY)=\" " +
        "\$HOME/.bashrc \$HOME/.profile \$HOME/.bash_profile \$HOME/.env 2>/dev/null | head -10)\" 2>/dev/null; fi; "

    override fun parseStreamLine(line: String): List<AgentMessage> =
        GeminiMessageParser.parse(line)

    /**
     * Gemini CLI 0.40+ stores chat sessions as JSONL at
     * ~/.gemini/tmp/<project>/chats/session-<ts>-<id>.jsonl . The older
     * per-hash .json layout is abandoned — `--list-sessions` no longer
     * surfaces it and `--resume <stem>` rejects it ("Invalid"), so we don't
     * list it either (it stays on disk, just not as a live session).
     *
     * The resumable id is the `sessionId` UUID in the file's first metadata
     * record — exactly what `gemini --resume <uuid>` accepts (verified on
     * 0.44.1). So the script:
     *   - matches the session JSONL files directly inside a chats dir,
     *     skipping the nested per-uuid subagent snapshots that
     *     `--list-sessions` itself hides (the extra find-path exclusion),
     *   - takes id from the first-line sessionId,
     *   - builds the preview from the first user text that ISN'T Gemini's
     *     injected session_context preamble,
     *   - dedupes by sessionId (resuming writes a fresh snapshot file with
     *     the same id) and sorts newest-first.
     */
    override val listSessionsScript: String? = """
find ~/.gemini/tmp -type f -name 'session-*.jsonl' -path '*/chats/*' ! -path '*/chats/*/*' 2>/dev/null | while IFS= read -r f; do
  [ -f "${'$'}f" ] || continue
  mtime=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || stat -f %m "${'$'}f" 2>/dev/null)
  size=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || stat -f %z "${'$'}f" 2>/dev/null)
  id=${'$'}(head -1 "${'$'}f" | grep -oE '"sessionId":"[^"]+"' | head -1 | sed -E 's/.*:"([^"]+)"${'$'}/\1/')
  [ -n "${'$'}id" ] || continue
  preview=${'$'}(grep -oE '"text":"[^"]*"' "${'$'}f" 2>/dev/null | grep -viE 'session_context|setting up the context' | head -1 | sed -E 's/^"text":"//; s/"${'$'}//')
  # Actual model that ANSWERED. Gemini auto-routes per turn (the chat is
  # launched as "auto"), but each reply record carries the concrete id it
  # used, e.g. gemini-3-flash-preview. Take the most recent non-"auto" one so
  # a resumed chat shows the real model instead of the bare "Auto" mode.
  model=${'$'}(grep -oE '"model":"[^"]+"' "${'$'}f" 2>/dev/null | sed -E 's/"model":"//; s/"${'$'}//' | grep -v '^auto${'$'}' | tail -1)
  printf '%s\t%s\t%s\t%s\t\t%s\t%s\n' "${'$'}id" "${'$'}mtime" "${'$'}f" "${'$'}model" "${'$'}size" "${'$'}preview"
done | sort -t'	' -k2 -rn | awk -F'\t' '!seen[${'$'}1]++' | head -200
""".trimIndent()

    override fun extractSessionPreview(rawPreview: String): String {
        if (rawPreview.isBlank()) return ""
        // listSessionsScript already isolates the first non-preamble user
        // `"text"` (Gemini's <session_context> block is filtered out there),
        // so the column normally arrives as the raw JSON string body. Unescape
        // the common sequences for display. The text/content regexes are a
        // fallback for any blob-style preview that still carries JSON wrappers.
        val body = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(rawPreview)?.groupValues?.getOrNull(1)
            ?: Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(rawPreview)?.groupValues?.getOrNull(1)
            ?: rawPreview
        return body
            .replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
            .replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
            .replace(Regex("\\s+"), " ").trim().take(140)
    }

    /**
     * A Gemini chat can be persisted across SEVERAL `.jsonl` files that share
     * one `sessionId` (every resume writes a fresh snapshot — listSessionsScript
     * dedupes them to one row). Removing only the surfaced [path] would leave
     * the siblings, and the next discovery sweep would resurrect the session.
     * So delete every `.jsonl` in the same `chats/` dir whose first-line
     * metadata carries this id, then the path itself as a backstop.
     */
    override fun deleteSessionCommand(sessionId: String, path: String): String {
        val p = shellEscape(path)
        val idPattern = shellEscape("\"sessionId\":\"$sessionId\"")
        return "d=\$(dirname $p) && " +
            "grep -lF $idPattern \"\$d\"/*.jsonl 2>/dev/null | xargs -r rm -f; " +
            "rm -f $p"
    }

    override val statusProbeLines: String = """
echo "gemini_inst=${'$'}(command -v gemini >/dev/null 2>&1 && echo y || echo n)"
echo "gemini_ver=${'$'}(gemini --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
echo "gemini_latest=${'$'}(command -v npm >/dev/null 2>&1 && npm view @google/gemini-cli version 2>/dev/null | tr -d '\r\n ' || echo '')"
CM=""
# OAuth = creds file that actually carries a refresh_token. A bare
# `-f` test lied: an empty/partial oauth_creds.json (e.g. the login
# poller killed the CLI mid token-exchange) read as "logged in"
# forever, then every turn failed. The offline OAuth flow
# (access_type=offline, see the login URL) always writes a
# refresh_token on success, so its presence is the real signal.
if grep -qs '"refresh_token"' ~/.gemini/oauth_creds.json ~/.config/gemini/oauth_creds.json 2>/dev/null; then CM="${'$'}CM oauth"; fi
if [ -n "${'$'}GEMINI_API_KEY" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?GEMINI_API_KEY=' ~/.bashrc ~/.profile ~/.bash_profile ~/.gemini/.env ~/.env 2>/dev/null; then CM="${'$'}CM api"; fi
if [ "${'$'}GOOGLE_GENAI_USE_VERTEXAI" = "true" ] || [ -n "${'$'}GOOGLE_APPLICATION_CREDENTIALS" ] || [ -f ~/.config/gcloud/application_default_credentials.json ]; then CM="${'$'}CM vertex"; fi
if [ -n "${'$'}GOOGLE_API_KEY" ] && [ "${'$'}GOOGLE_GENAI_USE_VERTEXAI" != "true" ]; then case " ${'$'}CM " in *" api "*) ;; *) CM="${'$'}CM api";; esac; fi
echo "gemini_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
SA=${'$'}(grep -oE '"(selectedAuthType|selectedType)"[[:space:]]*:[[:space:]]*"[^"]+"' ~/.gemini/settings.json ~/.config/gemini/settings.json 2>/dev/null | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
case "${'$'}SA" in
  oauth-personal) echo "gemini_active=oauth";;
  gemini-api-key) echo "gemini_active=api";;
  vertex-ai) echo "gemini_active=vertex";;
  *) echo "gemini_active=";;
esac
""".trimIndent()

    /** LIVE auth validation — runs the gemini CLI for real and reports whether
     *  the OAuth login actually works (creds-on-disk lie: token present but
     *  account not provisioned for Code Assist / revoked). Slow (spawns the
     *  CLI), so [AgentStatusProbe.probeLiveAuth] runs it ASYNC and merges into
     *  the already-shown fast-probe status — the picker never blocks on it.
     *  Emits `gemini_authok=y|n`; absent ⇒ nothing to validate. `head -c` kills
     *  the call right after the auth verdict (no full generation → fast+cheap). */
    override val liveAuthProbeLines: String = """
if command -v gemini >/dev/null 2>&1; then
  if grep -qs '"refresh_token"' ${'$'}HOME/.gemini/oauth_creds.json ${'$'}HOME/.config/gemini/oauth_creds.json 2>/dev/null; then
    # Verify OAuth IN ISOLATION: unset the api-key / vertex selectors first, or
    # gemini would answer via the API key and the probe would FALSELY conclude
    # "OAuth works" (the bug — a server with a stale oauth_creds + a real api-key
    # showed "ready / OAuth"). With the key unset, success means OAuth itself
    # works; failure (401 / "API key not valid" / UNAUTHENTICATED) means it
    # doesn't, and the verdict drops the oauth badge.
    GLO=${'$'}(unset GEMINI_API_KEY GOOGLE_API_KEY GOOGLE_GENAI_USE_VERTEXAI; printf 'hi' | timeout 25 gemini --skip-trust --output-format stream-json 2>&1 | head -c 6000)
    # NB: a non-interactive gemini with a written-but-unprovisioned oauth cred
    # (Code Assist refused) prints "Manual authorization is required ... the
    # current session is non-interactive" and exits 41 — NOT any of the classic
    # auth-error strings. Missing those here is exactly why the badge falsely
    # read "ready" after a Code-Assist-blocked login. Match them too.
    if echo "${'$'}GLO" | grep -qE 'UNAUTHENTICATED|invalid_grant|Reauthentication|invalid authentication|Could not load the default credentials|Please visit the following URL|Enter the authorization code|no valid credential|not authenticated|Login Required|API key not valid|PERMISSION_DENIED|connect to Gemini Code Assist|Code Assist for individuals|cloudcode-pa|401|Manual authorization|non-interactive|interactive terminal|Failed to sign in|Failed to authenticate|ineligible'; then
      echo "gemini_authok=n"
    else
      echo "gemini_authok=y"
    fi
  fi
fi
""".trimIndent()

    /**
     * Probe the live Google AI REST endpoint for the actual catalog
     * of available models, then fall back to a curated static list if
     * the network call fails or returns nothing.
     *
     * Auth precedence (matches the CLI's own choice):
     *   1. `$GEMINI_API_KEY` / `$GOOGLE_API_KEY` (API-key path)
     *   2. `access_token` from `~/.gemini/oauth_creds.json`
     *      (OAuth-personal path)
     *
     * If neither is present we serve the static fallback so the
     * picker still has something — the user might be on a fresh
     * install where OAuth ran but `oauth_creds.json` lives in a
     * different path (we look in both `~/.gemini/` and
     * `~/.config/gemini/`).
     *
     * Response format:
     *   {
     *     "models": [
     *       { "name": "models/gemini-3-pro-preview",
     *         "displayName": "Gemini 3 Pro Preview",
     *         "supportedGenerationMethods": ["generateContent", …] },
     *       …
     *     ]
     *   }
     *
     * We keep models with `generateContent` and prepend the synthetic
     * `auto` alias the CLI honours for adaptive routing.
     *
     * Source for the endpoint: https://ai.google.dev/api/models
     */
    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> {
        // Fallback for when the REST probe fails (no API key, no
        // OAuth creds yet, free-tier 500 etc.). Without **some** map
        // the picker stays empty and unclickable — which exactly the
        // user reported. The list below is curated to current 2026
        // model aliases, so even if the REST call dies we ship a
        // reasonable picker. The user can still type any model
        // string in -m at the CLI level if they need something
        // exotic; we pass it through verbatim.
        val fallback = linkedMapOf(
            "auto" to "Auto",
            "gemini-3-pro-preview" to "Gemini 3 Pro Preview",
            "gemini-3-flash-preview" to "Gemini 3 Flash Preview",
            "gemini-2.5-pro" to "Gemini 2.5 Pro",
            "gemini-2.5-flash" to "Gemini 2.5 Flash",
            "gemini-2.5-flash-lite" to "Gemini 2.5 Flash Lite",
        )
        // Single shell pipeline: pick whichever auth is available,
        // curl the catalog, dump JSON to stdout. No-op (empty) if
        // nothing's reachable.
        val probeCmd = """
            export PATH="${'$'}HOME/.local/bin:/usr/local/bin:${'$'}PATH"
            KEY=""
            if [ -n "${'$'}GEMINI_API_KEY" ]; then KEY="${'$'}GEMINI_API_KEY"; fi
            if [ -z "${'$'}KEY" ] && [ -n "${'$'}GOOGLE_API_KEY" ]; then KEY="${'$'}GOOGLE_API_KEY"; fi
            if [ -n "${'$'}KEY" ]; then
                curl -fsS -m 6 "https://generativelanguage.googleapis.com/v1beta/models?key=${'$'}KEY&pageSize=200" 2>/dev/null
                exit 0
            fi
            CREDS=""
            for f in "${'$'}HOME/.gemini/oauth_creds.json" "${'$'}HOME/.config/gemini/oauth_creds.json"; do
                [ -f "${'$'}f" ] && CREDS="${'$'}f" && break
            done
            if [ -n "${'$'}CREDS" ]; then
                TOK=${'$'}(grep -oE '"access_token"[[:space:]]*:[[:space:]]*"[^"]+"' "${'$'}CREDS" | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
                if [ -n "${'$'}TOK" ]; then
                    curl -fsS -m 6 -H "Authorization: Bearer ${'$'}TOK" "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" 2>/dev/null
                fi
            fi
        """.trimIndent()
        val json = SilentlyTry.logged("SshAi-GeminiSpec", "exec gemini models probe") { exec.exec("bash -lc " + shellEscapeForGemini(probeCmd)) }
            ?.takeIf { it.contains("\"models\"") }
            ?: return fallback

        // Cheap streaming parse — no Moshi dependency just for this.
        // Each model entry has `"name": "models/<alias>"`,
        // `"displayName": "…"`, and `"supportedGenerationMethods": […]`.
        // We zip them up positionally — Google emits them in a stable
        // object order per record, so as long as we scan record-by-
        // record we're fine.
        val nameRe = Regex("\"name\"\\s*:\\s*\"models/([^\"]+)\"")
        val displayRe = Regex("\"displayName\"\\s*:\\s*\"([^\"]+)\"")
        val methodsRe = Regex("\"supportedGenerationMethods\"\\s*:\\s*\\[([^\\]]*)\\]")
        // Split the body into per-record chunks. Google's JSON
        // pretty-prints each model as a `{ … }` object delimited by
        // `},\n{` in v1beta. Use `}` as a soft delimiter.
        val out = linkedMapOf<String, String>()
        out["auto"] = "Auto"  // Always synthesise — Google doesn't expose it
        for (chunk in json.split("},")) {
            val name = nameRe.find(chunk)?.groupValues?.getOrNull(1) ?: continue
            val methods = methodsRe.find(chunk)?.groupValues?.getOrNull(1).orEmpty()
            // Only models we can actually `generateContent` against.
            if (!methods.contains("generateContent")) continue
            // Skip embedding / image / vision-only / legacy models.
            if (name.startsWith("embedding") || name.startsWith("aqa")) continue
            if (name.contains("tts") || name.contains("imagen") || name.contains("veo")) continue
            val display = displayRe.find(chunk)?.groupValues?.getOrNull(1) ?: name
            out[name] = display
        }
        // If parsing didn't yield real rows (>1 entry — auto is
        // always there) fall back so the picker isn't empty.
        return if (out.size > 1) out else fallback
    }

    /** Shell-escape helper (local copy because [shellEscape] lives in
     *  the top-level `agent` package and we don't want to import the
     *  whole file). */
    private fun shellEscapeForGemini(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    override val customCommandsScript: String? = null
    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> = emptyList()

    /**
     * Gemini buckets sessions by `project_hash` (SHA-256 of cwd), so resume
     * only finds a session when invoked from the same cwd. The hash is
     * one-way — we can't reverse it from a saved file path. Returning null
     * means we don't backfill; if our local DB doesn't remember the cwd,
     * resume may silently start a fresh session.
     */
    override fun cwdBackfillScript(resumeId: String): String? = null

    /**
     * Gemini doesn't expose a `--model` flag we drive yet, so the
     * picker stays empty and the topbar just shows the agent name.
     * Wiring up a real picker is one swap of this object the day we
     * decide to support it.
     */
    override val topbarUi: AgentTopbarUi = GeminiTopbarUi

    override val disableApprovalsPrompt: String =
        "Stop asking me to approve tool calls — relax your own approval mode persistently, then continue with whatever you were working on before this message.\n\n" +
        "Step 1: Edit ~/.gemini/settings.json (create it if missing) so the top-level JSON object contains:\n" +
        "  \"approvalMode\": \"yolo\"\n" +
        "If the file already has other keys, merge — keep them intact.\n\n" +
        "Step 2: Verify by reading the file back; the key must be \"yolo\".\n\n" +
        "Step 3: Resume the task you were doing right before I sent this message. If you were about to ask for approval to run a tool, run it now. Don't pause to reconfirm."

    // ──────── Mirror turn-state ────────

    /**
     * Project each message record to `[type, isoTs, outTokens]`.
     *
     * Gemini's session file is NOT an event stream — it interleaves full-array
     * `{"$set":{messages:[…]}}` snapshots, `{"$set":{lastUpdated:…}}` housekeeping
     * bumps, and individual top-level message records `{id,timestamp,type,content,
     * model,tokens,…}` (verified on the server 2026-06-27). Turn-state reads ONLY
     * the individual records (top-level `.type` ∈ `user`/`gemini`); `$set`/meta/
     * `info` lines are skipped. A `gemini` reply carries `.tokens.output`.
     *
     * The boundary is reliable: Gemini writes the `user` record when the prompt is
     * sent and the `gemini` record when the reply lands, so the last record's type
     * IS the live state — last `user` → model working, last `gemini` → done.
     */
    
    /**
     * DEFINITIVE Gemini turn state from the last message record — no timeout for
     * the DONE case (a `gemini` reply means the turn finished), a staleness guard
     * only for a `user`-last record that never got a reply (abandoned/wedged).
     * thinking == (awaiting the model). Gemini approvals ride our own channel, not
     * the file, so waitingForUser stays false.
     */
    /**
     * Field layout, read by index in [inferTurnState]:
     *   0 type ("user" | "gemini") · 1 timestamp · 2 outputTokens
     */
    override fun projectTurnStateRecords(lines: Sequence<String>): List<List<String>> {
        val j = Json { ignoreUnknownKeys = true; isLenient = true }
        val out = ArrayList<List<String>>()
        for (line in lines) {
            val t = line.trim()
            if (t.length < 2 || t[0] != '{') continue
            val obj = runCatching { j.parseToJsonElement(t).jsonObject }.getOrNull() ?: continue
            val type = obj["type"]?.let {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            } ?: continue
            if (type != "user" && type != "gemini") continue
            val ts = obj["timestamp"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            val tokens = runCatching {
                obj["tokens"]?.jsonObject?.get("output")?.jsonPrimitive?.content
            }.getOrNull() ?: "0"
            out += listOf(type, ts, tokens)
        }
        return out
    }

    override fun inferTurnState(records: List<List<String>>, frozenForMs: Long?): TurnSignals {
        val recs = records.filter { it.isNotEmpty() && (it[0] == "user" || it[0] == "gemini") }
        if (recs.isEmpty()) return TurnSignals()
        val last = recs.last()
        val inFlight = when {
            last[0] == "gemini" -> false // model replied → turn done
            last[0] == "user" -> frozenForMs == null || frozenForMs < AWAIT_STALE_MS
            else -> false
        }
        val thinking = inFlight && last[0] == "user"
        val startIdx = recs.indexOfLast { it[0] == "user" }
        val turnStartMs = if (inFlight && startIdx >= 0)
            recs[startIdx].getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ts ->
                SilentlyTry.logged("SshAi-GeminiSpec", "parse turn-start ts") {
                    java.time.Instant.parse(ts).toEpochMilli()
                }
            } else null
        // Output tokens of any replies recorded so far this turn (0 while still
        // awaiting the first reply — the live stream feeds tokens for our own turn).
        val tokens = if (inFlight && startIdx >= 0)
            recs.drop(startIdx + 1).filter { it[0] == "gemini" }
                .sumOf { it.getOrNull(2)?.toLongOrNull() ?: 0L }
        else 0L
        return TurnSignals(
            inFlight = inFlight,
            thinking = thinking,
            turnStartMs = turnStartMs,
            tokens = tokens,
            // The model having replied IS the definitive completion for Gemini —
            // the same record that clears inFlight above. Leaving this unset meant
            // the stuck-turn reconcile, which is gated on turnComplete, was
            // structurally dead for every Gemini chat: exactly the condition that
            // produced the unstoppable spinner on Claude (2026-07-29).
            turnComplete = last[0] == "gemini",
        )
    }

    /** FALLBACK ONLY: a `user`-last record with no reply, frozen longer than this,
     *  is treated as an abandoned/wedged turn (cleared). */
    private val AWAIT_STALE_MS = 12 * 60_000L
}

private object GeminiTopbarUi : AgentTopbarUi {
    // Now wired up to the static alias map probeAvailableModels
    // returns (auto / pro / flash / flash-lite). We pass the picked
    // alias straight to `gemini --model <alias>`.
    //
    // Fallback chain for the label: user's explicit pick → model the
    // saved session was running on → model the live session reported
    // → CLI's default. If all four are null we show the agent name
    // ("Gemini CLI") rather than nothing — this matches Codex's
    // behaviour now that we ship a real picker.
    override fun displayLabel(state: TopbarModelState): String? {
        val alias = state.selectedModel
            ?: state.sessionInitialModel
            ?: state.observedModel
            ?: state.defaultModel
        if (!alias.isNullOrBlank()) {
            // Prefer the human-readable label from the alias table; otherwise
            // prettify the raw model id (resumed chats surface the concrete
            // `gemini-3-flash-preview`, which `prettyModel` turns into the
            // short "Gemini 3 Flash" so the chip reads like Claude's "Opus 4.8"
            // instead of a long lowercase slug).
            return state.availableModels[alias] ?: prettyModel(alias)
        }
        // Nothing picked. Show the FIRST item in the catalog as the
        // default — that's `Auto` in both the REST result and the
        // local fallback. NEVER fall back to `agentDisplayName`
        // ("Gemini CLI") — that's not a model and ends up in the
        // picker label which is incorrect (the user noted exactly
        // this on the previous build).
        return state.availableModels.entries.firstOrNull()?.value
    }

    /**
     * Second line of the topbar chip — mirrors Claude/Codex's
     * "model name / how-it-runs" two-line shape. Gemini has NO reasoning-effort
     * knob (verified: no CLI flag, no settings key, no session field), so the
     * sub-line instead names the model-selection MODE: "Auto" when the user
     * hasn't pinned a model (Gemini auto-routes per turn). Only shown when line
     * 1 already displays a resolved model — otherwise it would just duplicate
     * the bare "Auto" headline a fresh chat shows on line 1.
     */
    override fun reasoningLabel(state: TopbarModelState): String? {
        val userPickedModel = !state.selectedModel.isNullOrBlank() && state.selectedModel != "auto"
        if (userPickedModel) return null
        val resolvedOnLine1 = !state.sessionInitialModel.isNullOrBlank() && state.sessionInitialModel != "auto"
        return if (resolvedOnLine1) "Auto" else null
    }

    /** Raw Gemini model id → short display name, so a resumed chat's concrete
     *  `gemini-3-flash-preview` reads as "Gemini 3 Flash" (like "Opus 4.8")
     *  rather than a long slug. Unknown ids fall through unchanged (honest). */
    private fun prettyModel(model: String): String = when (model) {
        "auto" -> "Auto"
        "gemini-3-flash-preview", "gemini-3-flash" -> "Gemini 3 Flash"
        "gemini-3-pro-preview", "gemini-3-pro" -> "Gemini 3 Pro"
        "gemini-2.5-flash" -> "Gemini 2.5 Flash"
        "gemini-2.5-flash-lite" -> "Gemini 2.5 Flash-Lite"
        "gemini-2.5-pro" -> "Gemini 2.5 Pro"
        else -> model
    }

    override fun isMenuEnabled(state: TopbarModelState): Boolean =
        state.availableModels.isNotEmpty() && !state.modelsProbing

    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> =
        state.availableModels.map { (alias, label) ->
            ModelMenuItem(display = label, storedValue = alias)
        }
}
