package ai.eight24family.conch.agent.codex

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.agent.spec.stableId
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for **OpenAI Codex CLI**'s `codex exec --json` JSONL output, plus
 * the on-disk rollout files at `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`.
 *
 * Codex shipped **two distinct event schemas** between mid-2025 and the
 * 0.130 (May 2026) release line:
 *
 *   - **NEW (≥ 0.125):**
 *     ```
 *     {"type":"thread.started","thread_id":"..."}
 *     {"type":"turn.started"}
 *     {"type":"item.started","item":{"id":"item_1","type":"command_execution",...}}
 *     {"type":"item.completed","item":{"id":"item_3","type":"agent_message","text":"..."}}
 *     {"type":"turn.completed","usage":{...}}
 *     ```
 *     Top-level types: `thread.started`, `turn.{started|completed|failed}`,
 *     `item.{started|updated|completed}`, `error`. Nested `item.type` ∈
 *     `{agent_message, reasoning, command_execution, file_change,
 *     mcp_tool_call, web_search, todo_list, error}`.
 *
 *   - **OLD (pre-0.125):**
 *     ```
 *     {"type":"session_meta","payload":{"id":"...","cwd":"...","model":"..."}}
 *     {"type":"response_item","payload":{"type":"message","role":"assistant","content":[...]}}
 *     {"type":"event_msg","payload":{"type":"agent_message","message":"..."}}
 *     ```
 *     Top-level types: `session_meta`, `response_item`, `event_msg`,
 *     `turn_context`, `compacted`.
 *
 * **This parser handles both** — top-level `type` decides which branch runs.
 * Unknown shapes are dropped silently. The schema split is THE root cause
 * of the user's "new chat doesn't start" report (research §2, bug #4776):
 * the old parser saw `thread.started` and dropped it → empty chat bubble.
 *
 * **Stream parsing vs saved-file parsing**: the live `codex exec` stdout
 * and the saved rollout JSONL use the SAME shapes, so one parser covers
 * both paths (live runOneShot and history hydration / preview extraction).
 *
 * Sources: research report §2C; takopi exec-JSON cheatsheet; openai/codex
 * discussion #3827 (rollout file format).
 */
object CodexMessageParser {

    private val json get() = ParserHelpers.json

    /**
     * Codex's saved rollouts can contain synthetic "user" payloads that are
     * really CLI-side context injection (AGENTS.md, environment_context,
     * etc.). Hide them from chat and previews.
     */
    fun isSyntheticUserText(t: String): Boolean {
        val s = t.trimStart()
        if (s.isEmpty()) return false
        return s.startsWith("<environment_context>") ||
            s.startsWith("<INSTRUCTIONS>") ||
            s.startsWith("<user_instructions>") ||
            s.startsWith("<turn_aborted>") ||
            s.startsWith("<system-reminder>") ||
            s.startsWith("<command-name>") ||
            s.startsWith("<command-message>") ||
            s.startsWith("<command-args>") ||
            s.startsWith("# AGENTS.md") ||
            s.startsWith("# Skills")
    }

