package ai.eight24family.conch.diagnostics

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 *
 * What a real `conch-bridge ping` exercises, this exercises identically, leg
 * by leg, and FIXES what it can along the way:
 *
 *   1. pooled SSH up (no transport → say so),
 *   2. the CLI present on the server (missing/old → reinstall it ourselves —
 *      [BridgeInstaller] is idempotent),
 *   3. a synthetic `ping` request placed in `~/.conch-bridge/inbox/`,
 *   4. OUR OWN poller picking it up and answering into `outbox/` (that is
 *      [AgentBridge] — the exact code path an agent's request takes).
 *
 * Success means the channel is proven end-to-end; the agent still gets its
 * how-to message, but as INFORMATION — nothing waits on its reply anymore.
 */
object BridgeSelfTest {

    sealed interface Verdict {
        data object Ok : Verdict
        /** One human sentence naming the broken leg — never a raw stack. */
        data class Failed(val reason: String) : Verdict
    }

    suspend fun run(serverId: String): Verdict = withContext(Dispatchers.IO) {
        // Leg 1: transport.
        exec(serverId, "echo up") ?: return@withContext Verdict.Failed(
            "no ssh connection to this server — reconnect and try again",
        )
        // Leg 2: the CLI. Missing → install it right now instead of reporting.
        val bin = exec(serverId, "test -x \$HOME/.local/bin/conch-bridge && echo BIN_OK")
        if (bin?.trim() != "BIN_OK") {
            val r = BridgeInstaller.install(serverId)
            if (!r.success) return@withContext Verdict.Failed(
                "couldn't install the bridge CLI on the server: ${r.log}",
            )
        }
        // Leg 3+4: the real request path, exactly as the CLI drives it.
        ServiceLocator.bridgeManager.ensure(serverId)
        val id = java.util.UUID.randomUUID().toString()
        val req = "{\"id\":\"$id\",\"command\":\"ping\"}"
        val wrote = exec(
            serverId,
            "mkdir -p \$HOME/.conch-bridge/inbox \$HOME/.conch-bridge/outbox && " +
                "printf '%s' '$req' > \$HOME/.conch-bridge/inbox/'$id.req.json.part' && " +
                "mv \$HOME/.conch-bridge/inbox/'$id.req.json.part' \$HOME/.conch-bridge/inbox/'$id.req.json' && echo WROTE",
        )
        if (wrote?.trim() != "WROTE") return@withContext Verdict.Failed(
            "couldn't write into ~/.conch-bridge/inbox on the server",
        )
        // The poller ticks every 2 s in the foreground — 12 s is six chances.
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            delay(1_000)
            val res = exec(serverId, "cat \$HOME/.conch-bridge/outbox/'$id.res.json' 2>/dev/null")
            if (!res.isNullOrBlank()) {
                exec(
                    serverId,
                    "rm -f \$HOME/.conch-bridge/outbox/'$id.res.json' \$HOME/.conch-bridge/outbox/'$id.data'",
                )
                return@withContext if ("\"ok\"" in res || "pong" in res) Verdict.Ok else Verdict.Failed(
                    "the bridge answered with an error: ${res.take(160)}",
                )
            }
        }
        exec(serverId, "rm -f \$HOME/.conch-bridge/inbox/'$id.req.json'")
        Verdict.Failed(
            "the phone's own poller never answered — if this app was just " +
                "reinstalled, give it a few seconds and tap again",
        )
    }

    /** One bounded command over the pooled transport; null = no client/failed. */
    private suspend fun exec(serverId: String, cmd: String): String? {
        val client = ServiceLocator.sshConnectionPool.peek(serverId) ?: return null
        return SilentlyTry.logged("Conch-BridgeSelfTest", "exec") {
            val sess = client.startSession()
            try {
                val proc = sess.exec(cmd)
                val out = java.io.ByteArrayOutputStream()
                ai.eight24family.conch.ssh.BoundedExec.drain(
                    proc, out,
                    deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.COMMAND_MS,
                    maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.COMMAND,
                )
                proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                String(out.toByteArray(), Charsets.UTF_8)
            } finally {
                SilentlyTry.fired("Conch-BridgeSelfTest", "close exec session") { sess.close() }
            }
        }
    }
}
