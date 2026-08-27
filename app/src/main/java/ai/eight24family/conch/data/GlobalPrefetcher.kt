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
 * Throttling: servers run in PARALLEL (different hosts — a dead one must
 * not starve the rest), but work per host stays strictly sequential: one
 * agent at a time, one session at a time, so no single machine ever sees
 * a CPU/connection spike from us.
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

    /** One-shot latch for the fast tier's "I am running" line — see hotTailServer. */
    private val hotTailProofLogged = java.util.concurrent.atomic.AtomicBoolean(false)

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
            // The collector is a single coroutine for the whole process — an
            // exception inside `collect` (or thrown by triggerSweep) would kill
            // connect-triggered re-sweeps FOREVER with nothing in the UI to show
            // for it. Restart it instead; prev resets, so the first emission
            // after a restart re-sweeps once — idempotent, cheap.
            while (true) {
                try {
                    var prev = emptySet<String>()
                    ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.userHeldIds.collect { ids ->
                        if ((ids - prev).isNotEmpty()) {
                            android.util.Log.d(TAG, "connected: ${ids - prev} → re-sweep to fetch their sessions")
                            triggerSweep()
                        }
                        prev = ids
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    android.util.Log.w(TAG, "connect collector died: ${t.message} — restarting")
                    delay(5_000L)
                }
            }
        }
        // Periodic RE-LISTING. Re-list every 30s (90s under data saver) over
        // servers that already have a LIVE pooled client — one cheap exec per
        // (server, agent), no handshakes, no FIDO touches, no body downloads.
        // Listing-only, same policy as the sweep.
        procScope.launch {
            var wasForeground = false
            while (true) {
                try {
                    // NOTHING speculative while the app is off screen. procScope is
                    // process-wide ON PURPOSE (so a sweep survives screen teardown),
                    // but the foreground service keeps that process alive forever —
                    // so this loop used to re-list every 30s during a four-hour taxi
                    // ride with the app never opened. Skip the work, keep the loop
                    // alive so it resumes the moment the user comes back.
                    // ⚠ Only PREFETCH is gated. An open chat's tail-poll must keep
                    // running backgrounded — that is the point of the service.
                    if (!ai.eight24family.conch.util.AppForeground.isForeground) {
                        wasForeground = false
                        delay(5_000L)
                        continue
                    }
                    if (wasForeground) {
                        // A metered link counts as data-saver whether or not the user
                        // ever found the toggle — see NetworkCost. Re-read every tick
                        // so walking out of wifi tightens the loop immediately.
                        val dataSaver = ai.eight24family.conch.util.NetworkCost.isMetered() ||
                            SilentlyTry.loggedOrElse(TAG, "read data saver pref", false) {
                                ai.eight24family.conch.di.ServiceLocator.preferences.dataSaverEnabled.first()
                            }
                        delay(if (dataSaver) DATA_SAVER_RELIST_MS else FULL_RELIST_MS)
                        // Went off screen during the sleep — the top gate re-arms
                        // the immediate catch-up for the next return.
                        if (!ai.eight24family.conch.util.AppForeground.isForeground) continue
                    } else {
                        // BACKGROUND → FOREGROUND transition (or process start): the
                        // user is LOOKING at a list whose previews froze the moment
                        // the app left the screen (or the process died — same view).
                        // The old shape slept up to 30 s in the background gate plus
                        // a full 30/90 s tick before the first re-list — the user
                        // stared at a preview 8 minutes stale for over a minute
                        // (2026-08-19 00:56). Re-list NOW, delay after.
                        wasForeground = true
                        android.util.Log.d(TAG, "foreground return → immediate re-list")
                    }
                    SilentlyTry.fired(TAG, "periodic re-list") { relistConnected() }
                } catch (t: Throwable) {
                    // The loop IS the freshness mechanism — one unexpected throw
                    // (everything expected is SilentlyTry-wrapped already) must
                    // not kill previews for the rest of the process's life.
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    android.util.Log.w(TAG, "re-list tick failed: ${t.message}")
                    delay(30_000L)
                }
            }
        }
        // FAST TIER — the one the user actually watches. The full re-list above
        // rescans each host's session DIRECTORY (hundreds of files) and can only
        // run so often; but "what changed in the chats that are moving RIGHT
        // NOW" needs neither a directory scan nor a listing: it is one `stat` of
        // a handful of known paths plus a `tail` of whatever grew. That costs
        // almost nothing, so it runs every HOT_TAIL_MS while the app is on
        // screen.
        procScope.launch {
            while (true) {
                try {
                    if (!ai.eight24family.conch.util.AppForeground.isForeground) {
                        delay(3_000L)
                        continue
                    }
                    // Deltas are KB-sized, but a metered link is still the user's
                    // money — the sweep's thrift rule applies here too.
                    val thrift = ai.eight24family.conch.util.NetworkCost.isMetered() ||
                        SilentlyTry.loggedOrElse(TAG, "read data saver for hot tail", false) {
                            ai.eight24family.conch.di.ServiceLocator.preferences.dataSaverEnabled.first()
                        }
                    if (thrift) {
                        delay(DATA_SAVER_RELIST_MS)
                        continue
                    }
                    SilentlyTry.fired(TAG, "hot tail pass") { hotTailPass() }
                    delay(HOT_TAIL_MS)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    android.util.Log.w(TAG, "hot tail tick failed: ${t.message}")
                    delay(HOT_TAIL_MS * 3)
                }
            }
        }
    }

    /**
     * Catch up the mirrors of the sessions that are MOVING, as fast as the link
     * allows: one batched `stat` per server over the hot paths, then a `tail` of
     * every file that grew. No listing, no directory scan, no body downloads.
     *
     * "Hot" = the server's own last-activity says it ran within [HOT_WINDOW_MS],
     * or our mirror saw live output that recently. Capped at
     * [HOT_SESSIONS_PER_SERVER] newest-first, so a host with 235 sessions costs
     * the same as one with 5.
     */
    private suspend fun hotTailPass() {
        val servers = runCatching { repoListAll(repo) }.getOrElse { emptyList() }
        // Loop-alive proof, once per process. A fast tier that silently never
        // ran looks exactly like a quiet minute, and that ambiguity is what made
        // the last freshness bug so hard to see. It also names the reason it may
        // do nothing: no pooled client = no channel to ask over.
        if (hotTailProofLogged.compareAndSet(false, true)) {
            val live = servers.count {
                ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(it.id) != null
            }
            android.util.Log.d(
                TAG,
                "hot tail armed every ${HOT_TAIL_MS}ms — ${servers.size} server(s), $live with a live channel",
            )
        }
        kotlinx.coroutines.coroutineScope {
            for (server in servers) {
                launch { SilentlyTry.fired(TAG, "hot tail ${server.name}") { hotTailServer(server) } }
            }
        }
    }

    private suspend fun hotTailServer(server: Server) {
        val client = ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(server.id) ?: return
        val now = System.currentTimeMillis()
        val hot = ArrayList<ai.eight24family.conch.agent.RemoteSession>()
        for (agent in Agent.entries) {
            val snap = SilentlyTry.logged(TAG, "load listing for hot tail") {
                sessionsCache.load(server.id, agent)
            } ?: continue
            for (sess in snap.sessions) {
                // Only sessions we already mirror — a first fetch is the full
                // sweep's job (it owns sizes, caps and the tail-first path).
                if (historyCache.size(sess.id) <= 0L) continue
                val serverHot = now - sess.lastActiveAt * 1000L < HOT_WINDOW_MS
                val mirrorHot = now - historyCache.lastLiveActivityMs(sess.id) < HOT_WINDOW_MS
                if (serverHot || mirrorHot) hot += sess
            }
        }
        if (hot.isEmpty()) return
        val paths = hot.sortedByDescending { it.lastActiveAt }.take(HOT_SESSIONS_PER_SERVER)
        val quoted = paths.joinToString(" ") { ai.eight24family.conch.agent.shellEscapeRemotePath(it.path) }
        // ONE exec for every hot path: prints "<size> <path>" per line. GNU
        // form first, BSD second. No `wc -c` floor here on purpose — a host with
        // neither `stat` simply falls back to the 10 s full re-list, which is
        // still correct, and keeping this command free of shell $-expansion
        // keeps it free of Kotlin template escaping too.
val cmd = "stat -c '%s %n' " + quoted + " 2>/dev/null || " +
            "stat -f '%z %N' " + quoted + " 2>/dev/null"
        val out = buildPooledExec(client)(cmd) ?: return
        val sizeByPath = HashMap<String, Long>()
        out.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val sp = t.indexOf(' ')
            if (sp <= 0) return@forEach
            val size = t.substring(0, sp).trim().toLongOrNull() ?: return@forEach
            sizeByPath[t.substring(sp + 1).trim()] = size
        }
        if (sizeByPath.isEmpty()) return
        // Feed the shared delta-append path with FRESH sizes. It already handles
        // the tail-base offset, the open-chat poller race and the over-cap case.
        val fresh = paths.mapNotNull { s ->
            val size = sizeByPath[s.path] ?: return@mapNotNull null
            if (size == s.sizeBytes) null else s.copy(sizeBytes = size)
        }
        if (fresh.isEmpty()) {
            android.util.Log.d(TAG, "hot tail ${server.name}: ${paths.size} hot, none grew")
            return
        }
        appendGrownBodies(client, fresh, allowRetail = false)
        // Bump the activity store for what grew: the home list collects its
        // `changes` flow and reloads AT ONCE, so the row moves and its preview
        // updates inside the same second instead of on the next 2.5 s tick.
        for (s in fresh) {
            ai.eight24family.conch.di.ServiceLocator.sessionActivity
                .observeRemote(server.id, s.id, maxOf(s.lastActiveAt * 1000L, now))
        }
        android.util.Log.d(TAG, "hot tail ${server.name}: ${fresh.size}/${paths.size} grew")
    }

    /** Listing-only refresh for every server with a live pooled client: updates
     *  SessionsCache + durable owners so server-created sessions surface without
     *  an app restart. Never opens a new connection (skip when no pooled client)
     *  and never downloads bodies — the full sweep handles those. */
    private suspend fun relistConnected() {
        val servers = runCatching { repoListAll(repo) }.getOrElse { emptyList() }
        // Per-SERVER parallelism; each server's own work stays strictly
        // sequential. Serial order let one dead host starve everyone: 824 with a
        // hung pooled client burned 30 s of exec timeout per agent BEFORE the
        // loop even reached Home, so the visible list's previews froze for
        // minutes over a server the user wasn't looking at (2026-08-19).
        // Different servers are different hosts — parallelism never stacks load
        // on one machine.
        kotlinx.coroutines.coroutineScope {
            for (server in servers) {
                launch {
                    SilentlyTry.fired(TAG, "re-list ${server.name}") { relistServer(server) }
                }
            }
        }
    }

    private suspend fun relistServer(server: Server) {
        val client = ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(server.id) ?: return
        val exec = buildPooledExec(client)
        // Piggy-back a periodic agent RE-probe on the live channel. The
        // connect-triggered probe was the ONLY refresher, and the service
        // keeps transports alive for days — so versions / latest / run-state
        // on the Agents tab froze at connect time and only caught up when
        // the user opened the tab and its panel probed in front of them.
        // Every AGENT_REPROBE_MS per server, one exec, no handshake; the tab
        // observes the cache and repaints on its own.
        val snapshot = SilentlyTry.logged(TAG, "load status cache for reprobe") {
            agentStatusCache.load(server.id)
        }
        val lastProbe = snapshot?.lastCheckedAt ?: 0L
        if (System.currentTimeMillis() - lastProbe >= AGENT_REPROBE_MS) {
            SilentlyTry.fired(TAG, "periodic agent re-probe ${server.name}") {
                val probe = ai.eight24family.conch.di.ServiceLocator.agentStatusProbe
                probe.probe(exec, onOs = { os -> agentStatusCache.saveServerOs(server.id, os.name) })
                    .getOrNull()?.let { agentStatusCache.save(server.id, it) }
            }
        }
        val agents = (snapshot?.statuses ?: emptyMap())
            .filter { it.value.installed && it.value.loggedIn }.keys
        for (agent in agents) {
            val rawList = SilentlyTry.logged(TAG, "periodic list ${server.name}/${agent.name}") {
                discovery.list(agent, key = "${server.id}:${agent.name}", exec = exec)
            } ?: continue
            if (rawList.isEmpty()) continue
            historyCache.recordOwners(server.id, agent, rawList)
            val list = rawList.filter { it.preview.isNotBlank() }
            if (list.isNotEmpty()) sessionsCache.save(server.id, agent, list)
            // BACKGROUND MIRROR CATCH-UP. The cache used to grow ONLY inside
            // an OPEN chat's tail-poll (the sweep fetches a body exactly once,
            // then never again) — so the unread badge, the done-✓ and the row
            // preview stayed frozen until the user entered the session. Append
            // the DELTA for grown, already- cached sessions right here on the
            // live channel: bounded per session and per sweep, byte-exact (raw
            // stream + newline trim — a String decode could split a multibyte
            // char and corrupt the cache), and skipped when an open chat's
            // poller races us. Re-tails (2 MB each) take the SAME posture as
            // the body sweep: foreground is already implied by the loop's
            // gate; metered / data-saver forbids them, the ≤512 KB deltas stay
            // allowed.
            val allowRetail = !ai.eight24family.conch.util.NetworkCost.isMetered() &&
                !SilentlyTry.loggedOrElse(TAG, "read data saver for re-tail", true) {
                    ai.eight24family.conch.di.ServiceLocator.preferences.dataSaverEnabled.first()
                }
            appendGrownBodies(client, list, allowRetail)
        }
    }

    /** One sweep's incremental cache catch-up for [list] — see call site.
     *  [allowRetail] gates the 2 MB re-tail (foreground + unmetered + no data
     *  saver — the same posture as the body sweep); the ≤512 KB deltas keep
     *  their original any-network allowance. */
    private fun appendGrownBodies(
        client: net.schmizz.sshj.SSHClient,
        list: List<ai.eight24family.conch.agent.RemoteSession>,
        allowRetail: Boolean = false,
    ) {
        var appended = 0
        var retailed = 0
        for (s in list) {
            if (appended >= APPEND_SESSIONS_PER_SWEEP) return
            val remote = s.sizeBytes ?: continue
            val cached = historyCache.size(s.id)
            // TAIL-BASE AWARE: local length is a remote offset only after adding
            // the .base origin (0 for complete mirrors — the common case).
            val base = historyCache.baseOffset(s.id)
            val remoteOff = base + cached
            // Only sessions we already hold (first fetch is the full sweep's
            // job) that GREW; a shrink (compaction) is the chat-open re-adopt's.
            if (cached <= 0L || remote <= remoteOff) continue
            if (remote - remoteOff > APPEND_DELTA_CAP_BYTES) {
                // Over-cap growth used to freeze the mirror until the user
                // opened the chat. RE-TAIL instead (bounded: the display tail,
                // few per sweep, wifi+foreground only): the badge/preview/done
                // mark stay live however fast the session churns.
                // ⚠ Never under an ACTIVE mirror: an open chat's poller tracks
                // its own remote offset, and replacing the file beneath it
                // desyncs that offset into duplicate appends. A mirror written
                // in the last few minutes is being tended by someone — and a
                // poller-tended mirror can't fall this far behind anyway, so
                // the guard costs nothing in coverage.
                val locallyIdle = System.currentTimeMillis() -
                    historyCache.lastWriteMs(s.id) > RETAIL_MIN_LOCAL_IDLE_MS
                if (allowRetail && locallyIdle && retailed < RETAILS_PER_SWEEP) {
                    val ok = tailFirstPreload(client, s, remote)
                    if (ok) retailed++
                    android.util.Log.d(
                        TAG,
                        "  delta ${s.id.take(8)} ${(remote - remoteOff)}B over cap — " +
                            if (ok) "re-tailed" else "re-tail failed",
                    )
                } else {
                    android.util.Log.d(
                        TAG,
                        "  delta ${s.id.take(8)} ${(remote - remoteOff)}B over catch-up cap — chat open will stream it",
                    )
                }
                continue
            }
            SilentlyTry.fired(TAG, "append grown body ${s.id.take(8)}") {
                val q = ai.eight24family.conch.agent.shellEscapeRemotePath(s.path)
                val sess = client.startSession()
                val bytes = try {
                    val proc = sess.exec("tail -c +${remoteOff + 1} $q")
                    val out = java.io.ByteArrayOutputStream()
                    // Bounded read: the deadline wraps the READ, not the join after it.
                    ai.eight24family.conch.ssh.BoundedExec.drain(
                        proc, out,
                        deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.TRANSFER_MS,
                        maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.TRANSFER,
                    )
                    proc.join(30, java.util.concurrent.TimeUnit.SECONDS)
                    out.toByteArray()
                } finally {
                    SilentlyTry.fired(TAG, "close append session") { sess.close() }
                }
                if (bytes.isEmpty()) return@fired
                val safe = trimToLastNewline(bytes)
                if (safe.isEmpty()) return@fired
                // An open chat's tail-poll may have appended while we fetched —
                // our delta's offset is then stale; appending would duplicate.
                // (Base can only move via saveTail/save, both of which also
                // change the size, so the size check covers it.)
                if (historyCache.size(s.id) != cached) return@fired
                // Live activity ONLY when the server itself says the session is
                // hot right now — catching up a session that went idle hours ago
                // is mirror housekeeping, and counting it lit the home list's
                // "working" spinner on long-dead sessions (2026-08-17).
                val serverHot =
                    System.currentTimeMillis() - s.lastActiveAt * 1000L < 90_000L
                historyCache.append(s.id, safe, liveActivity = serverHot)
                appended++
                android.util.Log.d(TAG, "  caught up ${s.id.take(8)} +${safe.size}B (background mirror)")
            }
        }
    }

    /**
     * Cache ONLY the last [TAIL_PRELOAD_BYTES] of a remote session — the
     * tail-first path for rollouts over the prefetch cap, and the re-tail for
     * cached ones that grew past the delta cap. The slab starts mid-record, so
     * the leading partial line is dropped and the .base origin advanced to the
     * first kept byte ([HistoryCache.saveTail] then owns atomicity + the seen
     * rebase). Never marks live activity. Returns success.
     */
    private fun tailFirstPreload(
        client: net.schmizz.sshj.SSHClient,
        s: ai.eight24family.conch.agent.RemoteSession,
        remoteBytes: Long,
    ): Boolean {
        val start = (remoteBytes - TAIL_PRELOAD_BYTES).coerceAtLeast(0L)
        val slab = SilentlyTry.loggedOrElse<ByteArray?>(TAG, "fetch tail slab ${s.id.take(8)}", null) {
            val q = ai.eight24family.conch.agent.shellEscapeRemotePath(s.path)
            val sess = client.startSession()
            try {
                val proc = sess.exec("tail -c +${start + 1} $q")
                val out = java.io.ByteArrayOutputStream()
                // Bounded read: the deadline wraps the READ, not the join after it.
                ai.eight24family.conch.ssh.BoundedExec.drain(
                    proc, out,
                    deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.TRANSFER_MS,
                    maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.TRANSFER,
                )
                proc.join(60, java.util.concurrent.TimeUnit.SECONDS)
                out.toByteArray()
            } finally {
                SilentlyTry.fired(TAG, "close tail slab session") { sess.close() }
            }
        } ?: return false
        if (slab.isEmpty()) return false
        val (aligned, dropped) = dropLeadingPartialLine(slab, isFileStart = start == 0L)
        val safe = trimToLastNewline(aligned)
        if (safe.isEmpty()) return false
        historyCache.saveTail(s.id, safe, newBase = start + dropped)
        return true
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
                // A cancel is the normal debounce path (a newer trigger took
                // over, or the screen died) — logging it as "failed" made the
                // log read like the sweep machinery broke (2026-08-19 00:56).
                if (t is kotlinx.coroutines.CancellationException) throw t
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
        // The comment above already said body prefetch is "pure cost on mobile
        // data" — but nothing ever CHECKED for mobile data. dataSaverEnabled was
        // a manual toggle defaulting to off, so a backgrounded app on cellular
        // kept pulling MB-sized session bodies; four hours in a taxi ate a whole
        // monthly plan (user, 2026-07-23). Metered now implies data-saver, and
        // the toggle remains for forcing thrift on unmetered links too.
        val metered = ai.eight24family.conch.util.NetworkCost.isMetered()
        val dataSaver = metered ||
            SilentlyTry.loggedOrElse("SshAi-Prefetch", "read data saver pref", false) {
                ai.eight24family.conch.di.ServiceLocator.preferences.dataSaverEnabled.first()
            }
        // Data saver gates ONLY the expensive body downloads (MB per session),
        // NOT the cheap listing pass. The listing is what writes the durable
        // owner sidecars + SessionsCache that search navigation depends on —
        // skipping it entirely (old behaviour) is exactly why chats went
        // "serverless" in search and opened empty/offline. So we always list +
        // record owners; only the JSONL body prefetch is suppressed under data
        // saver. Bodies are the expensive, purely speculative half (MB per
        // session). They require BOTH a cheap link AND the user actually being
        // here: a sweep triggered by e.g. a server reconnecting must not start
        // pulling megabytes while the phone is in a pocket.
        val onScreen = ai.eight24family.conch.util.AppForeground.isForeground
        val fetchBodies = !dataSaver && onScreen
        if (dataSaver) {
            android.util.Log.d(
                TAG,
                "data saver ON (metered=$metered) — listing owners only, skipping body downloads",
            )
        }
        val servers = runCatching { repoListAll(repo) }.getOrElse { emptyList() }
        if (servers.isEmpty()) {
            android.util.Log.d(TAG, "no servers — nothing to prefetch")
            return
        }
        // Always state the network cost, not just when thrift kicks in — this is
        // the line that proves on-device whether the metered gate engaged.
        android.util.Log.d(
            TAG,
            "starting sweep over ${servers.size} server(s) " +
                "metered=$metered onScreen=$onScreen bodies=$fetchBodies",
        )

        // Per-SERVER parallelism, same rationale as [relistConnected]: one
        // unreachable host's exec timeouts (30 s each, × agents) used to hold
        // every other server's listing hostage — on 2026-08-19 a cold start took
        // 46 s to list the server the user was actually looking at because a
        // dead one sat first in the loop. Per-HOST work stays strictly
        // sequential inside [sweepServer]; different servers are different
        // machines, so nothing stacks.
        kotlinx.coroutines.coroutineScope {
            for (server in servers) {
                launch { sweepServer(this, server, fetchBodies) }
            }
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

    /** One server's full sweep — probe-if-unknown, fast listing pass, then the
     *  slow catalog/body pass. Extracted verbatim from [sweep]'s old serial
     *  loop so servers can run in parallel. */
    private suspend fun sweepServer(scope: CoroutineScope, server: Server, fetchBodies: Boolean) {
        if (!scope.isActive) return
        val secrets = SilentlyTry.logged("SshAi-Prefetch", "read server secrets") { repo.getSecrets(server.id) } ?: return

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
            // its sessions. Pooled transport only — a background probe must
            // not open a fresh handshake for ANY auth kind (see
            // listServerAgent).
            val exec = ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(server.id)
                ?.let { buildPooledExec(it) }
            if (exec == null) {
                android.util.Log.d(TAG, "skip ${server.name}: no live pooled connection — can't probe in background")
                return
            }
            probeAgents(server, secrets, exec)
            authorizedAgents = authorizedFromCache()
            if (authorizedAgents.isEmpty()) {
                android.util.Log.d(TAG, "skip ${server.name}: probe found no installed+logged-in agents")
                return
            }
        }

        // PASS 1 — list every agent FAST (no catalog, no bodies) so the home
        // list paints ALL agents' session titles together and almost at once,
        // instead of trickling in per-agent behind each agent's slow catalog /
        // body work.
        val listed = LinkedHashMap<Agent, List<ai.eight24family.conch.agent.RemoteSession>>()
        for (agent in authorizedAgents) {
            if (!scope.isActive) return
            val l = runCatching { listServerAgent(server, secrets, agent) }
                .onFailure { android.util.Log.w(TAG, "  ${server.name}/${agent.name} list failed: ${it.message}") }
                .getOrNull()
            if (l != null) listed[agent] = l
        }
        // PASS 2 — the slow part: model-catalog warm-up + JSONL body downloads,
        // reusing pass-1's listing (no re-list).
        for (agent in authorizedAgents) {
            if (!scope.isActive) return
            runCatching { fetchBodiesAndCatalog(server, secrets, agent, listed[agent].orEmpty(), fetchBodies) }
                .onFailure { android.util.Log.w(TAG, "  ${server.name}/${agent.name} bodies failed: ${it.message}") }
            delay(200)
        }
    }

    /** FAST listing pass for one (server, agent): list sessions, record durable
     * owners for ALL of them, harvest Claude's history.jsonl orphans, and cache
     * the non-blank listing so the home list paints titles immediately. NO model
     * catalog and NO body downloads — those are the slow part
     * ([fetchBodiesAndCatalog]), split out on purpose so every agent's titles
     * surface together and fast instead of trickling in behind each agent's heavy
     * work. Returns the cached (non-blank) list, or null when an SK server has no
     * live pooled connection. */
    private suspend fun listServerAgent(
        server: Server, secrets: ServerSecrets, agent: Agent,
    ): List<ai.eight24family.conch.agent.RemoteSession>? {
        // POOLED TRANSPORT ONLY — for every auth flavor, not just SK. Non-SK
        // servers used to fall back to a FRESH handshake per (server, agent)
        // here, and per session body below — but connectAllPossibleSilently has
        // already dialed every dialable server, so "no pooled client" means
        // that connect FAILED (or the user disconnected on purpose): redialing
        // per-file from a background sweep is the connection storm the user's
        // fail2ban was banning, for a purely speculative fetch. The chat-open
        // path still warms anything we skip here.
        val pooledClient = ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(server.id)
        if (pooledClient == null) {
            android.util.Log.d(TAG, "  ${server.name}/${agent.name}: skipped (no live pooled connection)")
            return null
        }
        val pooledExec: suspend (String) -> String? = buildPooledExec(pooledClient)
        val viaPool = " (via pooled SSH)"

        val rawList = discovery.list(agent, key = "${server.id}:${agent.name}", exec = pooledExec)
        // Record the durable owner for EVERY discovered session — including the
        // preview-blank ones dropped from the SessionsCache list below. A search
        // hit can land on any cached session (its body has searchable text even
        // when the first user turn is blank), so the owner map must cover the full
        // set or that tap is a silent no-op.
        historyCache.recordOwners(server.id, agent, rawList)
        // Recover owners for sessions the server DELETED server-side (Claude
        // compaction) but still remembers in ~/.claude/history.jsonl: it maps
        // sessionId→project, proving they lived on THIS server, so search can
        // attribute + open them.
        if (agent == Agent.CLAUDE) {
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
            return list
        }
        sessionsCache.save(server.id, agent, list)
        android.util.Log.d(TAG, "  ${server.name}/${agent.name}: cached ${list.size} session listing$viaPool")
        return list
    }

    /** SLOW pass for one (server, agent): model + reasoning catalog warm-up (so a
     *  chat opens onto a READY picker) and JSONL body downloads for search. Takes
     *  the [list] already produced + cached by [listServerAgent] so it NEVER
     *  re-lists. Body downloads are suppressed under data saver. */
    private suspend fun fetchBodiesAndCatalog(
        server: Server, secrets: ServerSecrets, agent: Agent,
        list: List<ai.eight24family.conch.agent.RemoteSession>, fetchBodies: Boolean,
    ) {
        // Pooled transport only — same rule (and rationale) as [listServerAgent]:
        // a background sweep must never open fresh handshakes, for any auth kind.
        val pooledClient = ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(server.id) ?: return
        val viaPool = " (via pooled SSH)"

        // MIRROR CATCH-UP AT SWEEP TIME — the same delta append the periodic
        // re-list does. The startup/connect sweep only LISTED; a cached session
        // that grew while the process was dead or backgrounded stayed stale
        // until the periodic loop's first pass reached this server, so a cold
        // start stared at an 8-minute-old preview for over a minute
        // (2026-08-19 00:56). Foreground-gated like all speculative work; the
        // ≤512 KB deltas keep their any-network allowance, the 2 MB re-tail
        // takes the body posture ([fetchBodies] = on-screen + unmetered).
        if (ai.eight24family.conch.util.AppForeground.isForeground) {
            appendGrownBodies(pooledClient, list, allowRetail = fetchBodies)
        }

        // Model + reasoning catalog warm-up — the chat-open probe skips its heavy
        // PTY pass while this is fresh.
        val catalogClient = pooledClient
        // ⚠ GATED like everything else speculative. This is the HEAVIEST thing
        // the sweep does — it allocates a PTY and spawns a full interactive
        // `claude` on the server — and it sat ABOVE the fetchBodies guard, so it
        // ran on a metered link and with the app off screen, which is exactly
        // what the taxi-ride bill was about. Same flag as the bodies.
        if (fetchBodies && catalogClient != null && !ModelCatalogPrefetcher.isFresh(server.id, agent)) {
            SilentlyTry.fired(TAG, "model catalog warm-up") {
                ModelCatalogPrefetcher.probeAndPersist(catalogClient, agent, server.id)
            }
        }

        if (list.isEmpty()) return
        // Prefetch JSONL bodies for the search index — incremental: each session's
        // body is fetched once (size==0 filter), then skipped on later sweeps.
        // Suppressed under data saver (bodies are the MBs; the listing above is the
        // cheap metadata search navigation depends on).
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
                run {
                    // Stream `cat` straight to the cache file — RAM stays flat
                    // regardless of session size. The old String path
                    // (fetch → toByteArray → trim) allocated 3-4× the file and
                    // OOM'd the read on a ~134 MB rollout (swallowed, but the
                    // session then went uncached → unsearchable).
                    val client = pooledClient
                    // ASK THE SIZE FIRST. Background prefetch used to `cat` every
                    // uncached session whole; with 29 sessions and rollouts in the
                    // hundreds of MB that is gigabytes of pure speculation — 3 GB
                    // measured in 4 hours, and note that was on WIFI. Metering is
                    // beside the point: the bytes are wasted on any network, this
                    // is just where the user happened to notice the bill.
                    // One `stat` costs bytes, not megabytes; oversized sessions
                    // stay uncached and are fetched when the user actually opens
                    // one (that path streams + gzips already).
                    val remoteBytes = SilentlyTry.loggedOrElse<Long?>(TAG, "stat session size", null) {
                        val sess = client.startSession()
                        try {
                            // `stat -c` is GNU; BSD/macOS wants `stat -f %z`, and
                            // BusyBox may have neither. Try both, then `wc -c` as
                            // the portable last resort — otherwise the size comes
                            // back null on those hosts and the cap silently stops
                            // capping, which is the failure mode we were fixing.
                            val q = ai.eight24family.conch.agent.shellEscapeRemotePath(s.path)
                            val p = sess.exec(
                                "stat -c %s $q 2>/dev/null || stat -f %z $q 2>/dev/null || wc -c < $q 2>/dev/null",
                            )
                            val txt = p.inputStream.bufferedReader().readText().trim()
                            p.join(15, java.util.concurrent.TimeUnit.SECONDS)
                            txt.toLongOrNull()
                        } finally { SilentlyTry.fired(TAG, "close stat session") { sess.close() } }
                    }
                    if (remoteBytes != null && remoteBytes > PREFETCH_BODY_MAX_BYTES) {
                        // TAIL-FIRST (2026-08-17): a session over the cap used to
                        // stay entirely uncached — «loading» on open, invisible
                        // to search, no badge — and the first open then paid for
                        // the WHOLE rollout. Cache just the display tail instead:
                        // bounded bytes, instant open, and the open path knows
                        // the head is missing via the .base sidecar.
                        val ok = tailFirstPreload(client, s, remoteBytes)
                        android.util.Log.d(
                            TAG,
                            "    tail-first ${s.id.take(8)} — ${remoteBytes}B over cap, " +
                                if (ok) "cached last $TAIL_PRELOAD_BYTES B" else "tail fetch failed (retry next sweep)",
                        )
                        continue
                    }
                    val cmd = ai.eight24family.conch.agent.RemoteEnv.portable(discovery.catCommand(s.path))
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
                }
                // (The old non-pooled String-fetch branch is gone: a fresh
                // handshake PER SESSION FILE from a background sweep was the
                // worst of the fail2ban feeders — 80 uncached sessions meant 80
                // connect+auth+disconnect cycles at 150 ms spacing.)
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
                // Chokepoint: session-listing scripts are `bash -lc` and rode
                // this transport raw — on a bash-less host every background
                // sweep read "no sessions" with no error anywhere.
                val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
                val out = java.io.ByteArrayOutputStream()
                // Bounded read: the deadline wraps the READ, not the join after it.
                ai.eight24family.conch.ssh.BoundedExec.drain(
                    proc, out,
                    deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.INTERACTIVE_MS,
                    maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.INTERACTIVE,
                )
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
        val resolved = (
            if (pooledExec != null) {
                probe.probe(pooledExec, onOs = { os -> agentStatusCache.saveServerOs(server.id, os.name) })
            } else probe.probe(server, secrets)
            )
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
        //
        // Runs ASYNC (procScope), NOT inline: it spawns CLIs to verify tokens and
        // Gemini's check alone can take ~25s (`timeout 25 gemini …`). Blocking here
        // held the FIRST session listing on a freshly-added server hostage to that
        // wait. The fast probe above is already saved, so the caller lists
        // immediately on installed+loggedIn; this only REFINES the badge (drops a
        // revoked OAuth) and re-saves — the overview observes the cache and updates
        // in place. Codex has no live check, so its chatgpt badge is never touched
        // here (the statusProbe verdict stands).
        val exec = pooledExec ?: return
        procScope.launch {
            val authok = runCatching { probe.probeLiveAuth(exec) }.getOrDefault(emptyMap())
            if (authok.isEmpty()) return@launch
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

        /**
         * Ceiling on a SPECULATIVE body download. Prefetch exists to make search
         * work and to open a chat instantly — neither needs a 100 MB rollout
         * pulled down on the off-chance. Above this the session stays uncached
         * until the user actually opens it.
         *
         * Matches ChatViewModelTailPoll.BIG_FILE_STREAM_BYTES: past that point a
         * session is already treated as "big" everywhere else in the app.
         */
        internal const val PREFETCH_BODY_MAX_BYTES: Long = 4_000_000L

        /** How stale a server's agent-status snapshot may get before the
         *  periodic re-list piggy-backs a re-probe on the live channel.
         *  Versions/run-state drift on the scale of hours, and each probe
         *  spawns CLIs server-side — 6 h keeps the Agents tab honest without
         *  measurable cost. */
        private const val AGENT_REPROBE_MS: Long = 6L * 60 * 60 * 1000

        /** Background mirror catch-up bounds (see appendGrownBodies): at most
         *  this many session deltas per (server, agent) per 30 s sweep, and a
         *  delta bigger than the cap is left for the chat-open streaming path
         *  (which gzips). Keeps the background cost strictly proportional to
         *  real growth — a busy turn writes a few KB/s, well inside this. */
        private const val APPEND_SESSIONS_PER_SWEEP = 24
        private const val APPEND_DELTA_CAP_BYTES = 2L * 1024 * 1024

        /** Full re-listing cadence while the app is on screen. Was 30 s — which
         *  is how long the user could sit watching a list he KNEW had moved. A
         *  listing is one exec per (server, agent) over an already-open channel;
         *  the expensive half (bodies) is not on this path. */
        private const val FULL_RELIST_MS = 10_000L
        private const val DATA_SAVER_RELIST_MS = 90_000L

        /** Fast tier: `stat` + `tail` over the sessions that are actually
         *  moving. No directory scan, no listing — cheap enough to run
         *  near-continuously while someone is looking at the list. */
        private const val HOT_TAIL_MS = 2_000L
        private const val HOT_WINDOW_MS = 10L * 60 * 1000
        private const val HOT_SESSIONS_PER_SERVER = 12

        /** How much of a big session the tail-first path mirrors — the same
         *  window the chat's open path displays (ChatViewModel's
         *  DISPLAY_TAIL_BYTES), so an instant open never shows less than a
         *  full-fetch open would have. */
        internal const val TAIL_PRELOAD_BYTES = 2L * 1024 * 1024

        /** Re-tails per sweep — each is a full [TAIL_PRELOAD_BYTES] transfer,
         *  so the bound is bytes, not politeness. */
        internal const val RETAILS_PER_SWEEP = 4

        /** A mirror written more recently than this is considered actively
         *  tended (open chat's poller) — re-tailing under it would desync the
         *  poller's remote offset. See the re-tail guard in appendGrownBodies. */
        internal const val RETAIL_MIN_LOCAL_IDLE_MS = 5L * 60 * 1000

        /** A `tail -c` slab that does not begin at byte 0 starts mid-record —
         *  half a record is not JSONL. Returns (whole-line bytes, bytes
         *  dropped from the front). Extracted pure for the unit test. */
        internal fun dropLeadingPartialLine(slab: ByteArray, isFileStart: Boolean): Pair<ByteArray, Long> {
            if (isFileStart) return slab to 0L
            val nl = slab.indexOfFirst { it == '\n'.code.toByte() }
            if (nl < 0) return ByteArray(0) to slab.size.toLong()
            return slab.copyOfRange(nl + 1, slab.size) to (nl + 1).toLong()
        }
    }
}
