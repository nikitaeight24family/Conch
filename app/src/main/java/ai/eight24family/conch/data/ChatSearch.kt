package ai.eight24family.conch.data

import ai.eight24family.conch.data.search.SearchDao
import ai.eight24family.conch.data.search.SearchDatabase
import ai.eight24family.conch.data.search.SearchHitRow
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Search facade backed by the FTS4 index in [SearchDatabase].
 * Replaces the previous linear-scan implementation that decoded
 * every cached JSONL into a String at once (OOM on the user's 24 MB
 * session). Now:
 *
 * - Tokenized + prefix-indexed on insert. - `MATCH` queries hit
 * posting lists, not full content — typically <50 ms even with
 * thousands of indexed lines. - Count is capped at 10001 so
 * very-common-prefix queries return a sentinel "10000+" instead of
 * stalling SQLite on millions of matches. - Hits are paged; callers
 * receive ~200 rows at a time and load more as the user scrolls.
 *
 * Prefix index covers token lengths 1-4 so even a single typed char
 * is an indexed lookup, not a scan.
 */
object ChatSearch {

    /** Stable cap returned by [count] when the index has > 10000
     *  matches. Lets the UI render "10000+ matches" without forcing
     *  SQLite to walk the entire result set. */
    const val COUNT_OVERFLOW_THRESHOLD = 10_000L

    /** One match. **Not** one indexed row — one occurrence of the query
     *  inside an indexed message. A long message with N matches expands
     *  to N Hits, each carrying its own [charOffset] within the message
     *  body. Telegram-style: tap a specific row → chat lands at that
     *  exact occurrence, highlight and scroll-centre both keyed to the
     *  same offset.
     *
     *  `snippet` is built locally around [charOffset] with U+0001/U+0002
     *  markers around the matched span (same encoding as SQLite's
     *  built-in `snippet()` produced — UI renderer untouched). */
    data class Hit(
        /** Stable hit-row id: `<fts_rowid>:<charOffset>`. Used as the
         *  LazyColumn item key (LazyColumn refuses duplicate keys, so
         *  the per-rowid fts_rowid alone won't do — we have N hits per
         *  rowid now). */
        val hitKey: String,
        val snippet: String,
        val sessionId: String,
        /** Stable id of the matched AgentMessage (Claude: msg_xxx#N).
         *  Codex / Gemini parsers assign random UUIDs to user/assistant
         *  text, so [msgId] is NOT stable across parses for those agents
         *  — use [ordinal] as the primary anchor and treat [msgId] as a
         *  secondary verification probe only. */
        val msgId: String,
        /** 0-based position of the matched AgentMessage in the parsed
         *  list. Deterministic given (agent, JSONL bytes) — both indexer
         *  and ChatViewModel produce identical lists when they share the
         *  agent spec, so this is the reliable cross-process anchor. */
        val ordinal: Int,
        /** Char offset of THIS occurrence within the message body —
         *  what ChatScreen uses to centre the matched line and pick
         *  the right highlight occurrence. */
        val charOffset: Int,
        /** "user" or "assistant" — drives the hit-row's ❯-prefix marker
         *  (cyan ❯ for user messages, plain for assistant). */
        val role: String,
        val sessionPreview: String,
        /** Owner stamped in the index at index time (v11). [agentName]
         *  drives the agent icon in the result row WITHOUT depending on
         *  the volatile SessionsCache; [serverId]/[path] let the row
         *  navigate. Any may be null for sessions indexed before their
         *  owner was resolvable. */
        val agentName: String?,
        val serverId: String?,
        val path: String?,
    )

    /**
     * Result of one search query.
     *
     * @param count Real count up to [COUNT_OVERFLOW_THRESHOLD]; equal
     *  to that constant +1 when overflowed. The UI checks
     *  `count > COUNT_OVERFLOW_THRESHOLD` to render "10000+".
     * @param hits First page of [pageSize] hits, newest first
     *  (highest rowid = most recently indexed = most recent chat).
     * @param queryEchoed The exact query string the user typed —
     *  passed through so the UI can ignore stale results when the
     *  user has typed further while a query is in flight.
     */
    data class Result(
        val queryEchoed: String,
        /** TRUE total of non-system matches (capped at [COUNT_OVERFLOW]).
         *  This is the real FTS count, NOT the deduped first-page size —
         *  for a common token the page is far smaller than reality. */
        val count: Long,
        /** TRUE total of system-role matches (capped). Drives "+M system". */
        val systemCount: Long,
        val hits: List<Hit>,
    )

