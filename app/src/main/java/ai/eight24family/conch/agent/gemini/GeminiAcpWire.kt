package ai.eight24family.conch.agent.gemini

import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Wire codec for **Gemini CLI's ACP mode** (`gemini --experimental-acp`) —
 * the Agent Client Protocol: JSON-RPC 2.0 over stdio (WITH the
 * `"jsonrpc":"2.0"` envelope, unlike codex app-server). This is the
 * interactive transport gemini's IDE integrations (Zed, JetBrains) ride;
 * headless stream-json can NOT prompt (CONFIRMATION_REQUIRED), ACP can.
 *
 * Shapes verified against the canonical ACP v1 JSON schema
 * (agentclientprotocol.com, schema/v1/schema.json, fetched 2026-06-13):
 *
 *  - `initialize` {protocolVersion: 1 (integer!), clientCapabilities:
 *    {fs:{readTextFile,writeTextFile}, terminal}, clientInfo} →
 *    {protocolVersion, agentCapabilities:{loadSession, …}, authMethods}.
 *    NO `initialized` follow-up notification in ACP.
 *  - `session/new` {cwd (absolute), mcpServers: []} → {sessionId};
 *    `session/load` {sessionId, cwd, mcpServers} — only when
 *    agentCapabilities.loadSession; the agent replays history via
 *    `session/update` notifications before responding.
 *  - `session/prompt` {sessionId, prompt:[{type:"text",text}]} →
 *    {stopReason: end_turn|max_tokens|max_turn_requests|refusal|cancelled}
 *    — the response IS the turn-done signal.
 *  - `session/update` notifications: params {sessionId, update
 *    {sessionUpdate: user_message_chunk|agent_message_chunk|
 *    agent_thought_chunk|tool_call|tool_call_update|plan|
 *    available_commands_update|current_mode_update, …}}. Chunks carry
 *    {content:{type:"text",text}, messageId?}; tool_call carries
 *    {toolCallId, title, kind, status pending|in_progress|completed|failed,
 *    content:[{type:"content"|"diff"|…}], rawInput}; plan carries
 *    {entries:[{content, priority, status}]}.
 *  - `session/request_permission` (server→client request) {sessionId,
 *    toolCall, options:[{optionId, name, kind: allow_once|allow_always|
 *    reject_once|reject_always}]} → result {outcome:{outcome:"selected",
 *    optionId}} or {outcome:{outcome:"cancelled"}} (NESTED outcome).
 *  - `session/cancel` {sessionId} — notification (no id); the in-flight
 *    session/prompt then resolves with stopReason "cancelled".
 *  - `fs/read_text_file` / `fs/write_text_file` server→client requests
 *    only flow if the client advertised the fs capability — we don't.
 */
internal object GeminiAcpWire {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ────────── Encoding: client → agent ──────────

