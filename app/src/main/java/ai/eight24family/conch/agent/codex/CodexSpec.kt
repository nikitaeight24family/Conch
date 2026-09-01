package ai.eight24family.conch.agent.codex

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-CLI spec for **OpenAI Codex CLI** (`codex` binary, npm `@openai/codex`).
 *
 * Authority for every flag and event type: OpenAI Codex CLI reference,
 * non-interactive docs, and discussion #3827, captured in
 * `docs/cli-research-2026-05.md` §2.
 *
 * **Why this was rewritten.** The user reported "model list doesn't load,
 * resume broken, new chat doesn't start". Root causes from the research:
 *
 *  1. The JSON event schema changed in 0.125+ from
 *     `session_meta`/`response_item`/`event_msg` to
 *     `thread.started`/`turn.*`/`item.*`. The old parser saw zero events it
 *     understood → empty chat bubble → "didn't start".
 *  2. The old build path used `--full-auto` for AUTO mode, which is
 *     **deprecated** — replaced by `--ask-for-approval never --sandbox
 *     workspace-write`.
 *  3. The model discovery was a brittle regex hunt across `codex exec
 *     --help` output (clap formatting varies); the right source is
 *     `~/.codex/config.toml` `[profiles.<name>].model` + bundled defaults
 *     `gpt-5.5 / 5.4 / 5.4-mini / 5.3-codex / 5.3-codex-spark / 5.2`.
 *
 * **Headless invocation shape** we build:
 * ```
 * printf '%s' "$PROMPT" | codex exec - [resume <SESSION_ID>]
 *     --json --skip-git-repo-check
 *     [--ask-for-approval untrusted|never --sandbox read-only|workspace-write
 *      | --dangerously-bypass-approvals-and-sandbox]
 *     [--model <name>] -C "$CWD"
 *     2>&1
 * ```
 *
 * The trailing `-` after `exec` is the documented stdin marker — without it
 * Codex expects a positional prompt argument and the piped stdin is
 * ignored. (This was the silent symptom of the user's broken chat: the
 * pipe contained the prompt but Codex never read it.)
 */
object CodexSpec : AgentCliSpec {

    override val agent = Agent.CODEX
    override val displayName = "Codex CLI"
    override val cliCommand = "codex"
    override val npmPackage = "@openai/codex"
    override val guardHarnessId = "codex"
    override val iconRes = ai.eight24family.conch.R.drawable.ic_agent_codex

    override val supportsSubagents = false
    override val supportsCustomSlashCommands = false  // CLI has /status etc. but no user-authored
    override val supportsResume = true
    override val supportsPreSetSessionId = false  // CLI assigns the UUID; we read thread.started.thread_id

    /**
     * **moltbot pattern.** Default `codex login` (no `--device-auth`) listens
     * on `localhost:1455` and prints an OAuth URL whose `redirect_uri` is
     * `http://localhost:1455/auth/callback`. The user opens the URL on the
     * phone, signs in, OpenAI 302s them to that callback — the phone browser
     * shows "Connection refused" but the URL bar now holds the full callback
     * with `?code=…&state=…`, which we curl on the SERVER through the pooled
     * SSH so the CLI's own listener completes the exchange.
     *
     * NOT `--device-auth`: OpenAI workspace admins can disable it (and have,
     * in this user's experience). The localhost-callback flow doesn't depend
     * on workspace policy. `BROWSER=true` suppresses xdg-open on the headless
     * box.
     */
    override val oauthLoginCommand = "BROWSER=true codex login"

    override val memoryFilename = "AGENTS.md"
    override val memoryGlobalPath = "\$HOME/.codex/AGENTS.md"
    override val memoryGlobalDisplay = "~/.codex/AGENTS.md"

    /** Drives the phone's local model via its custom provider — see
     *  [localProviderArgs]. */
    override val supportsLocalModel = true

    /**
     * The phone's own model, as a Codex model choice.
     *
     * A `local:<id>` model value routes the turn to the LOCAL inference
     * engine ([ai.eight24family.conch.linux.LocalLlmEngine], llama-server on
     * loopback) through Codex's own custom-provider mechanism — so the AGENT
     * is still the real Codex CLI with its real tools, sessions and sandbox;
     * only the brain is the phone's. `wire_api` MUST be "responses":
     * 0.151 dropped "chat" outright, and llama-server serves `/v1/responses`
     * (both verified on the owner's phone, 2026-08-31 — including a full
     * tool-use turn where Codex + Qwen3-1.7B created a file via the shell).
     */
    internal fun localProviderArgs(): String = listOf(
        "model_providers.conchlocal.name=\"local\"",
        "model_providers.conchlocal.base_url=\"${ai.eight24family.conch.linux.LocalLlmEngine.BASE_URL}/v1\"",
        "model_providers.conchlocal.wire_api=\"responses\"",
        "model_provider=\"conchlocal\"",
        // Tell codex the engine's REAL context so it compacts before the
        // wall instead of hitting it: without this it assumes a cloud-sized
        // window, and a session that outgrew the engine died with a raw
        // `400 request (8257 tokens) exceeds context` on resume, twice per
        // send, forever (owner's screenshot, 2026-09-01). Slightly under the
        // engine's -c so the reply has room to stream.
        "model_context_window=${ai.eight24family.conch.linux.LocalLlmEngine.CTX_TOKENS - 1024}",
        // No view_image tool against the local engine: codex puts the image
        // INSIDE the tool-call output, and llama-server's /v1/responses
        // requires tool output to be plain text — every view_image call died
        // as a 400 spam-loop ("Output of tool call should be 'Input text'",
        // 2026-09-01). The photo itself still reaches the model structurally
        // via turn/start's image paths, so nothing is lost.
        "features.view_image=false",
    ).joinToString("") { " -c ${shellEscape(it)}" }