    private const val PAGE_SIZE = 200
    /** Counts are capped at this in SQL (LIMIT 10001 → 10001 means
     *  "10000+"). UI renders the overflow form past 10000. */
    const val COUNT_OVERFLOW = 10_000L

    /**
     * Run [q] against the FTS index. Returns immediately if the
     * query is blank. Caller is expected to debounce (~60 ms) before
     * dispatching here — every call hits SQLite.
     *
     * Throws nothing: any storage error is swallowed and returns an
     * empty Result. Search must never crash the app — that's how we
     * got into the OOM-rebuild work in the first place.
     */
    suspend fun search(q: String): Result = withContext(Dispatchers.IO) {
        ai.eight24family.conch.util.Tracing.section(
            ai.eight24family.conch.util.Tracing.Names.FTS_SEARCH
        ) {
            val trimmed = q.trim()
            if (trimmed.isEmpty()) return@section Result(q, 0, 0, emptyList())
            val matchExpr = buildMatchExpression(trimmed)
            val dao = ServiceLocator.searchDatabase.searchDao()
            runCatching {
                // TRUE totals (not the deduped page size). For a common
                // token the page is ≤200/deduped while reality is thousands.
                val nonSystemCount = dao.countNonSystemMatches(matchExpr)
                val systemCount = dao.countSystemMatches(matchExpr)
                val rows = if (nonSystemCount == 0L && systemCount == 0L) emptyList()
                else dao.search(matchExpr, limit = PAGE_SIZE, offset = 0)
                // De-duplication, two layers (proven from on-device logs):
                //
                // 1) distinctBy { content } — collapse the SAME message
                // recurring across overlapping session files. Different
                // sessionIds, and the raw-JSONL wrappers differ (timestamps /
                // parent- uuids) so even the content-addressed stableId msgIds
                // differ — which is exactly why deduping by msgId alone still
                // showed. 2) distinctBy { msgId } — collapse the per-OCCURRENCE
                // hits expandRowToHits emits within one message.
                val hits = rows.asSequence()
                    .distinctBy { it.content }
                    .flatMap { expandRowToHits(it, trimmed).asSequence() }
                    .distinctBy { it.msgId }
                    .toList()
                Result(
                    queryEchoed = q,
                    count = nonSystemCount,
                    systemCount = systemCount,
                    hits = hits,
                )
            }.getOrElse {
                android.util.Log.w("Conch-Search", "search($q) failed: ${it.message}")
                Result(q, 0, 0, emptyList())
            }
        }
    }

