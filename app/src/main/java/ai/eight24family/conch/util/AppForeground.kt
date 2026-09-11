package ai.eight24family.conch.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Is any of our UI actually on screen right now?
 *
 * Needed because a foreground SERVICE keeps the process alive indefinitely, so
 * "the process is running" says nothing about whether the user is looking. The
 * speculative prefetcher was tied to a process-wide scope and therefore never
 * stopped: four hours in a taxi with the app merely backgrounded, never opened,
 * and it kept re-listing every 30s and pulling session bodies.
 *
 * ⚠ This must gate SPECULATIVE work only. The tail-poll of an OPEN chat is not
 * speculative — the whole point of the foreground service is that you can send
 * a task, pocket the phone, and have the agent keep working. Never gate that on
 * foreground.
 *
 * Deliberately dependency-free (plain ActivityLifecycleCallbacks, no
 * lifecycle-process artifact) because the androidx versions here are pinned
 * with care — see the comments in libs.versions.toml.
 */
object AppForeground {

    private val startedActivities = AtomicInteger(0)

    /** True between the first onStart and the last onStop of our activities. */
    val isForeground: Boolean get() = startedActivities.get() > 0

    /**
     * Called once each time the LAST activity stops — the moment the user
     * stopped looking.
     *
     * Polling `isForeground` answers "are they looking now"; several things
     * need the EDGE instead, because they want to start a grace period from it
     * (handing a session back to the server once the phone is plainly put away
     * — see `AgentSessionPersistentStream.releaseForHandoff`). Listeners run on
     * the main thread and must not block: arm something and return.
     */
    private val onBackgrounded = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOnBackgrounded(listener: () -> Unit) { onBackgrounded += listener }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                // Never let a stray unbalanced stop push this negative — a
                // negative count would read as "background forever" and silently
                // kill prefetch even while the user is staring at the screen.
                val left = startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
                if (left == 0) {
                    // A listener that throws must not take the others (or the
                    // activity callback) down with it.
                    for (l in onBackgrounded) {
                        ai.eight24family.conch.util.SilentlyTry.fired("Conch-Foreground", "backgrounded listener") { l() }
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
