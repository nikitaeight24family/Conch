package ai.eight24family.conch.data.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Full-text index of every searchable line of every cached chat
 * session. Backed by SQLite FTS4 in **external-content** mode
 * (Room's `contentEntity = FtsLineMeta::class`) — the FTS table
 * stores ONLY the inverted index (token postings + offsets), the
 * actual text body lives in [FtsLineMeta.content]. Without this,
 * every indexed message would be stored twice on flash: once in
 * `filesDir/session_history/<sid>.jsonl` (cache, source of truth)
 * and once again here in `fts_lines.content`. On the user's 80
 * sessions × 1-24 MB workload that doubles write/read amplification.
 *
 * Room generates the necessary BEFORE/AFTER triggers automatically:
 * inserts/updates/deletes on [FtsLineMeta] are mirrored into this
 * virtual table by SQLite. Our indexer only writes to
 * `fts_line_meta` — the FTS index follows.
 *
 * Tokenizer notes: - `unicode61` correctly word-tokenises Cyrillic,
 * Latin, mixed languages. The default ICU tokenizer requires bundled
 * ICU and is overkill here. We want literal matches, not fuzzy. -
 * `prefix=[1,2,3,4]` builds a prefix index for tokens of length 1-4
 * chars. Without it, the user's first-char input triggers a full
 * table scan; with it, it's an indexed lookup. Cost: ~1.3× extra
 * index size per prefix length added.
 */
@Entity(tableName = "fts_lines")
@Fts4(
    contentEntity = FtsLineMeta::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=0"],
    prefix = [1, 2, 3, 4],
)
data class FtsLine(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long = 0,
    /** Virtual pointer to [FtsLineMeta.content] — the column name
     *  must match the content-entity column the FTS index reads from. */
    val content: String,
)

/**
 * Sidecar table keyed by the same rowid as `fts_lines`. Holds the
 * non-searchable metadata we need to a) navigate from a hit to the
 * chat it came from and b) render a chat-title in the hit row
 * without joining the (potentially huge) cache file at query time.
 *
 * v3 schema: indexed unit is one parsed [ai.eight24family.conch.agent.AgentMessage]
 * (not one raw JSONL line). msgId + ordinal are the anchor pair
 * that lets ChatScreen jump straight to the right LazyColumn item —
 * Telegram-style instant navigation, no scroll motion.
 *
 * v8 schema: also holds `content` (the searchable message body) —
 * it's the **single** disk-resident copy now that `fts_lines` is
 * external-content.
 */
@Entity(tableName = "fts_line_meta")
data class FtsLineMeta(
    // autoGenerate so each insertMeta(rowid = 0) gets a FRESH rowid.
    // The recovery reconstruction had this as a plain `primaryKeys =
    // ["rowid"]` with `val rowid: Long`, so every insert of rowid=0
    // under onConflict=REPLACE collapsed ALL messages into the single
    // row at rowid=0 — the FTS table ended up with 1 row total and
    // search returned nothing. autoGenerate restores per-message
    // rows; the AFTER INSERT trigger Room emits mirrors NEW.rowid
    // into the external-content fts_lines docid.
    @PrimaryKey(autoGenerate = true)
    val rowid: Long = 0,
    val sessionId: String,
    /** AgentMessage.id of the indexed message. Stable across opens
     *  (Claude: `msg_xxx#blockIdx`, Codex: `codex_...`, Gemini: uuid). */
    val msgId: String,
    /** 0-based position of the message within its chat — feeds
     *  [androidx.compose.foundation.lazy.LazyListState] as the
     *  initialFirstVisibleItemIndex when the user taps this hit. */
    val ordinal: Int,
    /** "user" or "assistant" — drives the hit-row's ❯-prefix marker
     *  so the user can tell at a glance whether a match is in their
     *  own message or the agent's reply. */
    val role: String,
    /** First user-message text of the chat, truncated. Precomputed
     *  at index time so global-search hits render the chat label
     *  without re-parsing the JSONL. */
    val sessionPreview: String,
    /** Searchable message body — text of UserText/AssistantText (or
     *  the raw payload for `user_synthetic` System messages). Source
     *  of truth for both the FTS index and the per-match snippet
     *  builder. The column name MUST match the `content` declaration
     *  in [FtsLine] — that's the contract Room uses to wire the FTS
     *  external-content table to this sidecar. */
    val content: String,
)

/**
 * Per-session bookkeeping. Drives the incremental reconcile in
 * [SearchIndexer]: if a session's cache file has grown / shrunk /
 * disappeared since [indexedAt], we reindex (or drop) it.
 */
@Entity(tableName = "fts_session_state", primaryKeys = ["sessionId"])
data class SessionIndexState(
    val sessionId: String,
    /** Wall-clock ms when this session was last fully indexed. */
    val indexedAt: Long,
    /** Size of the cache bytes when we indexed. If the current
     *  cached size differs, we re-index — cheaper than computing a
     *  full content hash and good enough since cache writes are
     *  always append-or-replace. */
    val sourceBytes: Int,
    /** Which agent OWNS this session, resolved at index time (from
     *  SessionsCache, or detected from the JSONL structure when the
     *  cache can't say). Stored here so a search hit can render the
     *  agent icon and route to the right parser WITHOUT depending on
     *  the volatile SessionsCache at tap time — that dependency is
     *  exactly why hits on cached-but-unlisted sessions showed no
     *  agent icon and refused to navigate. Null only if even content
     *  detection failed. */
    val agent: String? = null,
    /** Owning server id + on-disk path, when resolvable from
     *  SessionsCache at index time. Lets a hit navigate straight to
     *  its chat. Null for sessions whose server the app no longer
     *  knows (cached earlier, server since dropped from the list) —
     *  those still show the agent icon but can't open until their
     *  server is seen again. */
    val serverId: String? = null,
    val path: String? = null,
    /** Version of the INDEXING LOGIC (searchableBody / role rules) this
     *  session was indexed with. When [SearchIndexer.INDEXER_VERSION]
     *  bumps (e.g. we start indexing tool calls / system messages), a
     *  mismatch forces re-index even though the cache bytes are
     *  unchanged — without needing a Room schema/version bump every
     *  time the WHAT-we-index rules change. */
    val indexerVersion: Int = 0,
    /** Session's recency (RemoteSession.lastActiveAt = server-side mtime),
     *  or 0 if unknown (content-detected session whose server we don't
     *  know). Search results ORDER BY this DESC so newest chats surface
     *  first instead of arbitrary index-insertion order. */
    val sessionMtime: Long = 0,
)
