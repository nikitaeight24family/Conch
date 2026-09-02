package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.mergeUnsyncedUserText
import ai.eight24family.conch.agent.userBodyKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The duplicate prompt stuck at the BOTTOM of a chat, under the reply it had
 * already received, appearing after leaving and re-entering a session — the bug
 * the user reported for a month (2026-07 → 2026-08-06).
 *
 * Two independent faults had to line up, which is why it was intermittent and why
 * fixing either one alone kept missing
 *
 *  1. identity was EXACT string equality, and the CLI does not store a prompt
 *     verbatim — Codex writes its own `<image name=… path=…>` block beside it
 *     and re-renders history with a `❯ ` prefix and hard wrapping;
 *  2. anything that failed to match was appended to the END of the list, so a
 *     prompt from several turns ago landed below the newest reply.
 *
 * Both are pinned here. If either regresses these tests fail.
 */
class PhantomPromptAtBottomTest {

    private fun user(id: String, text: String) = AgentMessage.UserText(id, text)
    private fun assistant(id: String, text: String) = AgentMessage.AssistantText(id, text)

    // ── fault 1: identity survives the CLI's own decoration ──

    @Test
    fun `codex attachment marker does not change a prompt's identity`() {
        val sent = "so it works now, right?\n\nAttached image(s) at:\n- /tmp/conch_uploads/1785983916135_x.jpg\n"
        // Verbatim shape of the echo from the user's own rollout: the CLI adds a
        // second content block that our parser joins onto the prompt.
        val echo = sent + "\n" + "<image name=[Image #1] path=\"/tmp/conch_uploads/1785983916135_x.jpg\">"
        assertEquals(userBodyKey(sent), userBodyKey(echo))
    }

    @Test
    fun `terminal prompt glyph and hard wrapping do not change identity`() {
        val sent = "should the build script live in the repo root, or is a separate tools directory better here?"
        val echo = "❯ should the build script live in the repo root, or is a separate tools directory better\n  here?"
        assertEquals(userBodyKey(sent), userBodyKey(echo))
    }

    @Test
    fun `different prompts keep different identities`() {
        assertTrue(userBodyKey("fix this") != userBodyKey("fix that"))
        // Normalisation must not collapse a prompt into a prefix of another.
        assertTrue(userBodyKey("yes") != userBodyKey("yes, exactly"))
    }

    // ── fault 2: a preserved prompt goes back to its own place ──

    @Test
    fun `an older prompt is never re-inserted below a newer reply`() {
        // Display carries the optimistic copy; the file's copy of the same
        // prompt is decorated, so under the OLD exact-match rule it counted as a
        // different message and was appended after the reply.
        val current = listOf(
            user("u1", "first question"),
            assistant("a1", "first answer"),
            user("u2", "second question"),
            assistant("a2", "second answer"),
        )
        val incoming = listOf(
            user("f1", "❯ first question"),
            assistant("a1", "first answer"),
            user("f2", "second question"),
            assistant("a2", "second answer"),
        )
        val merged = mergeUnsyncedUserText(current, incoming)
        assertEquals("nothing may be added — every prompt is covered", incoming, merged)
        assertTrue("the chat must still end on the reply", merged.last() is AgentMessage.AssistantText)
    }

    @Test
    fun `an un-echoed prompt from mid-conversation stays mid-conversation`() {
        // The file genuinely lacks the middle prompt (it was never written).
        // It must be kept — the user's words are sacred — but IN PLACE.
        val current = listOf(
            user("u1", "first question"),
            assistant("a1", "first answer"),
            user("u2", "middle question"),
            assistant("a2", "second answer"),
        )
        val incoming = listOf(
            user("u1", "first question"),
            assistant("a1", "first answer"),
            assistant("a2", "second answer"),
        )
        val merged = mergeUnsyncedUserText(current, incoming)
        assertEquals(
            listOf("u1", "a1", "u2", "a2"),
            merged.map { it.id },
        )
        assertTrue("the chat must still end on the reply", merged.last() is AgentMessage.AssistantText)
    }

