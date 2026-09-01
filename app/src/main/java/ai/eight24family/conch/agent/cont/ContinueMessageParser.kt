package ai.eight24family.conch.agent.cont

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
 * Parser for **Continue CLI**. Like Crush, it has no event stream — two very
 * different inputs arrive here:
 *
 * 1. **Live `--format json` output**: ONE object after the turn finishes.
 *    ```jsonc
 *    {"response":"…","status":"success","note":"Response was not valid JSON, so it was wrapped…"}
 *    {"status":"error","message":"…"}      // on stderr, exit 1
 *    ```
 *    ⚠ The wrapper is ABSENT when the model's own answer is already valid
 *    JSON — the CLI then prints that answer bare. So a `{…}` with no `status`
 *    and no `history` is the assistant's reply, not a malformed envelope.
 *    Tool calls produce NO stdout at all; they exist only in the session file.
 *
 * 2. **A replayed session file** (`<config>/sessions/<id>.json`), which is
 *    where the structure lives:
 *    ```jsonc
 *    {"sessionId":"…","title":"…","workspaceDirectory":"…",
 *     "history":[
 *       {"message":{"role":"user","content":"…"},"editorState":"…"},
 *       {"message":{"role":"assistant","content":"","toolCalls":[
 *          {"id":"call_1","function":{"name":"Bash","arguments":"{\"command\":…}"}}],
 *        "usage":{"prompt_tokens":…,"completion_tokens":…}},
 *        "toolCallStates":[{"toolCallId":"call_1","status":"done","output":[{"content":"…"}]}]}],
 *     "usage":{"totalCost":…,"promptTokens":…,"completionTokens":…}}
 *    ```
 *    `function.arguments` is a JSON document encoded inside a STRING.
 */
object ContinueMessageParser {

    private val json get() = ParserHelpers.json

    /** ESC-anchored CSI stripper — an unanchored one eats empty JSON arrays. */
    private val ANSI = Regex(Char(27) + Regex.escape("[") + "[0-?]*[ -/]*[@-~]")

    fun parse(line: String): List<AgentMessage> {
        val trimmed = ANSI.replace(line, "").trim()
        if (trimmed.isEmpty()) return emptyList()

        if (!trimmed.startsWith("{")) {
            // stderr is merged; a config or provider failure arrives as prose.
            return listOf(AgentMessage.Raw(ParserHelpers.uuid(), trimmed))
        }

        // ── Replayed session file (collapsed to one line by the read path). ──
        if (trimmed.contains("\"history\"")) {
            parseSessionFile(trimmed)?.let { return it }
        }

        val obj = SilentlyTry.logged(TAG, "parse result") {
            json.parseToJsonElement(trimmed).jsonObject
        } ?: return listOf(AgentMessage.Raw(ParserHelpers.uuid(), trimmed))

        // ── Live turn result. ──
        val status = obj.str("status")
        if (status == "error") {
            return listOf(
                AgentMessage.Error(
                    id = stableId(trimmed, "err"),
                    text = obj.str("message") ?: trimmed,
                ),
            )
        }
        val response = obj.str("response")
        if (response != null) {
            return buildList {
                if (response.isNotBlank()) {
                    add(AgentMessage.AssistantText(stableId(trimmed, "a"), response))
                }
                add(AgentMessage.TurnEnd(stableId(trimmed, "end"), status ?: "result"))
            }
        }
        // No `status`, no `response`: the model's own answer was valid JSON and
        // the CLI printed it bare. Show it as the reply rather than swallowing
        // it as an unrecognised envelope.
        return listOf(AgentMessage.AssistantText(stableId(trimmed, "a"), trimmed))
    }

    /** The saved session → its turns, in order. */
    private fun parseSessionFile(doc: String): List<AgentMessage>? {
        val root = SilentlyTry.logged(TAG, "parse session file") {
            json.parseToJsonElement(doc).jsonObject
        } ?: return null
        val history = SilentlyTry.logged(TAG, "read history") {
            root["history"] as? JsonArray
        } ?: return null

        val out = ArrayList<AgentMessage>()
        history.forEachIndexed { i, h ->
            val entry = SilentlyTry.logged(TAG, "cast entry") { h.jsonObject } ?: return@forEachIndexed
            val base = stableId(doc, "h$i")
            val message = entry.obj("message")
            val role = message?.str("role").orEmpty()
            val content = message?.str("content").orEmpty()
            if (content.isNotBlank()) {
                out += if (role == "user") AgentMessage.UserText("$base-u", content)
                else AgentMessage.AssistantText("$base-a", content)
            }
            // Tool CALLS live on the message, their RESULTS in a sibling array
            // keyed by the same id — the join is `toolCallId`, not position.
            (message?.get("toolCalls") as? JsonArray)?.forEach { c ->
                val call = SilentlyTry.logged(TAG, "cast toolCall") { c.jsonObject } ?: return@forEach
                val fn = call.obj("function")
                out += AgentMessage.ToolUse(
                    id = call.str("id") ?: "$base-tc",
                    toolName = fn?.str("name") ?: "tool",
                    // `arguments` is a JSON document inside a string; passed
                    // through verbatim rather than double-decoded.
                    input = fn?.str("arguments").orEmpty(),
                )
            }
            (entry["toolCallStates"] as? JsonArray)?.forEach { s ->
                val st = SilentlyTry.logged(TAG, "cast toolCallState") { s.jsonObject } ?: return@forEach
                val outputs = (st["output"] as? JsonArray)?.mapNotNull { o ->
                    SilentlyTry.logged(TAG, "read output content") {
                        o.jsonObject["content"]?.jsonPrimitive?.contentOrNull
                    }
                }?.joinToString("\n").orEmpty()
                val status = st.str("status").orEmpty()
                out += AgentMessage.ToolResult(
                    id = "$base-tr-${st.str("toolCallId").orEmpty()}",
                    toolUseId = st.str("toolCallId").orEmpty(),
                    output = outputs,
                    isError = status.equals("errored", ignoreCase = true) ||
                        status.equals("error", ignoreCase = true),
                )
            }
        }
        // Session totals — labelled as such, because Continue counts per
        // session and passing that off as this turn's cost would inflate it.
        root.obj("usage")?.let { u ->
            val inTok = u.num("promptTokens") ?: 0L
            val outTok = u.num("completionTokens") ?: 0L
            val cost = (u["totalCost"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            if (inTok > 0L || outTok > 0L) {
                val parts = listOfNotNull(
                    "in ${k(inTok)}",
                    "out ${k(outTok)}",
                    cost?.takeIf { it > 0.0 }
                        ?.let { "$" + String.format(java.util.Locale.US, "%.4f", it) },
                )
                out += AgentMessage.EventNote(
                    id = stableId(doc, "usage"),
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

    private const val TAG = "Conch-ContinueParse"
}
