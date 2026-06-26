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
        // Construct the haptics manager once; observe the pref to
        // flip its `enabled` flag at runtime without rebuilding.
        val haptics = ai.eight24family.conch.ui.haptic.SshAiHaptics(
            applicationContext,
            enabled = SilentlyTry.loggedOrElse("SshAi-MainActivity", "read haptics pref", true) {
                kotlinx.coroutines.runBlocking {
                    ServiceLocator.preferences.hapticsEnabled.first()
                }
            },
        )
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                ServiceLocator.preferences.hapticsEnabled.collect {
                    haptics.enabled = it
                }
            }
        }
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
        val activeCount = SilentlyTry.loggedOrElse("SshAi-MainActivity", "read activeCount for PiP", 0) {
            ServiceLocator.agentSessions.activeCount.value
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
        if (activeCount <= 0 && !loginInProgress) {
            android.util.Log.d(tag, "skipped: no active sessions (count=$activeCount) and no login")
            return
        }

        // **16:9** — chat content reads better horizontally than at
        // 4:3 (wider lines fit more tokens per row); the new
        // PipChatScreen is laid out for landscape too. System
        // clamps to its allowed range (typically 0.42–2.39) so a
        // user-resized PiP window still renders fine; this is just
        // our preferred starting shape.
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .also { builder ->
                // Add a "Stop turn" action — only meaningful if the
                // session is currently generating. We always include
                // it; the receiver no-ops if state isn't Running.
                // System will surface it as one of up to 3 icons in
                // the PiP window header bar.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    SilentlyTry.fired("SshAi-MainActivity", "set PiP stop action") {
                        builder.setActions(listOf(buildStopAction()))
                    }
                }
            }
            .build()
        val ok = runCatching { enterPictureInPictureMode(params) }
        android.util.Log.d(tag, "enterPictureInPictureMode -> ${ok.getOrNull()} (err=${ok.exceptionOrNull()?.message})")
    }

    /** PiP action that cancels the currently-streaming agent turn.
     *  Routes through [PipActionReceiver] which knows how to reach
     *  AgentSessionManager and call cancelCurrent() on the right
     *  session. Icon is the system stop glyph. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildStopAction(): android.app.RemoteAction {
        val stopIntent = android.content.Intent(this, PipActionReceiver::class.java)
            .setAction(PipActionReceiver.ACTION_STOP_TURN)
        val pending = android.app.PendingIntent.getBroadcast(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = android.graphics.drawable.Icon.createWithResource(
            this, android.R.drawable.ic_media_pause,
        )
        return android.app.RemoteAction(icon, "Stop", "Stop the current turn", pending)
    }
}

/**
 * Broadcast receiver for actions fired from the PiP window header.
 * Currently only [ACTION_STOP_TURN] — calls cancelCurrent() on the
 * most-recently-active session. No-op if no session is generating.
 */
class PipActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
        when (intent.action) {
            ACTION_STOP_TURN -> {
                SilentlyTry.fired("SshAi-MainActivity", "PiP stop turn") {
                    ServiceLocator.agentSessions.findMostRecentlyActive()?.cancelCurrent()
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP_TURN = "ai.eight24family.conch.pip.STOP_TURN"
    }
}

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
            // SAME conversation + reading position they minimized — not a
            // recency-guessed session whose history may be a different chat.
            val fgChat = ai.eight24family.conch.ui.window.PipForegroundChat
                .current.collectAsState().value
            Surface(modifier = Modifier.fillMaxSize()) {
                if (login != null) {
                    ai.eight24family.conch.ui.screens.PipLoginPanel(login)
                } else if (fgChat != null) {
                    val msgs = fgChat.messages.collectAsState().value
                    val st = fgChat.state.collectAsState().value
                    val anchor = fgChat.readingAnchorMsgId.collectAsState().value
                    android.util.Log.d(
                        "SshAi-PiP",
                        "overlay=FOREGROUND msgs=${msgs.size} anchor=$anchor working=${st is ai.eight24family.conch.agent.SessionState.Working}",
                    )
                    ai.eight24family.conch.ui.window.ChatPipView(
                        messages = msgs,
                        isWorking = st is ai.eight24family.conch.agent.SessionState.Working,
                        anchorMsgId = anchor,
                    )
                } else {
                    // No chat on screen (e.g. PiP entered from the Settings tab
                    // while a background turn runs) → fall back to the
                    // most-recently-active session's compact reply view.
                    val session = androidx.compose.runtime.remember {
                        SilentlyTry.logged("SshAi-MainActivity", "find most recently active for PiP") {
                            ServiceLocator.agentSessions.findMostRecentlyActive()
                        }
                    }
                    android.util.Log.d("SshAi-PiP", "overlay=FALLBACK session=${session != null}")
                    if (session != null) {
                        ai.eight24family.conch.ui.screens.PipChatScreen(session)
                    } else {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            androidx.compose.material3.Text(
                                "no active chat",
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
