package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.RemoteSession
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One row in the unified Sessions home. [RemoteSession] already carries its
 * [Agent]; we pair it with the owning server so the row can render a badge
 * and open the right chat.
 */
data class HomeSessionRow(
    val serverId: String,
    val serverName: String,
    /** SSH user that owns this session ($HOME holds the agent's rollout). */
    val username: String,
    val host: String,
    val port: Int,
    val session: RemoteSession,
    /** True when an agent is actively generating in THIS session right now
     *  (a live AgentSession in Working state). Drives the spinner on the row
     *  so you can see — from the sessions list — which agents are busy, even
     *  several running in parallel. */
    val working: Boolean = false,
    /** Count of new messages produced in this session since the user last had
     *  it on-screen (0 = nothing new / no baseline yet). Drives the "N new"
     *  badge on the row. Only meaningful for sessions with a live AgentSession. */
    val unread: Int = 0,
    /** Best-known last-activity time (epoch MILLIS) from [ServiceLocator.sessionActivity],
     *  already maxed with the server file mtime. THE sort key AND the value the
     *  row's time stamp formats — millis end-to-end, no seconds/millis confusion. */
    val lastActiveMs: Long = 0L,
    /** The chat's LAST message (most recent user/assistant text), extracted from
     *  the cached body — the messenger-style preview shown dim under the name.
     *  Null when no body cached / nothing extractable. */
    val lastMessage: String? = null,
    /** Phone glyph state for this row (NONE/IDLE/LIVE). Same tri-state the
     *  per-server list and chat title use — colored when the bridge is live,
     *  dim when the session was wired but is offline now. */
    val phoneGlyph: ai.eight24family.conch.diagnostics.BridgePresence =
        ai.eight24family.conch.diagnostics.BridgePresence.NONE,
    /** Unsent text typed into this chat's input box (a saved draft), or null.
     *  Shown inline as "Draft: …" in place of the row's preview. */
    val draftText: String? = null,
    /** This session's agent is in a BLOCK Claude run-state (no subscription /
     *  trial ended / rate limited / login expired …). The row shows a [codeBadge]
     *  marker so the blocked state is visible in the session list too, not only in
     *  the agent picker / chat. */
    val codeBlocked: Boolean = false,
    /** Short badge for the blocked state (e.g. "no subscription", "rate limited"),
     *  or null when not blocked. */
    val codeBadge: String? = null,
    /** Work FINISHED here since the user's last visit: the file grew past the
     * seen watermark AND the server has gone quiet. Drives the row's ✓ mark —.
     * Cleared the moment the chat is opened (the watermark catches up). */
    val doneUnseen: Boolean = false,
)

/**
 * The unified **Sessions** home — a Telegram-style chat list: every cached
 * session across ALL servers × agents, newest first. This is the app's start
 * destination now; the old servers → agents → sessions funnel became
 * management tabs.
 *
 * Renders instantly from [SessionsCache] (cache-first, never blocks on SSH).
 * [GlobalPrefetcher] keeps the per-(server,agent) caches warm in the
 * background for connected servers; we re-read the merged list on a tick so
 * fresh / updated sessions surface without the user lifting a finger. No SSH
 * and no FIDO touch are needed just to SEE the list — that's the whole
 * point.
 */
class HomeSessionsViewModel : ViewModel() {

    private val repo = ServiceLocator.serverRepository
    private val cache = ServiceLocator.sessionsCache
    private val prefetcher = ServiceLocator.globalPrefetcher
    private val agentStatusCache = ServiceLocator.agentStatusCache

    val servers: StateFlow<List<Server>> = repo.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _rows = MutableStateFlow<List<HomeSessionRow>>(emptyList())
    val rows: StateFlow<List<HomeSessionRow>> = _rows.asStateFlow()

    /**
     * Per-server set of agents that are BOTH installed AND logged-in — i.e. the
     * agents you can actually open a chat with (a new session needs both). Drives
     * the filter chip bar (only usable agents get a chip) AND the "new session"
     * button's server picker. Refreshed each [reload] from [AgentStatusCache]
     * (populated by the prefetch sweep's first-contact probe). Empty until the
     * first probe lands. user:.
     */
    private val _usableByServer = MutableStateFlow<Map<String, Set<Agent>>>(emptyMap())
    val usableByServer: StateFlow<Map<String, Set<Agent>>> = _usableByServer.asStateFlow()

    /** The agent filter the user last picked (null = "All"). IN-MEMORY so a chip
     *  tap switches the list THIS frame — going through the DataStore flow added a
     *  round-trip lag during which the old list kept its scroll position, so the
     *  post-tap scroll-to-top ran on the stale list and the switch "stayed mid-list
     *  on Claude" (user). Hydrated once from prefs on init and persisted on every
     *  change, so the choice still survives a restart. */
    private val _agentFilter = MutableStateFlow<String?>(null)
    val agentFilter: StateFlow<String?> = _agentFilter.asStateFlow()

