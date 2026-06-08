package ai.eight24family.conch.diagnostics

import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads `logcat` filtered to Conch's own UID via plain
 * `Runtime.exec`. Always available, no permissions needed.
 *
 * Why it works: since Android 4.1 (JB) the system stops handing
 * out other apps' log lines to non-system processes — but you can
 * still read your **own**. `logcat --uid=$(id -u)` filters to the
 * caller's UID; Android logd implicitly does the same when there's
 * no `READ_LOGS` permission, so the explicit flag is belt + braces.
 *
 * Output looks like:
 * ```
 * 05-08 12:00:00.123  1234  1234 D SshAi-Pool: acquire(...) MISS
 * ```
 */
class OwnUidLogCapture : LogCaptureService {

    override val tier: LogCaptureService.Tier = LogCaptureService.Tier.OwnUid

    override suspend fun isAvailable(): Boolean = true

    override suspend fun capture(
        request: LogCaptureService.CaptureRequest,
    ): LogCaptureService.CaptureResult = withContext(Dispatchers.IO) {
        val tStart = System.currentTimeMillis()
        val cmd = buildLogcatCommand(request)
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        // Read up to maxLines, then close stdin so logcat exits.
        val sb = StringBuilder()
        var count = 0
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null && count < request.maxLines) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
                count += 1
                line = reader.readLine()
            }
        }
        // `-d` mode causes logcat to exit on its own when it has
        // dumped the buffer. If we hit `maxLines` first, kill it.
        SilentlyTry.fired("SshAi-OwnLog", "destroy logcat process") { process.destroy() }

        LogCaptureService.CaptureResult(
            text = sb.toString(),
            lineCount = count,
            durationMs = System.currentTimeMillis() - tStart,
            tier = LogCaptureService.Tier.OwnUid,
        )
    }

    private fun buildLogcatCommand(req: LogCaptureService.CaptureRequest): List<String> {
        val args = mutableListOf("logcat", "-d", "-v", "threadtime")
        // Restrict to caller UID. Modern Android logd already
        // enforces this implicitly for non-privileged callers, but
        // passing it explicitly skips the privilege check noise.
        args += listOf("--uid", android.os.Process.myUid().toString())
        // Cap the line count at the logcat side so we don't even
        // spool extra lines into our process.
        args += listOf("-t", req.maxLines.toString())
        // Tag filter: convert "SshAi-*" into a `*:S SshAi-*:V`
        // logcat-filterspec. With no tag filter, default to "*:V"
        // truncated by --uid above.
        if (req.tagFilter.isNullOrBlank()) {
            args += "*:${req.minLevel.flag}"
        } else {
            // logcat doesn't natively glob; we approximate by
            // adding the literal tag and silencing everything else.
            // For a real glob the caller can use ShizukuLogCapture
            // which can grep post-hoc. (Worth revisiting when we
            // surface the bridge to AI agents.)
            args += "*:S"
            args += "${req.tagFilter}:${req.minLevel.flag}"
        }
        return args
    }
}
