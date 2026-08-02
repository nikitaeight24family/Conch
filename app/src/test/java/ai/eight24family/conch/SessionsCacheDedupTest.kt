package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.RemoteSession
import ai.eight24family.conch.data.SessionsCache
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the fix for the shipped v0.2.4 crash:
 *
 *   java.lang.IllegalArgumentException: Key "<server>/CLAUDE/<sid>" was already
 *   used. If you are using LazyColumn/Row please make sure you provide a unique
 *   key for each item.
 *
 * One logical Claude session spans several rollout files after a resume /
 * compaction, so a raw listing repeats the same session id. Both the unified
 * home list and the per-agent list key their LazyColumn by session id, and
 * Compose HARD-CRASHES on a duplicate key. [SessionsCache.dedupeById] is the
 * shared collapse used by both cache read and write; it must keep the FIRST
 * (newest, since listings are newest-first) occurrence and drop the rest.
 */
class SessionsCacheDedupTest {

    private fun s(id: String, path: String, lastActive: Long) = RemoteSession(
        id = id, path = path, agent = Agent.CLAUDE, lastActiveAt = lastActive, preview = "p",
    )

    @Test
    fun `duplicate id collapses to one row`() {
        val dup = "33002c04-f48c-4de9-94db-3cc64b9d4497"
        val input = listOf(
            s(dup, "~/.claude/projects/a/$dup.jsonl", 200L),   // newest file, listed first
            s("other", "~/.claude/projects/a/other.jsonl", 150L),
            s(dup, "~/.claude/projects/a/$dup-old.jsonl", 100L), // older rollout, same id
        )
        val out = SessionsCache.dedupeById(input)
        assertEquals(2, out.size)
        // exactly one row per id → keys are unique, LazyColumn can't crash
        assertEquals(out.size, out.map { it.id }.toSet().size)
    }

    @Test
    fun `keeps the first (most recent) occurrence`() {
        val dup = "abc"
        val out = SessionsCache.dedupeById(
            listOf(
                s(dup, "newest.jsonl", 999L),
                s(dup, "oldest.jsonl", 1L),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("newest.jsonl", out.first().path)
        assertEquals(999L, out.first().lastActiveAt)
    }

    @Test
    fun `distinct ids are preserved in order`() {
        val out = SessionsCache.dedupeById(
            listOf(s("a", "a", 3L), s("b", "b", 2L), s("c", "c", 1L)),
        )
        assertEquals(listOf("a", "b", "c"), out.map { it.id })
    }

    @Test
    fun `empty stays empty`() {
        assertEquals(emptyList<RemoteSession>(), SessionsCache.dedupeById(emptyList()))
    }
}