    override fun buildExecCommand(input: ExecInput): String {
        val escapedText = shellEscape(input.text)
        val localModel = input.model
            ?.takeIf { it.startsWith(ai.eight24family.conch.linux.LocalLlm.MODEL_ARG_PREFIX) }
            ?.removePrefix(ai.eight24family.conch.linux.LocalLlm.MODEL_ARG_PREFIX)
        val providerArg = if (localModel != null) localProviderArgs() else ""
        val modelArg = (localModel ?: input.model)?.takeIf { it.isNotBlank() }
            ?.let { " --model ${shellEscape(it)}" } ?: ""
        // Codex's reasoning effort lives behind the generic `-c key=value`
        // config override. Quote the value so a future enum widening
        // ("auto"/"adaptive") doesn't accidentally hit shell glob/IFS rules.
        val reasoningArg = input.reasoningEffort?.takeIf { it.isNotBlank() }
            ?.let { " -c model_reasoning_effort=${shellEscape(it)}" } ?: ""
        // Codex's non-deprecated flag set (0.130+):
        //   SAFE  → ask for everything outside read-only, only read-only sandbox.
        //           In headless this still doesn't hang because read-only has
        //           nothing TO approve (no writes possible).
        //   AUTO  → never ask + workspace-write — model writes inside cwd
        //           freely, can't touch anything outside. "Auto-edit" semantics.
        //   YOLO  → bypass approvals AND sandbox (most permissive — Codex's
        //           equivalent of Claude's --dangerously-skip-permissions).
        //
        // The old `--full-auto` flag is deprecated and replaced by this pair
        // of approval/sandbox flags.
        // ⛔ THESE ARE NOT DECORATIVE — THEY WERE BROKEN, AND ONLY THE SAFE ONES.
        //
        // Replayed through the installed parser on 2026-08-27 (codex-cli
        // 0.149.1):
        //   codex exec --ask-for-approval untrusted --sandbox read-only
        //     -> error: unexpected argument '--ask-for-approval' found   (exit 2)
        //   codex --ask-for-approval untrusted
        //     -> error: invalid value 'untrusted'  [possible values: on-request, never]
        //   codex exec --dangerously-bypass-approvals-and-sandbox
        //     -> ACCEPTED
        //
        // So SAFE and AUTO died at parse time on this path while YOLO — the one
        // mode that grants everything — kept working. `--ask-for-approval` is a
        // TOP-LEVEL flag now, and in a non-interactive `exec` run there is
        // nobody to answer an approval request anyway: the sandbox IS the
        // policy here. read-only for SAFE, workspace-write for AUTO.
        //
        // The primary Codex path (app-server, CodexAppServerWire.approvalToPolicy)
        // is unaffected — it already sends the current `on-request`/`never`
        // policies over JSON-RPC. This is the one-shot fallback, which is what
        // runs when the app-server handshake fails, i.e. exactly on the older
        // or stranger installs that need it most.
        //
        // Flags are declared once in spec/CliContract and audited by
        // agent/CliFlagAudit after every install; keep the two in step.
        val approvalArg = when (input.approvalMode) {
            // No plan mode in Codex — read-only is the honest neighbour.
            AgentApprovalMode.PLAN,
            AgentApprovalMode.SAFE -> " --sandbox read-only"
            AgentApprovalMode.AUTO -> " --sandbox workspace-write"
            AgentApprovalMode.YOLO -> " --dangerously-bypass-approvals-and-sandbox"
        }
        // Resume is its own subcommand under `exec`, NOT a `--resume` flag.
        // `codex exec --resume <id>` errors with "unexpected argument
        // '--resume' found". Resume id is the `thread_id` from
        // `thread.started` in the new schema (or `payload.id` from
        // `session_meta` in the old schema) — the FILENAME UUID is
        // decorative (openai/codex discussion #3827).
        return if (input.resumeId != null) {
            val rid = shellEscape(input.resumeId)
            "printf '%s' $escapedText | codex exec resume $rid - " +
                "--json --skip-git-repo-check$providerArg$approvalArg$modelArg$reasoningArg 2>&1"
        } else {
            "printf '%s' $escapedText | codex exec - " +
                "--json --skip-git-repo-check$providerArg$approvalArg$modelArg$reasoningArg 2>&1"
        }
    }

    override fun parseStreamLine(line: String): List<AgentMessage> =
        CodexMessageParser.parse(line, turnTag = "")

    override fun parseStreamLine(line: String, turnTag: String): List<AgentMessage> =
        CodexMessageParser.parse(line, turnTag = turnTag)

