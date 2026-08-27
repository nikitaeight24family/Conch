package ai.eight24family.conch.agent

import ai.eight24family.conch.data.ServerActivityLog
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * What an in-flight [AgentInstallManager] run is doing — so the row badge can
 * say the RIGHT word instead of a hardcoded "installing". [badge] is the pill
 * text, [line] the initial sub-line (the live stdout tail overwrites it once
 * the script starts streaming). INSTALL and UPDATE keep the validated
 * "installing" pill; only REMOVE differs.
 */
enum class InstallOp(val badge: String, val line: String) {
    INSTALL("installing", "starting…"),
    UPDATE("installing", "updating…"),
    REMOVE("removing", "removing…"),
}

/**
 * Process-scoped CLI install/update runner.
 *
 * Why a process singleton and not the AgentPicker VM: the user wants to tap
 * "update", walk into a chat, and have the update finish on its own — AND to
 * update several agents at once without one blocking the others. A VM-scoped
 * coroutine dies the moment you navigate away, and the old single
 * `installing: Agent?` flag serialised everything. So:
 *
 *  - **Parallel**: keyed by (serverId, agent); each run is independent, no
 *    global lock. Update claude + codex + gemini at the same time.
 *  - **Survives navigation**: its own [SupervisorJob] scope, never a screen's.
 *    Leave the picker, the install keeps going.
 *  - **Self-finishing**: on completion it re-probes and writes
 *    [AgentStatusCache], so every surface's badge flips even with no screen
 *    watching — you just come back and it says `ready`.
 *
 * The caller hands in the already-built bootstrap script (kept in
 * AgentPickerViewModelInstall) so this stays a pure runner. Best-effort, no
 * error UI (per product policy).
 */
object AgentInstallManager {
    private const val TAG = "SshAi-Install"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun key(serverId: String, agent: Agent) = "$serverId ${agent.name}"

    /** Keys ("serverId AGENT") with an install/update in flight. */
    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    val installing: StateFlow<Set<String>> = _installing.asStateFlow()

    /** Latest stdout tail per in-flight key — drives the live "installing…" line. */
    private val _output = MutableStateFlow<Map<String, String>>(emptyMap())
    val output: StateFlow<Map<String, String>> = _output.asStateFlow()

    /** What each in-flight key is DOING (install / update / remove) — so the
     *  badge shows the right verb. Cleared alongside [_installing] in finally. */
    private val _op = MutableStateFlow<Map<String, InstallOp>>(emptyMap())
    val op: StateFlow<Map<String, InstallOp>> = _op.asStateFlow()

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun isInstalling(serverId: String, agent: Agent): Boolean =
        _installing.value.contains(key(serverId, agent))

    /** Fire-and-forget install/update (the button path). Idempotent per
     *  (server, agent) — a second tap while running is a no-op.
     *  [op] is what we're doing — drives the badge verb + initial sub-line.
     *  Defaults from [forceLatest] (UPDATE vs INSTALL); uninstall passes REMOVE. */
    fun run(
        serverId: String,
        agent: Agent,
        script: String,
        forceLatest: Boolean,
        op: InstallOp = if (forceLatest) InstallOp.UPDATE else InstallOp.INSTALL,
    ) {
        val k = key(serverId, agent)
        if (!inFlight.add(k)) {
            android.util.Log.d(TAG, "run(${agent.name}@$serverId) — already in-flight, no-op")
            return
        }
        android.util.Log.d(TAG, "run(${agent.name}@$serverId) forceLatest=$forceLatest op=$op — launching")
        scope.launch { runInternal(serverId, agent, script, forceLatest, k, op) }
    }

    /** Suspend variant for callers that must wait (e.g. OAuth auto-recovery
     *  installs the CLI then logs in). No-op if one is already running. */
    suspend fun runAndWait(serverId: String, agent: Agent, script: String, forceLatest: Boolean) {
        val k = key(serverId, agent)
        if (!inFlight.add(k)) return
        runInternal(serverId, agent, script, forceLatest, k, if (forceLatest) InstallOp.UPDATE else InstallOp.INSTALL)
    }

