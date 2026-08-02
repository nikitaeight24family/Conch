package ai.eight24family.conch.ui.components

/**
 * Parser for unified-diff text. Recognizes the standard output of `git
 * diff`, `diff -u`, Codex's `file_change.patch`, and Claude's Edit-tool
 * patches when they round-trip through `git`. Best-effort — unified
 * diff is intentionally loose, real-world variants drift in formatting
 * and we'd rather render *something useful* than reject inputs.
 *
 * Used by [DiffView] in the chat to upgrade a raw tool-result text dump
 * into a colored side-by-side / inline view.
 *
 * The parser is deliberately not strict about line-counts in hunk
 * headers (they're informational; we recompute from actual content) and
 * tolerates missing file headers (an isolated `@@` hunk becomes a
 * single-file diff with `displayPath = "(unknown)"`).
 */
object UnifiedDiffParser {

    /**
     * Cheap heuristic for ToolResultLine to decide whether to render
     * with [DiffView] or fall through to [CopyableCodeBlock]. Looks for
     * the combination of a hunk header (`@@`) and at least one
     * `+`/`-` non-header line — that pair is unambiguous to unified
     * diff and rare to hit in arbitrary tool output. Bounded scan to
     * keep big outputs (multi-MB shell logs) cheap.
     */
    fun looksLikeDiff(text: String): Boolean {
        if (text.isBlank()) return false
        var sawHunk = false
        var sawSign = false
        text.lineSequence().take(300).forEach { line ->
            when {
                line.startsWith("@@") -> sawHunk = true
                line.startsWith("+++ ") || line.startsWith("--- ") -> { /* file header, not a sign */ }
                line.startsWith("+") || line.startsWith("-") -> sawSign = true
            }
        }
        return sawHunk && sawSign
    }

    /**
     * Parse the entire blob into one or more files' diffs. Files are
     * separated by `--- a/...` + `+++ b/...` header pairs, or by raw
     * `diff --git ...` lines. Hunks are separated by `@@ ... @@`
     * lines. Body lines preserve content (without their leading
     * `+`/`-`/space marker — the marker becomes [DiffLine.kind]).
     */
    fun parse(text: String): List<DiffFile> {
        val files = mutableListOf<DiffFile>()
        var pathOld: String? = null
        var pathNew: String? = null
        var hunks = mutableListOf<DiffHunk>()
        var hunkHeader: String? = null
        var hunkLines = mutableListOf<DiffLine>()

        fun flushHunk() {
            if (hunkHeader != null) {
                hunks += DiffHunk(hunkHeader!!, hunkLines.toList())
            }
            hunkHeader = null
            hunkLines = mutableListOf()
        }
        fun flushFile() {
            flushHunk()
            if (hunks.isNotEmpty() || pathOld != null || pathNew != null) {
                files += DiffFile(pathOld, pathNew, hunks.toList())
            }
            pathOld = null
            pathNew = null
            hunks = mutableListOf()
        }

        for (line in text.lineSequence()) {
            when {
                line.startsWith("diff --git ") -> {
                    // New file starting — flush whatever we were
                    // collecting and reset for the next one.
                    flushFile()
                }
                line.startsWith("--- ") -> {
                    flushHunk()  // hunk for previous file (if any) closes here
                    pathOld = line.removePrefix("--- ").removePrefix("a/").trim()
                        .takeIf { it.isNotEmpty() && it != "/dev/null" }
                }
                line.startsWith("+++ ") -> {
                    pathNew = line.removePrefix("+++ ").removePrefix("b/").trim()
                        .takeIf { it.isNotEmpty() && it != "/dev/null" }
                }
                line.startsWith("@@") -> {
                    flushHunk()
                    hunkHeader = line
                }
                hunkHeader != null -> {
                    // Inside a hunk: classify the line by its leading
                    // marker. Anything that isn't +/-/space (e.g. a
                    // stray "\ No newline at end of file" marker) is
                    // dropped — it's metadata, not content.
                    val kind = when {
                        line.startsWith("+") -> DiffLine.Kind.ADD
                        line.startsWith("-") -> DiffLine.Kind.REMOVE
                        line.startsWith(" ") -> DiffLine.Kind.CONTEXT
                        line.isEmpty() -> DiffLine.Kind.CONTEXT  // git sometimes drops the leading space on blank context
                        line.startsWith("\\") -> null
                        else -> null  // unknown marker
                    } ?: continue
                    val content = if (line.isNotEmpty()) line.substring(1) else ""
                    hunkLines += DiffLine(kind, content)
                }
                // Lines outside any hunk and before a file header are
                // metadata (e.g. "index abc..def 100644") — drop.
                else -> Unit
            }
        }
        flushFile()
        return files
    }
}

/** One classified line inside a hunk. */
data class DiffLine(val kind: Kind, val content: String) {
    enum class Kind { ADD, REMOVE, CONTEXT }
}

/** A single hunk inside a file diff — i.e. one `@@ ... @@` block. */
data class DiffHunk(val header: String, val lines: List<DiffLine>)

/** A whole file's set of hunks plus its old/new path identifiers. */
data class DiffFile(val pathOld: String?, val pathNew: String?, val hunks: List<DiffHunk>) {
    /** Best human label for the file. New path preferred (covers renames
     *  and additions); falls back to old (deletions) or a sentinel. */
    val displayPath: String get() = pathNew ?: pathOld ?: "(unknown)"
}
