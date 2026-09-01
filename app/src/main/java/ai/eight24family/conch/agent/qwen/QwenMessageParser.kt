package ai.eight24family.conch.agent.qwen

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.claude.ClaudeMessageParser
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.agent.spec.stableId
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for **Qwen Code**. Two shapes flow through it, and they are NOT the
 * same vocabulary — this is the fact that makes Qwen its own spec rather than
 * a Gemini variant:
 *
 * 1. **The LIVE stream** (`-o stream-json`) speaks the **Claude Agent SDK**
 *    vocabulary — `system/init` → `assistant` → `user` → `result`, with tool
 *    calls as `tool_use` / `tool_result` CONTENT BLOCKS. Those lines are
 *    delegated to [ClaudeMessageParser]: a wire-format reuse, nothing to do
 *    with Qwen's identity in the UI.
 *
 * 2. **The saved session file** (`~/.qwen/projects/<slug>/chats/<uuid>.jsonl`)
 *    is a Claude-Code-shaped ENVELOPE around a **Gemini-shaped body**
 *    (verified against a real file, 2026-08-28):
 *    ```jsonc
 *    {"uuid":…,"parentUuid":…,"sessionId":…,"timestamp":"…Z","type":"user",
 *     "provenance":"real_user","cwd":…,"message":{"role":"user","parts":[{"text":"…"}]}}
 *    {"…","type":"assistant","model":"qwen3-coder-plus","message":{"role":"model",
 *     "parts":[{"text":"…"},{"functionCall":{"id":…,"name":…,"args":{…}}}]},
 *     "usageMetadata":{"promptTokenCount":…,"candidatesTokenCount":…}}
 *    {"…","type":"tool_result","message":{"role":"user","parts":[{"functionResponse":
 *     {"id":…,"name":…,"response":{"output":"…"}}}]},"toolCallResult":{"status":"success"}}
 *    ```
 *    `role` is `model` (not `assistant`), tool calls are `functionCall` parts,
 *    and a tool RESULT is its own top-level record whose `message.role` is
 *    `"user"` — which is why `provenance` decides what a record is, never the
 *    role. The file never contains a `result` line.
 *
 * The discriminator between the two is `provenance`: present on every
 * persisted record, absent from every live event.
 */
object QwenMessageParser {

    private val json get() = ParserHelpers.json

    /** ESC-anchored CSI stripper — see the sibling parsers: an unanchored
     *  variant eats empty JSON arrays and takes the whole event with them. */
    private val ANSI = Regex(Char(27) + Regex.escape("[") + "[0-?]*[ -/]*[@-~]")

    fun parse(line: String): List<AgentMessage> {
        val trimmed = ANSI.replace(line, "").trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("{")) {
            // stderr is merged at launch, so a pre-stream failure ("No auth
            // type is selected…") arrives as prose and must reach the user.
            return listOf(AgentMessage.Raw(ParserHelpers.uuid(), trimmed))
        }

        // ── Persisted session record (history replay). ──
        if (trimmed.contains("\"provenance\"")) {
            parsePersisted(trimmed)?.let { return it }
        }

        // ── Qwen-only live event: a goal-state ticker with no Claude analogue.
        // Rendered as a dim note rather than dropped, with a stable id so it
        // updates in place. ──
        if (ParserHelpers.quickType(trimmed) == "stream_event") {
            val obj = SilentlyTry.logged(TAG, "parse stream_event") {
                json.parseToJsonElement(trimmed).jsonObject
            } ?: return emptyList()
            val ev = obj.obj("event") ?: return emptyList()
            val kind = ev.str("type") ?: return emptyList()
            val summary = listOf("goal", "state", "status", "message")
                .firstNotNullOfOrNull { k -> ev.str(k)?.takeIf { it.isNotBlank() } }
            return listOf(
                AgentMessage.EventNote(
                    id = "qwen-$kind",
                    label = kind.replace('_', ' ') + (summary?.let { " · ${it.take(100)}" } ?: ""),
                    detail = ev.toString().take(2000),
                ),
            )
        }

        // ── Live stream: the Claude Agent SDK vocabulary, verbatim. ──
        return ClaudeMessageParser.parse(trimmed)
    }

    /** One persisted record → message(s). Null when the line isn't one after
     *  all (falls through to the live paths). */
    private fun parsePersisted(line: String): List<AgentMessage>? {
        val obj = SilentlyTry.logged(TAG, "parse persisted record") {
            json.parseToJsonElement(line).jsonObject
        } ?: return null
        val type = obj.str("type") ?: return null
        val provenance = obj.str("provenance")
        // The record's own uuid is stable across re-parses — the search
        // indexer and the live history must agree on ids.
        val uuid = obj.str("uuid")
        fun id(salt: String) = uuid?.let { "$it#$salt" } ?: stableId(line, salt)
        val parts = obj.obj("message")?.get("parts") as? JsonArray

        return when {
            type == "user" && provenance == "real_user" -> {
                val text = partsText(parts).trim()
                if (text.isEmpty()) emptyList()
                else listOf(AgentMessage.UserText(id("u"), text))
            }
            type == "assistant" -> buildList {
                val text = partsText(parts).trim()
                if (text.isNotEmpty()) add(AgentMessage.AssistantText(id("a"), text))
                parts?.forEach { p ->
                    val call = SilentlyTry.logged(TAG, "read functionCall") {
                        p.jsonObject["functionCall"]?.jsonObject
                    } ?: return@forEach
                    add(
                        AgentMessage.ToolUse(
                            // The call id joins this to its tool_result record.
                            id = call.str("id") ?: id("tc"),
                            toolName = call.str("name") ?: "tool",
                            input = call["args"]?.toString().orEmpty(),
                        ),
                    )
                }
            }
            type == "tool_result" -> {
                val resp = parts?.firstNotNullOfOrNull { p ->
                    SilentlyTry.logged(TAG, "read functionResponse") {
                        p.jsonObject["functionResponse"]?.jsonObject
                    }
                } ?: return emptyList()
                val status = obj.obj("toolCallResult")?.str("status")
                val output = resp.obj("response")?.str("output")
                    ?: resp["response"]?.toString().orEmpty()
                listOf(
                    AgentMessage.ToolResult(
                        id = id("tr"),
                        toolUseId = resp.str("id").orEmpty(),
                        output = output,
                        isError = status != null && !status.equals("success", ignoreCase = true),
                    ),
                )
            }
            // attribution_snapshot / ui_telemetry — the CLI's own bookkeeping,
            // written to every session file. Not chat rows.
            type == "system" -> emptyList()
            else -> null
        }
    }

    /** Join the `text` parts of a Gemini-shaped message body. */
    private fun partsText(parts: JsonArray?): String {
        if (parts == null) return ""
        return parts.mapNotNull { p ->
            SilentlyTry.logged(TAG, "read part text") {
                p.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }
        }.joinToString("")
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        SilentlyTry.logged(TAG, "read obj '$key'") { this[key]?.jsonObject }

    private fun JsonObject.str(key: String): String? =
        SilentlyTry.logged(TAG, "read str '$key'") { this[key]?.jsonPrimitive?.contentOrNull }

    private const val TAG = "Conch-QwenParse"
}
