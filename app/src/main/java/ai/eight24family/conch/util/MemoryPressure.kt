package ai.eight24family.conch.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry of "drop your rebuildable caches" hooks.
 *
 * Google Play's memory-quality requirement tracks "Memory usage" (anonymous
 * RSS + swap) as an Android-vitals metric, and Android 17's per-app memory
 * limiter escalates zRAM-swap → process kill on apps that keep growing.
 * Framework caches already respond to trim signals on their own; this
 * registry is the same contract for OUR caches. Anything registered here
 * must be purely rebuildable (re-decodable, re-parsable, re-fetchable), so
 * running the action can never lose user state — only cost a repaint.
 *
 * [ai.eight24family.conch.ConchApp] dispatches: any `onTrimMemory` at
 * TRIM_MEMORY_UI_HIDDEN or above, plus legacy `onLowMemory`, fires every
 * registered action.
 */
object MemoryPressure {

    private val actions = ConcurrentHashMap<String, () -> Unit>()

    /** Idempotent by [key] — re-registering replaces the previous action. */
    fun register(key: String, action: () -> Unit) {
        actions[key] = action
    }

    /**
     * Remove a hook. REQUIRED when the action's lambda captures a scoped
     * object (e.g. a ViewModel's coordinator) — a process-lifetime registry
     * would otherwise pin that object reachable forever, which is exactly
     * the kind of leak this mechanism exists to fight.
     */
    fun unregister(key: String) {
        actions.remove(key)
    }

    /** Run every registered action; one failing action never blocks the rest.
     *  Returns how many actions ran cleanly (for logs and tests). */
    fun trimAll(reason: String): Int {
        var ran = 0
        for ((key, action) in actions) {
            SilentlyTry.fired("Conch-Memory", "trim $key ($reason)") {
                action()
                ran++
            }
        }
        return ran
    }
}
