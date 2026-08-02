package ai.eight24family.conch.diagnostics

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        SilentlyTry.loggedOrElse("SshAi-BridgeInstall", "parse bundled version", "?") {
            val text = ServiceLocator.appContext.assets.open("conch-bridge").use { it.readBytes() }
                .toString(Charsets.UTF_8)
            Regex("CONCH_BRIDGE_VERSION=\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "?"
        }
    }

    /** Write the bundled CLI + dirs to [serverId]. */
    suspend fun install(serverId: String): InstallResult {
        val script = SilentlyTry.logged("SshAi-BridgeInstall", "read conch-bridge asset") {
            ServiceLocator.appContext.assets.open("conch-bridge").use { it.readBytes() }
        } ?: return InstallResult(false, "bundled conch-bridge asset missing")
        val cmd = "set -e; " +
            "mkdir -p \$HOME/.conch-bridge/inbox \$HOME/.conch-bridge/outbox \$HOME/.local/bin; " +
            "chmod 700 \$HOME/.conch-bridge \$HOME/.conch-bridge/inbox \$HOME/.conch-bridge/outbox; " +
            "cat > \$HOME/.local/bin/conch-bridge; " +
            "chmod +x \$HOME/.local/bin/conch-bridge; " +
            "echo \"wrote \$(wc -c < \$HOME/.local/bin/conch-bridge)B to ~/.local/bin/conch-bridge; dirs ~/.conch-bridge ready\""
        val r = run(serverId, cmd, script) ?: return InstallResult(false, "no live connection — connect to the server first")
        return InstallResult(r.first == 0, oneLine(r.second).ifBlank { if (r.first == 0) "installed" else "remote exit ${r.first}" })
    }

    /** Remove conch-bridge (and the legacy sshai-bridge) + its dirs from [serverId]. */
    suspend fun uninstall(serverId: String): InstallResult {
        val cmd = "rm -rfv \$HOME/.conch-bridge \$HOME/.sshai-bridge " +
            "\$HOME/.local/bin/conch-bridge \$HOME/.local/bin/sshai-bridge 2>/dev/null; echo done"
        val r = run(serverId, cmd, null) ?: return InstallResult(false, "no live connection — connect to the server first")
        return InstallResult(r.first == 0, oneLine(r.second).ifBlank { "nothing to remove" })
    }

    /** Installed state + version on [serverId]. null = couldn't reach the server. */
    suspend fun status(serverId: String): Status? {
        val cmd = "if [ -x \$HOME/.local/bin/conch-bridge ]; then " +
            "echo \"v:\$(grep -m1 '^CONCH_BRIDGE_VERSION=' \$HOME/.local/bin/conch-bridge 2>/dev/null | cut -d'\"' -f2)\"; " +
            "else echo absent; fi"
        val r = run(serverId, cmd, null) ?: return null
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

    /** Run [cmd] on [serverId] over the pooled client; optional [stdin] piped in.
     *  Returns (exitCode, stdout+stderr) or null if there's no live connection. */
    private suspend fun run(serverId: String, cmd: String, stdin: ByteArray?): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val client = ServiceLocator.sshConnectionPool.peek(serverId) ?: return@withContext null
            SilentlyTry.logged("SshAi-BridgeInstall", "exec on server") {
                val sess = client.startSession()
                try {
                    val proc = sess.exec(cmd)
                    if (stdin != null) proc.outputStream.use { it.write(stdin); it.flush() }  // EOF for `cat`
                    val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                    val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
                    proc.join(20, TimeUnit.SECONDS)
                    (proc.exitStatus ?: -1) to (out + "\n" + err)
                } finally {
                    SilentlyTry.fired("SshAi-BridgeInstall", "close session") { sess.close() }
                }
            }
        }
}
