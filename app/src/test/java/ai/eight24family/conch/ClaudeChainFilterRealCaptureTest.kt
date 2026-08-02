package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeChainFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chain filter against a transcript a REAL CLI produced (2.1.202,
 * captured 2026-08-02): ask A, ask B, rewind to B, ask C.
 *
 * Verbatim uuids and record order from that capture — including the details a
 * hand-written mock would have missed: `attachment` records carry uuids and
 * sit ON the chain between a user turn and the assistant reply, and the
 * rewind's `last-prompt` back-pointer names the assistant BEFORE the
 * discarded pair.
 */
class ClaudeChainFilterRealCaptureTest {

    private val attach1 = "20ec1b7d-7a88-4cff-a56a-5c7c158773e4"
    private val userA = "c649cac8-1ab5-4a4a-86ea-02f233825b06"
    private val attach2 = "fe729fc5-ecf6-4fd6-a334-2640c20baa8e"
    private val attach3 = "6991f026-2d35-4da2-a979-a6739a369631"
    private val attach4 = "dc5948c1-1f6c-4581-be8c-8ca070f04635"
    private val asstA = "232bae81-14a2-4947-9500-4533d547389c"
    private val userB = "7824b86c-a615-4dd3-881a-b86bef2407a8"   // discarded
    private val asstB = "8887c704-dd87-416d-9e12-f523a90bec64"   // discarded
    private val userC = "cd3ad794-4993-4cbd-a063-bfad7df4d7aa"
    private val asstC = "c0a95bc4-b6ab-452e-a98e-8d64d87e9f76"

    private fun r(type: String, uuid: String, parent: String?) =
        """{"type":"$type","uuid":"$uuid","parentUuid":${parent?.let { "\"$it\"" } ?: "null"},"sessionId":"s"}"""

    private val real = listOf(
        """{"type":"queue-operation","op":"x"}""",
        """{"type":"queue-operation","op":"x"}""",
        r("attachment", attach1, null),
        r("user", userA, attach1),
        r("attachment", attach2, userA),
        r("attachment", attach3, attach2),
        r("attachment", attach4, attach3),
        r("assistant", asstA, attach4),
        """{"type":"queue-operation","op":"x"}""",
        """{"type":"queue-operation","op":"x"}""",
        r("user", userB, asstA),
        r("assistant", asstB, userB),
        """{"type":"last-prompt","leafUuid":"$asstA"}""",   // ← the rewind
        """{"type":"queue-operation","op":"x"}""",
        """{"type":"queue-operation","op":"x"}""",
        r("user", userC, asstA),                             // new branch, same parent
        r("assistant", asstC, userC),
        """{"type":"last-prompt","leafUuid":"$asstC"}""",
    )

    @Test
    fun `the real capture is detected as rewound`() {
        assertTrue(ClaudeChainFilter.hasRewind(real.asSequence()))
    }

    @Test
    fun `exactly the discarded pair is vetoed — attachments stay on the chain`() {
        val off = ClaudeChainFilter.offChainUuids(real.asSequence())
        assertEquals(setOf(userB, asstB), off)
        // The attachment records the CLI threads between the prompt and the
        // reply must survive: dropping them would strip images/pasted files
        // out of a perfectly good turn.
        listOf(attach1, attach2, attach3, attach4, userA, asstA, userC, asstC).forEach {
            assertFalse("$it must stay on the chain", it in off)
        }
    }

    @Test
    fun `rendering the real capture keeps A and C, drops B`() {
        val off = ClaudeChainFilter.offChainUuids(real.asSequence())
        val kept = real.filterNot { ClaudeChainFilter.isOffChain(it, off) }
        assertEquals(real.size - 2, kept.size)
        assertTrue(kept.none { it.contains(userB) && it.contains("\"type\":\"user\"") })
        assertTrue(kept.any { it.contains(userC) })
        // Every uuid-less record survives: all SIX queue-operations the real
        // capture carries (a pair before each of A, B and C — including the
        // pair belonging to the DISCARDED turn, which is correct: they hold no
        // uuid, so the filter may not reason about them) and both last-prompts.
        assertEquals(6, kept.count { it.contains("queue-operation") })
        assertEquals(2, kept.count { it.contains("last-prompt") })
    }
}
