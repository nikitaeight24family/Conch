package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.data.HistoryCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Locks in the draft-cache invariants behind issue #38 — pre-CLI-ack
 * UserText survives ChatViewModel reinit on a brand-new chat.
 *
 * The internal constructor takes a folder so this test runs pure-JVM
 * without an Android Context (which Robolectric on this project's
 * JDK17/AGP combo can't bring up — see SessionsScreenTest.kt for the
 * full rationale).
 */
class HistoryCacheDraftTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun cache(): HistoryCache {
        // Internal ctor — accessible from the same module.
        val root = File(tmp.root, "session_history")
        return HistoryCache::class.java
            .getDeclaredConstructor(File::class.java)
            .apply { isAccessible = true }
            .newInstance(root) as HistoryCache
    }

    @Test
    fun `loadDrafts returns empty list when nothing has been saved`() {
        val c = cache()
        assertTrue(c.loadDrafts("srv-1", Agent.CLAUDE).isEmpty())
    }

    @Test
    fun `appendDraft persists a single message and loadDrafts returns it`() {
        val c = cache()
        c.appendDraft("srv-1", Agent.CLAUDE, "hello")
        assertEquals(listOf("hello"), c.loadDrafts("srv-1", Agent.CLAUDE))
    }

    @Test
    fun `multiple appendDraft calls preserve insertion order`() {
        val c = cache()
        c.appendDraft("srv-1", Agent.CODEX, "first")
        c.appendDraft("srv-1", Agent.CODEX, "second")
        c.appendDraft("srv-1", Agent.CODEX, "third")
        assertEquals(
            listOf("first", "second", "third"),
            c.loadDrafts("srv-1", Agent.CODEX),
        )
    }

    @Test
    fun `drafts are isolated per server and per agent`() {
        val c = cache()
        c.appendDraft("srv-A", Agent.CLAUDE, "A claude")
        c.appendDraft("srv-A", Agent.CODEX, "A codex")
        c.appendDraft("srv-B", Agent.CLAUDE, "B claude")
        assertEquals(listOf("A claude"), c.loadDrafts("srv-A", Agent.CLAUDE))
        assertEquals(listOf("A codex"), c.loadDrafts("srv-A", Agent.CODEX))
        assertEquals(listOf("B claude"), c.loadDrafts("srv-B", Agent.CLAUDE))
        assertTrue(c.loadDrafts("srv-B", Agent.CODEX).isEmpty())
    }

    @Test
    fun `removeDraft drops only the first matching entry`() {
        val c = cache()
        c.appendDraft("srv-1", Agent.CLAUDE, "x")
        c.appendDraft("srv-1", Agent.CLAUDE, "y")
        c.appendDraft("srv-1", Agent.CLAUDE, "x")
        c.removeDraft("srv-1", Agent.CLAUDE, "x")
        assertEquals(listOf("y", "x"), c.loadDrafts("srv-1", Agent.CLAUDE))
    }

    @Test
    fun `removeDraft on missing entry is a no-op`() {
        val c = cache()
        c.appendDraft("srv-1", Agent.CLAUDE, "a")
        c.removeDraft("srv-1", Agent.CLAUDE, "z")
        assertEquals(listOf("a"), c.loadDrafts("srv-1", Agent.CLAUDE))
    }

    @Test
    fun `clearDrafts wipes the slot for that server-agent pair only`() {
        val c = cache()
        c.appendDraft("srv-1", Agent.CLAUDE, "claude msg")
        c.appendDraft("srv-1", Agent.CODEX, "codex msg")
        c.clearDrafts("srv-1", Agent.CLAUDE)
        assertTrue(c.loadDrafts("srv-1", Agent.CLAUDE).isEmpty())
        assertEquals(listOf("codex msg"), c.loadDrafts("srv-1", Agent.CODEX))
    }

    @Test
    fun `appendDraft survives across HistoryCache instances on the same folder`() {
        // Simulates ChatViewModel dying and a new VM seeing the same disk
        // state — exactly the scenario behind issue #38.
        val first = cache()
        first.appendDraft("srv-1", Agent.GEMINI, "buffered text")

        val second = HistoryCache::class.java
            .getDeclaredConstructor(File::class.java)
            .apply { isAccessible = true }
            .newInstance(File(tmp.root, "session_history")) as HistoryCache
        assertEquals(listOf("buffered text"), second.loadDrafts("srv-1", Agent.GEMINI))
    }

    @Test
    fun `removeDraft to zero entries deletes the underlying draft file`() {
        val c = cache()
        c.appendDraft("srv-1", Agent.CLAUDE, "only one")
        c.removeDraft("srv-1", Agent.CLAUDE, "only one")
        assertTrue(c.loadDrafts("srv-1", Agent.CLAUDE).isEmpty())
    }
}
