package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.RemoteSession
import ai.eight24family.conch.data.MemoryService
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionsViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    val agent: Agent = Agent.valueOf(checkNotNull(savedStateHandle["agent"]))

    private val repo = ServiceLocator.serverRepository
    private val discovery = ServiceLocator.sessionDiscovery
    private val cache = ServiceLocator.sessionsCache
    private val historyCache = ServiceLocator.historyCache

    /** Record the durable owner for EVERY session the server returns — BEFORE
     * the `preview.isNotBlank()` filter. The list we SHOW drops blank-preview
     * rows (/resume, system caveats, single-word chats…), but those sessions
     * still exist on the server and are searchable, so their (server, agent,
     * path, date) MUST be logged or a search hit on them resolves to nothing.
     * This was the actual root cause: every listing path filtered BEFORE
     * recording ownership, so weak-preview sessions were cached + indexed but
     * never owned. Idempotent. */
    private fun ownAll(raw: List<RemoteSession>) {
        ai.eight24family.conch.util.SilentlyTry.fired("SshAi-Sessions", "record durable owners (full listing)") {
            historyCache.recordOwners(serverId, agent, raw)
        }
        // The server just told us exactly which sessions exist FOR THIS AGENT.
        // A tombstone whose session is no longer listed is a confirmed delete and
        // can go; one the server still reports (rm pending/failed) is kept so the
        // row stays hidden.
        //
        // WARNING: SCOPE IT TO THIS AGENT. The tombstone key is
        // "<serverId>:<sessionId>" with no agent in it, and this listing covers
        // one agent — so the old server-wide prune dropped tombstones belonging to
        // the OTHER agents on the same host, and their deleted chats came back.
        // The durable owner sidecar knows whose session an id is; an id we cannot
        // attribute is left alone (it stays hidden, and the delete is retried by
        // the reconcile paths).
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val present = raw.mapTo(HashSet()) { it.id }
                val prefix = "$serverId:"
                val confirmed = _deletedIds.value.mapNotNull { id ->
                    if (id in present) return@mapNotNull null
                    val owner = SilentlyTry.logged("SshAi-Sessions", "owner for tombstone prune") {
                        historyCache.owner(id)
                    }
                    if (owner?.agent != agent) return@mapNotNull null
                    prefix + id
                }.toSet()
                ServiceLocator.preferences.clearDeletedSessionTombstones(confirmed)
            }
        }
    }

    /** Carry a known size forward when a fresh listing row lacks one — NEVER
     *  blank a size we already had (user's rule: show old until new arrives,
     *  always keep the cache). Matched by (id + path). No-op in the normal case
     *  where every fresh row already carries its size. */
    private fun mergeSizes(fresh: List<RemoteSession>): List<RemoteSession> {
        if (fresh.all { it.sizeBytes != null }) return fresh
        val prior = _sessions.value.associateBy { it.id + "·" + it.path }
        return fresh.map { f ->
            if (f.sizeBytes != null) f
            else prior[f.id + "·" + f.path]?.sizeBytes?.let { f.copy(sizeBytes = it) } ?: f
        }
    }

    /** Session ids (this server) the user swipe-deleted. Loaded from persisted
     *  tombstones in init; a deleted session is filtered out of every list we
     *  publish until a fresh server listing confirms it's gone (then the
     *  tombstone is pruned). Fixes "deleted session reappears". */
    private val _deletedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Publish a freshly-loaded list MINUS user-deleted (tombstoned) sessions,
     *  so a delete sticks across refreshes, re-entry, and app restarts even if
     *  an in-flight listing or a not-yet-landed server `rm` would resurface it. */
    private fun publishSessions(list: List<RemoteSession>) {
        val tomb = _deletedIds.value
        // Dedupe by session id — the per-agent list keys its LazyColumn by id and
        // Compose hard-crashes on a duplicate ("Key … was already used"). One
        // logical Claude session can list as several rollout files (resume/
        // compaction), so a raw listing may repeat an id. This is the single
        // publish choke point, so guarding here covers every feeder. Newest-first
        // ⇒ keep the first (most recent) file.
        val unique = list.distinctBy { it.id }
        _sessions.value = if (tomb.isEmpty()) unique else unique.filterNot { it.id in tomb }
    }

    /**
     * Close the delete loop. A swipe-delete tombstones the session locally and
     * tries to `rm` it on the server — but if no transport was live at that
     * moment the file lingers on the server, hidden-but-undeleted. So whenever
     * a listing succeeds over a live channel, re-fire the delete for every
     * tombstoned id the server STILL reports. ownAll()'s pruneDeletedSessions
     * then drops the tombstone once the next listing confirms the file is gone.
     * Best-effort, never throws; runs over the SAME channel that just listed.
     */
    private suspend fun reconcileTombstones(raw: List<RemoteSession>, exec: suspend (cmd: String) -> String?) {
        val tomb = _deletedIds.value
        if (tomb.isEmpty()) return
        val stragglers = raw.filter { it.id in tomb }
        if (stragglers.isEmpty()) return
        android.util.Log.d("SshAi-Sessions", "reconcile: ${stragglers.size} tombstoned session(s) still on server — deleting")
        for (s in stragglers) {
            val inner = ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent].deleteSessionCommand(s.id, s.path)
            val cmd = "bash -lc " + ai.eight24family.conch.agent.shellEscape(inner)
            SilentlyTry.fired("SshAi-Sessions", "reconcile delete ${s.id.take(8)}") { exec(cmd) }
        }
    }

    /** Fire [reconcileTombstones] in the BACKGROUND so it never delays the list
     *  render — rows are already published (tombstoned ones hidden); the server
     *  `rm` rides the same live channel a beat later. No-op when nothing is
     *  tombstoned (the common case), so zero overhead for most users. */
    private fun scheduleReconcile(raw: List<RemoteSession>, exec: suspend (cmd: String) -> String?) {
        if (_deletedIds.value.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            SilentlyTry.fired("SshAi-Sessions", "reconcile tombstones") { reconcileTombstones(raw, exec) }
        }
    }

    /** A reconcile-exec over a pooled SSHClient (fresh channel per command). */
    private fun pooledReconcileExec(client: net.schmizz.sshj.SSHClient): suspend (cmd: String) -> String? = { cmd ->
        withContext(Dispatchers.IO) {
            SilentlyTry.logged("SshAi-Sessions", "reconcile via pooled client") {
                val sess = client.startSession()
                try {
                    val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
                    proc.inputStream.readBytes()
                    proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                    ""
                } finally { SilentlyTry.fired("SshAi-Sessions", "close reconcile session") { sess.close() } }
            }
        }
    }

    /** Background prefetch job — cancelled on each list refresh and on clear. */
    private var prefetchJob: Job? = null

    /** Top-N most-recent sessions we'll prefetch. Beyond this is wasted SSH. */
    private val prefetchLimit = 25

    private val _server = MutableStateFlow<Server?>(null)
    val server: StateFlow<Server?> = _server.asStateFlow()

    private val _sessions = MutableStateFlow<List<RemoteSession>>(emptyList())
    val sessions: StateFlow<List<RemoteSession>> = _sessions.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** True only on the very first list call when there's no cache yet —
     *  drives a "loading…" label vs the "refreshing…" used for pull-to-refresh. */
    private val _initialLoading = MutableStateFlow(false)
    val initialLoading: StateFlow<Boolean> = _initialLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _activeResumeIds = MutableStateFlow<Set<String>>(emptySet())
    val activeResumeIds: StateFlow<Set<String>> = _activeResumeIds.asStateFlow()

    /** Resume ids (this server) the user wired to the phone via the chat
     *  paperclip → "Connect phone to server". Drives the phone glyph on the row.
     *  Stored in prefs as "<serverId>:<resumeId>"; stripped back to bare ids. */
    private val bridgeTagPrefix = "$serverId:"
    val phoneBridgeIds: StateFlow<Set<String>> =
        ServiceLocator.preferences.phoneBridgeSessions
            .map { tags ->
                tags.asSequence()
                    .filter { it.startsWith(bridgeTagPrefix) }
                    .map { it.removePrefix(bridgeTagPrefix) }
                    .toSet()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Server-global LIVE layer for the 📱 glyph (PHONE-GLYPH-SHIZUKU-2): the
     *  channel is polling ([BridgeHealth.isAlive]) AND Shizuku is granted right
     *  now. All wired sessions on one server share it (keyed by serverId), so the
     *  row glyph is colored only when truly live, dim otherwise. Re-sampled on a
     *  2s ticker so it dims/relights without a manual refresh. */
    val phoneBridgeLive: StateFlow<Boolean> =
        kotlinx.coroutines.flow.flow { while (true) { emit(Unit); kotlinx.coroutines.delay(2_000) } }
            .map {
                ai.eight24family.conch.diagnostics.BridgeHealth.isAlive(serverId) &&
                    ai.eight24family.conch.diagnostics.ShizukuShell.available()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Last successful list time — drives a "synced X ago" UI hint. */
    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()

    /**
     * Live progress of the per-(server, agent) JSONL prefetcher.
     *  - `done` = number of sessions whose JSONL has landed on disk
     *  - `total` = number of sessions we plan to fetch this round
     *
     * The header row uses this to render a yellow ring filling up
     * from 0 → total, with the count printed inside; flips to a
     * green checkmark once `done == total > 0`. Null when the
     * prefetcher hasn't started or has nothing to do.
     */
    data class PrefetchProgress(val done: Int, val total: Int) {
        val complete: Boolean get() = total > 0 && done >= total
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }
    private val _prefetchProgress = MutableStateFlow<PrefetchProgress?>(null)
    val prefetchProgress: StateFlow<PrefetchProgress?> = _prefetchProgress.asStateFlow()

    // ── Hardware security key touch handling for the discovery path ──
    /**
     * Surfaced when pull-to-refresh fires against an SK-keyed server.
     * Holds the credential metadata the screen needs to drive a touch
     * dialog. The screen runs the actual signing operation inline
     * (USB awaitUsb + signer build, OR NFC withNfc-callback that
     * holds the tag for the full SSH handshake) and calls
     * [runDiscoveryWithSigner] from within. Cleared by
     * [cancelSkRefresh] or automatically after a successful run.
     */
    data class SkTouchRequest(
        val transport: ai.eight24family.conch.domain.SecurityKeyTransport,
        val application: String,
        val credentialIdBase64: String,
        val serverName: String?,
    )
    private val _skTouchRequest = MutableStateFlow<SkTouchRequest?>(null)
    val skTouchRequest: StateFlow<SkTouchRequest?> = _skTouchRequest.asStateFlow()

    /**
     * Run discovery with the caller-built signer and save the result.
     * Intended to be invoked from the screen INSIDE yubikit's NFC
     * `withNfc` callback (so the IsoDep tag stays alive for the
     * duration) for NFC tokens, or after `awaitUsb` for USB tokens.
     * Suspends until the SSH handshake + script execution complete.
     */
    suspend fun runDiscoveryWithSigner(
        signer: ai.eight24family.conch.ssh.securitykey.SkSigner,
    ) {
        val tag = "SshAi-SK-Disc"
        android.util.Log.d(tag, "runDiscoveryWithSigner: enter")
        val server = _server.value ?: repo.getById(serverId).also { _server.value = it } ?: return
        val secrets = repo.getSecrets(serverId)
        android.util.Log.d(tag, "  server=${server.name} skKey=${secrets.skKeys.isNotEmpty()}")
        try {
            _refreshing.value = true
            android.util.Log.d(tag, "  calling pool.userConnect (one auth, held for the whole user session)…")
            // CRITICAL: drive a `pool.userConnect` instead of the
            // disposable `discovery.list(..., skSigner)` path. The
            // disposable path opens an SSHClient, auths, runs the
            // discovery script, and then DISCONNECTS — leaving the
            // pool empty and the home-screen ● dot dark even after
            // a successful touch. `userConnect` keeps the
            // authenticated client alive, refcount = 1 (user
            // intent), so subsequent screens / chats / refreshes
            // ride the same transport for free.
            val client = withContext(Dispatchers.IO) {
                ServiceLocator.sshConnectionPool.userConnect(server, secrets, signer)
            }
            // Discovery now rides the pooled client — fresh channel
            // per command, no extra handshake.
            val raw = discovery.list(agent) { cmd ->
                withContext(Dispatchers.IO) {
                    SilentlyTry.logged("SshAi-Sessions", "fetch sessions list (pool)") {
                        val sess = client.startSession()
                        try {
                            val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
                            val out = java.io.ByteArrayOutputStream()
                            proc.inputStream.copyTo(out)
                            proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                            String(out.toByteArray(), Charsets.UTF_8)
                        } finally { SilentlyTry.fired("SshAi-Sessions", "close list-pool session") { sess.close() } }
                    }
                }
            }
            ownAll(raw)
            val list = raw.filter { it.preview.isNotBlank() }
            android.util.Log.d(tag, "  discovery.list (pooled) returned ${list.size} session(s)")
            publishSessions(mergeSizes(list))
            _lastSyncedAt.value = System.currentTimeMillis()
            cache.save(serverId, agent, _sessions.value)
            android.util.Log.d(tag, "  cache.save done — pool now holds the live client (● lit on home)")
            // Kick prefetch — pool has a live client now so this
            // fires through it, no second touch.
            startPrefetch(server, secrets, list)
            // Push any pending server-side deletes over this same live client.
            scheduleReconcile(raw, pooledReconcileExec(client))
        } catch (t: Throwable) {
            android.util.Log.e(tag, "  SK discovery threw", t)
            _error.value = t.message ?: t.javaClass.simpleName
        } finally {
            _refreshing.value = false
            _skTouchRequest.value = null
            ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier.cancel(ServiceLocator.appContext)
            android.util.Log.d(tag, "  finally done")
        }
    }

    fun cancelSkRefresh() {
        _skTouchRequest.value = null
        _refreshing.value = false
        ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier.cancel(ServiceLocator.appContext)
    }

    /**
     * Run discovery by piping the script through an already-authenticated
     * AgentSession's persistent SSH channel — no new handshake, no fresh
     * touch. Used when the user has a chat already open on (server, agent):
     * the AgentSession already paid for one touch, so any number of free
     * pull-to-refreshes can ride that channel until the chat closes.
     *
     * If `execOnLive` returns null the persistent channel was dead AND
     * its fallback couldn't recover (typical for SK servers whose tag
     * was lifted). In that case we treat reuse as failed and fall
     * through to the touch-dialog path so the user has SOME way out
     * — instead of silently rendering an empty list.
     */
    /**
     * Run discovery directly through a pooled SSHClient. Same idea as
     * [runDiscoveryViaAliveSession] but uses the pool's client (which
     * survives across chat lifetimes via user-intent reference) — so
     * the moment the user does tap-to-connect on the home screen,
     * sessions list discovery is free without needing any chat to
     * have ever been opened.
     */
    private suspend fun runDiscoveryViaPooledClient(
        server: Server,
        secrets: ServerSecrets,
        client: net.schmizz.sshj.SSHClient,
    ): Boolean {
        val tag = "SshAi-SK-Disc"
        // Don't toggle `_refreshing` here — that flag drives the
        // pull-to-refresh spinner, which is overkill for an
        // automatic discovery run when the user hasn't even pulled
        // down. The yellow ring in the top bar is the only progress
        // indicator the user needs for an auto-fired refresh.
        try {
            var execFailed = false
            val raw = discovery.list(agent) { cmd ->
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    SilentlyTry.logged("SshAi-Sessions", "fetch sessions list (fresh)") {
                        val sess = client.startSession()
                        try {
                            val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
                            val out = java.io.ByteArrayOutputStream()
                            proc.inputStream.copyTo(out)
                            proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                            String(out.toByteArray(), Charsets.UTF_8)
                        } finally { SilentlyTry.fired("SshAi-Sessions", "close list-fresh session") { sess.close() } }
                    }.also { if (it == null) execFailed = true }
                }
            }
            if (execFailed) {
                android.util.Log.w(tag, "  pool path: exec returned null — channel dead")
                return false
            }
            ownAll(raw)
            val list = raw.filter { it.preview.isNotBlank() }
            android.util.Log.d(tag, "  discovery (pool) returned ${list.size} session(s)")
            publishSessions(mergeSizes(list))
            _lastSyncedAt.value = System.currentTimeMillis()
            cache.save(serverId, agent, _sessions.value)
            startPrefetch(server, secrets, list)
            scheduleReconcile(raw, pooledReconcileExec(client))
            return true
        } catch (t: Throwable) {
            android.util.Log.w(tag, "  pool path threw", t)
            return false
        }
    }

    private suspend fun runDiscoveryViaAliveSession(
        alive: ai.eight24family.conch.agent.AgentSession,
    ): Boolean {
        val tag = "SshAi-SK-Disc"
        android.util.Log.d(tag, "runDiscoveryViaAliveSession: enter (reuse persistent SSH)")
        // Same as runDiscoveryViaPooledClient: don't toggle
        // `_refreshing` here. Top-level `refresh()` already does it
        // for visible pulls; auto-init refreshes shouldn't paint
        // the pull-to-refresh spinner since the yellow sync ring
        // is doing that job.
        try {
            // Track whether the persistent path actually produced output —
            // null from execOnLive means "channel + fallback both failed",
            // distinct from "ran fine, server has zero sessions".
            var execFailed = false
            val raw = discovery.list(agent) { cmd ->
                val out = alive.execOnLive(cmd)
                if (out == null) execFailed = true
                out
            }
            if (execFailed) {
                android.util.Log.w(tag, "  reuse path: alive.execOnLive returned null — channel dead, will retry via touch")
                return false
            }
            ownAll(raw)
            val list = raw.filter { it.preview.isNotBlank() }
            android.util.Log.d(tag, "  discovery.list (reuse) returned ${list.size} session(s)")
            publishSessions(mergeSizes(list))
            _lastSyncedAt.value = System.currentTimeMillis()
            cache.save(serverId, agent, _sessions.value)
            // Kick prefetch via the same alive client.
            val server = _server.value ?: repo.getById(serverId).also { _server.value = it }
            val secrets = repo.getSecrets(serverId)
            if (server != null) startPrefetch(server, secrets, list)
            scheduleReconcile(raw) { alive.execOnLive(it) }
            return true
        } catch (t: Throwable) {
            android.util.Log.w(tag, "  reuse path threw", t)
            _error.value = t.message ?: t.javaClass.simpleName
            return false
        }
    }

    // ── Memory editor (loaded on demand when the user taps the icon) ──
    private val _memory = MutableStateFlow(MemoryDocs(filename = agent.memoryFilename, globalDisplay = agent.memoryGlobalDisplay))
    val memory: StateFlow<MemoryDocs> = _memory.asStateFlow()

    private val _memorySheetOpen = MutableStateFlow(false)
    val memorySheetOpen: StateFlow<Boolean> = _memorySheetOpen.asStateFlow()

    fun openMemoryEditor() {
        _memorySheetOpen.value = true
        refreshMemory()
    }
    fun closeMemoryEditor() { _memorySheetOpen.value = false }

    fun refreshMemory() {
        viewModelScope.launch(Dispatchers.IO) {
            _memory.value = MemoryService(serverId, agent, chatId = null).load()
        }
    }

    fun saveMemory(scope: MemoryScope, contents: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MemoryService(serverId, agent, chatId = null).save(scope, contents)
            refreshMemory()
        }
    }

    // ── Approval / sandbox mode (shared across all chats on this server) ──
    val approvalMode: StateFlow<AgentApprovalMode> = ServiceLocator.preferences.approvalMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentApprovalMode.YOLO)

    fun setApprovalMode(mode: AgentApprovalMode) {
        viewModelScope.launch { ServiceLocator.preferences.setApprovalMode(mode) }
    }

    /**
     * Terminate the LIVE session that's currently attached to [resumeId] for
     * this (server, agent). The remote CLI session JSONL on the host is
     * untouched — only our local SSH connection + AgentSession object are
     * torn down. Recomputes `activeResumeIds` so the LIVE marker drops the
     * row immediately.
     */
    fun killLive(resumeId: String) {
        viewModelScope.launch {
            val mgr = ServiceLocator.agentSessions
            // Locate which `chatSessionId` keys this resumeId, then close.
            // We don't keep a reverse index — sweep the few we have.
            val targets = mgr.active.value.filter {
                it.serverId == serverId && it.agent == agent
            }.mapNotNull { info ->
                val s = mgr.get(info.serverId, info.agent, info.chatSessionId)
                if (s?.agentSessionId == resumeId) info else null
            }
            targets.forEach { mgr.close(it.serverId, it.agent, it.chatSessionId) }
            _activeResumeIds.value = mgr.activeResumeIds(serverId, agent)
        }
    }

    init {
        ServiceLocator.agentSessions.reapDeadSessions()

        viewModelScope.launch {
            _server.value = repo.getById(serverId)
            // SK-keyed servers: never auto-trigger a refresh on init.
            // The refresh requires a touch dialog, which gets disposed
            // (and the NFC reader-mode released) if the nav stack
            // settles in another configuration in the same frame —
            // typical when the saved route restores a deep destination
            // through `walkBackStackTo`. Wait for explicit pull-down
            // by the user.
            val secrets = repo.getSecrets(serverId)
            if (secrets.skKeys.isNotEmpty()) {
                val cached = cache.load(serverId, agent)
                if (cached.sessions.isNotEmpty()) {
                    publishSessions(cached.sessions)
                    _lastSyncedAt.value = cached.cachedAt
                }
                // Pool live (user has done tap-to-connect or has any
                // chat open on this server) — refresh discovery + kick
                // prefetch through the pooled client. All free, no
                // touch.
                val pooled = ServiceLocator.sshConnectionPool.peek(serverId)
                if (pooled != null) {
                    android.util.Log.d("SshAi-Sessions", "init: pool live — refreshing discovery + kicking prefetch")
                    softRefresh()
                }
                return@launch
            }
            // 1) Hydrate from disk cache → instant render. No spinner shown.
            val cached = cache.load(serverId, agent)
            if (cached.sessions.isNotEmpty()) {
                publishSessions(cached.sessions)
                _lastSyncedAt.value = cached.cachedAt
                // 2) Quietly poll the server for fresh data; merge in if it
                //    differs from what we have. No spinner — invisible to user.
                softRefresh()
            } else {
                // No cache yet → first-ever load. Show "loading…" rather
                // than "refreshing…" — semantically different, and the
                // pull-to-refresh spinner shouldn't fire on initial paint.
                initialLoad()
            }
        }
        // Track delete tombstones for this server. Re-filter the visible list
        // whenever they change so a delete sticks even if prefs loads AFTER the
        // first cache render, or an in-flight refresh tried to resurface it.
        viewModelScope.launch {
            val prefix = "$serverId:"
            ServiceLocator.preferences.deletedSessions.collect { tags ->
                val ids = tags.asSequence()
                    .filter { it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .toSet()
                _deletedIds.value = ids
                if (ids.isNotEmpty()) _sessions.update { cur -> cur.filterNot { it.id in ids } }
            }
        }
        viewModelScope.launch {
            ServiceLocator.agentSessions.active.collect { _ ->
                _activeResumeIds.value = ServiceLocator.agentSessions.activeResumeIds(serverId, agent)
            }
        }
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3_000)
                _activeResumeIds.value = ServiceLocator.agentSessions.activeResumeIds(serverId, agent)
            }
        }
        // Re-sort the visible list the instant the activity store advances (a
        // local send/reply, or a fresh mtime sweep). We DON'T mutate lastActiveAt
        // here — that field stays the server's seconds-mtime (the row's stamp +
        // what we persist to cache). Ordering reads the store-aware effective time
        // instead, so a just-sent chat rises immediately AND survives restarts —
        // and without the old bug of copying a MILLIS bump into the SECONDS
        // lastActiveAt field (which dated that row to the year ~58000).
        viewModelScope.launch {
            ServiceLocator.sessionActivity.changes.collect {
                _sessions.update { list -> list.sortedByDescending { effectiveActivityMs(it) } }
            }
        }
    }

    /** Best-known last-activity for sorting, epoch MILLIS: the activity store's
     *  value (local + remote, monotonic) maxed with the cached file mtime
     *  (SECONDS → ×1000). Single source of truth, shared with the Home list. */
    private fun effectiveActivityMs(s: RemoteSession): Long =
        maxOf(
            ServiceLocator.sessionActivity.lastActivity(serverId, s.id),
            s.lastActiveAt * 1000L,
        )

    /** Visible refresh — pull-to-refresh wires here. Sets `_refreshing`. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _refreshing.value = true
            _error.value = null
            try {
                runListAndPersist(visible = true)
            } catch (t: Throwable) {
                _error.value = t.message ?: t.javaClass.simpleName
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Permanently delete one saved session (swipe-to-reveal → Delete on the
     * sessions list). Optimistically drops it from the visible list, the disk
     * cache, and the cached body, then removes the file(s) on the server over
     * the live pooled connection — no fresh handshake, no extra security-key
     * touch. Per-agent [deleteSessionCommand] handles multi-file CLIs (Gemini
     * writes a fresh snapshot per resume, all sharing one sessionId).
     */
    fun deleteSession(session: RemoteSession) {
        // Synchronous + optimistic: the row vanishes the instant Delete is tapped,
        // and the tombstone goes in NOW so any in-flight refresh that completes a
        // moment later (with the session still in its server snapshot) filters it
        // out instead of resurfacing it.
        _deletedIds.update { it + session.id }
        _sessions.update { list -> list.filterNot { it.id == session.id && it.path == session.path } }
        viewModelScope.launch(Dispatchers.IO) {
            // Persist the tombstone so re-entry / app restart / a not-yet-landed
            // server `rm` can't bring it back; pruned once the server confirms.
            SilentlyTry.fired("SshAi-Sessions", "persist delete tombstone") {
                ServiceLocator.preferences.setDeletedSession(serverId, session.id, true)
            }
            // Persist the removal so an offline re-open doesn't show it again.
            SilentlyTry.fired("SshAi-Sessions", "persist session removal to cache") {
                cache.save(serverId, agent, _sessions.value)
            }
            SilentlyTry.fired("SshAi-Sessions", "forget cached session body") {
                historyCache.forget(session.id)
            }
            // Server-side delete rides the pooled client. If none is live (deleting
            // from the sessions list with no open chat, or the connection dropped on
            // idle/network change), bring it up SILENTLY first — same seamless path
            // app-start uses (stored key/password, or an SK with an enrolled device
            // key; no tap). That's why deletes weren't reaching the server. A tap-only
            // SK with no device key can't connect silently → stays tombstoned (hidden)
            // and the rm rides the next connected refresh.
            var pooled = ServiceLocator.sshConnectionPool.peek(serverId)
            if (pooled == null) {
                try { ServiceLocator.sshConnectionPool.connectAllPossibleSilently() } catch (_: Throwable) {}
                pooled = ServiceLocator.sshConnectionPool.peek(serverId)
            }
            if (pooled == null) {
                android.util.Log.w("SshAi-Sessions", "deleteSession ${session.id.take(8)}: no transport — kept tombstoned, rm will ride next connect")
                return@launch
            }
            val inner = ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent]
                .deleteSessionCommand(session.id, session.path)
            val cmd = "bash -lc " + ai.eight24family.conch.agent.shellEscape(inner)
            SilentlyTry.fired("SshAi-Sessions", "delete session on server") {
                val sess = pooled.startSession()
                try {
                    val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
                    proc.inputStream.readBytes()
                    proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                } finally {
                    SilentlyTry.fired("SshAi-Sessions", "close delete session") { sess.close() }
                }
            }
            android.util.Log.d("SshAi-Sessions", "deleteSession ${session.id.take(8)}: server file(s) removed")
        }
    }

    // ──────────── Session file download (disk icon per row) ────────────
    // Mirrors the chat's file-download UX (download → Open here / Other app /
    // Share, remembered per-extension) but is FULLY SELF-CONTAINED + pool-backed
    // so it can't regress the chat download path. Reuses only the shared
    // OpenFileChooserSheet + ChatViewModel's request/status types (read-only).
    private val _downloads = MutableStateFlow<Map<String, ChatViewModel.DownloadStatus>>(emptyMap())
    val downloads: StateFlow<Map<String, ChatViewModel.DownloadStatus>> = _downloads.asStateFlow()

    private val _openInViewer = MutableSharedFlow<ChatViewModel.OpenInViewerRequest>(replay = 0, extraBufferCapacity = 4)
    val openInViewer = _openInViewer.asSharedFlow()
    private val _openExternally = MutableSharedFlow<ChatViewModel.OpenExternallyRequest>(replay = 0, extraBufferCapacity = 4)
    val openExternally = _openExternally.asSharedFlow()
    private val _shareFile = MutableSharedFlow<ChatViewModel.ShareRequest>(replay = 0, extraBufferCapacity = 4)
    val shareFile = _shareFile.asSharedFlow()
    private val _openFilePrompt = MutableSharedFlow<ChatViewModel.OpenFilePromptRequest>(replay = 0, extraBufferCapacity = 4)
    val openFilePrompt = _openFilePrompt.asSharedFlow()

    private fun mimeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** Disk-icon tap on a session row: download its JSONL to Downloads/conch/,
     *  then route to Open-here / Other-app / Share (chooser unless remembered).
     *  Re-tap mid-download = no-op; re-tap on Done re-opens the chooser. */
    fun downloadSession(session: RemoteSession) {
        val key = session.id
        when (val cur = _downloads.value[key]) {
            is ChatViewModel.DownloadStatus.Downloading -> return
            is ChatViewModel.DownloadStatus.Done -> {
                openDownloaded(cur.localUri, session.path, mimeForName(session.path.substringAfterLast('/')), cur.sizeBytes)
                return
            }
            else -> {}
        }
        val pooled = ServiceLocator.sshConnectionPool.peek(serverId) ?: run {
            _downloads.update { it + (key to ChatViewModel.DownloadStatus.Failed("Tap the server to connect first")) }
            return
        }
        val basename = session.path.substringAfterLast('/').ifBlank { "${session.id}.jsonl" }
        _downloads.update { it + (key to ChatViewModel.DownloadStatus.Downloading(-1f)) }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = ServiceLocator.appContext
            val mime = mimeForName(basename)
            val onProg: (Long, Long) -> Unit = { got, total ->
                val p = if (total > 0) got.toFloat() / total else -1f
                _downloads.update { it + (key to ChatViewModel.DownloadStatus.Downloading(p)) }
            }
            var resultUri: android.net.Uri? = null
            var displayLocation = ""
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, basename)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/conch/")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: run {
                    _downloads.update { it + (key to ChatViewModel.DownloadStatus.Failed("MediaStore insert failed")) }
                    return@launch
                }
                val outcome = runCatching {
                    resolver.openOutputStream(uri)!!.use { os -> streamRemoteToSink(pooled, session.path, os, onProg) }
                }.getOrElse { ai.eight24family.conch.agent.AgentSession.DownloadOutcome.Failed(it.message ?: "io error") }
                when (outcome) {
                    is ai.eight24family.conch.agent.AgentSession.DownloadOutcome.Done -> {
                        resolver.update(uri, android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        }, null, null)
                        resultUri = uri; displayLocation = "Download/conch/$basename"
                    }
                    is ai.eight24family.conch.agent.AgentSession.DownloadOutcome.Failed -> {
                        SilentlyTry.fired("SshAi-Sessions", "delete failed mediastore") { resolver.delete(uri, null, null) }
                        _downloads.update { it + (key to ChatViewModel.DownloadStatus.Failed(outcome.reason)) }
                        return@launch
                    }
                }
            } else {
                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: run {
                    _downloads.update { it + (key to ChatViewModel.DownloadStatus.Failed("no external storage")) }
                    return@launch
                }
                val target = java.io.File(dir, basename)
                val outcome = runCatching {
                    target.outputStream().use { os -> streamRemoteToSink(pooled, session.path, os, onProg) }
                }.getOrElse { ai.eight24family.conch.agent.AgentSession.DownloadOutcome.Failed(it.message ?: "io error") }
                when (outcome) {
                    is ai.eight24family.conch.agent.AgentSession.DownloadOutcome.Done -> {
                        resultUri = android.net.Uri.fromFile(target); displayLocation = target.absolutePath
                    }
                    is ai.eight24family.conch.agent.AgentSession.DownloadOutcome.Failed -> {
                        SilentlyTry.fired("SshAi-Sessions", "delete failed target") { target.delete() }
                        _downloads.update { it + (key to ChatViewModel.DownloadStatus.Failed(outcome.reason)) }
                        return@launch
                    }
                }
            }
            val u = resultUri ?: return@launch
            val sizeBytes = if (u.scheme == "file") {
                SilentlyTry.loggedOrElse("SshAi-Sessions", "file size", -1L) {
                    java.io.File(u.path ?: return@loggedOrElse -1L).length()
                }
            } else {
                SilentlyTry.loggedOrElse("SshAi-Sessions", "content uri size", -1L) {
                    ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (c.moveToFirst() && i >= 0) c.getLong(i) else -1L
                    } ?: -1L
                }
            }
            _downloads.update { it + (key to ChatViewModel.DownloadStatus.Done(u, displayLocation, sizeBytes)) }
            openDownloaded(u, session.path, mime, sizeBytes)
        }
    }

    private fun openDownloaded(uri: android.net.Uri, remotePath: String, mime: String, sizeBytes: Long) {
        val filename = remotePath.substringAfterLast('/')
        val ext = filename.substringAfterLast('.', "").lowercase()
        viewModelScope.launch {
            val pref = if (ext.isNotEmpty())
                ServiceLocator.preferences.openFilePreferenceForExtension(ext).first() else null
            when (pref) {
                "internal" -> _openInViewer.emit(ChatViewModel.OpenInViewerRequest(uri, filename, serverId, remotePath))
                "external" -> _openExternally.emit(ChatViewModel.OpenExternallyRequest(uri, mime))
                "share" -> _shareFile.emit(ChatViewModel.ShareRequest(uri, mime, filename))
                else -> _openFilePrompt.emit(ChatViewModel.OpenFilePromptRequest(uri, filename, mime, ext, sizeBytes, remotePath))
            }
        }
    }

    /** Persist the "where to open .ext" choice (shared with chat via prefs). */
    fun rememberOpenFileChoice(extension: String, choice: String) {
        if (extension.isBlank()) return
        viewModelScope.launch { ServiceLocator.preferences.setOpenFilePreferenceForExtension(extension, choice) }
    }

    /** `cat` the remote file over the pooled SSH into [sink] (mirror of
     *  AgentSessionFileTransfer.downloadFile, but pool-backed — no AgentSession
     *  needed since the sessions list isn't tied to an open chat). */
    private suspend fun streamRemoteToSink(
        pooled: net.schmizz.sshj.SSHClient,
        remotePath: String,
        sink: java.io.OutputStream,
        onProgress: (Long, Long) -> Unit,
    ): ai.eight24family.conch.agent.AgentSession.DownloadOutcome = withContext(Dispatchers.IO) {
        val esc = "'" + remotePath.replace("'", "'\\''") + "'"
        val total: Long = SilentlyTry.loggedOrElse("SshAi-Sessions", "stat for download", -1L) {
            val s = pooled.startSession()
            try {
                val p = s.exec("stat -c %s -- $esc 2>/dev/null || stat -f %z -- $esc 2>/dev/null || wc -c < $esc 2>/dev/null")
                val o = java.io.ByteArrayOutputStream(); p.inputStream.copyTo(o)
                p.join(15, java.util.concurrent.TimeUnit.SECONDS)
                String(o.toByteArray(), Charsets.UTF_8).trim().lines().firstNotNullOfOrNull { it.trim().toLongOrNull() } ?: -1L
            } finally { SilentlyTry.fired("SshAi-Sessions", "close stat session") { s.close() } }
        }
        onProgress(0, total)
        var s: net.schmizz.sshj.connection.channel.direct.Session? = null
        try {
            s = pooled.startSession()
            val cmd = s.exec("cat -- $esc")
            val buf = ByteArray(64 * 1024); var got = 0L
            while (true) {
                val n = cmd.inputStream.read(buf); if (n <= 0) break
                sink.write(buf, 0, n); got += n; onProgress(got, total)
            }
            cmd.join(2, java.util.concurrent.TimeUnit.MINUTES); sink.flush()
            val exit = cmd.exitStatus
            if (exit == null || exit == 0) ai.eight24family.conch.agent.AgentSession.DownloadOutcome.Done(got)
            else ai.eight24family.conch.agent.AgentSession.DownloadOutcome.Failed("cat exited with $exit")
        } catch (t: Throwable) {
            ai.eight24family.conch.agent.AgentSession.DownloadOutcome.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            SilentlyTry.fired("SshAi-Sessions", "close cat session") { s?.close() }
        }
    }

    /** First-ever load (no cache). Different flag so the UI can show
     *  "loading…" instead of "refreshing…", which is misleading on a fresh open. */
    private fun initialLoad() {
        viewModelScope.launch(Dispatchers.IO) {
            _initialLoading.value = true
            _error.value = null
            try {
                runListAndPersist(visible = true)
            } catch (t: Throwable) {
                _error.value = t.message ?: t.javaClass.simpleName
            } finally {
                _initialLoading.value = false
            }
        }
    }

    /** Silent background refresh — no spinner, errors swallowed; used on
     *  re-entry to slip newly-created server-side sessions in unobtrusively. */
    fun softRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            SilentlyTry.fired("SshAi-Sessions", "background runListAndPersist") { runListAndPersist(visible = false) }
        }
    }

    private suspend fun runListAndPersist(visible: Boolean) {
        val server = _server.value ?: repo.getById(serverId).also { _server.value = it } ?: return
        val secrets = repo.getSecrets(serverId)
        // Warm the usage/limit cache whenever we're here with a live pooled
        // connection, so the chat's bar is already populated the moment the
        // user opens a session. Fire-and-forget so it never delays the session
        // listing.
        if (ServiceLocator.sshConnectionPool.peek(serverId) != null) {
            viewModelScope.launch(Dispatchers.IO) {
                SilentlyTry.fired("SshAi-Sessions", "prefetch usage") {
                    // Fast first (rollout snapshot caches in ~0.3s → the bar is
                    // warm the instant a chat opens), then live to refine.
                    ai.eight24family.conch.agent.UsageProbe.fetch(serverId, agent, fast = true)
                    ai.eight24family.conch.agent.UsageProbe.fetch(serverId, agent, fast = false)
                }
            }
        }
        // FIDO security-key path: listing sessions needs `ssh.execute`,
        // which needs an SkSigner, which needs a physical touch. Two
        // short-circuits before we surface the touch dialog:
        //
        //   1. **Reuse persistent SSH from an alive AgentSession.**
        //      If the user has any chat open on this (server, agent),
        //      we already paid for a touch when that chat opened. Run
        //      discovery through that AgentSession's existing
        //      `execOnLive` (i.e. `sshClient.startSession().exec(...)`)
        //      — no new auth, no second touch.
        //   2. **Silent background refresh** still bails — we don't pop
        //      UI uninvited, last cached list stays rendered.
        //
        // Only when neither short-circuit applies do we publish the
        // touch request and let the screen drive a fresh `ssh.execute`
        // with a freshly-built signer.
        val skKey = secrets.skKeys.firstOrNull()
        if (skKey != null) {
            // First try the pool — if user has done tap-to-connect we
            // have a live SSH and discovery rides it for free.
            val pooled = ServiceLocator.sshConnectionPool.peek(serverId)
            if (pooled != null) {
                android.util.Log.d("SshAi-Sessions", "discovery via pooled SSH — no touch")
                val ok = runDiscoveryViaPooledClient(server, secrets, pooled)
                if (ok) return
                android.util.Log.d("SshAi-Sessions", "  pool discovery failed — falling through")
            }
            val alive = ServiceLocator.agentSessions.findAnyAlive(serverId, agent)
            if (alive != null) {
                android.util.Log.d(
                    "SshAi-Sessions",
                    "reusing alive AgentSession's SSH for SK discovery — no touch"
                )
                val ok = runDiscoveryViaAliveSession(alive)
                if (ok) return
                // Reuse failed (channel dead, signer dead, etc). Fall
                // through to the touch flow so the user has a way out.
                android.util.Log.d("SshAi-Sessions", "  reuse failed — falling back to touch flow")
            }
            if (!visible) {
                _lastSyncedAt.value = System.currentTimeMillis()
                return
            }
            val info = skKey.securityInfo ?: run {
                _error.value = "Security-key row is missing handle/application — re-add the key."
                return
            }
            _skTouchRequest.value = SkTouchRequest(
                transport = ai.eight24family.conch.domain.SecurityKeyTransport.EITHER,
                application = info.application,
                credentialIdBase64 = info.credentialIdBase64,
                serverName = server.name,
            )
            ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier.post(
                context = ServiceLocator.appContext,
                reason = ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier.Reason.CONNECT,
                transport = ai.eight24family.conch.domain.SecurityKeyTransport.EITHER,
                target = server.name,
            )
            _refreshing.value = false
            return
        }
        // Non-SK (password / plain saved "seamless" key): ride the pool exactly
        // like the SK path above, so the FIRST listing connects once (held via
        // user intent) and EVERY later refresh reuses the warm transport. The
        // old `discovery.list(server, secrets, agent)` path re-did a full
        // TCP + auth handshake on EVERY listing — the main reason the list was
        // slow to appear. App-start connectAllPossibleSilently usually has the
        // client live already, so the common case is just a fresh channel open.
        var pooled = ServiceLocator.sshConnectionPool.peek(serverId)
        if (pooled == null) {
            pooled = SilentlyTry.logged("SshAi-Sessions", "userConnect non-SK for listing") {
                withContext(Dispatchers.IO) {
                    ServiceLocator.sshConnectionPool.userConnect(server, secrets, null)
                }
            }
        }
        if (pooled != null && runDiscoveryViaPooledClient(server, secrets, pooled)) return
        // Pool unavailable (connect failed) or its channel died mid-list →
        // last-resort one-shot handshake. No live transport to reconcile over;
        // any pending server `rm` rides the next successful pooled listing.
        val raw = discovery.list(server, secrets, agent)
        ownAll(raw)
        val list = raw.filter { it.preview.isNotBlank() }
        // Compose treats this as a content swap; LazyColumn diffs by key
        // so new rows tear in and removed rows fade out without flicker.
        publishSessions(mergeSizes(list))
        _lastSyncedAt.value = System.currentTimeMillis()
        cache.save(serverId, agent, _sessions.value)
        // Kick off (or restart) the background prefetcher: while the user is
        // scrolling/picking, quietly cat each session's JSONL and stash it in
        // the per-session history cache. Tapping any of them then renders
        // INSTANTLY from the cache instead of waiting on a fresh SSH cat.
        startPrefetch(server, secrets, list)
    }

    /**
     * Walk [sessions] (newest-first; sorted by the discovery script) and
     * fill [historyCache] for any that don't already have an entry.
     *
     * Sequential, not parallel — every fetch opens a fresh SSH connection
     * (the AgentSession that holds an open channel only exists while the
     * chat is open). Parallel handshakes would saturate the channel and
     * spike CPU on both ends. Sequential at ~1-2s per session is fine
     * because nothing in the UI is blocked on it.
     *
     * Cancelled and restarted on each list refresh so we don't accumulate
     * stale work, and on `onCleared` so leaving the screen drops it.
     */
    private fun startPrefetch(server: Server, secrets: ServerSecrets, sessions: List<RemoteSession>) {
        prefetchJob?.cancel()

        // Data-saver: skip prefetch entirely. Each prefetched session
        // body can be hundreds of KB → MB; for 25 sessions a fresh
        // SessionsScreen open can pull tens of MB on a slow connection.
        // With data-saver ON the user pays only when they actually
        // tap a specific session.
        val dataSaver = runBlocking {
            ai.eight24family.conch.di.ServiceLocator.preferences.dataSaverEnabled.first()
        }
        if (dataSaver) {
            _prefetchProgress.value = null
            android.util.Log.d("SshAi-SessionsVM", "prefetch skipped: data saver on")
            return
        }

        // Total = top-N candidates we'd ever consider fetching.
        // Done   = how many of those already sit in the on-disk cache
        //          (whether from this session or a previous run).
        // We always publish a non-null progress as long as there are
        // sessions to display — that way pull-to-refresh doesn't
        // briefly black out the ring on the way back from the network.
        val window = sessions.take(prefetchLimit)
        val total = window.size
        if (total == 0) {
            _prefetchProgress.value = null
            return
        }
        val alreadyCached = window.count { historyCache.size(it.id) > 0L }
        _prefetchProgress.value = PrefetchProgress(done = alreadyCached, total = total)

        // Prefetch rides the POOLED transport only — for every auth kind, not
        // just SK. The non-SK fallback opened a FRESH handshake per session
        // file (N connect+auth+disconnect cycles 150 ms apart — a burst
        // indistinguishable from a scan, and a fail2ban feeder when creds go
        // stale). If the pool is down, the silent auto-connect paths own
        // bringing it up; prefetch just waits for the next visit.
        val pooledClient: net.schmizz.sshj.SSHClient? =
            ServiceLocator.sshConnectionPool.peek(serverId)
        if (pooledClient == null) {
            android.util.Log.d("SshAi-Prefetch", "skipped for $serverId (no live pool client)")
            return
        }

        // Drop the ones already on disk; only fetch the missing.
        val candidates = window.filter { historyCache.size(it.id) == 0L }
        if (candidates.isEmpty()) return  // ring already shows total/total = green ✓
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val tag = "SshAi-Prefetch"
            val viaPool = if (pooledClient != null) " (via pooled SSH)" else ""
            android.util.Log.d(tag, "begin: ${candidates.size} session(s) to prefetch (already $alreadyCached cached)$viaPool")
            // Counter starts at alreadyCached so the ring's `done`
            // includes pre-cached entries — pulling to refresh on a
            // fully-cached server immediately shows total/total = ✓
            // instead of crawling 0/total back up.
            var done = alreadyCached
            for (s in candidates) {
                if (historyCache.size(s.id) > 0L) {
                    // Raced with a chat-open. Still bump done so the
                    // ring tracks reality (the file IS on disk).
                    done += 1
                    _prefetchProgress.update { it?.copy(done = done) }
                    continue
                }
                try {
                    // Ride the pooled client — fresh channel per fetch,
                    // no auth round-trip.
                    val client = pooledClient
                    // SAME size discipline as the global sweep (2026-08-17):
                    // this path used to `cat` whole rollouts into a String —
                    // a 100 MB session is an OOM and a data bill, ring or no
                    // ring. Over the cap → cache the display TAIL only (the
                    // .base sidecar tells the open path the head is missing;
                    // "load all" fetches it on demand).
                    val remoteBytes = SilentlyTry.loggedOrElse<Long?>("SshAi-Sessions", "stat prefetch size", null) {
                        val sess = client.startSession()
                        try {
                            val q = ai.eight24family.conch.agent.shellEscapeRemotePath(s.path)
                            val p = sess.exec(
                                "stat -c %s $q 2>/dev/null || stat -f %z $q 2>/dev/null || wc -c < $q 2>/dev/null",
                            )
                            val txt = p.inputStream.bufferedReader().readText().trim()
                            p.join(15, java.util.concurrent.TimeUnit.SECONDS)
                            txt.toLongOrNull()
                        } finally { SilentlyTry.fired("SshAi-Sessions", "close stat session") { sess.close() } }
                    }
                    if (remoteBytes != null &&
                        remoteBytes > ai.eight24family.conch.data.GlobalPrefetcher.PREFETCH_BODY_MAX_BYTES
                    ) {
                        val start = (remoteBytes - ai.eight24family.conch.data.GlobalPrefetcher.TAIL_PRELOAD_BYTES)
                            .coerceAtLeast(0L)
                        val slab = SilentlyTry.loggedOrElse<ByteArray?>("SshAi-Sessions", "fetch tail slab", null) {
                            val sess = client.startSession()
                            try {
                                val q = ai.eight24family.conch.agent.shellEscapeRemotePath(s.path)
                                val p = sess.exec("tail -c +${start + 1} $q")
                                val out = java.io.ByteArrayOutputStream()
                                p.inputStream.copyTo(out, 64 * 1024)
                                p.join(60, java.util.concurrent.TimeUnit.SECONDS)
                                out.toByteArray()
                            } finally { SilentlyTry.fired("SshAi-Sessions", "close tail session") { sess.close() } }
                        }
                        val (aligned, dropped) = ai.eight24family.conch.data.GlobalPrefetcher
                            .dropLeadingPartialLine(slab ?: ByteArray(0), isFileStart = start == 0L)
                        val safeTail = trimToLastNewline(aligned)
                        if (safeTail.isNotEmpty()) {
                            historyCache.saveTail(s.id, safeTail, newBase = start + dropped)
                            android.util.Log.d(tag, "  tail-cached ${s.id.take(8)} (${safeTail.size}B of ${remoteBytes}B)$viaPool")
                        }
                        done += 1
                        _prefetchProgress.update { it?.copy(done = done) }
                        kotlinx.coroutines.delay(150)
                        continue
                    }
                    val raw = discovery.fetchSessionContent(s.path) { cmd ->
                        SilentlyTry.logged("SshAi-Sessions", "fetch session content for prefetch") {
                            val sess = client.startSession()
                            try {
                                val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
                                val out = java.io.ByteArrayOutputStream()
                                proc.inputStream.copyTo(out)
                                proc.join(60, java.util.concurrent.TimeUnit.SECONDS)
                                String(out.toByteArray(), Charsets.UTF_8)
                            } finally { SilentlyTry.fired("SshAi-Sessions", "close prefetch session") { sess.close() } }
                        }
                    }
                    if (raw.isNullOrBlank()) {
                        android.util.Log.d(tag, "  skip ${s.id.take(8)} (empty/null)")
                        done += 1
                        _prefetchProgress.update { it?.copy(done = done) }
                        continue
                    }
                    val bytes = raw.toByteArray(Charsets.UTF_8)
                    val safe = trimToLastNewline(bytes)
                    if (safe.isEmpty()) {
                        done += 1
                        _prefetchProgress.update { it?.copy(done = done) }
                        continue
                    }
                    historyCache.save(s.id, safe)
                    done += 1
                    _prefetchProgress.update { it?.copy(done = done) }
                    android.util.Log.d(tag, "  cached ${s.id.take(8)} (${safe.size}B)$viaPool")
                } catch (t: Throwable) {
                    done += 1
                    _prefetchProgress.update { it?.copy(done = done) }
                    android.util.Log.w(tag, "  failed ${s.id.take(8)}: ${t.message}")
                }
                // Tiny breather between fetches keeps the host responsive.
                kotlinx.coroutines.delay(150)
            }
            android.util.Log.d(tag, "done$viaPool")
            // Show the green ✓ briefly so the user knows sync finished,
            // then clear the badge so it stops eating space in the
            // topbar's actions slot.
            kotlinx.coroutines.delay(3_000)
            _prefetchProgress.value = null
        }
    }

    private fun trimToLastNewline(bytes: ByteArray): ByteArray =
        ai.eight24family.conch.util.JsonlUtils.trimToLastNewline(bytes)

    override fun onCleared() {
        super.onCleared()
        prefetchJob?.cancel()
    }
}