    /**
     * Robust to both old and new schemas. Old wrote first line as
     * `{"type":"session_meta","payload":{"id":"..."}}`; new writes
     * `{"type":"thread.started","thread_id":"..."}`. We try both — and
     * fall back to the UUID baked into the filename if neither is present.
     */
    override val listSessionsScript: String? = """
find ~/.codex -type f -name '*.jsonl' 2>/dev/null | while IFS= read -r f; do
  [ -f "${'$'}f" ] || continue
  meta=${'$'}(head -n 1 "${'$'}f" 2>/dev/null)
  id=${'$'}(printf '%s' "${'$'}meta" | grep -oE '"thread_id"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  if [ -z "${'$'}id" ]; then
    id=${'$'}(printf '%s' "${'$'}meta" | grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  fi
  if [ -z "${'$'}id" ]; then
    base="${'$'}{f##*/}"
    base="${'$'}{base%.jsonl}"
    id=${'$'}(printf '%s' "${'$'}base" | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | tail -1)
    [ -z "${'$'}id" ] && id="${'$'}base"
  fi
  mtime=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || stat -f %m "${'$'}f" 2>/dev/null)
  size=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || stat -f %z "${'$'}f" 2>/dev/null)
  # Model extraction — first 20 lines of the session JSONL usually
  # carry the model id in session_meta (old schema) or turn events
  # (new schema). Excludes model_provider so 'openai' doesn't leak
  # as a model name.
  model=${'$'}(head -n 20 "${'$'}f" 2>/dev/null | grep -oE '"model"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  # Reasoning effort extraction — codex writes `"reasoning_effort":"X"`
  # in the `settings` object of every turn. First-match wins (same as
  # model). Lets the topbar render the actual effort the chat was
  # running on, instead of falling back to the user's config.toml
  # global which may not match.
  reasoning=${'$'}(head -n 20 "${'$'}f" 2>/dev/null | grep -oE '"reasoning_effort"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"${'$'}/\1/')
  # Preview candidates: cover both schemas — old user_message events,
  # old response_item.message role=user, new item.completed/agent_message.
  # The spec.extractSessionPreview filters synthetic injections client-side.
  candidates=${'$'}(grep -E '("role":"user")|("type":"item.completed")' "${'$'}f" 2>/dev/null | head -n 8 | tr '\t' ' ' | tr '\n' '\036')
  # 7-col contract: id, mtime, path, model, reasoning, size, preview.
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}id" "${'$'}mtime" "${'$'}f" "${'$'}model" "${'$'}reasoning" "${'$'}size" "${'$'}candidates"
done | sort -t'	' -k2 -rn | head -500
""".trimIndent()

    override fun extractSessionPreview(rawPreview: String): String {
        if (rawPreview.isBlank()) return ""
        // ASCII Record Separator (U+001E) joins multiple candidate user
        // lines emitted by listSessionsScript's `tr '\n' '\036'`.
        val candidates = if (rawPreview.contains('\u001E')) rawPreview.split('\u001E') else listOf(rawPreview)
        for (c in candidates) {
            val msgs = CodexMessageParser.parse(c)
            val userText = msgs.filterIsInstance<AgentMessage.UserText>()
                .map { it.text }
                .firstOrNull { it.isNotBlank() && !CodexMessageParser.isSyntheticUserText(it) }
            if (!userText.isNullOrBlank()) {
                return userText.replace(Regex("\\s+"), " ").trim().take(140)
            }
        }
        return ""
    }

    override val statusProbeLines: String = """
echo "codex_inst=${'$'}(command -v codex >/dev/null 2>&1 && echo y || echo n)"
echo "codex_ver=${'$'}(conch_ver codex codex)"
echo "codex_latest=${'$'}(conch_latest codex @openai/codex)"
CM=""
# codex's OWN verdict. `codex login status` prints "Logged in using ChatGPT"
# (OAuth) / "Logged in using an API key" / "Not logged in", and EXITS 0 when
# credentials are present (OpenAI docs). 2) auth.json shape checked ALWAYS
# (not only when the command is silent): login-status wording drifts across
# versions, so a present tokens{}/api key in the file counts on its own. 3)
# exit 0 with no parsed method (creds in the OS keychain, not auth.json, and
# wording we don't recognise) → trust the exit code, default ChatGPT.
# `timeout` guards the shared probe from hanging.
CS=${'$'}(conch_timeout 8 codex login status 2>&1); CRC=${'$'}?
case "${'$'}CS" in *ChatGPT*|*chatgpt*) CM="${'$'}CM chatgpt";; esac
case "${'$'}CS" in *"API key"*|*"api key"*|*"API-key"*) CM="${'$'}CM api";; esac
# auth.json shape — always, de-duped so a method already found isn't repeated.
if [ -f ~/.codex/auth.json ]; then
  case " ${'$'}CM " in *" chatgpt "*) ;; *) grep -qE '"tokens"[[:space:]]*:' ~/.codex/auth.json 2>/dev/null && CM="${'$'}CM chatgpt";; esac
  case " ${'$'}CM " in *" api "*) ;; *) grep -qE '"OPENAI_API_KEY"[[:space:]]*:[[:space:]]*"' ~/.codex/auth.json 2>/dev/null && CM="${'$'}CM api";; esac
fi
# Logged in (exit 0) but nothing parsed ⇒ creds present in a form we couldn't
# classify (OS keychain / new wording). Don't render a false "log in": ChatGPT
# is the OAuth sign-in, the overwhelmingly common case.
if [ "${'$'}CRC" = "0" ] && [ -z "${'$'}(echo ${'$'}CM | tr -d ' ')" ]; then CM="chatgpt"; fi
if [ -n "${'$'}CODEX_API_KEY" ] || [ -n "${'$'}OPENAI_API_KEY" ] || grep -qsE '^[[:space:]]*(export[[:space:]]+)?(OPENAI|CODEX)_API_KEY=' ~/.bashrc ~/.profile ~/.bash_profile ~/.env 2>/dev/null; then case " ${'$'}CM " in *" api "*) ;; *) CM="${'$'}CM api";; esac; fi
echo "codex_methods=${'$'}(echo ${'$'}CM | tr ' ' ',')"
case "${'$'}CS" in
  *ChatGPT*) echo "codex_active=chatgpt";;
  *"API key"*|*"api key"*) echo "codex_active=api";;
  *) case " ${'$'}CM " in *" chatgpt "*) echo "codex_active=chatgpt";; *" api "*) echo "codex_active=api";; *) echo "codex_active=";; esac;;
esac
""".trimIndent()