    fun parse(line: String, turnTag: String = ""): List<AgentMessage> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("{")) return listOf(AgentMessage.Raw(uuid(), trimmed))

        // Fast path: token-stream parser (JsonReader). See Claude
        // parser for the architecture rationale — Codex / Claude /
        // Gemini share the fast-vs-slow split.
        ai.eight24family.conch.util.Tracing.section(
            ai.eight24family.conch.util.Tracing.Names.PARSER_FAST_PATH
        ) {
            parseFast(trimmed, turnTag)?.let { return it }
        }

        return ai.eight24family.conch.util.Tracing.section(
            ai.eight24family.conch.util.Tracing.Names.PARSER_SLOW_PATH
        ) {
        val obj = SilentlyTry.logged("SshAi-CodexParse", "parse jsonl line") { json.parseToJsonElement(trimmed).jsonObject }
            ?: return@section listOf(AgentMessage.Raw(uuid(), trimmed))

        when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            // ──── NEW schema (≥ 0.125) ────
            "thread.started" -> parseThreadStarted(obj, trimmed)
            "turn.started" -> emptyList()   // bookkeeping
            "turn.completed" -> parseTurnCompleted(obj)
            "turn.failed" -> {
                val msg = obj["error"]?.jsonObject?.string("message")
                    ?: obj.string("error") ?: "turn failed"
                listOf(AgentMessage.Error(uuid(), msg))
            }
            "item.started" -> parseItem(obj, isStarted = true, turnTag = turnTag, rawLine = trimmed)
            "item.updated" -> parseItem(obj, isStarted = false, turnTag = turnTag, rawLine = trimmed)
            "item.completed" -> parseItem(obj, isStarted = false, turnTag = turnTag, rawLine = trimmed)
            "error" -> {
                val msg = obj.string("message") ?: obj["error"]?.jsonObject?.string("message")
                    ?: trimmed
                listOf(AgentMessage.Error(uuid(), msg))
            }

            // ──── OLD schema (pre-0.125) ────
            "session_meta" -> parseSessionMeta(obj, trimmed)
            "response_item" -> parseResponseItem(obj, trimmed)
            "event_msg" -> parseEventMsg(obj)

            else -> {
                // Pre-emptive defence against future schema-drift: drop
                // unknown event types silently rather than dumping them
                // raw into the chat. `null` type means malformed → Raw.
                if (type == null) listOf(AgentMessage.Raw(uuid(), trimmed)) else emptyList()
            }
        }
        } // Tracing.section PARSER_SLOW_PATH
    }

    // ────────── NEW SCHEMA (≥ 0.125) ──────────

    private fun parseThreadStarted(obj: JsonObject, raw: String): List<AgentMessage> {
        val threadId = obj.string("thread_id")
        // First event of a fresh session — surface as System(init) so
        // AgentSession captures the id for subsequent --resume calls.
        return listOf(
            AgentMessage.System(
                id = stableId(raw, "sys"),
                subtype = "init",
                sessionId = threadId,
                model = null,
                cwd = null,
                version = null,
                toolCount = 0,
                raw = raw
            )
        )
    }

    private fun parseTurnCompleted(obj: JsonObject): List<AgentMessage> {
        val usage = SilentlyTry.logged("SshAi-CodexParse", "read usage obj") { obj["usage"]?.jsonObject }
        val input = usage?.string("input_tokens")
        val output = usage?.string("output_tokens")
        val cached = usage?.string("cached_input_tokens")
        val parts = listOfNotNull(
            input?.let { "in $it" },
            output?.let { "out $it" },
            cached?.let { "cached $it" },
        )
        val label = if (parts.isEmpty()) "turn complete" else "turn complete · " + parts.joinToString(" · ")
        return listOf(simpleEvent(label))
    }

    private fun parseItem(
        obj: JsonObject,
        isStarted: Boolean,
        turnTag: String = "",
        rawLine: String = "",
    ): List<AgentMessage> {
        val item = SilentlyTry.logged("SshAi-CodexParse", "read item obj") { obj["item"]?.jsonObject } ?: return emptyList()
        // Codex always supplies item.id in practice; the fallback is
        // content-addressed (instead of UUID) so the rare malformed
        // line still re-parses to the same id. Tool/error branches
        // pass `itemId` only as `toolUseId` (correlator, not own id),
        // so the random ↔ stable swap is invisible for them.
        val itemId = item.string("id") ?: stableId(rawLine, "item")
        val itemType = item.string("type") ?: return emptyList()

        return when (itemType) {
            "agent_message" -> {
                val text = item.string("text").orEmpty()
                if (text.isBlank()) return emptyList()
                // Tag with the stable item id so item.started/updated/completed
                // deltas for the same message replace the same bubble instead
                // of stacking N copies — same trick we use for Claude's
                // partial-message streaming via msgId#blockIndex.
                //
                // CRITICAL: prefix with turnTag — codex resets its
                // `item.id` counter each `codex exec` invocation, so
                // `item_1` in turn 2 == `item_1` in turn 1 byte-for-
                // byte. Without the turn prefix `emitMsg`'s
                // replace-in-place logic overwrites turn 1's bubble
                // at its OLD position with turn 2's text, scrambling
                // the visible chat order.
                listOf(AgentMessage.AssistantText("codex_${turnTag}$itemId", text))
            }
            "reasoning" -> {
                if (isStarted) return emptyList()  // wait for completed text
                val text = item.string("text").orEmpty()
                if (text.isBlank()) emptyList()
                else listOf(AgentMessage.Raw(uuid(), "· thinking · ${text.take(120)}"))
            }
            "command_execution" -> {
                val command = item.string("command").orEmpty()
                val aggregated = item.string("aggregated_output").orEmpty()
                val exitCode = item.string("exit_code")
                val status = item.string("status").orEmpty()
                val isError = exitCode != null && exitCode != "0"
                if (isStarted || status == "in_progress") {
                    listOf(simpleEvent("exec · ${command.take(120)}"))
                } else {
                    listOf(
                        AgentMessage.ToolResult(
                            id = uuid(),
                            toolUseId = itemId,
                            output = buildString {
                                if (command.isNotBlank()) appendLine("$ $command")
                                if (aggregated.isNotBlank()) append(aggregated)
                            },
                            isError = isError
                        )
                    )
                }
            }
            "file_change" -> {
                val changes = SilentlyTry.logged("SshAi-CodexParse", "read changes array") { item["changes"]?.jsonArray }
                val count = changes?.size ?: 0
                val first = changes?.firstOrNull()?.jsonObject
                val path = first?.string("path")?.substringAfterLast('/')
                val kind = first?.string("kind")
                val tail = if (count > 1) " (+${count - 1} more)" else ""
                val label = listOfNotNull(kind, path).joinToString(" ").ifBlank { "files" }
                listOf(simpleEvent("$label$tail"))
            }
            "mcp_tool_call" -> {
                val server = item.string("server").orEmpty()
                val tool = item.string("tool").orEmpty()
                val args = item["arguments"]?.toString().orEmpty()
                if (isStarted) {
                    listOf(AgentMessage.ToolUse(uuid(), "$server/$tool", args))
                } else {
                    val output = item.string("result") ?: item.string("output") ?: ""
                    val status = item.string("status").orEmpty()
                    listOf(
                        AgentMessage.ToolResult(
                            id = uuid(),
                            toolUseId = itemId,
                            output = output,
                            isError = status.equals("error", ignoreCase = true)
                        )
                    )
                }
            }
            "web_search" -> {
                val query = item.string("query").orEmpty().take(80)
                if (query.isBlank()) emptyList()
                else listOf(simpleEvent("web search · $query"))
            }
            "todo_list" -> {
                val items = SilentlyTry.logged("SshAi-CodexParse", "read todo items array") { item["items"]?.jsonArray }
                val total = items?.size ?: 0
                val done = items?.count { SilentlyTry.loggedOrElse("SshAi-CodexParse", "check todo completed", false) { it.jsonObject.string("completed") == "true" } } ?: 0
                if (total == 0) emptyList()
                else listOf(simpleEvent("todo · $done/$total"))
            }
            "error" -> {
                val msg = item.string("message") ?: item.string("error") ?: "error"
                listOf(AgentMessage.Error(uuid(), msg))
            }
            else -> emptyList()
        }
    }

    // ────────── OLD SCHEMA (pre-0.125) ──────────

    private fun parseSessionMeta(obj: JsonObject, raw: String): List<AgentMessage> {
        val payload = SilentlyTry.logged("SshAi-CodexParse", "read session meta payload") { obj["payload"]?.jsonObject } ?: return emptyList()
        return listOf(
            AgentMessage.System(
                id = stableId(raw, "sys"),
                subtype = "init",
                sessionId = payload.string("id"),
                // Only the actual model id ('gpt-5.4'). Do NOT fall
                // back to payload.string("model_provider") — that's
                // the vendor name ('openai') and was getting shown
                // as the chat's model in the topbar.
                model = payload.string("model"),
                cwd = payload.string("cwd"),
                version = payload.string("cli_version"),
                toolCount = 0,
                raw = raw
            )
        )
    }

    private fun parseResponseItem(obj: JsonObject, rawLine: String): List<AgentMessage> {
        val payload = SilentlyTry.logged("SshAi-CodexParse", "read response_item payload") { obj["payload"]?.jsonObject } ?: return emptyList()
        return when (payload.string("type")) {
            "message" -> parseOldMessage(payload, rawLine)
            "function_call" -> {
                val name = payload.string("name") ?: "tool"
                val args = payload.string("arguments").orEmpty()
                listOf(AgentMessage.ToolUse(uuid(), name, args))
            }
            "function_call_output" -> {
                val output = payload["output"]?.let { extractOutputText(it) }.orEmpty()
                val isError = output.contains("exited with code", ignoreCase = true) &&
                    !output.contains("exited with code 0")
                listOf(
                    AgentMessage.ToolResult(
                        id = uuid(),
                        toolUseId = payload.string("call_id").orEmpty(),
                        output = output,
                        isError = isError
                    )
                )
            }
            "reasoning" -> {
                val summary = SilentlyTry.logged("SshAi-CodexParse", "build reasoning summary") {
                    payload["summary"]?.jsonArray
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.joinToString(" ")
                }.orEmpty()
                if (summary.isBlank()) emptyList()
                else listOf(simpleEvent("thinking · ${summary.take(80)}"))
            }
            else -> emptyList()
        }
    }

    private fun parseOldMessage(payload: JsonObject, rawLine: String): List<AgentMessage> {
        val role = payload.string("role") ?: return emptyList()
        val content = payload["content"] ?: return emptyList()
        val text = extractText(content).trim()
        if (text.isBlank()) return emptyList()
        if (role == "user" && isSyntheticUserText(text)) {
            // Synthetic-user payload — emit as System(subtype="user_synthetic")
            // so chat can hide it via subtype while the search indexer
            // can still find it. Mirrors the Claude parser.
            return listOf(
                AgentMessage.System(
                    id = stableId(rawLine, "sys"),
                    subtype = "user_synthetic",
                    raw = text,
                )
            )
        }
        return when (role) {
            "user" -> listOf(AgentMessage.UserText(stableId(rawLine, "u"), text))
            "assistant" -> listOf(AgentMessage.AssistantText(stableId(rawLine, "a"), text))
            else -> emptyList()
        }
    }

    private fun parseEventMsg(obj: JsonObject): List<AgentMessage> {
        val payload = SilentlyTry.logged("SshAi-CodexParse", "read event_msg payload") { obj["payload"]?.jsonObject } ?: return emptyList()
        return when (payload.string("type")) {
            "agent_message", "user_message" -> emptyList()   // duplicates response_item.message
            "task_started" -> listOf(simpleEvent("turn started"))
            "task_complete" -> {
                val cost = payload.string("cost_usd")
                val ms = payload.string("duration_ms")?.toLongOrNull()
                val parts = listOfNotNull(
                    ms?.let { "${it / 1000}s" },
                    cost?.let { "\$$it" }
                )
                listOf(simpleEvent("turn complete${if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")}"))
            }
            "turn_aborted" -> {
                val reason = payload.string("reason") ?: "interrupted"
                listOf(simpleEvent("turn aborted · $reason"))
            }
            "context_compacted" -> listOf(simpleEvent("context compacted"))
            "exec_command_begin" -> {
                val cmd = formatCommand(payload["command"]) ?: "shell"
                listOf(simpleEvent("exec · ${cmd.take(120)}"))
            }
            "exec_command_end" -> {
                val cmd = formatCommand(payload["command"])
                val out = payload.string("aggregated_output").orEmpty()
                val exit = payload.string("exit_code")
                val isError = exit != null && exit != "0"
                listOf(
                    AgentMessage.ToolResult(
                        id = uuid(),
                        toolUseId = payload.string("call_id").orEmpty(),
                        output = buildString {
                            if (cmd != null) appendLine("$ $cmd")
                            if (out.isNotEmpty()) append(out)
                        },
                        isError = isError
                    )
                )
            }
            "patch_apply_end" -> {
                val success = payload.string("success") == "true"
                val files = SilentlyTry.logged("SshAi-CodexParse", "read patch_apply files") {
                    payload["changes"]?.jsonObject?.keys?.toList().orEmpty()
                } ?: emptyList()
                val name = files.firstOrNull()?.substringAfterLast('/') ?: "file"
                val tail = if (files.size > 1) " (+${files.size - 1} more)" else ""
                listOf(
                    simpleEvent(
                        if (success) "patched · $name$tail"
                        else "patch failed · $name$tail"
                    )
                )
            }
            "collab_agent_spawn_end" -> {
                val agentName = payload.string("agent_name") ?: "sub-agent"
                listOf(simpleEvent("spawned · $agentName"))
            }
            "collab_close_end" -> listOf(simpleEvent("sub-agent closed"))
            "collab_waiting_end" -> emptyList()
            "token_count" -> emptyList()
            "error" -> {
                val msg = payload.string("message") ?: payload.string("error") ?: "error"
                listOf(AgentMessage.Error(uuid(), msg))
            }
            else -> emptyList()
        }
    }

    // ────────── helpers ──────────

    private fun extractText(content: JsonElement): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { block ->
            SilentlyTry.logged("SshAi-CodexParse", "extract content block text") {
                val o = block.jsonObject
                o.string("text") ?: o.string("content")
            }
        }.joinToString("\n")
        else -> ""
    }

    private fun extractOutputText(elem: JsonElement): String = when (elem) {
        is JsonPrimitive -> elem.contentOrNull.orEmpty()
        is JsonArray -> elem.mapNotNull { b ->
            SilentlyTry.logged("SshAi-CodexParse", "extract output text block") { b.jsonObject.string("text") }
        }.joinToString("\n")
        is JsonObject -> elem["output"]?.let { extractOutputText(it) }
            ?: elem.string("text")
            ?: elem.toString()
        else -> elem.toString()
    }

    private fun formatCommand(elem: JsonElement?): String? {
        if (elem == null) return null
        val arr = SilentlyTry.logged("SshAi-CodexParse", "cast to JsonArray") { elem.jsonArray } ?: return elem.toString()
        val parts = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return when {
            parts.size >= 3 && parts[0].endsWith("bash") && (parts[1] == "-lc" || parts[1] == "-c") -> parts[2]
            else -> parts.joinToString(" ")
        }
    }

    private fun JsonObject.string(key: String): String? =
        SilentlyTry.logged("SshAi-CodexParse", "read string field '$key'") { this[key]?.jsonPrimitive?.contentOrNull }

    private fun simpleEvent(label: String): AgentMessage = AgentMessage.Raw(uuid(), "· $label")

    private fun uuid(): String = ParserHelpers.uuid()

    private fun quickType(line: String): String? = ParserHelpers.quickType(line)

    /**
     * Token-stream parser for `item.started` / `item.updated` /
     * `item.completed` events with item.type == "agent_message".
     *
     * That's the hot path: each streaming delta of an assistant reply
     * arrives as one such line. The slow tree-based path built a full
     * JsonObject per delta — 50-100×/sec during a live reply — just to
     * pull out item.id + item.text. Pull parser walks the line once.
     *
     * Returns null for anything else (other top-level types, or an
     * item with type != "agent_message" such as command_execution,
     * reasoning, file_change, mcp_tool_call). Caller falls through to
     * the tree path.
     */
    private fun parseFast(line: String, turnTag: String): List<AgentMessage>? {
        val type = quickType(line) ?: return null
        if (type != "item.started" && type != "item.updated" && type != "item.completed") {
            return null
        }

        val reader = android.util.JsonReader(java.io.StringReader(line))
        try {
            reader.beginObject()
            var itemId: String? = null
            var itemType: String? = null
            var itemText: String? = null
            var sawComplexField = false
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "item" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "id" -> itemId = reader.nextString()
                                "type" -> itemType = reader.nextString()
                                "text" -> itemText = reader.nextString()
                                else -> {
                                    // Any other field signals a complex
                                    // item type (command_execution,
                                    // reasoning, file_change, mcp_*).
                                    reader.skipValue()
                                    sawComplexField = true
                                }
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (itemType != "agent_message" || sawComplexField) return null
            val text = itemText.orEmpty()
            if (text.isBlank()) return emptyList()
            // Codex always supplies item.id for agent_message; falling
            // back to a content-addressed id keeps re-parses idempotent
            // on the rare malformed line.
            val msgId = itemId ?: stableId(line, "a")
            return listOf(AgentMessage.AssistantText("codex_${turnTag}$msgId", text))
        } catch (_: Throwable) {
            return null
        } finally {
            SilentlyTry.fired("SshAi-CodexParse", "close reader") { reader.close() }
        }
    }
}
