package ai.eight24family.conch

import ai.eight24family.conch.linux.LinuxSsh
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's Linux, as a machine the rest of the app can dial.
 *
 * Nothing here is about installing agents ON it, and that is the point: the
 * phone is an ordinary server row, so the ordinary screens do that work. What
 * is phone-specific is the endpoint they reach it through, and every line of it
 * was measured on the owner's device.
 */
class LinuxSshTest {

    @Test
    fun `the endpoint is loopback-only, key-only, and never backgrounds itself`() {
        val s = LinuxSsh.daemonScript()
        // A wildcard bind would put a listening port on every Wi-Fi network the
        // phone ever joins.
        assertTrue(s.contains("ListenAddress ${LinuxSsh.HOST}"))
        assertTrue(s.contains("Port ${LinuxSsh.PORT}"))
        assertTrue(s.contains("PasswordAuthentication no"))
        assertTrue(s.contains("KbdInteractiveAuthentication no"))
        // ⛔ -D is load-bearing: PRoot drives its tracees with ptrace and cannot
        // detach, so a daemon that forks into the background takes the proot
        // session — and itself — down with it.
        assertTrue(s.contains("exec /usr/sbin/sshd -D"))
    }

    @Test
    fun `it is openssh, because dropbear truncates what this app sends`() {
        val s = LinuxSsh.daemonScript()
        // ⛔ MEASURED, 2026-08-31: dropbear authenticated every connection and
        // then hung up with "String too long" — it caps an exec command at
        // ~1.4 KB, and the agent probe alone is several KB assembled from ten
        // CLI specs. The picker could only report "server isn't responding".
        assertTrue(s.contains("openssh-server"))
        assertFalse(s.contains("dropbear"))
        // File transfer in the app is SFTP, so the subsystem is not optional.
        assertTrue(s.contains("openssh-sftp-server"))
        assertTrue(s.contains("Subsystem sftp"))
    }

    @Test
    fun `it writes its OWN config, never the environment's sshd_config`() {
        val s = LinuxSsh.daemonScript()
        assertTrue(s.contains("/etc/ssh/sshd_conch.conf"))
        assertFalse(s.contains("> /etc/ssh/sshd_config"))
    }
}
