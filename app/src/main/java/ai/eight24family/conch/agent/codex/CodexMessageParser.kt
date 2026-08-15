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
 * Unknown shapes surface as generic [AgentMessage.EventNote] rows (label =
 * type + best-effort summary, payload in the expandable detail) — NEVER
 * silently dropped (user, 2026-06-12). The schema split is THE root cause
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
    /** Injected-wrapper tag at the start of a "user" payload:
     *  `<environment_context>`, `<user_instructions>`, `<turn_aborted>`,
     *  `<system-reminder>`, `<command-name>`, `<recommended_plugins>`, … —
     *  every wrapper the CLIs inject is a MULTI-WORD lower snake/kebab tag.
     *  This was an exact-name blacklist, and every codex release that added
     *  a wrapper we hadn't heard of (latest: `<recommended_plugins>`) leaked
     *  it into session titles/previews as a garbage first line. The shape is
     *  the invariant, not the names: a human message virtually never OPENS
     *  with `<multi_word-tag>`, while pasted HTML/XML opens with single-word
     *  tags (`<div>`, `<html>`) or capitalized components (`<MyWidget>`),
     *  which this regex deliberately does not match. */
    private val syntheticWrapperTag =
        Regex("^<[a-z][a-z0-9]*(?:[_-][a-z0-9]+)+>")

    fun isSyntheticUserText(t: String): Boolean {
        val s = t.trimStart()
        if (s.isEmpty()) return false
        return syntheticWrapperTag.containsMatchIn(s) ||
            s.startsWith("<INSTRUCTIONS>") ||
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
            // Per-turn launch metadata (cwd / model / approval policy) —
            // same information the init row already carries.
            "turn_context" -> emptyList()
            "compacted" -> listOf(note("context compacted", tone = AgentMessage.EventNote.Tone.INFO))

            else -> {
                // UNKNOWN top-level type — surface generically, never
                // swallow. `null` type = malformed → Raw.
                if (type == null) listOf(AgentMessage.Raw(uuid(), trimmed))
                else listOf(note(genericLabel(type, obj), detail = genericDetail(obj)))
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
        // Per-turn usage line — same «tokens · in X · out Y» shape the
        // Claude result branch emits, so all three agents read alike.
        val usage = SilentlyTry.logged("SshAi-CodexParse", "read usage obj") { obj["usage"]?.jsonObject }
        val input = usage?.string("input_tokens")?.toLongOrNull()
        val output = usage?.string("output_tokens")?.toLongOrNull()
        val cached = usage?.string("cached_input_tokens")?.toLongOrNull()
        // Guard on > 0 — a zero-usage turn_completed (interrupted / no output)
        // must read "turn complete", not "tokens · in 0 · out 0" (parity with
        // the Claude fix, user 2026-08-11).
        val parts = listOfNotNull(
            input?.takeIf { it > 0 }?.let { "in ${k(it)}" },
            output?.takeIf { it > 0 }?.let { "out ${k(it)}" },
            cached?.takeIf { it > 0 }?.let { "cached ${k(it)}" },
        )
        val label = if (parts.isEmpty()) "turn complete" else "tokens · " + parts.joinToString(" · ")
        return listOf(note(label))
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
                else listOf(note("thinking · ${text.take(120)}", detail = text.takeIf { it.length > 120 }))
            }
            "command_execution" -> {
                val command = item.string("command").orEmpty()
                val aggregated = item.string("aggregated_output").orEmpty()
                val exitCode = item.string("exit_code")
                val status = item.string("status").orEmpty()
                val isError = exitCode != null && exitCode != "0"
                if (isStarted || status == "in_progress") {
                    // Stable id: item.updated re-parses replace the same
                    // row instead of stacking "exec · …" copies.
                    listOf(note("exec · ${command.take(120)}", id = "codexevt-exec-$turnTag$itemId"))
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
                // Full change list in the expandable detail.
                val all = changes?.mapNotNull { c ->
                    SilentlyTry.logged("SshAi-CodexParse", "read change row") {
                        val o = c.jsonObject
                        listOfNotNull(o.string("kind"), o.string("path")).joinToString(" ")
                    }
                }?.filter { it.isNotBlank() }?.joinToString("\n")
                listOf(note("$label$tail", detail = all?.takeIf { count > 1 },
                    id = "codexevt-files-$turnTag$itemId"))
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
                else listOf(note("web search · $query", id = "codexevt-web-$turnTag$itemId"))
            }
            "todo_list" -> {
                val items = SilentlyTry.logged("SshAi-CodexParse", "read todo items array") { item["items"]?.jsonArray }
                val total = items?.size ?: 0
                val done = items?.count { SilentlyTry.loggedOrElse("SshAi-CodexParse", "check todo completed", false) { it.jsonObject.string("completed") == "true" } } ?: 0
                if (total == 0) emptyList()
                else {
                    val list = items?.mapNotNull { t ->
                        SilentlyTry.logged("SshAi-CodexParse", "read todo row") {
                            val o = t.jsonObject
                            val mark = if (o.string("completed") == "true") "✓" else "·"
                            o.string("text")?.let { "$mark $it" }
                        }
                    }?.joinToString("\n")
                    // Stable id: every todo_list update replaces the row
                    // in place — a live progress widget, not a log spam.
                    listOf(note("todo · $done/$total", detail = list,
                        id = "codexevt-todo-$turnTag$itemId"))
                }
            }
            "error" -> {
                val msg = item.string("message") ?: item.string("error") ?: "error"
                listOf(AgentMessage.Error(uuid(), msg))
            }
            // UNKNOWN item type — render generically, never swallow.
            else -> listOf(note(genericLabel(itemType, item), detail = genericDetail(item)))
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
                else listOf(note("thinking · ${summary.take(80)}", detail = summary.takeIf { it.length > 80 }))
            }
            // UNKNOWN payload type — render generically, never swallow.
            else -> {
                val t = payload.string("type") ?: return emptyList()
                listOf(note(genericLabel(t, payload), detail = genericDetail(payload)))
            }
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
            // system / developer / tool roles carry injected context
            // (AGENTS.md, env). Compact note, full text in the detail —
            // visible but never buries the chat.
            else -> listOf(note("context · $role", detail = text,
                id = stableId(rawLine, "ctx")))
        }
    }

    private fun parseEventMsg(obj: JsonObject): List<AgentMessage> {
        val payload = SilentlyTry.logged("SshAi-CodexParse", "read event_msg payload") { obj["payload"]?.jsonObject } ?: return emptyList()
        return when (val ptype = payload.string("type")) {
            "agent_message", "user_message" -> emptyList()   // duplicates response_item.message
            // Token-by-token / chunked delta streams — bookkeeping only,
            // surfacing each delta would spam a note per chunk. The final
            // agent_reasoning / agent_message event carries the full text.
            "agent_message_delta", "agent_reasoning_delta",
            "agent_reasoning_raw_content_delta", "agent_reasoning_section_break",
            "exec_command_output_delta", "mcp_tool_call_output_delta" -> emptyList()
            "agent_reasoning", "agent_reasoning_raw_content" -> {
                val text = payload.string("text") ?: payload.string("content") ?: ""
                if (text.isBlank()) emptyList()
                else listOf(note("thinking · ${text.take(120)}", detail = text.takeIf { it.length > 120 }))
            }
            "task_started" -> listOf(note("turn started"))
            "task_complete" -> {
                val cost = payload.string("cost_usd")
                val ms = payload.string("duration_ms")?.toLongOrNull()
                val parts = listOfNotNull(
                    ms?.let { "${it / 1000}s" },
                    cost?.let { "\$$it" }
                )
                listOf(note("turn complete${if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")}"))
            }
            "turn_aborted" -> {
                val reason = payload.string("reason") ?: "interrupted"
                listOf(note("turn aborted · $reason", tone = AgentMessage.EventNote.Tone.WARN))
            }
            "context_compacted" -> listOf(note("context compacted", tone = AgentMessage.EventNote.Tone.INFO))
            "exec_command_begin" -> {
                val cmd = formatCommand(payload["command"]) ?: "shell"
                listOf(note("exec · ${cmd.take(120)}",
                    id = "codexevt-exec-${payload.string("call_id") ?: uuid()}"))
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
                    if (success) note("patched · $name$tail", detail = files.joinToString("\n").takeIf { files.size > 1 })
                    else note("patch failed · $name$tail", tone = AgentMessage.EventNote.Tone.WARN)
                )
            }
            "collab_agent_spawn_end" -> {
                val agentName = payload.string("agent_name") ?: "sub-agent"
                listOf(note("spawned · $agentName"))
            }
            "collab_close_end" -> listOf(note("sub-agent closed"))
            "collab_waiting_end" -> emptyList()
            // Live cumulative token counter — TRANSIENT UI state, not a
            // chat row: AgentSessionRunOneShot regex-feeds it into
            // liveThinkingTokens (same treatment as Claude's
            // thinking_tokens event).
            "token_count" -> emptyList()
            "error" -> {
                val msg = payload.string("message") ?: payload.string("error") ?: "error"
                listOf(AgentMessage.Error(uuid(), msg))
            }
            // UNKNOWN event type — render generically, never swallow.
            else -> {
                if (ptype == null) emptyList()
                else listOf(note(genericLabel(ptype, payload), detail = genericDetail(payload)))
            }
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

    /** EventNote factory — the visible replacement for the old suppressed
     *  `simpleEvent` raw lines. Mirrors ClaudeMessageParser.note.
     *  `internal`: shared with [CodexAppServerEvents] (same chat surface,
     *  different transport). */
    internal fun note(
        label: String,
        detail: String? = null,
        tone: AgentMessage.EventNote.Tone = AgentMessage.EventNote.Tone.DIM,
        id: String = uuid(),
    ): AgentMessage = AgentMessage.EventNote(id = id, label = label, detail = detail, tone = tone)

    private val NOISE_KEYS = setOf("type", "id", "session_id", "call_id", "timestamp")

    /** `type · best-effort summary` for events with no tailored label —
     *  including types that don't exist yet. */
    internal fun genericLabel(type: String, obj: JsonObject): String {
        val text = listOf(
            "message", "text", "title", "description", "summary",
            "name", "content", "reason", "status", "state",
        ).firstNotNullOfOrNull { k -> obj.string(k)?.takeIf { it.isNotBlank() } }
        return type.replace('_', ' ').replace('.', ' ') + (text?.let { " · ${it.take(100)}" } ?: "")
    }

    /** Expandable key:value dump of the payload minus envelope noise. */
    internal fun genericDetail(obj: JsonObject): String? {
        val parts = obj.entries
            .filter { it.key !in NOISE_KEYS }
            .joinToString("\n") { (k, v) -> "$k: ${v.toString().take(300)}" }
        return parts.ifBlank { null }
    }

    /** Locale.US — the ru default formats "12,0k" with a comma. */
    internal fun k(n: Long): String =
        if (n >= 1000) "${"%.1f".format(java.util.Locale.US, n / 1000.0)}k" else n.toString()

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
