package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import ai.eight24family.conch.ui.components.ConnectionDot
import ai.eight24family.conch.ui.components.HostInfoSheet
import ai.eight24family.conch.ui.components.TopBarSpinner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentStatus
import ai.eight24family.conch.agent.InstallOp
import ai.eight24family.conch.ui.viewmodel.AgentPickerViewModel
import ai.eight24family.conch.util.SilentlyTry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerScreen(
    serverId: String,
    /** Browse mode (reached via the Agents bottom-tab): show cached agent
     *  statuses without demanding a connection, and SUPPRESS the
     *  not-connected watchdog so the user can sit here reading from cache —
     *  the FIDO touch is deferred until they actually act. Default false keeps
     *  the connect-flow behaviour when drilled in from a server tap. */
    browse: Boolean = false,
    /** When non-null (arrived from the Agents overview by tapping a "log in"
     *  agent), auto-open that agent's login chooser on first composition so the
     *  tap actually STARTS the login flow instead of just showing the list. */
    autoLoginAgent: Agent? = null,
    onBack: () -> Unit,
    onPickAgent: (Agent) -> Unit,
    /** Open the Keychain in "discover credentials" mode and auto-attach
     *  every imported key to [serverId]. Surfaced from the touch dialog
     *  on a Wrong-Key error: user taps "Find on this key" → keychain
     *  enumerates → returns to AgentPicker which retries. */
    onOpenKeychainForDiscover: (serverId: String) -> Unit = {},
    /** Open the Keychain in "register a new credential" mode and
     *  auto-attach the freshly minted key to [serverId]. Surfaced from
     *  the touch dialog when the user wants a brand-new credential
     *  rather than importing existing ones. */
    onOpenKeychainForRegister: (serverId: String) -> Unit = {},
    /** Navigate to global app settings. Surfaced as an icon in the
     *  topbar actions so the user can reach Settings without
     *  back-popping to the servers list. */
    onOpenSettings: () -> Unit = {},
    /** Tap on a search-result row → navigate to that exact chat at
     *  the matched message. Provided by AppNav. Receives sessionId
     *  and messageId so the receiver can route to the right chat. */
    onOpenChatFromSearch: (sessionId: String, msgId: String, ordinal: Int, query: String, charOffset: Int) -> Unit = { _, _, _, _, _ -> },
    /** Host-info sheet → "open terminal": route to the real shell for this host. */
    onOpenTerminal: (serverId: String, serverName: String) -> Unit = { _, _ -> },
    vm: AgentPickerViewModel = viewModel()
) {
    val server by vm.server.collectAsState()
    val statuses by vm.statuses.collectAsState()
    val authConfirmed by vm.authConfirmed.collectAsState()
    val isSkServer by vm.isSkServer.collectAsState()
    val skTouch by vm.skTouchRequest.collectAsState()
    val probing by vm.probing.collectAsState()
    // `userRefreshing` is true ONLY during a deliberate pull-to-refresh.
    // Drives the visible "refreshing…" bar. Background refreshes (init,
    // ON_RESUME, post-login, cache-miss) flip `probing` but NOT
    // `userRefreshing`, so the bar stays hidden — the corner spinner up
    // in the topbar actions slot is the only signal. User asked for this
    // explicitly:.
    val userRefreshing by vm.userRefreshing.collectAsState()
    val error by vm.error.collectAsState()
    val diagnosis by vm.diagnosis.collectAsState()
    val installingSet by vm.installing.collectAsState()
    val installOutput by vm.installOutput.collectAsState()
    // True after the FIRST status probe of this VM instance completes.
    // When false, statuses are either missing or stale-from-cache: we
    // show "[ checking ]" badges and disable row taps until a fresh
    // probe lands. The VM defaults this to TRUE when re-entering an
    // already-established SSH (returning from a chat), so cached
    // statuses render instantly and refresh happens silently — only
    // a truly-fresh login (pool connected <5s ago) keeps it false.
    val firstProbeDone by vm.firstProbeDone.collectAsState()

    // The corner refresh spinner must ALSO stay up while a live-auth verdict is
    // still pending for any agent. The fast probe flips `probing` false the
    // instant it returns, but Gemini's OAuth re-check (kickLiveAuth) runs async
    // right after — so without this the spinner vanished and Gemini's check then
    // looked like a jarring SEPARATE step that started after the refresh was
    // "done". Folding liveAuthPending in keeps that check UNDER the spinner:
    // "spinner gone" now means everything, including live-auth, has settled.
    val anyLiveAuthPending = statuses?.values?.any { it.liveAuthPending } == true

    // Browse (Agents tab) always renders the list (from cache) — never the
    // full "Checking server…" gate, since we deliberately don't probe there.
    val agentListUnlocked = browse || !isSkServer || authConfirmed
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Re-run the probe / SK touch flow on every ON_RESUME *after the
    // first one*. Skipping the very first ON_RESUME matters because
    // AgentPickerViewModel.init already kicks refresh() from a coroutine,
    // and that coroutine hasn't yet set _skTouchRequest by the time our
    // ON_RESUME observer fires. If we don't gate, we race init's refresh
    // with our own, both post a fresh SkTouchRequest object, the dialog
    // re-mounts on the second post and a brand-new DeferredCtapSkSigner
    // is created — the OLD signer was the one whose withNfc{} block
    // captured the user's tap, so the new dialog instance shows
    // 'Tap or plug' forever while the OS still has the tag held by the
    // abandoned signer. Net effect: user taps once, dialog appears to
    // ignore it and asks them to tap again.
    //
    // Subsequent ON_RESUMEs (e.g., user navigated to Keychain and back) still
    // trigger a refresh when pool is dead AND probing isn't in flight AND no
    // touch is already requested. rememberSaveable, NOT remember: this flag
    // exists to skip exactly ONE ON_RESUME — the one that races
    // AgentPickerViewModel.init's own refresh(). With plain `remember` it resets
    // whenever the composable is rebuilt from scratch (nav back from a chat,
    // process death, config change), so the FIRST resume after coming back is
    // swallowed as if it were the init race — and that is the resume that was
    // supposed to re-arm SK touch on a dead pool. INVARIANTS.md carried this as
    // and it stayed open until 2026-08-02.
    var firstResumeSeen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (!firstResumeSeen) {
                    // VM.init's refresh() owns the first attempt — don't
                    // race it.
                    firstResumeSeen = true
                    return@LifecycleEventObserver
                }
                val live = ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(serverId) != null
                val probingNow = vm.probing.value
                // A login in progress OWNS this resume: OAuth REQUIRES leaving to
                // the browser and coming back, so ON_RESUME here is the normal
                // return leg — NOT a cue to blanket-refresh all agents. Stand
                // down until the login dialog closes; the login flow itself
                // refreshes the one relevant agent on done.
                if (!live && !probingNow && vm.skTouchRequest.value == null &&
                    vm.loginRequest.value == null && !browse
                ) {
                    // browse (Agents tab): don't auto-connect on resume — the
                    // user refreshes explicitly. Avoids a surprise key prompt.
                    vm.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Arrived from the Agents overview by tapping a "log in" agent → drive the
    // login flow over a clean backdrop (the legacy agent-list scaffold is
    // suppressed below). The server must be CONNECTED first (startOAuthLogin
    // needs a live pool; refresh() on entry connects, prompting the SK touch),
    // so wait for the transport, THEN open the method chooser. Fires once.
    LaunchedEffect(autoLoginAgent) {
        val a = autoLoginAgent ?: return@LaunchedEffect
        var waited = 0
        while (ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(serverId) == null &&
            waited < 120_000
        ) {
            kotlinx.coroutines.delay(300); waited += 300
        }
        if (ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(serverId) != null) {
            vm.startLogin(a)
        }
    }

    // Watchdog: if there is no live SSH for this server and no in-flight
    // touch dialog, the user has no business sitting on a per-server
    // screen — bounce them back to the server list.
    //
    // EXCEPTIONS the watchdog must respect: 1. `error!= null` —
    // pre-flight TCP probe surfaced an error. 2. `probing == true` —
    // refresh() is still running. The state hasn't settled yet; popping
    // during a 3-second TCP probe means they never see the result.
    val loginActive = vm.loginRequest.collectAsState().value != null
    androidx.compose.runtime.LaunchedEffect(authConfirmed, skTouch, isSkServer, server, error, probing, diagnosis, loginActive) {
        if (browse) return@LaunchedEffect  // Agents tab: cache-only is valid; never bounce.
        if (server == null) return@LaunchedEffect
        if (!isSkServer) return@LaunchedEffect
        if (authConfirmed) return@LaunchedEffect
        if (skTouch != null) return@LaunchedEffect
        // An OAuth login is on screen — the user is mid-flow (often just back
        // from the browser via PiP). NEVER pop them to the server list here;
        // that's what destroyed the code-entry dialog.
        if (loginActive) return@LaunchedEffect
        if (error != null) return@LaunchedEffect
        if (diagnosis != null) return@LaunchedEffect  // ← keep user on screen reading the diagnostic card
        if (probing) return@LaunchedEffect  // ← wait for refresh() to settle
        // Bumped from 600 ms → 3000 ms. The 600 ms grace period was
        // shorter than refresh()'s slow path: VM init does a DB load
        // (server + secrets), TCP-probes the host (up to 3 s), then
        // classifies via ServerDiagnostics — only at the END of all
        // that does `_skTouchRequest` flip non-null or `_probing`
        // flip true. On a cold cache (first open after app start)
        // the whole sequence can take 1-2 s, well over 600 ms, and
        // the watchdog kept popping the screen back to the servers
        // list before SK touch ever appeared. 3 s gives every
        // realistic refresh() path enough time to set probing or
        // skTouch; beyond that something's really wrong and popping
        // back is the right call.
        kotlinx.coroutines.delay(3_000)
        // Fix 1: re-check resumed state AFTER the delay. During the 600ms
        // window the user could have navigated to Keychain (lifecycle drops
        // to STARTED); firing onBack() then would pop Keychain off.
        val lifecycleState = lifecycleOwner.lifecycle.currentState
        if (!lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            android.util.Log.d("SshAi-AgentPicker", "watchdog skipped — screen no longer resumed (state=$lifecycleState)")
            return@LaunchedEffect
        }
        if (!authConfirmed && skTouch == null && vm.error.value == null
            && !vm.probing.value
            && ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(serverId) == null
        ) {
            android.util.Log.d("SshAi-AgentPicker", "no active session and no in-flight touch — popping to servers")
            onBack()
        }
    }
    var hostSheetOpen by rememberSaveable { mutableStateOf(false) }
    val connected = server?.let {
        ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(it.id) != null
    } ?: false
    if (autoLoginAgent == null) ai.eight24family.conch.ui.components.SearchableScaffold(
        title = {
            Column {
                Text(
                    "Conch",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(
                            enabled = server != null,
                            onClick = { hostSheetOpen = true },
                        )
                        .padding(top = 2.dp, bottom = 4.dp),
                ) {
                    Text(
                        "❯ ",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        server?.name ?: "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = ai.eight24family.conch.ui.theme.serverNameColor(
                            serverId = server?.id,
                            serverName = server?.name,
                            fallback = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ConnectionDot(connected = connected)
                }
            }
        },
        navigationIcon = {
            // Per-server agents is a DRILL now (from the Agents overview or a
            // server tap) — not the top-level tab — so back returns to it.
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
        },
        extraActions = {
            // Settings live in the bottom nav now — only the probe spinner here.
            if (probing || anyLiveAuthPending) TopBarSpinner(tint = MaterialTheme.colorScheme.outline)
        },
        scopedServerId = serverId,
        onPickHit = onOpenChatFromSearch,
    ) { padding ->
        // Pull-to-refresh — every swipe re-fires vm.refresh(), which
        // re-runs the status probe (including the `npm view` version
        // checks) and re-classifies each agent badge.
        //
        // Default circular arc indicator is suppressed; we render our
        // own "⟳ refreshing…" status bar below the topbar instead —
        // gives a clearer affordance than a floating circle.
        val refreshHaptic = ai.eight24family.conch.ui.haptic.LocalSshAiHaptics.current
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            // `isRefreshing` only flips for user-triggered gestures so
            // PullToRefreshBox doesn't act as if it's busy during
            // background refreshes (would inhibit further gestures
            // while invisible work is in flight).
            isRefreshing = userRefreshing,
            onRefresh = {
                // GestureEnd haptic — confirms the swipe-release
                // landed and the refresh actually started. Without
                // it the user sometimes can't tell if their gesture
                // registered (the "refreshing…" bar is subtle).
                refreshHaptic.perform(
                    ai.eight24family.conch.ui.haptic.SshAiHaptic.GestureEnd
                )
                vm.refresh(userTriggered = true)
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            indicator = {},
        ) {
          ServerAgentPanel(
              vm = vm,
              serverId = serverId,
              browse = browse,
              onPickAgent = onPickAgent,
              onOpenKeychainForDiscover = onOpenKeychainForDiscover,
              onOpenKeychainForRegister = onOpenKeychainForRegister,
              // Picker is a full screen — a stranded touch (90s, no signer) pops
              // back to the server list. (In the overview this is a no-op.)
              onSkTouchTimeout = { onBack() },
              modifier = Modifier.fillMaxSize(),
          )
        }
    } else {
        // Login-only launch (tapped a "log in" pill on the new Agents overview):
        // NO legacy agent-list / search chrome. Clean backdrop; the SK-touch
        // connect dialog + login/OAuth dialogs carry the flow. The old per-server
        // agents page is GONE from this flow — the user demanded it removed.
        // (Updates DON'T come here anymore — they run in place on the overview
        // with the live log under the row.)
        // In this branch autoLoginAgent is non-null (the only gate condition).
        val headline = "Sign in to ${autoLoginAgent.displayName}"
        val notConnected = ai.eight24family.conch.di.ServiceLocator
            .sshConnectionPool.peek(serverId) == null
        val busy = skTouch != null || notConnected
        LoginOnlyBackdrop(
            serverName = server?.name,
            headline = headline,
            busy = busy,
            onBack = onBack,
        )
    }
    if (hostSheetOpen) {
        server?.let {
            HostInfoSheet(
                server = it,
                onDismiss = { hostSheetOpen = false },
                onOpenTerminal = { onOpenTerminal(it.id, it.name) },
            )
        } ?: run { hostSheetOpen = false }
    }
    // (Touch dialog + login/method/account dialogs are rendered by
    // ServerAgentPanel above — they're VM-driven and shared with the overview.)
}

/**
 * The per-server agent panel: the agent rows + every VM-driven dialog + the
 * security-key touch lifecycle. Extracted from [AgentPickerScreen] so the SAME
 * proven interface (live install/update log, login, method switch, accounts,
 * touch-connect) can be hosted in two places — the full-screen picker AND
 * inline, per server, in the Agents overview. Renders a plain [Column] (only
 * the fixed [Agent.entries] rows) so it nests inside any scroll; the caller
 * supplies the outer chrome (scaffold / pull-to-refresh / overview list).
 */
@Composable
internal fun ServerAgentPanel(
    vm: AgentPickerViewModel,
    serverId: String,
    browse: Boolean,
    onPickAgent: (Agent) -> Unit,
    onOpenKeychainForDiscover: (String) -> Unit,
    onOpenKeychainForRegister: (String) -> Unit,
    onSkTouchTimeout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val statuses by vm.statuses.collectAsState()
    val authConfirmed by vm.authConfirmed.collectAsState()
    val isSkServer by vm.isSkServer.collectAsState()
    val skTouch by vm.skTouchRequest.collectAsState()
    val probing by vm.probing.collectAsState()
    val userRefreshing by vm.userRefreshing.collectAsState()
    val error by vm.error.collectAsState()
    val diagnosis by vm.diagnosis.collectAsState()
    val installingSet by vm.installing.collectAsState()
    val installOutput by vm.installOutput.collectAsState()
    val installOp by vm.installOp.collectAsState()
    val firstProbeDone by vm.firstProbeDone.collectAsState()
    val serverOs by vm.serverOs.collectAsState()
    // Browse (the overview / Agents tab) always renders the list from cache; a
    // SK server otherwise gates the list behind a confirmed auth.
    val agentListUnlocked = browse || !isSkServer || authConfirmed

    // Countdown for the "(Ns remaining)" hint while the touch dialog is up.
    var remainingSeconds by remember { mutableStateOf(90) }
    androidx.compose.runtime.LaunchedEffect(skTouch) {
        if (skTouch == null) { remainingSeconds = 90; return@LaunchedEffect }
        remainingSeconds = 90
        while (remainingSeconds > 0 && vm.skTouchRequest.value != null) {
            kotlinx.coroutines.delay(1_000); remainingSeconds -= 1
        }
    }
    // Sanity timeout: touch dialog up 90s with no signer → give up. The caller
    // decides whether to ALSO navigate away (picker pops; overview just clears).
    androidx.compose.runtime.LaunchedEffect(skTouch) {
        if (skTouch == null) return@LaunchedEffect
        kotlinx.coroutines.delay(90_000)
        if (vm.skTouchRequest.value != null) {
            vm.cancelSkRefresh()
            onSkTouchTimeout()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Pull-to-refresh feedback bar (only on a deliberate user refresh).
        androidx.compose.animation.AnimatedVisibility(
            visible = userRefreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  refreshing…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // No "offline · showing cached — tap to refresh" banner: the offline
        // state is already shown by the connection dot next to the server name
        // (no point repeating it), and the user never refreshes by hand — the
        // app re-lists itself the moment a connection comes up.
        when {
            diagnosis != null -> DiagnosisCard(
                diagnosis = diagnosis!!,
                onRetry = { vm.refresh(userTriggered = true) },
            )
            error != null -> Text(
                "// $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            !agentListUnlocked && !probing -> Column(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                val gapText = if (skTouch != null) "Authenticating with your security key…" else "Checking server…"
                Text(
                    gapText,
                    modifier = Modifier.padding(top = 16.dp, start = 32.dp, end = 32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (skTouch != null && remainingSeconds < 30) {
                    Text(
                        "(${remainingSeconds}s remaining)",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            else -> {
                // In-flight login (this whole screen shares ONE): once the user
                // has pasted their code/URL (`submitted`), the OAuth exchange +
                // credential poll can run for many seconds. Surface that on the
                // agent's OWN row so it reads "signing in…" instead of a stale
                // "[ log in ]" that looks like it's asking them to start over
                // (user, 2026-07-05).
                val loginNow = vm.loginRequest.collectAsState().value
                val usageBriefs = vm.usageBrief.collectAsState().value
                Agent.entries.forEach { agent ->
                    val s = statuses?.get(agent)
                    val rowChecking = !firstProbeDone || (s?.liveAuthPending == true)
                    AgentRow(
                        agent = agent,
                        status = if (rowChecking) null else s,
                        installing = installingSet.contains(agent),
                        anyInstalling = installingSet.contains(agent),
                        liveOutput = installOutput[agent],
                        op = installOp[agent],
                        checking = rowChecking,
                        loggingIn = loginNow?.agent == agent && loginNow.submitted &&
                            loginNow.serverId == vm.serverId,
                        windowsServer = serverOs == "WINDOWS",
                        usageLine = usageBriefs[agent],
                        onClick = { vm.rememberAgent(agent); onPickAgent(agent) },
                        onInstall = { vm.installAgent(agent) },
                        onLogin = { vm.startLogin(agent) },
                        onUseApiKey = { vm.switchToApiKey(agent) },
                        onLongClick = { vm.openMethodSheet(agent) },
                    )
                }
            }
        }
    }

    // ── Overlays (Dialogs / sheets), VM-driven, one at a time ──
    skTouch?.let { req ->
        SkInlineTouchDialog(
            transport = req.transport,
            credentialIdBase64 = req.credentialIdBase64,
            application = req.application,
            onUsbSigner = { signer -> vm.runProbeWithSigner(signer) },
            onNfcSigner = { signer -> vm.runProbeWithSigner(signer) },
            onCancel = { vm.cancelSkRefresh() },
            onDiscoverOnKey = { vm.cancelSkRefresh(); onOpenKeychainForDiscover(serverId) },
            onRegisterNewKey = { vm.cancelSkRefresh(); onOpenKeychainForRegister(serverId) },
        )
    }
    // ⚠ OWN SERVER ONLY. The flow behind this dialog is process-global
    // (AgentPickerViewModel.activeLogin) while THIS composable exists once per
    // server on the Agents overview — ungated, every panel stacked its own
    // copy of the dialog and the topmost one belonged to a VM that never
    // started any login: its submit hit a null stdin and the button silently
    // did nothing. Retry and "use API key" would be aimed at the wrong server
    // too.
    val loginReq = vm.loginRequest.collectAsState().value
    if (loginReq != null && loginReq.serverId == vm.serverId) {
        LoginDialog(
            request = loginReq,
            onCancel = { vm.cancelLogin() },
            onSubmitCode = { code, manual ->
                if (loginReq.callbackMode) vm.submitCodexCallback(code)
                else vm.submitOAuthCode(code, manual)
            },
            onRetry = { vm.startOAuthLogin(loginReq.agent) },
            onUseApiKey = { vm.switchToApiKey(loginReq.agent) },
        )
    }
    val loginPicker = vm.loginPicker.collectAsState().value
    if (loginPicker != null) {
        LoginMethodPicker(
            agent = loginPicker,
            onChooseApiKey = { vm.chooseApiKey() },
            onChooseOAuth = { vm.chooseOAuth() },
            onCancel = { vm.cancelLoginPicker() },
        )
    }
    val apiKeyEntry = vm.apiKeyEntry.collectAsState().value
    if (apiKeyEntry != null) {
        ApiKeyDialog(
            agent = apiKeyEntry,
            onSubmit = { vm.submitApiKey(apiKeyEntry, it) },
            onCancel = { vm.cancelApiKeyEntry() },
        )
    }
    val methodSheetAgent = vm.methodSheetAgent.collectAsState().value
    if (methodSheetAgent != null) {
        val allSlots = vm.slots.collectAsState().value
        val activeSlots = vm.activeSlots.collectAsState().value
        AuthManagerSheet(
            agent = methodSheetAgent,
            slots = allSlots[methodSheetAgent].orEmpty(),
            activeSlotId = activeSlots[methodSheetAgent],
            activePlan = ai.eight24family.conch.agent.UsageProbe
                .cached(vm.serverId, methodSheetAgent)?.plan,
            busySlotId = vm.accountOpBusy.collectAsState().value,
            opError = vm.accountOpError.collectAsState().value,
            onActivateSlot = { vm.activateSlot(methodSheetAgent, it) },
            onRenameSlot = { vm.openRename(methodSheetAgent, it) },
            onRemoveSlot = { vm.removeSlot(methodSheetAgent, it) },
            onAddAccount = { vm.addAccount(methodSheetAgent) },
            onRemoveAgent = { vm.removeAgent(methodSheetAgent) },
            onDismiss = { vm.closeMethodSheet() },
        )
    }
    // (No "name this account" dialog — a fresh login auto-captures as the next
    // ordinal and the row goes straight to ready; renaming is on-demand via the
    // pencil. See AgentPickerViewModel.onLoginSuccess.)
    val renamePrompt = vm.renamePrompt.collectAsState().value
    if (renamePrompt != null) {
        AccountNameDialog(
            defaultName = renamePrompt.current,
            onSave = { vm.confirmRename(it) },
            onCancel = { vm.cancelRename() },
        )
    }
}

/**
 * Clean backdrop shown when the picker is opened LOGIN-ONLY (tapping a "log in"
 * agent on the Agents overview). No legacy agent-list / search chrome — just a
 * dark screen with the target identity; the SK-touch connect dialog and the
 * login chooser / OAuth dialogs render on top and carry the whole flow.
 */
@Composable
private fun LoginOnlyBackdrop(
    serverName: String?,
    headline: String,
    busy: Boolean,
    onBack: () -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cyan)
        }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            serverName?.let {
                // Only the NAME takes the accent; the `❯ ` prompt stays dim.
                val accent = ai.eight24family.conch.ui.theme.serverNameColor(
                    serverName = it,
                    fallback = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("❯ ")
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(color = accent),
                        ) { append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                Spacer(Modifier.size(4.dp))
                CircularProgressIndicator(
                    color = cyan,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * Auth manager (long-press an agent). Lists the saved ACCOUNTS for this agent
 * — tap to switch the active one, ✕ to log out — and a single "+ add account"
 * action. The currently-logged-in account is auto-saved as the first entry, so
 * there's no separate "save" step. Multiple accounts per method are supported:
 * juggle several ChatGPT / Google logins (see CredentialVault).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AuthManagerSheet(
    agent: Agent,
    slots: List<ai.eight24family.conch.agent.CredentialVault.Slot>,
    activeSlotId: String?,
    /** Live plan for the ACTIVE account (UsageProbe cache) — fills the plan
     *  line for credentials that carry no plan of their own (Claude
     *  setup-token). Null when never probed. */
    activePlan: String? = null,
    /** Slot an operation (remove / switch) is running on: that row shows a
     *  spinner instead of its action icons, and taps are ignored meanwhile —
     *  seconds of SSH must never look like a dead button. */
    busySlotId: String? = null,
    /** Why the last operation did nothing (no transport, server didn't
     *  confirm). Rendered in the sheet, in error colour. */
    opError: String? = null,
    onActivateSlot: (String) -> Unit,
    onRenameSlot: (ai.eight24family.conch.agent.CredentialVault.Slot) -> Unit,
    onRemoveSlot: (String) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAgent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface
    val danger = MaterialTheme.colorScheme.error
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "${agent.displayName} · accounts",
                style = MaterialTheme.typography.titleMedium,
                color = fg,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Switch active account; auto-fails over to the next when limits hit.",
                style = MaterialTheme.typography.labelSmall,
                color = dim,
            )
            Spacer(Modifier.height(14.dp))

            if (slots.isEmpty()) {
                Text(
                    "No accounts yet — tap “+ add account”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                for (s in slots) {
                    val isActive = s.id == activeSlotId
                    val rowBusy = s.id == busySlotId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = busySlotId == null) { onActivateSlot(s.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (isActive) "●" else "○", color = if (isActive) primary else dim)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.label, color = fg)
                            Text(
                                slotMethodLine(agent, s),
                                style = MaterialTheme.typography.labelSmall,
                                color = dim,
                            )
                            s.email?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = dim)
                            }
                            slotFactsLine(agent, s, if (isActive) activePlan else null)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = dim)
                            }
                        }
                        if (rowBusy) {
                            // The tap is being worked on — SSH round-trips take
                            // seconds, and a silent ✕ reads as a dead button.
                            CircularProgressIndicator(
                                color = primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .size(16.dp),
                            )
                        } else {
                            Text(
                                "✎",
                                color = dim,
                                modifier = Modifier
                                    .clickable(enabled = busySlotId == null) { onRenameSlot(s) }
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                            Text(
                                "✕",
                                color = dim,
                                modifier = Modifier
                                    .clickable(enabled = busySlotId == null) { onRemoveSlot(s.id) }
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                opError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = danger,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "[ + add account ]",
                color = primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { onAddAccount() }.padding(vertical = 8.dp),
            )
            // Destructive — uninstall the CLI from the server. Divider + error
            // colour set it apart from the account actions above. Chat history
            // under ~/.<agent>/ is preserved; only the binary goes.
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                "[ remove ${agent.displayName} from server ]",
                color = danger,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { onRemoveAgent() }
                    .padding(vertical = 8.dp),
            )
            Text(
                "uninstalls the CLI · your chat history is kept",
                color = dim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Name dialog shown right after an "add account" login — prefilled with a
 *  default ("Account N"), text selected + keyboard up, so the user just renames
 *  or hits Save. Saving captures the just-logged-in account under this name. */
@Composable
private fun AccountNameDialog(
    defaultName: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var value by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                defaultName,
                selection = androidx.compose.ui.text.TextRange(0, defaultName.length),
            )
        )
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Name this account",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "Cancel",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.clickable { onCancel() }.padding(8.dp),
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        "Save",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onSave(value.text) }.padding(8.dp),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun AgentRow(
    agent: Agent,
    status: AgentStatus?,
    installing: Boolean = false,
    anyInstalling: Boolean = false,
    liveOutput: String? = null,
    /** What the in-flight op is (install/update/remove) — drives the badge
     *  verb + the sub-line fallback so an uninstall says "removing", not
     *  "installing". null when nothing's in flight. */
    op: InstallOp? = null,
    /** When true, status is intentionally hidden — we're mid-probe
     *  after a fresh login. Row renders `[ checking ]` and ignores
     *  taps so the user can't open chat against a stale cache. */
    checking: Boolean = false,
    /** This agent's OAuth login has been submitted and the code/URL exchange
     *  is running (many seconds). Row shows "signing in…" and its tap is inert
     *  so it can't read as "start over". */
    loggingIn: Boolean = false,
    /** The OS pre-probe identified this as a Windows OpenSSH server: agents
     *  can't run there (no sh), so the row says WHY instead of a misleading
     *  "not installed" (honest detection; support itself is out of scope). */
    windowsServer: Boolean = false,
    /** Compact live limits ("5h 28% · Weekly 3%"), known from the connection
     *  alone — shown on the ready line so the user sees their budget BEFORE
     *  entering a chat. Null = unknown (not fetched / agent has no quota API). */
    usageLine: String? = null,
    onClick: () -> Unit,
    onInstall: () -> Unit = {},
    onLogin: () -> Unit = {},
    /** Logged in but the Claude subscription has NO Claude Code access — tap
     *  routes here (open the API-key entry, the remedy the CLI itself suggests). */
    onUseApiKey: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val cyan = MaterialTheme.colorScheme.primary
    val amber = MaterialTheme.colorScheme.tertiary
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface

    // Logged in (OAuth) but the account is in a BLOCK Claude-Code run-state (no
    // subscription / trial ended / payment due / login expired / rate limited …).
    // NOT "log in" (re-auth won't fix most). There are SEVERAL remedies (subscribe,
    // ask admin, add an API key, switch to another logged-in account, wait) — so a
    // tap must NOT force the API-key dialog. It opens the account/method sheet
    // where the user picks; the sub-line + badge already state the reason.
    val runBlocked = !checking && status != null && status.installed &&
        !status.updateAvailable && status.loggedIn && status.claudeState?.isBlocked == true

    // Block entry to chat until: installed, no update available, logged in, AND the
    // account can actually run a turn (else it dies on the CLI's refusal).
    val canEnter = !checking &&
        status?.installed == true && status.loggedIn && !status.updateAvailable &&
        status.claudeState?.isBlocked != true

    // Don't assert "ready" — show a small "checking subscription…" until it
    // resolves. Once ANY probe determines it, the cache PRESERVES that verdict
    // (AgentStatusCache.save), so this only ever shows in the brief first-probe
    // window, never as a stuck false "ready".
    val runStatePending = !checking && agent == Agent.CLAUDE && status != null &&
        status.installed && !status.updateAvailable && status.loggedIn &&
        "oauth" in status.methods && status.claudeState == null

    // Row-level click priorities:
    //   - install → not installed
    //   - update  → installed but newer version available (gates chat entry)
    //   - log in  → installed + current but no credentials → start OAuth
    //   - open chat → ready
    // While `checking`, every action is suppressed — cached status is
    // hidden, the row is non-interactive until the fresh probe lands.
    val needsInstall = !checking && status != null && !status.installed
    val needsUpdate = !checking && status != null && status.installed && status.updateAvailable
    // `!loggingIn`: while the exchange is running the row must NOT re-fire the
    // login flow on tap (that's the "invites me to log in again" confusion).
    val needsLogin = !checking && !loggingIn && status != null && status.installed && !status.updateAvailable && !status.loggedIn && status?.claudeState?.isBlocked != true
    val rowClick = when {
        canEnter -> onClick
        (needsInstall || needsUpdate) && !anyInstalling -> onInstall
        // Blocked run-state → open the account/method sheet (switch account, add
        // an API key, re-login …), NOT a forced API-key dialog.
        runBlocked -> onLongClick
        needsLogin -> onLogin
        else -> ({})
    }
    val rowEnabled = canEnter ||
        ((needsInstall || needsUpdate) && !anyInstalling) ||
        runBlocked ||
        needsLogin
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Long-press an INSTALLED agent → auth-method switcher. `enabled`
            // gates both gestures, so allow it whenever installed (even if the
            // normal tap is a no-op, e.g. needs update) so the switcher is
            // always reachable.
            .combinedClickable(
                enabled = rowEnabled || status?.installed == true,
                onClick = rowClick,
                onLongClick = { if (status?.installed == true) onLongClick() },
            )
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(
                    ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent].iconRes,
                ),
                contentDescription = agent.displayName,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 4.dp)
                    .let { if (!canEnter) it.alpha(0.4f) else it },
            )
            Text(
                agent.displayName,
                color = if (canEnter) fg else dim,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = true)
            )
            MethodBadges(status = status, checking = checking, cyan = cyan, dim = dim)
            Spacer(Modifier.width(8.dp))
            StatusBadge(
                status = status,
                installing = installing,
                op = op,
                checking = checking,
                loggingIn = loggingIn,
                runStatePending = runStatePending,
                cyan = cyan,
                amber = amber,
                dim = dim,
            )
        }
        // Sub-text. While installing, stream the SSH stdout tail live
        // (last line read off the channel) — the user sees exactly
        // what bash is doing at any moment. When idle, show a short
        // descriptive status.
        val sub = when {
            installing && liveOutput != null -> "  $liveOutput"
            installing -> "  ${op?.line ?: "installing…"}"
            loggingIn -> "  signing in… finishing up"
            checking -> "  checking…"
            // Before the not-installed line: on a Windows box "not installed"
            // is the wrong claim — the agent may well be installed, WE can't
            // drive it (every probe/launch is sh). Say the true reason.
            windowsServer -> "  Windows OpenSSH server — not supported yet"
            status == null -> "  …probing"
            !status.installed -> "  not installed"
            status.updateAvailable ->
                "  ${status.installedVersion ?: "?"} → ${status.latestVersion ?: "?"} · update before opening"
            !status.loggedIn -> "  not logged in — tap to start OAuth"
            // Claude run-state (no subscription / trial / payment / rate limit /
            // login expired …) — the enum's own honest line + datum. Covers BLOCK
            // and WARN; OK/UNKNOWN have an empty line and fall through to "ready".
            status.claudeState?.line?.isNotEmpty() == true ->
                "  " + (status.claudeState?.lineWith(status.claudeStateData) ?: "")
            // Run-state (subscription) not yet determined — honest "still
            // checking" instead of a premature "ready".
            runStatePending -> "  checking subscription…"
            else -> "  ready · ${status.installedVersion ?: ""}" +
                (usageLine?.let { " · $it" } ?: "")
        }
        Text(
            sub,
            color = dim,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (installing) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Small auth-method chips on the agent row, left of the status badge — one
 *  per DETECTED method, the active one brighter. Little "which auth this
 *  server has" achievement badges (API / OAUTH / VERTEX …). */
@Composable
private fun MethodBadges(
    status: AgentStatus?,
    checking: Boolean,
    cyan: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
) {
    if (checking || status == null || status.methods.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in status.methods.toList().sorted()) {
            val isActive = key == status.activeMethod
            val c = if (isActive) cyan else dim
            Text(
                methodBadgeLabel(key),
                style = MaterialTheme.typography.labelSmall,
                color = c,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(
                        c.copy(alpha = 0.13f),
                        androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

private fun methodBadgeLabel(key: String): String = when (key) {
    "oauth" -> "OAuth"
    "api" -> "API"
    "bearer" -> "Token"
    "vertex" -> "Vertex"
    "bedrock" -> "Bedrock"
    "chatgpt" -> "OAuth"
    else -> key.replaceFirstChar { it.uppercase() }
}

// ── Account passport display (AuthManagerSheet row details) ──────────────────
// Facts extracted server-side from the slot's own credential copy (see
// CredentialVault.listSlots enrichment). Everything here is as-of-capture.

private fun fmtDay(epochSec: Long): String? =
    if (epochSec <= 0) null
    else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
        .format(java.util.Date(epochSec * 1000))

private fun isoToEpochSec(iso: String?): Long? = iso?.let {
    runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant().epochSecond }.getOrNull()
}

/** Second row: what KIND of credential this slot holds. */
private fun slotMethodLine(agent: Agent, s: ai.eight24family.conch.agent.CredentialVault.Slot): String {
    val badge = methodBadgeLabel(s.method)
    val kindDetail = when {
        s.masked != null -> s.masked
        // Claude OAuth comes in two shapes worth telling apart: the 1-year
        // setup-token vs a refreshing full-oauth session file.
        agent == Agent.CLAUDE && s.kind == "token" -> "long-lived token"
        agent == Agent.CLAUDE && s.kind == "file" -> "session file"
        else -> null
    }
    return if (kindDetail != null) "$badge · $kindDetail" else badge
}

/** Bottom row: added date · plan · the one expiry fact that matters. */
private fun slotFactsLine(
    agent: Agent,
    s: ai.eight24family.conch.agent.CredentialVault.Slot,
    livePlan: String?,
): String? {
    val now = System.currentTimeMillis() / 1000
    val parts = mutableListOf<String>()
    fmtDay(s.createdAt)?.let { parts.add("added $it") }
    val plan = s.plan ?: livePlan
    plan?.let { parts.add(it.replaceFirstChar { c -> c.uppercase() } + " plan") }
    when {
        // Claude setup-token: documented 1-year lifetime, nothing inside the
        // token to read — computed from the capture date, hence the ~.
        agent == Agent.CLAUDE && s.kind == "token" && s.createdAt > 0 -> {
            val exp = s.createdAt + 365L * 24 * 3600
            fmtDay(exp)?.let {
                parts.add(if (exp < now) "token expired $it" else "token expires ~$it")
            }
        }
        // Codex: the id_token says how far the subscription is paid.
        s.planUntil != null -> isoToEpochSec(s.planUntil)?.let { untilSec ->
            fmtDay(untilSec)?.let {
                parts.add(if (untilSec < now) "sub lapsed $it" else "sub until $it")
            }
        }
        // Codex without a sub claim: at least say how fresh the session copy is.
        s.lastRefresh != null -> isoToEpochSec(s.lastRefresh)?.let { refSec ->
            fmtDay(refSec)?.let { parts.add("session from $it") }
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun StatusBadge(
    status: AgentStatus?,
    installing: Boolean = false,
    /** In-flight op — picks the pill verb (install/update → "installing",
     *  remove → "removing"). null falls back to "installing". */
    op: InstallOp? = null,
    /** Show `[ checking ]` instead of the cached badge — driven by
     *  the parent's `firstProbeDone` flag during a fresh-login. */
    checking: Boolean = false,
    /** OAuth code/URL submitted, exchange in flight — show a live "signing in…"
     *  pill so the long completion window reads as progress, not "[ log in ]". */
    loggingIn: Boolean = false,
    /** Claude OAuth account whose run-state (subscription / limits) isn't known
     *  yet — show a dim "[ … ]" working pill, not a premature cyan "[ ready ]". */
    runStatePending: Boolean = false,
    cyan: Color = MaterialTheme.colorScheme.primary,
    amber: Color = MaterialTheme.colorScheme.tertiary,
    dim: Color = MaterialTheme.colorScheme.outline,
) {
    val (label, color) = when {
        loggingIn -> "[ signing in… ]" to cyan
        checking -> "[ checking ]" to dim
        status == null -> "[ … ]" to dim
        // Gray (dim) — communicates "mid-flight, can't tap right now". The row
        // itself is also non-clickable while installing (see anyInstalling
        // gate).
        installing -> "[ ${op?.badge ?: "installing"} ]" to dim
        // The badge IS the action button — tap the row to trigger.
        //   install — CLI not on the server yet
        //   update  — installed but registry has a newer version
        //             (gates entry; can't open chat until updated)
        //   log in  — CLI installed and current; needs OAuth.
        //             Tap fires the device-code flow.
        //   ready   — all green, row tap opens chat
        !status.installed -> "[ install ]" to cyan
        status.updateAvailable -> "[ update ]" to amber
        !status.loggedIn -> "[ log in ]" to amber
        // Logged in, but the account is in a BLOCK run-state (no subscription /
        // trial ended / payment due / login expired / rate limited …) — a real
        // dead-end, not a login prompt. The enum supplies the honest short badge.
        status.claudeState?.isBlocked == true -> "[ ${status.claudeState?.badge ?: ""} ]" to amber
        // Run-state not yet determined → dim working pill, not a false "ready".
        runStatePending -> "[ … ]" to dim
        else -> "[ ready ]" to cyan
    }
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = RectangleShape,
        border = BorderStroke(1.dp, color)
    ) {
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Structured connectivity diagnosis card — replaces the legacy
 * single-line red banner with a title + bulleted reasons + retry
 * button. Rendered only when [vm.diagnosis] is non-null (i.e. the
 * pre-flight TCP probe failed AND ServerDiagnostics has produced
 * an actionable categorisation).
 *
 * Visual style follows the rest of the cyberpunk-CLI app: red title
 * for the error state, muted body for the reasons, accent-coloured
 * `[ retry ]` button at the bottom.
 */
@Composable
private fun DiagnosisCard(
    diagnosis: ai.eight24family.conch.ssh.ServerDiagnostics.Diagnosis,
    onRetry: () -> Unit,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "// ${diagnosis.title}",
            color = errorColor,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (diagnosis.reasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                diagnosis.reasons.forEach { reason ->
                    Row {
                        Text(
                            "• ",
                            color = muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            reason,
                            color = onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.padding(top = 4.dp))
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("[ retry ]", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Modal dialog that drives the agent's OAuth login flow. Streams the
 * server-side CLI's stdout, surfaces the URL + code as soon as the
 * device-code prompt appears, and auto-dismisses when the
 * credentials file lands on the server.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/** How many times the login dialog re-reads the clipboard after a return from
 *  the browser. Android only allows the read once the window has focus, which
 *  arrives some time AFTER onResume — a single read is a coin flip. */
private const val CLIP_GRAB_TRIES = 8

@Composable
private fun LoginDialog(
    request: ai.eight24family.conch.ui.viewmodel.AgentPickerViewModel.LoginRequest,
    onCancel: () -> Unit,
    /** (code, manual) — `manual` false for the clipboard auto-grab. A human
     *  press must always produce an answer; see submitOAuthCode. */
    onSubmitCode: (String, Boolean) -> Unit,
    /** Start the sign-in over — offered when the flow stalled on something the
     *  user has now fixed (typically: the server was not connected yet). */
    onRetry: () -> Unit,
    onUseApiKey: () -> Unit,
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val cyan = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var pasted by remember { mutableStateOf("") }
    // **CLIPBOARD AUTO-GRAB.** A full zero-touch intercept of the OAuth code is
    // impossible: the redirect lands on the PROVIDER's domain (platform.claude.com
    // / localhost-on-the-server), which our app can't claim via App Links, and a
    // WebView would break "Sign in with Google" (disallowed_useragent). The next
    // best thing: the user taps Copy on the provider's callback page, returns to
    // the app (one tap — PiP keeps us floating over the browser), and we read the
    // clipboard on that return, validate the shape, and SUBMIT AUTOMATICALLY — no
    // manual paste, no submit tap. Guards: only after the user actually LEFT the
    // app (ON_PAUSE seen — never on first open, where the clipboard may hold a
    // stale code), only while a paste is awaited, strict per-mode validation, and
    // same-clip dedup so a failed exchange isn't hammered in a loop.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var leftApp by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> leftApp = true
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    if (leftApp) { leftApp = false; resumeTick++ }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var lastAutoClip by remember { mutableStateOf("") }
    LaunchedEffect(resumeTick) {
        if (resumeTick == 0) return@LaunchedEffect
        if (request.submitted || request.fatalError != null) return@LaunchedEffect
        // ⚠ ONE READ IS NOT ENOUGH, AND THAT IS WHY THIS NEVER WORKED.
        //
        // Android 10+ hands the clipboard over only to a window that HAS FOCUS.
        // Coming back from the browser, focus lands some time after ON_RESUME —
        // longer on an OEM with its own transition, longer again when the app was
        // floating in PiP.
        //
        // So: poll for a few seconds and stop at the first read that yields a
        // code. Cheap (a string read per tick), bounded, and it costs nothing when
        // the clipboard is already readable on the first tick.
        //
        // The `awaitingPaste` gate is also gone from here: the dialog being up,
        // unsubmitted and without a fatal error IS the state where a code is
        // welcome. Requiring the CLI to have printed its prompt first meant a
        // fast copy-paste round trip lost the grab entirely.
        val codeRx = Regex("[A-Za-z0-9_-]{16,}#[A-Za-z0-9_-]{16,}")
        var found: String? = null
        repeat(CLIP_GRAB_TRIES) { attempt ->
            kotlinx.coroutines.delay(if (attempt == 0) 350L else 400L)
            if (request.submitted || request.fatalError != null) return@LaunchedEffect
            val clip = clipboard.getText()?.text?.trim().orEmpty()
            if (clip.isEmpty() || clip == lastAutoClip) return@repeat
            // ⚠ TAKE THE CODE, NOT THE CLIPBOARD. the browser's Copy button
            // also happily includes surrounding text on some pages.
            val hit = if (request.callbackMode) {
                clip.takeIf { it.startsWith("http") && it.contains("code=") }
            } else {
                codeRx.find(clip)?.value
            }
            if (hit != null) { lastAutoClip = clip; found = hit; return@repeat }
        }
        val code = found ?: run {
            android.util.Log.d(
                "SshAi-AgentPicker",
                "clipboard auto-grab found nothing in ${CLIP_GRAB_TRIES} tries — manual paste still works",
            )
            return@LaunchedEffect
        }
        android.util.Log.d(
            "SshAi-AgentPicker",
            "LoginDialog clipboard auto-grab — took ${code.length}B of a clip, callbackMode=${request.callbackMode}",
        )
        pasted = ""
        onSubmitCode(code, false)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RectangleShape,
            border = BorderStroke(1.dp, cyan),
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Sign in to ${request.agent.displayName}",
                    color = cyan,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                // **Provider refused** (e.g. Google declined Code Assist) — the
                // OAuth path is a dead end the user can't retry past. Explain it
                // and offer the API-key path; suppress the now-useless URL/paste.
                if (request.fatalError != null) {
                    Text(
                        request.fatalError,
                        color = fg,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = onUseApiKey,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("[ use API key ]", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Cancel")
                    }
                    return@Column
                }
                // **Submitted state** — the user has already pasted a
                // code / callback URL and tapped Submit. From this
                // point on the URL, the open-in-browser button, the
                // displayed device code, and the paste field are all
                // stale UI: there's nothing left for the user to do.
                // Just show a small spinner with a "wrapping up"
                // status line until the credentials file appears (or
                // the CLI errors out and the dialog gets dismissed).
                if (request.submitted) {
                    // Clean animation + a CONCRETE live status: the big line is the
                    // ONE thing happening right now (exchanging code / saving /
                    // checking subscription), the small dim log below is the trail
                    // of finished steps (✓). NOT the raw server dump. rawTail
                    // carries a `\n`-joined trail.
                    val steps = request.rawTail.lineSequence()
                        .map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    val current = steps.lastOrNull() ?: "Signing in…"
                    val done = if (steps.size > 1) steps.dropLast(1) else emptyList()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = cyan,
                        )
                        Text(
                            current,
                            color = fg,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (done.isNotEmpty()) {
                            Column(
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                done.forEach { s ->
                                    Text(
                                        "✓ $s",
                                        color = muted.copy(alpha = 0.55f),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    if (request.url != null) {
                        Text(
                            "1. Sign in via your browser:",
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Single button — no raw URL text. Anthropic /
                        // OpenAI OAuth URLs are 400+ chars of unreadable
                        // params; showing them in the dialog was ugly and
                        // the user never reads them anyway.
                        OutlinedButton(
                            onClick = { SilentlyTry.fired("SshAi-AgentPicker", "open OAuth url in browser") { uriHandler.openUri(request.url) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("[ open in browser ]", style = MaterialTheme.typography.labelLarge)
                        }
                        // Copy the link so the user can open it on ANOTHER device
                        // (e.g. one already signed into the right Google account).
                        // Android 13+ shows its own "Copied" toast on clipboard write.
                        var copied by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(request.url))
                                copied = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (copied) "[ link copied ✓ ]" else "[ copy link ]",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    if (request.code != null) {
                        Text(
                            "2. Enter this code on the page:",
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            request.code,
                            color = fg,
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (request.url == null && request.code == null) {
                        // ⚠ SAY WHAT THE FLOW SAYS. This used to be an
                        // unconditional spinner + "Starting OAuth flow…", which is
                        // a promise the app cannot keep once the flow has given up:
                        // the login wrote "no connection to this server" into
                        // rawTail and the screen spun over it (2026-08-18). The
                        // model's own words win, and a stalled flow gets a way
                        // forward instead of an animation.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!request.stalled) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp).padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                    color = cyan,
                                )
                            }
                            Text(
                                request.rawTail.takeIf { it.isNotBlank() && it != "starting…" }
                                    ?: "Starting OAuth flow…",
                                color = if (request.stalled) fg else muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (request.stalled) {
                            OutlinedButton(
                                onClick = { onRetry() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("[ retry ]", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                // Paste field. Two modes:
                //
                //  - **callbackMode (Codex)** — user pastes the full
                //    URL their phone browser landed on after sign-in
                //    (something like
                //    `http://localhost:1455/auth/callback?code=…&state=…`).
                //    The phone browser will show "Connection refused"
                //    because the listener is on the SERVER's localhost,
                //    not the phone's — that's expected. The URL bar
                //    still has the callback URL we need.
                //
                //  - **typed-code (Claude / Gemini)** — user pastes the
                //    short authorization code shown on the OAuth callback
                //    page, which we then type back into the CLI's stdin.
                if (request.awaitingPaste) {
                    val callbackPlaceholder = when (request.agent) {
                        Agent.CODEX -> "http://localhost:1455/auth/callback?code=…"
                        Agent.GEMINI -> "http://localhost:NNNN/oauth2callback?code=…"
                        else -> "http://localhost:…/callback?code=…"
                    }
                    val (label, placeholder, button) = if (request.callbackMode) Triple(
                        "${if (request.url != null) "2" else "1"}. After sign-in your browser will show \"Connection refused\" — that's expected. Copy the URL from the address bar and come back — it's picked up automatically (or paste it here):",
                        callbackPlaceholder,
                        "[ finish sign-in ]",
                    ) else Triple(
                        "${if (request.url != null) "2" else "1"}. Tap Copy on the page and come back — the code is picked up automatically (or paste it here):",
                        "Authorization code",
                        "[ submit code ]",
                    )
                    Text(
                        label,
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        placeholder = { Text(placeholder, color = muted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                    OutlinedButton(
                        onClick = {
                            android.util.Log.d(
                                "SshAi-AgentPicker",
                                "LoginDialog submit tapped — codeLen=${pasted.trim().length} callbackMode=${request.callbackMode}",
                            )
                            onSubmitCode(pasted, true)
                            pasted = ""
                        },
                        enabled = pasted.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(button, style = MaterialTheme.typography.labelLarge)
                    }
                }
                // Pre-submit: a short reassurance hint ONLY — never the raw CLI
                // tail (user: no server log in the window). Once submitted, the
                // clean "Signing in…" animation above is the whole story.
                if (!request.submitted) {
                    Text(
                        if (request.awaitingPaste)
                            "Copy the code and come back — it's picked up and submitted automatically."
                        else
                            "This window closes on its own once you've signed in.",
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Chooser dialog surfaced when the user taps `[ log in ]` on an agent
 * row. Two choices: paste an API key OR run the device-code OAuth
 * flow. Closes itself; the next state is driven by the ViewModel
 * (`apiKeyEntry` or `loginRequest`).
 *
 * Why a chooser at all (vs. picking one per agent automatically):
 *
 *   - Claude offers BOTH `ANTHROPIC_API_KEY` and OAuth via `claude /login`.
 *   - Codex offers BOTH `OPENAI_API_KEY` and OAuth via `codex login`.
 *   - Gemini offers BOTH `GEMINI_API_KEY` and OAuth via `gemini auth`.
 *
 * Some users have an API key in their password manager; others prefer
 * the ChatGPT-Plus / Claude-Pro subscription path that only OAuth can
 * unlock. We don't second-guess them.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LoginMethodPicker(
    agent: Agent,
    onChooseApiKey: () -> Unit,
    onChooseOAuth: () -> Unit,
    onCancel: () -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RectangleShape,
            border = BorderStroke(1.dp, cyan),
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Sign in to ${agent.displayName}",
                    color = cyan,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Choose how to authenticate. Both options are stored on your server.",
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.size(4.dp))
                OutlinedButton(
                    onClick = onChooseApiKey,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            "[ API key ]",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Paste a key — saved as an env var on the server",
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onChooseOAuth,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            "[ OAuth ]",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Open browser, enter device code (uses your subscription)",
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Dialog with a single text field for the API key. On Save, fires the
 * ViewModel's `submitApiKey` which persists `export <VAR>=<key>` into
 * `~/.profile` over the pooled SSH client, then re-runs the probe so
 * the badge flips to `[ ready ]`.
 *
 * The toggle button reveals the key (default obscured) so users can
 * sanity-check what they pasted before saving.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyDialog(
    agent: Agent,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    // Per-agent UX hints — what the key looks like + which env var
    // we'll persist it to. Drives the placeholder + the muted footer
    // line so the user knows exactly what's happening.
    val (placeholder, envVar) = when (agent) {
        Agent.CLAUDE -> "sk-ant-..." to "ANTHROPIC_API_KEY"
        Agent.CODEX -> "sk-..." to "OPENAI_API_KEY"
        Agent.GEMINI -> "AIza..." to "GEMINI_API_KEY"
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RectangleShape,
            border = BorderStroke(1.dp, cyan),
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${agent.displayName} API key",
                    color = cyan,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Will be persisted as `export $envVar=…` in ~/.profile on this server.",
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = { Text(placeholder, color = muted) },
                    singleLine = true,
                    visualTransformation = if (visible)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { visible = !visible }) {
                        Text(
                            if (visible) "Hide" else "Show",
                            color = muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Row {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                        Spacer(modifier = Modifier.size(8.dp))
                        OutlinedButton(
                            onClick = { onSubmit(key) },
                            enabled = key.isNotBlank(),
                        ) {
                            Text("[ save ]", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
