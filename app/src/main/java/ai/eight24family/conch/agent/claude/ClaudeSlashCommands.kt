package ai.eight24family.conch.agent.claude

import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.SlashCommandKind

/**
 * Parser for Claude Code's user-defined slash commands.
 *
 * The server-side discovery script (see [ClaudeSpec.customCommandsScript])
 * walks `~/.claude/commands/` and `$(pwd)/.claude/commands/`, emitting:
 *
 * ```
 * === <scope>|<name>|<absolute-path>
 * <file contents>
 * === <scope>|<name>|<absolute-path>
 * <file contents>
 * ...
 * ```
 *
 * Where `<scope>` is `"global"` or `"project"`. We parse each block,
 * extract any YAML frontmatter (e.g. `description: "…"`) and build a
 * [SlashCommand] entry for autocomplete.
 *
 * Lives in its own file so the test suite (`CustomCommandsParserTest`,
 * `ChatViewModelHelpersTest`) imports a stable name from a stable package
 * — these tests existed before the per-CLI module refactor and depended
 * on the helper living next to ChatViewModel.
 */
internal fun parseClaudeCustomCommands(raw: String): List<SlashCommand> {
    if (raw.isBlank()) return emptyList()
    val out = mutableListOf<SlashCommand>()
    var scope = ""; var name = ""; var path = ""
    var buf = StringBuilder()
    var inDoc = false
    fun flush() {
        if (!inDoc || name.isBlank()) return
        val (frontmatter, body) = splitMarkdownFrontmatter(buf.toString())
        val desc = frontmatter["description"] ?: "user-defined command"
        out += SlashCommand(
            name = name,
            description = desc,
            kind = SlashCommandKind.CUSTOM,
            acceptsArgs = body.contains("\$ARGUMENTS"),
            customPrompt = body,
            source = "$scope: $path"
        )
    }
    for (line in raw.lineSequence()) {
        if (line.startsWith("=== ")) {
            flush()
            val parts = line.removePrefix("=== ").split('|', limit = 3)
            scope = parts.getOrNull(0).orEmpty()
            name = parts.getOrNull(1).orEmpty()
            path = parts.getOrNull(2).orEmpty()
            buf = StringBuilder()
            inDoc = true
        } else if (inDoc) {
            buf.append(line).append('\n')
        }
    }
    flush()
    return out
}

/**
 * Cheap YAML-frontmatter parser — `key: value` lines between two `---`
 * fences at the top of the document. Returns `(map, body)`. Returns
 * `(emptyMap, originalText)` when there's no frontmatter.
 *
 * Renamed from the old top-level `splitFrontmatter` to make grep-ability
 * clearer (`SubagentService` has its own copy with the same logic — we
 * keep them separate so a behavior change for one doesn't surprise the
 * other).
 */
internal fun splitMarkdownFrontmatter(text: String): Pair<Map<String, String>, String> {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("---")) return emptyMap<String, String>() to text
    val rest = trimmed.removePrefix("---")
    val end = rest.indexOf("\n---")
    if (end < 0) return emptyMap<String, String>() to text
    val fm = rest.substring(0, end)
    val body = rest.substring(end + 4).trimStart('\n', '\r')
    val map = fm.lineSequence()
        .mapNotNull { line ->
            val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, colon).trim().lowercase() to
                line.substring(colon + 1).trim().trim('"', '\'')
        }
        .toMap()
    return map to body
}
