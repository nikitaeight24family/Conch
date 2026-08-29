package ai.eight24family.conch.agent.cursor

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.agent.spec.stableId
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for **Cursor CLI**'s `--output-format stream-json` NDJSON.
 *
 * ⚠ **It LOOKS Anthropic-shaped and is not** — the three differences that
 * would break a borrowed Claude parser (all read out of the 2026.08.25 bundle,
 * which is the emitter itself):
 *  1. **Tool calls are their own top-level events** (`{"type":"tool_call",
 *     "subtype":"started"|"completed",…}`), not `tool_use` content blocks.
 *  2. **`usage` is camelCase** (`inputTokens`/`outputTokens`/`cacheReadTokens`/
 *     `cacheWriteTokens`), and `inputTokens` is already NET of the cache
 *     fields — summing them would double-count.
 *  3. Assistant messages carry no `id` and no `model`, so ids are derived
 *     content-addressably rather than read off the event.
 *
 * Event vocabulary:
 * ```jsonc
 * {"type":"system","subtype":"init","session_id":…,"model":"Auto","cwd":…,"apiKeySource":…}
 * {"type":"user","message":{"role":"user","content":[{"type":"text","text":…}]},"session_id":…}
 * {"type":"assistant","message":{"role":"assistant","content":[…]},"session_id":…,"model_call_id":…}
 * {"type":"thinking","subtype":"delta"|"completed",…}
 * {"type":"tool_call","subtype":"started"|"completed","call_id":…,"tool_call":{…}}
 * {"type":"interaction_query","subtype":"request"|"response",…}   // approval prompts
 * {"type":"result","subtype":"success","is_error":false,"result":…,"usage":{…}}
 * {"type":"error",…}
 * ```
 */
object CursorMessageParser {

    private val json get() = ParserHelpers.json

    /** ESC-anchored CSI stripper — see the sibling parsers for why the anchor
     *  is mandatory (an unanchored one eats empty JSON arrays). */
    private val ANSI = Regex(Char(27) + Regex.escape("[") + "[0-?]*[ -/]*[@-~]")

    fun parse(line: String): List<AgentMessage> {
        val trimmed = ANSI.replace(line, "").trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("{")) {
            // stderr is merged at launch — "Authentication required. Please run
            // 'agent login' first…" arrives as prose and must reach the user.
            return listOf(AgentMessage.Raw(ParserHelpers.uuid(), trimmed))
        }
        val obj = SilentlyTry.logged(TAG, "parse event") {
            json.parseToJsonElement(trimmed).jsonObject
        } ?: return listOf(AgentMessage.Raw(ParserHelpers.uuid(), trimmed))
        val type = obj.str("type") ?: return emptyList()
        val subtype = obj.str("subtype")

