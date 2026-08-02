package ai.eight24family.conch.util

import ai.eight24family.conch.BuildConfig

/**
 * Compile-time-gated verbose diagnostic logging — the single on/off switch
 * for our chatty `SshAi-*` traces (persistent-stream wire, tail-poll
 * cadence, app-server / ACP RPC, /model menu dumps, model auto-switch, …).
 *
 * - **OFF** in the Play Store / GitHub release artifact
 *   (`BuildConfig.VERBOSE_LOGS == false`): the gated calls — AND the string
 *   concatenation that feeds them — are stripped by R8 (the flag is a
 *   compile-time `const`), so the shipped app is silent and pays zero cost.
 * - **ON** in debug builds, and in dev-iteration release builds invoked
 *   with `-PverboseLogs` (e.g. `assembleRelease -PfastRelease -PverboseLogs`)
 *   when you actually need the traces.
 *
 * NOT a user-facing setting. Genuine production errors/warnings that must
 * always surface should still call `android.util.Log.w/e` directly — Logx is
 * for DEVELOPER diagnostics only.
 *
 * NB: this does NOT affect on-disk storage. logcat lives in the OS ring
 * buffer, never in our files; the app's footprint is the [HistoryCache]
 * session bodies, bounded separately.
 */
object Logx {
    inline fun d(tag: String, msg: () -> String) {
        if (BuildConfig.VERBOSE_LOGS) android.util.Log.d(tag, msg())
    }

    inline fun d(tag: String, t: Throwable?, msg: () -> String) {
        if (BuildConfig.VERBOSE_LOGS) android.util.Log.d(tag, msg(), t)
    }

    inline fun w(tag: String, msg: () -> String) {
        if (BuildConfig.VERBOSE_LOGS) android.util.Log.w(tag, msg())
    }

    inline fun w(tag: String, t: Throwable?, msg: () -> String) {
        if (BuildConfig.VERBOSE_LOGS) android.util.Log.w(tag, msg(), t)
    }
}