    /** Load the next page beyond what [search] returned. Caller
     *  passes the offset (e.g. previous results.size). */
    suspend fun loadMore(q: String, offset: Int): List<Hit> = withContext(Dispatchers.IO) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val dao = ServiceLocator.searchDatabase.searchDao()
        SilentlyTry.loggedOrElse("Conch-Search", "loadMore search page", emptyList()) {
            dao.search(buildMatchExpression(trimmed), limit = PAGE_SIZE, offset = offset)
                .flatMap { expandRowToHits(it, trimmed) }
        }
    }

    /** Convert a free-text input into an FTS4 MATCH expression.
     *
     * - Splits on whitespace; each token becomes a prefix query so
     * matches, etc. - Double-quotes are escaped (SQLite uses `""` to
     * embed a quote inside a quoted string). - Each token is
     * double-quoted so punctuation in the input (`-`, `:`, `(`, `)`,
     * etc.) doesn't get interpreted as an FTS operator and crash the
     * parser.
     */
    private fun buildMatchExpression(q: String): String {
        val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { tok ->
            val safe = tok.replace("\"", "\"\"")
            "\"$safe\"*"
        }
    }

    /** Expand one FTS row into one Hit per match. Walks the
     * `offsets()` output (4-tuple-per-match: colNum termNum byteOffset
     * termSize), converts each byte offset to a char offset against
     * [SearchHitRow.content], dedupes nearby term-internal offsets, and
     * slices a local snippet around each distinct match. */
    private fun expandRowToHits(row: SearchHitRow, query: String): List<Hit> = ai.eight24family.conch.util.Tracing.section(
        ai.eight24family.conch.util.Tracing.Names.FTS_EXPAND_HITS
    ) {
        val byteOffsets = parseOffsetsBytePositions(row.offsets)
        if (byteOffsets.isEmpty()) return@section emptyList()
        // FTS4 can emit multiple terms inside the same token (multi-
        // word query). For our typical single-prefix queries there's
        // one per match, but distinctness + sort keeps multi-token
        // searches sane.
        //
        // All in-place on a single IntArray — no Sequence chain (which
        // would build ArrayList<Int> + boxed HashSet + sorted copy for
        // every hit row, three allocations × N matches per row).
        val n = byteOffsets.size
        for (i in 0 until n) byteOffsets[i] = byteOffsetToCharOffset(row.content, byteOffsets[i])
        java.util.Arrays.sort(byteOffsets)
        // Compact duplicates in-place: byteOffsets[0..uniq) holds the
        // distinct sorted values once we're done.
        var uniq = if (n > 0) 1 else 0
        for (i in 1 until n) {
            if (byteOffsets[i] != byteOffsets[i - 1]) {
                byteOffsets[uniq] = byteOffsets[i]
                uniq++
            }
        }
        val out = ArrayList<Hit>(uniq)
        for (i in 0 until uniq) {
            val charOff = byteOffsets[i]
            out += Hit(
                hitKey = "${row.rowid}:$charOff",
                snippet = buildSnippet(row.content, charOff, query.length),
                sessionId = row.sessionId,
                msgId = row.msgId,
                ordinal = row.ordinal,
                charOffset = charOff,
                role = row.role,
                sessionPreview = row.sessionPreview,
                agentName = row.agent,
                serverId = row.serverId,
                path = row.path,
            )
        }
        out
    }

    /** Parse FTS4 `offsets()` raw string into a freshly-allocated
     *  IntArray of the BYTE-offsets only (3rd of each 4-tuple `c t b
     *  s`). Caller mutates the array in-place to translate bytes → chars
     *  and to dedup/sort, so we avoid the boxed `List<Int>` we used to
     *  return through a Sequence chain. */
    private fun parseOffsetsBytePositions(raw: String): IntArray {
        if (raw.isEmpty()) return IntArray(0)
        // Single pass: count tokens, then re-walk and extract every
        // 4th. No `split` (allocates List<String>) and no per-element
        // `toIntOrNull` (allocates boxed Int) — hand-rolled scanner.
        // Each tuple: `c t b s ` — 4 integers separated by spaces.
        // FTS4 contract is strict so we trust the layout.
        var tokens = 1
        for (i in 0 until raw.length) if (raw[i] == ' ') tokens++
        if (tokens < 4) return IntArray(0)
        val out = IntArray(tokens / 4)
        var tokenIdx = 0
        var outIdx = 0
        var n = 0
        var have = false
        for (i in 0..raw.length) {
            val end = i == raw.length
            val c = if (end) ' ' else raw[i]
            if (c == ' ') {
                if (have) {
                    if (tokenIdx % 4 == 2) {
                        out[outIdx++] = n
                    }
                    tokenIdx++
                    n = 0
                    have = false
                }
            } else {
                n = n * 10 + (c.code - '0'.code)
                have = true
            }
            if (end) break
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    /** UTF-8 byte offset → UTF-16 (Java/Kotlin String) char offset.
     *  Walks code points; each code point contributes 1-4 UTF-8 bytes
     *  and 1-2 UTF-16 chars (2 for supplementary planes). */
    private fun byteOffsetToCharOffset(content: String, byteOff: Int): Int {
        if (byteOff <= 0) return 0
        var c = 0
        var b = 0
        while (c < content.length && b < byteOff) {
            val cp = content.codePointAt(c)
            val charLen = if (Character.isSupplementaryCodePoint(cp)) 2 else 1
            val byteLen = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (b + byteLen > byteOff) break
            b += byteLen
            c += charLen
        }
        return c
    }

    /** Build a per-match snippet: ~40 chars of body on each side of
     *  the match, with U+0001/U+0002 markers around the matched span.
     *  Same encoding as SQLite's `snippet()` so the HitRow renderer
     *  (which already parses these markers) needs no changes. */
    private fun buildSnippet(content: String, charOff: Int, queryLen: Int): String {
        val matchEnd = (charOff + queryLen).coerceAtMost(content.length)
        val from = (charOff - 40).coerceAtLeast(0)
        val to = (matchEnd + 40).coerceAtMost(content.length)
        val sb = StringBuilder(to - from + 4)
        if (from > 0) sb.append('…')
        sb.append(content, from, charOff)
        sb.append('')
        sb.append(content, charOff, matchEnd)
        sb.append('')
        sb.append(content, matchEnd, to)
        if (to < content.length) sb.append('…')
        return sb.toString()
    }
}