    /** True once the user explicitly picked a filter this session — guards the
     *  async prefs hydration from clobbering a fast early tap. */
    @Volatile private var filterUserSet = false

    fun setAgentFilter(agentName: String?) {
        filterUserSet = true
        _agentFilter.value = agentName // instant — the list switches this frame
        viewModelScope.launch {
            SilentlyTry.fired("SshAi-Home", "persist agent filter") {
                ServiceLocator.preferences.setHomeAgentFilter(agentName)
            }
        }
    }

    /** Servers the user un-ticked in the server filter (long-press an agent
     *  chip). In-memory for the same reason as [agentFilter] — a tick must
     *  re-filter the list this frame — hydrated from prefs on init and persisted
     *  on every change. HIDDEN ids, so a newly added server shows by default. */
    private val _hiddenServerIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenServerIds: StateFlow<Set<String>> = _hiddenServerIds.asStateFlow()

    @Volatile private var hiddenUserSet = false

    fun toggleServerVisible(serverId: String, visible: Boolean) {
        hiddenUserSet = true
        val next = if (visible) _hiddenServerIds.value - serverId else _hiddenServerIds.value + serverId
        _hiddenServerIds.value = next
        viewModelScope.launch {
            SilentlyTry.fired("SshAi-Home", "persist hidden servers") {
                ServiceLocator.preferences.setHiddenServerIds(next)
            }
        }
    }

    /** Show every server again — the escape hatch from a filter the user forgot
     *  they set (an all-hidden list looks like data loss otherwise). */
    fun showAllServers() {
        hiddenUserSet = true
        _hiddenServerIds.value = emptySet()
        viewModelScope.launch {
            SilentlyTry.fired("SshAi-Home", "clear hidden servers") {
                ServiceLocator.preferences.setHiddenServerIds(emptySet())
            }
        }
    }

    /** sessionId → when THIS process last had a turn of its own running there.
     *  Feeds the echo window that keeps a just-finished turn from re-lighting
     *  its own row; see the working-signal comment in [reload]. */
    private val ourTurnWorkingAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** False until the first cache read completes — lets the UI distinguish
     *  "genuinely no sessions" from "haven't loaded yet" (no empty flash). */
    private val _loadedOnce = MutableStateFlow(false)
    val loadedOnce: StateFlow<Boolean> = _loadedOnce.asStateFlow()

    /** Persisted delete tombstones ("<serverId>:<sessionId>"). A user-deleted
     *  session is filtered out of [reload] even when the prefetcher re-lists it
     *  into the cache before the server `rm` lands — fixes "deleted, reappeared
     *  at once" on this unified list. Shared with the per-server SessionsViewModel
     *  via AppPreferences.deletedSessions. */
    @Volatile private var tombstones: Set<String> = emptySet()

    /** Phone-wired session tags ("<serverId>:<sessionId>") — drives the small
     *  phone glyph on the row. Same prefs set the per-server list reads. */
    @Volatile private var phoneBridge: Set<String> = emptySet()

    /** chatId (resume id) → unsent input draft text — drives the inline "Draft: …". */
    @Volatile private var draftsByChat: Map<String, String> = emptyMap()

    /** Live connection rollup for the home header — connected vs. mid-handshake
     * right now. Drives the "connecting…" line so the user SEES the app
     * auto-connecting from launch on the screen they land on, instead of having
     * to open the Servers tab to notice the dots flip. */
    data class Connectivity(val connected: Int, val connecting: Int, val total: Int)
    private val _connectivity = MutableStateFlow(Connectivity(0, 0, 0))
    val connectivity: StateFlow<Connectivity> = _connectivity.asStateFlow()

