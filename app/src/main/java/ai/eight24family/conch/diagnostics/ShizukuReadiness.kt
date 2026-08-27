package ai.eight24family.conch.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * How ready Shizuku is to execute anything on THIS phone, as one value.
 *
 * The four states are the four different things the user has to DO, in the
 * order they degrade:
 *
 *  - [Ready]        — bound and granted; the bridge works.
 *  - [NotGranted]   — service is up, Conch's grant is gone (revoked, or it did
 *                     not survive a Shizuku restart). One tap fixes it.
 *  - [NotRunning]   — the app is installed but its service is stopped. This is
 *                     the common one: the service dies on every reboot, and
 *                     aggressive OEM skins (ColorOS, MIUI) kill it in the
 *                     background — exactly the state that used to look like
 *                     "phone connected" to the agent while every command failed.
 *  - [NotInstalled] — nothing to talk to.
 *
 * This used to be a private enum + private `detect()` inside
 * SettingsSectionBridge, so the only place in the app that knew the difference
 * between "not granted" and "service dead" was a settings screen the user isn't
 * looking at. The chat send path needs the same verdict, hence one shared home.
 */
enum class ShizukuStage {
    NotInstalled,
    NotRunning,
    NotGranted,
    Ready,
    ;

    val isReady: Boolean get() = this == Ready

    /** Dialog title — names the state, not the remedy. */
    val blockTitle: String
        get() = when (this) {
            Ready -> ""
            NotGranted -> "Shizuku access revoked"
            NotRunning -> "Shizuku isn't running"
            NotInstalled -> "Shizuku isn't installed"
        }

    /**
     * What is broken and what it means for this chat. Plain and short, and it
     * never claims the phone is fine — the whole point of the gate is that the
     * agent would otherwise be told the phone is connected and then burn a
     * whole turn discovering it isn't.
     */
    val blockBody: String
        get() = when (this) {
            Ready -> ""
            NotGranted ->
                "Shizuku is running, but Conch is no longer allowed to use it. " +
                    "Nothing can run on this phone until the grant is back."
            NotRunning ->
                "Shizuku is installed but its service is stopped, so no command can reach " +
                    "this phone. It has to be started again after every reboot, and some " +
                    "Android skins (ColorOS, MIUI) kill it in the background — if that keeps " +
                    "happening, turn off battery and system optimization for Shizuku."
            NotInstalled ->
                "This chat is wired to run commands on this phone, but Shizuku isn't " +
                    "installed, so there is nothing to run them."
        }

    /** Label for the primary action button. */
    val blockAction: String
        get() = when (this) {
            Ready -> ""
            NotGranted -> "Grant access"
            NotRunning -> "Open Shizuku"
            NotInstalled -> "Install Shizuku"
        }
}

/**
 * Live Shizuku readiness.
 *
 * Cheap enough to call on every send: [ShizukuShell.bound] is a local binder
 * ping and the installed check is a PackageManager lookup. Called at send time
 * on purpose — a cached value can be a second stale, and a second stale here
 * means a message goes to the model claiming a phone that isn't there.
 */
fun shizukuStage(context: Context): ShizukuStage = when {
    ShizukuShell.available() -> ShizukuStage.Ready
    ShizukuShell.bound() -> ShizukuStage.NotGranted
    ShizukuShell.installed(context) -> ShizukuStage.NotRunning
    else -> ShizukuStage.NotInstalled
}

/**
 * Push-based readiness for anything that wants to REACT to Shizuku dying
 * rather than ask.
 *
 * Shizuku's own API carries the signal — `addBinderDeadListener` /
 * `addBinderReceivedListener` (per the Shizuku-API developer guide) — so a
 * service killed by the OEM skin lands here immediately instead of up to one
 * poll interval later. The synchronous [shizukuStage] stays the authority at
 * send time; this flow exists so UI (glyphs, banners) stops lying promptly.
 *
 * [start] is idempotent and safe to call from Application.onCreate.
 */
object ShizukuWatch {

    private val _stage = MutableStateFlow(ShizukuStage.NotInstalled)
    val stage: StateFlow<ShizukuStage> = _stage.asStateFlow()

    @Volatile
    private var started = false
    private var appContext: Context? = null

    private val onAlive = Shizuku.OnBinderReceivedListener { refresh() }
    private val onDead = Shizuku.OnBinderDeadListener { refresh() }
    private val onPermission =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        SilentlyTry.fired("SshAi-Shizuku", "attach binder listeners") {
            Shizuku.addBinderReceivedListenerSticky(onAlive)
            Shizuku.addBinderDeadListener(onDead)
            Shizuku.addRequestPermissionResultListener(onPermission)
        }
        refresh()
    }

    /** Re-read the live state. Called by the listeners and by any UI that just
     *  came back from Shizuku (returning from the app fires no binder event when
     *  the user changed nothing). */
    fun refresh() {
        val ctx = appContext ?: return
        _stage.value = shizukuStage(ctx)
    }
}

/**
 * Bring up the Shizuku app, falling back to its store page when it isn't
 * installed. NEW_TASK on both branches — this is also reached from a dialog
 * whose context isn't guaranteed to be an activity.
 */
fun openShizukuApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (launch != null) {
        SilentlyTry.fired("SshAi-Shizuku", "open shizuku app") {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return
    }
    SilentlyTry.fired("SshAi-Shizuku", "open shizuku store page") {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
