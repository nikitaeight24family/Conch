package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeChainFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `last-prompt` is ROUTINE — it is not evidence of a rewind.
 *
 * The chain filter took the last `last-prompt` marker for the live tip of the
 * conversation. It isn't: the CLI writes one naming the current tip after
 * every single turn (14 in one ordinary session on the user's box, 2026-08-03).
 * The moment a new turn lands, the marker is history — but the filter still
 * walked from it, so the freshly sent user record was a child of the marker's
 * leaf, off the walked path, and got hidden as an "abandoned branch". The
 * user's own message disappeared from a chat that was working fine.
 *
 * Records below are the real tail of session 2980a82e, uuids shortened.
 */
class ClaudeChainRoutineMarkerTest {

    private fun user(uuid: String, parent: String, text: String) =
        """{"type":"user","uuid":"$uuid","parentUuid":"$parent","message":{"role":"user","content":"$text"}}"""

    private fun asst(uuid: String, parent: String, text: String = "ok") =
        """{"type":"assistant","uuid":"$uuid","parentUuid":"$parent","message":{"role":"assistant","content":"$text"}}"""

    private fun marker(leaf: String) = """{"type":"last-prompt","leafUuid":"$leaf"}"""

    /** The transcript as it stood when the prompt vanished. */
    private val realTail = listOf(
        user("d009a2f3", "829668f0", "loop tick"),
        asst("1e7aca12", "d009a2f3", "Next tick at 19:28."),
        marker("1e7aca12"),                       // routine: names the tip
        """{"type":"queue-operation"}""",
        user("391bdb26", "1e7aca12", "count slowly from 1 to 40"),
        """{"type":"file-history-snapshot"}""",
        asst("7401c885", "391bdb26"),
        asst("36a7e2e0", "7401c885", "1 2 3 4"),
    )

    @Test
    fun `a prompt sent after a routine marker stays in the transcript`() {
        val off = ClaudeChainFilter.offChainUuids(realTail.asSequence())
        assertFalse("the user's own message must never be hidden", "391bdb26" in off)
        assertFalse("nor the reply to it", "36a7e2e0" in off)
        assertEquals("nothing here was abandoned", emptySet<String>(), off)
    }

    @Test
    fun `a real rewind with nothing sent yet still hides the discarded turn`() {
        // Marker names an EARLIER record and nothing follows it: the turn the
        // user rewound away from is still physically the tail of the file.
        val lines = listOf(
            user("u1", "", "first"),
            asst("a1", "u1"),
            user("u2", "a1", "discarded"),
            asst("a2", "u2", "discarded reply"),
            marker("a1"),
        )
        val off = ClaudeChainFilter.offChainUuids(lines.asSequence())
        assertTrue("the rewound-away turn goes", "u2" in off && "a2" in off)
        assertFalse("what it rewound TO stays", "u1" in off || "a1" in off)
    }

    @Test
    fun `after a rewind the replacement turn stays and only the abandoned one goes`() {
        val lines = listOf(
            user("u1", "", "first"),
            asst("a1", "u1"),
            user("u2", "a1", "discarded"),
            asst("a2", "u2", "discarded reply"),
            marker("a1"),
            user("u3", "a1", "replacement"),   // same parent → a genuine fork
            asst("a3", "u3", "new reply"),
        )
        val off = ClaudeChainFilter.offChainUuids(lines.asSequence())
        assertTrue("the abandoned branch goes", "u2" in off && "a2" in off)
        assertFalse("the replacement must survive", "u3" in off || "a3" in off)
    }

    @Test
    fun `an ordinary transcript with markers after every turn loses nothing`() {
        val lines = buildList {
            add(user("u1", "", "one"))
            add(asst("a1", "u1"))
            add(marker("a1"))
            add(user("u2", "a1", "two"))
            add(asst("a2", "u2"))
            add(marker("a2"))
            add(user("u3", "a2", "three"))
            add(asst("a3", "u3"))
            add(marker("a3"))
        }
        assertEquals(emptySet<String>(), ClaudeChainFilter.offChainUuids(lines.asSequence()))
    }
}
