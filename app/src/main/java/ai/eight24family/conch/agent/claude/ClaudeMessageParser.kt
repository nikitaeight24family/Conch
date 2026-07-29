package ai.eight24family.conch.agent.claude

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.agent.spec.stableId
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for **Claude Code's `--output-format stream-json --verbose`** JSONL.
 *
 * Event types (top-level `type`):
 *   - `system` (subtype = `init` / `api_retry` / `plugin_install` / …)
 *   - `assistant`        — full assistant turn (text + tool_use blocks)
 *   - `user`             — synthetic user turn carrying `tool_result` blocks,
 *                          OR a replayed user echo when piping stream-json in
 *   - `stream_event`     — token-level deltas with `--include-partial-messages`
 *   - `result`           — final aggregate (one per turn, last event)
 *   - `error`            — non-fatal warning
 *   - `permission_request`/`tool_permission_request` — server-side approval
 *   - several JSONL-only bookkeeping types (`permission-mode`, `attachment`,
 *     `last-prompt`, `queue-operation`, `summary`, `file-history-snapshot`)
 *     which we silently filter to keep the chat free of noise.
 *
 * The shape comes from Anthropic's CLI reference and the takopi stream-JSON
 * cheatsheet (linked in `docs/cli-research-2026-05.md` §1C).
 *
 * **Partial-message streaming**: when Claude is invoked with
 * `--include-partial-messages`, every delta arrives as an `assistant` event
 * with the **same** `message.id` and a growing `content[].text`. We tag the
 * emitted `AssistantText` with `${msgId}#${blockIndex}` and the upsert-on-id
 * logic in `AgentSession.emitMsg` replaces the existing block instead of
 * appending duplicates — so the assistant bubble grows in place.
 */
object ClaudeMessageParser {

    private val json get() = ParserHelpers.json

    /**
     * Stable id for the upstream-overloaded ("Service is busy") banner. Using a
     * constant means each `api_retry` chip + the final terminal `is_error`
     * result land on the SAME row (history.emitMsg upserts by id) — so the
     * user sees ONE banner whose text reflects the latest state, not a stack
     * of 10 cards.
     */
    private const val OVERLOAD_BANNER_ID = "claude-overload-banner"

    /**
     * Stable id shared by the live "Compacting…" row and the final
     * "context compacted" divider — history.emitMsg upserts by id, so the
     * animated row morphs into the summary IN PLACE (same trick as the
     * overload banner). Known limitation: a SECOND compaction within one
     * chat upserts the previous divider at its old position instead of
     * appending a new row — acceptable, compaction is rare per chat.
     */
    private const val COMPACT_ROW_ID = "claude-compact-row"

    /**
     * Stable id for the model-unavailable banner ("Claude Fable 5 is
     * currently unavailable. Learn more: …"). Anthropic ships these for
     * export-control suspensions / deprecations — IMPORTANT, must NOT be
     * truncated or buried in a red `! …` line. The CLI may emit it as
     * both a `result` text AND a top-level `error`; the shared id
     * collapses them to ONE card via emitMsg's upsert-by-id (kills the
     * double-render).
     */
    private const val UNAVAILABLE_BANNER_ID = "claude-model-unavailable-banner"

    /** Id PREFIX for the non-rendering System row that carries the model the
     *  session is actually running on (`message.model` from each assistant
     *  turn). PER-MESSAGE ([prefix]+msgId) so `distinctBy { id }` keeps one
     *  per turn and `observedModel` (reads the LAST) reflects the LATEST
     *  model — not the first turn's. Synthetic (never written to the raw
     *  JSONL cache, so zero storage cost); drives the topbar's session mirror. */
    private const val OBSERVED_MODEL_ID_PREFIX = "claude-model-"

    /** Stable id for the non-rendering System row that mirrors the session's
     *  current reasoning effort (only `ultracode` is recorded by Claude — see
     *  parseAttachment). Stable → one upserting row; `observedReasoning` in the
     *  VM reads the LAST so a later change wins. */
    private const val OBSERVED_REASONING_ID = "claude-effort-observed"

    /** Stable id for the non-rendering System row carrying the session's
     *  auto-generated title (`ai-title`). VM reads the latest for the topbar. */
    private const val AI_TITLE_ID = "claude-ai-title"

    /** Matches Anthropic's "<model> is currently unavailable" notice. Both
     *  signals required (the phrase AND a Learn-more / anthropic.com link)
     *  so a normal reply that merely says "the API is currently unavailable"
     *  isn't hijacked into the card. The CLI emits this notice as the
     *  assistant-turn text AND the result text — routing both to the card
     *  (stable id) collapses the double-render the user hit (2026-06-13). */
    private fun String.matchesModelUnavailable(): Boolean =
        contains("is currently unavailable", ignoreCase = true) &&
        (contains("Learn more", ignoreCase = true) || contains("anthropic.com", ignoreCase = true))

    /** First http(s) URL in the text, stripped of trailing punctuation. */
    private val URL_RX = Regex("https?://\\S+")

    /**
     * Build the one-card representation: clean title (everything before
     * "Learn more" / the URL) + the URL stashed in [AgentMessage.Error.details]
     * for the clickable "Learn more →" the UI renders. Stable id so the
     * `result` and `error` copies upsert into a single card.
     */
    private fun modelUnavailableCard(rawText: String): AgentMessage {
        val url = URL_RX.find(rawText)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
        var title = rawText
        val lm = title.indexOf("Learn more", ignoreCase = true)
        if (lm >= 0) title = title.substring(0, lm)
        else if (url != null) title = title.replace(url, "")
        title = title.trim().trimEnd('.', ':', '·', '—', '-').trim()
        if (title.isBlank()) title = "Model unavailable"
        return AgentMessage.Error(
            id = UNAVAILABLE_BANNER_ID,
            text = "$title.",
            kind = "unavailable",
            details = url,
        )
    }

    /**
     * True when [this] looks like an upstream Anthropic "overloaded" signal
     * embedded in the CLI's text. The CLI streams the raw upstream blob
     * verbatim — both the structured `overloaded_error` type and the human-
     * readable `API Error (529 …)` / `Repeated 529 Overloaded` chips. We match
     * all three so a parser change in either layer doesn't break detection.
     */
    private fun String.matchesOverloaded(): Boolean =
        contains("overloaded_error", ignoreCase = true) ||
        contains("Repeated 529", ignoreCase = true) ||
        contains("API Error (529", ignoreCase = true) ||
        contains("API Error: 529", ignoreCase = true) ||
        Regex("\\boverloaded\\b", RegexOption.IGNORE_CASE).containsMatchIn(this)

