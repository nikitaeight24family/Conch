package ai.eight24family.conch.agent.claude

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Wire codec for Claude Code's bidirectional stream-json CONTROL
 * PROTOCOL — the same protocol the official Agent SDK speaks to the CLI
 * (`--input-format stream-json --output-format stream-json`):
 *
 *  - CLIENT → CLI stdin lines: user turns
 *    (`{"type":"user","message":{...}}`), control responses
 *    (`{"type":"control_response","response":{...}}`), and
 *    client-initiated control requests (interrupt).
 *  - CLI → CLIENT stdout lines: the usual stream-json events PLUS
 *    `{"type":"control_request","request_id":"...","request":{...}}` —
 *    `can_use_tool` being the one that carries live permission prompts
 *    and AskUserQuestion.
 *
 * Shapes verified against the embedded JS of CLI 2.1.170 and the Agent
 * SDK sources — adjust HERE (single choke point) if the CLI evolves.
 */
internal object ClaudeControlWire {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One `control_request` from the CLI. [inputJson] is the tool input
     *  for `can_use_tool`; null for other subtypes. */
    data class ControlRequest(
        val requestId: String,
        val subtype: String,
        val toolName: String?,
        val inputJson: JsonObject?,
        val raw: String,
    )

    /** Cheap pre-filter so the hot read loop doesn't JSON-parse every
     *  line twice. */
    fun isControlLine(line: String): Boolean =
        line.startsWith("{") && line.contains("\"control_request\"")

    fun parseControlRequest(line: String): ControlRequest? {
        val obj = SilentlyTry.logged("SshAi-Control", "parse control line") {
            json.parseToJsonElement(line).jsonObject
        } ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "control_request") return null
        val requestId = obj["request_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val req = SilentlyTry.logged("SshAi-Control", "read request obj") {
            obj["request"]?.jsonObject
        } ?: return null
        val subtype = req["subtype"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return ControlRequest(
            requestId = requestId,
            subtype = subtype,
            toolName = req["tool_name"]?.jsonPrimitive?.contentOrNull,
            inputJson = SilentlyTry.logged("SshAi-Control", "read input obj") {
                req["input"]?.jsonObject
            },
            raw = line,
        )
    }

