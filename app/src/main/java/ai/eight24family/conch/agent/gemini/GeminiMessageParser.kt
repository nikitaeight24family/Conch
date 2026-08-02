package ai.eight24family.conch.agent.gemini

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.agent.spec.stableId
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for **Google Gemini CLI**'s `--output-format stream-json` JSONL.
 *
 * Authority: <https://raw.githubusercontent.com/google-gemini/gemini-cli/main/docs/cli/headless.md>
 * (research report §3C).
 *
 * Event shapes (one JSON object per line):
 * ```jsonc
 * {"type":"init","session_id":"...","model":"gemini-2.5-pro","timestamp":"..."}
 * {"type":"message","role":"assistant","content":"...","timestamp":"..."}
 * {"type":"tool_use","tool_id":"...","tool_name":"...","parameters":{...}}
 * {"type":"tool_result","tool_id":"...","status":"ok|error","output":"..."}
 * {"type":"error","message":"..."}
 * {"type":"result","status":"...","stats":{...}}
 * ```
 *
 * `session_id` lives in the first `init` event — capture it for `--resume`.
 *
 * **Note on streaming**: stream-json on Gemini was added relatively recently
 * (PR #10883). On older installs the flag is unknown and stdout falls back
 * to text mode — we surface those lines as `AgentMessage.Raw` so the user
 * at least sees the agent's reply (just without per-event semantics).
 */
object GeminiMessageParser {

    private val json get() = ParserHelpers.json

    /** CSI/SGR escape sequences. Gemini's crash output (e.g. the red
     *  FatalAuthenticationError) carries raw `[31m…` color codes; without
     *  stripping them the chat shows literal `[31m` garbage (the user hit this). */
    private val ANSI = Regex("\\[[0-?]*[ -/]*[@-~]")

    fun parse(line: String): List<AgentMessage> {
        val trimmed = ANSI.replace(line, "").trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("{")) {
            // Non-JSON stdout line. Filter the typical garbage that
            // Gemini CLI dumps when it crashes (Node stack traces,
            // gaxios HTTP error JSON pretty-prints, `config: { … }`
            // / `headers: { … }` / `error: undefined` partial
            // structures) so the chat thread isn't a wall of
            // unactionable noise.
            //
            // Per the auto-fix-errors invariant
            // (feedback_auto_fix_errors.md): show only what the user
            // can act on; everything else goes to Log.d.
            if (isLikelyNoise(trimmed)) {
                android.util.Log.d("SshAi-Gemini", "noise (suppressed): ${trimmed.take(200)}")
                return emptyList()
            }
            return listOf(AgentMessage.Raw(uuid(), trimmed))
        }

        // Fast path: token-stream parser for `type=="message"` events.
        // Gemini streaming hot path. See ClaudeMessageParser for the
        // fast-vs-slow path architecture rationale.
        ai.eight24family.conch.util.Tracing.section(
            ai.eight24family.conch.util.Tracing.Names.PARSER_FAST_PATH
        ) {
            parseFast(trimmed)?.let { return it }
        }

        return ai.eight24family.conch.util.Tracing.section(
            ai.eight24family.conch.util.Tracing.Names.PARSER_SLOW_PATH
        ) {
        val obj = SilentlyTry.logged("SshAi-GeminiParse", "parse jsonl line") { json.parseToJsonElement(trimmed).jsonObject }
            ?: return@section listOf(AgentMessage.Raw(uuid(), trimmed))

        // ── Saved session-FILE shape — DISTINCT from the live stream. Gemini
        //    persists a chat as a mutation log: `{"$set":{"messages":[…]}}`
        //    snapshots interleaved with bare `{type,content,id}` append records
        //    (the live stream instead emits `{"type":"message",…}`). When a
        //    resumed chat is re-hydrated we parse the FILE, so these shapes MUST
        //    yield the past turns — otherwise the chat opens empty. distinctBy
        //    {id} upstream collapses the repeated snapshots back to one each.
        (obj["\$set"] ?: obj["\$push"])?.let { mut ->
            val msgs = (mut as? JsonObject)?.get("messages")
            val arr: List<JsonObject>? = when (msgs) {
                is JsonArray -> msgs.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(msgs)
                else -> null
            }
            if (arr != null) return@section arr.flatMap { fileMessage(it) }
        }

        when (obj.string("type")) {
            "init" -> listOf(
                AgentMessage.System(
                    id = stableId(trimmed, "sys"),
                    subtype = "init",
                    sessionId = obj.string("session_id"),
                    model = obj.string("model"),
                    cwd = obj.string("cwd"),
                    version = obj.string("version"),
                    toolCount = 0,
                    raw = trimmed
                )
            )
            "message" -> {
                val role = obj.string("role") ?: return@section emptyList()
                val content = obj.string("content").orEmpty()
                if (content.isBlank()) return@section emptyList()
                when (role) {
                    "assistant", "model" -> listOf(AgentMessage.AssistantText(stableId(trimmed, "a"), content))
                    "user" -> {
                        // The CLI may echo our prompt back. AgentSession dedupes
                        // via `wasRecentlySent`, so we still surface it here.
                        listOf(AgentMessage.UserText(stableId(trimmed, "u"), content))
                    }
                    else -> emptyList()
                }
            }
            "tool_use" -> {
                val toolName = obj.string("tool_name") ?: obj.string("name") ?: "tool"
                val params = obj["parameters"]?.toString()
                    ?: obj["input"]?.toString()
                    ?: ""
                listOf(
                    AgentMessage.ToolUse(
                        id = obj.string("tool_id") ?: uuid(),
                        toolName = toolName,
                        input = params,
                    )
                )
            }
            "tool_result" -> {
                val status = obj.string("status").orEmpty()
                val output = obj.string("output") ?: obj["result"]?.toString() ?: ""
                listOf(
                    AgentMessage.ToolResult(
                        id = uuid(),
                        toolUseId = obj.string("tool_id").orEmpty(),
                        output = output,
                        isError = status.equals("error", ignoreCase = true) ||
                            status.equals("failed", ignoreCase = true)
                    )
                )
            }
            "error" -> {
                val msg = obj.string("message") ?: obj.string("error") ?: trimmed
                listOf(AgentMessage.Error(uuid(), msg))
            }
            "result" -> {
                val status = obj.string("status").orEmpty()
                val text = obj.string("response") ?: obj.string("result")
                // Per-turn usage line from `stats.models.*.tokens` — same
                // «tokens · in X · out Y» shape Claude and Codex emit, so
                // all three agents read alike (user, 2026-06-12).
                buildList {
                    add(AgentMessage.Result(uuid(), status, text))
                    statsNote(obj)?.let { add(it) }
                }
            }
            // Bare append records from the saved file (user / model turns).
            "user", "gemini", "model", "assistant" -> fileMessage(obj)
            else -> {
                // UNKNOWN type — never swallow a LIVE stream event (they all
                // carry `timestamp`); surface it generically. Records from a
                // SAVED session file (no timestamp — mutation-log noise like
                // `sessionTitle` snapshots) stay hidden: generic-noting those
                // would spray rows on every history re-hydration.
                val t = obj.string("type")
                if (t.isNullOrBlank() || obj["timestamp"] == null) {
                    android.util.Log.d("SshAi-GeminiParse", "non-live unknown type=$t (suppressed): ${trimmed.take(200)}")
                    emptyList()
                } else listOf(note(genericLabel(t, obj), detail = genericDetail(obj)))
            }
        }
        } // Tracing.section PARSER_SLOW_PATH
    }

    private fun JsonObject.string(key: String): String? =
        SilentlyTry.logged("SshAi-GeminiParse", "read string field '$key'") { this[key]?.jsonPrimitive?.contentOrNull }

    /**
     * Token usage from the `result` event's `stats` block:
     * `stats.models.<model-id>.tokens = {prompt, candidates, total, cached,
     * thoughts, tool}`. Summed across models (multi-model turns exist —
     * flash for tools + pro for the answer). Tolerant: any missing branch
     * → null → no note, never a crash.
     */
    private fun statsNote(obj: JsonObject): AgentMessage? {
        val models = SilentlyTry.logged("SshAi-GeminiParse", "read stats.models") {
            obj["stats"]?.jsonObject?.get("models")?.jsonObject
        } ?: return null
        var prompt = 0L; var candidates = 0L; var cached = 0L; var thoughts = 0L
        for ((_, m) in models.entries) {
            val tok = SilentlyTry.logged("SshAi-GeminiParse", "read model tokens") {
                (m as? JsonObject)?.get("tokens")?.jsonObject
            } ?: continue
            fun n(key: String): Long =
                (tok[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            prompt += n("prompt"); candidates += n("candidates")
            cached += n("cached"); thoughts += n("thoughts")
        }
        if (prompt == 0L && candidates == 0L) return null
        val parts = listOfNotNull(
            "in ${k(prompt)}",
            "out ${k(candidates)}",
            thoughts.takeIf { it > 0 }?.let { "thinking ${k(it)}" },
            cached.takeIf { it > 0 }?.let { "cached ${k(it)}" },
        )
        return note("tokens · ${parts.joinToString(" · ")}")
    }

    /** EventNote factory — mirrors ClaudeMessageParser.note. `internal`:
     *  shared with [GeminiAcpEvents] (same chat surface, ACP transport). */
    internal fun note(
        label: String,
        detail: String? = null,
        tone: AgentMessage.EventNote.Tone = AgentMessage.EventNote.Tone.DIM,
        id: String = uuid(),
    ): AgentMessage = AgentMessage.EventNote(id = id, label = label, detail = detail, tone = tone)

    private val NOISE_KEYS = setOf("type", "session_id", "timestamp")

    /** `type · best-effort summary` for live events we have no tailored
     *  label for — including types that don't exist yet. */
    internal fun genericLabel(type: String, obj: JsonObject): String {
        val text = listOf(
            "message", "text", "title", "description", "summary",
            "name", "content", "reason", "status", "state",
        ).firstNotNullOfOrNull { k -> obj.string(k)?.takeIf { it.isNotBlank() } }
        return type.replace('_', ' ') + (text?.let { " · ${it.take(100)}" } ?: "")
    }

    /** Expandable key:value dump of the payload minus envelope noise. */
    internal fun genericDetail(obj: JsonObject): String? {
        val parts = obj.entries
            .filter { it.key !in NOISE_KEYS }
            .joinToString("\n") { (k, v) -> "$k: ${v.toString().take(300)}" }
        return parts.ifBlank { null }
    }

    /** Locale.US — the ru default formats "12,0k" with a comma. */
    private fun k(n: Long): String =
        if (n >= 1000) "${"%.1f".format(java.util.Locale.US, n / 1000.0)}k" else n.toString()

    /**
     * One message record from a SAVED session file → AgentMessage(s). The file
     * uses `type` (user / gemini / model) and `content` that is EITHER an array
     * of `{"text":"…"}` parts OR a bare string. Gemini's injected
     * `<session_context>` preamble (a giant workspace/dir-tree dump) is dropped
     * — it's not a turn the user typed, and rendering it would bury the chat.
     * The record's own `id` is reused so distinctBy collapses snapshot repeats.
     */
    private fun fileMessage(msg: JsonObject): List<AgentMessage> {
        // Safe `as?` casts (never `.jsonPrimitive`, which THROWS on a
        // non-primitive) — this runs in the per-line parse loop, which is not
        // wrapped, so a throw here would abort the whole history load.
        val type = (msg["type"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        val text = contentText(msg["content"]).trim()
        if (text.isEmpty() || text.startsWith("<session_context>")) return emptyList()
        val id = (msg["id"] as? JsonPrimitive)?.contentOrNull ?: stableId(text, type)
        return when (type) {
            "user" -> listOf(AgentMessage.UserText(id, text))
            "gemini", "model", "assistant" -> listOf(AgentMessage.AssistantText(id, text))
            else -> emptyList()
        }
    }

    /** `content` in a saved message is either `[{"text":"…"},…]` or a bare
     *  string. Join the text parts; non-text parts (thoughts/tool calls) are
     *  ignored — history shows the conversation, not Gemini's scratchpad. */
    private fun contentText(el: JsonElement?): String = when (el) {
        is JsonArray -> el.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("")
        is JsonPrimitive -> el.contentOrNull ?: ""
        else -> ""
    }

    private fun uuid(): String = ParserHelpers.uuid()

    private fun quickType(line: String): String? = ParserHelpers.quickType(line)

    /**
     * Token-stream parser for `type=="message"` events.
     *
     * Gemini streaming hot path — every reply chunk arrives as
     * `{"type":"message","role":"assistant","content":"..."}`. Slow
     * tree path built a full JsonObject per delta just to read three
     * strings. Pull parser walks the line once with only a StringReader
     * + reader state allocated.
     *
     * Returns null for non-"message" events (init / tool_* / error /
     * result) — slow path below takes over.
     */
    private fun parseFast(line: String): List<AgentMessage>? {
        if (quickType(line) != "message") return null

        val reader = android.util.JsonReader(java.io.StringReader(line))
        try {
            reader.beginObject()
            var role: String? = null
            var content: String? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "role" -> role = reader.nextString()
                    "content" -> {
                        // Fast path only handles string content. If the
                        // schema ever ships array-of-blocks content, fall
                        // through to slow path.
                        if (reader.peek() == android.util.JsonToken.STRING) {
                            content = reader.nextString()
                        } else {
                            reader.skipValue()
                            return null
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val text = content.orEmpty()
            if (text.isBlank()) return emptyList()
            return when (role) {
                "assistant", "model" -> listOf(AgentMessage.AssistantText(stableId(line, "a"), text))
                "user" -> listOf(AgentMessage.UserText(stableId(line, "u"), text))
                else -> emptyList()
            }
        } catch (_: Throwable) {
            return null
        } finally {
            SilentlyTry.fired("SshAi-GeminiParse", "close reader") { reader.close() }
        }
    }

    /**
     * Heuristics for "this stdout line is internal CLI / Node garbage,
     * not something the user should see in their chat."
     *
     * Matches the patterns we've seen in actual crash dumps:
     *  - JS stack frames: `at FunctionName (file:///…)` or
     *    `at process.processTicksAndRejections (…)`
     *  - gaxios error object pretty-prints: lines starting with
     *    `config:`, `request:`, `response:`, `headers:`, `body:`,
     *    `status:`, `statusText:`, `errors:`, `data:`,
     *    `retryConfig:`, `errorRedactor:`, `paramsSerializer:`,
     *    `validateStatus:`, `responseType:`, `responseURL:`,
     *    `noResponseRetries:`, `statusCodesToRetry:`,
     *    `httpMethodsToRetry:`, `currentRetryAttempt:`,
     *    `retryDelay:`, `retry:`, `retryDelayMultiplier:`,
     *    `error: undefined`, `Symbol(…):`
     *  - HTTP headers dumped raw: `'content-type':`, `'alt-svc':`,
     *    `'server-timing':`, etc.
     *  - npm/nvm noise the CLI also re-emits to stdout:
     *    `Your user's .npmrc file has`, `Run \`nvm use`, etc.
     *  - Trailing JSON object continuation lines: `},`, `}`,
     *    `[Object]`, `[Array]`, `]`, `[`
     *  - Hex blob / Symbol: `Symbol(gaxios-…`
     *  - Standalone Node REPL prefixes: `(node:1234) Warning:`
     */
    // `(?i)` = case-insensitive — `Content-Type` / `User-Agent` /
    // `Authorization` were slipping through the old lowercase-only
    // pattern. Updated to cover every line type we've observed in
    // real free-tier crash dumps.
    private val noiseRe = Regex(
        "(?i)^(at\\s+[\\w._$<>]+\\s*\\(|" +                  // stack frame
            "at\\s+async\\s|" +                              // stack frame async
            "at\\s+process\\.|" +                            // node internal
            "at\\s+file:|" +                                  // bare file: in stack
            // gaxios state object pretty-print, HTTP request /
            // response headers, retry config, the dump pretty-prints
            // EVERYTHING. Catch any reasonable field name (any-case)
            // followed by `:` or `=`. Quote-wrapped header names
            // (e.g. `'Content-Type':`) included via the
            // `'[\\w-]+'\\s*:` alternative.
            "(config|request|response|headers|body|status(Text)?|errors|data|" +
            "retryConfig|retry|retryDelay(Multiplier)?|noResponseRetries|" +
            "statusCodesToRetry|httpMethodsToRetry|currentRetryAttempt|" +
            "errorRedactor|paramsSerializer|validateStatus|responseType|" +
            "responseURL|error|code|message|domain|reason|" +
            "authorization|accept|host|" +
            "timeOfFirstRequest|totalTimeout|maxRetryDelay|" +
            "currentRetryDelay|minRetryDelay|retryableErrors|" +
            "shouldRetry|onRetryAttempt|backoffType|" +
            "url|method|" +
            "alt-svc|content-encoding|content-type|content-length|" +
            "date|server|server-timing|transfer-encoding|vary|" +
            "user-agent|x-[a-z-]+|'[\\w-]+')" +
            "\\s*[:=]|" +
            "Symbol\\(|" +                                    // Symbol(gaxios-…)
            "\\(node:\\d+\\)\\s+Warning|" +                  // (node:1234) Warning
            "Warning:\\s+|" +                                // generic "Warning:" prefix
            "Your user.s \\.npmrc|" +
            "has a .globalconfig|" +
            "Run .nvm use|" +
            "\\[(Object|Array|Function|Circular|RangeError|TypeError)|" +
            "[\\}\\]\\{]+\\s*,?\\s*$|" +                    // }, } ] {, etc.
            // Free-tier backend error noise — Gemini CLI dumps these
            // as `We can't connect to Gemini Code Assist…`,
            // `An unexpected critical error occurred:`,
            // `Error authenticating:`, `Approval mode overridden`,
            // `YOLO mode is enabled`, `Ripgrep is not available`,
            // `\\[31m…` (red ANSI prefix). All squashed so the chat
            // is clean — the post-exit handler in [AgentSession]
            // surfaces the single actionable replacement message.
            "We can.t connect to Gemini Code Assist|" +
            "An unexpected critical error occurred|" +
            "Error authenticating|" +
            "Failed to authenticate|" +
            "Approval mode overridden|" +
            "YOLO mode is enabled|" +
            "Ripgrep is not available|" +
            "\\\\u001b?\\[\\d+m|" +                          // bare ANSI prefix
            "Gemini CLI is not running in|" +
            // Quoted token by itself on a line — gaxios dumps stuff
            // like `'application/json',` (continuation of headers).
            "'[^']+'\\s*,?\\s*$|" +
            "^\\s*\\$)"
    )

    private fun isLikelyNoise(line: String): Boolean = noiseRe.containsMatchIn(line)
}
