package ai.eight24family.conch.agent.copilot

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.agent.spec.stableId
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for **GitHub Copilot CLI**'s `--output-format json` JSONL — the same
 * vocabulary the CLI persists to `session-state/<id>/events.jsonl` (minus
 * `ephemeral:true` events), so ONE parser serves the live stream AND history
 * replay.
 *
 * Authority: the schema the emitter itself ships —
 * `schemas/session-events.schema.json` + `copilot-sdk/generated/
 * session-events.d.ts` inside `@github/copilot@1.0.80` — plus live captures.
 *
 * Envelope of every line:
 * ```json
 * {"type":"<ns.event>","data":{…},"id":"<uuid4>","timestamp":"ISO8601",
 *  "parentId":"<uuid|null>","ephemeral":true?,"agentId":"<uuid>"?}
 * ```
 *
 * Mapping decisions: - `assistant.message` (the COMPLETE message, id-stable
 * via `data.messageId`) is the render source; `assistant.message_delta` is
 * ephemeral streaming that a stateless parser can't accumulate — the delta
 * events are dropped and the full message upserts once per messageId. Tool
 * events still stream live, so the chat visibly works. - `assistant.usage`
 * renders the cross-agent «tokens · in X · out Y» line AND carries Copilot's
 * OWN billing unit (`copilotUsage.totalNanoAiu`, nano = 1e-9 AI credit) —
 * surfaced as «AI credits» because that is the unit Copilot's own footer
 * shows. Uniquely Copilot's gauge, per the per-agent-identity rule. -
 * `session.error` maps quota/rate-limit codes to a prominent Error. -
 * `session.idle` is the CLI's terminal done signal → TurnEnd. - Unknown types
 * render as a generic `type · summary` note — — except `ephemeral:true`
 * bookkeeping churn (mcp status flaps and deltas), which updates IN PLACE via
 * stable ids.
 */
object CopilotMessageParser {

    private val json get() = ParserHelpers.json

    /** ESC-anchored CSI stripper. ⚠ MUST be anchored: an unanchored
     *  `\[[0-?]*[ -/]*[@-~]` also matches the plain `[]` of an empty JSON
     *  array (`]` sits in the final char class), mangling
     *  `"attachments":[]` into invalid JSON — every such event then parsed
     *  to NOTHING (caught by CopilotMessageParserTest, 2026-08-28). */
    private val ANSI = Regex(Char(27) + Regex.escape("[") + "[0-?]*[ -/]*[@-~]")

    fun parse(line: String): List<AgentMessage> {
        val trimmed = ANSI.replace(line, "").trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("{")) {
            // stderr is merged at launch: auth failures and quota walls arrive
            // as plain prose. Keep the actionable lines, drop pure decoration.
            if (trimmed.length <= 2) return emptyList()
            return listOf(AgentMessage.Raw(ParserHelpers.uuid(), trimmed))
        }
        val type = ParserHelpers.quickType(trimmed) ?: return rawLine(trimmed)
        val obj by lazy {
            SilentlyTry.logged(TAG, "parse event") { json.parseToJsonElement(trimmed).jsonObject }
        }
        fun data(): JsonObject? = obj?.obj("data")
        fun envelopeId(salt: String): String = obj?.str("id") ?: stableId(trimmed, salt)

