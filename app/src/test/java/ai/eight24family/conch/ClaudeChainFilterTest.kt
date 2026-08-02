package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeChainFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rewind FORKS the transcript instead of truncating it — captured from a
 * live CLI (2026-08-02). Read linearly the chat would show the discarded
 * turn next to its replacement, i.e. the rewind would look like it silently
 * did nothing. These pin the active-chain reconstruction.
 */
class ClaudeChainFilterTest {

    // Shapes copied from the real capture: A → (B discarded) → C.
    private fun rec(type: String, uuid: String, parent: String?, text: String) =
        """{"type":"$type","uuid":"$uuid","parentUuid":${parent?.let { "\"$it\"" } ?: "null"},""" +
            """"message":{"role":"${if (type == "user") "user" else "assistant"}",""" +
            """"content":"$text"},"sessionId":"s1"}"""

    private fun lastPrompt(leaf: String) = """{"type":"last-prompt","leafUuid":"$leaf"}"""

    private val rewound = listOf(
        """{"type":"queue-operation","op":"enqueue"}""",
        rec("user", "u-A", null, "A"),
        rec("assistant", "a-A", "u-A", "A!"),
        rec("user", "u-B", "a-A", "B"),          // discarded by the rewind
        rec("assistant", "a-B", "u-B", "B!"),    // discarded by the rewind
        lastPrompt("a-A"),                        // ← the rewind's back-pointer
        rec("user", "u-C", "a-A", "C"),          // new branch off the same parent
        rec("assistant", "a-C", "u-C", "C!"),
        lastPrompt("a-C"),
    )

    private val neverRewound = listOf(
        rec("user", "u-A", null, "A"),
        rec("assistant", "a-A", "u-A", "A!"),
        lastPrompt("a-A"),
        rec("user", "u-B", "a-A", "B"),
        rec("assistant", "a-B", "u-B", "B!"),
        lastPrompt("a-B"),
    )

    @Test
    fun `a rewound transcript is detected, a normal one is not`() {
        assertTrue(ClaudeChainFilter.hasRewind(rewound.asSequence()))
        assertFalse(ClaudeChainFilter.hasRewind(neverRewound.asSequence()))
    }

    @Test
    fun `the abandoned branch is vetoed and the new one survives`() {
        val off = ClaudeChainFilter.offChainUuids(rewound.asSequence())
        assertEquals(setOf("u-B", "a-B"), off)
        assertTrue(ClaudeChainFilter.isOffChain(rewound[3], off))
        assertTrue(ClaudeChainFilter.isOffChain(rewound[4], off))
        assertFalse(ClaudeChainFilter.isOffChain(rewound[6], off))
        assertFalse(ClaudeChainFilter.isOffChain(rewound[1], off))
        // Records with no uuid (queue-operation, last-prompt) are NEVER dropped.
        assertFalse(ClaudeChainFilter.isOffChain(rewound[0], off))
        assertFalse(ClaudeChainFilter.isOffChain(rewound[5], off))
    }

    @Test
    fun `a never-rewound transcript vetoes nothing`() {
        assertEquals(emptySet<String>(), ClaudeChainFilter.offChainUuids(neverRewound.asSequence()))
    }

    @Test
    fun `rewound-but-nothing-sent-yet vetoes the discarded tail`() {
        // No fork exists yet (the replacement turn hasn't been sent), so the
        // ONLY signal is the CLI's own leafUuid pointing at an earlier record.
        // Without this the mirror appends the discarded turn straight back
        // onto the chat we just truncated — measured on device.
        val pending = rewound.take(6)
        assertEquals(setOf("u-B", "a-B"), ClaudeChainFilter.offChainUuids(pending.asSequence()))
    }

    @Test
    fun `only the leaf's DESCENDANTS go — not everything off its path`() {
        // The distinction that matters: a record hanging off an EARLIER part
        // of the chain is not the discarded turn, and vetoing it is how a
        // normal chat loses rows.
        val sideBranch = listOf(
            rec("user", "u-A", null, "A"),
            rec("assistant", "a-A", "u-A", "A!"),
            rec("system", "side-1", "u-A", "side note"),
            rec("user", "u-B", "a-A", "B"),
            rec("assistant", "a-B", "u-B", "B!"),
            lastPrompt("a-A"),
        )
        val off = ClaudeChainFilter.offChainUuids(sideBranch.asSequence())
        assertTrue("the discarded turn goes", "u-B" in off && "a-B" in off)
        assertTrue("a side record off an earlier node stays", "side-1" !in off)
    }

    @Test
    fun `an ordinary session vetoes NOTHING`() {
        // The safety direction that matters most: a chat nobody rewound must
        // come back byte-for-byte. An over-broad version of the leaf rule once
        // deleted the user's own message from a normal chat.
        assertEquals(emptySet<String>(), ClaudeChainFilter.offChainUuids(neverRewound.asSequence()))
        val withTrailingRecord = neverRewound + listOf(
            rec("system", "hook-1", "a-B", "SessionStart"),
        )
        assertEquals(
            emptySet<String>(),
            ClaudeChainFilter.offChainUuids(withTrailingRecord.asSequence()),
        )
    }

    @Test
    fun `a record hanging off the chain without a sibling is never vetoed`() {
        // The exact shape that broke a normal chat: a hook record parented on
        // the assistant, then the next turn parented on the SAME assistant.
        // Only the abandoned branch may be vetoed — never the hook.
        val withHook = listOf(
            rec("user", "u-A", null, "A"),
            rec("assistant", "a-A", "u-A", "A!"),
            rec("system", "hook-1", "a-A", "SessionStart"),
            lastPrompt("a-A"),
        )
        assertEquals(emptySet<String>(), ClaudeChainFilter.offChainUuids(withHook.asSequence()))
    }

    @Test
    fun `two rewinds off the same parent leave only the newest branch`() {
        val twice = rewound + listOf(
            lastPrompt("a-A"),
            rec("user", "u-D", "a-A", "D"),
            rec("assistant", "a-D", "u-D", "D!"),
            lastPrompt("a-D"),
        )
        assertEquals(
            setOf("u-B", "a-B", "u-C", "a-C"),
            ClaudeChainFilter.offChainUuids(twice.asSequence()),
        )
    }

    @Test
    fun `a tail window whose parents are outside it keeps everything reachable`() {
        // The tail-poll reads from a byte offset, so the earliest records'
        // parents are simply absent. Walking back must stop at the missing
        // parent and keep the whole path, never blank the window.
        val tail = neverRewound.drop(3)
        assertEquals(emptySet<String>(), ClaudeChainFilter.offChainUuids(tail.asSequence()))
    }

    @Test
    fun `garbage and cycles terminate without dropping everything`() {
        val cyclic = listOf(
            rec("user", "x", "y", "x"),
            rec("assistant", "y", "x", "y"),
            lastPrompt("y"),
        )
        // A cycle must not hang; whatever it returns, it must not veto the leaf.
        val off = ClaudeChainFilter.offChainUuids(cyclic.asSequence())
        assertFalse("y" in off)
        assertEquals(emptySet<String>(), ClaudeChainFilter.offChainUuids(emptySequence()))
        assertFalse(ClaudeChainFilter.hasRewind(listOf("not json", "").asSequence()))
    }

    @Test
    fun `incremental scanners match the sequence helpers`() {
        val d = ClaudeChainFilter.RewindDetector()
        val r = ClaudeChainFilter.ChainResolver()
        rewound.forEach { d.feed(it); r.feed(it) }
        assertTrue(d.found)
        assertEquals(ClaudeChainFilter.offChainUuids(rewound.asSequence()), r.result())
    }
}
