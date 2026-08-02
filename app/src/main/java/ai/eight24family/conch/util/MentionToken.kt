package ai.eight24family.conch.util

/**
 * Pure helpers for @-mention detection in the prompt input — the trigger for
 * the server-side `file_suggestions` control request (Claude's own fuzzy file
 * index). Kept free of Compose/Android so the token rules are unit-testable.
 */
object MentionToken {

    /**
     * The ACTIVE mention query: the input's trailing token when it starts
     * with `@` at a word boundary (start of input or after whitespace).
     * Returns the text after the `@` (possibly empty — a bare trailing `@`
     * legitimately asks for the generic suggestion list, same as the CLI).
     * Null = no mention being typed (no `@`-token at the caret's end, or the
     * token already ended with whitespace).
     */
    fun activeQuery(input: String): String? {
        if (input.isEmpty()) return null
        val at = input.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !input[at - 1].isWhitespace()) return null // user@host etc.
        val tail = input.substring(at + 1)
        if (tail.any { it.isWhitespace() }) return null // mention finished
        return tail
    }

    /** Replace the trailing `@<query>` with `@<path> ` (trailing space so the
     *  user keeps typing). No-op when no active mention. */
    fun complete(input: String, path: String): String {
        val query = activeQuery(input) ?: return input
        val at = input.length - query.length - 1
        return input.substring(0, at) + "@" + path + " "
    }
}