        return when (type) {
            "session.start" -> {
                val d = data()
                listOf(
                    AgentMessage.System(
                        id = envelopeId("sys"),
                        subtype = "init",
                        sessionId = d?.str("sessionId"),
                        model = d?.str("selectedModel"),
                        reasoning = d?.str("reasoningEffort"),
                        cwd = d?.obj("context")?.str("cwd"),
                        version = d?.str("copilotVersion"),
                        raw = trimmed,
                    ),
                )
            }
            "session.resume" -> {
                val d = data()
                listOf(
                    AgentMessage.System(
                        id = envelopeId("sys"),
                        subtype = "resume",
                        sessionId = d?.str("sessionId"),
                        model = d?.str("selectedModel"),
                        raw = trimmed,
                    ),
                )
            }
            "session.model_change" -> {
                val model = data()?.str("model") ?: data()?.str("selectedModel")
                listOf(
                    AgentMessage.System(
                        id = envelopeId("sys"),
                        subtype = "model_changed",
                        model = model,
                        raw = trimmed,
                    ),
                )
            }
            "session.title_changed" -> {
                val title = data()?.str("title")?.takeIf { it.isNotBlank() }
                    ?: return emptyList()
                listOf(
                    AgentMessage.System(
                        id = envelopeId("sys"),
                        subtype = "title",
                        title = title,
                        raw = trimmed,
                    ),
                )
            }
            "user.message" -> {
                val text = data()?.str("content")?.takeIf { it.isNotBlank() }
                    ?: return emptyList()
                listOf(AgentMessage.UserText(envelopeId("u"), text))
            }
            "assistant.message" -> {
                val d = data() ?: return emptyList()
                val msgId = d.str("messageId") ?: envelopeId("a")
                buildList {
                    d.str("reasoningText")?.takeIf { it.isNotBlank() }?.let { rt ->
                        add(
                            AgentMessage.EventNote(
                                id = "$msgId#thinking",
                                label = "thinking · ${rt.replace(Regex("\\s+"), " ").take(100)}",
                                detail = rt,
                            ),
                        )
                    }
                    d.str("content")?.takeIf { it.isNotBlank() }?.let { text ->
                        add(AgentMessage.AssistantText(msgId, text))
                    }
                }
            }
            // Streaming deltas + partial tool output: ephemeral by schema,
            // unrenderable statelessly (the full message/result upserts).
            "assistant.message_delta", "assistant.message_start",
            "assistant.reasoning_delta", "assistant.tool_call_delta",
            "assistant.streaming_delta", "tool.execution_partial_result",
            "tool.execution_progress" -> emptyList()
            "assistant.reasoning" -> {
                val rt = data()?.str("content") ?: data()?.str("reasoningText")
                if (rt.isNullOrBlank()) return emptyList()
                listOf(
                    AgentMessage.EventNote(
                        id = envelopeId("th"),
                        label = "thinking · ${rt.replace(Regex("\\s+"), " ").take(100)}",
                        detail = rt,
                    ),
                )
            }
            "tool.execution_start" -> {
                val d = data() ?: return emptyList()
                val callId = d.str("toolCallId") ?: envelopeId("tc")
                listOf(
                    AgentMessage.ToolUse(
                        id = callId,
                        toolName = d.str("mcpToolName") ?: d.str("toolName") ?: "tool",
                        input = d["arguments"]?.toString().orEmpty(),
                    ),
                )
            }
            "tool.execution_complete" -> {
                val d = data() ?: return emptyList()
                val callId = d.str("toolCallId") ?: return emptyList()
                val success = d.str("success") != "false"
                val result = d.obj("result")
                val output = result?.str("content")
                    ?: result?.str("detailedContent")
                    ?: d.obj("error")?.str("message")
                    ?: ""
                listOf(
                    AgentMessage.ToolResult(
                        id = envelopeId("tr"),
                        toolUseId = callId,
                        output = output,
                        isError = !success,
                    ),
                )
            }
            "assistant.usage" -> {
                val d = data() ?: return emptyList()
                val inTok = d.num("inputTokens") ?: 0L
                val outTok = d.num("outputTokens") ?: 0L
                if (inTok == 0L && outTok == 0L) return emptyList()
                val cached = d.num("cacheReadTokens") ?: 0L
                val reasoning = d.num("reasoningTokens") ?: 0L
                // Copilot's billing unit: nano-AIU → AI credits (1e-9). The
                // footer of the real CLI shows credits, so ours does too.
                val nano = d.obj("copilotUsage")?.num("totalNanoAiu")
                val credits = nano?.takeIf { it > 0 }
                    ?.let { String.format(java.util.Locale.US, "%.4f", it / 1e9) }
                val parts = listOfNotNull(
                    "in ${k(inTok)}",
                    "out ${k(outTok)}",
                    reasoning.takeIf { it > 0 }?.let { "thinking ${k(it)}" },
                    cached.takeIf { it > 0 }?.let { "cached ${k(it)}" },
                    credits?.let { "$it AI credits" },
                )
                listOf(
                    AgentMessage.EventNote(
                        id = envelopeId("usage"),
                        label = "tokens · ${parts.joinToString(" · ")}",
                    ),
                )
            }
            "session.error" -> {
                val d = data()
                val code = d?.str("errorCode").orEmpty()
                val msg = d?.str("message") ?: trimmed
                // Quota / rate-limit walls get the prominent card treatment
                // (kind drives the styled render, mirroring the CLI's own
                // loud quota banners).
                val kind = when {
                    code.contains("rate_limited") || d?.str("errorType") == "rate_limit" -> "overloaded"
                    else -> null
                }
                listOf(AgentMessage.Error(envelopeId("err"), msg, kind = kind))
            }
            "session.warning" -> {
                val msg = data()?.str("message") ?: return emptyList()
                listOf(
                    AgentMessage.EventNote(
                        id = envelopeId("warn"),
                        label = msg.replace(Regex("\\s+"), " ").take(160),
                        detail = msg,
                        tone = AgentMessage.EventNote.Tone.WARN,
                    ),
                )
            }
            "session.info" -> {
                val msg = data()?.str("message") ?: return emptyList()
                listOf(
                    AgentMessage.EventNote(
                        id = envelopeId("info"),
                        label = msg.replace(Regex("\\s+"), " ").take(160),
                        detail = msg,
                        tone = AgentMessage.EventNote.Tone.INFO,
                    ),
                )
            }
            "session.task_complete" -> {
                val d = data()
                listOf(AgentMessage.Result(envelopeId("res"), "task_complete", d?.str("summary")))
            }
            // The CLI's terminal "turn over" signal — the single authority,
            // consumed by the stream reader and dropped (never history).
            "session.idle" -> listOf(AgentMessage.TurnEnd(envelopeId("end"), "session.idle"))
            "session.shutdown" -> listOf(AgentMessage.TurnEnd(envelopeId("end"), "session.shutdown"))
            // Frequent bookkeeping churn — stable per-KIND ids so each kind
            // updates ONE dim row in place instead of spamming the chat.
            "session.mcp_servers_loaded", "session.mcp_server_status_changed",
            "session.skills_loaded", "session.extensions_loaded",
            "session.custom_agents_updated", "session.context_changed",
            "session.usage_checkpoint", "session.managed_settings_resolved",
            "session.managed_settings_enforced", "assistant.turn_start",
            "assistant.turn_end", "assistant.intent", "assistant.idle",
            "session.permissions_changed", "session.mode_changed",
            "session.todos_changed", "session.plan_changed" -> emptyList()
            "session.compaction_start" -> listOf(
                AgentMessage.EventNote(id = "copilot-compaction", label = "compacting context…"),
            )
            "session.compaction_complete" -> listOf(
                AgentMessage.EventNote(id = "copilot-compaction", label = "context compacted"),
            )
            "session.usage_info" -> {
                // The CLI's own session-limits ping («Session limits: 0.5/1 AI
                // credits used.») — Copilot's native quota surface, kept verbatim.
                val msg = data()?.str("message")
                    ?: data()?.toString()?.take(200)
                    ?: return emptyList()
                listOf(
                    AgentMessage.EventNote(
                        id = "copilot-usage-info",
                        label = msg.replace(Regex("\\s+"), " ").take(160),
                        tone = AgentMessage.EventNote.Tone.INFO,
                    ),
                )
            }
            else -> {
                // Unknown event — surfaced generically, never swallowed.
                val label = type.replace('.', ' ').replace('_', ' ')
                val summary = data()?.let { d ->
                    listOf("message", "title", "summary", "name", "status")
                        .firstNotNullOfOrNull { k -> d.str(k)?.takeIf { it.isNotBlank() } }
                }
                listOf(
                    AgentMessage.EventNote(
                        id = stableId(type, "gen"),
                        label = label + (summary?.let { " · ${it.take(100)}" } ?: ""),
                        detail = data()?.toString()?.take(2000),
                    ),
                )
            }
        }
    }

    private fun rawLine(line: String): List<AgentMessage> =
        listOf(AgentMessage.Raw(ParserHelpers.uuid(), line))

    private fun JsonObject.obj(key: String): JsonObject? =
        SilentlyTry.logged(TAG, "read obj '$key'") { this[key]?.jsonObject }

    private fun JsonObject.str(key: String): String? =
        SilentlyTry.logged(TAG, "read str '$key'") { this[key]?.jsonPrimitive?.contentOrNull }

    private fun JsonObject.num(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

    private fun k(n: Long): String =
        if (n >= 1000) "${"%.1f".format(java.util.Locale.US, n / 1000.0)}k" else n.toString()

    private const val TAG = "SshAi-CopilotParse"
}
