package ai.eight24family.conch.agent.spec

import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Shared infrastructure for the per-CLI JSONL parsers (Durov critique #4).
 *
 * **The unified schema is [ai.eight24family.conch.agent.AgentMessage].**
 * Claude, Codex and Gemini each have wildly different stream-json shapes
 * — Claude has `assistant`/`user`/`stream_event`/`system`/`result`/…,
 * Codex has `item.created`/`item.updated`/`turn.started`/`thread.started`
 * with item-id namespacing, Gemini has `request`/`response` with a
 * `parts[]` array — but the UI doesn't care. Every parser's job is to
 * funnel those CLI dialects into the same `AgentMessage` sealed type:
 * `UserText`, `AssistantText`, `ToolUse`, `ToolResult`, `System`,
 * `Result`, `Error`, `PermissionRequest`, `Raw`.
 *
 * What used to be duplicated three times verbatim now lives here:
 *
 *  • [Json] config — `ignoreUnknownKeys = true; isLenient = true`.
 *    Without `ignoreUnknownKeys` every minor CLI release breaks the
 *    parser because they add new event fields. Without `isLenient`
 *    some Codex shapes (numbers-as-strings in older versions) crash.
 *  • [quickType] — `"\"type\":\"<value>\""` extractor using plain
 *    `String.indexOf` instead of a full parse. Used to early-route
 *    lines to the right fast-path handler before paying for JSON
 *    parsing. Hot — runs on every stream line.
 *  • [uuid] — the only non-deterministic id fallback that survives
 *    after the [stableId] migration. Used for genuinely
 *    non-content-addressable emissions (e.g. transient `Result`
 *    summary lines where no source id exists). Prefer [stableId] for
 *    anything reparseable.
 *
 * The remaining per-parser code is the CLI-specific event-to-message
 * mapping that genuinely differs — there's no useful abstraction over
 * Claude's nested `content[]` block array vs Codex's `payload.item.*`
 * vs Gemini's `parts[]`. A "unified `AgentEvent`" enum below
 * `AgentMessage` was sketched in critique notes; we held off because
 * the three CLIs' event grammars don't line up cleanly enough to be
 * worth the abstraction tax. If a fourth CLI ever ships in this
 * category we revisit — three is not a pattern.
 */
internal object ParserHelpers {

    /** Shared lenient JSON config. Reused across parsers — Json is
     *  thread-safe per kotlinx.serialization docs. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Cheap top-level `"type":"…"` extractor — String.indexOf, no JSON
     * parsing. Used to early-route lines to the right fast-path handler
     * before paying for a full parse. Returns null when the field is
     * absent or malformed (caller falls through to the tree path).
     */
    fun quickType(line: String): String? {
        val needle = "\"type\":\""
        val start = line.indexOf(needle).takeIf { it >= 0 } ?: return null
        val s = start + needle.length
        val e = line.indexOf('"', s)
        if (e < 0) return null
        return line.substring(s, e)
    }

    /**
     * Random UUID. Last-resort id for emissions that genuinely have no
     * content-addressable equivalent (e.g. transient summary lines).
     * Prefer [stableId] — re-parsing the same JSONL line yields the
     * same id from `stableId`, which keeps the FTS index and the
     * in-memory history in sync.
     */
    fun uuid(): String = UUID.randomUUID().toString()
}