    fun encodeInitialize(id: Long, appVersion: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", 1)
            putJsonObject("clientCapabilities") {
                putJsonObject("fs") {
                    put("readTextFile", false)
                    put("writeTextFile", false)
                }
                put("terminal", false)
            }
            putJsonObject("clientInfo") {
                put("name", "conch")
                put("title", "conch")
                put("version", appVersion)
            }
        }
    }.toString()

    fun encodeSessionNew(id: Long, cwd: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "session/new")
        putJsonObject("params") {
            put("cwd", cwd)
            putJsonArray("mcpServers") {}
        }
    }.toString()

    fun encodeSessionLoad(id: Long, sessionId: String, cwd: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "session/load")
        putJsonObject("params") {
            put("sessionId", sessionId)
            put("cwd", cwd)
            putJsonArray("mcpServers") {}
        }
    }.toString()

    fun encodePrompt(
        id: Long,
        sessionId: String,
        text: String,
        /** Absolute paths of images already uploaded to the server. Gemini reads
         *  them via its native `@<path>` file mention (the at-processor pulls the
         *  file into the turn as real image content). Before this, attachments
         *  reached the model only as a path string in the prose (audit
         *  2026-06-14). NOTE: confirm on-device that ACP runs @-expansion. */
        imagePaths: List<String> = emptyList(),
    ): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "session/prompt")
        putJsonObject("params") {
            put("sessionId", sessionId)
            putJsonArray("prompt") {
                val withImages = if (imagePaths.isEmpty()) text else buildString {
                    append(text)
                    imagePaths.forEach { p -> if (p.isNotBlank()) append("\n@").append(p) }
                }
                add(buildJsonObject {
                    put("type", "text")
                    put("text", withImages)
                })
            }
        }
    }.toString()

    /** Cancellation is a NOTIFICATION (no id) — the pending prompt
     *  request then resolves with stopReason "cancelled". */
    fun encodeCancel(sessionId: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "session/cancel")
        putJsonObject("params") { put("sessionId", sessionId) }
    }.toString()

    /** Answer a session/request_permission — NESTED outcome object. */
    fun encodePermissionSelected(requestId: JsonElement, optionId: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId)
        putJsonObject("result") {
            putJsonObject("outcome") {
                put("outcome", "selected")
                put("optionId", optionId)
            }
        }
    }.toString()

    fun encodePermissionCancelled(requestId: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId)
        putJsonObject("result") {
            putJsonObject("outcome") { put("outcome", "cancelled") }
        }
    }.toString()

    /** Refusal for server requests we don't implement (fs and terminal families). */
    fun encodeErrorResponse(requestId: JsonElement, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId)
        putJsonObject("error") {
            put("code", -32601)
            put("message", message)
        }
    }.toString()

    // ────────── Decoding: agent → client ──────────

    sealed interface Incoming {
        /** Agent-initiated request — MUST be answered (has an id). */
        data class ServerReq(val id: JsonElement, val method: String, val params: JsonObject) : Incoming
        data class Notification(val method: String, val params: JsonObject) : Incoming
        data class Response(val id: Long?, val result: JsonObject?, val error: JsonObject?) : Incoming
    }

    fun parseLine(line: String): Incoming? {
        val t = line.trim()
        if (!t.startsWith("{")) return null
        val obj = SilentlyTry.logged("SshAi-GeminiAcp", "parse rpc line") {
            json.parseToJsonElement(t).jsonObject
        } ?: return null
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
        val id = obj["id"]
        val params = SilentlyTry.logged("SshAi-GeminiAcp", "rpc params obj") { obj["params"]?.jsonObject }
            ?: JsonObject(emptyMap())
        return when {
            method != null && id != null -> Incoming.ServerReq(id, method, params)
            method != null -> Incoming.Notification(method, params)
            id != null -> Incoming.Response(
                id = (id as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                result = SilentlyTry.logged("SshAi-GeminiAcp", "rpc result obj") { obj["result"]?.jsonObject },
                error = SilentlyTry.logged("SshAi-GeminiAcp", "rpc error obj") { obj["error"]?.jsonObject },
            )
            else -> null
        }
    }

    /** Permission option as offered by the agent. The client just echoes
     *  the chosen [optionId] — ids are agent-defined, NEVER hardcoded. */
    data class PermissionOption(val optionId: String, val name: String, val kind: String)

    fun parsePermissionOptions(params: JsonObject): List<PermissionOption> {
        val arr = SilentlyTry.logged("SshAi-GeminiAcp", "options array") { params["options"]?.jsonArray }
            ?: return emptyList()
        return arr.mapNotNull { o ->
            SilentlyTry.logged("SshAi-GeminiAcp", "option obj") {
                val obj = o.jsonObject
                PermissionOption(
                    optionId = obj.str("optionId") ?: return@logged null,
                    name = obj.str("name").orEmpty(),
                    kind = obj.str("kind").orEmpty(),
                )
            }
        }
    }

    /**
     * Pick the option that matches an allow/deny tap. Kind-driven (the
     * schema's allow_once / allow_always / reject_once / reject_always),
     * with positional fallback for agents that omit kinds.
     */
    fun pickOption(options: List<PermissionOption>, allow: Boolean, preferAlways: Boolean = false): String? {
        val want = when {
            !allow -> listOf("reject_once", "reject_always")
            // "Always allow this session" — prefer the persistent option, fall
            // back to once if the agent only offered that (audit 2026-06-14).
            preferAlways -> listOf("allow_always", "allow_once")
            else -> listOf("allow_once", "allow_always")
        }
        for (kind in want) options.firstOrNull { it.kind == kind }?.let { return it.optionId }
        // Fallback: first option for allow, last for deny.
        return if (allow) options.firstOrNull()?.optionId else options.lastOrNull()?.optionId
    }

    internal fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