    @Test
    fun `a just-sent prompt the file has not caught up to still lands last`() {
        val current = listOf(
            user("u1", "first question"),
            assistant("a1", "first answer"),
            user("u2", "just sent this"),
        )
        val incoming = listOf(
            user("u1", "first question"),
            assistant("a1", "first answer"),
        )
        val merged = mergeUnsyncedUserText(current, incoming)
        assertEquals(listOf("u1", "a1", "u2"), merged.map { it.id })
    }

    @Test
    fun `the merge is idempotent — its own output cannot grow a duplicate`() {
        // The display feeds the result back in as `current` on the next
        // emission. A rule that is not a fixed point accumulates a phantom on
        // every tick, which is what made the row impossible to get rid of.
        val current = listOf(
            user("u1", "question"),
            assistant("a1", "answer"),
            user("u2", "just sent this"),
        )
        val incoming = listOf(user("u1", "question"), assistant("a1", "answer"))
        val once = mergeUnsyncedUserText(current, incoming)
        val twice = mergeUnsyncedUserText(once, incoming)
        assertEquals(once, twice)
        assertEquals(1, twice.count { it is AgentMessage.UserText && it.text == "just sent this" })
    }

    @Test
    fun `a rewound prompt is not resurrected`() {
        val current = listOf(user("u1", "question"), user("u2", "cancelled"))
        val incoming = listOf(user("u1", "question"))
        val merged = mergeUnsyncedUserText(current, incoming) { it.trim() == "cancelled" }
        assertEquals(incoming, merged)
    }
}

/**
 * The half-read answer that Stop deleted (2026-08-06).
 *
 * Stop killed the app-server, the session rebuilt, and the Codex thread was
 * minutes old — its cache still `bytes=0`.
 *
 * The rule preserved `UserText` and nothing else. That was never a decision.
 */
class UnsyncedAssistantTextTest {

    private fun user(id: String, text: String) = AgentMessage.UserText(id, text)
    private fun assistant(id: String, text: String) = AgentMessage.AssistantText(id, text)

    @Test
    fun `a reply already on screen survives a rebuild the cache knows nothing about`() {
        val current = listOf(user("u1", "turn it off over adb"), assistant("a1", "Got it. Turning it off over ADB…"))
        val merged = mergeUnsyncedUserText(current, emptyList())
        assertEquals(listOf("u1", "a1"), merged.map { it.id })
    }

    @Test
    fun `the file still wins where the file has an opinion`() {
        // Wholesale replacement of agent content — nothing shared, the file has
        // its own text. Pinned by loadHistory_replaces_agent_content_normally.
        val current = listOf(assistant("a-old", "stale"))
        val incoming = listOf(assistant("a-new", "fresh"))
        assertEquals(incoming, mergeUnsyncedUserText(current, incoming))
    }

    @Test
    fun `a partial reply is superseded by its own completion, never doubled`() {
        val current = listOf(user("u1", "question"), assistant("a-live", "Got it. Turning it off"))
        val incoming = listOf(user("u1", "question"), assistant("a-file", "Got it. Turning it off — the Google package, over ADB."))
        val merged = mergeUnsyncedUserText(current, incoming)
        assertEquals(incoming, merged)
        assertEquals(1, merged.count { it is AgentMessage.AssistantText })
    }

    @Test
    fun `live output past the file's frontier is kept, in place`() {
        // The mirror has the first exchange; the second reply is still only live.
        val current = listOf(
            user("u1", "first"), assistant("a1", "first answer"),
            user("u2", "second"), assistant("a2", "second answer, not in the file yet"),
        )
        val incoming = listOf(user("u1", "first"), assistant("a1", "first answer"))
        val merged = mergeUnsyncedUserText(current, incoming)
        assertEquals(listOf("u1", "a1", "u2", "a2"), merged.map { it.id })
    }

    @Test
    fun `the merge stays a fixed point with agent text in play`() {
        val current = listOf(user("u1", "question"), assistant("a1", "answer"))
        val once = mergeUnsyncedUserText(current, emptyList())
        val twice = mergeUnsyncedUserText(once, emptyList())
        assertEquals(once, twice)
    }
}
