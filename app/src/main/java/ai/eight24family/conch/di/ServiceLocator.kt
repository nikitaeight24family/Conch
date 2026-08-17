package ai.eight24family.conch.di

import android.content.Context
import ai.eight24family.conch.agent.AgentSessionManager
import ai.eight24family.conch.agent.AgentStatusProbe
import ai.eight24family.conch.agent.ServerStatsProbe
import ai.eight24family.conch.agent.SessionDiscovery
import ai.eight24family.conch.data.AgentStatusCache
import ai.eight24family.conch.data.ChatSessionRepository
import ai.eight24family.conch.data.GlobalPrefetcher
import ai.eight24family.conch.data.HistoryCache
import ai.eight24family.conch.data.ServerRepository
import ai.eight24family.conch.data.SessionsCache
import ai.eight24family.conch.data.SshKeyRepository
import ai.eight24family.conch.data.UploadCache
import ai.eight24family.conch.data.db.AppDatabase
import ai.eight24family.conch.data.prefs.AppPreferences
import ai.eight24family.conch.data.secrets.SecretsStore
import ai.eight24family.conch.diagnostics.BridgeManager
import ai.eight24family.conch.diagnostics.DefaultBridgeHandler
import ai.eight24family.conch.diagnostics.LogCaptureCoordinator
import ai.eight24family.conch.ssh.SshClient
import ai.eight24family.conch.ssh.SshConnectionPool
import ai.eight24family.conch.ssh.securitykey.SecurityKeyManager
import ai.eight24family.conch.ssh.securitykey.SecurityKeyRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Dependency graph for the app, built lazily.
 *
 * **Design** (Durov critique #1): every collaborator is a `by lazy(SYNCHRONIZED)`
 * property that constructs itself when first read. The dependency chain is
 * explicit in each getter — no `lateinit var` lottery, no "works because
 * Application.onCreate ran first" magic. Concurrent reads from different
 * threads block on the per-property monitor; one wins, the others see the
 * fully-constructed value.
 *
 * Why an `object` and not a DI framework: Hilt brings ~30s onto compile
 * times and a generated-code surface that's worse than this for a 20-binding
 * graph. Koin's runtime DSL adds another mutable global. Pure `by lazy`
 * is the minimum that compiles cleanly, type-checks all wiring at the
 * call site, and stays threadsafe.
 *
 * `init(context)` only does two things now:
 *   1. Plant the application context (the one input the graph can't derive).
 *   2. Kick off side-effects that have to happen at app start (FTS reconcile,
 *      orphan SSH-key recovery). All actual construction is on first read.
 */
object ServiceLocator {

    // ──────── Context: the one external input ────────
    //
    // Stored as @Volatile not lateinit because we want a clear "not yet
    // initialized" sentinel (null) without UninitializedPropertyAccessException
    // — that exception's message ("lateinit property X has not been initialized")
    // is useless when X is a complete graph and you have no idea which root
    // wasn't reached.

    @Volatile private var appCtx: Context? = null

    /**
     * Safe to call from any thread; idempotent. Calling more than once with
     * different contexts is a programmer error but tolerated — the first
     * context wins and the rest are ignored. (We never observed this in
     * practice, but Application.onCreate is the only legitimate caller.)
     */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (appCtx == null) appCtx = app else return
        }
        // Side effects: best-effort, off the caller's thread.
        // Fire-and-forget — the lazy properties will resolve as readers
        // touch them, these kicks just front-load the cold paths.
        // `nullOnError` over `runCatching` because the name advertises
        // that any failure here is intentional swallow — there's no
        // recovery action and the operation re-runs on next launch.
        // (Durov critique #2: prefer honest names for swallows.)
        startupScope.launch {
            ai.eight24family.conch.util.SilentlyTry.nullOnError { searchIndexer.reconcile() }
        }
        startupScope.launch {
            ai.eight24family.conch.util.SilentlyTry.nullOnError { sshKeyRepository.recoverOrphans() }
        }
    }

    val isInitialized: Boolean get() = appCtx != null

    val appContext: Context
        get() = appCtx ?: error(
            "ServiceLocator.init() not called yet — make sure Application.onCreate ran"
        )

    // ──────── Singletons ────────
    //
    // Every property is `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`. The
    // explicit mode is the default but spelling it makes intent obvious:
    // we WANT the synchronisation, the cost (one monitor enter per
    // not-yet-initialised read) is negligible.

    private val db: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDatabase.get(appContext)
    }

    private val secrets: SecretsStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecretsStore(appContext)
    }

    val sshKeyRepository: SshKeyRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SshKeyRepository(db.sshKeyDao(), secrets)
    }

    val chatSessionRepository: ChatSessionRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChatSessionRepository(db.chatSessionDao())
    }

    val serverRepository: ServerRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ServerRepository(db.serverDao(), secrets, sshKeyRepository, chatSessionRepository)
    }

    val sshClient: SshClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SshClient()
    }

    /**
     * Per-server authenticated SSH client pool with refcount-based
     * lifetimes. Lets every AgentSession on the same (server) ride
     * one shared connection — first chat-open / sessions-list
     * refresh costs ONE FIDO touch on SK servers; everything else
     * is free until the last consumer releases.
     */
    val sshConnectionPool: SshConnectionPool by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SshConnectionPool()
    }

    val agentSessions: AgentSessionManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentSessionManager(appContext, serverRepository, sshClient)
    }

    /**
     * THE single source of truth for per-session last-activity time — orders the
     * sessions list and stamps each row. Persisted + monotonic, fed by local
     * sends/replies ([AgentSession]) and remote file-mtime sweeps
     * ([SessionsCache.save]). Replaces the old mtime + in-memory-bump + persisted-
     * bump `max()` juggling that lost the just-active chat's time on restart.
     */
    val sessionActivity: ai.eight24family.conch.agent.SessionActivityStore
        by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ai.eight24family.conch.agent.SessionActivityStore(preferences)
        }

    /** Process-wide live interactive-terminal shells, keyed by serverId.
     *  Held here (not in a ViewModel) so a shell survives leaving the
     *  terminal screen — same session reusable across navigations. */
    val terminalSessions: ai.eight24family.conch.ui.terminal.TerminalSessionManager
        by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ai.eight24family.conch.ui.terminal.TerminalSessionManager()
        }

    val sessionDiscovery: SessionDiscovery by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SessionDiscovery(sshClient)
    }

    val agentStatusProbe: AgentStatusProbe by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentStatusProbe(sshClient)
    }

    val serverStatsProbe: ServerStatsProbe by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ServerStatsProbe(sshClient)
    }

    val uploadCache: UploadCache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UploadCache(appContext)
    }

    val agentStatusCache: AgentStatusCache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentStatusCache(appContext)
    }

    /** Per-(server,agent) active auth method + per-session method binding.
     *  Drives the long-press method switcher + per-session auth selection. */
    val authMethodStore: ai.eight24family.conch.data.AuthMethodStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ai.eight24family.conch.data.AuthMethodStore(appContext)
    }

    val sessionsCache: SessionsCache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SessionsCache(appContext)
    }

    val historyCache: HistoryCache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HistoryCache(appContext)
    }

    val searchDatabase: ai.eight24family.conch.data.search.SearchDatabase
        by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ai.eight24family.conch.data.search.SearchDatabase.create(appContext)
        }

    val searchIndexer: ai.eight24family.conch.data.search.SearchIndexer
        by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ai.eight24family.conch.data.search.SearchIndexer(
                db = searchDatabase,
                cache = historyCache,
                scope = indexerScope,
            )
        }

    val globalPrefetcher: GlobalPrefetcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GlobalPrefetcher(
            repo = serverRepository,
            agentStatusCache = agentStatusCache,
            sessionsCache = sessionsCache,
            historyCache = historyCache,
            discovery = sessionDiscovery,
        )
    }

    val preferences: AppPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppPreferences(appContext)
    }

    /**
     * App-scoped haptics player.
     *
     * ⚠ It USED to be constructed inside `MainActivity.onCreate` and handed to
     * the UI through a CompositionLocal only, which meant the one haptic the
     * user actually waits for — "the answer landed" — could only fire while the
     * chat was on screen AND composed. In Picture-in-Picture `ChatScreen`
     * short-circuits before its haptic effects even run, so swiping home (the
     * exact moment you stop watching and want to be told) silently disabled
     * buzzing. Owning it here lets the ViewModel — which outlives composition
     * and survives the PiP round-trip — be the one to fire turn-level haptics.
     *
     * The Settings toggle is honoured inside [SshAiHaptics.perform], so call
     * sites never check. The initial value is read synchronously (microseconds
     * off a warm DataStore) because a haptic that arrives after the pref lands
     * is a haptic the user has already missed; the collector below keeps it
     * live for the rest of the process.
     */
    val haptics: ai.eight24family.conch.ui.haptic.SshAiHaptics by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val initial = ai.eight24family.conch.util.SilentlyTry.loggedOrElse(
            "SshAi-Haptics", "read haptics pref", true,
        ) {
            kotlinx.coroutines.runBlocking { preferences.hapticsEnabled.first() }
        }
        ai.eight24family.conch.ui.haptic.SshAiHaptics(appContext, enabled = initial).also { h ->
            startupScope.launch {
                ai.eight24family.conch.util.SilentlyTry.nullOnError {
                    preferences.hapticsEnabled.collect { h.enabled = it }
                }
            }
        }
    }

    val securityKeyManager: SecurityKeyManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecurityKeyManager(appContext as android.app.Application)
    }

    val securityKeyRegistrar: SecurityKeyRegistrar by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecurityKeyRegistrar()
    }

    val logCaptureCoordinator: LogCaptureCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LogCaptureCoordinator(appContext)
    }

    /**
     * Spawns/reaps per-server [ai.eight24family.conch.diagnostics.AgentBridge]
     * pollers that fulfil `conch-bridge` requests written to
     * `~/.conch-bridge/inbox/` on the user's server. Started by
     * SshAiService when the foreground service is up.
     */
    val bridgeManager: BridgeManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BridgeManager(handler = DefaultBridgeHandler(logCaptureCoordinator))
    }

    /**
     * Cross-composition cache for the in-flight SK touch dialog's
     * `DeferredCtapSkSigner`. The only legitimately MUTABLE state on the
     * locator — every dispose-recreate cycle of `SkInlineTouchDialog`
     * needs to see the same in-flight signer, and the same field doubles
     * as the explicit "clear on Done / Cancel" slot.
     *
     * Why this can't be `by lazy`: there is no construction logic — the
     * dialog creates a signer matching its current attempt and stores it
     * here. The locator just provides a stable address that survives
     * composition disposes.
     *
     * Slot is keyed (informally) by `credentialIdBase64 + "|" + application`
     * in the dialog itself; the locator just holds the latest, the dialog
     * decides whether to reuse or replace.
     */
    @Volatile var cachedSkDialogSigner:
        ai.eight24family.conch.ssh.securitykey.DeferredCtapSkSigner? = null

    // ──────── Scopes ────────
    //
    // Process-wide background scope for FTS reconcile + key recovery —
    // outlives any ViewModel, doesn't block app start. SupervisorJob so
    // one failed task doesn't cancel the others.

    private val startupScope: CoroutineScope by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val indexerScope: CoroutineScope by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * Test-only: forget the cached context so the next [init] rebuilds
     * the graph against a fresh Robolectric application. Per-property
     * `by lazy` slots are tied to this object instance — they cache for
     * the lifetime of the JVM, so a full test reset would need a
     * different injector. None of our current tests touch the locator,
     * so this stays a no-op shim for ABI compat.
     */
    @androidx.annotation.VisibleForTesting
    fun resetForTest() {
        synchronized(this) { appCtx = null }
    }
}
