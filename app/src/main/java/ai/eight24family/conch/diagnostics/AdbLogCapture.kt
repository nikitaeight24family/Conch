package ai.eight24family.conch.diagnostics

import ai.eight24family.conch.adb.LocalAdbShell

/**
 * Whole-device `logcat`, read at the shell uid through Conch's own ADB client.
 *
 * The difference from [OwnUidLogCapture] is not cosmetic. Since Android 4.1 a
 * normal app can read ONLY its own log lines, which is enough to debug Conch and
 * useless for the question an agent is usually asked — why did *that* app crash,
 * what did the framework say. At shell level the whole buffer is readable, the
 * same as `adb shell logcat` from a desktop.
 *
 * No helper app is involved: [LocalAdbShell] talks ADB to this device over its
 * own loopback interface with a key the device was paired with.
 */
class AdbLogCapture : LogCaptureService {

    override val tier: LogCaptureService.Tier = LogCaptureService.Tier.Adb

    /**
     * Whether a privileged capture would work right now.
     *
     * Reports the HELD session only — it never opens one. Availability is asked
     * on UI paths that must not block, and a probe that dials out would turn a
     * status check into a network round trip.
     */
    override suspend fun isAvailable(): Boolean = LocalAdbShell.hasLiveSession()

    override suspend fun capture(
        request: LogCaptureService.CaptureRequest,
    ): LogCaptureService.CaptureResult {
        val started = System.currentTimeMillis()
        val result = LocalAdbShell.exec(buildCommand(request))
            ?: return LogCaptureService.CaptureResult(
                text = "",
                lineCount = 0,
                durationMs = System.currentTimeMillis() - started,
                tier = LogCaptureService.Tier.Adb,
            )
        val text = result.stdout.trimEnd('\n')
        return LogCaptureService.CaptureResult(
            text = text,
            lineCount = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1,
            durationMs = System.currentTimeMillis() - started,
            tier = LogCaptureService.Tier.Adb,
        )
    }

    /**
     * One shell command, quoted so a caller's filter cannot become one.
     *
     * The tag filter arrives from a request that ultimately came off the wire,
     * so it is single-quoted like every other value here — the bridge already
     * learned that lesson elsewhere, and a logcat filterspec is a fine place to
     * hide a semicolon.
     */
    private fun buildCommand(req: LogCaptureService.CaptureRequest): String {
        val parts = mutableListOf("logcat", "-d", "-v", "threadtime", "-t", req.maxLines.toString())
        req.sinceTime?.takeIf { it.isNotBlank() }?.let { parts += listOf("-T", quote(it)) }
        // No --uid here: that is the restriction this tier exists to lift.
        if (req.tagFilter.isNullOrBlank()) {
            parts += quote("*:${req.minLevel.flag}")
        } else {
            parts += quote("${req.tagFilter}:${req.minLevel.flag}")
            parts += quote("*:S")
        }
        return parts.joinToString(" ")
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
