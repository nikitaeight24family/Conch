package ai.eight24family.conch.agent.grok

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.claude.ClaudeMessageParser
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.agent.spec.stableId
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for **xAI Grok Build** output. Two distinct shapes flow through it:
 *
 * 1. **The LIVE stream** (`grok -p … --output-format streaming-messages-json
 *    --include-partial-messages`) — by Grok's own docs this is "the Anthropic
 *    Messages API stream-json wire format" (`system/init` → `assistant`/`user`
 *    → `result`, plus `stream_event` deltas), emitted so that a Claude-format
 *    consumer "works without changes". So those lines are DELEGATED to
 *    [ClaudeMessageParser] — a deliberate wire-format reuse (the CLI promises
 *    the compatibility), not a UI-identity one. Grok's native NDJSON
 *    (`streaming-json`) was rejected as the live format because its
 *    text/thought chunks carry no ids, which a stateless parser can't
 *    accumulate.
 *
 * 2. **The saved session file** (`~/.grok/sessions/<cwd>/<uuid>/updates.jsonl`)
 *    — consolidated ACP `session/update` records (verified live on 1.0.5):
 *    ```json
 *    {"timestamp":…,"method":"session/update","params":{"sessionId":"…",
 *      "update":{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"…"}},
 *      "_meta":{"eventId":"<sid>-2",…}}}
 *    {"…","update":{"sessionUpdate":"agent_thought_chunk","content":{…}},…}
 *    {"…","update":{"sessionUpdate":"agent_message_chunk","content":{…}},…}
 *    {"timestamp":…,"method":"_x.ai/session/update","params":{…,
 *      "update":{"sessionUpdate":"turn_completed","stop_reason":"end_turn",
 *        "usage":{"inputTokens":…,"outputTokens":…,…}}}}
 *    ```
 *    Each line is one WHOLE message block (the CLI consolidates its stream
 *    chunks before persisting), with a stable `_meta.eventId` reused as the
 *    message id so re-parses and the search indexer agree.
 *
 * A defensive third shape — Grok's native `streaming-json` events
 * (`{"type":"text"|"thought"|"tool_call"|"usage"|"end"|"error"}`), in case a session is ever
 * launched with that flag — is mapped minimally rather than dropped.
 */
object GrokMessageParser {

    private val json get() = ParserHelpers.json

    /** CSI/SGR escapes — same guard Gemini needed for crash output. */
    private val ANSI = Regex(Char(27) + Regex.escape("[") + "[0-?]*[ -/]*[@-~]")

    fun parse(line: String): List<AgentMessage> {
        val trimmed = ANSI.replace(line, "").trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("{")) {
            // stderr is dropped at launch, so a non-JSON stdout line is rare —
            // surface it (it's usually the updater or a crash note).
            return listOf(AgentMessage.Raw(ParserHelpers.uuid(), trimmed))
        }

        // ── Saved-session shape: ACP session/update records. Routed FIRST —
        //    they have "method", never a top-level "type", so the check is
        //    cheap and can't shadow the live stream. ──
        if (trimmed.contains("\"sessionUpdate\"")) {
            parseAcpRecord(trimmed)?.let { return it }
        }

        // ── Native streaming-json safety net. Its type vocabulary is
        //    disjoint from the Claude wire format's, so this can't steal
        //    Claude-format lines. ──
        when (ParserHelpers.quickType(trimmed)) {
            "error" -> {
                val obj = SilentlyTry.logged(TAG, "parse error line") {
                    json.parseToJsonElement(trimmed).jsonObject
                }
                val msg = obj?.str("message") ?: trimmed
                return listOf(AgentMessage.Error(stableId(trimmed, "err"), msg))
            }
            "text" -> return listOf(
                AgentMessage.AssistantText(stableId(trimmed, "a"), nativeData(trimmed) ?: return emptyList()),
            )
            "thought" -> return emptyList() // token-level fragments; unrenderable statelessly
            "tool_call", "tool_call_update", "usage", "plan",
            "available_commands", "end" -> return emptyList() // native-mode bookkeeping
        }