    init {
        // Idempotent + process-scoped — warms every authorized (server,agent)
        // cache so the merged list fills/updates while the user sits here.
        prefetcher.start(viewModelScope)
        // Seed the agent filter from the persisted choice ONCE (survives restart),
        // unless the user already tapped a chip before this async read returned.
        viewModelScope.launch {
            val persisted = SilentlyTry.nullOnError {
                ServiceLocator.preferences.homeAgentFilter.first()
            }
            if (!filterUserSet) _agentFilter.value = persisted
        }
        viewModelScope.launch {
            val persisted = SilentlyTry.nullOnError {
                ServiceLocator.preferences.hiddenServerIds.first()
            }
            if (!hiddenUserSet && persisted != null) _hiddenServerIds.value = persisted
        }
        viewModelScope.launch {
            while (true) {
                // Home is the nav start destination, so this VM lives as long
                // as the app process — WITHOUT the foreground gate this walked
                // every server × agent × cached session off disk every 2.5 s
                // with the app minimized, forever (battery). The event-driven
                // collectors below keep the list correct; the periodic walk is
                // only for a user actually looking at it.
                if (ai.eight24family.conch.util.AppForeground.isForeground) reload()
                delay(2_500)
            }
        }
        // Reload the instant ANY session's activity advances — a local send/reply
        // or a fresh server-mtime sweep — so the bumped row jumps without waiting
        // up to 2.5s for the periodic tick. collectLatest + a short debounce
        // coalesces the burst of per-session observeRemote calls a listing sweep
        // fires into a single reload. The store is process-scoped + persisted, so
        // a VM that subscribes late (open app → straight into chat → send → only
        // then open Home) still ranks correctly — and so does the list after an
        // app restart, which the old in-memory bump could never survive.
        viewModelScope.launch {
            ServiceLocator.sessionActivity.changes.collectLatest {
                delay(250)
                reload()
            }
        }
        // A new chat opening / closing changes the in-flight set — reload at once
        // so a just-started session shows immediately (the periodic tick would
        // otherwise lag up to 2.5s). Active changes are rare, so this is cheap.
        viewModelScope.launch {
            ServiceLocator.agentSessions.active.collectLatest { reload() }
        }
        // Keep delete-tombstones fresh and reload AT ONCE when they change, so a
        // deletion drops the row immediately (don't wait for the 2.5s tick — and
        // beat the prefetcher re-listing the still-on-server file back in).
        viewModelScope.launch {
            ServiceLocator.preferences.deletedSessions.collect { tombstones = it; reload() }
        }
        // Keep the phone-wired set fresh; reload so the glyph appears the moment
        // a session is wired (or after a restart re-reads prefs).
        viewModelScope.launch {
            ServiceLocator.preferences.phoneBridgeSessions.collect { phoneBridge = it; reload() }
        }
        // Keep the "has draft" set fresh; reload so the hint appears/clears the
        // moment the user types/sends in a chat.
        viewModelScope.launch {
            ServiceLocator.preferences.draftsByChat.collect { draftsByChat = it; reload() }
        }
        // Poll the pool so the home header reflects auto-connect progress live —
        // a server mid-handshake counts as "connecting", a peek-alive one as
        // "connected". 600ms is snappy enough to show the ~2s launch handshake.
        viewModelScope.launch {
            val pool = ServiceLocator.sshConnectionPool
            while (true) {
                // Backgrounded: nobody sees the header — nap coarsely instead
                // of 100 wakeups/min (same rationale as the reload tick above).
                if (!ai.eight24family.conch.util.AppForeground.isForeground) {
                    delay(5_000)
                    continue
                }
                val srv = servers.value
                val connected = srv.count { pool.peek(it.id) != null }
                _connectivity.value = Connectivity(connected, pool.connectingIds().size, srv.size)
                delay(600)
            }
        }
    }

    // Claude's ai-title also lives in the locally CACHED session body, so we can
    // show it WITHOUT a server re-list. The listing only refreshes the title when
    // the server is connected; a dropped device key (Exhausted → no live conn)
    // would otherwise leave the row on the raw first message forever. Read the
    // head only (ai-title sits near the top), take the LAST match, memoise per
    // session (bodies rarely change titles; the reload tick fires every 2.5s).
    private val bodyTitleCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Server-mtime freshness window that counts as "an agent is working in
     *  this session". CLI writes the rollout continuously mid-turn; 90 s
     *  covers the 30 s relist cadence + slow moments without flapping. */
    private val SERVER_WORKING_WINDOW_MS = 90_000L

    /** How long after OUR OWN turn stops we ignore server-side activity signals
     *  for that session — long enough to swallow the final answer landing in the
     *  file and the mirror, short enough that work started on the server right
     *  after ours shows up immediately. */
    private val OUR_TURN_ECHO_MS = 20_000L

    /** sessionId → (cached size, seen bytes, count) memo so the 2.5 s reload
     *  only rescans a file tail when either side actually moved. */
    private val unreadCache =
        java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Long, Int>>()

