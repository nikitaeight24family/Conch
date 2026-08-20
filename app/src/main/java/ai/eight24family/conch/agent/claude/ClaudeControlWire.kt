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

    /** Deny reason sent when the user taps "Deny" on a permission card. Shared so
     * [ClaudeMessageParser] recognises the CLI's echoed deny tool_result and
     * renders it as a CALM "declined" note instead of a scary red error. */
    const val DENY_PERMISSION_REASON = "User denied this action from the mobile client."

    /** Deny reason sent when the user types a new message instead of answering a
     *  live AskUserQuestion (cancelPendingQuestions). Same calm-render treatment. */
    const val DENY_KEPT_GOING_REASON = "User chose to keep going without answering."

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

    // ── Client → CLI control requests (verified against the 2.1.219 binary's
    //    stdin dispatcher in print.ts — see INVARIANTS 2026-08-02) ──────────

    private fun clientRequest(
        requestId: String,
        subtype: String,
        params: JsonObject? = null,
    ): String = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        putJsonObject("request") {
            put("subtype", subtype)
            params?.forEach { (k, v) -> put(k, v) }
        }
    }.toString()

    /** LIVE model switch — the CLI swaps `mainLoopModelForSession` in place,
     *  no process restart, no re-read of the session file. `model` null/blank
     *  → "default" (the CLI resolves it to its effective default). Errors come
     *  back as a control_response error with the CLI's own message (unknown
     *  model → suggestion, restricted model → policy text). */
    fun encodeSetModel(requestId: String, model: String?): String =
        clientRequest(requestId, "set_model", buildJsonObject {
            put("model", model?.takeIf { it.isNotBlank() } ?: "default")
        })

    /** LIVE permission-mode switch. CLI modes (2.1.219): "default",
     *  "acceptEdits", "plan", "bypassPermissions", "auto", "dontAsk"
     *  ("manual" aliases default). bypassPermissions can be REFUSED when the
     *  session wasn't launched with the bypass flag — callers must fall back
     *  to the restart path on an error verdict. */
    fun encodeSetPermissionMode(requestId: String, mode: String): String =
        clientRequest(requestId, "set_permission_mode", buildJsonObject {
            put("mode", mode)
        })

    /** LIVE thinking-budget switch — replaces the launch-scoped
     *  MAX_THINKING_TOKENS env for the rest of the session. null = back to the
     *  CLI's adaptive default. Schema (binary-verified): max_thinking_tokens
     *  must be an integer or null. */
    fun encodeSetMaxThinkingTokens(requestId: String, maxTokens: Int?): String =
        clientRequest(requestId, "set_max_thinking_tokens", buildJsonObject {
            put("max_thinking_tokens", maxTokens)
        })

    /** `/context` data over the wire: `{categories:[{name,tokens}], totalTokens,
     *  maxTokens, percentage, autoCompactThreshold, ...}` — the same numbers the
     *  CLI's own /context grid draws, for THIS live session, no session copy. */
    fun encodeGetContextUsage(requestId: String): String =
        clientRequest(requestId, "get_context_usage")

    /** Plan-limit windows from the CLI's own cache: `{rate_limits:{five_hour:
     *  {utilization,resets_at}, seven_day, …, model_scoped:[…]},
     *  subscription_type, session:{total_cost_usd,…}}`. */
    fun encodeGetUsage(requestId: String): String =
        clientRequest(requestId, "get_usage")

    /** Server-side file search for @-mentions: `{query}` →
     *  `{suggestions:[{path}]}` (the CLI's own fuzzy file index). */
    fun encodeFileSuggestions(requestId: String, query: String): String =
        clientRequest(requestId, "file_suggestions", buildJsonObject {
            put("query", query)
        })

    /** Rename the session's title (shows in `claude --resume`). Title must be
     *  non-empty; the CLI persists it to the transcript. */
    /**
     * Kill ONE running task by id. Schema: `{subtype:"stop_task",task_id}`,
     * described as "Stops a running task."
     *
     * This is the protocol answer to a runaway build: the app already knows every
     * task_id (they arrive on `task_started`), so a task can be stopped from the
     * phone instead of the user reaching for a laptop to Ctrl-C it.
     */
    fun encodeStopTask(requestId: String, taskId: String): String =
        clientRequest(requestId, "stop_task", buildJsonObject { put("task_id", taskId) })

    /**
     * Background in-flight FOREGROUND work — Ctrl+B semantics.
     * `{subtype:"background_tasks",tool_use_id?}`: "Backgrounds in-flight
     * foreground tasks (Bash commands and subagents). With tool_use_id, targets
     * only the task whose originating tool_use block has this id. When omitted,
     * backgrounds all foreground tasks."
     *
     * ⚠ Not the same thing as `/bg`, which spawns a NEW detached agent. This
     * detaches what is ALREADY running so the turn can end.
     */
    fun encodeBackgroundTasks(requestId: String, toolUseId: String? = null): String =
        clientRequest(
            requestId, "background_tasks",
            toolUseId?.let { id -> buildJsonObject { put("tool_use_id", id) } },
        )

    /**
     * The formatted session cost — "the same text /usage prints in
     * non-interactive mode … so the thin-client /usage dialog shows the remote
     * container cost instead of the local $0.00", which is exactly our situation:
     * the money is spent on the user's server, not on the phone.
     * Response: `{text}` (ANSI-stripped).
     */
    fun encodeGetSessionCost(requestId: String): String =
        clientRequest(requestId, "get_session_cost")

    /**
     * The workspace diff, resolved BY THE WORKER: "the worker resolves one base
     * ref for both stats and hunks (working tree vs HEAD, falling back to
     * branch-vs-default-merge-base when the tree is clean) and applies the
     * standard caps (5s git timeout, 50 files, 1MB/file)".
     *
     * Better than our own `git diff HEAD` in two ways that matter: it picks the
     * right base when the tree is clean (ours returned nothing and said "no
     * changes"), and the caps are the CLI's, so a huge repo cannot hang the turn.
     */
    fun encodeGetWorkspaceDiff(requestId: String): String =
        clientRequest(requestId, "get_workspace_diff")

    /** The plan-mode plan. `{exists, content?}` — and the caller does not need to
     *  know the plan file's path, the worker resolves its own slug. */
    fun encodeGetPlan(requestId: String): String = clientRequest(requestId, "get_plan")

    /** The CLI's own version, `{version, buildTime?}`. Ours is the phone's idea of
     *  the app; this is the thing actually running the turns. */
    fun encodeGetBinaryVersion(requestId: String): String =
        clientRequest(requestId, "get_binary_version")

    fun encodeRenameSession(requestId: String, title: String): String =
        clientRequest(requestId, "rename_session", buildJsonObject {
            put("title", title)
        })

    /**
     * Rewind the CONVERSATION to just before [targetMessageUuid] (a `user`
     * record's uuid from the session JSONL). Response:
     * `{rewound, targetMessageUuid, prefillText, precedingAssistantUuid}` —
     * or `{rewound:false, error:"turn running"|"target not found"|
     * "stale target"|…}`.
     *
     * ⚠ VERIFIED LIVE (2026-08-02): this does NOT truncate the session file.
     * The CLI appends a `last-prompt` record whose `leafUuid` points BACK at
     * `precedingAssistantUuid`, and the next turn's user record parents onto
     * that same uuid — the abandoned branch stays in the file forever. Any
     * consumer that reads the JSONL linearly (our mirror) MUST reconstruct
     * the active chain — see [ClaudeChainFilter].
     *
     * [interruptIfRunning] asks the CLI to abort an in-flight turn first;
     * without it a running turn is answered with `error:"turn running"`.
     */
    fun encodeRewindConversation(
        requestId: String,
        targetMessageUuid: String,
        interruptIfRunning: Boolean,
    ): String = clientRequest(requestId, "rewind_conversation", buildJsonObject {
        put("target_message_uuid", targetMessageUuid)
        put("interrupt_if_running", interruptIfRunning)
    })

    /**
     * Restore FILES to their state before [userMessageId]'s turn.
     * `dry_run` = report only: `{canRewind, filesChanged:[…], insertions,
     * deletions}`; apply returns `{canRewind:true, skippedLinks?}`.
     * `{canRewind:false, error}` when the CLI has no checkpoint (or file
     * checkpointing is off — headless requires
     * `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING`, see ClaudeSpec).
     */
    fun encodeRewindFiles(
        requestId: String,
        userMessageId: String,
        dryRun: Boolean,
    ): String = clientRequest(requestId, "rewind_files", buildJsonObject {
        put("user_message_id", userMessageId)
        put("dry_run", dryRun)
    })

    /** One `control_response` from the CLI to a request WE sent. */
    data class ControlResponse(
        val requestId: String,
        val ok: Boolean,
        /** CLI's error text on an error verdict (null on success). */
        val error: String?,
        /** `response.response` payload object on success (may be null — many
         *  acks carry no payload). */
        val payload: JsonObject?,
    )

    /** Parse a `control_response` line. Returns null when [line] isn't one.
     *  Shape (binary-verified): `{"type":"control_response","response":
     *  {"subtype":"success"|"error","request_id":…,"response":{…}|,"error":…}}`
     *  — request_id NESTED inside `response` (the documented asymmetry). */
    fun parseControlResponse(line: String): ControlResponse? {
        if (!line.startsWith("{") || !line.contains("\"control_response\"")) return null
        val obj = SilentlyTry.logged("SshAi-Control", "parse control response") {
            json.parseToJsonElement(line).jsonObject
        } ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "control_response") return null
        val resp = SilentlyTry.logged("SshAi-Control", "read response obj") {
            obj["response"]?.jsonObject
        } ?: return null
        val requestId = resp["request_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val ok = resp["subtype"]?.jsonPrimitive?.contentOrNull == "success"
        return ControlResponse(
            requestId = requestId,
            ok = ok,
            error = resp["error"]?.jsonPrimitive?.contentOrNull,
            payload = SilentlyTry.logged("SshAi-Control", "read response payload") {
                resp["response"]?.jsonObject
            },
        )
    }

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
