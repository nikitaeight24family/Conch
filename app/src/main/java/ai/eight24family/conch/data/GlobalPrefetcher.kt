package ai.eight24family.conch.data

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.SessionDiscovery
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wakes up on app start (via [ServersViewModel.init]), walks every saved
 * server, and for each agent that's already known to be installed +
 * logged-in (per [AgentStatusCache]) it:
 *
 *   1. Re-lists CLI sessions on the host and refreshes [SessionsCache].
 *   2. For the top-N most-recent sessions whose JSONL we don't already
 *      have on disk, downloads it and stores in [HistoryCache].
 *
 * Effect: by the time the user taps a server → picks an agent → opens a
 * session, the list AND that session's full chat history are already
 * sitting in the local cache and paint instantly.
 *
 * Throttling: strictly sequential — one server at a time, one session
 * at a time. Each `discovery.fetchSessionContent` opens its own SSH
 * handshake (we don't share a channel here), so parallelism would
 * spike CPU on both ends and accomplish nothing.
 *
 * Cancellation: the job is held in [job], cancelled on [stop] (called
 * from `ServersViewModel.onCleared`). Also self-cancels gracefully on
 * any failure rather than aborting the whole sweep.
 */
class GlobalPrefetcher(
    private val repo: ServerRepository,
    private val agentStatusCache: AgentStatusCache,
    private val sessionsCache: SessionsCache,
    private val historyCache: HistoryCache,
    private val discovery: SessionDiscovery,
) {

    private var job: Job? = null

    /** PROCESS-scoped, NOT tied to the caller's [start] scope. The user
     * wants the cache/index sweep to keep running after they leave the
     * home screen / close search. A viewModelScope would cancel the
     * sweep on screen teardown; this SupervisorJob lives for the whole
     * process. */
    private val procScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var collectorStarted = false

    /** Caller's scope is ignored on purpose — see [procScope]. Kept in
     *  the signature so existing call sites (ServersViewModel) don't change. */
    fun start(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        triggerSweep()
        if (collectorStarted) return
        collectorStarted = true
        // Re-sweep whenever a server CONNECTS. SK servers can't be fetched
        // in the background (each handshake needs a FIDO touch), so the
        // startup sweep skips them and the user's chats stay un-indexed.
        // The moment the user tap-connects one (it lands in
        // pool.userHeldIds), re-run the sweep: prefetchOne now sees a live
        // pooled client for that server and fetches ALL its uncached
        // sessions over that connection — no extra touch. This is what
        // makes "load all" actually reach SK servers ("80 → all").
        procScope.launch {
            var prev = emptySet<String>()
            ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.userHeldIds.collect { ids ->
                if ((ids - prev).isNotEmpty()) {
                    android.util.Log.d(TAG, "connected: ${ids - prev} → re-sweep to fetch their sessions")
                    triggerSweep()
                }
                prev = ids
            }
        }
    }

    /** Launch a sweep, cancelling any in-flight/pending one and debouncing
     *  800 ms so a burst of connects coalesces into a single pass. The
     *  sweep is idempotent (size==0 filter skips already-cached), so a
     *  cancel-and-restart only re-lists, never re-downloads. */
    private fun triggerSweep() {
        job?.cancel()
        job = procScope.launch {
            delay(800)
            try {
                sweep(this)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "sweep failed: ${t.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun sweep(scope: CoroutineScope) {
        // Data-saver gate: skip the entire sweep when the user has
        // explicitly asked us to economise bandwidth. Each sweep
        // probes ~3 servers × 3 agents × (ls + cat) = ~20 SSH execs,
        // ~50-500 KB total. Nice quality-of-life when on Wi-Fi,
        // pure cost on mobile data.
        val dataSaver = SilentlyTry.loggedOrElse("SshAi-Prefetch", "read data saver pref", false) {
            ai.eight24family.conch.di.ServiceLocator.preferences.dataSaverEnabled.first()
        }
        // Data saver gates ONLY the expensive body downloads (MB per session),
        // NOT the cheap listing pass. The listing is what writes the durable
        // owner sidecars + SessionsCache that search navigation depends on —
        // skipping it entirely (old behaviour) is exactly why chats went
        // "serverless" in search and opened empty/offline. So we always list +
        // record owners; only the JSONL body prefetch is suppressed under data
        // saver.
        val fetchBodies = !dataSaver
        if (dataSaver) {
            android.util.Log.d(TAG, "data saver ON — listing owners only, skipping body downloads")
        }
        val servers = runCatching { repoListAll(repo) }.getOrElse { emptyList() }
        if (servers.isEmpty()) {
            android.util.Log.d(TAG, "no servers — nothing to prefetch")
            return
        }
        android.util.Log.d(TAG, "starting sweep over ${servers.size} server(s)")

        for (server in servers) {
            if (!scope.isActive) return
            val secrets = SilentlyTry.logged("SshAi-Prefetch", "read server secrets") { repo.getSecrets(server.id) } ?: continue

            suspend fun authorizedFromCache() =
                SilentlyTry.loggedOrElse("SshAi-Prefetch", "load agent status cache", emptyMap<Agent, ai.eight24family.conch.agent.AgentStatus>()) { agentStatusCache.load(server.id).statuses }
                    .filter { it.value.installed && it.value.loggedIn }.keys
            var authorizedAgents = authorizedFromCache()
            if (authorizedAgents.isEmpty()) {
                // Never probed yet — typically a server the user JUST added /
                // connected. We used to skip here and wait until they opened the
                // agent picker to probe; the user wants the gather to start the
                // INSTANT a server connects, with no navigation. So probe NOW
                // over the live connection (SK rides the pooled client → no extra
                // touch; non-SK handshakes), cache it, then fall through to fetch
                // its sessions.
                val isSk = secrets.skKeys.isNotEmpty()
                val exec = if (isSk) {
                    ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(server.id)?.let { buildPooledExec(it) }
                } else null
                if (isSk && exec == null) {
                    android.util.Log.d(TAG, "skip ${server.name}: SK + no live connection — can't probe in background")
                    continue
                }
                probeAgents(server, secrets, exec)
                authorizedAgents = authorizedFromCache()
                if (authorizedAgents.isEmpty()) {
                    android.util.Log.d(TAG, "skip ${server.name}: probe found no installed+logged-in agents")
                    continue
                }
            }

            for (agent in authorizedAgents) {
                if (!scope.isActive) return
                runCatching { prefetchOne(server, secrets, agent, fetchBodies) }
                    .onFailure { android.util.Log.w(TAG, "  ${server.name}/${agent.name} failed: ${it.message}") }
                delay(200)
            }
            delay(500)
        }
        android.util.Log.d(TAG, "sweep done")
        // The sweep just recorded durable owners (sidecars) for every session
        // each reachable server listed — including ones previously orphaned in
        // the search index (serverId null). Kick the indexer to re-resolve them
        // NOW: reconcile's serverId==null retry folds the fresh owners into the
        // index immediately instead of waiting for the next app launch.
        // Idempotent — no-op when nothing changed.
        ai.eight24family.conch.di.ServiceLocator.searchIndexer.reconcile()
    }

    private suspend fun prefetchOne(server: Server, secrets: ServerSecrets, agent: Agent, fetchBodies: Boolean = true) {
        // FIDO security-key servers can't open a fresh SSH in the
        // background (each handshake needs a physical user touch).
        // Two paths:
        //
        //   - The user has already done a tap-to-connect on this
        //     server (pool has a live client) → ride that connection
        //     for everything below. No extra touches, no new
        //     handshakes, just channels.
        //   - No live pool client → bail; the chat-open path will
        //     warm caches when the user actually visits.
        val isSk = secrets.skKeys.isNotEmpty()
        val pooledClient = if (isSk) {
            ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(server.id)
        } else null

        if (isSk && pooledClient == null) {
            android.util.Log.d(TAG, "  ${server.name}/${agent.name}: skipped (SK key, no live connection)")
            return
        }

        // Build an exec lambda: for SK servers with a live pool
        // client, pipe through it (no auth, no touch). For non-SK,
        // fall through to the existing fresh-handshake path.
        val pooledExec: (suspend (String) -> String?)? = pooledClient?.let { buildPooledExec(it) }
        val viaPool = if (pooledExec != null) " (via pooled SSH)" else ""

        // 1) List sessions and update the per-(server, agent) cache.
        val rawList = if (pooledExec != null) {
            discovery.list(agent, pooledExec)
        } else {
            discovery.list(server, secrets, agent)
        }
        // Record the durable owner for EVERY discovered session — including
        // the preview-blank ones we drop from the SessionsCache list just
        // below. A search hit can land on any cached session (the body has
        // searchable text even when the first user turn is blank), so the
        // owner map must cover the full set or that hit's tap is a silent
        // no-op. This is the automatic, no-button path: every home-screen
        // prefetch sweep refreshes ownership for all reachable servers.
        historyCache.recordOwners(server.id, agent, rawList)
        // Recover owners for sessions the server DELETED server-side (Claude
        // compaction) but still remembers in ~/.claude/history.jsonl: their rollout
        // file is gone (so the listing above misses them) yet we cached the body,
        // so search showed them serverless + opened them ownerless. history.jsonl
        // maps sessionId→project, proving they lived on THIS server — harvest it
        // and stamp the durable owner so the next reconcile attributes them.
        // Confirmed root cause of the user's — those ids are in 824's
        // history.jsonl. Pooled-exec only — both of the user's servers are SK.
        if (agent == Agent.CLAUDE && pooledExec != null) {
            SilentlyTry.fired(TAG, "harvest claude history.jsonl owners") {
                val cmd = "bash -lc " + ai.eight24family.conch.agent.shellEscape(
                    "cat \"\$HOME/.claude/history.jsonl\" 2>/dev/null",
                )
                pooledExec(cmd)?.takeIf { it.isNotBlank() }?.let { harvestClaudeHistoryOwners(server.id, it) }
            }
        }
        val list = rawList.filter { it.preview.isNotBlank() }
        if (list.isEmpty()) {
            android.util.Log.d(TAG, "  ${server.name}/${agent.name}: 0 sessions$viaPool")
            return
        }
        sessionsCache.save(server.id, agent, list)
        android.util.Log.d(TAG, "  ${server.name}/${agent.name}: cached ${list.size} session listing$viaPool")

        // 2) Prefetch JSONL bodies for EVERY session we don't already have on
        // disk — the user wants the search index to cover ALL chats, not a
        // recent-N slice. The size==0 filter keeps it incremental: each session
        // is fetched once, then skipped on later sweeps, so this isn't a
        // re-download every time. First sweep on a large history is long but
        // paced (150ms between fetches) and process-scoped, so it finishes
        // regardless of navigation. Everything above (list → recordOwners →
        // SessionsCache.save) is the cheap metadata pass that keeps search
        // navigation correct. The body downloads below are the expensive part —
        // suppressed under data saver.
        if (!fetchBodies) {
            val pending = list.count { historyCache.size(it.id) == 0L }
            if (pending > 0) {
                android.util.Log.d(TAG, "  ${server.name}/${agent.name}: data saver — skipped $pending body download(s)$viaPool")
            }
            return
        }
        val candidates = list.filter { historyCache.size(it.id) == 0L }
        for (s in candidates) {
            try {
                if (pooledClient != null) {
                    // Stream `cat` straight to the cache file — RAM stays flat
                    // regardless of session size. The old String path
                    // (fetch → toByteArray → trim) allocated 3-4× the file and
                    // OOM'd the read on a ~134 MB rollout (swallowed, but the
                    // session then went uncached → unsearchable).
                    val client = pooledClient
                    val cmd = discovery.catCommand(s.path)
                    val written = SilentlyTry.loggedOrElse(TAG, "stream session via pooled SSH", 0L) {
                        val sess = client.startSession()
                        try {
                            val proc = sess.exec(cmd)
                            val n = historyCache.saveFromStream(s.id, proc.inputStream)
                            proc.join(60, java.util.concurrent.TimeUnit.SECONDS)
                            n
                        } finally { SilentlyTry.fired(TAG, "close stream session") { sess.close() } }
                    }
                    if (written > 0L) {
                        android.util.Log.d(TAG, "    cached history ${s.id.take(8)} (${written}B, streamed)$viaPool")
                    }
                } else {
                    // Non-SK servers: no pooled client; fall back to the
                    // String fetch (these are typically smaller dev hosts).
                    val raw = discovery.fetchSessionContent(server, secrets, s.path) ?: continue
                    if (raw.isBlank()) continue
                    val bytes = raw.toByteArray(Charsets.UTF_8)
                    val safe = trimToLastNewline(bytes)
                    if (safe.isEmpty()) continue
                    historyCache.save(s.id, safe)
                    android.util.Log.d(TAG, "    cached history ${s.id.take(8)} (${safe.size}B)$viaPool")
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "    history ${s.id.take(8)} failed: ${t.message}")
            }
            delay(150)
        }
    }

    /** A `cmd -> stdout` lambda over an already-authenticated pooled client:
     *  opens a fresh channel per call (no auth, no FIDO touch), runs the
     *  command, returns its stdout. Shared by [probeAgents] and the session
     *  listing / body fetch in [prefetchOne] so SK servers never trigger a
     *  second touch for any background work. */
    private fun buildPooledExec(client: net.schmizz.sshj.SSHClient): suspend (String) -> String? = { cmd: String ->
        SilentlyTry.logged("SshAi-Prefetch", "exec on pooled client") {
            val sess = client.startSession()
            try {
                val proc = sess.exec(cmd)
                val out = java.io.ByteArrayOutputStream()
                proc.inputStream.copyTo(out)
                proc.join(60, java.util.concurrent.TimeUnit.SECONDS)
                String(out.toByteArray(), Charsets.UTF_8)
            } finally { SilentlyTry.fired("SshAi-Prefetch", "close exec session") { sess.close() } }
        }
    }

    /** First-contact agent probe for a freshly-connected server. Mirrors
     *  AgentPickerViewModelRefresh.runProbe* (fast presence probe → cache →
     *  live-auth merge → cache) but runs unattended in this background sweep so
     *  "add a server → know which agents are installed → load all sessions"
     *  needs ZERO navigation. The fast probe alone unblocks the session
     *  prefetch below (it only fetches for installed+logged-in agents); the
     *  live-auth pass then resolves OAuth rows from "checking" to ready/login
     *  without the user opening the picker. */
    private suspend fun probeAgents(
        server: Server,
        secrets: ServerSecrets,
        pooledExec: (suspend (String) -> String?)?,
    ) {
        val probe = ai.eight24family.conch.di.ServiceLocator.agentStatusProbe
        val resolved = (if (pooledExec != null) probe.probe(pooledExec) else probe.probe(server, secrets))
            .getOrNull() ?: run {
                android.util.Log.w(TAG, "  ${server.name}: first-contact agent probe failed")
                return
            }
        agentStatusCache.save(server.id, resolved)
        android.util.Log.d(TAG, "  ${server.name}: probed agents → installed=${resolved.filterValues { it.installed }.keys}")
        // Live-auth pass — only over a live channel (SK pooled exec). Resolves a
        // present-but-revoked OAuth cred so the overview doesn't sit on
        // "checking" or flash a false "OAuth" until a manual refresh. Same merge
        // as AgentPickerViewModelRefresh.kickLiveAuth, applied to the local map.
        val exec = pooledExec ?: return
        val authok = runCatching { probe.probeLiveAuth(exec) }.getOrDefault(emptyMap())
        val merged = resolved.mapValues { (agent, st) ->
            when (authok[agent]) {
                false -> {
                    val m = st.methods - setOf("oauth", "chatgpt")
                    st.copy(
                        methods = m,
                        loggedIn = m.isNotEmpty(),
                        activeMethod = st.activeMethod?.takeIf { it in m } ?: m.singleOrNull(),
                        liveAuthPending = false,
                    )
                }
                true -> st.copy(liveAuthPending = false)
                else -> if (st.liveAuthPending) st.copy(liveAuthPending = false) else st
            }
        }
        if (merged != resolved) agentStatusCache.save(server.id, merged)
    }

    // Claude's ~/.claude/history.jsonl: one JSON object per line, each carrying
    // sessionId + project (cwd) + timestamp. It persists EVERY session the CLI
    // ever ran on this host — including ones whose rollout file was later deleted
    // / compacted. That's the authoritative "this session lived here" record.
    private val histSidRe = Regex("\"sessionId\"\\s*:\\s*\"([0-9a-fA-F][0-9a-fA-F-]{7,})\"")
    private val histProjRe = Regex("\"project\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val histTsRe = Regex("\"timestamp\"\\s*:\\s*(\\d+)")

    /** Parse [raw] (a server's history.jsonl) and, for every session we've
     *  CACHED locally but have NO durable owner for yet, stamp the owner as
     *  ([serverId], CLAUDE, derived path). Idempotent + bounded: only fills the
     *  gaps (the orphans), never overwrites an existing owner, and only touches
     *  sessions whose body we actually hold. Reconcile's serverId==null retry
     *  then re-stamps the search index → the row gets its server + opens. */
    private fun harvestClaudeHistoryOwners(serverId: String, raw: String) {
        // sessionId → (project, newest timestamp ms)
        val latest = HashMap<String, Pair<String, Long>>()
        raw.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val sid = histSidRe.find(line)?.groupValues?.getOrNull(1) ?: return@forEach
            val proj = histProjRe.find(line)?.groupValues?.getOrNull(1).orEmpty()
            val ts = histTsRe.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val prev = latest[sid]
            if (prev == null || ts > prev.second) latest[sid] = proj to ts
        }
        var recovered = 0
        for ((sid, info) in latest) {
            // Only sessions whose body we actually cached (the searchable
            // orphans) and that don't already have an owner. Cheap-out fast.
            if (historyCache.size(sid) <= 0L) continue
            if (historyCache.owner(sid) != null) continue
            val (proj, tsMs) = info
            // Claude stores rollouts at ~/.claude/projects/<cwd-with-slashes→dashes>/<sid>.jsonl.
            // Derive it for re-fetch; harmless if the file is gone (cat returns
            // blank → cached body is shown, never overwritten).
            val path = if (proj.isNotBlank()) "~/.claude/projects/${proj.replace('/', '-')}/$sid.jsonl" else null
            historyCache.recordOwner(sid, serverId, Agent.CLAUDE, path, tsMs / 1000L)
            recovered++
        }
        if (recovered > 0) {
            android.util.Log.d(TAG, "harvested $recovered orphan owner(s) from history.jsonl on $serverId")
            // Re-stamp the search index NOW so the recovered server shows without
            // waiting for the next launch (reconcile's serverId==null retry folds
            // the fresh sidecars in). Idempotent.
            ai.eight24family.conch.di.ServiceLocator.searchIndexer.reconcile()
        }
    }

    private fun trimToLastNewline(bytes: ByteArray): ByteArray =
        ai.eight24family.conch.util.JsonlUtils.trimToLastNewline(bytes)

    /** ServerRepository doesn't expose a sync `listAll`, so we sip from
     *  observeServers's first emission — semantically identical here. */
    private suspend fun repoListAll(repo: ServerRepository): List<Server> =
        repo.observeServers().first()

    companion object {
        private const val TAG = "SshAi-Prefetch-Global"
    }
}
