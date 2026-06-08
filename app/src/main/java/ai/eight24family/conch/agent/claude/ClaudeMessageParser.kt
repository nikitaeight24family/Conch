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

        val reader = android.util.JsonReader(java.io.StringReader(line))
        val out = mutableListOf<AgentMessage>()
        try {
            reader.beginObject()
            var msgId: String? = null
            var contentHandled = false
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "message" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "id" -> msgId = reader.nextString()
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

    private fun parseObject(obj: JsonObject, raw: String): List<AgentMessage> {
        return when (obj.string("type")) {
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
                val isError = obj["is_error"]?.jsonPrimitive?.contentOrNull == "true" ||
                    subtype == "error" || subtype.startsWith("error_")
                if (isError) {
                    val body = text.orEmpty()
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
                        )
                    )
                } else {
                    listOf(AgentMessage.Result(uuid(), subtype, text))
                }
            }
            "error" -> {
                val msg = obj.string("message") ?: raw
                val isOverload = msg.matchesOverloaded()
                listOf(
                    AgentMessage.Error(
                        id = if (isOverload) OVERLOAD_BANNER_ID else uuid(),
                        text = if (isOverload) "Service is busy" else msg,
                        kind = if (isOverload) "overloaded" else null,
                        details = if (isOverload) "Try again in a moment, or switch to a different model." else null,
                    )
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
            "file-history-snapshot" -> {
                val backups = SilentlyTry.logged("SshAi-ClaudeParse", "count file-history backups") {
                    obj["snapshot"]?.jsonObject?.get("trackedFileBackups")?.jsonObject?.size
                } ?: 0
                if (backups > 0) listOf(simpleEvent("file backup · $backups files"))
                else emptyList()
            }
            "last-prompt" -> emptyList()
            "queue-operation" -> emptyList()  // duplicates the user's prompt — skip
            "attachment" -> parseAttachment(obj, raw)
            "summary" -> {
                val s = obj.string("summary").orEmpty().take(120)
                if (s.isBlank()) emptyList() else listOf(simpleEvent("summary · $s"))
            }
            else -> emptyList()
        }
    }

    private fun parseSystem(obj: JsonObject, raw: String): List<AgentMessage> {
        val subtype = obj.string("subtype").orEmpty()
        // Stream-json `init` event has full session metadata.
        if (subtype == "init" || obj.contains("model") || obj.contains("session_id")) {
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
            "turn_duration" -> {
                val ms = obj["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
                val msgs = obj["messageCount"]?.jsonPrimitive?.contentOrNull ?: "?"
                listOf(simpleEvent("turn · ${ms / 1000}s · $msgs msgs"))
            }
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
                    listOf(simpleEvent("api retry${if (attempt != null) " · $attempt" else ""}${if (err.isNotBlank()) " · $err" else ""}"))
                }
            }
            "plugin_install" -> {
                val name = obj.string("name").orEmpty()
                val status = obj.string("status").orEmpty()
                if (name.isBlank()) emptyList()
                else listOf(simpleEvent("plugin · $name${if (status.isNotBlank()) " · $status" else ""}"))
            }
            else -> if (subtype.isBlank()) emptyList() else listOf(simpleEvent("system · $subtype"))
        }
    }

    private fun parseAttachment(obj: JsonObject, raw: String): List<AgentMessage> {
        val attachment = SilentlyTry.logged("SshAi-ClaudeParse", "read attachment obj") { obj["attachment"]?.jsonObject } ?: return emptyList()
        return when (attachment.string("type")) {
            "task_reminder" -> {
                val n = attachment["itemCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                if (n == 0) emptyList() else listOf(simpleEvent("task reminder · $n items"))
            }
            "skill_listing" -> {
                val text = attachment.string("content").orEmpty()
                val count = text.count { it == '\n' }.let { if (it == 0 && text.isNotBlank()) 1 else it }
                listOf(simpleEvent("skills loaded · $count entries"))
            }
            "deferred_tools_delta" -> {
                val added = SilentlyTry.logged("SshAi-ClaudeParse", "count addedNames") { attachment["addedNames"]?.jsonArray?.size } ?: 0
                val removed = SilentlyTry.logged("SshAi-ClaudeParse", "count removedNames") { attachment["removedNames"]?.jsonArray?.size } ?: 0
                val parts = listOfNotNull(
                    if (added > 0) "+$added" else null,
                    if (removed > 0) "-$removed" else null
                )
                if (parts.isEmpty()) emptyList()
                else listOf(simpleEvent("tools changed · ${parts.joinToString(" ")}"))
            }
            "queued_command" -> {
                val text = attachment.string("prompt").orEmpty().take(80)
                listOf(simpleEvent("queued${if (text.isNotEmpty()) " · $text" else ""}"))
            }
            "edited_text_file" -> {
                val name = attachment.string("filename").orEmpty().substringAfterLast('/').take(60)
                listOf(simpleEvent("edited · $name"))
            }
            else -> emptyList()
        }
    }

    private fun simpleEvent(label: String): AgentMessage = AgentMessage.Raw(uuid(), "· $label")

    private fun parseAssistant(obj: JsonObject, raw: String): List<AgentMessage> {
        val msg = obj["message"]?.jsonObject ?: return emptyList()
        // No msg.id from Claude is rare — when it does happen we fall
        // back to a content-addressed id so re-parses match. Mirrors
        // the fast-path msgIdHint=null branch.
        val msgId = msg["id"]?.jsonPrimitive?.contentOrNull ?: stableId(raw, "amsg")
        val content = msg["content"] ?: return emptyList()
        return parseContentBlocks(content, fromAssistant = true, msgIdHint = msgId, rawLine = raw)
    }

    private fun parseUser(obj: JsonObject, raw: String): List<AgentMessage> {
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
                    out += AgentMessage.ToolUse(
                        id = o.string("id") ?: uuid(),
                        toolName = o.string("name") ?: "tool",
                        input = o["input"]?.toString().orEmpty()
                    )
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
                    out += AgentMessage.ToolResult(
                        id = uuid(),
                        toolUseId = o.string("tool_use_id") ?: "",
                        output = outputText,
                        isError = o.string("is_error") == "true" || o["is_error"]?.toString() == "true"
                    )
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
