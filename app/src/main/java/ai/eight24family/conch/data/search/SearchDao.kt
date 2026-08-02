package ai.eight24family.conch.data.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SearchDao {
    /** Insert one meta row — and via Room's auto-generated content-sync
     *  triggers, the matching FTS posting list. Returns the assigned
     *  rowid (SQLite picks one since `rowid` defaults to 0).
     *
     *  External-content FTS4 means we NEVER insert into `fts_lines`
     *  directly — the AFTER INSERT trigger Room generated on this
     *  table writes the FTS index row for us. Same for updates and
     *  deletes via the BEFORE UPDATE / BEFORE DELETE triggers. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeta(meta: FtsLineMeta): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessionState(state: SessionIndexState)

    /** All session states the index knows about. Drives the reconcile
     *  loop: anything in here but missing from HistoryCache gets
     *  dropped; anything in cache but absent here gets indexed. */
    @Query("SELECT * FROM fts_session_state")
    suspend fun allSessionStates(): List<SessionIndexState>

    /** Diagnostic: total indexed message rows (FTS content lives in the
     *  meta table). Zero with non-zero session_state = FTS got dropped /
     *  never populated. */
    @Query("SELECT COUNT(*) FROM fts_line_meta")
    suspend fun countAllMeta(): Long

    @Query("SELECT * FROM fts_session_state WHERE sessionId = :sid LIMIT 1")
    suspend fun sessionState(sid: String): SessionIndexState?

    /** Drop everything for one session. With external-content FTS4
     *  we only delete from the meta table — Room's BEFORE DELETE
     *  trigger removes the matching FTS rows automatically. */
    @Query("DELETE FROM fts_line_meta WHERE sessionId = :sid")
    suspend fun deleteMetaBySession(sid: String)

    @Query("DELETE FROM fts_session_state WHERE sessionId = :sid")
    suspend fun deleteSessionState(sid: String)

    /** Counted match for a query. Capped at 10001 so the UI can show
     *  "10000+" without scanning the entire result set. */
    @Query("SELECT COUNT(*) FROM (SELECT 1 FROM fts_lines WHERE fts_lines MATCH :q LIMIT 10001)")
    suspend fun countMatches(q: String): Long

    /** TRUE total match counts split by user/assistant vs system role —
     *  capped at 10001 each. These drive the "// N matches · +M system"
     *  caption. Must be the real FTS totals, NOT the size of the first
     *  page (the page is capped at 200 + deduped, which made a search for
     *  a common token like "c" read as an absurd "99 matches · +2
     *  system"). Role lives in fts_line_meta, so we JOIN. */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT 1 FROM fts_lines
            JOIN fts_line_meta m ON m.rowid = fts_lines.rowid
            WHERE fts_lines MATCH :q AND m.role != 'system'
            LIMIT 10001
        )
        """
    )
    suspend fun countNonSystemMatches(q: String): Long

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT 1 FROM fts_lines
            JOIN fts_line_meta m ON m.rowid = fts_lines.rowid
            WHERE fts_lines MATCH :q AND m.role = 'system'
            LIMIT 10001
        )
        """
    )
    suspend fun countSystemMatches(q: String): Long

    /** Paged search. Returns full message content + raw `offsets()`
     *  output per row. The caller ([ai.eight24family.conch.data.ChatSearch.search])
     *  then expands each row into ONE Hit per match using the byte
     *  offsets, so the user sees a distinct row for every occurrence
     *  of the query and can tap the SPECIFIC one they want.
     *
     *  Why content + offsets() instead of SQLite's built-in
     *  snippet(): snippet() only returns the densest match window,
     *  collapsing N matches into one row. We need per-match
     *  navigation.
     *
     *  Content is pulled from `fts_line_meta` since `fts_lines` is
     *  external-content (v8) — `SELECT fts_lines.content` would
     *  return empty strings. */
    @Query(
        """
        SELECT fts_lines.rowid AS rowid,
               m.content AS content,
               offsets(fts_lines) AS offsets,
               m.sessionId AS sessionId,
               m.msgId AS msgId,
               m.ordinal AS ordinal,
               m.role AS role,
               m.sessionPreview AS sessionPreview,
               s.agent AS agent,
               s.serverId AS serverId,
               s.path AS path
        FROM fts_lines
        JOIN fts_line_meta m ON m.rowid = fts_lines.rowid
        LEFT JOIN fts_session_state s ON s.sessionId = m.sessionId
        WHERE fts_lines MATCH :q
        ORDER BY s.sessionMtime DESC, m.sessionId, m.ordinal ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun search(q: String, limit: Int, offset: Int): List<SearchHitRow>

    /** Owner of a session as stamped at index time — drives search-hit →
     *  chat navigation when SessionsCache has forgotten the session. Null
     *  row (or null fields) when the session was never indexed with a
     *  resolvable owner. */
    @Query("SELECT serverId, agent, path FROM fts_session_state WHERE sessionId = :sid LIMIT 1")
    suspend fun sessionOwner(sid: String): SessionOwnerRow?

    @Query("DELETE FROM fts_lines")
    suspend fun clearFts()

    @Query("DELETE FROM fts_line_meta")
    suspend fun clearMeta()

    @Query("DELETE FROM fts_session_state")
    suspend fun clearSessionStates()
}

/** Projection of one FTS row — full content + raw offsets() output.
 *  Multiple Hits get produced from one row (one per match position);
 *  the expansion happens in [ai.eight24family.conch.data.ChatSearch]. */
data class SearchHitRow(
    val rowid: Long,
    /** Full body text indexed for this AgentMessage. Used to slice
     *  per-match snippets around each offset. Can be megabytes for
     *  very long assistant blocks — the query still pages at 200
     *  rows so the cost is bounded. */
    val content: String,
    /** Raw `offsets(fts_lines)` output: space-separated 4-tuples
     *  `colNum termNum byteOffset termSize`. Parsed by ChatSearch. */
    val offsets: String,
    val sessionId: String,
    val msgId: String,
    val ordinal: Int,
    val role: String,
    val sessionPreview: String,
    /** Owner stamped at index time (v11). `agent` lets the hit row show
     *  its agent icon without SessionsCache; `serverId`/`path` let it
     *  navigate. Any may be null for sessions indexed before their owner
     *  was resolvable. */
    val agent: String?,
    val serverId: String?,
    val path: String?,
)

/** Owner projection for [SearchDao.sessionOwner]. */
data class SessionOwnerRow(
    val serverId: String?,
    val agent: String?,
    val path: String?,
)
