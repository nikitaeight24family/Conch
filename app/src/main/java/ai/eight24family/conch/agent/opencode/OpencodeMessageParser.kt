package ai.eight24family.conch.agent.opencode

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
 * Parser for **opencode**'s `run --format json` NDJSON.
 *
 * The emitter is one function in the binary, so the vocabulary is exactly six
 * types — no guessing:
 * ```js
 * JSON.stringify({type, timestamp, sessionID, ...payload}) + "\n"
 * ```
 * `step_start` · `tool_use` · `text` · `reasoning` · `step_finish` · `error`.
 *
 * Shapes, from real captures (2026-08-28):
 * ```jsonc
 * {"type":"step_start","sessionID":"ses_…","part":{"type":"step-start",…}}
 * {"type":"tool_use","sessionID":"ses_…","part":{"type":"tool","tool":"read",
 *   "callID":"…","state":{"status":"completed","input":{…},"output":"…","title":"README.md"}}}
 * {"type":"text","sessionID":"ses_…","part":{"type":"text","text":"`second line`"}}
 * {"type":"step_finish","sessionID":"ses_…","part":{"reason":"tool-calls",
 *   "tokens":{"total":8862,"input":8512,"output":94,"cache":{"read":256,"write":0}},"cost":0}}
 * {"type":"error","sessionID":"ses_…","error":{"name":"UnknownError","data":{"ref":"err_…"}}}
 * ```
 *
 * ⚠ Two things a naive mapping gets wrong:
 *  - **`tool_use` is emitted only ONCE per call, already terminal** (status
 *    `completed` or `error`) — there is no separate "started" event on this
 *    channel, so one event yields BOTH the call and its result.
 *  - **`error` payloads are deliberately opaque**: the human-readable cause
 *    lives in the log line carrying the same `ref`, which is why the launch
 *    passes `--print-logs --log-level ERROR` and this parser surfaces those
 *    plain-text log lines instead of discarding them as noise.
 */
object OpencodeMessageParser {

    private val json get() = ParserHelpers.json

    /** ESC-anchored CSI stripper — an unanchored one eats empty JSON arrays. */
    private val ANSI = Regex(Char(27) + Regex.escape("[") + "[0-?]*[ -/]*[@-~]")