    /**
     * Probe available models from codex CLI's OWN local cache.
     *
     * `~/.codex/models_cache.json` is the file codex itself downloads
     * from OpenAI's backend on login/start (it has `fetched_at` and
     * `etag` fields — standard HTTP cache). Its `models[]` array is
     * the authoritative list of slugs the CLI will accept in `--model`,
     * with the user-facing `display_name` baked in.
     *
     * Why this beats every previous approach:
     *  - **`codex --help` clap parsing** — modern codex doesn't list
     *    enumerated values for `--model`; the flag takes free text.
     *  - **Binary grep of the JS bundle** — caught random strings
     *    that LOOKED like model ids but weren't (`codex-cli`,
     *    `codex-path`, old removed model names). Garbage in dropdown.
     *  - **`/v1/models` API call** — returned every OpenAI model
     *    ever published (including embeddings, whisper, deprecated
     *    legacy davinci), then we had to filter heuristically.
     *    Also doesn't work for ChatGPT OAuth users (no `sk-` key).
     *
     * The cache file is what codex ITSELF considers the truth — by
     * definition every slug there is acceptable to the CLI, and
     * nothing else is.
     *
     * Fallback: if the cache file doesn't exist (fresh codex install,
     * user hasn't logged in yet), we read the model field out of
     * `config.toml` as a one-entry list so the topbar at least shows
     * the configured default instead of going empty.
     */
    override suspend fun probeAvailableModels(exec: AgentExec): Map<String, String> {
        // Single SSH round trip: dump the cache file (authoritative)
        // and as a fallback the top-level `model =` line from
        // config.toml. Separator marker so we can split deterministically.
        val script = """
            echo --CACHE--
            cat ${'$'}HOME/.codex/models_cache.json 2>/dev/null || true
            echo --CONFIG--
            cat ${'$'}HOME/.codex/config.toml 2>/dev/null || true
            echo --END--
        """.trimIndent()
        val raw = exec.exec("bash -lc " + shellEscape(script)).orEmpty()
        android.util.Log.d("Conch-Models", "codex probe output (${raw.length}B)")

        val cacheChunk = raw.substringAfter("--CACHE--", "").substringBefore("--CONFIG--", "")
        val configChunk = raw.substringAfter("--CONFIG--", "").substringBefore("--END--", "")

        val ordered = LinkedHashMap<String, String>()

        // Primary source: parse the cache JSON. Iterate `models[]`
        // and read `slug` (the literal value passed to `--model`)
        // and `display_name` (the human-readable label for the
        // dropdown — codex itself controls this string, so it
        // matches what `codex` UI shows).
        val reasoningMap = mutableMapOf<String, ModelReasoningInfo>()
        runCatching {
            val root = json.parseToJsonElement(cacheChunk.trim()).jsonObject
            val list = root["models"]?.jsonArray ?: JsonArray(emptyList())
            for (entry in list) {
                val o = SilentlyTry.logged("Conch-CodexSpec", "cast model entry") { entry.jsonObject } ?: continue
                val slug = o["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: continue
                // Skip models codex explicitly marks as not user-pickable.
                // Different cache schemas use `visibility` (string),
                // `hidden` (bool), or `available` (bool). Treat any of
                // them as veto.
                val visibility = o["visibility"]?.jsonPrimitive?.contentOrNull
                if (visibility == "hidden" || visibility == "deprecated" ||
                    visibility == "internal" || visibility == "private"
                ) continue
                val hidden = SilentlyTry.loggedOrElse("Conch-CodexSpec", "read hidden flag", false) { o["hidden"]?.jsonPrimitive?.contentOrNull == "true" }
                if (hidden) continue
                val available = o["available"]?.jsonPrimitive?.contentOrNull
                if (available == "false") continue

                val display = o["display_name"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: slug

                // Drop models intended for codex's own internal
                // automated flows (e.g. "Codex Auto Review" — the
                // model behind PR auto-review). They appear in the
                // cache because the CLI talks to them under the
                // hood, but the user can't sensibly pick them from
                // a chat. Match on display_name and slug both, case
                // insensitive — codex names this family with
                // "auto review" / "auto-review" tokens.
                val internalToken = "auto review|auto-review|autoreview".toRegex(
                    RegexOption.IGNORE_CASE
                )
                if (internalToken.containsMatchIn(display) ||
                    internalToken.containsMatchIn(slug)
                ) continue

                ordered[slug] = display

                // Parse `default_reasoning_level` +
                // `supported_reasoning_levels` per model. Schema (per
                // observed cache dump):
                //   "default_reasoning_level": "xhigh",
                //   "supported_reasoning_levels": [
                //     {"effort": "low", "description": "Fast responses..."},
                //     {"effort": "medium", "description": "..."}, ...
                //   ]
                val defaultEffort = o["default_reasoning_level"]
                    ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val levelsArr = SilentlyTry.logged("Conch-CodexSpec", "read supported_reasoning_levels") {
                    o["supported_reasoning_levels"]?.jsonArray
                }
                val levels = levelsArr?.mapNotNull { lvl ->
                    val obj = SilentlyTry.logged("Conch-CodexSpec", "cast reasoning level entry") { lvl.jsonObject } ?: return@mapNotNull null
                    val effort = obj["effort"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    ReasoningLevel(
                        effort = effort,
                        displayName = reasoningDisplayName(effort),
                        description = desc,
                    )
                }.orEmpty()
                if (!defaultEffort.isNullOrBlank() && levels.isNotEmpty()) {
                    reasoningMap[slug] = ModelReasoningInfo(
                        defaultEffort = defaultEffort,
                        levels = levels,
                    )
                }
            }
            // Snapshot cache. Safe to overwrite — singleton spec held by
            // AgentSpecRegistry, no per-chat state. Synchronized swap so
            // a concurrent UI read can't observe a half-populated map.
            synchronized(reasoningCacheLock) {
                reasoningCache = reasoningMap.toMap()
            }
            android.util.Log.d(
                "Conch-Models",
                "codex cache parsed ${ordered.size} models from models_cache.json (${reasoningMap.size} with reasoning info)",
            )
        }.onFailure {
            android.util.Log.w(
                "Conch-Models",
                "codex cache parse failed (will fall back to config.toml): ${it.message}",
            )
        }

        // Fallback: cache file absent or unparseable. Surface the
        // user's configured default from config.toml so the picker
        // isn't empty.
        if (ordered.isEmpty()) {
            Regex("(?m)^\\s*model\\s*=\\s*\"([^\"]+)\"")
                .find(configChunk)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { ordered[it] = it }
            android.util.Log.d(
                "Conch-Models",
                "codex cache empty; config.toml fallback yielded ${ordered.size} models",
            )
        }

        return ordered
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Reasoning info cache populated by `probeAvailableModels`. Keyed
    // by slug. Singleton state is fine here — the cache reflects the
    // remote codex install's `models_cache.json`, identical for every
    // chat on that server. UI reads via `reasoningInfoFor`.
    private val reasoningCacheLock = Any()
    @Volatile private var reasoningCache: Map<String, ModelReasoningInfo> = emptyMap()

    override fun reasoningInfoFor(slug: String): ModelReasoningInfo? =
        reasoningCache[slug]

    /**
     * Dropdown label for a codex effort — the RAW token from codex's own
     * models_cache.json (`low`/`medium`/`high`/`xhigh`). The cache carries no
     * display names (only effort + description), so any prettier word here
     * would be OUR invention — the old "Extra high"/"Low" mapping was exactly
     * that hardcode. The per-level description from the cache renders
     * alongside, so the menu stays self-explanatory.
     */
    private fun reasoningDisplayName(effort: String): String = effort

    /**
     * Top-level `model = "..."` in `~/.codex/config.toml` — the model
     * codex CLI uses when no `--model` flag is passed. Returned to the
     * topbar so a chat that hasn't had an explicit pick still shows
     * the real model it's running on instead of a placeholder.
     */
    override suspend fun probeDefaultModel(exec: AgentExec): String? {
        val raw = exec.exec(
            "bash -lc " + shellEscape("cat ${'$'}HOME/.codex/config.toml 2>/dev/null || true")
        ).orEmpty()
        return Regex("(?m)^\\s*model\\s*=\\s*\"([^\"]+)\"")
            .find(raw)?.groupValues?.getOrNull(1)
    }

    override suspend fun probeDefaultReasoning(exec: AgentExec): String? {
        val raw = exec.exec(
            "bash -lc " + shellEscape("cat ${'$'}HOME/.codex/config.toml 2>/dev/null || true")
        ).orEmpty()
        return Regex("(?m)^\\s*model_reasoning_effort\\s*=\\s*\"([^\"]+)\"")
            .find(raw)?.groupValues?.getOrNull(1)
    }

    override val customCommandsScript: String? = null  // Codex has no user-authored slash commands
    override fun parseCustomCommands(rawOutput: String): List<SlashCommand> = emptyList()

    /** Codex resumes by global thread id — cwd doesn't matter for finding
     *  the session. Hence no backfill needed. */
    override fun cwdBackfillScript(resumeId: String): String? = null

    /**
     * Codex has no Claude-style alias indirection — `selectedModel`
     * IS the literal OpenAI model name passed as `codex exec --model
     * <X>`. The topbar's display falls through a chain of
     * progressively-older / less-specific sources until something
     * non-blank is found, ending at the bare agent name as a final
     * safety net. There is intentionally NO `default` placeholder —
     * OpenAI's catalog doesn't include a model named "default", so
     * surfacing that string lies to the user.
     */
    override val topbarUi: AgentTopbarUi = CodexTopbarUi

    override val disableApprovalsPrompt: String = """
        Stop asking me to approve tool calls — relax your own approval policy and sandbox, then continue with whatever you were working on before this message.

        Step 1: Edit ~/.codex/config.toml (create it if missing) so it contains, at the top level:
        approval_policy = "never"
        sandbox_mode = "danger-full-access"
        If the file already has other keys, merge — do NOT overwrite the rest of the config.

        Step 2: Verify by reading the file back. Both keys must be present with those exact values.

        Step 3: Resume the task you were doing right before I sent this message. If you were about to request approval for a command, just run it. Don't pause to reconfirm.
    """.trimIndent()

    // ──────── Mirror turn-state ────────

    /**
     * Project each turn-boundary / token line to `[marker, isoTs, lastOutTokens]`.
     *
     * Codex has NO `stop_reason`; its turns are bracketed by explicit lifecycle
     * events (verified on the server 2026-06-27, 27 task_started/task_complete
     * pairs, file ends on task_complete):
     *   • OLD schema (`type:"event_msg"`): `payload.type` = `task_started`
     *     (turn begins) … work events … `task_complete` (turn done). Token usage
     *     rides `token_count` → `payload.info.last_token_usage.output_tokens`
     *     (per-step; sum since the last start = the turn's output tokens).
     *   • NEW schema (0.125+, `type:"turn.started"`/`"turn.completed"`/
     *     `"turn.failed"` at top level): same idea, included for future-proofing —
     *     no such files exist on the server yet, so token paths there are
     *     best-effort.
     * `$m` normalizes both schemas to one marker string. ISO `.timestamp` is
     * top-level on every line.
     */
    
    /**
     * DEFINITIVE Codex turn state from the lifecycle markers — second-accurate,
     * no timeout. The LAST start/done boundary in the window decides:
     *   • last boundary is `task_started`/`turn.started`  → WORKING
     *   • last boundary is `task_complete`/`turn.*`(done) → DONE
     *   • NO boundary in the tail window (a turn so long its start scrolled past
     *     tail -n 400) → the tail is all work events → WORKING, cleared only if
     *     the file's been frozen unusually long (wedged/dead).
     * thinking == inFlight (Codex applies effort across the whole turn; there's no
     * file-visible think-vs-tool split worth gating on). Codex approvals are driven
     * through our own channel, not the file, so waitingForUser stays false here.
     */
    /**
     * Field layout, read by index in [inferTurnState]:
     *   0 marker · 1 timestamp · 2 outputTokens
     * where marker = `payload.type` for an `event_msg`, else the top-level
     * `type`, kept only when it is one of the turn-boundary / token markers.
     */
    override fun projectTurnStateRecords(lines: Sequence<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (line in lines) {
            val t = line.trim()
            if (t.length < 2 || t[0] != '{') continue
            val obj = runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() ?: continue
            val topType = obj["type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: continue
            val payload = runCatching { obj["payload"]?.jsonObject }.getOrNull()
            val marker = if (topType == "event_msg") {
                payload?.get("type")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            } else {
                topType
            }
            if (marker !in TURN_STATE_MARKERS) continue
            val ts = obj["timestamp"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            // jq's `//` chain: first NON-NULL wins, else 0.
            val tokens = runCatching {
                payload?.get("info")?.jsonObject?.get("last_token_usage")?.jsonObject
                    ?.get("output_tokens")?.jsonPrimitive?.content
            }.getOrNull()
                ?: runCatching { payload?.get("usage")?.jsonObject?.get("output_tokens")?.jsonPrimitive?.content }.getOrNull()
                ?: runCatching { obj["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.content }.getOrNull()
                ?: "0"
            out += listOf(marker, ts, tokens)
        }
        return out
    }

    override fun inferTurnState(records: List<List<String>>, frozenForMs: Long?): TurnSignals {
        val recs = records.filter { it.isNotEmpty() && it[0].isNotBlank() }
        if (recs.isEmpty()) return TurnSignals()
        val lastBoundaryIdx = recs.indexOfLast { it[0] in CODEX_START_MARKERS || it[0] in CODEX_DONE_MARKERS }
        if (lastBoundaryIdx < 0) {
            // No turn boundary in the tail — a turn long enough to push its
            // task_started out of the 400-line window. The window is all work
            // events ⇒ still running, unless frozen past the staleness guard.
            val working = frozenForMs == null || frozenForMs < AWAIT_STALE_MS
            val toks = recs.filter { it[0] == "token_count" }
                .sumOf { it.getOrNull(2)?.toLongOrNull() ?: 0L }
            return TurnSignals(
                inFlight = working,
                thinking = working,
                turnStartMs = null,
                tokens = if (working) toks else 0L,
            )
        }
        // Started with no matching completion ⇒ running — but bounded by the SAME
        // staleness guard as the no-boundary branch above. A `task_started` whose
        // `task_complete` never landed (the CLI was killed, the box rebooted, the
        // rollout was truncated) otherwise pins the thinking indicator on for the
        // life of the chat with nothing able to clear it. Same hole Claude had
        // (2026-07-29); closing it here too rather than waiting for the report.
        val inFlight = recs[lastBoundaryIdx][0] in CODEX_START_MARKERS &&
            (frozenForMs == null || frozenForMs < AWAIT_STALE_MS)
        val startIdx = recs.indexOfLast { it[0] in CODEX_START_MARKERS }
        val turnStartMs = if (inFlight && startIdx >= 0)
            recs[startIdx].getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ts ->
                SilentlyTry.logged("Conch-CodexSpec", "parse turn-start ts") {
                    java.time.Instant.parse(ts).toEpochMilli()
                }
            } else null
        // Turn output tokens = sum of per-step last_token_usage since the start
        // (total_token_usage is whole-session cumulative, not per-turn).
        val tokens = if (inFlight && startIdx >= 0)
            recs.drop(startIdx + 1).filter { it[0] == "token_count" }
                .sumOf { it.getOrNull(2)?.toLongOrNull() ?: 0L }
        else 0L
        return TurnSignals(
            inFlight = inFlight,
            thinking = inFlight,
            turnStartMs = turnStartMs,
            tokens = tokens,
            // A real completion marker in the file — the proof the stuck-turn
            // reconcile needs. Unset until now, which left the reconcile dead for
            // every Codex chat. Deliberately NOT set in the no-boundary branch
            // above: there the "done" verdict is a staleness GUESS, and
            // force-completing on a guess is what kills long research turns.
            turnComplete = recs[lastBoundaryIdx][0] in CODEX_DONE_MARKERS,
        )
    }

    /** Markers that OPEN a turn (work begins). */
    /** Every marker the turn-state projection keeps — the union of the boundary
     *  markers and `token_count`. The single source of truth for
     *  which records the turn detector considers. */
    private val TURN_STATE_MARKERS = setOf(
        "task_started", "task_complete", "token_count",
        "turn.started", "turn.completed", "turn.failed",
    )

    private val CODEX_START_MARKERS = setOf("task_started", "turn.started")

    /** Markers that CLOSE a turn (done / failed). */
    private val CODEX_DONE_MARKERS = setOf("task_complete", "turn.completed", "turn.failed")

    /** FALLBACK ONLY: no turn boundary visible in the tail window. Treat the
     *  running-work tail as working unless frozen longer than this. */
    private val AWAIT_STALE_MS = 12 * 60_000L
}

private object CodexTopbarUi : AgentTopbarUi {
    /**
     * Codex chain — five sources, first non-blank wins, then resolve
     * the slug to its human-readable `display_name` from the cache
     * (so the topbar shows "GPT-5.5" instead of "gpt-5.5"):
     *   1. selectedModel       — user's explicit pick this session.
     *   2. sessionInitialModel — model parsed from session JSONL during
     *                            discovery; set instantly when a session
     *                            row is tapped, BEFORE the chat opens.
     *   3. observedModel       — model reported by live session via
     *                            session_meta / thread.started events.
     *   4. defaultModel        — top-level `model = "..."` from
     *                            `~/.codex/config.toml`.
     *   5. agentDisplayName    — last resort. Better the bare agent
     *                            name than a placeholder that lies
     *                            ("default" / "loading…" / "pick a model"
     *                            are explicitly forbidden — the user
     *                            has shouted about each of them).
     */
    override fun displayLabel(state: TopbarModelState): String? {
        // The phone's own model reads as its NAME, not the `local:` slug the
        // plumbing carries — shared with every local-capable harness.
        ai.eight24family.conch.agent.spec.LocalTopbar.localDisplayLabel(state)?.let { return it }
        val slug = state.selectedModel?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialModel?.takeIf { it.isNotBlank() }
            ?: state.observedModel?.takeIf { it.isNotBlank() }
            ?: state.defaultModel?.takeIf { it.isNotBlank() }
            // New chat, no live probe + no `model =` in config.toml → show the
            // recommended (first cached) model instead of nothing. codex's
            // models_cache.json is ordered recommended-first, and that's what
            // codex runs when no --model is passed. Matches how Claude/Gemini
            // show a model on a fresh session; refines to observedModel once the
            // turn lands.
            ?: state.availableModels.keys.firstOrNull()
            ?: return null  // truly nothing cached yet — caller hides the picker
        // Map slug -> display_name when the cache has loaded; else
        // fall back to the raw slug. Both are accurate identifiers
        // of the running model, just one is prettier.
        return state.availableModels[slug] ?: slug
    }

    /**
     * Codex's full model list is fetched live (config.toml + `codex
     * --help` + binary grep + OpenAI `/v1/models`), so opening the
     * dropdown before that returns shows a near-empty list. Gate the
     * click until we have both a finished probe AND a non-empty
     * result — or, on the phone's own row, a downloaded local model
     * (those need no probe: they are files on this device).
     */
    override fun isMenuEnabled(state: TopbarModelState): Boolean =
        // The menu is tappable exactly when it will SHOW something. On the
        // phone's row it is always tappable: an empty state there renders an
        // explanation (download a model / sign in), not a dead button —
        // "the picker won't even open" with zero context was worse
        // (owner, 2026-09-01).
        state.serverId == ai.eight24family.conch.linux.LinuxSsh.SERVER_ID ||
            menuItems(state).isNotEmpty()

    /** Downloaded local models — shared across every local-capable harness. */
    private fun localModelItems(state: TopbarModelState): List<ModelMenuItem> =
        ai.eight24family.conch.agent.spec.LocalTopbar.localModelItems(state)

    /**
     * `availableModels` carries `slug -> display_name` from codex's
     * own `~/.codex/models_cache.json` — slug is what `--model`
     * wants, display_name is what we show in the dropdown.
     *
     * Keep insertion order (the cache's natural ordering — codex
     * already orders them sensibly: recommended first). NO sort by
     * key, which alphabetically would push `o3` to the top and bury
     * the recommended default.
     *
     * NO `default` entry — OpenAI's catalog has no model with that
     * literal name; surfacing one would let the user select a value
     * codex CLI would reject.
     *
     * Each item also carries its model's reasoning catalog (parsed
     * from `supported_reasoning_levels` + `default_reasoning_level`)
     * so the picker can show a reasoning submenu off each model
     * entry, matching codex CLI's own `/model` interactive flow.
     */
    override fun menuItems(state: TopbarModelState): List<ModelMenuItem> {
        // The phone's row leads with ITS OWN models and lists the cloud
        // catalog after them — the owner picked GPT-5 on a qwen session and
        // expected it to run (2026-09-01), which supersedes 08-31's "only
        // local models here". A cloud pick relaunches the channel onto
        // codex's normal provider (see AgentSessionCodexAppServer).
        val cloud = state.availableModels.entries.map { (slug, label) ->
            val info = state.reasoningCatalog[slug]
            ModelMenuItem(
                display = label,
                storedValue = slug,
                reasoning = info?.levels.orEmpty(),
                // The picker's "default" marker + no-pick slider position must be what
                // codex ACTUALLY runs — its config.toml `model_reasoning_effort`
                // (state.defaultReasoning) — NOT the model's catalog default_reasoning_
                // _level. GPT-5.5's catalog default is xhigh, but the user's config
                // pins medium, so the slider sat on xhigh while the CLI ran medium.
                // Catalog default is the honest fallback ONLY when config has no pin.
                defaultReasoning = state.defaultReasoning?.takeIf { it.isNotBlank() }
                    ?: info?.defaultEffort,
            )
        }
        val local = localModelItems(state)
        // On the phone's row the cloud catalog is offered ONLY when codex is
        // actually signed in there — otherwise every cloud pick is a dead end
        // by construction, and the picker must not sell dead ends.
        if (state.serverId == ai.eight24family.conch.linux.LinuxSsh.SERVER_ID &&
            !state.phoneCloudLoggedIn
        ) return local
        return local + cloud
    }

    /**
     * Sub-label rendered next to the model name in the topbar — the
     * current reasoning effort if one is pinned to the chat,
     * otherwise the model's default level (so the user always knows
     * which effort is in play).
     */
    override fun reasoningLabel(state: TopbarModelState): String? {
        // Priority — must match what codex actually runs:
        //   1. selectedReasoning — user's explicit pick (we send via -c).
        //   2. sessionInitialReasoning — what THIS chat's JSONL says it
        //      was running on. We pass it as -c on resume (see
        //      ChatViewModel modelOverride / reasoningEffortOverride
        //      seeding), so the chat continues with the same effort
        //      instead of jumping to config.toml on reopen.
        //   3. defaultReasoning — `model_reasoning_effort` from config.toml
        //      (codex's fallback when we don't pass -c — only relevant
        //      for fresh chats with no JSONL history yet).
        //   4. reasoningCatalog[slug].defaultEffort — model's intrinsic
        //      default from the cache (last-resort).
        //
        // CRITICAL: do NOT gate on `reasoningCatalog[slug] != null`.
        // The catalog only populates after the live models-cache probe
        // returns, which can take a second on a slow SSH link. The user
        // explicitly rejected the "shows model first, then loads, then
        // shows reasoning" chain — when we already know the effort from
        // the JSONL header (sessionInitialReasoning, set the instant a
        // session row is tapped), display it from frame zero with a
        // capitalized fallback ("Medium" / "High" / "Xhigh") and let
        // the catalog upgrade it to a friendlier display name silently
        // once it lands.
        val slug = state.selectedModel?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialModel?.takeIf { it.isNotBlank() }
            ?: state.observedModel?.takeIf { it.isNotBlank() }
            ?: state.defaultModel?.takeIf { it.isNotBlank() }
        // A local model's instant/thinking effort — shared across harnesses.
        ai.eight24family.conch.agent.spec.LocalTopbar.localReasoningLabel(state)?.let { return it }
        val info = slug?.let { state.reasoningCatalog[it] }
        val effort = state.selectedReasoning?.takeIf { it.isNotBlank() }
            ?: state.sessionInitialReasoning?.takeIf { it.isNotBlank() }
            ?: state.defaultReasoning?.takeIf { it.isNotBlank() }
            ?: info?.defaultEffort
            ?: return null
        // Render the RAW effort token exactly as codex's own data spells it
        // ("xhigh"/"high"/"medium" — models_cache.json supported_reasoning_levels
        // and the JSONL both carry lowercase tokens). The old capitalization
        // invented a word the CLI never shows. Raw is accurate AND stable from
        // frame zero through cache landing (no relabel flicker).
        return effort
    }
}
