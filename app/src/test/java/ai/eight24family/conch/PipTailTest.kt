package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.ui.window.pipTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the floating window shows.
 *
 * The window's job is "is it working and how far along" — and it was answering
 * with prose from an old turn, because it rendered the whole chat scrolled to the
 * reading anchor the user had in the full screen. Two rules pinned here: while a
 * turn runs, show THAT turn and nothing else; when nothing runs, whatever is
 * shown must be LABELLED as the last reply so it can never be mistaken for live
 * output.
 */
class PipTailTest {

    private fun user(id: String, t: String) = AgentMessage.UserText(id, t)
    private fun asst(id: String, t: String) = AgentMessage.AssistantText(id, t)
    private fun tool(id: String, name: String) = AgentMessage.ToolUse(id, name, "{}")

    private val history = listOf(
        user("u1", "old question"),
        asst("a1", "old answer"),
        user("u2", "new question"),
        tool("t1", "Grep"),
        asst("a2", "partial new answer"),
    )

    @Test
    fun `a running turn shows only that turn`() {
        val tail = pipTail(history, isWorking = true)
        assertTrue(
            "the previous turn must not appear: $tail",
            tail.none { it.text.contains("old") },
        )
        assertEquals(listOf("⚙ Grep", "partial new answer"), tail.map { it.text })
    }

    @Test
    fun `tool calls are collapsed to their name`() {
        val tail = pipTail(listOf(user("u", "go"), tool("t", "Bash")), isWorking = true)
        assertEquals("⚙ Bash", tail.single().text)
        assertTrue("a tool line is context, not the answer", tail.single().dim)
    }

    @Test
    fun `an idle chat labels what it shows as the last reply`() {
        val tail = pipTail(history, isWorking = false)
        assertEquals(2, tail.size)
        assertEquals("last reply", tail[0].text)
        assertTrue(tail[0].dim)
        assertEquals("partial new answer", tail[1].text)
    }

    @Test
    fun `an empty chat shows nothing rather than guessing`() {
        assertTrue(pipTail(emptyList(), isWorking = true).isEmpty())
        assertTrue(pipTail(emptyList(), isWorking = false).isEmpty())
    }

    @Test
    fun `a turn with no output yet has an empty tail`() {
        // The status line already says "Working…"; inventing a body would be a lie.
        assertTrue(pipTail(listOf(user("u", "go")), isWorking = true).isEmpty())
    }

    @Test
    fun `a long turn is capped to what the window can show`() {
        val long = listOf(user("u", "go")) + (1..80).map { asst("a$it", "line $it") }
        val tail = pipTail(long, isWorking = true)
        assertTrue("cap the tail: ${tail.size}", tail.size <= 12)
        // …and it must be the NEWEST lines that survive.
        assertEquals("line 80", tail.last().text)
    }

    @Test
    fun `an error is shown rather than swallowed`() {
        val tail = pipTail(
            listOf(user("u", "go"), AgentMessage.Error("e1", "disconnected")),
            isWorking = true,
        )
        assertEquals("! disconnected", tail.single().text)
    }
}
