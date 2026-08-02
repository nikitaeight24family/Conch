package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.ssh.SshClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One saved CLI session, surfaced to the sessions list.
 *
 * @param id      The CLI's resume id (Claude UUID, Codex `thread_id`, Gemini
 *                session filename UUID). Passed back as `--resume` argument
 *                — fragments must match what each CLI's resume flow expects.
 * @param path    Absolute path to the JSONL/JSON on the server. Used to
 *                hydrate full history when the user opens the chat row.
 * @param agent   Which CLI authored it.
 * @param lastActiveAt Unix-seconds mtime, used to sort the row list.
 * @param preview Trimmed first user message for the row subtitle.
 */
data class RemoteSession(
    val id: String,
    val path: String,
    val agent: Agent,
    val lastActiveAt: Long,
    val preview: String,
    /** Model id this session was opened with (parsed from the JSONL
     *  header during listing). null when the session file doesn't
     *  surface the model, or the agent's listSessionsScript doesn't
     *  emit a model column. Lets the chat topbar render the actual
     *  model name the moment the user taps a session row — no
     *  'loading…' flash. */
    val model: String? = null,
    /** Reasoning effort the session was running on, parsed from the
     *  JSONL header. Codex writes `"reasoning_effort":"<X>"` in the
     *  `settings` object of each turn. Mirror of [model] — lets the
     *  topbar render the actual effort from frame zero of a chat
     *  resume, instead of falling back to the user's config.toml
     *  global which may not match what THIS chat was running on. */
    val reasoning: String? = null,
    /** On-disk size of the session JSONL in bytes (sum across multi-file
     *  gemini chats isn't done — it's the listed file's size). null when the
     *  agent's listing script predates the size column. Rendered human-readable
     *  on the session row. */
    val sizeBytes: Long? = null,
    /** The CLI's OWN generated session title (Claude `ai-title`, the name shown
     *  in `claude --resume`) — rendered as the row's ACCENT header. Null when the
     *  agent/session has none → the row falls back to [preview] as the name. */
    val title: String? = null,
)

/**
 * Lists saved CLI sessions on the server.
 *
 * This used to be a per-agent monster with two hardcoded bash scripts and
 * separate preview parsers. After the per-CLI spec refactor every
 * agent-specific concern (listing script, preview extraction) lives in its
 * `AgentCliSpec`, leaving this class as a thin orchestrator over SSH
 * `execute` + tab-split parsing.
 *
 * Three overloads exist:
 *
 *  - [list] (server, secrets) — fresh SSH handshake. Used by cold-start
 *    pulls when there's no live channel.
 *  - [list] (exec) — reuse a caller-provided `execOnLive` closure. Used by
 *    pull-to-refresh on the sessions screen while a chat is already open,
 *    so we skip the second auth round-trip (and on SK servers, the second
 *    FIDO touch).
 *  - [fetchSessionContent] — read one full session file back, two
 *    overloads with the same authenticated-channel-vs-fresh-handshake split.
 */
class SessionDiscovery(private val ssh: SshClient) {

    suspend fun list(
        server: Server,
        secrets: ServerSecrets,
        agent: Agent,
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null,
    ): List<RemoteSession> =
        withContext(Dispatchers.IO) {
            val script = AgentSpecRegistry[agent].listSessionsScript ?: return@withContext emptyList()
            val cmd = "bash -lc " + shellEscape(script)
            val raw = ssh.execute(server, secrets, cmd, skSigner)
            val out = raw.getOrNull().orEmpty()
            android.util.Log.d(
                "SshAi-SK-Disc",
                "  ssh.execute raw: success=${raw.isSuccess} bytes=${out.length} preview='${out.take(300).replace("\n", "\\n")}'"
            )
            raw.exceptionOrNull()?.let {
                android.util.Log.w("SshAi-SK-Disc", "  ssh.execute threw", it)
            }
            parseLines(agent, out)
        }

    /** Read the entire JSONL content of a saved session file from the server. */
    suspend fun fetchSessionContent(
        server: Server,
        secrets: ServerSecrets,
        path: String,
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            val cmd = catCommand(path)
            ssh.execute(server, secrets, cmd, skSigner).getOrNull()
        }

    /** [fetchSessionContent] bounded to [maxBytes]; null when the file is over
     *  the cap (caller should leave it uncached and fetch it on open instead). */
    suspend fun fetchSessionContentCapped(
        server: Server,
        secrets: ServerSecrets,
        path: String,
        maxBytes: Long,
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            val raw = ssh.execute(server, secrets, catCappedCommand(path, maxBytes), skSigner).getOrNull()
                ?: return@withContext null
            if (raw.toByteArray(Charsets.UTF_8).size > maxBytes) null else raw
        }

