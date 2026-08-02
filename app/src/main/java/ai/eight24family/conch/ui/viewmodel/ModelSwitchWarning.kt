package ai.eight24family.conch.ui.viewmodel

/**
 * Should switching the model warn the user first?
 *
 * Anthropic's own CLI shows this dialog, and the user asked for the SAME behaviour —
 * (2026-08-02). So the predicate below is not invented: it is the CLI's own `CQt(next,
 * current, cfg, ackedAtOutputTokens, hasConversationMessages)`, read out of claude.exe
 * 2.1.219 and transliterated
 *
 * ```js
 * function CQt(e, t, r, n, o) {
 *   if (!o) return false;                       // no conversation
 *   let i = Uv();                               // current OUTPUT tokens
 *   if (i === 0 || i === n) return false;        // nothing cached / already acked here
 *   …
 *   if (Goe(r, e) === Goe(r, t)) return false;   // effective value unchanged
 *   return true;
 * }
 * ```
 *
 * and on confirm it records `cacheMissAckedAtOutputTokens = Uv()`.
 *
 * WHY IT MATTERS, in this app's terms: Anthropic's prompt cache is keyed PER MODEL.
 * While the model holds, the history rides as `cache_read` (about a tenth of normal
 * input price); the moment it changes, `--resume` re-reads the whole session file and
 * the entire history is re-billed as `cache_creation` (dearer than normal input). One
 * of his turns read 871k cached tokens — switching the model there costs more than a
 * day of ordinary work.
 *
 * The three "don't nag" rules matter as much as the warning itself:
 *  - an empty chat has nothing cached, so switching is free;
 *  - zero output tokens means nothing has been generated yet — likewise free;
 *  - once acknowledged AT a given output-token count, stay quiet until the
 *    conversation actually grows past it. Re-asking at the same point is the
 *    "abi kogda" the user explicitly ruled out.
 */
internal object ModelSwitchWarning {

    /**
     * @param next            model slug/id the user just tapped
     * @param current         model the chat is running on right now
     * @param hasMessages     does this chat have any conversation at all
     * @param outputTokens    output tokens generated in this chat so far
     * @param ackedAtTokens   [outputTokens] when the user last confirmed a switch,
     *                        null if never
     * @param resolve         slug → effective model id, so an alias and the id it
     *                        resolves to are never mistaken for a switch (the
     *                        CLI's `Goe`)
     */
    fun shouldWarn(
        next: String?,
        current: String?,
        hasMessages: Boolean,
        outputTokens: Long,
        ackedAtTokens: Long?,
        resolve: (String) -> String = { it },
    ): Boolean {
        if (!hasMessages) return false
        if (outputTokens == 0L) return false
        if (ackedAtTokens != null && outputTokens == ackedAtTokens) return false
        val a = next?.takeIf { it.isNotBlank() }?.let(resolve)
        val b = current?.takeIf { it.isNotBlank() }?.let(resolve)
        if (a == null) return false
        if (a == b) return false
        return true
    }
}