    private fun durableUnreadCached(sessionId: String, seenB: Long): Int {
        val size = ServiceLocator.historyCache.size(sessionId)
        if (size <= seenB) return 0
        unreadCache[sessionId]?.let { (s, b, n) -> if (s == size && b == seenB) return n }
        val n = ServiceLocator.historyCache.newLinesSince(sessionId, seenB)
        unreadCache[sessionId] = Triple(size, seenB, n)
        return n
    }
    private val aiTitleRe = Regex("\"aiTitle\":\"([^\"]*)\"")
    /**
     * Sessions whose body-derived bits (Claude's ai-title, the last-message
     * preview) are not memoized YET, mapped to the agent that can parse them and
     * the row's last-activity stamp so the warm pass starts with what the user is
     * actually looking at.
     *
     * ⛔ WHY THIS EXISTS. [reload] used to call [titleFromBody] and
     * [lastMessageFromBody] inline for every row. Both are memoized, so on a warm
     * process they cost nothing — but on a COLD START the memos are empty, and the
     * first paint had to mmap and parse the tail of every cached body first: 380
     * sessions x 128 KB of JSONL, each line through the agent's own stream parser,
     * before a single row could appear. That is the (user, 2026-08-29) — the data
     * was on disk the whole time, the list was just behind ~48 MB of parsing. Rows
     * now paint from the cache alone and the bodies are read afterwards, newest
     * first, re-painting as they land.
     */
    private val coldBodies = java.util.concurrent.ConcurrentHashMap<String, Pair<Agent, Long>>()

    @Volatile private var warmingBodies = false

    /** Memoized ai-title only — never touches disk. Registers a miss for the warm
     *  pass; the row falls back to the listing's own title until then. */
    private fun titleFromBodyMemo(sessionId: String, agent: Agent, lastActiveMs: Long): String? {
        bodyTitleCache[sessionId]?.let { return it.ifEmpty { null } }
        if (ServiceLocator.historyCache.size(sessionId) > 0L) {
            coldBodies[sessionId] = agent to lastActiveMs
        }
        return null
    }

    /** Memoized last message only — never touches disk beyond the length check the
     *  memo is keyed on. A miss falls back to the server listing's preview, which
     *  is already in the sessions cache: a slightly older line beats an empty row. */
    private fun lastMessageMemo(sessionId: String, agent: Agent, lastActiveMs: Long): String? {
        val size = ServiceLocator.historyCache.size(sessionId)
        if (size <= 0L) return null
        lastMsgCache[sessionId]?.let { (sz, m) -> if (sz == size) return m.ifEmpty { null } }
        coldBodies[sessionId] = agent to lastActiveMs
        return null
    }

