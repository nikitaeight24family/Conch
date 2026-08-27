package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.AgentStatus
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Live-auth verdicts cached per server (file-level so it survives VM
// re-creation on picker re-open) — the live check SPAWNS the CLIs, so it runs
// at most once per TTL, not on every refresh.
private val liveAuthCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Map<Agent, Boolean>, Long>>()
private const val LIVE_AUTH_TTL_MS = 5 * 60_000L

/**
 * Refresh / probing pipeline pulled out of [AgentPickerViewModel].
 *
 * Owns the [refresh] entry point and all three "probe over an existing
 * SSH" code paths:
 *
 *  - [runProbeViaPooledClient] — ride a pool client that already
 *    handshook on a previous tap. No second touch needed.
 *  - [runProbeViaAliveSession] — ride an AgentSession's live channel
 *    when no pool entry exists (e.g. a long-running chat).
 *  - [runProbeWithSigner] — the SK touch-dialog calls this from
 *    inside its `withNfc{}` / `awaitUsb` callback so the token I/O
 *    outlives a single handshake.
 *
 * **Invariants preserved here (do NOT regress):**
 *
 *  - The visible "refreshing…" bar is gated by [userRefreshingMut] —
 *    set true *only* for `refresh(userTriggered = true)`. Background
 *    refreshes (init, ON_RESUME, post-login, cache-miss prefill) keep
 *    it false; the corner spinner (driven by [probingMut]) does the
 *    talking. User asked for this explicitly ~5 times.
 *  - Cache-trust on cold open — `firstProbeDoneMut.value = true` the
 *    moment we set non-empty cached statuses. Without it the row taps
 *    stay disabled showing `[ checking ]` even though we have good
 *    cached data.
 *  - Pre-flight TCP probe for both SK and non-SK paths. Don't ask the
 *    user to physically tap their key for a dead host.
 *
 * UI-visible mutators are passed in as setters / read-only refs so this
 * helper stays decoupled from the orchestrator. Every StateFlow on the
 * orchestrator stays publicly readable — only the writes happen here.
 */
internal class AgentPickerViewModelRefresh(
    private val scope: CoroutineScope,
    private val serverId: String,
    /** Read-only accessor — repository is owned by the orchestrator. */
    private val repo: ai.eight24family.conch.data.ServerRepository,
    private val probe: ai.eight24family.conch.agent.AgentStatusProbe,
    private val cache: ai.eight24family.conch.data.AgentStatusCache,
    private val serverMut: MutableStateFlow<Server?>,
    private val statusesMut: MutableStateFlow<Map<Agent, AgentStatus>?>,
    private val probingMut: MutableStateFlow<Boolean>,
    private val userRefreshingMut: MutableStateFlow<Boolean>,
    private val errorMut: MutableStateFlow<String?>,
    private val diagnosisMut: MutableStateFlow<ai.eight24family.conch.ssh.ServerDiagnostics.Diagnosis?>,
    private val lastCheckedAtMut: MutableStateFlow<Long?>,
    private val firstProbeDoneMut: MutableStateFlow<Boolean>,
    private val isSkServerMut: MutableStateFlow<Boolean>,
    private val authConfirmedMut: MutableStateFlow<Boolean>,
    private val skTouchRequestMut: MutableStateFlow<AgentPickerViewModel.SkTouchRequest?>,
) {

    /** Active diagnosis Job — cancelled before a new refresh kicks off
     *  so a stale result from the previous server can't surface. */
    private var diagnosisJob: Job? = null

    /**
     * Forced re-probe.
     *
     * @param userTriggered `true` when called from a deliberate user
     *  gesture (pull-to-refresh) — the "refreshing…" bar always
     *  shows so the gesture has visible feedback. `false` (default)
     *  for automatic refreshes (init, ON_RESUME watchdog, post-login)
     *  — the bar shows ONLY when the cache is cold (first-ever
     *  visit), otherwise the probe runs silently in the background.
     */
    fun refresh(
        userTriggered: Boolean = false,
        force: Boolean = false,
        /** Show the in-panel "refreshing…" bar. ONLY a literal pull gesture on
         *  THIS surface should pass true (the bar is that gesture's feedback).
         *  Programmatic-but-thorough refreshes (the Agents-tab pull fanning out
         *  to every server panel, retry taps, post-account-change reverify) pass
         *  userTriggered=true for probe semantics but keep the bar OFF — the
         *  user's rule: no refreshing panel unless THEY pulled; background work
         *  shows only a small spinner by the connection dot, never blocking. */
        showBar: Boolean = userTriggered,
    ): Job {
        // HARD GATE: never probe while an OAuth login is in flight. Diagnosed
        // on-device: an automatic refresh fired the instant the user returned
        // from the browser (PiP-exit), its kickLiveAuth spawned a SECOND gemini
        // on the same pooled SSH + churned sessions → the login channel hit EOF →
        // the login coroutine's stdin closed → the pasted code had nowhere to go.
        // Source-of-truth gate so it catches EVERY trigger (ON_RESUME, init,
        // prefetch, watchdog), not just the one observer Step 1 guarded.
        if (!force && AgentPickerViewModel.activeLogin.value != null) {
            android.util.Log.d("SshAi-AgentPicker", "refresh skipped — OAuth login in progress")
            return scope.launch { }
        }
        // Cancel any in-flight diagnose from a previous attempt before
        // we launch the new one — otherwise a slow ServerDiagnostics
        // call from the previous refresh could overwrite a fresh
        // result for this same server.
        diagnosisJob?.cancel()
        return scope.launch(Dispatchers.IO) {
            val tag = "SshAi-AgentPicker"
            android.util.Log.d(tag, "refresh(serverId=$serverId, userTriggered=$userTriggered, force=$force) — enter")
            // **Two distinct "we're working" signals.**
            //  - `_probing`: any in-flight refresh. Drives the small
            //    top-right corner spinner and the screen's internal
            //    state-machine gates (watchdog, ON_RESUME re-fire,
            //    diagnosis-vs-loading branching). Hidden plumbing.
            //  - `_userRefreshing`: TRUE only during a deliberate user
            //    gesture (pull-to-refresh). Drives the visible
            //    "refreshing…" bar at the top of the screen. Never
            //    fires for init / ON_RESUME / post-login / cache-miss
            //    background refreshes — those run silently while the
            //    corner spinner does the talking.
            //
            //  Background refresh = invisible. User-triggered = visible
            //  bar (so the swipe gesture has feedback). This was the
            //  user's explicit ask, repeated ~5 times now.
            probingMut.value = true
            userRefreshingMut.value = userTriggered && showBar
            errorMut.value = null
            diagnosisMut.value = null
            // Data-saver: skip the live SSH probe altogether when
            // (a) the user didn't explicitly pull-to-refresh, and
            // (b) we already have cached statuses that are <1h old.
            // The agent-list shown to the user is still accurate
            // (cached on disk) — we just don't spend SSH round-trips
            // re-confirming what we already know. Pull-to-refresh
            // always bypasses the gate.
            val dataSaver = SilentlyTry.loggedOrElse("SshAi-AgentPicker", "read data saver pref", false) {
                ServiceLocator.preferences.dataSaverEnabled.first()
            }
            val lastCheck = lastCheckedAtMut.value ?: 0L
            val cacheAgeMs = if (lastCheck > 0L) System.currentTimeMillis() - lastCheck else Long.MAX_VALUE
            val DATA_SAVER_PROBE_TTL_MS = 60L * 60L * 1000L  // 1 hour
            if (dataSaver && !userTriggered && cacheAgeMs < DATA_SAVER_PROBE_TTL_MS) {
                android.util.Log.d(tag, "  data-saver: skip probe, cache age ${cacheAgeMs / 1000}s")
                probingMut.value = false; userRefreshingMut.value = false
                return@launch
            }
            try {
                val server = repo.getById(serverId) ?: run {
                    android.util.Log.w(tag, "  server $serverId not found in repo")
                    errorMut.value = "Server not found"
                    return@launch
                }
                val secrets = repo.getSecrets(serverId)
                val skPrimary = secrets.skKeys.firstOrNull()
                isSkServerMut.value = (skPrimary != null)
                android.util.Log.d(
                    tag,
                    "  server=${server.name} host=${server.host}:${server.port} auth=${server.authMethod} skKeys=${secrets.skKeys.size}"
                )
                if (skPrimary != null) {
                    // Ride a pooled / alive session if we already paid for a tap.
                    val pooled = ServiceLocator.sshConnectionPool.peek(serverId)
                    if (pooled != null) {
                        android.util.Log.d("SshAi-AgentPicker", "probe via pooled SSH — no touch")
                        authConfirmedMut.value = true
                        if (runProbeViaPooledClient(pooled)) return@launch
                        android.util.Log.d("SshAi-AgentPicker", "  pooled probe failed — falling back to touch flow")
                    }
                    val alive = ServiceLocator.agentSessions.findAnyAlive(serverId, Agent.CLAUDE)
                        ?: ServiceLocator.agentSessions.findAnyAlive(serverId, Agent.CODEX)
                        ?: ServiceLocator.agentSessions.findAnyAlive(serverId, Agent.GEMINI)
                    if (alive != null) {
                        if (runProbeViaAliveSession(alive)) return@launch
                        android.util.Log.d("SshAi-AgentPicker", "  alive-session probe failed — falling back to touch flow")
                    }
                    val info = skPrimary.securityInfo ?: run {
                        errorMut.value = "Security-key row is missing handle/application — re-add the key."
                        return@launch
                    }
                    // Device-key reconnect FIRST — silent, NO tap. The opt-in
                    // AndroidKeyStore ephemeral key reconnects without a touch
                    // (the "switched network / re-entered the screen — don't
                    // re-ask for the key" path). Mirrors ChatViewModel's
                    // beginSearchOpenedConnect + ensureConnectedThenRun. Usually
                    // a no-op here (the app-wide connectAllSeamlessSilently has
                    // already brought the pool up, so the pooled path above ran)
                    // — this is the backstop for the window before it lands.
                    val silentUp = ai.eight24family.conch.ssh.EphemeralSshKey.exists(serverId) &&
                        runCatching { ServiceLocator.sshConnectionPool.userConnectEphemeral(server) }.getOrNull() != null
                    if (silentUp) {
                        val pooledNow = ServiceLocator.sshConnectionPool.peek(serverId)
                        if (pooledNow != null) {
                            android.util.Log.d("SshAi-AgentPicker", "silent device-key reconnect — no touch")
                            authConfirmedMut.value = true
                            if (runProbeViaPooledClient(pooledNow)) return@launch
                            android.util.Log.d("SshAi-AgentPicker", "  silent-pooled probe failed — falling through")
                        }
                    }
                    // The FIDO touch is reserved for an EXPLICIT user gesture —
                    // pull-to-refresh, an install/login tap, or the per-server
                    // connect screen (Keychain re-attach). ALL of those pass
                    // userTriggered=true. A background / navigation refresh (init,
                    // ON_RESUME, the overview glance) must NEVER pop the key
                    // dialog: the offline dot already says "not connected", and
                    // the app re-lists itself the moment a connection comes up. So
                    // a silent connect that didn't land just leaves the cached
                    // agents on screen — no touch, no diagnosis.
                    if (!userTriggered) {
                        firstProbeDoneMut.value = true
                        probingMut.value = false; userRefreshingMut.value = false
                        android.util.Log.d(
                            tag,
                            "auto refresh + no silent device-key connect → cached agents stay (no FIDO)"
                        )
                        return@launch
                    }
                    // Explicit refresh — surface WHY the host is unreachable
                    // (typed diagnosis via a ~3s TCP probe) BEFORE asking for a
                    // physical tap, so the user doesn't pay the tap + PIN only to
                    // learn the server is dead. The full SSH connect inside
                    // `pool.userConnect` does the same check 15s later.
                    val tcp = ai.eight24family.conch.ssh.TcpProbe.probe(server.host, server.port)
                    // The TcpProbe also reads a 16-byte banner on connect — so
                    // WrongPort and SilentSsh get caught here too, before the tap.
                    val diag = ai.eight24family.conch.ssh.ServerDiagnostics.classify(
                        host = server.host,
                        port = server.port,
                        outcome = tcp,
                        context = ServiceLocator.appContext,
                    )
                    if (diag !is ai.eight24family.conch.ssh.ServerDiagnostics.Diagnosis.Ok) {
                        android.util.Log.w(
                            "SshAi-AgentPicker",
                            "pre-flight diagnosis for ${server.host}:${server.port} → ${diag::class.simpleName}"
                        )
                        // Race re-check: a background prefetcher might
                        // have opened a pool client during the probe.
                        if (ServiceLocator.sshConnectionPool.peek(serverId) == null) {
                            diagnosisMut.value = diag
                        }
                        probingMut.value = false; userRefreshingMut.value = false
                        return@launch
                    }
                    skTouchRequestMut.value = AgentPickerViewModel.SkTouchRequest(
                        // Always EITHER at auth time — transport is a physical
                        // contact channel, not a property of the credential.
                        transport = ai.eight24family.conch.domain.SecurityKeyTransport.EITHER,
                        application = info.application,
                        credentialIdBase64 = info.credentialIdBase64,
                        serverName = server.name,
                    )
                    ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier.post(
                        context = ServiceLocator.appContext,
                        reason = ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier.Reason.CONNECT,
                        transport = info.transport,
                        target = server.name,
                    )
                    probingMut.value = false; userRefreshingMut.value = false
                    return@launch
                }
                // Pre-flight TCP for non-SK paths too — same UX win
                // (~4 s typed diagnosis vs. 15 s sshj timeout with an
                // obscure exception class name). ONLY on an explicit user
                // gesture: this used to run on every init/ON_RESUME
                // navigation too, and each probe is a preauth disconnect in
                // sshd's log — background refreshes were feeding the user's
                // fail2ban a steady drip of them until the phone's IP got
                // banned. An auto refresh has no user staring at a spinner,
                // so it can afford the slow honest path below instead.
                if (userTriggered) {
                    val tcp = ai.eight24family.conch.ssh.TcpProbe.probe(server.host, server.port)
                    val diag = ai.eight24family.conch.ssh.ServerDiagnostics.classify(
                        host = server.host,
                        port = server.port,
                        outcome = tcp,
                        context = ServiceLocator.appContext,
                    )
                    if (diag !is ai.eight24family.conch.ssh.ServerDiagnostics.Diagnosis.Ok) {
                        android.util.Log.w(
                            "SshAi-AgentPicker",
                            "pre-flight diagnosis for ${server.host}:${server.port} → ${diag::class.simpleName}"
                        )
                        if (ServiceLocator.sshConnectionPool.peek(serverId) == null) {
                            diagnosisMut.value = diag
                        }
                        return@launch
                    }
                }
                probe.probe(server, secrets)
                    .onSuccess { result ->
                        // The fresh (non-SK) probe has no reusable connection to
                        // run a separate live-auth pass, so clear any
                        // liveAuthPending here — the GLOBAL "checking" gate must
                        // release (it's held while ANY agent is pending). The
                        // SK / pooled / alive paths run the real live-auth.
                        val cleared = result.mapValues { (_, st) ->
                            if (st.liveAuthPending) st.copy(liveAuthPending = false) else st
                        }
                        val effective = cache.save(serverId, cleared)
                        statusesMut.value = effective
                        lastCheckedAtMut.value = System.currentTimeMillis()
                        firstProbeDoneMut.value = true
                    }
                    .onFailure {
                        errorMut.value = ai.eight24family.conch.util.ErrorMessages.humanize(it)
                    }
            } catch (t: Throwable) {
                errorMut.value = t.message ?: t.javaClass.simpleName
            } finally {
                probingMut.value = false; userRefreshingMut.value = false
            }
        }
    }

    private suspend fun runProbeViaPooledClient(client: net.schmizz.sshj.SSHClient): Boolean {
        val tag = "SshAi-AgentPicker"
        try {
            probingMut.value = true
            var ok = false
            // 30s cap (was 15) so the same lambda can carry the slower live-auth
            // call; the fast probe still returns the instant its process exits.
            val execLambda: suspend (String) -> String? = { cmd ->
                withContext(Dispatchers.IO) {
                    SilentlyTry.logged("SshAi-AgentPicker", "probe agents on pool") {
                        val sess = client.startSession()
                        try {
                            val proc = sess.exec(cmd)
                            val out = java.io.ByteArrayOutputStream()
                            // Bounded read: the deadline wraps the READ, not the join after it.
                            ai.eight24family.conch.ssh.BoundedExec.drain(
                                proc, out,
                                deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.INTERACTIVE_MS,
                                maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.INTERACTIVE,
                            )
                            proc.join(30, java.util.concurrent.TimeUnit.SECONDS)
                            String(out.toByteArray(), Charsets.UTF_8)
                        } finally { SilentlyTry.fired("SshAi-AgentPicker", "close pool probe session") { sess.close() } }
                    }
                }
            }
            probe.probe(execLambda, onOs = { os -> cache.saveServerOs(serverId, os.name) })
                .onSuccess {
                    // withCachedLiveAuth: keep a login we ALREADY verified this
                    // session resolved (no "checking" re-flash on a background
                    // re-probe); leave an unverified one pending so it checks.
                    val resolved = withCachedLiveAuth(it)
                    val effective = cache.save(serverId, resolved)
                    statusesMut.value = effective
                    lastCheckedAtMut.value = System.currentTimeMillis()
                    firstProbeDoneMut.value = true
                    android.util.Log.d(tag, "  probe (pool) ok")
                    ok = true
                    kickLiveAuth(execLambda)   // async, non-blocking — self-corrects the badge
                }
                .onFailure {
                    android.util.Log.w(tag, "  probe (pool) failed: ${it.message}")
                }
            return ok
        } finally {
            probingMut.value = false; userRefreshingMut.value = false
        }
    }

    private suspend fun runProbeViaAliveSession(alive: AgentSession): Boolean {
        val tag = "SshAi-AgentPicker"
        try {
            probingMut.value = true
            var ok = false
            val execLambda: suspend (String) -> String? = { cmd -> alive.execOnLive(cmd) }
            probe.probe(execLambda, onOs = { os -> cache.saveServerOs(serverId, os.name) })
                .onSuccess {
                    val resolved = withCachedLiveAuth(it)
                    val effective = cache.save(serverId, resolved)
                    statusesMut.value = effective
                    lastCheckedAtMut.value = System.currentTimeMillis()
                    firstProbeDoneMut.value = true
                    android.util.Log.d(tag, "  probe (reuse) ok")
                    ok = true
                    kickLiveAuth(execLambda)   // async, non-blocking — self-corrects the badge
                }
                .onFailure {
                    android.util.Log.w(tag, "  probe (reuse) failed: ${it.message}")
                }
            return ok
        } finally {
            probingMut.value = false; userRefreshingMut.value = false
        }
    }

    /**
     * QUIET single-agent re-probe — the account-op path (add / remove / switch).
     * Rides the pool/alive channel the op just used, runs ONE probe, and merges
     * ONLY [agent]'s fresh status into the shown map — every other row is left
     * exactly as-is. NO "refreshing…" bar, NO whole-server churn; the small
     * spinner by the connection dot (probingMut) is the only affordance.
     *
     * Claude's run-state check (the profile curl) rides this same probe, so a
     * determined state (OK / no-sub / login-expired) lands as fast as the curl
     * answers — and since a 200/403-scope profile ALREADY proves the token, we
     * clear liveAuthPending and SKIP the slow live-auth CLI spawn: "ready" shows
     * immediately, no extra round-trip. If nothing is connected we can't probe
     * without a touch, so we leave the caller's optimistic state.
     */
    fun reprobeAgentQuiet(agent: Agent): Job = scope.launch(Dispatchers.IO) {
        val tag = "SshAi-AgentPicker"
        val pooled = ServiceLocator.sshConnectionPool.peek(serverId)
        val alive = if (pooled == null) {
            ServiceLocator.agentSessions.findAnyAlive(serverId, Agent.CLAUDE)
                ?: ServiceLocator.agentSessions.findAnyAlive(serverId, Agent.CODEX)
                ?: ServiceLocator.agentSessions.findAnyAlive(serverId, Agent.GEMINI)
        } else null
        val execLambda: (suspend (String) -> String?)? = when {
            pooled != null -> { cmd ->
                withContext(Dispatchers.IO) {
                    SilentlyTry.logged(tag, "quiet reprobe on pool") {
                        val sess = pooled.startSession()
                        try {
                            val proc = sess.exec(cmd)
                            val out = java.io.ByteArrayOutputStream()
                            // Bounded read: the deadline wraps the READ, not the join after it.
                            ai.eight24family.conch.ssh.BoundedExec.drain(
                                proc, out,
                                deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.INTERACTIVE_MS,
                                maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.INTERACTIVE,
                            )
                            proc.join(30, java.util.concurrent.TimeUnit.SECONDS)
                            String(out.toByteArray(), Charsets.UTF_8)
                        } finally { SilentlyTry.fired(tag, "close quiet reprobe session") { sess.close() } }
                    }
                }
            }
            alive != null -> { cmd -> alive.execOnLive(cmd) }
            else -> null
        }
        if (execLambda == null) {
            android.util.Log.w(tag, "  quiet reprobe($agent) SKIPPED — no live channel (pooled+alive both null)")
            return@launch
        }
        probingMut.value = true
        try {
            var settled = false
            probe.probe(execLambda, onOs = { os -> cache.saveServerOs(serverId, os.name) })
                .onSuccess { result ->
                    val raw = result[agent] ?: return@onSuccess
                    // A determined Claude run-state was reached by curling the
                    // token's own profile → the token is proven live; drop the
                    // "checking" pending so the row reads its true state NOW.
                    val fresh = if (agent == Agent.CLAUDE && raw.claudeState != null) {
                        settled = true
                        raw.copy(liveAuthPending = false)
                    } else raw
                    val cur = statusesMut.value ?: emptyMap()
                    statusesMut.value = cache.save(serverId, cur + (agent to fresh))
                    lastCheckedAtMut.value = System.currentTimeMillis()
                    firstProbeDoneMut.value = true
                    android.util.Log.d(tag, "  quiet reprobe($agent) ok settled=$settled")
                }
                .onFailure {
                    android.util.Log.w(tag, "  quiet reprobe($agent) failed: ${it.message}")
                }
            // Only pay for the (slower, CLI-spawning) live-auth confirm when the
            // fast run-state didn't already settle the row.
            if (!settled) kickLiveAuth(execLambda)
        } finally {
            probingMut.value = false
        }
    }

    /**
     * Apply a STILL-FRESH cached live-auth verdict to a freshly-probed status
     * map. This is what makes re-entry to a logged-in server instant + quiet:
     * a fast probe always marks oauth/chatgpt rows `liveAuthPending=true`
     * (presence only), which would flash "[ checking ]" on every refresh — but
     * if we already verified that login this session (verdict cached, < TTL),
     * we resolve the row right here (ready / drop-if-dead) so it never
     * re-flashes. No fresh verdict for an agent ⇒ leave its pending as-is, so a
     * genuinely-unverified login DOES show "checking" until [kickLiveAuth]
     * confirms it. Mirror of the merge in [kickLiveAuth].
     */
    private fun withCachedLiveAuth(fresh: Map<Agent, AgentStatus>): Map<Agent, AgentStatus> {
        val now = System.currentTimeMillis()
        val verdict = liveAuthCache[serverId]?.takeIf { now - it.second < LIVE_AUTH_TTL_MS }?.first
        // Have we ALREADY shown the user a status (cache hit at open, or a prior
        // probe)? Then this probe is a BACKGROUND re-confirm — it must be
        // invisible: keep the last-known row, NEVER surface "[ checking ]"
        // (the user's rule — re-entry must not disturb). kickLiveAuth still runs
        // and flips the row only if the live verdict actually changed it. Only a
        // genuinely COLD open (nothing shown yet) is allowed to show "checking".
        return fresh.mapValues { (agent, st) ->
            when (verdict?.get(agent)) {
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
                // The fast probe marks ONLY oauth/chatgpt rows pending
                // (presence-only), and an OAuth cred FILE present ≠ a live token
                // (Gemini's silently expires). Previously we cleared pending on
                // any "background" re-entry to avoid a "[ checking ]" flash — but
                // that rendered a stale "ready" that flipped to "log in" a few
                // seconds later once kickLiveAuth ran. Honest beats smooth: show
                // "[ checking ]" until the live verdict lands. api/bearer keys
                // are never marked pending → unaffected, still render instantly
                // from presence.
                else -> st
            }
        }
    }

    /** True when we have a STILL-FRESH (< TTL) live-auth verdict for [agent] —
     *  it was verified this process recently and does NOT need re-checking.
     *  Used at cache-load to decide whether an account login renders "ready"
     *  (fresh) or "[ checking ]" from the START (stale ⇒ re-verify), so a
     *  refresh never flashes "ready" → then → "checking". */
    fun liveAuthFresh(agent: Agent): Boolean {
        val now = System.currentTimeMillis()
        val cached = liveAuthCache[serverId] ?: return false
        return now - cached.second < LIVE_AUTH_TTL_MS && cached.first.containsKey(agent)
    }

    /** Drop [agent]'s cached live-auth verdict so the NEXT probe re-verifies it
     *  from scratch. Called when the user just added / removed / switched the
     *  account for that agent — the old verdict is now meaningless and must not
     *  let the badge flash a stale "ready" before the real check lands. */
    fun invalidateLiveAuth(agent: Agent) {
        val cur = liveAuthCache[serverId] ?: return
        liveAuthCache[serverId] = (cur.first - agent) to cur.second
    }

    /**
     * Fire-and-forget LIVE OAuth validation. The fast probe shows presence
     * instantly; this then SPAWNS the agent CLIs for real (the only truth —
     * creds-on-disk can be present but revoked / not provisioned) and MERGES
     * the verdict into the shown status, so the badge self-corrects a few
     * seconds later WITHOUT ever blocking the picker. Cached per server (TTL).
     */
    private fun kickLiveAuth(exec: suspend (cmd: String) -> String?) {
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = liveAuthCache[serverId]
            val authok = if (cached != null && now - cached.second < LIVE_AUTH_TTL_MS) {
                cached.first
            } else {
                val fresh = runCatching { probe.probeLiveAuth(exec) }.getOrDefault(emptyMap())
                if (fresh.isNotEmpty()) liveAuthCache[serverId] = fresh to now
                fresh
            }
            val cur = statusesMut.value ?: return@launch
            // The live-auth pass is DONE — clear liveAuthPending on EVERY row so
            // the global "checking" gate always releases. A row must never hang
            // on "checking" because a verdict was missing or the probe timed out
            // (that would freeze the whole picker, since checking is now global).
            // Apply verdicts where we got them: false ⇒ drop the dead
            // oauth/chatgpt; true ⇒ keep; none ⇒ just stop checking and fall
            // back to the fast-probe status.
            val merged = cur.mapValues { (agent, st) ->
                when (authok[agent]) {
                    false -> {
                        // CLI demanded re-auth → the on-disk OAuth cred is NOT
                        // usable. Drop it → row flips from "checking" to "log in".
                        val m = st.methods - setOf("oauth", "chatgpt")
                        st.copy(
                            methods = m,
                            loggedIn = m.isNotEmpty(),
                            activeMethod = st.activeMethod?.takeIf { it in m } ?: m.singleOrNull(),
                            liveAuthPending = false,
                        )
                    }
                    // Verified usable → clear pending so "checking" → "ready".
                    true -> st.copy(liveAuthPending = false)
                    // No verdict for this agent → don't keep it spinning.
                    else -> if (st.liveAuthPending) st.copy(liveAuthPending = false) else st
                }
            }
            if (merged != cur) {
                statusesMut.value = cache.save(serverId, merged)
            }
        }
    }

    /**
     * Run the probe with a caller-built signer. Invoked from the screen
     * INSIDE yubikit's `withNfc` callback (NFC) or after `awaitUsb` (USB)
     * so the token I/O outlives a single SSH handshake.
     *
     * Drives a `pool.userConnect` so the SSH client stays alive for the
     * user's whole session — every later chat-open / sessions-list
     * refresh / agent-picker re-enter on this server is free until they
     * explicitly disconnect.
     *
     * **Throws on failure** — the dialog's classifier catches and either
     * surfaces a Wrong-Key UI (with Discover/Register actions) or
     * re-arms NFC for another tap. Don't swallow exceptions here.
     */
    suspend fun runProbeWithSigner(
        signer: ai.eight24family.conch.ssh.securitykey.SkSigner,
    ) {
        val tag = "SshAi-AgentPicker"
        val server = serverMut.value ?: repo.getById(serverId).also { serverMut.value = it } ?: return
        val secrets = repo.getSecrets(serverId)
        // **Silent background probe when cache is hot.** If we
        // already have cached statuses (i.e. user has been on this
        // server before), we don't show the "refreshing…" bar — the
        // user sees agents immediately as `ready` and the fresh
        // probe quietly refreshes the cache in the background. Per
        // user direction
        val haveCache = statusesMut.value?.isNotEmpty() == true
        probingMut.value = !haveCache
        try {
            val client = withContext(Dispatchers.IO) {
                ServiceLocator.sshConnectionPool.userConnect(server, secrets, signer)
            }
            authConfirmedMut.value = true
            // Drop the "Hold your security key against the back of the
            // phone" notification THE MOMENT the SSH transport is up.
            // Previously this was deferred until after probe.probe()
            // completed — that's another 1-3 seconds of stale "still
            // tap" notification sitting in the user's tray when in
            // reality the key was already accepted. User's complaint
            skTouchRequestMut.value = null
            ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier.cancel(ServiceLocator.appContext)
            val execLambda: suspend (String) -> String? = { cmd ->
                withContext(Dispatchers.IO) {
                    SilentlyTry.logged("SshAi-AgentPicker", "probe agents after connect") {
                        val sess = client.startSession()
                        try {
                            val cmdProc = sess.exec(cmd)
                            val out = java.io.ByteArrayOutputStream()
                            // Bounded read: the deadline wraps the READ, not the join after it.
                            ai.eight24family.conch.ssh.BoundedExec.drain(
                                cmdProc, out,
                                deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.INTERACTIVE_MS,
                                maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.INTERACTIVE,
                            )
                            cmdProc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                            String(out.toByteArray(), Charsets.UTF_8)
                        } finally { SilentlyTry.fired("SshAi-AgentPicker", "close post-connect probe session") { sess.close() } }
                    }
                }
            }
            probe.probe(execLambda, onOs = { os -> cache.saveServerOs(serverId, os.name) })
                .onSuccess {
                    val resolved = withCachedLiveAuth(it)
                    val effective = cache.save(serverId, resolved)
                    statusesMut.value = effective
                    lastCheckedAtMut.value = System.currentTimeMillis()
                    firstProbeDoneMut.value = true
                    // Live-auth pass (same as pooled/alive). A still-valid login
                    // stays resolved via withCachedLiveAuth above; an unverified
                    // one shows "checking" until this clears it.
                    kickLiveAuth(execLambda)
                    android.util.Log.d(tag, "  probe (after connect) ok — connection held in pool")
                }
                .onFailure {
                    android.util.Log.w(tag, "  probe (after connect) failed but connection is live: ${it.message}")
                }
        } finally {
            probingMut.value = false; userRefreshingMut.value = false
        }
    }

    fun cancelSkRefresh() {
        skTouchRequestMut.value = null
        probingMut.value = false; userRefreshingMut.value = false
        ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier.cancel(ServiceLocator.appContext)
    }
}
