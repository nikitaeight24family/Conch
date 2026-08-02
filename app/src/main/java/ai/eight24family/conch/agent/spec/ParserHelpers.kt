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
     * Cheap **TOP-LEVEL** `"type":"…"` extractor — one pass, no JSON parsing.
     * Used to route lines to the right fast-path handler before paying for a
     * full parse. Returns null when the top-level field is absent or malformed.
     *
     * ⚠ "TOP-LEVEL" is the whole point, and it used to be a plain
     * `line.indexOf("\"type\":\"")` — the FIRST such substring ANYWHERE in the
     * line, nested objects included. Two ways that lies:
     *
     *  1. the top-level `type` is not the first key, and something nested
     *     carries a `type` ahead of it;
     *  2. the value returned belongs to an inner object entirely.
     *
     * That is what left a finished turn spinning. Claude's stream-json `result`
     * envelope arrived as `{"is_error":false,"duration_api_ms":…,"usage":{…},…}`
     * with its own `type` well down the object; the reader's turn-end gate reads
     * quickType, got something that was not "result", and never completed the
     * turn — while the file-mirror path, which uses the real parser
     * (`obj.string("type")`, a genuine key lookup), rendered that same envelope's
     * token/cost row on screen. Answer on screen, spinner still going
     * (2026-07-29). A weaker second extraction disagreeing with the parser is a
     * bug generator; keep them in agreement.
     *
     * Scans with brace/bracket depth, skipping string literals and escapes, so a
     * text delta containing `{` or `"type":"` cannot fool it. O(n), no
     * allocation until the value is found.
     */
    fun quickType(line: String): String? {
        var i = 0
        var depth = 0
        val n = line.length
        while (i < n) {
            when (line[i]) {
                '"' -> {
                    // Walk the string literal; note where it started and ended.
                    val keyStart = i + 1
                    var j = keyStart
                    while (j < n) {
                        val ch = line[j]
                        if (ch == '\\') { j += 2; continue }
                        if (ch == '"') break
                        j++
                    }
                    if (j >= n) return null
                    // A TOP-LEVEL key named "type" — depth 1 means "directly
                    // inside the outermost object".
                    if (depth == 1 && j - keyStart == 4 && line.startsWith("type", keyStart)) {
                        var k = j + 1
                        while (k < n && line[k].isWhitespace()) k++
                        if (k < n && line[k] == ':') {
                            k++
                            while (k < n && line[k].isWhitespace()) k++
                            if (k < n && line[k] == '"') {
                                val vs = k + 1
                                var ve = vs
                                while (ve < n) {
                                    val ch = line[ve]
                                    if (ch == '\\') { ve += 2; continue }
                                    if (ch == '"') break
                                    ve++
                                }
                                if (ve >= n) return null
                                return line.substring(vs, ve)
                            }
                        }
                    }
                    i = j + 1
                }
                '{', '[' -> { depth++; i++ }
                '}', ']' -> { depth--; i++ }
                else -> i++
            }
        }
        return null
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
