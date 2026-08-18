package ai.eight24family.conch

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.service.ConnectionPermissions
import ai.eight24family.conch.util.SilentlyTry
import ai.eight24family.conch.ui.screens.ConnectionGuardSheet
import ai.eight24family.conch.ui.theme.SshAiTheme
import ai.eight24family.conch.ui.window.AppScaffold
import ai.eight24family.conch.ui.window.AppWindowAdaptiveProvider

class MainActivity : ComponentActivity() {
    /**
     * Mirrors Android's `isInPictureInPictureMode` so Compose can
     * branch its top-level render between the regular AppScaffold
     * and the slim [ai.eight24family.conch.ui.screens.PipChatScreen].
     * Updated from [onPictureInPictureModeChanged].
     */
    private val isInPipState = androidx.compose.runtime.mutableStateOf(false)

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipState.value = isInPictureInPictureMode
        android.util.Log.d("SshAi-PiP", "mode changed → inPip=$isInPictureInPictureMode")
    }

    override fun onStart() {
        super.onStart()
        // App in the foreground → silently connect EVERY server we can reach
        // without a tap: password/plain-key servers via their stored secret, and
        // seamless FIDO servers via their device key. Covers a WARM open / return
        // from background that SshAiApp.onCreate (cold start only) misses. The
        // user never navigates to "connect" when access exists. Idempotent —
        // skips already-connected.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ai.eight24family.conch.util.SilentlyTry.fired("SshAi-Reconnect", "silent auto-connect on foreground") {
                ServiceLocator.sshConnectionPool.connectAllPossibleSilently()
            }
        }
        // Re-arm the foreground service if Android 15's dataSync background
        // budget (Service.onTimeout, ~6h cumulative) stopped it while work
        // is still alive. Foregrounding the app RESETS the budget, so this
        // start always succeeds here; idempotent when already running.
        ai.eight24family.conch.util.SilentlyTry.fired("SshAi-Service", "re-arm foreground service on app start") {
            val active = ServiceLocator.agentSessions.activeCount.value
            val held = ServiceLocator.sshConnectionPool.userHeldIds.value
            if (active > 0 || held.isNotEmpty()) {
                ai.eight24family.conch.service.SshAiService.start(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() MUST come before super.onCreate() — the
        // backport library hooks the system splash transition on API 31+
        // and renders its own splash on API 26-30. Calling it after
        // super.onCreate would skip the animation on older devices.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Apply high-refresh-rate request based on user preference.
        // Default is on (smoother UX wins for most); a toggle in
        // Settings → Appearance flips it off for battery-conscious
        // users on phones that drain at 120 Hz.
        //
        // We sample synchronously via runBlocking from the SharedPref-
        // backed flow; the read is microseconds and we need the value
        // BEFORE setContent so the window's preferred mode is set
        // before Compose attaches.
        val highRefreshOn = SilentlyTry.loggedOrElse("SshAi-MainActivity", "read highRefreshRate pref", true) {
            kotlinx.coroutines.runBlocking {
                ServiceLocator.preferences.highRefreshRateEnabled.first()
            }
        }
        if (highRefreshOn) requestHighRefreshRate() else cap60Hz()
        // Observe runtime changes so a Settings toggle takes effect
        // without requiring the user to manually restart. Activity
        // `recreate()` rebuilds the window with the new attributes.
        var lastSeen = highRefreshOn
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                ServiceLocator.preferences.highRefreshRateEnabled.collect { v ->
                    if (v != lastSeen) {
                        lastSeen = v
                        recreate()
                    }
                }
            }
        }
        // ONE app-scoped haptics player, owned by ServiceLocator — the UI just
        // borrows it through the CompositionLocal below. It must not be built
        // here: turn-level haptics are fired by ChatViewModel, which outlives
        // this Activity's composition (and keeps buzzing in PiP, where
        // ChatScreen stops composing entirely). ServiceLocator also owns the
        // pref collector, so the Settings toggle still lands instantly without
        // this Activity being alive to relay it.
        val haptics = ServiceLocator.haptics
        setContent {
            // App-wide scale: override LocalDensity so every dp/sp
            // gets multiplied uniformly. User configures via Settings
            // → Appearance → App scale (slider, 0.75–1.5).
            //
            // IMPORTANT: scale ONLY [density]. Don't touch [fontScale].
            // Compose's sp-to-px is `sp * fontScale * density`, so
            // multiplying both compounds: at scale=1.5 dp grows 1.5×
            // but sp grows 1.5×1.5=2.25×, and text starts overflowing
            // its dp container. Just scaling density makes sp and dp
            // grow in lockstep — uniform whole-app zoom, which is the
            // spec.
            //
            // We resolve the scale value SYNCHRONOUSLY before first
            // composition (runBlocking on the DataStore Flow) so the
            // window doesn't render once at 1.0×, get measured, then
            // re-layout at the real scale — the user sees that as
            // "app opens at half/wrong size then suddenly jumps". A
            // few ms blocking on disk is invisible; the layout jank
            // isn't.
            val initialScale = remember {
                SilentlyTry.loggedOrElse("SshAi-MainActivity", "read appScale pref", 1.0f) {
                    kotlinx.coroutines.runBlocking {
                        ServiceLocator.preferences.appScale
                            .first()
                    }
                }
            }
            val scale by ServiceLocator.preferences.appScale
                .collectAsState(initial = initialScale)
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            val scaled = androidx.compose.ui.unit.Density(
                density = baseDensity.density * scale,
                fontScale = baseDensity.fontScale,
            )
            // App-wide override of LocalUriHandler so any URL clicked
            // through a Compose `Text` LinkAnnotation opens via Chrome
            // Custom Tabs (in-app browser surface) instead of kicking
            // the user out to a separate browser app. Affects chat
            // links, settings/about page links, etc.
            val customTabHandler = androidx.compose.runtime.remember {
                ai.eight24family.conch.ui.CustomTabUriHandler(this@MainActivity)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides scaled,
                ai.eight24family.conch.ui.haptic.LocalSshAiHaptics provides haptics,
                androidx.compose.ui.platform.LocalUriHandler provides customTabHandler,
            ) {
                Root(isInPip = isInPipState.value)
            }
        }
    }

    /**
     * Ask the platform for the highest refresh rate the panel supports.
     *
     * Android defaults to ~60 Hz for most apps even on 120 Hz panels —
     * the OS is conservative about battery, so it expects the app to
     * declare it wants the high refresh rate. Without this, our chat
     * scroll on a Samsung S24+ / Pixel 8 Pro / OnePlus etc.
     *
     * Two-tier approach:
     *  1. **`preferredDisplayModeId`** for Android 11+ (R) — pick the
     *     supported display mode with the highest refresh rate
     *     **at the current resolution** (so we don't downgrade
     *     resolution on phones that ship 1440p/60 + 1080p/120 modes).
     *  2. **`preferredRefreshRate`** as a fallback hint — older Android
     *     versions and OEMs that ignore mode IDs but honour the
     *     refresh-rate field.
     *
     * The OS treats both as a request, not a guarantee — battery saver
     * / system load can still cap us. Adaptive refresh rate (Modifier
     * .requestedFrameRate, Android 15+) is the next step if we want
     * finer control but isn't needed for the basic "make scroll
     * smooth" win.
     */
    /**
     * Force the window down to 60 Hz when the user opts out of high
     * refresh. Mirror of [requestHighRefreshRate] but picks the
     * LOWEST mode ≥ 60 Hz at the current resolution. Used when the
     * system would otherwise be giving us 120 Hz by default (Samsung
     * "Motion smoothness = Adaptive" etc.) and the user explicitly
     * wants to save battery / dial the panel down.
     */
    private fun cap60Hz() {
        SilentlyTry.fired("SshAi-MainActivity", "cap to 60Hz") {
            // ContextCompat path instead of the deprecated
            // windowManager.defaultDisplay — Play's SDK-35 edge-to-edge
            // check flags deprecated display-API references in the dex
            // even behind version gates.
            val display = androidx.core.content.ContextCompat.getDisplayOrDefault(this)
            val currentMode = display.mode
            // Look for a 60 Hz mode at the same resolution.
            val sixtyMode = display.supportedModes.firstOrNull {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight &&
                    it.refreshRate in 59.5f..60.5f
            } ?: return@fired
            val attrs = window.attributes
            attrs.preferredDisplayModeId = sixtyMode.modeId
            attrs.preferredRefreshRate = 60f
            window.attributes = attrs
            android.util.Log.d(
                "SshAi-Display",
                "capped to 60 Hz (was ${currentMode.refreshRate})",
            )
        }
    }

    private fun requestHighRefreshRate() {
        SilentlyTry.fired("SshAi-MainActivity", "request high refresh") {
            // Same ContextCompat swap as cap60Hz — no deprecated
            // defaultDisplay reference in our dex.
            val display = androidx.core.content.ContextCompat.getDisplayOrDefault(this)
            val currentMode = display.mode
            // Filter to modes that keep our current resolution — never
            // trade pixels for Hz.
            val candidates = display.supportedModes.filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            val best = candidates.maxByOrNull { it.refreshRate } ?: return@fired
            if (best.refreshRate <= currentMode.refreshRate + 0.5f) return@fired
            val attrs = window.attributes
            attrs.preferredDisplayModeId = best.modeId
            attrs.preferredRefreshRate = best.refreshRate
            window.attributes = attrs
            android.util.Log.d(
                "SshAi-Display",
                "requested ${best.refreshRate} Hz (was ${currentMode.refreshRate}) at " +
                    "${best.physicalWidth}x${best.physicalHeight}",
            )
        }
    }

    /**
     * Phase 9 of the foldable workstream — Picture-in-Picture on
     * background.
     *
     * `onUserLeaveHint` fires the instant the user starts swiping home /
     * taps the recents button — BEFORE `onPause`. That's the canonical
     * place to opt into PiP because once `onStop` runs the window is
     * already torn down and `enterPictureInPictureMode` returns false.
     *
     * Gating:
     *  - Only on API 26+ (PiP shipped in 24 but the
     *    `enterPictureInPictureMode(params)` overload is from 26 and we
     *    want the aspect-ratio param so the floating window doesn't
     *    open square-and-clipped).
     *  - Only if the device declares the feature
     *    (`FEATURE_PICTURE_IN_PICTURE`) — DeX, most phones, tablets do;
     *    a handful of OEMs strip it.
     *  - Only if there's at least one active AgentSession — i.e. user
     *    has an open chat worth watching float. Without this gate every
     *    home-press from Settings or the keychain screen would launch
     *    a useless tiny window.
     *
     * The whole thing is `runCatching`-wrapped because some Samsung
     * builds throw `IllegalStateException` on enter() if multi-window
     * is disabled in Developer Options — we should never crash here,
     * just degrade to the standard "app sent to background" path.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val tag = "SshAi-PiP"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            android.util.Log.d(tag, "skipped: api<26")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            android.util.Log.d(tag, "skipped: device has no FEATURE_PICTURE_IN_PICTURE")
            return
        }
        // No live SSH sessions → nothing worth floating, just background normally.
        if (!ServiceLocator.isInitialized) {
            android.util.Log.d(tag, "skipped: ServiceLocator not initialised")
            return
        }
        // ⚠ A TURN IN FLIGHT, not "a session exists".
        //
        // This read used to be `agentSessions.activeCount` — the number of
        // AgentSession OBJECTS in the manager, which is ≥1 from the first chat
        // you open until you close the app. So every swipe home, from any
        // screen, popped a floating window, and since nothing was running it had
        // nothing to say: it showed whatever chat the recency guess landed on,
        // scrolled to an old reading anchor. PiP exists for one reason —
        // watching work you can't watch on screen — so it now opens only when
        // there IS work.
        val turnInFlight = SilentlyTry.loggedOrElse("SshAi-MainActivity", "read turn-in-flight for PiP", false) {
            // The WIDER test: a turn generating, a session still bootstrapping, or
            // a prompt drainer inside a turn. PiP keeps this process resumed, so
            // an in-flight handshake / touch / upload needs it as much as a
            // streaming reply does — gating on `Working` alone dropped all of them
            // plus the gap between send and the state flipping.
            ServiceLocator.agentSessions.anyWorkWorthFloating()
        }
        // A MIRRORED turn (driven from the console / another device) never flips
        // our own SessionState.Working, but it is exactly as much "work in
        // progress" as ours — the file-mirror flag is its Working.
        val mirroredTurn = SilentlyTry.loggedOrElse("SshAi-MainActivity", "read mirrored turn for PiP", false) {
            ai.eight24family.conch.ui.window.PipForegroundChat.current.value
                ?.remoteFileOpen?.value == true
        }
        // Phase 9b: ride a "working" session even if activeCount reads 0.
        // activeCount only counts sessions whose state is Running/Working,
        // but a Bootstrapping session that hasn't quite flipped state yet
        // is still something the user wants to watch float. Also tolerate
        // the brief moment between send() and the state transition.
        // An in-flight OAuth login is ALSO worth floating — in fact it's the
        // case that NEEDS PiP most: OAuth forces the user out to the browser,
        // and PiP keeps this activity resumed so the SSH login process (blocked
        // on stdin waiting for the pasted code) stays alive instead of dying on
        // a frozen background. The PiP branch renders a login panel for it.
        val loginInProgress = SilentlyTry.loggedOrElse("SshAi-MainActivity", "read login presence for PiP", false) {
            ai.eight24family.conch.ui.viewmodel.AgentPickerViewModel.activeLogin.value != null
        }
        if (!turnInFlight && !mirroredTurn && !loginInProgress) {
            android.util.Log.d(tag, "skipped: nothing running (no turn in flight, no mirrored turn, no login)")
            return
        }

        // **16:9** — chat content reads better horizontally than at
        // 4:3 (wider lines fit more tokens per row); the new
        // PipChatScreen is laid out for landscape too. System
        // clamps to its allowed range (typically 0.42–2.39) so a
        // user-resized PiP window still renders fine; this is just
        // our preferred starting shape.
        // ⛔ NO ACTIONS IN THE PiP HEADER. DO NOT PUT THE STOP BUTTON BACK.
        //
        // There used to be a "Stop turn" RemoteAction here. A PiP header action
        // is a ~24dp icon inside a ~200dp window, sitting under the same tap the
        // user makes to expand the window — and a RemoteAction fires
        // IMMEDIATELY, with no possible confirmation. So the cheapest possible
        // misfire destroyed the most expensive thing in the app: a running turn,
        // with its context, its tokens and its minutes.
        //
        // The asymmetry decides it: stopping from PiP saves one tap, and an
        // accidental stop costs a whole turn. Tap the window → the app expands →
        // Stop is right there in the prompt bar, where a deliberate press is
        // what it takes. A destructive action never belongs on the gesture the
        // user makes by reflex.
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        val ok = runCatching { enterPictureInPictureMode(params) }
        android.util.Log.d(tag, "enterPictureInPictureMode -> ${ok.getOrNull()} (err=${ok.exceptionOrNull()?.message})")
    }

}

// PipActionReceiver is GONE along with the PiP header's Stop action — see the
// PictureInPictureParams block above for why a destructive control must not sit
// on the tap the user makes to expand the window. Nothing mints that
// PendingIntent any more, so the receiver (and its manifest entry) would just be
// an exported-by-accident way to cancel someone's turn.

@Composable
private fun Root(isInPip: Boolean) {
    SshAiTheme {
        // **PiP branch.** When the activity is in Picture-in-Picture we render
        // a dedicated compact layout (server name, last assistant message
        // streaming, status footer) instead of the full app. The regular
        // AppScaffold renders unusably small in a 160dp window — that's the
        // whole reason this branch exists. The main app is ALWAYS composed —
        // even in PiP. Early-returning a separate PiP tree (as before) tore
        // down the whole NavHost, which cleared its ViewModelStores → cancelled
        // viewModelScope → killed any in-flight OAuth login coroutine. Keeping
        // the NavHost composed and drawing the PiP UI as an OVERLAY on top
        // means the login coroutine (and every other VM) survives the PiP
        // round-trip.
        //
        // [AppWindowAdaptiveProvider] wires foldable / tablet / DeX / PiP size
        // signals into a CompositionLocal. See ui/window/WindowAdaptive.kt.
        AppWindowAdaptiveProvider {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppScaffold()
                ConnectionGuardAutoPrompt()
            }
        }
        // PiP overlay — compact layout drawn over the (still-composed) app so
        // the small floating window shows something useful without disposing
        // the app underneath. Login takes priority (the OAuth case, where
        // there's usually no chat); else the active chat; else a hint.
        if (isInPip) {
            val login = ai.eight24family.conch.ui.viewmodel.AgentPickerViewModel
                .activeLogin.collectAsState().value
            // Prefer the chat the user actually has on screen (published by
            // ChatScreen via PipForegroundChat) so the floating window is the
            // SAME conversation they minimized — not a recency-guessed session
            // whose history is a different chat. (Its reading position is NOT
            // carried over: this window reports progress, and reopening at an old
            // anchor is what made it show stale replies.)
            val fgChat = ai.eight24family.conch.ui.window.PipForegroundChat
                .current.collectAsState().value
            Surface(modifier = Modifier.fillMaxSize()) {
                if (login != null) {
                    ai.eight24family.conch.ui.screens.PipLoginPanel(login)
                } else if (fgChat != null) {
                    android.util.Log.d("SshAi-PiP", "overlay=FOREGROUND")
                    // The whole VM, not a snapshot: the window shows live status
                    // (verb, elapsed, tokens, agents, queue), which is a dozen
                    // flows, and the reading anchor is deliberately NOT among
                    // them — see ChatPipView.
                    ai.eight24family.conch.ui.window.ChatPipView(fgChat)
                } else {
                    // No chat on screen (PiP entered from another tab while a
                    // background turn runs). Show the session that is ACTUALLY
                    // WORKING, re-picked live.
                    //
                    // ⚠ It used to be `remember { findMostRecentlyActive() }`:
                    // `Running` counts as "active" (it only means the session is
                    // up), and `remember` froze the very first guess for the
                    // lifetime of the composition. Re-polled because a session's
                    // state is not a Compose-observable flow; 2 Hz over an
                    // in-memory map costs nothing and this surface exists for
                    // seconds at a time.
                    var session by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            SilentlyTry.logged("SshAi-MainActivity", "find working session for PiP") {
                                ServiceLocator.agentSessions.findWorkingSession()
                            }
                        )
                    }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(500)
                            session = SilentlyTry.logged("SshAi-MainActivity", "repoll working session for PiP") {
                                ServiceLocator.agentSessions.findWorkingSession()
                            }
                        }
                    }
                    android.util.Log.d("SshAi-PiP", "overlay=FALLBACK working=${session != null}")
                    session?.let { ai.eight24family.conch.ui.screens.PipChatScreen(it) }
                    if (session == null) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            androidx.compose.material3.Text(
                                "nothing running",
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Auto-prompt the [ConnectionGuardSheet] on first launch after install,
 * or after the user has reset the "shown" flag from Settings. Skipped
 * entirely if nothing is pending — no point showing a sheet that says
 * "everything's fine".
 */
@Composable
private fun ConnectionGuardAutoPrompt() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = ServiceLocator.preferences
    val alreadyShown by prefs.permissionGuardShown.collectAsState(initial = true)
    val oemAcked by prefs.oemAutoStartAcknowledged.collectAsState(initial = false)
    var visible by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(alreadyShown, oemAcked) {
        if (alreadyShown) return@LaunchedEffect
        if (ConnectionPermissions.anythingPending(ctx, oemAcked)) {
            visible = true
        } else {
            // Nothing to ask — silently mark shown so we never auto-pop.
            prefs.setPermissionGuardShown(true)
        }
    }
    if (visible) {
        ConnectionGuardSheet(onDismiss = {
            visible = false
            scope.launch { prefs.setPermissionGuardShown(true) }
        })
    }
}
