package ai.eight24family.conch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.eight24family.conch.MainActivity
import ai.eight24family.conch.R
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SshAiService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null
    private var reconnectWatchdogJob: Job? = null
    private var repostJob: Job? = null
    /** Default-network watcher — detects Wi-Fi⇄cellular handoffs so held
     *  connections are restored (or kept + surfaced) the instant the network
     *  flips, not after a 30 s keepalive timeout. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var netReconnectJob: Job? = null
    /** Cached last-rendered counts so the re-post ticker can rebuild
     *  the notification without having to re-collect from the flows. */
    @Volatile private var lastActive = 0
    @Volatile private var lastHeld = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Read CURRENT state into the initial notification instead of
        // posting (0, 0) blindly. The service was started from somewhere
        // that already knows there's work to do (userConnect / session
        // start), so by the time onCreate runs the flows should
        // typically already show > 0. Posting (0, 0) here was producing
        // a brief flash of "Conch · Ready" that the user saw and
        // (rightly) called meaningless.
        val initActive = SilentlyTry.loggedOrElse("SshAi-Service", "read initial active count", 0) { ServiceLocator.agentSessions.activeCount.value }
        val initIds = SilentlyTry.loggedOrElse("SshAi-Service", "read initial user-held ids", emptySet()) { ServiceLocator.sshConnectionPool.userHeldIds.value }
        val initHeld = initIds.size
        lastActive = initActive
        lastHeld = initHeld
        // First foreground notification — must be posted from onCreate
        // before any other work (Android requires startForeground
        // within ~5 s of service start). Pick the first server's
        // per-server notification if available; fall back to a
        // generic count summary if we somehow got here with empty ids.
        val initialNotif = if (initIds.isNotEmpty()) {
            val firstId = initIds.sorted().first()
            val name = SilentlyTry.logged("SshAi-Service", "resolve initial server name") {
                val repo = ServiceLocator.serverRepository
                kotlinx.coroutines.runBlocking { repo.getById(firstId)?.name }
            } ?: "server"
            buildServerNotification(firstId, name, live = ServiceLocator.sshConnectionPool.peek(firstId) != null)
        } else {
            buildNotification(initActive, initHeld)
        }
        startForegroundCompat(initialNotif)
        // If we got here on stale signals (caller bumped a count then
        // dropped it before onCreate ran), self-stop immediately rather
        // than sitting on a "Disconnected" notification.
        if (initActive == 0 && initHeld == 0) {
            Log.d("SshAiService", "onCreate with no active/held — stopping immediately")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // Start the agent ↔ phone bridge. It lives for the lifetime
        // of the foreground service — the moment all chats close
        // and the user-intent ref is dropped, the service stops
        // and the bridge with it. CLAUDE.md §11.5 has the protocol.
        SilentlyTry.fired("SshAi-Service", "start bridge manager") { ServiceLocator.bridgeManager.start() }
        // Self-healing reconnect watchdog. An idle SSH drop (no network-change,
        // no app-foreground event) used to leave a held server transport-down
        // with no recovery except a manual server re-login — the ONLY thing that
        // revived the phone bridge after it went deaf (session finding REL-2:
        // chat recovers on the next send, but the bridge poller's peek() never
        // came back). While the service is up, silently restore any held-but-down
        // server every 20s (seamless device key → no tap), so peek() returns and
        // the bridge poller resumes on its own — zero user action.
        if (reconnectWatchdogJob?.isActive != true) {
            reconnectWatchdogJob = scope.launch {
                while (isActive) {
                    delay(20_000L)
                    SilentlyTry.fired("SshAi-Service", "watchdog reconnect held-but-down") {
                        ServiceLocator.sshConnectionPool.reconnectHeldButDownSilently()
                    }
                }
            }
        }
        // The service stays alive as long as EITHER there's an in-flight
        // agent session OR the user has explicitly connected to at least
        // one server (pool.userHeld). The user wants connections to
        // outlive backgrounding, swiping the app away, even the Activity
        // being destroyed — without an explicit Disconnect we hold the
        // socket. The only auto-stop is when BOTH counts hit zero, which
        // happens only after every chat is closed AND every userDisconnect
        // has fired.
        observerJob = scope.launch {
            combine(
                ServiceLocator.agentSessions.activeCount,
                ServiceLocator.sshConnectionPool.userHeldIds,
            ) { active, ids -> active to ids }
                .collect { (active, ids) ->
                    lastActive = active
                    lastHeld = ids.size
                    if (active == 0 && ids.isEmpty()) {
                        Log.d("SshAiService", "active=0 held=0 — stopping self")
                        // Pull the notification BEFORE stopSelf so the
                        // user never sees the "Disconnected" placeholder
                        // briefly — stopSelf takes a few hundred ms to
                        // tear down which is enough for the eye to catch
                        // a stale notif.
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    refreshNotifications(active, ids)
                }
        }
        // Re-post ticker. setOngoing(true) is honored by stock Android
        // but Huawei (and to a lesser extent Xiaomi / Vivo / some
        // Samsung skins) let "Clear all" wipe even foreground-service
        // notifications. The service stays alive, but the user loses
        // the persistent UI + the End action. Every 20 s we check
        // whether NOTIF_ID is still in the active set and re-notify
        // if it isn't. Cheap (no Builder unless we actually need to
        // re-post) and idempotent — stock OEMs that respect setOngoing
        // never trigger the re-post branch.
        repostJob = scope.launch {
            while (isActive) {
                delay(20_000)
                SilentlyTry.fired("SshAi-Service", "repaint+repost notification tick") {
                    // Repaint each tick so a transport that died silently
                    // (keepalive timeout with no network event) flips its
                    // row to "reconnect" within 20 s. We DELIBERATELY no
                    // longer prune user-intent here: a dropped connection
                    // must stay VISIBLE and recoverable, not vanish. Intent
                    // is dropped only by an explicit End / userDisconnect.
                    // refreshNotifications re-issues startForeground for the
                    // anchor, which also covers the OEM "clear all" wipe.
                    val active = ServiceLocator.agentSessions.activeCount.value
                    val ids = ServiceLocator.sshConnectionPool.userHeldIds.value
                    lastActive = active
                    lastHeld = ids.size
                    if (active == 0 && ids.isEmpty()) {
                        Log.d("SshAiService", "tick: both counts zero — stopping self")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@fired
                    }
                    refreshNotifications(active, ids)
                }
            }
        }
        registerNetworkMonitor()
    }

    /**
     * Watch the default network. When it changes (Wi-Fi⇄cellular, dropped and
     * came back), reconcile every held connection: password/key servers
     * reconnect silently on the new network; FIDO/SK servers keep their intent
     * (one tap restores them). This is what was missing — nothing reacted to a
     * live handoff, so a held SSH just died and the app silently forgot it.
     */
    private fun registerNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scheduleNetworkReconnect("available") }
            override fun onLost(network: Network) { scheduleNetworkReconnect("lost") }
        }
        networkCallback = cb
        SilentlyTry.fired("SshAi-Service", "register default network callback") {
            cm.registerDefaultNetworkCallback(cb)
        }
    }

    /** Debounced network-change handler. Networks flap during a handoff, so we
     *  coalesce to a single reconcile ~2 s after the dust settles. No-op when
     *  every held transport is already alive. */
    private fun scheduleNetworkReconnect(reason: String) {
        netReconnectJob?.cancel()
        netReconnectJob = scope.launch {
            delay(2_000)
            Log.d("SshAiService", "network change ($reason) — reconciling held connections")
            SilentlyTry.fired("SshAi-Service", "reconnect held on network change") {
                ServiceLocator.sshConnectionPool.reconnectHeldOnNetworkChange()
            }
            val ids = ServiceLocator.sshConnectionPool.userHeldIds.value
            lastHeld = ids.size
            if (ids.isNotEmpty()) refreshNotifications(lastActive, ids)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ACTION_END_ALL fires from the notification's "End" action. Drop
        // every user-intent SSH ref, close every live AgentSession, then
        // let the activeCount+held observer in onCreate notice 0/0 and
        // self-stop. We do NOT call stopSelf() directly here — the observer
        // path also clears the notification cleanly via the same code that
        // updates it on normal lifecycle, so we don't get a flash of "0
        // active" before the notif disappears.
        if (intent?.action == ACTION_END_ALL) {
            Log.d("SshAiService", "ACTION_END_ALL received from notification")
            scope.launch {
                SilentlyTry.fired("SshAi-Service", "ACTION_END_ALL disconnect+close") {
                    val pool = ServiceLocator.sshConnectionPool
                    pool.userHeldIds().toList().forEach { pool.userDisconnect(it) }
                    ServiceLocator.agentSessions.closeAll()
                }
            }
            return START_NOT_STICKY
        }
        // ACTION_END_ONE — per-server End button from a server-specific
        // notification. Disconnects ONLY the named server (and its
        // chat sessions); other servers stay live. Same observer path
        // updates the remaining notifications.
        if (intent?.action == ACTION_END_ONE) {
            val sid = intent.getStringExtra(EXTRA_SERVER_ID)
            Log.d("SshAiService", "ACTION_END_ONE received for serverId=$sid")
            if (!sid.isNullOrBlank()) {
                scope.launch {
                    SilentlyTry.fired("SshAi-Service", "ACTION_END_ONE disconnect+close") {
                        ServiceLocator.sshConnectionPool.userDisconnect(sid)
                        ServiceLocator.agentSessions.closeAllForServer(sid)
                        // Manually pull the per-server notification —
                        // the observer also clears it via refreshNotifications,
                        // but doing it here avoids a brief flash while
                        // the flow settles.
                        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        mgr.cancel(serverNotifId(sid))
                    }
                }
            }
            return START_NOT_STICKY
        }
        // ACTION_REPOST — our ongoing notification was dismissed (swipe, or an OEM
        // "Clear all" that ignores FLAG_NO_CLEAR). Re-post AT ONCE instead of
        // waiting for the 20 s ticker — the persistent connection indicator must
        // reappear immediately. Idempotent: if the connection is genuinely gone
        // (0/0) we stop rather than flash a stale row.
        if (intent?.action == ACTION_REPOST) {
            Log.d("SshAiService", "ACTION_REPOST — notification dismissed, re-posting now")
            SilentlyTry.fired("SshAi-Service", "repost on dismiss") {
                val active = ServiceLocator.agentSessions.activeCount.value
                val ids = ServiceLocator.sshConnectionPool.userHeldIds.value
                if (active == 0 && ids.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    refreshNotifications(active, ids)
                }
            }
            return START_STICKY
        }
        // START_STICKY: Android will restart this service after a process kill
        // (e.g. OOM, crash). NOTE: the re-created service has an EMPTY SSHClient
        // pool — all live channels are gone, AgentSessionManager is reset, and
        // any SK-keyed server will need a fresh user tap to reconnect. The
        // foreground notification will momentarily show "0 active" until either
        // a chat resumes the connection or the service self-stops via the
        // activeCount==0 observer in onCreate.
        return START_STICKY
    }

    // ── Android 15 dataSync time limit ──────────────────────────────
    // dataSync foreground services get a ~6h cumulative budget while the
    // app is in the BACKGROUND; at exhaustion the system calls onTimeout
    // and we MUST stop within seconds — otherwise the OS kills the app
    // with ForegroundServiceDidNotStopInTimeException (seen in the crash
    // buffer, 2026-06-10). The budget resets when the app returns to the
    // foreground; MainActivity.onStart() re-arms the service when there
    // is still work (active sessions / held connections).
    //
    // We deliberately do NOT drop user intent or close sessions here:
    // the pool + session managers are process-scoped singletons that
    // survive the service, and the CLI agent keeps working SERVER-side
    // regardless — reopening the app restores the service and the
    // usual reconnect paths revive the transports.
    override fun onTimeout(startId: Int) = handleFgsTimeout("onTimeout($startId)")

    override fun onTimeout(startId: Int, fgsType: Int) =
        handleFgsTimeout("onTimeout($startId, type=$fgsType)")

    private fun handleFgsTimeout(reason: String) {
        Log.w("SshAiService", "$reason — dataSync background budget exhausted; stopping gracefully")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        SilentlyTry.fired("SshAi-Service", "stop bridge manager") { ServiceLocator.bridgeManager.stop() }
        networkCallback?.let { cb ->
            SilentlyTry.fired("SshAi-Service", "unregister network callback") {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        netReconnectJob?.cancel()
        observerJob?.cancel()
        reconnectWatchdogJob?.cancel()
        repostJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        // On Android 13+ (TIRAMISU), startForeground() throws SecurityException
        // if the user denied POST_NOTIFICATIONS — the system requires a visible
        // foreground notification, and no permission means it can't show one.
        // Gracefully stop ourselves instead of crashing; the user-facing UI will
        // re-prompt for the permission next time they try to open a chat.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.w(
                "SshAiService",
                "startForeground denied (likely POST_NOTIFICATIONS revoked); stopping self",
                e,
            )
            stopSelf()
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+, extends
            // IllegalStateException): thrown when the dataSync background
            // budget is already exhausted — the START_STICKY restart after
            // an onTimeout stop used to CRASH-LOOP here ("Time limit
            // already exhausted for foreground service type dataSync",
            // 2026-06-10). Stop quietly; MainActivity.onStart() re-arms
            // the service when the user returns (foreground resets the
            // budget).
            Log.w("SshAiService", "startForeground not allowed (FGS budget/state); stopping self", e)
            stopSelf()
        }
    }

    /**
     * Routes back into [onStartCommand] with [ACTION_REPOST] so the connection
     * notification re-posts IMMEDIATELY rather than after the 20 s ticker.
     * Service-targeted, no extra BroadcastReceiver needed — the service is
     * foreground/alive whenever the notification exists, so delivering
     * onStartCommand is always allowed.
     */
    private fun repostDeleteIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            REPOST_REQUEST_CODE,
            Intent(this, SshAiService::class.java).setAction(ACTION_REPOST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildNotification(activeCount: Int, heldCount: Int, heldNames: List<String> = emptyList()): Notification {
        // Tap the body → open the app at whatever screen it was last on
        // (singleTop on MainActivity in the manifest).
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Action "End" → routes through this service with ACTION_END_ALL,
        // which drops every user-intent SSH ref + closes every chat. The
        // observer in onCreate then sees 0/0 and self-stops, taking the
        // notification with it. Service-targeted PendingIntent (not
        // broadcast) so we don't need a separate BroadcastReceiver.
        val endIntent = Intent(this, SshAiService::class.java).apply {
            action = ACTION_END_ALL
        }
        val endPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 1, endIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this, 1, endIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        // Connection-focused copy: the user doesn't care whether an
        // agent is mid-turn — they care that the SSH session is alive
        // so the next chat-open doesn't re-prompt for the security key.
        // Both branches frame it that way.
        val connected = heldCount.coerceAtLeast(if (activeCount > 0) 1 else 0)
        // Drop the "Conch · " prefix — the app name is already in
        // Android's notification header (small icon + app name strip),
        // repeating it in the title just chops off the real info on
        // narrow screens. Same reason "Tap to open" is gone from the
        // body: tapping the notification opens its app, that's
        // platform convention, users know it.
        // Title:
        //   1 server  → use its name (no "1 server connected" — user
        //               explicitly objected to that summary text)
        //   N servers → comma-separated names if short, else count
        //   0         → "Disconnected"
        val joinedNames = heldNames.joinToString(", ").take(60)
        val title = when {
            connected == 0 -> "Disconnected"
            connected == 1 && joinedNames.isNotBlank() -> joinedNames
            joinedNames.isNotBlank() && joinedNames.length < 50 -> joinedNames
            connected > 0 -> "$connected servers connected"
            else -> "Disconnected"
        }
        val text = when {
            connected > 1 -> "End to disconnect all"
            connected > 0 -> "End to disconnect"
            else -> "Open app to reconnect"
        }

        // Custom RemoteViews so the End button sits inline in the
        // collapsed notification row. Android's default template only
        // exposes addAction() actions in the expanded view; this is the
        // public, supported way to get an inline button. Wrap with
        // DecoratedCustomViewStyle so the system still draws the app
        // name strip / small icon / timestamp around our row.
        val collapsed = android.widget.RemoteViews(packageName, R.layout.notif_session).apply {
            setTextViewText(R.id.notif_title, title)
            setTextViewText(R.id.notif_text, text)
            setOnClickPendingIntent(R.id.notif_end, endPending)
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tap)
            // Re-post the instant the user (or OEM "Clear all") dismisses it.
            .setDeleteIntent(repostDeleteIntent())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(collapsed)
            .setStyle(androidx.core.app.NotificationCompat.DecoratedCustomViewStyle())
            // Kept as a fallback for systems that ignore custom views
            // (Android Auto / Wear / accessibility services that read
            // the Action API directly).
            .addAction(
                NotificationCompat.Action.Builder(
                    /* icon  */ 0,
                    /* title */ "End",
                    /* intent*/ endPending,
                ).build()
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notif.flags = notif.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        return notif
    }

    /** Tracks which per-server notifications are currently posted so
     *  we can cancel the ones whose server disappeared from
     *  `userHeldIds` between two emissions. Service-instance-local;
     *  re-built from scratch on process restart. */
    private val postedServerNotifs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Render the full notification surface for the current set of
     * connected servers:
     *
     *   - Foreground notification (NOTIF_ID 1001) — kept as the
     *     service-lifecycle anchor. Title reflects the SINGLE server
     *     name when only one is connected (no "1 server connected"
     *     summary — user explicitly called that out), or a count
     *     summary "N servers connected" when more.
     *   - Per-server notification (NOTIF_ID 1100 + hash) — one each
     *     with the server's name in the title and an END button that
     *     disconnects ONLY that server. Posted regardless of count
     *     so even with one connection the user can see / act on the
     *     server by name.
     *
     * Any per-server notification whose serverId is no longer in
     * `ids` gets canceled to keep the tray clean.
     */
    private fun refreshNotifications(active: Int, ids: Set<String>) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Resolve names synchronously — repo's suspend API wrapped in
        // runBlocking. Lookups are local DB hits (~milliseconds).
        val names: Map<String, String> = SilentlyTry.loggedOrElse("SshAi-Service", "resolve server names", ids.associateWith { "server" }) {
            val repo = ServiceLocator.serverRepository
            kotlinx.coroutines.runBlocking {
                ids.associateWith { id -> repo.getById(id)?.name ?: "server" }
            }
        }

        // **Only per-server notifications, no extra summary row.**
        // Previous design posted a separate "N servers connected"
        // summary at NOTIF_ID=1001 in addition to one row per server
        // — three notifications for two servers. User objected. Now:
        // one row per server, period.
        //
        // The service still needs ONE foreground-anchor notification
        // (Android requires it for the foreground state). The first
        // server (sorted alphabetically by id, stable) is promoted
        // to anchor at the fixed NOTIF_ID. The rest are regular
        // `notify()` rows at server-derived ids. Together they
        // render as a uniform list of per-server rows; the user
        // can't tell which one is the foreground anchor.
        val sortedIds = ids.sorted()
        if (sortedIds.isEmpty()) return
        val foregroundId = sortedIds.first()
        val foregroundName = names[foregroundId] ?: "server"
        // If the new anchor was previously a SIDE row (e.g. server A
        // disconnected and B got promoted), cancel its side row so
        // we don't show B twice.
        if (postedServerNotifs.contains(foregroundId)) {
            mgr.cancel(serverNotifId(foregroundId))
            postedServerNotifs.remove(foregroundId)
        }
        val pool = ServiceLocator.sshConnectionPool
        // Re-issuing startForeground updates the foreground notif
        // in-place; the system doesn't post a new tray row.
        SilentlyTry.fired("SshAi-Service", "startForeground update") {
            startForegroundCompat(
                buildServerNotification(foregroundId, foregroundName, live = pool.peek(foregroundId) != null)
            )
        }
        // Post side rows for the rest.
        sortedIds.asSequence().drop(1).forEach { sid ->
            val name = names[sid] ?: "server"
            mgr.notify(serverNotifId(sid), buildServerNotification(sid, name, live = pool.peek(sid) != null))
            postedServerNotifs.add(sid)
        }
        // Cancel side rows for servers that just left the set. The
        // foreground anchor doesn't need cancellation here — it
        // either survives (still in `ids`) or got pulled via
        // stopForeground upstream when ids hit empty.
        (postedServerNotifs - ids).forEach { gone ->
            mgr.cancel(serverNotifId(gone))
            postedServerNotifs.remove(gone)
        }
    }

    /** Per-server notification: title = server name. When [live], body =
     *  "Connected · End to disconnect"; when the transport is DOWN but the
     *  user still wants it (network blip / FIDO needs a re-tap), body =
     *  "Disconnected · open to reconnect" — the connection is NOT silently
     *  forgotten, it stays here as a one-tap recovery affordance. */
    private fun buildServerNotification(serverId: String, serverName: String, live: Boolean): Notification {
        val bodyText = if (live) "Connected · End to disconnect" else "Disconnected · open to reconnect"
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // request code derived from serverId so each per-server
        // PendingIntent is independent — without this, two END buttons
        // for two different servers would share one PendingIntent and
        // tapping either would disconnect whichever serverId was set
        // most recently (Android PendingIntent equality ignores extras).
        val endIntent = Intent(this, SshAiService::class.java)
            .setAction(ACTION_END_ONE)
            .putExtra(EXTRA_SERVER_ID, serverId)
        val endPending = PendingIntent.getService(
            this, serverId.hashCode(),
            endIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val collapsed = android.widget.RemoteViews(packageName, R.layout.notif_session).apply {
            setTextViewText(R.id.notif_title, serverName)
            setTextViewText(R.id.notif_text, bodyText)
            setOnClickPendingIntent(R.id.notif_end, endPending)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(serverName)
            .setContentText(bodyText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tap)
            // Re-post the instant the user (or OEM "Clear all") dismisses it.
            .setDeleteIntent(repostDeleteIntent())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(collapsed)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .addAction(
                NotificationCompat.Action.Builder(0, "End", endPending).build()
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
            .also {
                it.flags = it.flags or
                    Notification.FLAG_ONGOING_EVENT or
                    Notification.FLAG_NO_CLEAR
            }
    }

    companion object {
        const val CHANNEL_ID = "sshai_session"
        private const val NOTIF_ID = 1001
        /** Per-server notifications occupy ids in `1100 + hash%5000`
         *  range — well clear of the foreground 1001 and the SK touch
         *  channel's 1003. */
        private fun serverNotifId(serverId: String): Int =
            1100 + (kotlin.math.abs(serverId.hashCode()) % 5000)
        /** Intent action fired by the notification's "End" button. Routes
         *  through onStartCommand which disconnects every server and
         *  closes every chat. */
        const val ACTION_END_ALL = "ai.eight24family.conch.action.END_ALL"
        /** Per-server End — extras must include [EXTRA_SERVER_ID]. */
        const val ACTION_END_ONE = "ai.eight24family.conch.action.END_ONE"
        const val EXTRA_SERVER_ID = "ai.eight24family.conch.extra.SERVER_ID"
        /** Fired by the notification's deleteIntent when it's dismissed (swipe /
         *  OEM "Clear all"). Triggers an IMMEDIATE re-post — no waiting for the
         *  20 s ticker. */
        const val ACTION_REPOST = "ai.eight24family.conch.action.REPOST"
        /** Distinct PendingIntent request code for the repost deleteIntent (END_ALL
         *  uses 1, tap uses 0, per-server uses serverId.hashCode()). */
        private const val REPOST_REQUEST_CODE = 2

        fun ensureChannel(context: Context) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Agent sessions",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent connection to your servers"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(channel)
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, SshAiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SshAiService::class.java))
        }
    }
}
