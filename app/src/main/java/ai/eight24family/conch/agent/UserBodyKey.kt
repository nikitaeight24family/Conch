package ai.eight24family.conch.agent

/**
 * THE identity of a user's prompt, for deciding "is this row the same message
 * as that row".
 *
 * ─── Why this exists ────────────────────────────────────────────────────────
 *
 * A prompt exists in two forms that must collapse onto each other:
 *
 *  1. the OPTIMISTIC copy — exactly the bytes we handed to the CLI, rendered
 *     the moment the user pressed send;
 *  2. the ECHO — the same prompt read back out of the agent's session file.
 *
 * Three separate places decide whether those two are one message:
 * `AgentSessionHistory.loadHistory` (survivors on a rebuild),
 * `ChatViewModel.preserveUnsyncedUserText` (the display twin of the same rule),
 * and `ChatViewModelTailPoll.appendDeduped` (the file echo). All three used
 * `text.trim()` — EXACT string equality.
 *
 * That is a false premise: the CLI does not store the prompt verbatim. Codex
 * appends its own attachment marker as a second content block
 * (`<image name=[Image #1] path="/tmp/…">`, confirmed in the user's own
 * rollout, 2026-08-06) and re-renders history through its terminal formatter,
 * which prefixes `❯ ` and hard-wraps with two-space continuations. One
 * character of divergence and the echo is judged a DIFFERENT message.
 *
 * The consequence is the bug the user reported over and over: leave a chat and
 * come back, and a prompt from several turns ago is sitting at the BOTTOM,
 * below the reply it already got, forever. Re-entry is when the two forms
 * finally meet — the live session outlives the screen, so its optimistic copy
 * survives while the cache re-parse supplies the echo — and every one of the
 * three rules appends what it could not match to the END of the list.
 *
 * So identity is decided on a NORMALISED body: strip what a CLI adds around
 * the user's words, keep the words. Deliberately conservative — it never
 * rewrites what is shown, only what is compared.
 *
 * ⚠ If you add a rule that pairs an optimistic prompt with its echo, use this.
 * A fourth `text.trim()` comparison re-opens the same bug.
 */

/** Codex's own attachment marker, written as its own `input_text` content
 *  block next to the prompt. Never typed by a human. */
private val ATTACHMENT_MARKER = Regex("""<image\s+name=\[[^\]]*]\s+path="[^"]*"\s*/?>""")

/** The prompt glyph a CLI puts in front of the user's line when it re-renders
 *  history through its terminal formatter. Only ever leading. */
private val LEADING_PROMPT_GLYPH = Regex("""^[>❯›»]\s+""")

/** Any run of whitespace — the hard-wrapping a terminal formatter introduces
 *  turns one line into several with two-space continuations, so newlines and
 *  runs of spaces cannot be part of a message's identity. */
private val WHITESPACE_RUN = Regex("""\s+""")

/**
 * Normalised identity key for a user prompt body. Two prompts with the same
 * key are the same message. Never shown to anyone — comparison only.
 */
fun userBodyKey(text: String): String =
    text.replace(ATTACHMENT_MARKER, " ")
        .trim()
        .replace(LEADING_PROMPT_GLYPH, "")
        .replace(WHITESPACE_RUN, " ")
        .trim()

/**
 * Is [shown] already contained in [candidate] — the rule that lets a PARTIAL
 * assistant reply be replaced by its own completion instead of sitting next to
 * it as a duplicate. A stream that was interrupted leaves a prefix of the text
 * the file eventually carries, so prefix-coverage is the honest test; equality
 * alone would keep both.
 */
private fun coversAssistantBody(candidate: String, shown: String): Boolean {
    val a = candidate.trim()
    val b = shown.trim()
    return b.isNotEmpty() && (a == b || a.startsWith(b))
}