    /**
     * Fill the body memos in the background, newest row first, re-painting after
     * every batch. Runs only while the user is looking (the same gate [reload]
     * uses) and only one pass at a time.
     */
    private fun warmBodiesIfNeeded() {
        if (warmingBodies || coldBodies.isEmpty()) return
        warmingBodies = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                while (ai.eight24family.conch.util.AppForeground.isForeground) {
                    val batch = coldBodies.entries
                        .sortedByDescending { it.value.second }
                        .take(WARM_BATCH)
                        .map { it.key to it.value.first }
                    if (batch.isEmpty()) break
                    for ((id, agent) in batch) {
                        coldBodies.remove(id)
                        SilentlyTry.fired("SshAi-Home", "warm body preview") {
                            if (agent == Agent.CLAUDE) titleFromBody(id)
                            lastMessageFromBody(id, agent)
                        }
                    }
                    // Repaint with what just landed instead of making the user
                    // wait for the whole backlog.
                    reload()
                }
            } finally {
                warmingBodies = false
            }
        }
    }

    private fun titleFromBody(sessionId: String): String? {
        bodyTitleCache[sessionId]?.let { return it.ifEmpty { null } }
        val t = SilentlyTry.nullOnError {
            ServiceLocator.historyCache.load(sessionId)?.use { snap ->
                val buf = snap.buffer.duplicate()
                val n = minOf(buf.remaining(), 64 * 1024)
                val head = ByteArray(n)
                buf.get(head)
                aiTitleRe.findAll(String(head, Charsets.UTF_8)).lastOrNull()?.groupValues?.getOrNull(1)?.trim()
            }
        }?.takeIf { it.isNotBlank() }
        bodyTitleCache[sessionId] = t.orEmpty()
        return t
    }

    // The chat's LAST message (most recent user/assistant text) for the dim
    // preview under the name — the messenger-style "last message" the user asked
    // for (codex/all rows). Read the body TAIL (last 128KB = last message +
    // headroom), parse with the agent's OWN line parser, take the last UserText/
    // AssistantText. Memoised by (sid, body size) so it re-extracts only when the
    // body grows, not on every 2.5s tick.
    private val lastMsgCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private fun lastMessageFromBody(sessionId: String, agent: Agent): String? {
        val size = ServiceLocator.historyCache.size(sessionId)
        if (size <= 0L) return null
        lastMsgCache[sessionId]?.let { (sz, m) -> if (sz == size) return m.ifEmpty { null } }
        val spec = ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent]
        val result = SilentlyTry.nullOnError {
            ServiceLocator.historyCache.load(sessionId)?.use { snap ->
                val buf = snap.buffer
                val total = buf.remaining()
                val tailN = minOf(total, 128 * 1024)
                val dup = buf.duplicate()
                dup.position(total - tailN)
                val bytes = ByteArray(tailN)
                dup.get(bytes)
                val raw = String(bytes, Charsets.UTF_8)
                // Drop a partial first line if the 128KB slice cut mid-line.
                val body = if (tailN < total) raw.substringAfter('\n') else raw
                var last: String? = null
                body.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val msgs = SilentlyTry.nullOnError { spec.parseStreamLine(line) } ?: return@forEach
                    for (m in msgs) {
                        val t = when (m) {
                            is ai.eight24family.conch.agent.AgentMessage.UserText -> m.text
                            is ai.eight24family.conch.agent.AgentMessage.AssistantText -> m.text
                            else -> null
                        }
                        if (!t.isNullOrBlank()) last = t
                    }
                }
                last?.replace(Regex("\\s+"), " ")?.trim()?.take(140)
            }
        }
        lastMsgCache[sessionId] = size to result.orEmpty()
        return result?.ifBlank { null }
    }

    private suspend fun reload() {
        // Deferred deletes first; a cheap no-op unless something is tombstoned.
        reconcileTombstones()
        val list = servers.value.ifEmpty { repo.observeServers().first() }
        val out = ArrayList<HomeSessionRow>()
        val usable = HashMap<String, Set<Agent>>()
        // Privileged-capability layer for the 📱 glyph, sampled once per reload: is
        // Shizuku's binder alive AND granted RIGHT NOW. The channel layer
        // (BridgeHealth heartbeat) is checked per-server below. Both must be live,
        // else the glyph over-claims.
        val shizukuOk = ai.eight24family.conch.diagnostics.ShizukuShell.available()
        for (s in list) {
            // Agents on THIS server that are installed AND logged-in — the only
            // ones a chat can be opened with. Read from the status cache (filled
            // by the prefetch probe); absent/unprobed server → empty set.
            val srvStatuses = SilentlyTry.loggedOrElse(
                "SshAi-Home", "load agent status", emptyMap<Agent, ai.eight24family.conch.agent.AgentStatus>(),
            ) { agentStatusCache.load(s.id).statuses }
            // "Usable" excludes an agent in a BLOCK run-state — logged in but can't
            // actually run a turn, so it must NOT count as a new-chat target (else
            // the FAB offers a doomed chat).
            usable[s.id] = srvStatuses
                .filterValues { it.installed && it.loggedIn && it.claudeState?.isBlocked != true }.keys
            for (agent in Agent.entries) {
                val agentState = srvStatuses[agent]?.claudeState
                val agentBlocked = agentState?.isBlocked == true
                val snap = cache.load(s.id, agent)
                for (sess in snap.sessions) {
                    // User-deleted → never show, even if the prefetcher just
                    // re-listed it back into the cache (server `rm` not landed yet).
                    if ("${s.id}:${sess.id}" in tombstones) continue
                    val live = ServiceLocator.agentSessions.findByResume(s.id, agent, sess.id)
                    val liveWorking = live != null &&
                        live.state.value == ai.eight24family.conch.agent.SessionState.Working
                    // SERVER-driven working — survives an app restart. The
                    // relist sweep refreshes mtimes every 30 s.
                    val serverWorking =
                        System.currentTimeMillis() - sess.lastActiveAt * 1000L < SERVER_WORKING_WINDOW_MS
                    // Local-cache freshness beats both other signals — but only
                    // LIVE appends count (open chat's poller / a background
                    // catch-up of a server-hot session). File mtime was the old
                    // key, and OUR OWN housekeeping writes move it: every
                    // background catch-up of a cold session lit "working" on a
                    // chat finished 12+ hours earlier, one 90 s flash per sweep
                    // (user 2026-08-17). The signal still stops the ✓ over a
                    // VISIBLY streaming session (2026-08-11) — those appends are
                    // live by definition.
                    val cacheWorking =
                        System.currentTimeMillis() - ServiceLocator.historyCache.lastLiveActivityMs(sess.id) < SERVER_WORKING_WINDOW_MS
                    // OUR OWN just-finished turn ECHOES: the final answer lands in
                    // the file and in the mirror right after our state goes idle,
                    // and both look like activity. Suppress the server signals for
                    // a SHORT window after our turn ends — that kills the ~90 s
                    // ghost spinner (2026-08-19 01:23) without going deaf to real
                    // work. ⚠ It must be a WINDOW, not "our session is idle".
                    // Keying on idleness alone meant a session the user was
                    // driving from the laptop CLI never lit up at all — the app
                    // holds an adopted, idle AgentSession for it, so every
                    // server-side signal was thrown away.
                    if (liveWorking) ourTurnWorkingAtMs[sess.id] = System.currentTimeMillis()
                    val echoing = !liveWorking &&
                        System.currentTimeMillis() - (ourTurnWorkingAtMs[sess.id] ?: 0L) < OUR_TURN_ECHO_MS
                    val working = liveWorking || (!echoing && (serverWorking || cacheWorking))
                    // Unread: live message count while this process has the
                    // session (precise); otherwise the DURABLE byte watermark
                    // vs the mirrored body — new JSONL lines since last view.
                    val liveUnread = if (live != null)
                        ai.eight24family.conch.agent.SessionSeenTracker.unread(sess.id, live.history.value.size)
                    else 0
                    val durableUnread = if (liveUnread > 0) 0 else run {
                        val seenB = ServiceLocator.historyCache.seenBytes(sess.id)
                        if (seenB == null) 0 else durableUnreadCached(sess.id, seenB)
                    }
                    val unread = maxOf(liveUnread, durableUnread)
                    val doneUnseen = !working && unread > 0
                    // Single source of truth for "last activity": the persisted,
                    // monotonic SessionActivityStore (fed by local sends/replies +
                    // remote mtime sweeps, maxed at write time). Fall back to the
                    // cached file mtime (×1000 → millis) only for a session the
                    // store has never seen. Millis end-to-end — no seconds/millis
                    // confusion, and it survives restarts.
                    val storeMs = ServiceLocator.sessionActivity.lastActivity(s.id, sess.id)
                    val lastActiveMs = maxOf(storeMs, sess.lastActiveAt * 1000L)
                    // AUTHENTIC per-agent naming: ONLY Claude has its own session
                    // title (ai-title), so only Claude gets a title lookup.
                    // Codex/Gemini are named by their first message — their real
                    // identity, exactly like their own `resume` picker — never an
                    // invented or stray title. Prefer the listing's ai-title; fall
                    // back to the cached body's ai-title when the server isn't
                    // re-listed (device key dropped / offline). sess.copy keeps the
                    // UI reading row.session.title.
                    val resolvedTitle = sess.title
                        ?: if (agent == Agent.CLAUDE) titleFromBodyMemo(sess.id, agent, lastActiveMs) else null
                    val rowSess = if (sess.title == null && resolvedTitle != null)
                        sess.copy(title = resolvedTitle) else sess
                    // The chat's last message — dim preview under the name (all agents).
                    // Memo or the listing's preview; the body is read off the
                    // critical path (see [coldBodies]).
                    val lastMsg = lastMessageMemo(sess.id, agent, lastActiveMs)
                        ?: sess.preview.takeIf { it.isNotBlank() }
                    out += HomeSessionRow(
                        s.id, s.name, s.username, s.host, s.port, rowSess, working, unread, lastActiveMs, lastMsg,
                        phoneGlyph = ai.eight24family.conch.diagnostics.bridgePresenceOf(
                            "${s.id}:${sess.id}" in phoneBridge, s.id, shizukuOk),
                        draftText = draftsByChat[sess.id],
                        codeBlocked = agentBlocked,
                        codeBadge = if (agentBlocked) agentState?.badge else null,
                        doneUnseen = doneUnseen,
                    )
                }
            }
        }
        // In-flight chats the server hasn't listed yet → show them INSTANTLY so a
        // freshly-started session appears the moment the user writes. Deduped
        // against the cached rows by (server, agent, id); once the session is
        // listed + cached, the richer cache row wins and this synthetic one drops
        // out. Only sessions that already have a user message qualify — an empty
        // just-opened chat isn't list-worthy yet.
        val seen = out.mapTo(HashSet()) {
            it.serverId + "|" + it.session.agent.name + "|" + it.session.id
        }
        // Also index cached rows by (server, agent, first-message preview): a
        // RESUMED chat opened but not yet sent-in has currentResumeId == null, so
        // its synthetic row keys on the LOCAL id and the id-dedup above MISSES its
        // cached row → a phantom duplicate. Content-dedup catches that — same
        // first message on the same server+agent ⇒ same session, skip the
        // synthetic.
        val seenPreview = out.mapTo(HashSet()) {
            it.serverId + "|" + it.session.agent.name + "|" + it.session.preview.trim()
        }
        for (info in ServiceLocator.agentSessions.active.value) {
            val sess = ServiceLocator.agentSessions.get(info.serverId, info.agent, info.chatSessionId)
                ?: continue
            val hist = sess.history.value
            val firstUser = hist.firstOrNull { it is ai.eight24family.conch.agent.AgentMessage.UserText }
                ?.let { (it as ai.eight24family.conch.agent.AgentMessage.UserText).text }
                ?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.takeIf { it.isNotBlank() }
                ?: continue
            val id = sess.currentResumeId ?: info.chatSessionId
            if ("${info.serverId}:$id" in tombstones) continue
            val key = info.serverId + "|" + info.agent.name + "|" + id
            if (key in seen) continue
            // Same first message on this server+agent as an already-listed (cached)
            // session ⇒ it's that session opened/resumed, not a new one. Skip the
            // synthetic row so a resume-without-send never shows as a copy.
            if ("${info.serverId}|${info.agent.name}|${firstUser.trim()}" in seenPreview) continue
            val server = list.firstOrNull { it.id == info.serverId } ?: continue
            val working = sess.state.value == ai.eight24family.conch.agent.SessionState.Working
            val lastMsg = hist.lastOrNull {
                it is ai.eight24family.conch.agent.AgentMessage.UserText ||
                    it is ai.eight24family.conch.agent.AgentMessage.AssistantText
            }?.let {
                when (it) {
                    is ai.eight24family.conch.agent.AgentMessage.UserText -> it.text
                    is ai.eight24family.conch.agent.AgentMessage.AssistantText -> it.text
                    else -> null
                }
            }?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
            val storeMs = maxOf(
                ServiceLocator.sessionActivity.lastActivity(info.serverId, id),
                ServiceLocator.sessionActivity.lastActivity(info.serverId, info.chatSessionId),
            )
            val lastActiveMs = if (storeMs > 0L) storeMs else System.currentTimeMillis()
            val remote = ai.eight24family.conch.agent.RemoteSession(
                id = id, path = "", agent = info.agent, lastActiveAt = 0L,
                preview = firstUser, title = null,
            )
            out += HomeSessionRow(
                server.id, server.name, server.username, server.host, server.port,
                remote, working, 0, lastActiveMs, lastMsg?.takeIf { it != firstUser },
                phoneGlyph = ai.eight24family.conch.diagnostics.bridgePresenceOf(
                    "${info.serverId}:$id" in phoneBridge, info.serverId, shizukuOk),
                draftText = draftsByChat[id],
            )
            seen += key
        }
        out.sortByDescending { it.lastActiveMs }
        _usableByServer.value = usable
        // Per-agent row count — logged only when it CHANGES (not every 2.5s tick),
        // so "where are my codex/gemini sessions" is one logcat line away without
        // spamming. (They're in the list; the recency sort just buries the older
        // agent below a burst of recent Claude — hence the filter chips.)
        val counts = out.groupingBy { it.session.agent.name }.eachCount()
        if (counts != lastLoggedCounts) {
            lastLoggedCounts = counts
            android.util.Log.d("SshAi-Home", "reload: ${out.size} rows by agent=$counts")
        }
        // Final safety net: the home list keys its LazyColumn by
        // serverId/agent/sessionId and Compose HARD-CRASHES on a duplicate key
        // ("Key … was already used" — user's crash, v0.2.4). The cache dedupe above
        // should make this a no-op, but a duplicate server row in `list` or any
        // future feeder could still double a row — so we guarantee uniqueness at
        // the single choke point right before the UI. distinctBy keeps the first,
        // which after the recency sort is the most-recently-active copy.
        _rows.value = out.distinctBy { it.serverId + "|" + it.session.agent.name + "|" + it.session.id }
        _loadedOnce.value = true
        // The list is on screen; NOW go read the bodies whose previews are missing.
        warmBodiesIfNeeded()
    }

    /** How many bodies one warm batch reads before the list repaints. Big enough
     *  that a 380-session backlog drains in a handful of passes, small enough that
     *  the first repaint (the visible top of the list) lands almost at once. */
    private val WARM_BATCH = 40

    /** Last by-agent row counts we logged, so [reload] logs only on change. */
    @Volatile private var lastLoggedCounts: Map<String, Int> = emptyMap()

    /**
     * Delete one session (swipe-to-reveal → Delete, same as the per-agent
     * list). Optimistically drops the row, prunes the owning (server,agent)
     * cache + cached body, then removes the file(s) on the server over the
     * live pooled connection (no fresh handshake / SK touch). No pool ⇒
     * local-only removal — it just won't re-appear from cache.
     */
    fun deleteSession(row: HomeSessionRow) {
        val serverId = row.serverId
        val agent = row.session.agent
        val session = row.session
        // Tombstone SYNCHRONOUSLY so the very next reload() (the active/activity
        // collectors fire one almost immediately) filters it out instead of
        // re-adding it from a not-yet-pruned cache.
        tombstones = tombstones + "$serverId:${session.id}"
        _rows.update { list ->
            list.filterNot { it.serverId == serverId && it.session.id == session.id && it.session.path == session.path }
        }
        viewModelScope.launch(Dispatchers.IO) {
            SilentlyTry.fired("SshAi-Home", "persist delete tombstone") {
                ServiceLocator.preferences.setDeletedSession(serverId, session.id, true)
            }
            SilentlyTry.fired("SshAi-Home", "prune session from cache") {
                val snap = cache.load(serverId, agent)
                val pruned = snap.sessions.filterNot { it.id == session.id && it.path == session.path }
                cache.save(serverId, agent, pruned)
            }
            // WARNING: THE LOCAL BODY IS THE ONLY COPY LEFT IF THE SERVER `rm`
            // DOES NOT LAND. It used to be deleted right here, BEFORE the transport
            // was even checked, so deleting while offline hid the row behind a
            // persisted tombstone, destroyed the cached conversation AND its owner
            // sidecar, and never reached the server. The session lived on the
            // machine and was gone from the app forever: not in Home, not in
            // search, no trash, no undo, and the only screen that re-fires the `rm`
            // is the per-agent list for that exact (server, agent) pair. Clearing
            // app data was the only reset.
            //
            // So the server delete comes FIRST, and the local body is dropped only
            // once it has actually happened. A tombstoned session whose `rm` is
            // still owed stays hidden but INTACT, and [reconcileTombstones]
            // finishes the job the next time that server is reachable.
            var pooled = ServiceLocator.sshConnectionPool.peek(serverId)
            if (pooled == null) {
                // The same courtesy the per-agent screen extends: try to come up
                // silently before giving up on the remote half.
                SilentlyTry.fired("SshAi-Home", "silent connect for delete") {
                    ServiceLocator.sshConnectionPool.connectAllPossibleSilently()
                }
                pooled = ServiceLocator.sshConnectionPool.peek(serverId)
            }
            if (pooled == null) {
                android.util.Log.w(
                    "SshAi-Home",
                    "deleteSession ${session.id.take(8)}: offline, hidden but body KEPT; " +
                        "server rm deferred to reconcile",
                )
                return@launch
            }
            val inner = ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent]
                .deleteSessionCommand(session.id, session.path)
            val cmd = ai.eight24family.conch.agent.RemoteEnv.portable(
                "bash -lc " + ai.eight24family.conch.agent.shellEscape(inner),
            )
            val removed = SilentlyTry.logged("SshAi-Home", "delete session on server") {
                val sess = pooled.startSession()
                try {
                    val proc = sess.exec(cmd)
                    proc.inputStream.readBytes()
                    proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                    true
                } finally {
                    SilentlyTry.fired("SshAi-Home", "close delete session") { sess.close() }
                }
            } == true
            if (removed) {
                SilentlyTry.fired("SshAi-Home", "forget cached session body") {
                    ServiceLocator.historyCache.forget(session.id)
                }
            }
        }
    }

    /**
     * Finish deletes whose server `rm` never landed.
     *
     * A delete made offline is only half done: the row is hidden by a persisted
     * tombstone while the conversation is still on the machine. Without this the
     * other half only ever ran from the per-agent sessions list, a screen the user
     * reaches by drilling Agents -> server -> agent, so a session deleted on the
     * train stayed on the server indefinitely while looking deleted in the app.
     *
     * Runs on reload, and only when something is actually tombstoned AND that
     * server is reachable, so it costs nothing in the common case.
     */
    private fun reconcileTombstones() {
        if (tombstones.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (tag in tombstones) {
                val serverId = tag.substringBefore(':')
                val sessionId = tag.substringAfter(':')
                if (serverId.isBlank() || sessionId.isBlank()) continue
                val pooled = ServiceLocator.sshConnectionPool.peek(serverId) ?: continue
                // Still in a cache for this server means a listing saw it, which
                // means the server still has it. Nothing cached, nothing owed.
                val owed = Agent.entries.mapNotNull { agent ->
                    val snap = SilentlyTry.logged("SshAi-Home", "load cache for reconcile") {
                        cache.load(serverId, agent)
                    }
                    snap?.sessions?.firstOrNull { it.id == sessionId }?.let { agent to it }
                }
                for ((agent, sess) in owed) {
                    val inner = ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent]
                        .deleteSessionCommand(sess.id, sess.path)
                    val cmd = ai.eight24family.conch.agent.RemoteEnv.portable(
                        "bash -lc " + ai.eight24family.conch.agent.shellEscape(inner),
                    )
                    android.util.Log.i(
                        "SshAi-Home",
                        "reconcile: finishing deferred delete of ${sess.id.take(8)} on $serverId",
                    )
                    val ok = SilentlyTry.logged("SshAi-Home", "reconcile delete") {
                        val ch = pooled.startSession()
                        try {
                            val proc = ch.exec(cmd)
                            proc.inputStream.readBytes()
                            proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                            true
                        } finally {
                            SilentlyTry.fired("SshAi-Home", "close reconcile session") { ch.close() }
                        }
                    } == true
                    if (ok) {
                        SilentlyTry.fired("SshAi-Home", "forget body after reconcile") {
                            ServiceLocator.historyCache.forget(sess.id)
                        }
                    }
                }
            }
        }
    }

    // NOTE: no prefetcher.stop() — it's deliberately process-scoped and meant
    // to keep indexing after the user navigates away.
}
