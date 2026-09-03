package ai.eight24family.conch.diagnostics

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.ssh.SshConnectionPool
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.util.concurrent.TimeUnit

/**
 * Installs / removes the `conch-bridge` CLI + its inbox/outbox dirs on ONE
 * server, over that server's existing pooled SSH transport. Invoked only from
 * explicit user actions (chat "Connect phone to server", or the server page's
 * bridge controls) — we never write to a user's server uninvited. Idempotent.
 * Every op returns a one-line [InstallResult.log] so the UI can show exactly
 * what happened on the server.
 */
object BridgeInstaller {

    data class InstallResult(val success: Boolean, val log: String)

    /** Bridge state on a server. [version] is "?" when installed but the script
     *  predates versioning (an old/auto-installed copy). */
    data class Status(val installed: Boolean, val version: String?)

    /** Version string this app ships, parsed once from the bundled asset. */
    val bundledVersion: String by lazy {
        SilentlyTry.loggedOrElse("Conch-BridgeInstall", "parse bundled version", "?") {
            val text = ServiceLocator.appContext.assets.open("conch-bridge").use { it.readBytes() }
                .toString(Charsets.UTF_8)
            Regex("CONCH_BRIDGE_VERSION=\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "?"
        }
    }

    /** Write the bundled CLI + dirs to [serverId]. */
    suspend fun install(serverId: String): InstallResult {
        val script = SilentlyTry.logged("Conch-BridgeInstall", "read conch-bridge asset") {
            ServiceLocator.appContext.assets.open("conch-bridge").use { it.readBytes() }
        } ?: return InstallResult(false, "bundled conch-bridge asset missing")
        val cmd = "set -e; " +
            "mkdir -p \$HOME/.conch-bridge/inbox \$HOME/.conch-bridge/outbox \$HOME/.local/bin; " +
            "chmod 700 \$HOME/.conch-bridge \$HOME/.conch-bridge/inbox \$HOME/.conch-bridge/outbox; " +
            "cat > \$HOME/.local/bin/conch-bridge; " +
            "chmod +x \$HOME/.local/bin/conch-bridge; " +
            "echo \"wrote \$(wc -c < \$HOME/.local/bin/conch-bridge)B to ~/.local/bin/conch-bridge; dirs ~/.conch-bridge ready\""
        val client = when (val d = ServiceLocator.sshConnectionPool.ensureConnected(serverId)) {
            is SshConnectionPool.Dialled.Down -> return InstallResult(false, d.why)
            is SshConnectionPool.Dialled.Up -> d.client
        }
        val r = exec(client, cmd, script)
            ?: return InstallResult(false, "the connection dropped while writing the bridge — try again")
        return InstallResult(r.first == 0, oneLine(r.second).ifBlank { if (r.first == 0) "installed" else "remote exit ${r.first}" })
    }

    /** Remove conch-bridge (and the legacy sshai-bridge) + its dirs from [serverId]. */
    suspend fun uninstall(serverId: String): InstallResult {
        val cmd = "rm -rfv \$HOME/.conch-bridge \$HOME/.sshai-bridge " +
            "\$HOME/.local/bin/conch-bridge \$HOME/.local/bin/sshai-bridge 2>/dev/null; echo done"
        val client = when (val d = ServiceLocator.sshConnectionPool.ensureConnected(serverId)) {
            is SshConnectionPool.Dialled.Down -> return InstallResult(false, d.why)
            is SshConnectionPool.Dialled.Up -> d.client
        }
        val r = exec(client, cmd, null)
            ?: return InstallResult(false, "the connection dropped before the bridge was removed — try again")
        return InstallResult(r.first == 0, oneLine(r.second).ifBlank { "nothing to remove" })
    }

    /**
     * Installed state + version on [serverId]. null = couldn't ask.
     *
     * ⛔ PASSIVE ON PURPOSE, unlike [install]: this one never dials. It runs
     * on screen-open (the server page's bridge card), and a page you merely
     * LOOKED at must not open a connection nobody asked for. The paths that ARE
     * a tap bring the transport up themselves first — and then this answers
     * the truth rather than null, which is what had the chat offering to install
     * a bridge that was already sitting on the server (owner, 2026-09-03).
     */
    suspend fun status(serverId: String): Status? {
        val cmd = "if [ -x \$HOME/.local/bin/conch-bridge ]; then " +
            "echo \"v:\$(grep -m1 '^CONCH_BRIDGE_VERSION=' \$HOME/.local/bin/conch-bridge 2>/dev/null | cut -d'\"' -f2)\"; " +
            "else echo absent; fi"
        val client = ServiceLocator.sshConnectionPool.peek(serverId) ?: return null
        val r = exec(client, cmd, null) ?: return null
        val line = r.second.trim().lineSequence()
            .firstOrNull { it.startsWith("v:") || it == "absent" } ?: ""
        return when {
            line == "absent" -> Status(false, null)
            line.startsWith("v:") -> Status(true, line.removePrefix("v:").ifBlank { "?" })
            else -> Status(false, null)
        }
    }

    private fun oneLine(s: String): String =
        s.trim().replace(Regex("[\\r\\n]+"), " · ").take(300)

    /** Run [cmd] on an already-open [client]; optional [stdin] piped in. Returns
     *  (exitCode, stdout+stderr), or null if the exec itself failed. */
    private suspend fun exec(client: SSHClient, cmd: String, stdin: ByteArray?): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            SilentlyTry.logged("Conch-BridgeInstall", "exec on server") {
                val sess = client.startSession()
                try {
                    val proc = sess.exec(cmd)
                    if (stdin != null) proc.outputStream.use { it.write(stdin); it.flush() }  // EOF for `cat`
                    val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                    val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
                    proc.join(20, TimeUnit.SECONDS)
                    (proc.exitStatus ?: -1) to (out + "\n" + err)
                } finally {
                    SilentlyTry.fired("Conch-BridgeInstall", "close session") { sess.close() }
                }
            }
        }
}