    /**
     * Claude injects "synthetic" user turns into its saved JSONL — caveat
     * headers, slash-command echoes, task notifications, system reminders,
     * etc. They're addressed at the model, not from the human, so we hide
     * them in chat AND skip them when picking a session preview (otherwise
     * the row title becomes "local-command-caveat").
     */
    fun isSyntheticUserText(text: String): Boolean {
        val s = text.trimStart()
        if (s.isEmpty()) return false
        val xmlHeads = listOf(
            "<local-command-caveat",
            "<local-command-stdout", "<local-command-stderr",
            "<command-name", "<command-message", "<command-args", "<command-output",
            "<task-notification", "<task-context",
            "<system-reminder", "<system_reminder",
            "<bash-stdout", "<bash-stderr",
            "<interrupted", "<request-interrupted"
        )
        if (xmlHeads.any { s.startsWith(it) }) return true
        if (s.startsWith("Caveat:")) return true
        if (s.startsWith("[Request interrupted")) return true
        // Auto-compaction continuation: Claude Code injects this as a
        // "user" turn when context overflows and it rewrites history
        // into a summary. It's a system handoff, not a human prompt —
        // rendering it as a user message (with ❯ + cyan stripe) is
        // misleading.
        if (s.startsWith("This session is being continued from a previous conversation")) return true
        return false
    }

