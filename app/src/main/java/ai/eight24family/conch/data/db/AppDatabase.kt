package ai.eight24family.conch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ServerEntity::class, SshKeyEntity::class, ChatSessionEntity::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun chatSessionDao(): ChatSessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** Current DB file. Renamed from the dead "sshai.db" — the file is
         *  migrated in place (see [migrateLegacyDbName]) so no user loses
         *  their servers or sessions on update. */
        const val DB_NAME = "conch.db"
        private const val LEGACY_DB_NAME = "sshai.db"

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: run {
                migrateLegacyDbName(context.applicationContext)
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** One-time file rename: if the new DB doesn't exist yet but the old
         *  one does, move it (and its -wal/-shm siblings) into place. Room then
         *  opens the SAME data under the new name. Idempotent, cheap, invisible. */
        private fun migrateLegacyDbName(context: Context) {
            val new = context.getDatabasePath(DB_NAME)
            if (new.exists()) return
            val old = context.getDatabasePath(LEGACY_DB_NAME)
            if (!old.exists()) return
            runCatching {
                for (suffix in listOf("", "-wal", "-shm")) {
                    val from = java.io.File(old.parentFile, LEGACY_DB_NAME + suffix)
                    val to = java.io.File(new.parentFile, DB_NAME + suffix)
                    if (from.exists()) from.renameTo(to)
                }
                android.util.Log.i("Conch-Db", "migrated legacy db name → $DB_NAME")
            }
        }

        /**
         * Test-only: drop the cached singleton so the next [get] rebuilds the
         * Room database against the (potentially new) Robolectric-managed
         * application context. Without this, the first test class's DB
         * leaks into subsequent test classes and references a now-defunct
         * Application instance.
         */
        @androidx.annotation.VisibleForTesting
        fun resetForTest() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
