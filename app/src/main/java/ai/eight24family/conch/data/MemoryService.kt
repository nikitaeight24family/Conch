package ai.eight24family.conch.data

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.ui.viewmodel.MemoryDocs
import ai.eight24family.conch.ui.viewmodel.MemoryScope
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SSH-backed read/write of the per-CLI "memory" file
 * (`CLAUDE.md` / `AGENTS.md` / `GEMINI.md`). Mirrors the live-or-fresh
 * channel strategy in [SubagentService] so this is usable both from a
 * live chat AND from screens that never opened one (e.g. SessionsScreen).
 *
 * The probe-cwd path is best-effort: a live session has [AgentSession.cwdSnapshot]
 * already; without one we fall back to running `pwd` over a fresh
 * handshake, which gives the user's login shell cwd — not perfect (the
 * user might have wanted a specific repo's project-scope file) but a
 * reasonable default for "show me my global memory".
 */
class MemoryService(
    private val serverId: String,
    private val agent: Agent,
    /** Optional — when non-null, prefer the live session for that chat. */
    private val chatId: String?,
) {

    suspend fun load(): MemoryDocs = withContext(Dispatchers.IO) {
        val cwd = liveSession()?.cwdSnapshot
            ?: probeLoginCwd()
            ?: ""
        val globalPath = agent.memoryGlobalPath
        val projectPath = if (cwd.isNotBlank()) "$cwd/${agent.memoryFilename}" else ""
        val script = """
            echo --GLOBAL--
            cat "$globalPath" 2>/dev/null
            echo
            echo --PROJECT--
            ${if (projectPath.isNotBlank()) "cat \"$projectPath\" 2>/dev/null" else ""}
        """.trimIndent()
        val out = exec("bash -lc " + shQuote(script)).orEmpty()
        val global = out.substringAfter("--GLOBAL--").substringBefore("--PROJECT--").trim()
        val project = out.substringAfter("--PROJECT--").trim()
        MemoryDocs(
            global = global,
            project = project,
            projectPath = projectPath,
            filename = agent.memoryFilename,
            globalDisplay = agent.memoryGlobalDisplay,
        )
    }

    /** Returns true on success; false if the path can't be resolved (e.g.
     *  PROJECT scope without a known cwd) or the write fails. */
    suspend fun save(scope: MemoryScope, contents: String): Boolean = withContext(Dispatchers.IO) {
        val live = liveSession() ?: return@withContext false  // stdin write needs a live session
        val targetPath = when (scope) {
            MemoryScope.GLOBAL -> agent.memoryGlobalPath
            MemoryScope.PROJECT -> {
                val cwd = live.cwdSnapshot ?: return@withContext false
                "$cwd/${agent.memoryFilename}"
            }
        }
        val cmd = "bash -lc " + shQuote("mkdir -p \"\$(dirname $targetPath)\" && cat > $targetPath")
        live.execOnLiveWithStdin(cmd, contents.toByteArray())
    }

    // ── internals ──

    private fun liveSession(): AgentSession? {
        // Defensive against ServiceLocator not being initialized — happens
        // in unit tests that construct this class directly. Production
        // always has it set up by Application.onCreate.
        val mgr = SilentlyTry.logged("SshAi-Memory", "resolve agent sessions") { ServiceLocator.agentSessions } ?: return null
        return chatId?.let { mgr.get(serverId, agent, it) }
            ?: mgr.findAnyAlive(serverId, agent)
    }

    private suspend fun exec(command: String): String? {
        liveSession()?.let { return it.execOnLive(command) }
        val server = ServiceLocator.serverRepository.getById(serverId) ?: return null
        val secrets = ServiceLocator.serverRepository.getSecrets(serverId)
        return ServiceLocator.sshClient.execute(server, secrets, command).getOrNull()
    }

    private suspend fun probeLoginCwd(): String? {
        val out = exec("bash -lc 'pwd'") ?: return null
        return out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
