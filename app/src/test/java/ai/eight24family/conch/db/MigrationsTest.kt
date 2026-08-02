package ai.eight24family.conch.db

import ai.eight24family.conch.data.db.MIGRATION_1_2
import ai.eight24family.conch.data.db.MIGRATION_2_3
import ai.eight24family.conch.data.db.MIGRATION_3_4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Tests every Room migration by directly invoking `Migration.migrate(...)`
 * against a real SQLite db, then asserting the resulting schema/data with
 * raw SQL.
 *
 * Why JDBC + Xerial sqlite-jdbc instead of Room's `MigrationTestHelper`?
 *   - `MigrationTestHelper` needs per-version schema JSON files that Room
 *     dumps when `exportSchema = true`; this project never set that flag,
 *     so the historical schemas don't exist anywhere.
 *   - Robolectric's bootstrap (needed for any `Context`-backed sqlite open)
 *     blows up on JDK 17 + recent Android framework jars with
 *     `NoSuchFieldError: noncompatWidthPixels`. Avoiding it means tests
 *     run in ~50 ms/each instead of ~3 s/each, and don't carry the
 *     Robolectric setup/teardown weight.
 *
 * The migrations themselves only ever touch `SupportSQLiteDatabase`. We
 * wrap a JDBC `Connection` in a thin adapter implementing the methods
 * the migrations actually call (`execSQL`, `query`). That's enough to
 * exercise the production code path.
 */
class MigrationsTest {

    private var connection: Connection? = null

    @After
    fun tearDown() {
        connection?.close()
        connection = null
    }

    /**
     * Open a fresh in-memory SQLite db with v1's schema (servers table
     * only, no agent / sshKeyId columns; no ssh_keys / chat_sessions
     * tables). Reverse-engineered from the entity definition that
     * existed prior to MIGRATION_1_2 — we can't `exportSchema` something
     * that was never built with that flag.
     */
    private fun openV1(): Connection {
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection = c
        c.createStatement().use { st ->
            st.execute("""
                CREATE TABLE servers (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    username TEXT NOT NULL,
                    authMethod TEXT NOT NULL,
                    knownHostKey TEXT
                )
            """.trimIndent())
        }
        return c
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.queryFirst(sql: String): Map<String, Any?>? {
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                if (!rs.next()) return null
                val md = rs.metaData
                val out = LinkedHashMap<String, Any?>(md.columnCount)
                for (i in 1..md.columnCount) {
                    out[md.getColumnLabel(i)] = rs.getObject(i)
                }
                return out
            }
        }
    }

    private fun Connection.tableExists(name: String): Boolean =
        queryFirst("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'") != null

    private fun Connection.indexExists(name: String): Boolean =
        queryFirst("SELECT name FROM sqlite_master WHERE type='index' AND name='$name'") != null

    @Test
    fun `migration 1 to 2 adds agent column with CLAUDE default`() {
        val c = openV1()
        c.exec("""
            INSERT INTO servers (id,name,host,port,username,authMethod,knownHostKey)
            VALUES ('s1','one','example.com',22,'root','PASSWORD','SHA256:abc')
        """.trimIndent())

        MIGRATION_1_2.migrate(JdbcSupportDb(c))

        val row = c.queryFirst("SELECT id, agent FROM servers WHERE id='s1'")
        assertNotNull(row)
        assertEquals("s1", row!!["id"])
        assertEquals("CLAUDE", row["agent"])

        // Default kicks in for inserts that omit the column.
        c.exec("""
            INSERT INTO servers (id,name,host,port,username,authMethod,knownHostKey)
            VALUES ('s2','two','example.org',2222,'admin','KEY',NULL)
        """.trimIndent())
        val row2 = c.queryFirst("SELECT agent FROM servers WHERE id='s2'")
        assertEquals("CLAUDE", row2!!["agent"])
    }

    @Test
    fun `migration 2 to 3 creates ssh_keys table and adds sshKeyId to servers`() {
        val c = openV1()
        MIGRATION_1_2.migrate(JdbcSupportDb(c))
        c.exec("""
            INSERT INTO servers (id,name,host,port,username,authMethod,knownHostKey,agent)
            VALUES ('s1','one','example.com',22,'root','KEY','SHA256:abc','CLAUDE')
        """.trimIndent())

        MIGRATION_2_3.migrate(JdbcSupportDb(c))

        assertTrue("ssh_keys table not created", c.tableExists("ssh_keys"))

        // ssh_keys must accept the documented columns.
        c.exec("""
            INSERT INTO ssh_keys (id,name,type,publicKey,fingerprint,comment,createdAt)
            VALUES ('k1','my-key','RSA','ssh-rsa AAAA','SHA256:xyz','laptop',1700000000000)
        """.trimIndent())

        c.exec("UPDATE servers SET sshKeyId='k1' WHERE id='s1'")
        val row = c.queryFirst("SELECT sshKeyId FROM servers WHERE id='s1'")
        assertEquals("k1", row!!["sshKeyId"])
    }

    @Test
    fun `migration 3 to 4 creates chat_sessions table and serverId-agent index`() {
        val c = openV1()
        MIGRATION_1_2.migrate(JdbcSupportDb(c))
        MIGRATION_2_3.migrate(JdbcSupportDb(c))
        MIGRATION_3_4.migrate(JdbcSupportDb(c))

        assertTrue("chat_sessions table not created", c.tableExists("chat_sessions"))
        assertTrue(
            "composite (serverId,agent) index missing",
            c.indexExists("index_chat_sessions_serverId_agent")
        )

        c.exec("""
            INSERT INTO chat_sessions (id,serverId,agent,name,createdAt,lastUsedAt)
            VALUES ('cs1','s1','CLAUDE','first',1700000000000,1700000000000)
        """.trimIndent())
        val row = c.queryFirst("SELECT name, agent FROM chat_sessions WHERE id='cs1'")
        assertEquals("first", row!!["name"])
        assertEquals("CLAUDE", row["agent"])
    }

    @Test
    fun `chained migration 1 to 4 preserves original v1 row data`() {
        val c = openV1()
        c.exec("""
            INSERT INTO servers (id,name,host,port,username,authMethod,knownHostKey)
            VALUES ('legacy','from-v1','old.example.com',22,'me','PASSWORD','SHA256:legacy')
        """.trimIndent())

        val db = JdbcSupportDb(c)
        MIGRATION_1_2.migrate(db)
        MIGRATION_2_3.migrate(db)
        MIGRATION_3_4.migrate(db)

        val row = c.queryFirst("""
            SELECT name, host, port, username, authMethod, knownHostKey, agent, sshKeyId
            FROM servers WHERE id='legacy'
        """.trimIndent())
        assertNotNull("legacy row missing after chained migration", row)
        assertEquals("from-v1", row!!["name"])
        assertEquals("old.example.com", row["host"])
        assertEquals(22, (row["port"] as Number).toInt())
        assertEquals("me", row["username"])
        assertEquals("PASSWORD", row["authMethod"])
        assertEquals("SHA256:legacy", row["knownHostKey"])
        assertEquals("CLAUDE", row["agent"])
        assertEquals(null, row["sshKeyId"])
    }
}