    private suspend fun runInternal(
        serverId: String,
        agent: Agent,
        script: String,
        forceLatest: Boolean,
        k: String,
        op: InstallOp,
    ) {
        _op.update { it + (k to op) }
        _installing.update { it + k }
        _output.update { it + (k to op.line) }
        android.util.Log.d(TAG, "runInternal(${agent.name}@$serverId) — op=$op, installing=${_installing.value.size}")
        try {
            val pooled = ServiceLocator.sshConnectionPool.peek(serverId) ?: run {
                android.util.Log.w(TAG, "install(${agent.name}@$serverId): no live pool client — skip")
                return
            }
            withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                var exit = -1
                var tail = ""
                SilentlyTry.fired(TAG, "run install bootstrap") {
                    pooled.startSession().use { sess ->
                        // Outer vehicle rides portable() like every transport;
                        // the INNER `bash -s` interpreter stays — vendor install
                        // scripts are bash, so installing agents on a bash-less
                        // host fails with a visible "bash: not found" in the
                        // install output rather than pretending. Honest limit.
                        val proc = sess.exec(RemoteEnv.portable("bash -lc 'cat | bash -s'") + " 2>&1")
                        proc.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)) }
                        val reader = proc.inputStream.bufferedReader()
                        val full = StringBuilder()
                        while (true) {
                            val line = try { reader.readLine() } catch (_: Throwable) { null } ?: break
                            full.append(line).append('\n')
                            val t = line.trim()
                            if (t.isNotEmpty()) _output.update { it + (k to t.take(140)) }
                        }
                        proc.join(600, TimeUnit.SECONDS)
                        exit = proc.exitStatus ?: -1
                        tail = full.toString()
                    }
                }
                ServerActivityLog.append(
                    serverId,
                    ServerActivityLog.Entry(
                        ts = start,
                        category = "install",
                        command = "${agent.cliCommand} install/update bootstrap (forceLatest=$forceLatest)",
                        exitCode = exit,
                        stdoutTail = tail.takeLast(200),
                        durationMs = System.currentTimeMillis() - start,
                    ),
                )
                android.util.Log.d(TAG, "install(${agent.name}@$serverId) exit=$exit")
            }
            _output.update { it + (k to "verifying…") }
            // Re-probe + write the cache so the badge flips everywhere even if
            // no screen is currently observing this server.
            refreshStatus(serverId)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "install(${agent.name}@$serverId) threw: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            inFlight.remove(k)
            _installing.update { it - k }
            _output.update { it - k }
            _op.update { it - k }
        }
    }

    /** Re-probe the server's agents over the pooled channel and write the
     *  result to [AgentStatusCache] (keeping the prior loggedIn — an install
     *  never logs you in). No fresh handshake / SK touch. */
    private suspend fun refreshStatus(serverId: String) {
        val pooled = ServiceLocator.sshConnectionPool.peek(serverId) ?: return
        val fresh = ServiceLocator.agentStatusProbe.probe(
            // Named: probe() gained a second (onOs) parameter, and a bare
            // trailing lambda would bind to IT, not to exec.
            exec = { cmd ->
                withContext(Dispatchers.IO) {
                    SilentlyTry.logged(TAG, "post-install probe") {
                        val sess = pooled.startSession()
                        try {
                            val proc = sess.exec(cmd)
                            val out = java.io.ByteArrayOutputStream()
                            // Bounded read: the deadline wraps the READ, not the join after it.
                            ai.eight24family.conch.ssh.BoundedExec.drain(
                                proc, out,
                                deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.COMMAND_MS,
                                maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.COMMAND,
                            )
                            proc.join(60, TimeUnit.SECONDS)
                            String(out.toByteArray(), Charsets.UTF_8)
                        } finally {
                            SilentlyTry.fired(TAG, "close post-install probe session") { sess.close() }
                        }
                    }
                }
            },
            onOs = { os -> ServiceLocator.agentStatusCache.saveServerOs(serverId, os.name) },
        ).getOrNull() ?: return
        val old = SilentlyTry.loggedOrElse(TAG, "load old status", emptyMap()) {
            ServiceLocator.agentStatusCache.load(serverId).statuses
        }
        val merged = fresh.mapValues { (a, f) -> old[a]?.let { f.copy(loggedIn = it.loggedIn) } ?: f }
        SilentlyTry.fired(TAG, "save post-install status") {
            ServiceLocator.agentStatusCache.save(serverId, merged)
        }
        android.util.Log.d(TAG, "post-install status refreshed for $serverId")
    }
}
