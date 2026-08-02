package ai.eight24family.conch.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import ai.eight24family.conch.util.SilentlyTry
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume

/**
 * Runs `logcat` as the `shell` UID via Shizuku. Sees ALL log lines
 * from every app and the framework — same visibility as a developer
 * running `adb shell logcat`, but on-device, no USB cable, no root.
 *
 * Setup the user has to do (one-time):
 *   1. Install **Shizuku** from Play Store (https://shizuku.rikka.app/).
 *   2. Start the Shizuku service inside that app — either via wireless
 *      ADB (Android 11+, no PC) or via a one-time `adb shell sh
 *      /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`.
 *   3. The first time Conch needs Shizuku for logs / dumpsys /
 *      package queries, [requestPermission] kicks off the consent
 *      dialog. After "Allow", further calls are silent.
 *
 * Permission state is per-app per-Shizuku-session. If the user
 * reboots and forgets to restart Shizuku, [isAvailable] returns
 * false and the coordinator falls back to own-UID logs.
 *
 * **Privacy note:** because this returns logs from OTHER apps, Conch
 * MUST surface the consent dialog every time the data leaves the
 * device. The bridge that ships these logs to the user's own SSH
 * server (their server, not ours) is OK; auto-uploading anywhere
 * else would be Spyware-policy territory.
 */
class ShizukuLogCapture(private val context: Context) : LogCaptureService {

    override val tier: LogCaptureService.Tier = LogCaptureService.Tier.Shizuku

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Default) {
        // Three checks, cheap to expensive:
        //   a. Shizuku Manager app installed at all? (PM lookup)
        //   b. The Shizuku service is currently bound to our process?
        //   c. We have permission?
        if (!isShizukuManagerInstalled()) return@withContext false
        if (!Shizuku.pingBinder()) return@withContext false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    private fun isShizukuManagerInstalled(): Boolean = SilentlyTry.loggedOrElse("SshAi-Shizuku", "check shizuku manager installed", false) {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }

    /**
     * Trigger the system permission dialog. Resumes with `true` on
     * grant, `false` on deny. Intended to be called from a UI flow
     * that explains why; don't call before showing user-facing
     * justification.
     */
    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        val requestCode = REQUEST_CODE
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(rc: Int, grantResult: Int) {
                if (rc != requestCode) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (cont.isActive) {
                    cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    override suspend fun capture(
        request: LogCaptureService.CaptureRequest,
    ): LogCaptureService.CaptureResult = withContext(Dispatchers.IO) {
        require(isAvailable()) {
            "Shizuku not available: install the Shizuku app, start its service, and grant Conch permission first"
        }
        // Whitelist tagFilter chars before it ever reaches a shell pipeline; reject anything else.
        val filter = request.tagFilter
        if (!filter.isNullOrBlank() && !TAG_FILTER_WHITELIST.matches(filter)) {
            return@withContext LogCaptureService.CaptureResult(
                text = "tagFilter contains disallowed characters; allowed set: a-z A-Z 0-9 _ * ? [ ] - : . space",
                lineCount = 0,
                durationMs = 0,
                tier = LogCaptureService.Tier.Shizuku,
            )
        }
        val tStart = System.currentTimeMillis()
        val cmd = buildLogcatArgs(request).joinToString(" ")
        // Run as `shell` UID. Output limited to `maxLines` at the
        // logcat side so we don't have to filter post-hoc.
        //
        // Reflection because `Shizuku.newProcess` is marked
        // @RestrictTo(LIBRARY) in 13.x — Kotlin honours that as
        // `private`. The method is still on the public ABI; it just
        // demands callers know what they're doing. We do.
        val proc = newShizukuProcess(arrayOf("sh", "-c", cmd))
        val sb = StringBuilder()
        var count = 0
        // Hard cap on accumulated bytes so a runaway logcat can't OOM the app; see MAX_OUTPUT_BYTES.
        var truncated = false
        var approxBytes = 0
        BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null && count < request.maxLines) {
                val addedBytes = line.length + 1 // approx UTF-8 bytes; +1 for newline
                if (approxBytes + addedBytes > MAX_OUTPUT_BYTES) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append("// truncated at ${MAX_OUTPUT_BYTES / (1024 * 1024)}MB")
                    truncated = true
                    break
                }
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
                approxBytes += addedBytes
                count += 1
                line = reader.readLine()
            }
        }
        SilentlyTry.fired("SshAi-Shizuku", "destroy logcat proc") { proc.destroy() }
        proc.waitFor()

        LogCaptureService.CaptureResult(
            text = sb.toString(),
            lineCount = count,
            durationMs = System.currentTimeMillis() - tStart,
            tier = LogCaptureService.Tier.Shizuku,
        )
    }

    private fun buildLogcatArgs(req: LogCaptureService.CaptureRequest): List<String> {
        val args = mutableListOf("logcat", "-d", "-v", "threadtime")
        args += listOf("-t", req.maxLines.toString())
        if (req.tagFilter.isNullOrBlank()) {
            args += "*:${req.minLevel.flag}"
        } else {
            // With shell UID we can post-filter via grep cleanly.
            args += "*:${req.minLevel.flag}"
            // Caller-friendly: simple glob "Foo-*" → grep regex.
            // tagFilter is whitelist-validated in capture(); single-quote (with '\'' escape) for defense-in-depth.
            val regex = req.tagFilter
                .replace(".", "\\.")
                .replace("*", ".*")
            val singleQuoted = "'" + regex.replace("'", "'\\''") + "'"
            args += listOf("|", "grep", "-E", singleQuoted)
        }
        return args
    }

    /**
     * Reflective accessor to `Shizuku.newProcess(String[], String[], String)`.
     * The method exists (it's how Shizuku Manager apps spawn shell
     * processes) but is `@RestrictTo(LIBRARY)`. Caching the [Method]
     * keeps the per-call overhead at a single virtual dispatch.
     */
    private fun newShizukuProcess(cmd: Array<String>): Process {
        val m = newProcessMethod ?: throw IllegalStateException(
            "Shizuku.newProcess(String[], String[], String) not found — Shizuku-API version drift"
        )
        @Suppress("UNCHECKED_CAST")
        return m.invoke(null, cmd, null, null) as Process
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 1424
        /** Hard cap on captured log bytes to prevent unbounded StringBuilder growth (OOM). */
        private const val MAX_OUTPUT_BYTES = 5 * 1024 * 1024
        /** Allowed characters in tagFilter — shell-safe whitelist (logcat tag chars + glob meta). */
        private val TAG_FILTER_WHITELIST = Regex("^[a-zA-Z0-9_*?\\[\\]\\-:.\\s]+$")

        private val newProcessMethod: java.lang.reflect.Method? = SilentlyTry.logged("SshAi-Shizuku", "reflect Shizuku.newProcess method") {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }
    }
}
