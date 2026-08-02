package ai.eight24family.conch.data

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.SessionDiscovery
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * "Max global" indexer — fetches every reachable session JSONL from
 * every server×agent into the local [HistoryCache] so the
 * [ChatSearch] linear scan covers absolutely everything the user
 * can possibly have on file, not just the chats they've manually
 * opened.
 *
 * **Reachability rules:**
 *  - Non-SK server: use `ssh.execute` (fresh handshake — works as
 *    long as creds are valid).
 *  - SK-keyed server with live `pool.peek(serverId) != null`: ride
 *    the pooled SSH client (no tap needed). The user pre-tapped at
 *    some point and the connection is still warm.
 *  - SK server without live pool: skipped. Indexing every SK chat
 *    would demand a user tap per server, which contradicts the
 *    "max global, just do it" UX. We surface those servers in the
 *    progress text so the user knows to tap them once if they want
 *    those chats indexed too.
 *
 * Progress is reported via a callback so the GlobalSearchScreen can
 * render a live "indexing X / Y" line.
 */
object ChatIndexer {

    data class Progress(
        val serversTotal: Int,
        val serversDone: Int,
        val sessionsTotal: Int,
        val sessionsDone: Int,
        val skipped: Int,
        val currentLabel: String,
    )

    /**
     * Walk every server × every agent, list sessions, fetch any not
     * yet cached locally, save into [HistoryCache]. Returns when the
     * sweep is done (or coroutine cancelled). Caller drives progress
     * via [onProgress] — runs on caller's dispatcher.
     */
    suspend fun indexAll(onProgress: (Progress) -> Unit) {
        val repo = ServiceLocator.serverRepository
        val cache = ServiceLocator.historyCache
        val pool = ServiceLocator.sshConnectionPool
        val discovery = SessionDiscovery(ServiceLocator.sshClient)
        val cachedIds = cache.listSessionIds().toHashSet()

        val servers = repo.observeServers().first()
        var serversDone = 0
        var sessionsTotal = 0
        var sessionsDone = 0
        var skipped = 0

        onProgress(
            Progress(
                serversTotal = servers.size,
                serversDone = 0,
                sessionsTotal = 0,
                sessionsDone = 0,
                skipped = 0,
                currentLabel = "starting",
            )
        )

        for (server in servers) {
            val secrets = repo.getSecrets(server.id)
            val isSk = secrets.skKeys.isNotEmpty()
            val pooled = pool.peek(server.id)
            // SK without live pool → skip; would need a user tap.
            if (isSk && pooled == null) {
                skipped++
                serversDone++
                onProgress(
                    Progress(
                        serversTotal = servers.size,
                        serversDone = serversDone,
                        sessionsTotal = sessionsTotal,
                        sessionsDone = sessionsDone,
                        skipped = skipped,
                        currentLabel = "${server.name}: skipped (SK key, no live connection)",
                    )
                )
                continue
            }

            for (agent in Agent.entries) {
                onProgress(
                    Progress(
                        serversTotal = servers.size,
                        serversDone = serversDone,
                        sessionsTotal = sessionsTotal,
                        sessionsDone = sessionsDone,
                        skipped = skipped,
                        currentLabel = "${server.name}/${agent.name.lowercase()}: listing…",
                    )
                )
                val sessions = withContext(Dispatchers.IO) {
                    SilentlyTry.loggedOrElse("SshAi-Indexer", "list sessions for indexer", emptyList<ai.eight24family.conch.agent.RemoteSession>()) {
                        if (pooled != null) {
                            // Ride the pooled client — no fresh handshake.
                            discovery.list(agent) { cmd ->
                                SilentlyTry.logged("SshAi-Indexer", "exec list on pooled") {
                                    val sess = pooled.startSession()
                                    try {
                                        val proc = sess.exec(cmd)
                                        val out = java.io.ByteArrayOutputStream()
                                        proc.inputStream.copyTo(out)
                                        proc.join(30, java.util.concurrent.TimeUnit.SECONDS)
                                        String(out.toByteArray(), Charsets.UTF_8)
                                    } finally { SilentlyTry.fired("SshAi-Indexer", "close list-exec session") { sess.close() } }
                                }
                            }
                        } else {
                            discovery.list(server, secrets, agent)
                        }
                    }
                }
                sessionsTotal += sessions.size
                // Record the durable owner for every discovered session so a
                // search hit on any of them stays navigable — same sidecar
                // GlobalPrefetcher writes. (resolveSessionOwner falls back to
                // it when SessionsCache misses.)
                cache.recordOwners(server.id, agent, sessions)
                for (rs in sessions) {
                    if (rs.id in cachedIds) {
                        sessionsDone++
                        continue
                    }
                    onProgress(
                        Progress(
                            serversTotal = servers.size,
                            serversDone = serversDone,
                            sessionsTotal = sessionsTotal,
                            sessionsDone = sessionsDone,
                            skipped = skipped,
                            currentLabel = "${server.name}/${agent.name.lowercase()}: fetching ${rs.id.take(8)}",
                        )
                    )
                    val body = withContext(Dispatchers.IO) {
                        SilentlyTry.logged("SshAi-Indexer", "fetch session content for indexer") {
                            if (pooled != null) {
                                discovery.fetchSessionContent(rs.path) { cmd ->
                                    SilentlyTry.logged("SshAi-Indexer", "exec fetch on pooled") {
                                        val sess = pooled.startSession()
                                        try {
                                            val proc = sess.exec(cmd)
                                            val out = java.io.ByteArrayOutputStream()
                                            proc.inputStream.copyTo(out)
                                            proc.join(120, java.util.concurrent.TimeUnit.SECONDS)
                                            String(out.toByteArray(), Charsets.UTF_8)
                                        } finally { SilentlyTry.fired("SshAi-Indexer", "close fetch-exec session") { sess.close() } }
                                    }
                                }
                            } else {
                                discovery.fetchSessionContent(server, secrets, rs.path)
                            }
                        }
                    }
                    if (!body.isNullOrEmpty()) {
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        // Trim to last \n so partial-line tail doesn't
                        // pollute the cache.
                        val trimmed = ai.eight24family.conch.util.JsonlUtils.trimToLastNewline(bytes)
                        cache.save(rs.id, trimmed)
                        cachedIds.add(rs.id)
                    }
                    sessionsDone++
                    onProgress(
                        Progress(
                            serversTotal = servers.size,
                            serversDone = serversDone,
                            sessionsTotal = sessionsTotal,
                            sessionsDone = sessionsDone,
                            skipped = skipped,
                            currentLabel = "${server.name}/${agent.name.lowercase()}: ${rs.id.take(8)} ✓",
                        )
                    )
                }
            }
            serversDone++
        }

        onProgress(
            Progress(
                serversTotal = servers.size,
                serversDone = serversDone,
                sessionsTotal = sessionsTotal,
                sessionsDone = sessionsDone,
                skipped = skipped,
                currentLabel = "done · indexed $sessionsDone session(s), skipped $skipped server(s)",
            )
        )
    }
}
