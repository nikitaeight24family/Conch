package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentStatusProbe
import ai.eight24family.conch.agent.RemoteEnv
import ai.eight24family.conch.agent.ServerOs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Windows OpenSSH honest detection (Workstream D, 2026-08-17): the OS
 * pre-probe's classifier is the entire decision — a wrong WINDOWS verdict
 * hides real agents on a unix box, a missed one leaves the misleading
 * "not installed" the workstream exists to kill.
 */
class ServerOsProbeTest {

    @Test
    fun `unix uname outputs classify as unix`() {
        assertEquals(ServerOs.UNIX, AgentStatusProbe.classifyOsProbe("Linux\n"))
        assertEquals(ServerOs.UNIX, AgentStatusProbe.classifyOsProbe("Darwin"))
        assertEquals(ServerOs.UNIX, AgentStatusProbe.classifyOsProbe("FreeBSD\n"))
    }

    @Test
    fun `cmd exe shapes classify as windows`() {
        // cmd's || fired after uname/redirect failed → the sentinel echoes.
        assertEquals(ServerOs.WINDOWS, AgentStatusProbe.classifyOsProbe("CONCH_NO_UNAME\r\n"))
        assertEquals(
            ServerOs.WINDOWS,
            AgentStatusProbe.classifyOsProbe(
                "'uname' is not recognized as an internal or external command,\r\n" +
                    "operable program or batch file.\r\nCONCH_NO_UNAME\r\n",
            ),
        )
        assertEquals(
            ServerOs.WINDOWS,
            AgentStatusProbe.classifyOsProbe("The system cannot find the path specified.\r\n"),
        )
    }

    @Test
    fun `powershell shapes classify as windows`() {
        assertEquals(
            ServerOs.WINDOWS,
            AgentStatusProbe.classifyOsProbe(
                "uname : The term 'uname' is not recognized as the name of a cmdlet, function...",
            ),
        )
        assertEquals(
            ServerOs.WINDOWS,
            AgentStatusProbe.classifyOsProbe(
                "The token '||' is not a valid statement separator in this version.",
            ),
        )
        assertEquals(
            ServerOs.WINDOWS,
            AgentStatusProbe.classifyOsProbe("+ CategoryInfo : ObjectNotFound ... CommandNotFoundException"),
        )
    }

    /** No output is NO EVIDENCE — never a Windows verdict (it would hide real
     *  agents on a unix box whose transport hiccupped). */
    @Test
    fun `transport failure and blank output claim nothing`() {
        assertNull(AgentStatusProbe.classifyOsProbe(null))
        assertNull(AgentStatusProbe.classifyOsProbe(""))
        assertNull(AgentStatusProbe.classifyOsProbe("   \n"))
    }

    /** The probe command must stay raw — a `bash -lc`/portable wrapper would
     *  itself be the failure on the very servers it exists to identify. */
    @Test
    fun `os probe command is plain and portable() leaves it alone`() {
        assertTrue(AgentStatusProbe.OS_PROBE_CMD.startsWith("uname -s"))
        assertEquals(AgentStatusProbe.OS_PROBE_CMD, RemoteEnv.portable(AgentStatusProbe.OS_PROBE_CMD))
    }

    // ── the timeout shim (same workstream) ──

    @Test
    fun `timeout shim prefers the real binary and defines the fallback`() {
        val fn = RemoteEnv.TIMEOUT_FN
        assertTrue(fn.startsWith("conch_timeout()"))
        assertTrue("real timeout when present", fn.contains("command -v timeout"))
        assertTrue("watchdog fallback", fn.contains("sleep \"\$_t\"; kill"))
        assertTrue("returns the command's own code", fn.contains("return \$_r"))
    }
}
