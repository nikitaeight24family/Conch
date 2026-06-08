package ai.eight24family.conch.diagnostics

import android.content.pm.PackageManager
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Runs arbitrary shell commands at the `shell` UID via Shizuku — the
 * same privilege level as `adb shell`, on-device, no cable, no root.
 * This is what gives the server-side agent "ADB access to the phone"
 * through the bridge (AgentBridge "shell" command → here).
 *
 * One-time setup the user does: install + start the Shizuku app, then
 * grant Conch permission via Settings → Phone bridge. [available]
 * reports the live state; [requestPermission] triggers the consent
 * dialog (call from a UI flow, e.g. SettingsSectionBridge).
 *
 * Security model: this is full shell-uid execution driven by request
 * files dropped on the user's OWN authenticated server (700 inbox dir,
 * read over the pooled SSH transport). It's intentional power over the
 * user's own phone+server — gated behind Shizuku's mandatory grant.
 * Output is byte-capped and the call is time-bounded so a runaway
 * command can't hang or OOM the app.
 */
object ShizukuShell {

    data class ShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val truncated: Boolean,
        val timedOut: Boolean,
    )

    private const val REQUEST_CODE = 1425
    private const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
    private const val DEFAULT_TIMEOUT_S = 30L

    /** Shizuku service bound AND Conch granted? `pingBinder()` is false
     *  unless the manager app is installed + running, so no Context /
     *  PackageManager lookup is needed. */
    fun available(): Boolean = SilentlyTry.loggedOrElse("SshAi-ShizukuShell", "check available", false) {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    /** Shizuku service bound (manager installed + running) but maybe not
     *  granted yet — i.e. a permission request would help. */
    fun bound(): Boolean = SilentlyTry.loggedOrElse("SshAi-ShizukuShell", "check bound", false) {
        Shizuku.pingBinder()
    }

    /** Is the Shizuku manager app installed at all (even if not running)?
     *  Distinguishes "install it" from "start its service" in the guide. */
    fun installed(context: android.content.Context): Boolean =
        SilentlyTry.loggedOrElse("SshAi-ShizukuShell", "check shizuku installed", false) {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        }

    /** Trigger the Shizuku consent dialog. Resumes true on grant, false
     *  on deny / error. Call from a UI flow that already explained why. */
    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(rc: Int, grantResult: Int) {
                if (rc != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    /**
     * Run `sh -c <command>` at shell UID. stdout and stderr are read
     * CONCURRENTLY (a stderr-heavy command would otherwise deadlock on a
     * full pipe while we drain stdout), each bounded to [maxBytes], and
     * the whole call is bounded to [timeoutSec] wall-clock. On timeout
     * the process is destroyed. Throws if Shizuku isn't granted.
     */
    suspend fun exec(
        command: String,
        timeoutSec: Long = DEFAULT_TIMEOUT_S,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): ShellResult = withContext(Dispatchers.IO) {
        check(available()) {
            "Shizuku not granted — open Conch → Settings → Phone bridge and enable it (install + start the Shizuku app first)."
        }
        val proc = newProcess(arrayOf("sh", "-c", command))
        try {
            val res = withTimeoutOrNull(timeoutSec * 1000) {
                coroutineScope {
                    val out = async(Dispatchers.IO) { readCapped(proc.inputStream, maxBytes) }
                    val err = async(Dispatchers.IO) { readCapped(proc.errorStream, maxBytes) }
                    val exit = async(Dispatchers.IO) { proc.waitFor() }
                    Triple(out.await(), err.await(), exit.await())
                }
            }
            if (res == null) {
                // Timed out — destroy() closes the pipes so the blocked
                // reads/waitFor unwind; return a clear timeout verdict.
                SilentlyTry.fired("SshAi-ShizukuShell", "destroy timed-out proc") { proc.destroy() }
                ShellResult("", "", exitCode = -1, truncated = false, timedOut = true)
            } else {
                val (out, err, exit) = res
                ShellResult(out.first, err.first, exit, out.second || err.second, timedOut = false)
            }
        } finally {
            SilentlyTry.fired("SshAi-ShizukuShell", "destroy proc") { proc.destroy() }
        }
    }

    /** Read up to [maxBytes] from [stream], draining the rest so the
     *  process isn't left blocked on a full pipe. Returns (text, truncated). */
    private fun readCapped(stream: InputStream, maxBytes: Int): Pair<String, Boolean> {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(32 * 1024)
        var total = 0
        var truncated = false
        stream.use { s ->
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                val take = minOf(n, maxBytes - total)
                if (take > 0) { buf.write(chunk, 0, take); total += take }
                if (total >= maxBytes) {
                    truncated = true
                    while (s.read(chunk) >= 0) { /* discard the overflow */ }
                    break
                }
            }
        }
        return String(buf.toByteArray(), Charsets.UTF_8) to truncated
    }

    private val newProcessMethod: java.lang.reflect.Method? =
        SilentlyTry.logged("SshAi-ShizukuShell", "reflect Shizuku.newProcess") {
            // @RestrictTo(LIBRARY) in shizuku-api 13.x → reflect it (same
            // trick ShizukuLogCapture uses for logcat).
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }

    private fun newProcess(cmd: Array<String>): Process {
        val m = newProcessMethod ?: error("Shizuku.newProcess not found — shizuku-api version drift")
        return m.invoke(null, cmd, null, null) as Process
    }
}