        // ── Live stream: the Claude wire format, by Grok's own contract. ──
        return ClaudeMessageParser.parse(trimmed)
    }

    /** `data` field of a native streaming-json event. */
    private fun nativeData(line: String): String? =
        SilentlyTry.logged(TAG, "read native data") {
            json.parseToJsonElement(line).jsonObject["data"]?.jsonPrimitive?.contentOrNull
        }?.takeIf { it.isNotBlank() }

    /** One consolidated ACP record → message(s). Null = not an ACP record
     *  after all (falls through to the other shapes). */
    private fun parseAcpRecord(line: String): List<AgentMessage>? {
        val obj = SilentlyTry.logged(TAG, "parse acp record") {
            json.parseToJsonElement(line).jsonObject
        } ?: return null
        val params = obj.obj("params") ?: return null
        val update = params.obj("update") ?: return null
        val kind = update.str("sessionUpdate") ?: return null
        // Stable id: the CLI's own eventId when present (unique per record),
        // else content-addressed off the raw line.
        val eventId = update.obj("_meta")?.str("eventId")
            ?: params.obj("_meta")?.str("eventId")
        fun id(salt: String) = eventId ?: stableId(line, salt)

        return when (kind) {
            "user_message_chunk" -> {
                val text = contentText(update) ?: return emptyList()
                listOf(AgentMessage.UserText(id("u"), text))
            }
            "agent_message_chunk" -> {
                val text = contentText(update) ?: return emptyList()
                listOf(AgentMessage.AssistantText(id("a"), text))
            }
            "agent_thought_chunk" -> {
                val text = contentText(update) ?: return emptyList()
                listOf(
                    AgentMessage.EventNote(
                        id = id("th"),
                        label = "thinking · ${text.replace(Regex("\\s+"), " ").take(100)}",
                        detail = text,
                    ),
                )
            }
            "tool_call" -> {
                val callId = update.str("toolCallId") ?: id("tc")
                listOf(
                    AgentMessage.ToolUse(
                        id = callId,
                        toolName = update.str("title") ?: update.str("toolName") ?: "tool",
                        input = update["rawInput"]?.toString().orEmpty(),
                    ),
                )
            }
            "tool_call_update" -> {
                val callId = update.str("toolCallId") ?: return emptyList()
                val status = update.str("status").orEmpty()
                // Only terminal updates render — in_progress churn would spam.
                if (status != "completed" && status != "failed") return emptyList()
                listOf(
                    AgentMessage.ToolResult(
                        id = id("tr"),
                        toolUseId = callId,
                        output = update["rawOutput"]?.toString()
                            ?: update["content"]?.toString().orEmpty(),
                        isError = status == "failed",
                    ),
                )
            }
            "turn_completed" -> {
                // Per-turn token line — the same «tokens · in X · out Y» shape
                // every agent emits (cross-agent invariant, 2026-06-12).
                val usage = update.obj("usage")
                val inTok = usage?.num("inputTokens") ?: 0L
                val outTok = usage?.num("outputTokens") ?: 0L
                if (inTok == 0L && outTok == 0L) return emptyList()
                val reasoning = usage?.num("reasoningTokens") ?: 0L
                val cached = usage?.num("cachedReadTokens") ?: 0L
                val parts = listOfNotNull(
                    "in ${k(inTok)}",
                    "out ${k(outTok)}",
                    reasoning.takeIf { it > 0 }?.let { "thinking ${k(it)}" },
                    cached.takeIf { it > 0 }?.let { "cached ${k(it)}" },
                )
                listOf(
                    AgentMessage.EventNote(
                        id = id("done"),
                        label = "tokens · ${parts.joinToString(" · ")}",
                    ),
                )
            }
            "current_mode_update", "available_commands_update", "plan" ->
                emptyList() // session bookkeeping, no chat row
            else -> {
                // Unknown record — never swallowed silently: generic
                // `kind · summary` note with the payload expandable.
                listOf(
                    AgentMessage.EventNote(
                        id = id("gen"),
                        label = kind.replace('_', ' '),
                        detail = update.toString().take(2000),
                    ),
                )
            }
        }
    }

    /** `update.content` is `{type:"text",text:"…"}` (single block). */
    private fun contentText(update: JsonObject): String? {
        val content = update.obj("content") ?: return null
        return content.str("text")?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        SilentlyTry.logged(TAG, "read obj '$key'") { this[key]?.jsonObject }

    private fun JsonObject.str(key: String): String? =
        SilentlyTry.logged(TAG, "read str '$key'") { this[key]?.jsonPrimitive?.contentOrNull }

    private fun JsonObject.num(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun k(n: Long): String =
        if (n >= 1000) "${"%.1f".format(java.util.Locale.US, n / 1000.0)}k" else n.toString()

    private const val TAG = "SshAi-GrokParse"
}
