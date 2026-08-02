package ai.eight24family.conch.agent.codex

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.codex.CodexAppServerWire.str
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * `codex app-server` v2 notification → [AgentMessage] mapping — the
 * app-server twin of [CodexMessageParser] (which parses the `codex exec`
 * / rollout-file schema). Field names here are camelCase ThreadItem
 * shapes verified against the installed binary's TS bindings (0.139.0).
 *
 * Same contract as everywhere since 2026-06-12: NOTHING is silently
 * swallowed — item types and notification methods without a tailored
 * branch render as generic notes. The only deliberate drops are
 * per-chunk delta spam (the completed item carries the full payload)
 * and userMessage echoes (we render the user's text on send()).
 */
internal object CodexAppServerEvents {

    /**
     * Map one `item/started` / `item/completed` notification.
     * Message ids embed the server-side [turnId] — item ids restart
     * per turn, the turn id keeps them globally unique (same trick as
     * the exec path's turnTag).
     */
    fun mapItem(item: JsonObject, started: Boolean, turnId: String): List<AgentMessage> {
        val itemId = item.str("id") ?: ParserHelpers.uuid()
        val baseId = "codexapp_${turnId}_$itemId"
        return when (val type = item.str("type")) {
            // Echo of our own send (or resume hydration handled by the
            // rollout-file path) — never re-render.
            "userMessage", "hookPrompt" -> emptyList()

            "agentMessage" -> {
                if (started) return emptyList()
                val text = item.str("text").orEmpty()
                if (text.isBlank()) emptyList()
                // Same id the delta accumulator streams under — the final
                // authoritative text replaces the streamed bubble in place.
                else listOf(AgentMessage.AssistantText(baseId, text))
            }

            "reasoning" -> {
                if (started) return emptyList()
                val text = SilentlyTry.logged("SshAi-CodexApp", "reasoning summary") {
                    item["summary"]?.jsonArray
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.joinToString(" ")
                }.orEmpty()
                if (text.isBlank()) emptyList()
                else listOf(CodexMessageParser.note(
                    "thinking · ${text.take(120)}",
                    detail = text.takeIf { it.length > 120 },
                    id = "$baseId-think",
                ))
            }

            "commandExecution" -> {
                val command = item.str("command").orEmpty()
                if (started) {
                    listOf(CodexMessageParser.note("exec · ${command.take(120)}", id = "$baseId-exec"))
                } else {
                    val exit = item.str("exitCode")?.toLongOrNull()
                    val status = item.str("status").orEmpty()
                    val out = item.str("aggregatedOutput").orEmpty()
                    listOf(
                        AgentMessage.ToolResult(
                            id = ParserHelpers.uuid(),
                            toolUseId = baseId,
                            output = buildString {
                                if (command.isNotBlank()) appendLine("$ $command")
                                if (out.isNotBlank()) append(out)
                            },
                            isError = (exit != null && exit != 0L) || status == "failed",
                        )
                    )
                }
            }

            "fileChange" -> {
                val changes = SilentlyTry.logged("SshAi-CodexApp", "fileChange changes") {
                    item["changes"]?.jsonArray
                }
                val paths = changes?.mapNotNull { c ->
                    SilentlyTry.logged("SshAi-CodexApp", "change row") {
                        c.jsonObject.str("path")
                    }
                }.orEmpty()
                val name = paths.firstOrNull()?.substringAfterLast('/') ?: "file"
                val tail = if (paths.size > 1) " (+${paths.size - 1} more)" else ""
                val status = item.str("status").orEmpty()
                val label = when {
                    started || status == "inProgress" -> "patching · $name$tail"
                    status == "completed" -> "patched · $name$tail"
                    else -> "patch $status · $name$tail"
                }
                // Stable id: started → completed morphs the row in place.
                listOf(CodexMessageParser.note(
                    label,
                    detail = paths.joinToString("\n").takeIf { paths.size > 1 },
                    tone = if (status == "failed") AgentMessage.EventNote.Tone.WARN
                    else AgentMessage.EventNote.Tone.DIM,
                    id = "$baseId-files",
                ))
            }

            "mcpToolCall" -> {
                val server = item.str("server").orEmpty()
                val tool = item.str("tool").orEmpty()
                if (started) {
                    listOf(AgentMessage.ToolUse(baseId, "$server/$tool", item["arguments"]?.toString().orEmpty()))
                } else {
                    val status = item.str("status").orEmpty()
                    val result = SilentlyTry.logged("SshAi-CodexApp", "mcp result") {
                        item["result"]?.jsonObject?.get("content")?.toString()
                    } ?: item.str("error") ?: ""
                    listOf(
                        AgentMessage.ToolResult(
                            id = ParserHelpers.uuid(),
                            toolUseId = baseId,
                            output = result,
                            isError = status == "failed",
                        )
                    )
                }
            }

            "webSearch" -> {
                val query = item.str("query").orEmpty().take(80)
                if (query.isBlank()) emptyList()
                else listOf(CodexMessageParser.note("web search · $query", id = "$baseId-web"))
            }

            "plan" -> {
                if (started) return emptyList()
                val text = item.str("text").orEmpty()
                if (text.isBlank()) emptyList()
                else listOf(CodexMessageParser.note(
                    "plan · ${text.take(120)}",
                    detail = text.takeIf { it.length > 120 },
                    id = "$baseId-plan",
                ))
            }

            // Live compaction — SAME message kinds the Claude path uses, so
            // the animated CompactingRow + dim summary divider render for
            // free (ChatMessageLines switches on System.subtype).
            "contextCompaction" -> listOf(
                AgentMessage.System(
                    id = "$baseId-compact",
                    subtype = if (started) "compacting" else "compact_done",
                    raw = if (started) "" else "✻ Context compacted",
                )
            )

            "enteredReviewMode" -> listOf(CodexMessageParser.note(
                "review mode · ${item.str("review").orEmpty().take(80)}",
                tone = AgentMessage.EventNote.Tone.INFO, id = "$baseId-review",
            ))
            "exitedReviewMode" -> {
                // v2 carries the review RESULT as `review: string` (markdown).
                // Render it as a real assistant block (markdown/tables) instead
                // of dropping it — otherwise the user only saw "review finished"
                // and never the findings (audit follow-up 2026-06-14).
                val review = item.str("review").orEmpty()
                buildList {
                    add(CodexMessageParser.note(
                        "review finished", tone = AgentMessage.EventNote.Tone.INFO,
                        id = "$baseId-reviewend",
                    ))
                    if (review.isNotBlank()) add(AgentMessage.AssistantText("$baseId-reviewout", review))
                }
            }

            // UNKNOWN item type — render generically, never swallow. One
            // note per item (stable id collapses started+completed).
            else -> {
                if (type == null) emptyList()
                else listOf(CodexMessageParser.note(
                    CodexMessageParser.genericLabel(type, item),
                    detail = CodexMessageParser.genericDetail(item),
                    id = "$baseId-gen",
                ))
            }
        }
    }

    /**
     * `turn/plan/updated` → live progress note (upserts in place per turn).
     */
    fun mapPlanUpdate(params: JsonObject, turnId: String): List<AgentMessage> {
        val steps = SilentlyTry.logged("SshAi-CodexApp", "plan steps") { params["plan"]?.jsonArray }
            ?: return emptyList()
        if (steps.isEmpty()) return emptyList()
        var done = 0
        val lines = steps.mapNotNull { s ->
            SilentlyTry.logged("SshAi-CodexApp", "plan step") {
                val o = s.jsonObject
                val status = o.str("status")
                if (status == "completed") done++
                val mark = when (status) {
                    "completed" -> "✓"
                    "inProgress" -> "▸"
                    else -> "·"
                }
                o.str("step")?.let { "$mark $it" }
            }
        }
        return listOf(CodexMessageParser.note(
            "plan · $done/${steps.size}",
            detail = lines.joinToString("\n"),
            id = "codexapp-plan-$turnId",
        ))
    }

    /**
     * `error` notification. Retriable errors are a WARN note (codex is
     * already retrying — no scary red banner per the auto-fix invariant);
     * terminal ones render as a real Error.
     */
    fun mapError(params: JsonObject): List<AgentMessage> {
        val msg = SilentlyTry.logged("SshAi-CodexApp", "error message") {
            params["error"]?.jsonObject?.str("message")
        } ?: "error"
        val willRetry = params.str("willRetry") == "true"
        return if (willRetry) listOf(CodexMessageParser.note(
            "retrying · ${msg.take(100)}", tone = AgentMessage.EventNote.Tone.WARN,
        )) else listOf(AgentMessage.Error(ParserHelpers.uuid(), msg))
    }

    /** `model/rerouted` — the model changed under the user; they must see it. */
    fun mapModelRerouted(params: JsonObject): List<AgentMessage> = listOf(
        CodexMessageParser.note(
            "model rerouted · ${params.str("fromModel") ?: "?"} → ${params.str("toModel") ?: "?"}" +
                (params.str("reason")?.let { " · $it" } ?: ""),
            tone = AgentMessage.EventNote.Tone.WARN,
        )
    )
}
