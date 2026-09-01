package ai.eight24family.conch.data

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentDoc
import ai.eight24family.conch.agent.AgentScope
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SSH-backed CRUD for `~/.claude/agents/<name>.md` files.
 *
 * Tries the live, already-authenticated [AgentSession] first (multiplexes a
 * fresh exec channel — fast). Falls back to a brand-new [SshClient.execute]
 * handshake when no chat session is up. Editing/saving REQUIRES a live
 * channel because we pipe content through stdin (no fresh-handshake path
 * for that — the existing `SshClient.execute` is one-shot stdout-only).
 *
 * The list/fetch path works either way, so the agents browser is usable
 * immediately after navigating to the screen — even if the chat never
 * bootstrapped.
 */
class SubagentService(
    private val serverId: String,
    private val agent: Agent,
    /** Optional — when non-null, we prefer the live session for that chat. */
    private val chatId: String?,
) {

    /**
     * Enumerate subagent files under `$HOME/.claude/agents` and (if cwd
     * is given) `<cwd>/.claude/agents`. Returns parsed docs in the order
     * they appear on disk.
     */
    suspend fun list(cwd: String?): List<AgentDoc> = withContext(Dispatchers.IO) {
        val cwdPath = cwd?.takeIf { it.isNotBlank() }.orEmpty()
        val script = """
            for d in "${'$'}HOME/.claude/agents" "$cwdPath/.claude/agents"; do
              [ -d "${'$'}d" ] || continue
              scope="global"; case "${'$'}d" in *${'$'}HOME*) scope="global";; *) scope="project";; esac
              for f in "${'$'}d"/*.md; do
                [ -f "${'$'}f" ] || continue
                echo "=== ${'$'}scope|${'$'}f"
                cat "${'$'}f"
              done
            done
        """.trimIndent()
        val raw = exec("bash -lc " + shQuote(script)) ?: return@withContext emptyList()
        parseAgentDocs(raw)
    }

    /** Cwd of the live session if any — used by the browser to surface
     *  project-scope agents in the list and to enable the "project" pill
     *  in the editor. */
    fun cwdSnapshot(): String? = liveSession()?.cwdSnapshot

    /**
     * Read one agent file as raw markdown. Used by the edit screen to
     * load the existing body when the user taps a card.
     */
    suspend fun fetchOne(path: String): String? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        exec("bash -lc " + shQuote("cat ${shQuote(path)}"))
    }

    /**
     * Write (create or replace) an agent file. Returns true on success.
     * Requires a live session because the body is piped via stdin.
     *
     * If [oldPath] differs from the freshly-computed target path, the old
     * file is removed too (handles renames atomically-ish from the user's
     * perspective).
     */
    suspend fun save(
        scope: AgentScope,
        name: String,
        description: String,
        tools: List<String>,
        body: String,
        oldPath: String? = null,
    ): SaveResult = withContext(Dispatchers.IO) {
        val cleanName = name.trim().replace(Regex("[^A-Za-z0-9_-]"), "-").lowercase()
        if (cleanName.isBlank()) return@withContext SaveResult.InvalidName
        val live = liveSession() ?: return@withContext SaveResult.NoLiveSession

        val targetPath = when (scope) {
            AgentScope.GLOBAL -> "\$HOME/.claude/agents/$cleanName.md"
            AgentScope.PROJECT -> {
                val cwd = live.cwdSnapshot ?: return@withContext SaveResult.NoCwd
                "$cwd/.claude/agents/$cleanName.md"
            }
        }
        val frontmatter = buildString {
            append("---\n")
            append("name: ").append(cleanName).append('\n')
            if (description.isNotBlank()) {
                // Description is single-line in YAML — strip stray newlines.
                append("description: ").append(description.replace('\n', ' ').trim()).append('\n')
            }
            if (tools.isNotEmpty()) append("tools: ").append(tools.joinToString(", ")).append('\n')
            append("---\n\n")
        }
        val content = frontmatter + body.trimEnd() + "\n"
        val writeCmd = "bash -lc " + shQuote("mkdir -p \"\$(dirname $targetPath)\" && cat > $targetPath")
        val ok = live.execOnLiveWithStdin(writeCmd, content.toByteArray())
        if (!ok) return@withContext SaveResult.WriteFailed

        // Rename: if filename changed, remove the old file. Different
        // scope (global vs project dir) is also handled by this since
        // oldPath won't end with the same trailing /<name>.md.
        if (!oldPath.isNullOrBlank() && !oldPath.endsWith("/$cleanName.md")) {
            live.execOnLive("bash -lc " + shQuote("rm -f ${shQuote(oldPath)}"))
        }
        SaveResult.Ok(savedName = cleanName, savedPath = targetPath)
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext false
        val out = exec("bash -lc " + shQuote("rm -f ${shQuote(path)} && echo OK || echo ERR"))
        out?.contains("OK") == true
    }

    sealed interface SaveResult {
        data class Ok(val savedName: String, val savedPath: String) : SaveResult
        data object InvalidName : SaveResult
        data object NoLiveSession : SaveResult
        data object NoCwd : SaveResult
        data object WriteFailed : SaveResult
    }

    // ── internals ──

    private fun liveSession(): AgentSession? {
        // Defensive against ServiceLocator not being initialized — happens
        // in unit tests that construct this class directly. Production
        // always has it set up by Application.onCreate.
        val mgr = SilentlyTry.logged("Conch-Subagent", "resolve agent sessions") { ServiceLocator.agentSessions } ?: return null
        return chatId?.let { mgr.get(serverId, agent, it) }
            ?: mgr.findAnyAlive(serverId, agent)
    }

    private suspend fun exec(command: String): String? {
        liveSession()?.let { return it.execOnLive(command) }
        // Fresh-handshake fallback — only stdout-capable, fine for list/fetch.
        val server = ServiceLocator.serverRepository.getById(serverId) ?: return null
        val secrets = ServiceLocator.serverRepository.getSecrets(serverId)
        return ServiceLocator.sshClient.execute(server, secrets, command).getOrNull()
    }

    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    internal fun parseAgentDocs(raw: String): List<AgentDoc> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<AgentDoc>()
        var scope = ""; var path = ""; var buf = StringBuilder(); var inDoc = false
        fun flush() {
            if (!inDoc) return
            val (frontmatter, body) = splitFrontmatter(buf.toString())
            val name = frontmatter["name"] ?: path.substringAfterLast('/').removeSuffix(".md")
            val desc = frontmatter["description"]
            val tools = frontmatter["tools"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
            out += AgentDoc(
                name = name,
                scope = scope,
                description = desc,
                tools = tools,
                body = body.trim(),
                path = path,
            )
        }
        for (line in raw.lineSequence()) {
            if (line.startsWith("=== ")) {
                flush()
                val tail = line.removePrefix("=== ")
                val pipe = tail.indexOf('|')
                scope = if (pipe > 0) tail.substring(0, pipe) else "global"
                path = if (pipe > 0) tail.substring(pipe + 1) else tail
                buf = StringBuilder()
                inDoc = true
            } else if (inDoc) {
                buf.append(line).append('\n')
            }
        }
        flush()
        return out
    }

    internal fun splitFrontmatter(text: String): Pair<Map<String, String>, String> {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("---")) return emptyMap<String, String>() to text
        val rest = trimmed.removePrefix("---")
        val end = rest.indexOf("\n---")
        if (end < 0) return emptyMap<String, String>() to text
        val fm = rest.substring(0, end)
        val body = rest.substring(end + 4).trimStart('\n', '\r')
        val map = fm.lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, colon).trim().lowercase() to
                    line.substring(colon + 1).trim().trim('"', '\'')
            }
            .toMap()
        return map to body
    }
}
