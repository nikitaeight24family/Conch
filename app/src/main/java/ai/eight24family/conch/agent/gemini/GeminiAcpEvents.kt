package ai.eight24family.conch.agent.gemini

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.gemini.GeminiAcpWire.str
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Gemini ACP `session/update` → [AgentMessage] mapping — the ACP twin of
 * [GeminiMessageParser] (which parses headless stream-json and saved
 * session files). Streamed message/thought CHUNKS are accumulated by the
 * driver (they need per-message state); everything else maps statelessly
 * here.
 *
 * Same contract as everywhere since 2026-06-12: NOTHING is silently
 * swallowed — unknown `sessionUpdate` kinds render as generic notes. The
 * only deliberate drops are user_message_chunk echoes (we render the
 * user's text on send) and available_commands_update (bookkeeping spam
 * at session start).
 */
internal object GeminiAcpEvents {

    /** Map one non-chunk update. [turnTag] namespaces stable ids per turn. */
    fun mapUpdate(update: JsonObject, turnTag: String): List<AgentMessage> {
        return when (val kind = update.str("sessionUpdate")) {
            // Chunks are handled by the driver's accumulator; echoes and
            // command lists are deliberate drops.
            "agent_message_chunk", "agent_thought_chunk",
            "user_message_chunk", "available_commands_update" -> emptyList()

            "tool_call" -> {
                val callId = update.str("toolCallId") ?: ParserHelpers.uuid()
                val title = update.str("title").orEmpty()
                listOf(
                    AgentMessage.ToolUse(
                        id = "gemacp_$callId",
                        toolName = title.ifBlank { update.str("kind") ?: "tool" },
                        input = update["rawInput"]?.toString().orEmpty(),
                    )
                )
            }

            "tool_call_update" -> {
                val callId = update.str("toolCallId") ?: return emptyList()
                when (update.str("status")) {
                    "completed", "failed" -> listOf(
                        AgentMessage.ToolResult(
                            id = ParserHelpers.uuid(),
                            toolUseId = "gemacp_$callId",
                            output = extractToolContent(update),
                            isError = update.str("status") == "failed",
                        )
                    )
                    // pending / in_progress / title-only updates — the
                    // ToolUse row is already on screen.
                    else -> emptyList()
                }
            }

            "plan" -> {
                val entries = SilentlyTry.logged("SshAi-GeminiAcp", "plan entries") {
                    update["entries"]?.jsonArray
                } ?: return emptyList()
                if (entries.isEmpty()) return emptyList()
                var done = 0
                val lines = entries.mapNotNull { e ->
                    SilentlyTry.logged("SshAi-GeminiAcp", "plan entry") {
                        val o = e.jsonObject
                        val status = o.str("status")
                        if (status == "completed") done++
                        val mark = when (status) {
                            "completed" -> "✓"
                            "in_progress" -> "▸"
                            else -> "·"
                        }
                        o.str("content")?.let { "$mark $it" }
                    }
                }
                listOf(GeminiMessageParser.note(
                    "plan · $done/${entries.size}",
                    detail = lines.joinToString("\n"),
                    id = "gemacp-plan-$turnTag",
                ))
            }

            "current_mode_update" -> listOf(GeminiMessageParser.note(
                "mode · ${update.str("currentModeId") ?: "?"}",
                id = "gemacp-mode",
            ))

            // UNKNOWN update kind — render generically, never swallow.
            else -> {
                if (kind.isNullOrBlank()) emptyList()
                else listOf(GeminiMessageParser.note(
                    GeminiMessageParser.genericLabel(kind, update),
                    detail = GeminiMessageParser.genericDetail(update),
                ))
            }
        }
    }

    /**
     * Tool output from a ToolCall/ToolCallUpdate `content` array:
     * `{type:"content", content:{type:"text",text}}` blocks become their
     * text; `{type:"diff", path, …}` becomes a one-line diff marker.
     */
    fun extractToolContent(update: JsonObject): String {
        val arr = SilentlyTry.logged("SshAi-GeminiAcp", "tool content array") {
            update["content"]?.jsonArray
        } ?: return ""
        return arr.mapNotNull { item ->
            SilentlyTry.logged("SshAi-GeminiAcp", "tool content item") {
                val o = item.jsonObject
                when (o.str("type")) {
                    "content" -> o["content"]?.jsonObject?.str("text")
                    "diff" -> "diff · ${o.str("path") ?: "file"}"
                    else -> null
                }
            }
        }.joinToString("\n")
    }
}
