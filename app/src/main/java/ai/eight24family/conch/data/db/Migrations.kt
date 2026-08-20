package ai.eight24family.conch.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE servers ADD COLUMN agent TEXT NOT NULL DEFAULT 'CLAUDE'")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ssh_keys (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                publicKey TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                comment TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("ALTER TABLE servers ADD COLUMN sshKeyId TEXT DEFAULT NULL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_sessions (
                id TEXT NOT NULL PRIMARY KEY,
                serverId TEXT NOT NULL,
                agent TEXT NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                lastUsedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_sessions_serverId_agent ON chat_sessions(serverId, agent)")
    }
}

/**
 * Add the FIDO security-key columns to `ssh_keys`. All three are nullable
 * — software keys leave them empty, hardware keys (`SK_*` types) require
 * all three set when the row is upserted.
 *
 * `credentialIdBase64` is the opaque CTAP2 credential ID we pass back in
 * the allowList of every getAssertion call. `application` is the rpId
 * (`ssh:` by default — same as `ssh-keygen -t ed25519-sk`). `transport`
 * pins the row to USB / NFC / either — purely a UX hint, the connect
 * dialog still tries whichever the user has plugged in.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ssh_keys ADD COLUMN credentialIdBase64 TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE ssh_keys ADD COLUMN application TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE ssh_keys ADD COLUMN transport TEXT DEFAULT NULL")
    }
}

/**
 * Add multi-key support: a server can now reference more than one ssh_key row.
 * Original `sshKeyId` column stayed as the "primary" key (first tried at
 * connect), and `additionalKeyIdsCsv` was a CSV of the rest. Both later
 * collapsed into a single flat CSV in v7 (see [MIGRATION_6_7]).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE servers ADD COLUMN additionalKeyIdsCsv TEXT DEFAULT NULL")
    }
}

/**
 * Collapse `sshKeyId` + `additionalKeyIdsCsv` into a single
 * `sshKeyIdsCsv` column. There is no longer a concept of "primary" —
 * sshj walks every enrolled key when authenticating and the server picks.
 *
 * SQLite can't drop columns directly (Android API < 35 doesn't expose
 * `ALTER TABLE … DROP COLUMN`), so we go through the standard rebuild:
 * create a new table, copy data while concatenating the two old columns,
 * drop the old, rename the new.
 *
 * Concatenation rule:
 *   primary = sshKeyId (nullable)
 *   rest    = additionalKeyIdsCsv (nullable, comma-separated)
 *   merged  = listOfNotNull(primary) + rest.split(",")
 *             then distinct + non-blank + joinToString(",")
 *
 * Performed entirely in SQL with COALESCE/||/TRIM tricks to avoid
 * loading rows into Kotlin during migration. Empty/blank result becomes
 * NULL so the column matches the pre-migration sentinel.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE servers_new (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                username TEXT NOT NULL,
                authMethod TEXT NOT NULL,
                knownHostKey TEXT,
                agent TEXT NOT NULL DEFAULT 'CLAUDE',
                sshKeyIdsCsv TEXT DEFAULT NULL
            )
            """.trimIndent()
        )
        // Build the merged CSV in SQL: prefix primary, then strip leading
        // comma when the result starts with one (happens when primary is
        // null). NULLIF turns blanks into NULL so the column stays sparse.
        db.execSQL(
            """
            INSERT INTO servers_new (id, name, host, port, username, authMethod, knownHostKey, agent, sshKeyIdsCsv)
            SELECT id, name, host, port, username, authMethod, knownHostKey, agent,
                NULLIF(
                    CASE
                        WHEN sshKeyId IS NULL AND (additionalKeyIdsCsv IS NULL OR additionalKeyIdsCsv = '') THEN ''
                        WHEN sshKeyId IS NULL THEN additionalKeyIdsCsv
                        WHEN additionalKeyIdsCsv IS NULL OR additionalKeyIdsCsv = '' THEN sshKeyId
                        ELSE sshKeyId || ',' || additionalKeyIdsCsv
                    END,
                    ''
                )
            FROM servers
            """.trimIndent()
        )
        db.execSQL("DROP TABLE servers")
        db.execSQL("ALTER TABLE servers_new RENAME TO servers")
    }
}

/**
 * v8 — per-server accent colour (`#RRGGBB`). Purely additive and nullable: an
 * existing row keeps NULL and the UI derives a stable colour from its id, so no
 * backfill pass is needed and no server ever renders colourless.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE servers ADD COLUMN colorHex TEXT DEFAULT NULL")
    }
}

val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
)
