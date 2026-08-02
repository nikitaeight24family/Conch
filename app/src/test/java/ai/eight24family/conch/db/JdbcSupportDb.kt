package ai.eight24family.conch.db

import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import java.sql.Connection
import java.util.Locale

/**
 * Minimal `SupportSQLiteDatabase` adapter over a JDBC `Connection`. Only
 * implements the surface area the production migrations actually touch
 * (`execSQL`); the rest throws so we notice immediately if a future
 * migration starts using a broader API and we need to extend this shim.
 *
 * Sole client: [MigrationsTest]. Lives in `test/` so it can never leak
 * into a debug or release build.
 */
internal class JdbcSupportDb(private val conn: Connection) : SupportSQLiteDatabase {

    override fun execSQL(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        conn.prepareStatement(sql).use { ps ->
            bindArgs.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.execute()
        }
    }

    // ── unsupported (migrations don't touch these — fail loud if they ever do) ──

    private fun nope(name: String): Nothing =
        throw UnsupportedOperationException("$name is not implemented in JdbcSupportDb test shim")

    override fun compileStatement(sql: String): SupportSQLiteStatement = nope("compileStatement")
    override fun beginTransaction() = nope("beginTransaction")
    override fun beginTransactionNonExclusive() = nope("beginTransactionNonExclusive")
    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = nope("beginTransactionWithListener")
    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = nope("beginTransactionWithListenerNonExclusive")
    override fun endTransaction() = nope("endTransaction")
    override fun setTransactionSuccessful() = nope("setTransactionSuccessful")
    override fun inTransaction(): Boolean = false
    override val isDbLockedByCurrentThread: Boolean get() = false
    override fun yieldIfContendedSafely(): Boolean = false
    override fun yieldIfContendedSafely(sleepAfterYieldDelay: Long): Boolean = false
    override var version: Int
        get() = nope("version get")
        set(_) = nope("version set")
    override val maximumSize: Long get() = nope("maximumSize")
    override fun setMaximumSize(numBytes: Long): Long = nope("setMaximumSize")
    override var pageSize: Long
        get() = nope("pageSize get")
        set(_) = nope("pageSize set")
    override fun query(query: String): Cursor = nope("query(String)")
    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = nope("query(String,args)")
    override fun query(query: SupportSQLiteQuery): Cursor = nope("query(SupportSQLiteQuery)")
    override fun query(query: SupportSQLiteQuery, cancellationSignal: android.os.CancellationSignal?): Cursor = nope("query(SupportSQLiteQuery,sig)")
    override fun insert(table: String, conflictAlgorithm: Int, values: android.content.ContentValues): Long = nope("insert")
    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int = nope("delete")
    override fun update(table: String, conflictAlgorithm: Int, values: android.content.ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int = nope("update")
    override val isReadOnly: Boolean get() = false
    override val isOpen: Boolean get() = !conn.isClosed
    override fun needUpgrade(newVersion: Int): Boolean = nope("needUpgrade")
    override val path: String? get() = ":memory:"
    override fun setLocale(locale: Locale) = nope("setLocale")
    override fun setMaxSqlCacheSize(cacheSize: Int) = nope("setMaxSqlCacheSize")
    override fun setForeignKeyConstraintsEnabled(enabled: Boolean) = nope("setForeignKeyConstraintsEnabled")
    override fun enableWriteAheadLogging(): Boolean = nope("enableWriteAheadLogging")
    override fun disableWriteAheadLogging() = nope("disableWriteAheadLogging")
    override val isWriteAheadLoggingEnabled: Boolean get() = false
    override val attachedDbs: List<android.util.Pair<String, String>>? get() = emptyList()
    override val isDatabaseIntegrityOk: Boolean get() = true
    override fun close() { conn.close() }
}