    /**
     * Variant that pipes the `cat` through a caller-provided [exec] —
     * typically a closure over a pooled SSHClient's `startSession()`. Lets
     * the global prefetcher fan out lots of session-body fetches over a
     * SINGLE authenticated connection (no per-fetch handshake, no per-fetch
     * security-key touch).
     */
    suspend fun fetchSessionContent(
        path: String,
        exec: suspend (cmd: String) -> String?,
    ): String? = withContext(Dispatchers.IO) {
        val cmd = catCommand(path)
        exec(cmd)
    }

    /** The shell command that reads a session body. Exposed so the
     *  prefetcher can run it and pipe stdout STRAIGHT to disk, instead of
     *  buffering the whole (possibly 100+ MB) file in RAM — the String-based
     *  fetch above OOM'd on very large rollouts. */
    fun catCommand(path: String): String =
        "bash -lc " + shellEscape("cat ${shellEscape(path)}")

    /**
     * Like [catCommand] but reads at most [maxBytes] + 1 bytes.
     *
     * Lets a SPECULATIVE prefetch bound both the transfer and the memory it
     * costs without a second round-trip to stat the file first: ask for one
     * byte more than the cap, and if that many come back the file is over the
     * cap and gets skipped. Non-pooled (password / plain-key) hosts had no cap
     * at all and still pulled every uncached session whole, through the same
     * String path that already OOM'd on a large rollout.
     */
    fun catCappedCommand(path: String, maxBytes: Long): String =
        "bash -lc " + shellEscape("head -c ${maxBytes + 1} ${shellEscape(path)}")

    /**
     * Variant that runs the discovery script through a caller-provided
     * `exec` lambda — typically `agentSession::execOnLive`, riding an
     * already-authenticated persistent SSH channel and so not needing a
     * fresh handshake (and, on FIDO security-key servers, no fresh touch).
     * Used by sessions / agent-picker pull-to-refresh paths whenever a chat
     * is already open for that (server, agent) pair.
     */
    suspend fun list(
        agent: Agent,
        exec: suspend (cmd: String) -> String?,
    ): List<RemoteSession> =
        withContext(Dispatchers.IO) {
            val script = AgentSpecRegistry[agent].listSessionsScript ?: return@withContext emptyList()
            val cmd = "bash -lc " + shellEscape(script)
            val out = exec(cmd).orEmpty()
            parseLines(agent, out)
        }

    private fun parseLines(agent: Agent, output: String): List<RemoteSession> {
        val spec = AgentSpecRegistry[agent]
        return output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                // Output contract from spec.listSessionsScript:
                //   id<TAB>mtime<TAB>path<TAB>model<TAB>reasoning<TAB>raw_preview
                // model + reasoning columns are optional ('' when the spec's
                // script doesn't extract them or the file doesn't carry them).
                // Legacy 5-column scripts (without reasoning) still parse — the
                // 5th part is treated as the preview.
                val parts = line.split('\t', limit = 7)
                if (parts.size < 3) return@mapNotNull null
                val id = parts[0].trim()
                val mtime = parts[1].trim().toLongOrNull() ?: 0L
                val path = parts[2].trim()
                val model = parts.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() }
                // Column layouts (preview is always LAST):
                //   7-col: …model<TAB>reasoning<TAB>size<TAB>preview   (current)
                //   6-col: …model<TAB>reasoning<TAB>preview            (legacy, no size)
                //   5-col: …model<TAB>preview                         (legacy claude)
                val reasoning: String?
                val sizeBytes: Long?
                val rawPreview: String
                when {
                    parts.size >= 7 -> {
                        reasoning = parts[4].trim().takeIf { it.isNotBlank() }
                        sizeBytes = parts[5].trim().toLongOrNull()
                        rawPreview = parts[6]
                    }
                    parts.size == 6 -> {
                        reasoning = parts[4].trim().takeIf { it.isNotBlank() }
                        sizeBytes = null
                        rawPreview = parts[5]
                    }
                    else -> {
                        reasoning = null
                        sizeBytes = null
                        rawPreview = parts.getOrNull(4).orEmpty()
                    }
                }
                if (id.isBlank() || path.isBlank()) return@mapNotNull null
                RemoteSession(
                    id = id,
                    path = path,
                    agent = agent,
                    lastActiveAt = mtime,
                    preview = spec.extractSessionPreview(rawPreview),
                    title = spec.extractSessionTitle(rawPreview),
                    model = model,
                    reasoning = reasoning,
                    sizeBytes = sizeBytes,
                )
            }.toList()
    }
}