    /** Parse AskUserQuestion's tool input into typed questions.
     *  Schema (CLI 2.1.170): questions[1-4]{question,header,
     *  options[2-4]{label,description},multiSelect}. */
    fun parseAskQuestions(input: JsonObject): List<AgentMessage.AskUserQuestion.Question> {
        val arr = SilentlyTry.logged("SshAi-Control", "read questions array") {
            input["questions"]?.jsonArray
        } ?: return emptyList()
        return arr.mapNotNull { q ->
            val qo = SilentlyTry.logged("SshAi-Control", "question obj") { q.jsonObject }
                ?: return@mapNotNull null
            val options = SilentlyTry.logged("SshAi-Control", "options array") {
                qo["options"]?.jsonArray
            }?.mapNotNull { o ->
                val oo = SilentlyTry.logged("SshAi-Control", "option obj") { o.jsonObject }
                    ?: return@mapNotNull null
                AgentMessage.AskUserQuestion.Option(
                    label = oo["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    description = oo["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.orEmpty()
            if (options.isEmpty()) return@mapNotNull null
            AgentMessage.AskUserQuestion.Question(
                question = qo["question"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                header = qo["header"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                options = options,
                multiSelect = qo["multiSelect"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }
    }

    /** One user turn for the persistent stdin — canonical SDK shape:
     *  `{"type":"user","session_id":"","parent_tool_use_id":null,
     *    "message":{"role":"user","content":"…"}}`. */
    fun encodeUserTurn(text: String): String = buildJsonObject {
        put("type", "user")
        put("session_id", "")
        put("parent_tool_use_id", null as String?)
        putJsonObject("message") {
            put("role", "user")
            put("content", text)
        }
    }.toString()

    /** control_response envelope shared by every reply we send. NOTE the
     *  asymmetry verified against the SDKs: `request_id` sits at the TOP
     *  level of a control_REQUEST but NESTED inside `response` of a
     *  control_RESPONSE. */
    private fun responseEnvelope(requestId: String, payload: JsonObject): String =
        buildJsonObject {
            put("type", "control_response")
            putJsonObject("response") {
                put("subtype", "success")
                put("request_id", requestId)
                put("response", payload)
            }
        }.toString()

    /**
     * Allow a `can_use_tool`. `updatedInput` is REQUIRED by the CLI's
     * schema (zod `nk9`) — pass the ORIGINAL tool input unchanged for a
     * plain allow; a non-empty different object REPLACES the executed
     * input (that's the AskUserQuestion answer vehicle).
     */
    fun encodeAllow(requestId: String, updatedInput: JsonObject): String =
        responseEnvelope(requestId, buildJsonObject {
            put("behavior", "allow")
            put("updatedInput", updatedInput)
        })

    /** Deny a `can_use_tool` with a human-readable reason the model sees. */
    fun encodeDeny(requestId: String, message: String): String =
        responseEnvelope(requestId, buildJsonObject {
            put("behavior", "deny")
            put("message", message)
        })

    /**
     * Answer AskUserQuestion — verbatim contract from the Agent SDK docs:
     * allow with `updatedInput = {questions: <original array, required
     * pass-through>, answers: {"<question text>": "<label>" }}`,
     * multiSelect answers comma-joined. The CLI executes the tool against
     * the answered input and surfaces the picks to the model as
     * `[User answered AskUserQuestion]: …`.
     */
    fun encodeAskAnswers(
        requestId: String,
        originalInput: JsonObject,
        answers: Map<Int, List<String>>,
    ): String {
        val questions = originalInput["questions"]?.jsonArray
        val answersByText = buildJsonObject {
            questions?.forEachIndexed { qi, q ->
                val qo = SilentlyTry.logged("SshAi-Control", "answer question obj") { q.jsonObject }
                    ?: return@forEachIndexed
                val text = qo["question"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val chosen = answers[qi].orEmpty()
                if (chosen.isNotEmpty()) put(text, chosen.joinToString(", "))
            }
        }
        val updatedInput = buildJsonObject {
            put("questions", questions ?: buildJsonArray { })
            put("answers", answersByText)
        }
        return encodeAllow(requestId, updatedInput)
    }

    /**
     * Cancel-style answer for CLI→client dialog requests we can't render
     * (`request_user_dialog` / `elicitation`): per the binary's protocol
     * notes, hosts must answer unrecognized kinds with
     * `{behavior:"cancelled"}` — leaving them unanswered hangs the turn.
     */
    fun encodeDialogCancelled(requestId: String): String =
        responseEnvelope(requestId, buildJsonObject {
            put("behavior", "cancelled")
        })

    /** Client-initiated interrupt of the in-flight turn. */
    fun encodeInterrupt(requestId: String): String = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        putJsonObject("request") { put("subtype", "interrupt") }
    }.toString()

    /**
     * The `initialize` handshake the Agent SDK sends FIRST on every
     * session. We declare the dialog kinds we can actually render so the
     * CLI doesn't fail closed on permission/question flows. All config
     * fields are optional; we keep it minimal + honest about our
     * capabilities.
     */
    fun encodeInitialize(requestId: String): String = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        putJsonObject("request") {
            put("subtype", "initialize")
            put("supportedDialogKinds", buildJsonArray {
                add("can_use_tool")
                add("ask_user_question")
            })
        }
    }.toString()

    /** `{"type":"control_cancel_request","request_id":...}` — the CLI
     *  retires an outstanding can_use_tool/dialog when a turn aborts.
     *  Returns the cancelled request_id, or null if [line] isn't one. */
    fun parseCancelRequest(line: String): String? {
        if (!line.startsWith("{") || !line.contains("\"control_cancel_request\"")) return null
        val obj = SilentlyTry.logged("SshAi-Control", "parse cancel line") {
            json.parseToJsonElement(line).jsonObject
        } ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "control_cancel_request") return null
        return obj["request_id"]?.jsonPrimitive?.contentOrNull
    }
}
