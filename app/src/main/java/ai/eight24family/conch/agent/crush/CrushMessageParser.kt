package ai.eight24family.conch.agent.crush

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.agent.spec.stableId
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for **Crush**. Unlike every other agent here, Crush has **no event
 * stream**: `crush run -q` prints the assistant's final message as raw
 * markdown (verified to contain not one ESC byte) and nothing else. So this
 * parser handles two very different inputs:
 *
 * 1. **Live stdout** — plain prose. Each line becomes assistant text, with the
 *    CLI's own error banners promoted to errors. Tool calls are INVISIBLE on
 *    this channel; that is the CLI's design, not a gap in the parsing.
 * 2. **A replayed transcript** — the single JSON document from
 *    `crush session show <id> --json`, which is where the structure lives:
 *    ```jsonc
 *    {"meta":{"id":…,"cost":…,"prompt_tokens":…,"completion_tokens":…},
 *     "messages":[
 *       {"role":"user","parts":[{"type":"text","text":"run echo"},{"type":"finish",…}]},
 *       {"role":"assistant","model":"…","parts":[
 *          {"type":"tool_call","tool_call_id":"…","name":"bash","input":"{\"command\":…}"},
 *          {"type":"finish","reason":"tool_use"}]},
 *       {"role":"tool","parts":[{"type":"tool_result","tool_call_id":"…","content":"…"}]},
 *       {"role":"assistant","parts":[{"type":"text",…},{"type":"finish","reason":"end_turn"}]}]}
 *    ```
 *    The whole document arrives as ONE "line" and expands into the turn's
 *    messages. `tool_call.input` is a JSON-encoded STRING — a nested parse,
 *    not an object.
 */
object CrushMessageParser {

    private val json get() = ParserHelpers.json

    /** ESC-anchored CSI stripper. Crush's `run` output is ANSI-free, but its
     *  error banners are drawn by the same TUI toolkit and a `--debug` run is
     *  not, so the guard stays. */
    private val ANSI = Regex(Char(27) + Regex.escape("[") + "[0-?]*[ -/]*[@-~]")

    /** The CLI's own not-ready banner and friends. Crush hard-wraps stderr at
     *  ~118 columns and pads it, so whitespace is collapsed before matching. */
    private val ERROR_HINT = Regex(
        "No providers configured|error:|failed to|unknown (shorthand )?flag|not found",
        RegexOption.IGNORE_CASE,
    )

    fun parse(line: String): List<AgentMessage> {
        val trimmed = ANSI.replace(line, "").trim()
        if (trimmed.isEmpty()) return emptyList()

        // ── Replayed transcript: one whole JSON document. ──
        if (trimmed.startsWith("{") && trimmed.contains("\"messages\"")) {
            parseTranscript(trimmed)?.let { return it }
        }

        // ── Live stdout: prose. ──
        val flat = trimmed.replace(Regex("\\s+"), " ").trim()
        if (ERROR_HINT.containsMatchIn(flat)) {
            return listOf(AgentMessage.Error(stableId(trimmed, "err"), flat))
        }
        // Crush's own decorative frame lines ("ERROR" banners are boxed) carry
        // no words — dropping them keeps the chat readable.
        if (flat.none { it.isLetterOrDigit() }) return emptyList()
        return listOf(AgentMessage.AssistantText(stableId(trimmed, "a"), trimmed))
    }

    /** `crush session show --json` → the turn's messages, in order. */
    private fun parseTranscript(doc: String): List<AgentMessage>? {
        val obj = SilentlyTry.logged(TAG, "parse transcript") {
            json.parseToJsonElement(doc).jsonObject
        } ?: return null
        val messages = SilentlyTry.logged(TAG, "read messages") {
            obj["messages"]?.jsonArray
        } ?: return null

        val out = ArrayList<AgentMessage>()
        messages.forEachIndexed { i, m ->
            val msg = SilentlyTry.logged(TAG, "cast message") { m.jsonObject } ?: return@forEachIndexed
            val role = msg.str("role").orEmpty()
            val parts = SilentlyTry.logged(TAG, "read parts") { msg["parts"]?.jsonArray }
                ?: return@forEachIndexed
            // Ids must survive a re-parse (the search indexer and the live
            // history have to agree), and Crush's messages carry no id of
            // their own in this view — so they are content-addressed with the
            // message's position as the salt.
            val base = stableId(doc, "m$i")
            val text = StringBuilder()
            parts.forEach { p ->
                val part = SilentlyTry.logged(TAG, "cast part") { p.jsonObject } ?: return@forEach
                when (part.str("type")) {
                    "text" -> part.str("text")?.let { text.append(it) }
                    "tool_call" -> out += AgentMessage.ToolUse(
                        id = part.str("tool_call_id") ?: "$base-tc",
                        toolName = part.str("name") ?: "tool",
                        // `input` is a JSON document encoded INSIDE a string —
                        // passed through as-is; the UI renders it verbatim.
                        input = part.str("input").orEmpty(),
                    )
                    "tool_result" -> out += AgentMessage.ToolResult(
                        id = "$base-tr",
                        toolUseId = part.str("tool_call_id").orEmpty(),
                        output = part.str("content").orEmpty(),
                        isError = part.str("is_error") == "true",
                    )
                    // `finish` closes a message; `reason == "end_turn"` is the
                    // turn boundary, which the spec's turn-state reads.
                    else -> Unit
                }
            }
            val body = text.toString().trim()
            if (body.isNotEmpty()) {
                out += when (role) {
                    "user" -> AgentMessage.UserText("$base-u", body)
                    else -> AgentMessage.AssistantText("$base-a", body)
                }
            }
        }
        // The document's own totals — Crush counts per SESSION, not per turn,
        // so this is labelled as the session's running cost rather than being
        // passed off as this turn's.
        obj.obj("meta")?.let { meta ->
            val inTok = meta.num("prompt_tokens") ?: 0L
            val outTok = meta.num("completion_tokens") ?: 0L
            val cost = (meta["cost"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            if (inTok > 0L || outTok > 0L) {
                val parts = listOfNotNull(
                    "in ${k(inTok)}",
                    "out ${k(outTok)}",
                    cost?.takeIf { it > 0.0 }
                        ?.let { "$" + String.format(java.util.Locale.US, "%.4f", it) },
                )
                out += AgentMessage.EventNote(
                    id = stableId(doc, "meta"),
                    label = "session total · ${parts.joinToString(" · ")}",
                )
            }
        }
        return out
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        SilentlyTry.logged(TAG, "read obj '$key'") { this[key]?.jsonObject }

    private fun JsonObject.str(key: String): String? =
        SilentlyTry.logged(TAG, "read str '$key'") { this[key]?.jsonPrimitive?.contentOrNull }

    private fun JsonObject.num(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

    private fun k(n: Long): String =
        if (n >= 1000) "${"%.1f".format(java.util.Locale.US, n / 1000.0)}k" else n.toString()

    private const val TAG = "Conch-CrushParse"
}