        return when (type) {
            "system" -> when (subtype) {
                "init" -> listOf(
                    AgentMessage.System(
                        id = stableId(trimmed, "sys"),
                        subtype = "init",
                        sessionId = obj.str("session_id"),
                        model = obj.str("model"),
                        cwd = obj.str("cwd"),
                        raw = trimmed,
                    ),
                )
                // task_notification / background_shell_timeout and whatever
                // ships next — surfaced generically, never swallowed.
                else -> listOf(
                    AgentMessage.EventNote(
                        id = stableId(trimmed, "sysnote"),
                        label = "system · ${(subtype ?: "event").replace('_', ' ')}",
                        detail = trimmed.take(2000),
                    ),
                )
            }
            "user" -> {
                val text = contentText(obj).trim()
                if (text.isEmpty()) emptyList()
                else listOf(AgentMessage.UserText(stableId(trimmed, "u"), text))
            }
            "assistant" -> {
                val text = contentText(obj).trim()
                if (text.isEmpty()) emptyList()
                else listOf(AgentMessage.AssistantText(stableId(trimmed, "a"), text))
            }
            "thinking" -> {
                // Deltas are token fragments a stateless parser cannot
                // accumulate; only the completed block becomes a row.
                if (subtype == "delta") return emptyList()
                val text = (obj.str("text") ?: contentText(obj)).trim()
                if (text.isEmpty()) emptyList()
                else listOf(
                    AgentMessage.EventNote(
                        id = stableId(trimmed, "th"),
                        label = "thinking · ${text.replace(Regex("\\s+"), " ").take(100)}",
                        detail = text,
                    ),
                )
            }
            "tool_call" -> {
                val callId = obj.str("call_id") ?: stableId(trimmed, "tc")
                val call = obj.obj("tool_call")
                when (subtype) {
                    "started" -> listOf(
                        AgentMessage.ToolUse(
                            id = callId,
                            toolName = call?.str("name")
                                ?: call?.keys?.firstOrNull()   // proto oneof: the key IS the tool
                                ?: "tool",
                            input = call?.toString().orEmpty(),
                        ),
                    )
                    "completed" -> {
                        val result = call?.get("result")?.toString()
                            ?: obj["result"]?.toString().orEmpty()
                        // A tool the user's mode declined comes back as a
                        // completed call whose result says so — render it as an
                        // error so a silently-declined edit is never mistaken
                        // for a done one.
                        val rejected = result.contains("User Rejected", ignoreCase = true)
                        listOf(
                            AgentMessage.ToolResult(
                                id = stableId(trimmed, "tr"),
                                toolUseId = callId,
                                output = result,
                                isError = rejected || obj.str("is_error") == "true",
                            ),
                        )
                    }
                    else -> emptyList()
                }
            }
            // The approval round-trip. In headless SAFE mode the CLI answers
            // these itself (auto-reject), so they are informational — but they
            // are the ONLY trace that a tool was refused, so they stay visible.
            "interaction_query" -> {
                if (subtype == "response") return emptyList()
                val what = obj.str("message") ?: obj.str("query") ?: "approval requested"
                listOf(
                    AgentMessage.EventNote(
                        id = stableId(trimmed, "iq"),
                        label = "approval · ${what.replace(Regex("\\s+"), " ").take(120)}",
                        detail = trimmed.take(2000),
                        tone = AgentMessage.EventNote.Tone.WARN,
                    ),
                )
            }
            "result" -> buildList {
                add(
                    AgentMessage.Result(
                        id = stableId(trimmed, "res"),
                        subtype = subtype.orEmpty(),
                        text = obj.str("result"),
                    ),
                )
                usageNote(obj, trimmed)?.let { add(it) }
                add(AgentMessage.TurnEnd(stableId(trimmed, "end"), "result"))
            }
            "error" -> listOf(
                AgentMessage.Error(
                    id = stableId(trimmed, "err"),
                    text = obj.str("message") ?: obj.str("error") ?: trimmed,
                ),
            )
            // Transport chatter; nothing for the chat.
            "connection" -> emptyList()
            else -> listOf(
                AgentMessage.EventNote(
                    id = stableId(type, "gen"),
                    label = type.replace('_', ' ') +
                        (subtype?.let { " · ${it.replace('_', ' ')}" } ?: ""),
                    detail = trimmed.take(2000),
                ),
            )
        }
    }

    /**
     * The per-turn token line every agent emits, in Cursor's own camelCase
     * spelling. `inputTokens` is already NET of the cache counters (the CLI
     * subtracts them before emitting), so they are reported side by side and
     * never summed.
     */
    private fun usageNote(obj: JsonObject, raw: String): AgentMessage? {
        val u = obj.obj("usage") ?: return null
        val inTok = u.num("inputTokens") ?: 0L
        val outTok = u.num("outputTokens") ?: 0L
        if (inTok == 0L && outTok == 0L) return null
        val cached = u.num("cacheReadTokens") ?: 0L
        val parts = listOfNotNull(
            "in ${k(inTok)}",
            "out ${k(outTok)}",
            cached.takeIf { it > 0 }?.let { "cached ${k(it)}" },
        )
        return AgentMessage.EventNote(
            id = stableId(raw, "usage"),
            label = "tokens · ${parts.joinToString(" · ")}",
        )
    }

    /** `message.content[]` → joined text (blocks without `text` are skipped). */
    private fun contentText(obj: JsonObject): String {
        val content = SilentlyTry.logged(TAG, "read message.content") {
            obj["message"]?.jsonObject?.get("content")
        } ?: return ""
        return when (content) {
            is JsonArray -> content.mapNotNull { el ->
                SilentlyTry.logged(TAG, "read block text") {
                    el.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }
            }.joinToString("")
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            else -> ""
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        SilentlyTry.logged(TAG, "read obj '$key'") { this[key]?.jsonObject }

    private fun JsonObject.str(key: String): String? =
        SilentlyTry.logged(TAG, "read str '$key'") { this[key]?.jsonPrimitive?.contentOrNull }

    private fun JsonObject.num(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

    private fun k(n: Long): String =
        if (n >= 1000) "${"%.1f".format(java.util.Locale.US, n / 1000.0)}k" else n.toString()

    private const val TAG = "SshAi-CursorParse"
}
