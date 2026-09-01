package ai.eight24family.conch.data.search

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FtsLine::class, FtsLineMeta::class, SessionIndexState::class],
    // v8: `fts_lines` is external-content FTS4 — the table holds
    // only the inverted index (tokens, postings, offsets), the
    // actual body text moved to `fts_line_meta.content`. On the
    // user's 80 sessions × 1-24 MB workload this kills the doubled
    // disk footprint (jsonl cache + duplicate FTS-content blob)
    // Durov critique #7 called out.
    // v9: fts_line_meta.rowid is now @PrimaryKey(autoGenerate=true) —
    // the v8 recovery reconstruction wrongly used a plain rowid PK, so
    // insert(rowid=0)+REPLACE collapsed every message into one row and
    // search returned nothing. Schema (PK) change → version bump.
    // v10: forced clean rebuild attempt (version-only bump). NOTE: a
    // version-only bump did NOT reliably trigger the destructive wipe on
    // device (observed: index stayed at 80 sessions / 6592 rows). v11
    // below carries a REAL schema change so the migration definitely fires.
    // v11: fts_session_state gains agent/serverId/path columns so a search
    // hit can show its agent icon and navigate WITHOUT the volatile
    // SessionsCache. Real schema change → identity hash changes → Room
    // must migrate → no Migration(_,11) → fallbackToDestructiveMigration
    // drops + recreates → SearchIndexer.reconcile rebuilds every cached
    // chat from scratch, now stamping each session's owner. HistoryCache
    // (the chats themselves) is untouched.
    // v12: fts_session_state gains indexerVersion; the indexer now also
    // indexes tool calls (ToolUse, e.g. update_plan), tool outputs
    // (ToolResult), and system messages — previously ONLY user/assistant
    // text was searchable, so a search for anything inside a plan/command/
    // output found nothing. Schema change → destructive reindex repopulates
    // the FTS with the broadened content.
    // v13: fts_session_state gains sessionMtime so search results sort
    // newest-first (was arbitrary index-insertion order). Schema change →
    // reindex re-stamps every session's mtime.
    version = 13,
    exportSchema = false,
)
abstract class SearchDatabase : RoomDatabase() {
    abstract fun searchDao(): SearchDao

    companion object {
        /** Single VM-level instance — Room handles connection pooling
         *  internally. ServiceLocator owns the singleton. */
        fun create(context: Context): SearchDatabase {
            // Drop the abandoned legacy index (derived data — no loss).
            runCatching {
                for (s in listOf("", "-wal", "-shm")) {
                    context.getDatabasePath("ssh_ai_search.db$s").delete()
                }
            }
            return Room.databaseBuilder(
                context.applicationContext,
                SearchDatabase::class.java,
                // Renamed off the dead brand. This index is 100% derived from
                // HistoryCache, so the old ssh_ai_search.db is just abandoned
                // (best-effort deleted below) and this one rebuilds itself.
                "conch_search.db",
            )
                // Real migration for the known 7→8 path…
                .addMigrations(MIGRATION_7_8)
                // …PLUS a destructive fallback. Durov #8 said — true for
                // USER data. But this FTS index is 100% DERIVED from
                // HistoryCache; SearchIndexer.reconcile rebuilds it from
                // the cached JSONL in a few seconds. For regenerable data,
                // a destructive fallback is the CORRECT design, not a
                // flag: it means a schema mismatch (like the v8→v9 rowid
                // PK fix, or any reconstruction drift) self-heals into a
                // clean rebuild instead of bricking search forever.
                // Removing it earlier is exactly why a phantom/empty index
                // could persist undetected.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}

/**
 * v7 → v8: external-content FTS4 reshape.
 *
 * **What changed.** In v7, `fts_lines.content` was the source of
 * truth for the indexed message body — FTS4 was a regular
 * content-bearing table. Cost: every indexed message lived twice on
 * flash, once in the cache JSONL and once in `fts_lines.content`.
 *
 * In v8, `fts_lines` is **external-content** (Room's
 * `contentEntity = FtsLineMeta::class`). The FTS table is just the
 * inverted index; the actual bytes move into `fts_line_meta.content`,
 * and Room generates BEFORE/AFTER triggers to keep the FTS index in
 * sync on inserts/updates/deletes of the meta table.
 *
 * **Migration strategy.** We can't `ALTER VIRTUAL TABLE` an FTS4
 * table to change its `content=` clause — SQLite has no such
 * command. So:
 *   1. Add `content` column to `fts_line_meta` (was missing in v7).
 *   2. Backfill `fts_line_meta.content` from `fts_lines.content` by
 *      rowid — the join key was already 1:1 by design.
 *   3. Drop the old `fts_lines` (loses no information).
 *   4. Create the new external-content `fts_lines` with the exact
 *      pragma settings Room infers from `@Fts4(contentEntity=...)`.
 *   5. Rebuild the FTS index via
 *      `INSERT INTO fts_lines(fts_lines) VALUES('rebuild')` — a
 *      built-in FTS4 command that walks the content table and
 *      repopulates postings.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE fts_line_meta ADD COLUMN content TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            """
            UPDATE fts_line_meta
            SET content = COALESCE(
                (SELECT content FROM fts_lines WHERE fts_lines.rowid = fts_line_meta.rowid),
                ''
            )
            """.trimIndent()
        )
        db.execSQL("DROP TABLE fts_lines")
        db.execSQL(
            """
            CREATE VIRTUAL TABLE fts_lines USING fts4(
                content TEXT NOT NULL,
                content=`fts_line_meta`,
                tokenize=unicode61 `remove_diacritics=0`,
                prefix=`1,2,3,4`
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO fts_lines(fts_lines) VALUES('rebuild')")
    }
}