    /** logfmt ERROR lines from `--print-logs`, e.g.
     *  `level=ERROR … message=failed ref=err_e5e6d915 error="ProviderModelNotFoundError: …"`. */
    private val LOG_ERROR = Regex("""level=ERROR""")
    private val LOG_ERROR_TEXT = Regex("""error="((?:[^"\\]|\\.)*)"""")

    fun parse(line: String): List<AgentMessage> {
        val trimmed = ANSI.replace(line, "").trim()
        if (trimmed.isEmpty()) return emptyList()

        // ── Replayed transcript: the whole `opencode export` document, which
        //    the read command collapses onto one line. Its shape is NOT the
        //    live vocabulary: `{info, messages:[{info, parts:[…]}]}`. ──
        if (trimmed.startsWith("{") && trimmed.contains("\"messages\"")) {
            parseTranscript(trimmed)?.let { return it }
        }

        if (!trimmed.startsWith("{")) {
            // `--print-logs` mirrors logfmt to stderr, which we merge. An
            // ERROR line is the ONLY place the real cause of an opaque JSON
            // error appears, so it is promoted to a visible error; everything
            // else at lower levels is dropped.
            if (LOG_ERROR.containsMatchIn(trimmed)) {
                val detail = LOG_ERROR_TEXT.find(trimmed)?.groupValues?.get(1)
                    ?.replace("\\\"", "\"") ?: trimmed
                return listOf(AgentMessage.Error(stableId(trimmed, "logerr"), detail))
            }
            return emptyList()
        }

        val obj = SilentlyTry.logged(TAG, "parse event") {
            json.parseToJsonElement(trimmed).jsonObject
        } ?: return listOf(AgentMessage.Raw(ParserHelpers.uuid(), trimmed))
        val type = obj.str("type") ?: return emptyList()
        val part = obj.obj("part")

        return when (type) {
            // Marks a model step opening; the session id rides every event, so
            // this is where a fresh chat learns the id it must resume with.
            "step_start" -> listOf(
                AgentMessage.System(
                    id = stableId(trimmed, "sys"),
                    subtype = "init",
                    sessionId = obj.str("sessionID"),
                    raw = trimmed,
                ),
            )
            "text" -> {
                val text = part?.str("text")?.takeIf { it.isNotBlank() } ?: return emptyList()
                listOf(AgentMessage.AssistantText(part.str("id") ?: stableId(trimmed, "a"), text))
            }
            "reasoning" -> {
                val text = part?.str("text")?.takeIf { it.isNotBlank() } ?: return emptyList()
                listOf(
                    AgentMessage.EventNote(
                        id = part.str("id") ?: stableId(trimmed, "th"),
                        label = "thinking · ${text.replace(Regex("\\s+"), " ").take(100)}",
                        detail = text,
                    ),
                )
            }
            "tool_use" -> {
                val p = part ?: return emptyList()
                val callId = p.str("callID") ?: stableId(trimmed, "tc")
                val state = p.obj("state")
                val status = state?.str("status").orEmpty()
                val name = p.str("tool") ?: "tool"
                // One event, both halves: the call as it was made and the
                // result it already carries.
                buildList {
                    add(
                        AgentMessage.ToolUse(
                            id = callId,
                            toolName = name,
                            input = state?.get("input")?.toString().orEmpty(),
                        ),
                    )
                    val output = state?.str("output")
                        ?: state?.str("error")
                        ?: state?.get("metadata")?.toString().orEmpty()
                    add(
                        AgentMessage.ToolResult(
                            id = stableId(trimmed, "tr"),
                            toolUseId = callId,
                            output = output,
                            // An auto-rejected permission arrives as status
                            // "error" with the rejection as the message — the
                            // only trace that a tool was declined.
                            isError = status.equals("error", ignoreCase = true),
                        ),
                    )
                }
            }
            "step_finish" -> {
                val tokens = part?.obj("tokens")
                val inTok = tokens?.num("input") ?: 0L
                val outTok = tokens?.num("output") ?: 0L
                if (inTok == 0L && outTok == 0L) return emptyList()
                val cacheRead = tokens?.obj("cache")?.num("read") ?: 0L
                val reasoning = tokens?.num("reasoning") ?: 0L
                val cost = (part?.get("cost") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                val parts = listOfNotNull(
                    "in ${k(inTok)}",
                    "out ${k(outTok)}",
                    reasoning.takeIf { it > 0 }?.let { "thinking ${k(it)}" },
                    cacheRead.takeIf { it > 0 }?.let { "cached ${k(it)}" },
                    cost?.takeIf { it > 0.0 }?.let { "$" + String.format(java.util.Locale.US, "%.4f", it) },
                )
                listOf(
                    AgentMessage.EventNote(
                        id = part?.str("id") ?: stableId(trimmed, "usage"),
                        label = "tokens · ${parts.joinToString(" · ")}",
                    ),
                )
            }
            "error" -> {
                val err = obj.obj("error")
                val name = err?.str("name")
                val msg = err?.obj("data")?.str("message") ?: err?.str("message")
                val ref = err?.obj("data")?.str("ref")
                // Deliberately opaque by design — say so, and keep the ref so
                // it can be matched against the log line that carries the
                // real cause (surfaced separately above).
                val text = listOfNotNull(name, msg).joinToString(": ").ifBlank { trimmed }
                listOf(
                    AgentMessage.Error(
                        id = stableId(trimmed, "err"),
                        text = if (ref != null) "$text (ref $ref)" else text,
                    ),
                )
            }
            else -> listOf(
                AgentMessage.EventNote(
                    id = stableId(type, "gen"),
                    label = type.replace('_', ' '),
                    detail = trimmed.take(2000),
                ),
            )
        }
    }

    /**
     * `opencode export <id>` → the turn's messages.
     *
     * Two shapes here that a flat mapping gets wrong, both verified against a
     * real export (2026-08-28):
     *  - a message is `{info:{role,…}, parts:[…]}` — a two-level split, not a
     *    flat object with a role on it;
     *  - **one model turn can be SEVERAL assistant messages** sharing a
     *    `parentID` (the tool-call step and the final-text step are separate
     *    entries), so nothing may assume one assistant message per turn;
     *  - a `tool` part is BOTH the call and its result: `state.input` is the
     *    arguments, `state.output` the result, `state.error` the failure.
     */
    private fun parseTranscript(doc: String): List<AgentMessage>? {
        val root = SilentlyTry.logged(TAG, "parse transcript") {
            json.parseToJsonElement(doc).jsonObject
        } ?: return null
        val messages = SilentlyTry.logged(TAG, "read messages") {
            root["messages"] as? JsonArray
        } ?: return null

        val out = ArrayList<AgentMessage>()
        for (m in messages) {
            val msg = SilentlyTry.logged(TAG, "cast message") { m.jsonObject } ?: continue
            val info = msg.obj("info")
            val role = info?.str("role").orEmpty()
            val parts = msg["parts"] as? JsonArray ?: continue
            for (p in parts) {
                val part = SilentlyTry.logged(TAG, "cast part") { p.jsonObject } ?: continue
                val partId = part.str("id") ?: stableId(part.toString(), "p")
                when (part.str("type")) {
                    "text" -> {
                        val text = part.str("text")?.takeIf { it.isNotBlank() } ?: continue
                        out += if (role == "user") AgentMessage.UserText(partId, text)
                        else AgentMessage.AssistantText(partId, text)
                    }
                    "reasoning" -> {
                        val text = part.str("text")?.takeIf { it.isNotBlank() } ?: continue
                        out += AgentMessage.EventNote(
                            id = partId,
                            label = "thinking · ${text.replace(Regex("\\s+"), " ").take(100)}",
                            detail = text,
                        )
                    }
                    "tool" -> {
                        val state = part.obj("state")
                        val callId = part.str("callID") ?: partId
                        out += AgentMessage.ToolUse(
                            id = callId,
                            toolName = part.str("tool") ?: "tool",
                            input = state?.get("input")?.toString().orEmpty(),
                        )
                        val status = state?.str("status").orEmpty()
                        val failed = status.equals("error", ignoreCase = true)
                        out += AgentMessage.ToolResult(
                            id = "$partId-r",
                            toolUseId = callId,
                            output = state?.str("output") ?: state?.str("error").orEmpty(),
                            isError = failed,
                        )
                    }
                    // step-start / step-finish / patch are bookkeeping in a
                    // replay: the token line belongs to the live stream, and
                    // repeating it per replayed step would double-count.
                    else -> Unit
                }
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

    private const val TAG = "Conch-OpencodeParse"
}
