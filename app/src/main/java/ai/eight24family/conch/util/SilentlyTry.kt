package ai.eight24family.conch.util

import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Honest-name wrappers around `runCatching` (Durov critique #2).
 *
 * **The problem.** The codebase had ~300 `runCatching { ... }.getOrNull()`
 * sites that silently swallowed exceptions. Mixed three intents under
 * one syntax:
 *
 *   1. **Intentional swallow** — "if this file delete fails, I don't
 *      care and there's no recovery action." Honest case.
 *   2. **Boundary catch** — "this IO call CAN fail; catch and return
 *      Result so the caller decides." Should be typed `try/catch`.
 *   3. **Exception-as-control-flow** — "if this throws, branch to the
 *      slow path." Worst — a NEW bug's NPE gets routed silently to the
 *      slow path forever.
 *
 * **The actual fix.** Even category (1) — intentional swallow — has a
 * real cost in production: a Sentry user reports "app frozen", and the
 * underlying `runCatching` that ate the OOM / IOException / whatever is
 * indistinguishable from working code. So [logged] writes a Sentry
 * breadcrumb on every failure. The breadcrumb attaches to the next
 * error report; we see what was swallowed in the lead-up.
 *
 * **The mechanical sweep.** All `runCatching { ... }.getOrNull()` /
 * `.getOrDefault(x)` sites in the codebase are converted to
 * [SilentlyTry.logged] with file-specific tags. Sites that already
 * had explicit error handlers (`.getOrElse { }`, `.onFailure { log }`,
 * `.fold(...)`) stay as-is — they had meaningful recovery actions.
 *
 * What this DOESN'T fix: typed boundaries that should catch only
 * `IOException` / `SerializationException` rather than `Throwable`.
 * That's category (2) and requires per-site judgement. Tagged in
 * follow-up as «typed-catch sweep». But after this pass, the silent
 * swallows are gone — every uncaught failure leaves a breadcrumb.
 */
object SilentlyTry {

    /**
     * Run [block] and return null on any failure. Same wire as
     * `runCatching { … }.getOrNull()`, but the name advertises the
     * intent: "I genuinely don't care if this fails, even Sentry."
     *
     * Use sparingly. The default for any swallow should be [logged] —
     * use [nullOnError] only when the failure is so frequent and so
     * benign that even a Sentry breadcrumb would be noise (e.g.
     * sampled telemetry, hot polling probes where every miss is fine).
     */
    inline fun <R> nullOnError(block: () -> R): R? =
        try {
            block()
        } catch (_: Throwable) {
            null
        }

    /**
     * Run [block], return null on failure, and leave a Sentry
     * breadcrumb + WARN-level logcat entry on every swallow.
     *
     * The breadcrumb persists in Sentry's session ring buffer and
     * attaches to the next error event — so when a user reports a
     * crash, the breadcrumbs preceding it show what got eaten on the
     * way down. Without this, the chain "thing-X-failed → some
     * downstream side-effect didn't happen → user noticed something
     * weird" is invisible.
     *
     * [tag] / [msg] format: tag is the source-file logcat tag
     * (`SshAi-Chat`, `SshAi-Pool`, …); msg names the operation
     * ("save draft", "decode JSONL").
     */
    inline fun <R> logged(tag: String, msg: String, block: () -> R): R? =
        try {
            block()
        } catch (t: Throwable) {
            recordSwallow(tag, msg, t)
            null
        }

    /**
     * Run [block] returning [default] on failure. Companion to
     * [logged] for the `.getOrDefault(x)` migration pattern.
     */
    inline fun <R> loggedOrElse(tag: String, msg: String, default: R, block: () -> R): R =
        try {
            block()
        } catch (t: Throwable) {
            recordSwallow(tag, msg, t)
            default
        }

    /**
     * Fire-and-forget block — runs for side effects only. Logs +
     * breadcrumbs on failure. Use for "kick this background task and
     * don't wait for the result" patterns where the old code had
     * `runCatching { sideEffect() }` with no consumer.
     */
    inline fun fired(tag: String, msg: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            recordSwallow(tag, msg, t)
        }
    }

    /**
     * Public entry into the breadcrumb + log machinery, for use by
     * existing call sites that have their own custom handler shape
     * but still want the swallow recorded (`.onFailure { recordSwallow(...) }`).
     */
    fun recordSwallow(tag: String, msg: String, t: Throwable) {
        Log.w(tag, "$msg — swallowed: ${t.javaClass.simpleName}: ${t.message}")
        // Sentry's breadcrumb API: safe to call before init (no-op if
        // Sentry isn't initialised, e.g. on debug builds without DSN,
        // or in unit tests). The breadcrumb sits in the session ring
        // buffer until either flushed to an event or evicted at 100
        // crumbs (`SentryOptions.maxBreadcrumbs`, see SshAiApp).
        try {
            Sentry.addBreadcrumb(
                Breadcrumb().apply {
                    category = tag
                    message = "$msg: ${t.javaClass.simpleName}: ${t.message}"
                    level = SentryLevel.WARNING
                    type = "error"
                    setData("exception", t.javaClass.name)
                }
            )
        } catch (_: Throwable) {
            // Sentry's static API can throw on misconfigured builds;
            // don't let observability infrastructure crash the
            // observation site.
        }
    }
}