/**
 * Merge [incoming] (the file/cache truth) with anything in [current] it has not
 * caught up to yet — the "never lose what is already on screen" rule, in ONE
 * implementation.
 *
 * ⚠ IT COVERS THE AGENT'S WORDS TOO, NOT ONLY THE USER'S.
 *
 * It used to preserve `UserText` and nothing else, so a rebuild against a cache the
 * file had not reached yet DELETED the reply the user was reading. Measured end to
 * end on the user's device (2026-08-06): Stop killed the app-server, the session
 * rebuilt, the Codex thread was minutes old and its cache was still `bytes=0`, so
 * history reloaded from an empty list and the half-answer on screen — which existed
 * only in memory — was dropped. From the user:.
 *
 * The asymmetry was never a decision, it was an omission: the same sentence
 * that calls the user's words sacred applies to an answer they have already
 * read. An assistant row is preserved unless [incoming] has one that COVERS it
 * (equal, or continues it — a partial superseded by its completion), which is
 * what keeps a resumed stream from doubling.
 *
 * ⚠ A PRESERVED ROW GOES BACK WHERE IT WAS, NOT AT THE END.
 *
 * Both call sites used to finish with `incoming + survivors`, which is only ever
 * right for a prompt the user sent seconds ago. For anything older it put the
 * prompt UNDERNEATH the answer it had already received — the user's question,
 * verbatim:. Two independent things had to go wrong to show it (an identity
 * mismatch AND the append), so it appeared only on some re-entries, which is why it
 * survived a month of fixes aimed at the mismatch alone.
 *
 * Position is recovered by ANCHOR — the neighbours a preserved row had in
 * [current] that [incoming] also knows:
 *
 *  - a row PRECEDING it that [incoming] has → re-insert directly after that row;
 *  - else a row FOLLOWING it that [incoming] has → insert directly before that one;
 *  - else nothing around it is locatable → append at the end.
 *
 * The last case is not a fallback, it is the common one and it is right: a
 * prompt sent seconds ago sits alone in a history the file has not caught up
 * to, has no anchors at all, and IS the newest thing in the chat
 * (`loadHistory_preserved_prompt_lands_after_incoming` pins exactly that). The
 * two anchored cases are what stop an OLDER prompt from being dragged down
 * there with it.
 *
 * @param isRewoundAway bodies the user explicitly undid; never resurrect those.
 */
fun mergeUnsyncedUserText(
    current: List<AgentMessage>,
    incoming: List<AgentMessage>,
    isRewoundAway: (String) -> Boolean = { false },
): List<AgentMessage> {
    if (current.isEmpty()) return incoming
    val incomingIds = incoming.mapTo(HashSet()) { it.id }
    val incomingUserCounts = HashMap<String, Int>()
    for (m in incoming) if (m is AgentMessage.UserText) {
        val b = userBodyKey(m.text)
        incomingUserCounts[b] = (incomingUserCounts[b] ?: 0) + 1
    }
    val incomingAssistant = incoming.filterIsInstance<AgentMessage.AssistantText>().map { it.text }
    // The file's FRONTIER: the last row of `current` that [incoming] also has.
    // Anything past it is content the file has not reached; anything before it
    // is content the file has an opinion about, and there its opinion wins.
    val lastAnchorIndex = current.indexOfLast { it.id in incomingIds }
    // Which rows of `current` are un-synced, and where each one sat.
    data class Kept(val row: AgentMessage, val prevAnchor: String?, val index: Int)
    val kept = ArrayList<Kept>()
    val seen = HashMap<String, Int>()
    var prevAnchor: String? = null
    current.forEachIndexed { i, m ->
        if (m.id in incomingIds) { prevAnchor = m.id; return@forEachIndexed }
        when (m) {
            is AgentMessage.UserText -> {
                if (isRewoundAway(m.text)) return@forEachIndexed
                val b = userBodyKey(m.text)
                val n = seen[b] ?: 0
                seen[b] = n + 1
                if (n < (incomingUserCounts[b] ?: 0)) return@forEachIndexed  // the file's copy covers it
            }
            is AgentMessage.AssistantText -> {
                if (m.text.isBlank()) return@forEachIndexed
                if (isRewoundAway(m.text)) return@forEachIndexed
                // 1. The file carries this text, or its continuation → its copy wins.
                if (incomingAssistant.any { coversAssistantBody(it, m.text) }) return@forEachIndexed
                // 2. AGENT CONTENT IS FILE-AUTHORITATIVE — but only where the file
                //    HAS content. Keep this row when the file knows no agent text
                //    at all (a rebuilt session whose cache is still empty: the case
                //    that erased a half-read answer), or when it sits past the
                //    file's frontier (live output the mirror hasn't caught yet).
                //    Otherwise the file's version replaces it, as it always has —
                //    `loadHistory_replaces_agent_content_normally` pins that.
                val beyondFrontier = lastAnchorIndex >= 0 && i > lastAnchorIndex
                if (incomingAssistant.isNotEmpty() && !beyondFrontier) return@forEachIndexed
            }
            else -> return@forEachIndexed
        }
        kept += Kept(m, prevAnchor, i)
    }
    if (kept.isEmpty()) return incoming

    val after = HashMap<String, MutableList<AgentMessage>>()
    val before = HashMap<String, MutableList<AgentMessage>>()
    val tail = ArrayList<AgentMessage>()
    for (k in kept) {
        val anchor = k.prevAnchor
        if (anchor != null) {
            after.getOrPut(anchor) { ArrayList() }.add(k.row)
            continue
        }
        // Nothing before it is locatable — look forward instead.
        val next = current.drop(k.index + 1).firstOrNull { it.id in incomingIds }?.id
        if (next != null) before.getOrPut(next) { ArrayList() }.add(k.row)
        else tail.add(k.row)                      // newest — belongs at the end
    }

    val out = ArrayList<AgentMessage>(incoming.size + kept.size)
    for (m in incoming) {
        before[m.id]?.let { out.addAll(it) }
        out.add(m)
        after[m.id]?.let { out.addAll(it) }
    }
    out.addAll(tail)
    return out
}
