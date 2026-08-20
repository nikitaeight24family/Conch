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

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "sshai.db"
            )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                .also { INSTANCE = it }
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
