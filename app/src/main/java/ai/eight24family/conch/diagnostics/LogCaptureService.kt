package ai.eight24family.conch.diagnostics

import android.content.Context

/**
 * Pulls a chunk of `logcat` from this device into a String.
 *
 * Two implementations live in this package:
 *
 *   - [OwnUidLogCapture] — works on every Android device with no user
 *     interaction. Uses `Runtime.exec("logcat -d ...")` filtered to
 *     Conch's own UID. Useful for debugging the app itself; CAN'T
 *     see other apps' or the framework's logs (Android only lets a
 *     non-system process read its own log lines since JB).
 *
 *   - [AdbLogCapture] — the whole device. Conch speaks ADB to this
 *     phone over its own loopback with a key the device was paired
 *     with, and runs `logcat` at the `shell` UID — system-wide
 *     visibility, the same as `adb shell logcat` from a desktop. No
 *     root, no signature permission, and no helper app.
 *
 * The bridge picks the most capable available impl on each call:
 *   1. If a logcat tier is explicitly requested → that one.
 *   2. Else: the ADB tier when a shell connection is open.
 *   3. Else: fall back to own-UID.
 *
 * `[CaptureRequest]` is an intentionally narrow API — every option
 * maps to a single `logcat` flag. Don't bolt on bespoke filtering;
 * if it's not enough, expose another flag instead.
 */
interface LogCaptureService {

    /** What this implementation can see. */
    val tier: Tier

    /** Whether `capture` would succeed right now (a shell connection is
     *  open, etc.). For [Tier.OwnUid] always true. */
    suspend fun isAvailable(): Boolean

    /** Run a logcat dump and return it as a UTF-8 String. The
     *  buffer's already trimmed to [CaptureRequest.maxLines]. */
    suspend fun capture(request: CaptureRequest): CaptureResult

    enum class Tier {
        /** Only Conch's own log lines. Always available. */
        OwnUid,

        /** Whole-device logs, read at the `shell` UID over our own ADB. */
        Adb,
    }

    data class CaptureRequest(
        /** Glob-ish tag pattern. `null` means no tag filter.
         *  Examples: `"Conch-*"`, `"AndroidRuntime"`. We pass it
         *  through to logcat's `--tag` filter (a list of
         *  `<tag>:<priority>` pairs). */
        val tagFilter: String? = null,
        /** V/D/I/W/E. Lowest priority that gets surfaced. */
        val minLevel: Level = Level.Verbose,
        /** Hard upper bound on returned lines. Any older overflow
         *  is dropped. Caps a runaway dump at a reasonable size. */
        val maxLines: Int = 2_000,
        /** If non-null, only lines whose timestamp is after this
         *  point. `logcat -t` accepts a `mm-dd hh:mm:ss.mmm` format,
         *  so callers should pass a pre-formatted string. */
        val sinceTime: String? = null,
        /** Override the auto-pick of impl. Used by test harnesses
         *  and by `conch-bridge logs --tier adb` etc. */
        val tierOverride: Tier? = null,
    ) {
        enum class Level(val flag: String) {
            Verbose("V"), Debug("D"), Info("I"), Warn("W"), Error("E")
        }
    }

    data class CaptureResult(
        /** UTF-8 logcat output, oldest line first, no trailing newline. */
        val text: String,
        /** Lines successfully captured (may be < maxLines). */
        val lineCount: Int,
        /** Wall-clock duration of the underlying logcat invocation,
         *  for telemetry / display. */
        val durationMs: Long,
        /** Which impl actually did the work. */
        val tier: Tier,
    )
}

/**
 * Default coordinator. Holds singletons for both impls, picks the
 * one to use per call.
 */
class LogCaptureCoordinator(private val context: Context) {

    private val ownUid by lazy { OwnUidLogCapture() }
    private val privileged by lazy { AdbLogCapture() }

    suspend fun capture(
        request: LogCaptureService.CaptureRequest,
    ): LogCaptureService.CaptureResult {
        val pick = pick(request)
        return pick.capture(request)
    }

    /**
     * Pick which impl to use for this request.
     * Visible for testing; the coordinator's normal entry point is
     * [capture].
     */
    suspend fun pick(
        request: LogCaptureService.CaptureRequest,
    ): LogCaptureService {
        request.tierOverride?.let { override ->
            return when (override) {
                LogCaptureService.Tier.OwnUid -> ownUid
                LogCaptureService.Tier.Adb -> privileged
            }
        }
        // Auto-pick: the whole device when we can see it, else our own lines.
        if (privileged.isAvailable()) return privileged
        return ownUid
    }

    /** Quickly check what tiers are available right now without
     *  running an actual capture. Used by the diagnostic UI. */
    suspend fun availableTiers(): Set<LogCaptureService.Tier> {
        val out = mutableSetOf(LogCaptureService.Tier.OwnUid)
        if (privileged.isAvailable()) out += LogCaptureService.Tier.Adb
        return out
    }
}
