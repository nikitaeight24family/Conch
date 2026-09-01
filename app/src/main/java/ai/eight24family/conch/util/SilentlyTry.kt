package ai.eight24family.conch.util

import android.util.Log

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
 * real cost: when the app misbehaves, the `runCatching` that ate the
 * OOM / IOException / whatever is indistinguishable from working code.
 * So [logged] writes a WARN logcat line naming the operation and the
 * exception on every failure — visible in `adb logcat` and in a bug
 * report the user sends, which is now the only place failures surface.
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
 * swallows are gone — every uncaught failure leaves a log line.
 */
object SilentlyTry {

    /**
     * Run [block] and return null on any failure. Same wire as
     * `runCatching { … }.getOrNull()`, but the name advertises the
     * intent: "I genuinely don't care if this fails."
     *
     * Use sparingly. The default for any swallow should be [logged] —
     * use [nullOnError] only when the failure is so frequent and so
     * benign that even a log line would be noise (e.g. hot polling
     * probes where every miss is fine).
     */
    inline fun <R> nullOnError(block: () -> R): R? =
        try {
            block()
        } catch (_: Throwable) {
            null
        }

    /**
     * Run [block], return null on failure, and leave a WARN-level
     * logcat entry on every swallow.
     *
     * Without this, the chain "thing-X-failed → some downstream
     * side-effect didn't happen → user noticed something weird" is
     * invisible: the log line is what makes an eaten failure findable
     * afterwards.
     *
     * [tag] / [msg] format: tag is the source-file logcat tag
     * (`Conch-Chat`, `Conch-Pool`, …); msg names the operation
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
     * Fire-and-forget block — runs for side effects only. Logs on
     * failure. Use for "kick this background task and
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
     * Public entry into the log machinery, for use by existing call
     * sites that have their own custom handler shape but still want
     * the swallow recorded (`.onFailure { recordSwallow(...) }`).
     */
    fun recordSwallow(tag: String, msg: String, t: Throwable) {
        Log.w(tag, "$msg — swallowed: ${t.javaClass.simpleName}: ${t.message}")
    }
}
