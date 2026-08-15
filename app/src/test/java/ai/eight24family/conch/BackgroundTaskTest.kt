package ai.eight24family.conch

import ai.eight24family.conch.agent.SlashCommandKind
import ai.eight24family.conch.agent.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/bg <task>` — the honest form of "background sessions".
 *
 * `/background` and `/fork` are `type:"local-jsx"` in the CLI binary: screens of
 * the terminal UI, absent from the 45 commands the headless handshake reports.
 * What IS real is `claude --bg`, verified on the user's server: it answers
 * `backgrounded · 941a5f38` and returns immediately, the job outlives the
 * process that spawned it, and it writes an ordinary session file — so the work
 * shows up in the sessions list by itself and `claude stop <id>` ends it.
 *
 * On a phone that is the whole point: the app can close, the network can drop,
 * the work carries on.
 */
class BackgroundTaskTest {

    private val backgrounded = Regex("""backgrounded\s*·\s*([0-9a-f]{6,})""")

    @Test
    fun `the palette offers it and it takes a task`() {
        val cmd = SlashCommands.find("bg", emptyList())
        assertNotNull(cmd)
        assertEquals(SlashCommandKind.RUN_BACKGROUND, cmd!!.kind)
        assertTrue("a background task without a task is nothing", cmd.acceptsArgs)
    }

    @Test
    fun `the CLI's confirmation line yields the id everything else is keyed by`() {
        // Verbatim from the live run (2026-08-03).
        val out = """backgrounded · 941a5f38
  claude agents             list sessions
  claude attach 941a5f38    open in this terminal
  claude logs 941a5f38      show recent output
  claude stop 941a5f38      stop this session"""
        assertEquals("941a5f38", backgrounded.find(out)?.groupValues?.get(1))
    }

    @Test
    fun `a failure is not read as a launch`() {
        assertNull(backgrounded.find("bash: claude: command not found"))
        assertNull(backgrounded.find(""))
    }

    @Test
    fun `parsing keeps the whole task, spaces and all`() {
        val (name, args) = SlashCommands.parse("/bg run the test suite and fix what breaks")!!
        assertEquals("bg", name)
        assertEquals("run the test suite and fix what breaks", args)
    }
}