    fun parse(line: String): List<AgentMessage> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("{")) return listOf(AgentMessage.Raw(uuid(), trimmed))

        // SUBAGENT traffic is intercepted BEFORE the fast path: a sidechain
        // record is text-shaped, so the streaming reader would turn it into a
        // normal chat row and a Task fan-out of eight agents would bury the
        // conversation — the CLI keeps these out of its own transcript too
        // (`filtered from /resume: isSidechain=true`).
        //
        // ⚠ FAIL-OPEN, AND VERIFY BEFORE SWALLOWING. The first cut of this
        // matched the raw substrings `"isSidechain":true` / `"agent_progress"`
        // anywhere in the line and then dropped the line if the record didn't
        // parse as a subagent. That ate ANY line merely CONTAINING that text —
        // including `system.init`, which carries session_id and the model — so
        // chats opened with an empty model and never answered at all (user,
        // 2026-07-23). The substring is now only a cheap pre-filter; whether to
        // intercept is decided by the PARSED record, and anything that isn't
        // definitively a subagent record falls through to normal parsing. A
        // chat line must never be able to vanish here.
        // ⚠ The pre-filter must stay NARROW. EVERY ordinary record carries
        // `"isSidechain":false`, so a bare `contains("isSidechain")` matches the
        // whole transcript and drags every line through a full AST parse ahead
        // of the fast path — the exact hot path that exists to avoid it at
        // 50-100 deltas/sec. Match the `true` literal (both spellings; the CLI
        // emits compact JSON, the spaced form is belt-and-braces). A miss here
        // is safe by construction: it just means no interception, i.e. the
        // pre-subagent behaviour, never a lost line.
        if (trimmed.contains("\"isSidechain\":true") ||
            trimmed.contains("\"isSidechain\": true") ||
            trimmed.contains("agent_progress")
        ) {
            subagentActivity(trimmed)?.let { return listOf(it) }
        }

        // Fast path: token-stream parser (JsonReader) for the hot
        // `assistant` / `user` events with text-only content. This is
        // where Claude streams 50-100 deltas/sec during a live reply.
        // Pull parser walks the line once, allocates only the
        // StringReader + reader state + the AgentMessage strings we
        // actually need. Returns null for shapes the streaming reader
        // can't reconstruct (tool_use / tool_result blocks, unknown
        // top-level types, malformed JSON) — caller falls through to
        // the slow path.
        ai.eight24family.conch.util.Tracing.section(
            ai.eight24family.conch.util.Tracing.Names.PARSER_FAST_PATH
        ) {
            parseFast(trimmed)?.let { return it }
        }

        // Slow path: kotlinx.serialization tree-based parse. Used for
        // the rare-by-volume but rich-by-content events: tool calls,
        // system metadata, result summaries, permission requests,
        // attachments. AST cost is real but amortised over events that
        // arrive a few-per-turn rather than 100/sec. Durov critique #3
        // ("if JsonReader covers 99% — kill slow path") doesn't apply
        // here: the slow path handles ~20-30% of lines by volume but
        // those carry most of the structural complexity. The
        // PARSER_SLOW_PATH trace section lets us prove the volume
        // breakdown in production via Perfetto rather than guessing.
        return ai.eight24family.conch.util.Tracing.section(
            ai.eight24family.conch.util.Tracing.Names.PARSER_SLOW_PATH
        ) {
            val obj = SilentlyTry.logged("SshAi-ClaudeParse", "parse jsonl line") { json.parseToJsonElement(trimmed).jsonObject }
                ?: return@section listOf(AgentMessage.Raw(uuid(), trimmed))
            parseObject(obj, trimmed)
        }
    }

    /**
     * Pull one [AgentMessage.SubagentActivity] out of a sidechain /
     * `agent_progress` record. Field names are taken from the shipped CLI
     * binary (2.1.218), not guessed: `agentId`, `parentToolUseID` (the join key
     * back to the spawning `Task` tool_use), `subagent_type`, `task_description`
     * and `elapsed_time_seconds`.
     *
     * Returns null unless the record is DEFINITIVELY a subagent record, so the
     * caller can safely fall through to normal parsing. Anything less strict
     * eats real chat lines: the first version accepted any parseable JSON that
     * merely mentioned the words, which silently swallowed `system.init`
     * (session_id + model) and left chats mute (user, 2026-07-23).
     */
    private fun subagentActivity(line: String): AgentMessage.SubagentActivity? {
        val obj = SilentlyTry.logged("SshAi-ClaudeParse", "parse subagent record") {
            json.parseToJsonElement(line).jsonObject
        } ?: return null

        // Decide on the PARSED record, never on the raw text. Two positive
        // signals, both structural:
        //   • top-level `type` == "agent_progress" — the live progress event;
        //   • top-level `isSidechain` == true — a turn belonging to a subagent.
        // `"isSidechain":false` (every ordinary record carries it) and a chat
        // message that merely quotes these words both fail this test, which is
        // exactly the point.
        val isProgress = firstString(obj, "type") == "agent_progress"
        val isSidechain = SilentlyTry.loggedOrElse("SshAi-ClaudeParse", "read isSidechain", false) {
            obj["isSidechain"]?.jsonPrimitive?.content == "true"
        } ?: false
        if (!isProgress && !isSidechain) return null

        // Token usage lives on the assistant record's `message.usage`. Sum the
        // billed halves; cache reads are counted too because that is what the
        // CLI's per-agent "↓ N tokens" reflects.
        var tokens = 0L
        SilentlyTry.fired("SshAi-ClaudeParse", "subagent usage") {
            val usage = obj["message"]?.jsonObject?.get("usage")?.jsonObject
            if (usage != null) {
                for (k in listOf(
                    "input_tokens", "output_tokens",
                    "cache_read_input_tokens", "cache_creation_input_tokens",
                )) {
                    tokens += usage[k]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                }
            }
        }

        val subtype = firstString(obj, "subtype")
        return AgentMessage.SubagentActivity(
            id = uuid(),
            agentId = firstString(obj, "agentId", "agent_id"),
            parentToolUseId = firstString(obj, "parentToolUseID", "parent_tool_use_id"),
            subagentType = firstString(obj, "subagent_type"),
            task = firstString(obj, "task_description", "description"),
            tokens = tokens,
            elapsedSeconds = SilentlyTry.logged("SshAi-ClaudeParse", "subagent elapsed") {
                obj["elapsed_time_seconds"]?.jsonPrimitive?.content?.toLongOrNull()
            },
            done = subtype == "subagent_complete",
            // Keep the subagent's own words so a Task fan-out stays searchable
            // (these records used to parse as AssistantText/ToolUse and get
            // indexed; folding them into metadata alone would drop the whole
            // research trail out of search).
            text = SilentlyTry.logged("SshAi-ClaudeParse", "subagent text") {
                val content = obj["message"]?.jsonObject?.get("content")
                when (content) {
                    is kotlinx.serialization.json.JsonArray -> content.mapNotNull { blk ->
                        val o = blk as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        o["text"]?.jsonPrimitive?.contentOrNull
                            ?: o["content"]?.jsonPrimitive?.contentOrNull
                    }.joinToString("\n").takeIf { it.isNotBlank() }
                    is kotlinx.serialization.json.JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotBlank() }
                    else -> null
                }
            },
        )
    }

    private fun quickType(line: String): String? = ParserHelpers.quickType(line)

    /**
     * Token-stream parser for "assistant" / "user" events.
     *
     * Returns null when the event isn't one we handle on the fast path
     * (other top-level types, or a content array with tool_use /
     * tool_result blocks the streaming reader can't reconstruct without
     * an AST). Caller falls through to the tree-based slow path.
     */
    private fun parseFast(line: String): List<AgentMessage>? {
        val type = quickType(line) ?: return null
        if (type != "assistant" && type != "user") return null
        val fromAssistant = type == "assistant"
        // `isMeta:true` user turns are model-directed CONTEXT injected by the CLI —
        // slash/skill EXPANSIONS (the full "# /loop — …\n## Input" markdown), caveat
        // headers, task notifications. The interactive TUI hides them; rendering the
        // raw expansion as a giant user prompt was the bug. Verified marker:
        // top-level "isMeta":true (real prompts have isMeta:null). Bail to the slow
        // path for ANY user line that even mentions isMeta — the JSON-aware
        // parseUser is the single authority on the top-level field, so a whitespace
        // variant (`"isMeta": true`) or a re-serialized file can't slip a giant fake
        // user prompt through the fast path (audit, 2026-06-14). A real prompt that
        // merely contains the text "isMeta" just takes the slow path and renders
        // correctly — only the rare line pays the cost.
        if (!fromAssistant && line.contains("\"isMeta\"")) return null

        val reader = android.util.JsonReader(java.io.StringReader(line))
        val out = mutableListOf<AgentMessage>()
        try {
            reader.beginObject()
            var msgId: String? = null
            var model: String? = null
            var contentHandled = false
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "message" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "id" -> msgId = reader.nextString()
                                // Session's actual model — see parseAssistant.
                                // Skip synthetic markers ("<synthetic>" etc.).
                                "model" -> model = SilentlyTry.logged("SshAi-ClaudeParse", "fast model") {
                                    if (reader.peek() == android.util.JsonToken.STRING)
                                        reader.nextString()?.takeIf { it.isNotBlank() && !it.startsWith("<") }
                                    else { reader.skipValue(); null }
                                }
                                "content" -> {
                                    val ok = readContent(reader, fromAssistant, msgId, out, line)
                                    if (!ok) return null
                                    contentHandled = true
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            // If we never saw a content field, return null so the slow
            // path can decide (Claude's stream-json sometimes emits
            // bookkeeping events under role labels we don't handle here).
            if (!contentHandled) return null
            // Mirror the session's model into the topbar (non-rendering,
            // stable id) — same as the slow-path parseAssistant.
            if (fromAssistant && !model.isNullOrBlank()) {
                val mid = msgId ?: stableId(line, "amsg")
                out.add(0, AgentMessage.System(id = OBSERVED_MODEL_ID_PREFIX + mid, subtype = "model_observed", model = model, raw = ""))
            }
            return out
        } catch (_: Throwable) {
            return null
        } finally {
            SilentlyTry.fired("SshAi-ClaudeParse", "close reader") { reader.close() }
        }
    }

    /** Reads the `content` field of an assistant/user message. It's
     *  either a bare string OR an array of blocks. Returns true when
     *  the content is fully handled on the fast path; false to bail
     *  out to the slow path (complex blocks). */
    private fun readContent(
        reader: android.util.JsonReader,
        fromAssistant: Boolean,
        msgIdHint: String?,
        out: MutableList<AgentMessage>,
        rawLine: String,
    ): Boolean {
        return when (reader.peek()) {
            android.util.JsonToken.STRING -> {
                val txt = reader.nextString()
                if (txt.isNotBlank()) {
                    appendTextBlock(out, fromAssistant, msgIdHint, 0, txt, rawLine)
                }
                true
            }
            android.util.JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var idx = 0
                while (reader.hasNext()) {
                    if (!readBlock(reader, fromAssistant, msgIdHint, idx, out, rawLine)) {
                        // Complex block (tool_use / tool_result) —
                        // drain the rest of the array so JsonReader's
                        // state stays consistent, then bail.
                        while (reader.hasNext()) reader.skipValue()
                        reader.endArray()
                        return false
                    }
                    idx++
                }
                reader.endArray()
                true
            }
            else -> {
                reader.skipValue()
                // Unexpected shape — let slow path handle.
                false
            }
        }
    }

    /** Reads one content block. Returns false for tool_use /
     *  tool_result / image-with-data / unknown — those need the tree
     *  parser. */
    private fun readBlock(
        reader: android.util.JsonReader,
        fromAssistant: Boolean,
        msgIdHint: String?,
        blockIdx: Int,
        out: MutableList<AgentMessage>,
        rawLine: String,
    ): Boolean {
        reader.beginObject()
        var blockType: String? = null
        var textVal: String? = null
        var thinkingVal: String? = null
        var sawComplexField = false
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> blockType = reader.nextString()
                "text" -> textVal = reader.nextString()
                "thinking" -> thinkingVal = reader.nextString()
                else -> {
                    // Any field other than the three we know about
                    // (input/name/id/tool_use_id/content/is_error/…)
                    // signals a complex block. Skip its value, then bail.
                    reader.skipValue()
                    sawComplexField = true
                }
            }
        }
        reader.endObject()
        return when (blockType) {
            "text" -> {
                if (sawComplexField) return false
                val t = textVal.orEmpty()
                if (t.isNotBlank()) appendTextBlock(out, fromAssistant, msgIdHint, blockIdx, t, rawLine)
                true
            }
            "thinking" -> {
                val t = thinkingVal.orEmpty()
                if (t.isNotBlank()) {
                    out += AgentMessage.Raw(uuid(), "· thinking · ${t.take(80)}")
                }
                true
            }
            // tool_use, tool_result, image (with rich data), or unknown —
            // not on the fast path.
            else -> false
        }
    }

    /** Emits one text block — with the same synthetic-user-text →
     *  System(subtype="user_synthetic") routing the slow path does. */
    private fun appendTextBlock(
        out: MutableList<AgentMessage>,
        fromAssistant: Boolean,
        msgIdHint: String?,
        blockIdx: Int,
        text: String,
        rawLine: String,
    ) {
        // When Claude gives us a stable msgId (assistant turns, and the
        // streaming partial-message upserts that ride the same id),
        // keep our long-standing `$msgId#$blockIdx` scheme — that's
        // what `AgentSession.emitMsg` upserts on. For everything else
        // (parseUser path, multi-block fallback) derive a content-
        // addressed id from the raw line + block index so re-parsing
        // is idempotent. See Durov-critique #3 in IdUtil.kt.
        // Model-unavailable notice arriving as the assistant-turn text —
        // route to the SAME card the result/error branches emit (stable id
        // dedups the double-render). Tightened matcher avoids false hits.
        if (fromAssistant && text.matchesModelUnavailable()) {
            out += modelUnavailableCard(text)
            return
        }
        val id = if (msgIdHint != null) {
            "$msgIdHint#$blockIdx"
        } else if (!fromAssistant && isSyntheticUserText(text)) {
            stableId(rawLine, "sys_$blockIdx")
        } else if (fromAssistant) {
            stableId(rawLine, "a_$blockIdx")
        } else {
            stableId(rawLine, "u_$blockIdx")
        }
        if (!fromAssistant && isSyntheticUserText(text)) {
            out += AgentMessage.System(
                id = id,
                subtype = "user_synthetic",
                raw = text,
            )
            return
        }
        out += if (fromAssistant) AgentMessage.AssistantText(id, text)
        else AgentMessage.UserText(id, text)
    }

    /**
     * The top-level `type`, or — when the CLI did not put one there — what the
     * envelope's SHAPE says it is.
     *
     * The 2026-07-29 capture arrived as
     * `{"is_error":false,"duration_api_ms":2593,"num_turns":1,…}`: `type` not
     * first, `duration_ms` absent, a top-level `stop_reason` present. That is
     * already a different key layout from the one documented in
     * `docs/cli-research-2026-05.md`, so the app may not assume where — or
     * whether — `type` appears. What a turn-final envelope ALWAYS carries is the
     * turn's accounting: `total_cost_usd`, or `num_turns` + `duration_api_ms`.
     * No other stream-json line carries those.
     *
     * Deliberately conservative: anything with `event` or `message` is a
     * stream_event / assistant / user record and is left alone, so this can
     * never promote a mid-turn line to terminal.
     */
    /** The marker that ends a turn. Non-rendering; the stream reader consumes it
     *  and drops it. Every terminal exit of this parser must emit exactly one. */
    private fun turnEnd(reason: String) = AgentMessage.TurnEnd(uuid(), reason)

    private fun inferTopLevelType(obj: JsonObject): String? = when {
        obj.containsKey("event") || obj.containsKey("message") -> null
        obj.containsKey("total_cost_usd") -> "result"
        obj.containsKey("num_turns") && obj.containsKey("duration_api_ms") -> "result"
        else -> null
    }

    private fun parseObject(obj: JsonObject, raw: String): List<AgentMessage> {
        return when (obj.string("type") ?: inferTopLevelType(obj)) {
            "system" -> parseSystem(obj, raw)
            "assistant" -> parseAssistant(obj, raw)
            "user" -> parseUser(obj, raw)
            "result" -> {
                // Stream-json result event. When `is_error:true` (and/or
                // subtype="error" / "error_*"), Claude CLI exhausted its 10
                // internal retries and is giving up — surface as a prominent
                // banner, not a "result · error" line buried in chat. The CLI
                // is configured to retry 529 / 500 / 504 silently; by the time
                // this final event lands, the user has stared at "..." for up
                // to 10 backoffs (minutes). The wording matches the claude.ai
                // web "Service is busy" card the user asked for.
                val subtype = obj.string("subtype").orEmpty()
                val text = obj.string("result") ?: obj.string("error") ?: obj.string("text")
                // Model-unavailable notice — render as ONE clean card with a
                // working "Learn more" link, regardless of is_error, and skip
                // the plain Result bubble + tokens line that would otherwise
                // double it (user, 2026-06-13).
                if (text?.matchesModelUnavailable() == true) {
                    return listOf(modelUnavailableCard(text), turnEnd("result:unavailable"))
                }
                val isError = obj["is_error"]?.jsonPrimitive?.contentOrNull == "true" ||
                    subtype == "error" || subtype.startsWith("error_")
                if (isError) {
                    val body = text.orEmpty()
                    // A user-initiated STOP ends the turn as an `is_error` result
                    // with NO message (or an explicit "Request interrupted") —
                    // that's an action the user took, not a failure. Render a
                    // calm "stopped" note, never a red "! Error". A genuine
                    // failure always carries a message.
                    if (body.isBlank() ||
                        body.contains("Request interrupted", ignoreCase = true) ||
                        body.contains("interrupted by user", ignoreCase = true)
                    ) {
                        return listOf(note("stopped"), turnEnd("result:stopped"))
                    }
                    val isOverload = body.matchesOverloaded()
                    listOf(
                        AgentMessage.Error(
                            // Stable id for overload → the api_retry banner
                            // and this final card are the SAME row, upserted
                            // through retries to the final state (history's
                            // id-index makes emitMsg upsert by id).
                            id = if (isOverload) OVERLOAD_BANNER_ID else uuid(),
                            text = if (isOverload) "Service is busy" else (body.take(140).ifBlank { "Error" }),
                            kind = if (isOverload) "overloaded" else null,
                            details = if (isOverload)
                                "Try again in a moment, or switch to a different model."
                            else
                                body.takeIf { it.isNotBlank() && it != text },
                        ),
                        turnEnd("result:error"),
                    )
                } else {
                    // Successful turn → ALSO emit a dim per-turn usage line:
                    // tokens in/out, cost and duration straight from the
                    // result event.
                    val usage = SilentlyTry.logged("SshAi-ClaudeParse", "result usage") {
                        obj["usage"]?.jsonObject
                    }
                    val inTok = usage?.get("input_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val outTok = usage?.get("output_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val cacheRead = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val cost = obj["total_cost_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    val durMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    // Locale.US: the default (ru) locale renders "12,0k"
                    // with a comma — broke the label (and the test).
                    fun k(n: Long): String =
                        if (n >= 1000) "${"%.1f".format(java.util.Locale.US, n / 1000.0)}k"
                        else n.toString()
                    val statParts = listOfNotNull(
                        inTok?.let { "in ${k(it + (cacheRead ?: 0))}" },
                        outTok?.let { "out ${k(it)}" },
                        cost?.takeIf { it > 0 }
                            ?.let { "$" + "%.4f".format(java.util.Locale.US, it).trimEnd('0').trimEnd('.') },
                        durMs?.let { "${it / 1000}s" },
                    )
                    buildList {
                        add(AgentMessage.Result(uuid(), subtype, text))
                        if (statParts.isNotEmpty()) {
                            add(note("tokens · ${statParts.joinToString(" · ")}"))
                        }
                        add(turnEnd("result"))
                    }
                }
            }
            "error" -> {
                val msg = obj.string("message") ?: raw
                if (msg.matchesModelUnavailable()) return listOf(modelUnavailableCard(msg), turnEnd("error:unavailable"))
                val isOverload = msg.matchesOverloaded()
                listOf(
                    AgentMessage.Error(
                        id = if (isOverload) OVERLOAD_BANNER_ID else uuid(),
                        text = if (isOverload) "Service is busy" else msg,
                        kind = if (isOverload) "overloaded" else null,
                        details = if (isOverload) "Try again in a moment, or switch to a different model." else null,
                    ),
                    turnEnd("error"),
                )
            }
            "permission_request", "tool_permission_request" -> {
                val rid = obj.string("id") ?: uuid()
                listOf(
                    AgentMessage.PermissionRequest(
                        id = "perm_$rid",
                        requestId = rid,
                        toolName = obj.string("tool_name") ?: obj.string("tool") ?: "tool",
                        description = obj.string("description") ?: obj.string("message") ?: raw,
                        input = obj["input"]?.toString().orEmpty(),
                        raw = raw
                    )
                )
            }
            "permission-mode" -> emptyList()  // bookkeeping
            // file backups are internal plumbing — Claude snapshots edited files
            // so it can undo. The user doesn't act on it and it just clutters the
            // chat. Hide.
            "file-history-snapshot" -> emptyList()
            "last-prompt" -> emptyList()
            // Claude's auto-generated session title — surface it (non-rendering)
            // so the topbar shows it instead of the first user message. Mirrors
            // model_observed: stable id → one upserting row, VM reads the latest.
            "ai-title" -> {
                val t = obj.string("aiTitle")?.takeIf { it.isNotBlank() }
                if (t != null) listOf(AgentMessage.System(id = AI_TITLE_ID, subtype = "ai_title", title = t, raw = ""))
                else emptyList()
            }
            "queue-operation" -> emptyList()  // duplicates the user's prompt — skip
            "attachment" -> parseAttachment(obj, raw)
            "summary" -> {
                val s = obj.string("summary").orEmpty().take(120)
                if (s.isBlank()) emptyList() else listOf(note("summary · $s"))
            }
            else -> emptyList()
        }
    }

    private fun parseSystem(obj: JsonObject, raw: String): List<AgentMessage> {
        val subtype = obj.string("subtype").orEmpty()
        // Live compaction signals — the CLI's own TUI shows "Compacting
        // conversation…" and the user asked for parity (2026-06-10).
        // `status{status=compacting}` opens the live row; `compact_boundary`
        // closes it with a summary. Both can carry session_id, so they MUST
        // be matched BEFORE the init-like branch below (it swallows anything
        // with model/session_id).
        if (subtype == "status") {
            val status = obj.string("status").orEmpty()
            return if (status == "compacting") listOf(
                AgentMessage.System(id = COMPACT_ROW_ID, subtype = "compacting", raw = raw)
            ) else emptyList() // other statuses: no UI yet, and never init-like rows
        }
        if (subtype == "compact_boundary") {
            val meta = SilentlyTry.logged("SshAi-ClaudeParse", "read compact_metadata") {
                obj["compact_metadata"]?.jsonObject
            }
            val trigger = meta?.string("trigger")
            val preTokens = meta?.get("pre_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val label = buildString {
                append("context compacted")
                if (!trigger.isNullOrBlank()) append(" · ").append(trigger)
                if (preTokens != null && preTokens > 0) {
                    append(" · ").append(preTokens / 1000).append("k tokens")
                }
            }
            return listOf(AgentMessage.System(id = COMPACT_ROW_ID, subtype = "compact_done", raw = label))
        }
        // Stream-json `init` event has full session metadata. RESTRICTED to
        // subtype "init" / blank: EVERY system event carries session_id in
        // its envelope, so the old `contains("session_id")` shortcut would
        // swallow the whole subtype table below into init-like rows (caught
        // by the unknown-subtype test, 2026-06-12).
        if (subtype == "init" || (subtype.isBlank() && (obj.contains("model") || obj.contains("session_id")))) {
            val toolsArr = SilentlyTry.logged("SshAi-ClaudeParse", "read tools array") { obj["tools"]?.jsonArray }
            return listOf(
                AgentMessage.System(
                    id = stableId(raw, "sys"),
                    subtype = subtype.ifEmpty { "init" },
                    sessionId = obj.string("session_id"),
                    model = obj.string("model"),
                    cwd = obj.string("cwd"),
                    version = obj.string("version") ?: obj.string("app_version"),
                    toolCount = toolsArr?.size ?: 0,
                    raw = raw
                )
            )
        }
        return when (subtype) {
            // just noise in the chat. Hide.
            "turn_duration" -> emptyList()
            "api_retry" -> {
                // CLI retries 529 / 500 / 504 / dropped connections up to 10×
                // with exponential backoff. WITHOUT a visible signal the user
                // sees only "." for minutes.
                val err = obj.string("error").orEmpty()
                val attempt = obj["attempt"]?.jsonPrimitive?.contentOrNull
                if (err.matchesOverloaded()) {
                    val tail = attempt?.let { " · retry $it/10" }.orEmpty()
                    listOf(
                        AgentMessage.Error(
                            id = OVERLOAD_BANNER_ID,
                            text = "Service is busy",
                            kind = "overloaded",
                            details = "Try again in a moment, or switch to a different model.$tail",
                        )
                    )
                } else {
                    listOf(note(
                        "api retry${if (attempt != null) " · $attempt" else ""}${if (err.isNotBlank()) " · ${err.take(80)}" else ""}",
                        tone = AgentMessage.EventNote.Tone.WARN,
                    ))
                }
            }
            "plugin_install" -> {
                val name = obj.string("name").orEmpty()
                val status = obj.string("status").orEmpty()
                if (name.isBlank()) emptyList()
                else listOf(note("plugin · $name${if (status.isNotBlank()) " · $status" else ""}"))
            }

            // ── Full system-subtype surface. Field names mined from CLI
            // 2.1.170's zod wire schemas (offsets ~231.35-231.43M) —
            // snake_case on the wire except commands[].argumentHint and
            // mirror_error.key.* (camelCase). firstString fallbacks keep
            // the labels alive if upstream renames. Progress-ish families
            // reuse STABLE ids → in-place upserts. UNKNOWN future subtypes
            // hit the generic else — visible, never swallowed.

            "task_started", "task_progress", "task_updated", "task_notification" -> {
                val taskKey = firstString(obj, "task_id", "tool_use_id") ?: "task"
                val patch = SilentlyTry.logged("SshAi-ClaudeParse", "task patch") { obj["patch"]?.jsonObject }
                val status = firstString(obj, "status")
                    ?: patch?.let { firstString(it, "status") }
                    ?: if (subtype == "task_started") "started" else "running"
                val what = firstString(obj, "summary", "description")
                    ?: patch?.let { firstString(it, "description", "error") }
                val lastTool = firstString(obj, "last_tool_name")
                listOf(note(
                    "task · $status${what?.let { " · ${it.take(90)}" } ?: ""}" +
                        (lastTool?.let { " · $it" } ?: ""),
                    detail = genericDetail(obj),
                    tone = when (status) {
                        "failed", "killed" -> AgentMessage.EventNote.Tone.WARN
                        "completed" -> AgentMessage.EventNote.Tone.INFO
                        else -> AgentMessage.EventNote.Tone.DIM
                    },
                    id = "sysevt-task-$taskKey",
                ))
            }
            "task_summary" -> {
                val detail = firstString(obj, "detail")
                if (detail.isNullOrBlank()) emptyList()
                else listOf(note("task summary · ${detail.take(120)}",
                    detail = detail, tone = AgentMessage.EventNote.Tone.INFO))
            }

            // Hook lifecycle — in-place per hook_id.
            "hook_started", "hook_progress", "hook_response" -> {
                val hookKey = firstString(obj, "hook_id") ?: "hook"
                val name = firstString(obj, "hook_name", "hook_event") ?: "hook"
                val outcome = firstString(obj, "outcome")
                val phase = when (subtype) {
                    "hook_started" -> "running"
                    "hook_progress" -> "running"
                    else -> outcome ?: "done"
                }
                listOf(note(
                    "hook · $name · $phase",
                    detail = firstString(obj, "output", "stdout", "stderr") ?: genericDetail(obj),
                    tone = if (outcome == "error") AgentMessage.EventNote.Tone.WARN
                    else AgentMessage.EventNote.Tone.DIM,
                    id = "sysevt-hook-$hookKey",
                ))
            }
            "stop_hook_summary" -> {
                val count = firstString(obj, "hook_count") ?: "?"
                val errors = SilentlyTry.logged("SshAi-ClaudeParse", "hook errors") {
                    obj["hook_errors"]?.jsonArray
                }?.size ?: 0
                listOf(note(
                    "hooks · $count ran" + if (errors > 0) " · $errors error(s)" else "",
                    detail = genericDetail(obj),
                    tone = if (errors > 0) AgentMessage.EventNote.Tone.WARN
                    else AgentMessage.EventNote.Tone.DIM,
                ))
            }

            // Live thinking-token counter — TRANSIENT UI state, not a chat
            // row: the readers feed AgentSession.liveThinkingTokens, the
            // list shows «thinking · N tokens» above the spinner while the
            // turn runs and drops it on completion (user, 2026-06-12).
            "thinking_tokens" -> emptyList()

            // The model silently swapped under the user — they must see it.
            // ALL of the CLI's swap subtypes land here (2.1.218 ships six).
            // Handling only the first two meant the most common real-world
            // swap — `model_consent_fallback`, i.e. "Switched to Sonnet 5 for
            // this session · Fable 5 requires usage credits" — was dropped on
            // the floor: the session quietly ran a different model and the app
            // never told the user why their pick had no effect
            // (user, 2026-07-23).
            "model_fallback", "model_refusal_fallback", "model_consent_fallback",
            "model_not_found_fallback", "availability_switch" -> {
                val from = firstString(obj, "original_model", "from_model")
                val to = firstString(obj, "fallback_model", "to_model")
                val trigger = firstString(obj, "trigger")
                val reason = when (subtype) {
                    "model_refusal_fallback" -> " · after refusal"
                    // Say "credits" outright: this one is ACTIONABLE by the
                    // user (top up, or pick another model), unlike a capacity
                    // or export-control swap they can only wait out.
                    "model_consent_fallback" -> " · needs usage credits"
                    "model_not_found_fallback" -> " · model not found"
                    "availability_switch" -> " · unavailable"
                    else -> ""
                }
                listOf(note(
                    "model fallback${from?.let { " · $it" } ?: ""}${to?.let { " → $it" } ?: ""}" +
                        (trigger?.takeIf { it != "refusal" }?.let { " · $it" } ?: "") + reason,
                    detail = firstString(obj, "content", "api_refusal_explanation") ?: genericDetail(obj),
                    tone = AgentMessage.EventNote.Tone.WARN,
                ))
            }

            // Refused with NOTHING to fall back to — the turn is dead. Without
            // this the chat just stopped with no explanation on screen.
            // (Tone has no ERROR level; WARN is the loudest one available.)
            "model_refusal_no_fallback" -> listOf(note(
                "model refused${firstString(obj, "original_model", "from_model")?.let { " · $it" } ?: ""}" +
                    " · no fallback available",
                detail = firstString(obj, "content", "api_refusal_explanation") ?: genericDetail(obj),
                tone = AgentMessage.EventNote.Tone.WARN,
            ))

            "permission_denied" -> listOf(note(
                "permission denied · ${firstString(obj, "tool_name") ?: "?"}" +
                    (firstString(obj, "decision_reason", "message")?.let { " · ${it.take(70)}" } ?: ""),
                detail = genericDetail(obj),
                tone = AgentMessage.EventNote.Tone.WARN,
            ))
            "permission_retry" -> listOf(note(
                (firstString(obj, "content") ?: "permission retry").take(140),
                detail = genericDetail(obj),
                tone = AgentMessage.EventNote.Tone.WARN,
            ))

            "api_error", "mirror_error" -> listOf(note(
                "${subtype.replace('_', ' ')}${firstString(obj, "error", "message")?.let { " · ${it.take(100)}" } ?: ""}",
                detail = genericDetail(obj),
                tone = AgentMessage.EventNote.Tone.WARN,
            ))

            "agents_killed" -> listOf(note(
                "background agents stopped",
                tone = AgentMessage.EventNote.Tone.WARN,
            ))

            // Catch-up digest after the user was away — genuinely useful.
            "away_summary" -> listOf(note(
                (firstString(obj, "content") ?: "away summary").take(140),
                detail = firstString(obj, "content"),
                tone = AgentMessage.EventNote.Tone.INFO,
            ))
            "post_turn_summary" -> listOf(note(
                (firstString(obj, "status_detail", "status_category") ?: "turn summary").take(140),
                detail = listOfNotNull(
                    firstString(obj, "status_category")?.let { "category: $it" },
                    firstString(obj, "needs_action")?.let { "needs action: $it" },
                ).joinToString("\n").ifBlank { null },
                tone = AgentMessage.EventNote.Tone.INFO,
            ))

            "memory_recall" -> {
                val n = SilentlyTry.logged("SshAi-ClaudeParse", "memories array") {
                    obj["memories"]?.jsonArray
                }?.size ?: 0
                listOf(note(
                    "memory · recalled $n",
                    detail = genericDetail(obj),
                    tone = AgentMessage.EventNote.Tone.INFO,
                ))
            }
            "memory_saved" -> {
                val paths = SilentlyTry.logged("SshAi-ClaudeParse", "written paths") {
                    obj["written_paths"]?.jsonArray
                }
                listOf(note(
                    "memory · saved ${paths?.size ?: 1}",
                    detail = paths?.joinToString("\n") { it.jsonPrimitive.contentOrNull.orEmpty() },
                    tone = AgentMessage.EventNote.Tone.INFO,
                ))
            }

            "notification" -> listOf(note(
                (firstString(obj, "text") ?: "notification").take(140),
                detail = genericDetail(obj),
                tone = if (firstString(obj, "priority") in setOf("high", "immediate"))
                    AgentMessage.EventNote.Tone.WARN else AgentMessage.EventNote.Tone.INFO,
            ))
            "informational" -> listOf(note(
                (firstString(obj, "content") ?: "info").take(140),
                detail = firstString(obj, "content"),
                tone = if (firstString(obj, "level") == "warning")
                    AgentMessage.EventNote.Tone.WARN else AgentMessage.EventNote.Tone.INFO,
            ))

            "scheduled_task_fire" -> listOf(note(
                "scheduled task · ${(firstString(obj, "content") ?: "fired").take(100)}",
                detail = firstString(obj, "content"),
                tone = AgentMessage.EventNote.Tone.INFO,
            ))

            "elicitation_complete" -> listOf(note(
                "input received" + (firstString(obj, "mcp_server_name")?.let { " · $it" } ?: ""),
            ))

            // Same internal plumbing as file-history-snapshot — file snapshots
            // Claude takes to support undo. Not user-facing; hide (user, 2026-06-14).
            "file_snapshot" -> emptyList()
            "commands_changed" -> {
                val n = SilentlyTry.logged("SshAi-ClaudeParse", "commands array") {
                    obj["commands"]?.jsonArray
                }?.size ?: 0
                listOf(note("commands · $n available"))
            }
            "bridge_state" -> listOf(note(
                "bridge · ${firstString(obj, "state") ?: "?"}" +
                    (firstString(obj, "detail")?.let { " · ${it.take(80)}" } ?: ""),
            ))
            "local_command" -> listOf(note(
                (firstString(obj, "content") ?: "local command").take(140),
            ))
            "session_state_changed" -> listOf(note(
                "session · ${firstString(obj, "state") ?: "?"}",
            ))

            // UNKNOWN subtype — render generically, NEVER swallow. A future
            // CLI release's new event shows up by itself.
            else -> if (subtype.isBlank()) emptyList()
            else listOf(note(genericLabel(subtype, obj), detail = genericDetail(obj)))
        }
    }

    /** First non-blank string among [keys] in [obj]. */
    private fun firstString(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> obj.string(k)?.takeIf { it.isNotBlank() } }

    private val NOISE_KEYS = setOf(
        "type", "subtype", "session_id", "uuid", "parent_tool_use_id", "timestamp",
    )

    /** `subtype · best-effort summary` for events we have no tailored
     *  label for — including subtypes that don't exist yet. */
    private fun genericLabel(subtype: String, obj: JsonObject): String {
        val text = firstString(
            obj, "message", "text", "title", "description", "summary",
            "name", "content", "reason", "status", "state",
        )
        return subtype.replace('_', ' ') + (text?.let { " · ${it.take(100)}" } ?: "")
    }

    /** Expandable key:value dump of the payload minus envelope noise. */
    private fun genericDetail(obj: JsonObject): String? {
        val parts = obj.entries
            .filter { it.key !in NOISE_KEYS }
            .joinToString("\n") { (k, v) -> "$k: ${v.toString().take(300)}" }
        return parts.ifBlank { null }
    }

    /** EventNote factory — the visible replacement for the old suppressed
     *  `simpleEvent` raw lines. */
    private fun note(
        label: String,
        detail: String? = null,
        tone: AgentMessage.EventNote.Tone = AgentMessage.EventNote.Tone.DIM,
        id: String = uuid(),
    ): AgentMessage = AgentMessage.EventNote(id = id, label = label, detail = detail, tone = tone)

    private fun parseAttachment(obj: JsonObject, raw: String): List<AgentMessage> {
        val attachment = SilentlyTry.logged("SshAi-ClaudeParse", "read attachment obj") { obj["attachment"]?.jsonObject } ?: return emptyList()
        return when (attachment.string("type")) {
            "task_reminder" -> {
                val n = attachment["itemCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                if (n == 0) emptyList() else listOf(note("task reminder · $n items"))
            }
            // Session-init / tool-registry plumbing — NOT chat content. These fire
            // on every connect and on every MCP/tool-set change and just clutter
            // the conversation. Suppress.
            "skill_listing" -> emptyList()
            "deferred_tools_delta" -> emptyList()
            "queued_command" -> {
                val text = attachment.string("prompt").orEmpty().take(80)
                listOf(note("queued${if (text.isNotEmpty()) " · $text" else ""}"))
            }
            "edited_text_file" -> {
                val name = attachment.string("filename").orEmpty().substringAfterLast('/').take(60)
                listOf(note("edited · $name"))
            }
            // `/effort ultracode` writes this when the session enters ultracode.
            // It's the ONLY effort change Claude records in the session file —
            // the regular levels (low/medium/high/xhigh/max) never appear here
            // (verified 2026-06-13: grep found only ultra_effort_enter). Mirror
            // it as a non-rendering System so the topbar shows the effort the
            // session ACTUALLY runs at, exactly like model_observed mirrors the
            // model. Stable id → one upserting row.
            "ultra_effort_enter" -> listOf(
                AgentMessage.System(
                    id = OBSERVED_REASONING_ID, subtype = "reasoning_observed",
                    reasoning = "ultracode", raw = "",
                )
            )
            else -> emptyList()
        }
    }


    private fun parseAssistant(obj: JsonObject, raw: String): List<AgentMessage> {
        val msg = obj["message"]?.jsonObject ?: return emptyList()
        // No msg.id from Claude is rare — when it does happen we fall
        // back to a content-addressed id so re-parses match. Mirrors
        // the fast-path msgIdHint=null branch.
        val msgId = msg["id"]?.jsonPrimitive?.contentOrNull ?: stableId(raw, "amsg")
        val content = msg["content"] ?: return emptyList()
        val blocks = parseContentBlocks(content, fromAssistant = true, msgIdHint = msgId, rawLine = raw)
        // PICK UP THE SESSION'S MODEL. Claude stamps the actual model it
        // auto-picked into every assistant turn's `message.model`. Stable id
        // → upserts in place (one row, updated if the model ever changes
        // mid-session); subtype "model_observed" is dropped by
        // ChatMessageLines so it never shows as a banner. ONLY a real model
        // id. Claude stamps synthetic events (compaction summaries, injected
        // context) with `"model":"<synthetic>"` — taking that as the session
        // model put literal "<synthetic>" in the topbar (user, 2026-06-13).
        // Skip anything that isn't a concrete model.
        val model = msg["model"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.startsWith("<") }
            ?: return blocks
        return buildList {
            add(AgentMessage.System(id = OBSERVED_MODEL_ID_PREFIX + msgId, subtype = "model_observed", model = model, raw = ""))
            addAll(blocks)
        }
    }

    private fun parseUser(obj: JsonObject, raw: String): List<AgentMessage> {
        // Drop CLI-injected meta turns (slash/skill expansions, caveat headers,
        // task notifications). Verified marker: top-level "isMeta":true; a real
        // typed prompt has isMeta:null (skill_probe, 2026-06-14). Hidden from chat
        // (the raw stays in the cache for search) — exactly what the TUI does.
        if (obj["isMeta"]?.jsonPrimitive?.contentOrNull == "true") return emptyList()
        val msg = obj["message"]?.jsonObject ?: return emptyList()
        val content = msg["content"] ?: return emptyList()
        return parseContentBlocks(content, fromAssistant = false, msgIdHint = null, rawLine = raw)
    }

    /**
     * @param msgIdHint Claude's stable per-message id ("msg_..."). When the
     * agent streams partial messages every delta arrives with the same hint,
     * so we tag each `AssistantText` with `${msgIdHint}#${blockIndex}` and the
     * upsert-on-id logic in `AgentSession.emitMsg` replaces the existing
     * block in history rather than appending — the bubble grows in place.
     */
    private fun parseContentBlocks(
        content: JsonElement,
        fromAssistant: Boolean,
        msgIdHint: String?,
        rawLine: String,
    ): List<AgentMessage> {
        val out = mutableListOf<AgentMessage>()
        // When Claude gave us a stable msgId, keep the
        // `$msgId#$blockIdx` scheme that AgentSession.emitMsg upserts
        // on for streaming partials. Otherwise derive a content-
        // addressed id from the raw line so re-parsing the same JSONL
        // line yields the same ids. Salt distinguishes synthetic-user
        // (System) / assistant / user emissions that could otherwise
        // share a hash for the same block index.
        fun textId(blockIndex: Int, salt: String): String =
            if (msgIdHint != null) "$msgIdHint#$blockIndex" else stableId(rawLine, "${salt}_$blockIndex")
        val arr = SilentlyTry.logged("SshAi-ClaudeParse", "cast content to JsonArray") { content.jsonArray }
        if (arr == null) {
            val txt = content.jsonPrimitive.contentOrNull.orEmpty()
            if (txt.isNotBlank()) {
                if (!fromAssistant && isSyntheticUserText(txt)) {
                    // Synthetic-user payload (auto-compact handoff,
                    // <system-reminder>, etc.) — emit as a typed System
                    // message so the chat can hide it via subtype while
                    // the search indexer can still find it. Storing the
                    // text in `raw` keeps the System data class shape
                    // unchanged.
                    out += AgentMessage.System(
                        id = textId(0, "sys"),
                        subtype = "user_synthetic",
                        raw = txt,
                    )
                    return out
                }
                val id = textId(0, if (fromAssistant) "a" else "u")
                out += if (fromAssistant) AgentMessage.AssistantText(id, txt)
                else AgentMessage.UserText(id, txt)
            }
            return out
        }
        arr.forEachIndexed { idx, block ->
            val o = SilentlyTry.logged("SshAi-ClaudeParse", "cast block to JsonObject") { block.jsonObject } ?: return@forEachIndexed
            when (o.string("type")) {
                "text" -> {
                    val text = o.string("text").orEmpty()
                    if (text.isNotBlank()) {
                        if (!fromAssistant && isSyntheticUserText(text)) {
                            out += AgentMessage.System(
                                id = textId(idx, "sys"),
                                subtype = "user_synthetic",
                                raw = text,
                            )
                            return@forEachIndexed
                        }
                        val id = textId(idx, if (fromAssistant) "a" else "u")
                        out += if (fromAssistant) AgentMessage.AssistantText(id, text)
                        else AgentMessage.UserText(id, text)
                    }
                }
                "thinking" -> {
                    val visible = o.string("thinking").orEmpty()
                    if (visible.isNotBlank()) {
                        out += AgentMessage.Raw(uuid(), "· thinking · ${visible.take(80)}")
                    }
                }
                "tool_use" -> {
                    val name = o.string("name") ?: "tool"
                    val inputObj = SilentlyTry.logged("SshAi-ClaudeParse", "tool_use input obj") { o["input"]?.jsonObject }
                    // AskUserQuestion in a MIRRORED session lands as a plain tool_use
                    // in the file (the control_request path only exists when WE drive
                    // the turn). Render it as the same option card the CLI shows —
                    // read-only, since the answer can only be given in the CLI session.
                    val questions = if (name == "AskUserQuestion" && inputObj != null)
                        ClaudeControlWire.parseAskQuestions(inputObj) else emptyList()
                    if (questions.isNotEmpty()) {
                        out += AgentMessage.AskUserQuestion(
                            id = o.string("id") ?: uuid(),
                            requestId = "",          // mirror — no control id to answer to
                            questions = questions,
                            readOnly = true,
                        )
                    } else {
                        out += AgentMessage.ToolUse(
                            id = o.string("id") ?: uuid(),
                            toolName = name,
                            input = o["input"]?.toString().orEmpty()
                        )
                    }
                }
                "tool_result" -> {
                    val outputElem = o["content"]
                    val outputText = when {
                        outputElem == null -> ""
                        outputElem is kotlinx.serialization.json.JsonPrimitive -> outputElem.contentOrNull.orEmpty()
                        outputElem is kotlinx.serialization.json.JsonArray -> {
                            outputElem.mapNotNull { b ->
                                SilentlyTry.logged("SshAi-ClaudeParse", "extract tool_result text") {
                                    b.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                                }
                            }.joinToString("\n").ifBlank { outputElem.toString() }
                        }
                        else -> outputElem.toString()
                    }
                    val isErr = o.string("is_error") == "true" || o["is_error"]?.toString() == "true"
                    // The CLI echoes our OWN deny reason back as an is_error
                    // tool_result when the user taps Deny (or types past a live
                    // question). The permission/question card already flipped to
                    // "Denied" — surfacing this as a red error on top is the scary
                    // "! Error" the user flagged. Render it as a calm dim note.
                    if (isErr && (
                            outputText.contains(ClaudeControlWire.DENY_PERMISSION_REASON) ||
                            outputText.contains(ClaudeControlWire.DENY_KEPT_GOING_REASON)
                        )
                    ) {
                        out += note("declined")
                    } else {
                        out += AgentMessage.ToolResult(
                            id = uuid(),
                            toolUseId = o.string("tool_use_id") ?: "",
                            output = outputText,
                            isError = isErr,
                        )
                    }
                }
                "image" -> out += AgentMessage.Raw(uuid(), "· image attached")
            }
        }
        return out
    }

    private fun JsonObject.string(key: String): String? =
        SilentlyTry.logged("SshAi-ClaudeParse", "read string field '$key'") { this[key]?.jsonPrimitive?.contentOrNull }

    private fun uuid(): String = ParserHelpers.uuid()
}
