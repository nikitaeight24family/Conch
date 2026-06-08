package ai.eight24family.conch.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Lightweight perf-overhead monitor.
 *
 * Every [SAMPLE_INTERVAL_MS] reads:
 *   - **Battery temperature** via the sticky `ACTION_BATTERY_CHANGED`
 *     broadcast. Battery is right next to the SoC on most phones, so
 *     it tracks heating well enough as a proxy. Decitenths of °C, we
 *     normalise to whole degrees.
 *   - **CPU thermal zones** at `/sys/class/thermal/thermal_zoneN/temp`
 *     — most OEMs leave the cpu / tsens / soc zones world-readable.
 *     We pick the hottest one and report it as "cpu". Falls back to
 *     "n/a" silently if the kernel restricts read.
 *   - **System CPU usage** from `/proc/stat` (delta of busy vs total
 *     jiffies between samples). Whole-device, all cores combined.
 *   - **App CPU usage** from `/proc/self/stat` (utime + stime delta vs
 *     system delta). 100% = one full core busy on us; >100% means we
 *     are multi-threaded actively burning cores.
 *
 * Logs to `SshAi-Perf` at INFO level so it's easy to grep:
 *   ```
 *   adb logcat -d --pid $(adb shell pidof ai.eight24family.conch) -s SshAi-Perf
 *   ```
 *
 * Also exposes [snapshot] as a [StateFlow] so a UI overlay can read
 * the current values without an extra subscription path.
 *
 * No-op if [start] hasn't been called; [stop] cancels the loop and
 * clears the snapshot. Safe to call start/stop multiple times.
 */
object PerfMonitor {

    private const val TAG = "SshAi-Perf"
    private const val SAMPLE_INTERVAL_MS = 5_000L

    /** Current readings. Null fields = couldn't read this metric. */
    data class Snapshot(
        val batteryC: Double?,
        val cpuZoneC: Double?,
        /** 0-100. Whole-device, all cores combined. */
        val sysCpuPct: Double?,
        /** 0..(numCores * 100). 100 = one busy core. */
        val appCpuPct: Double?,
        /** Real RSS of our process, in MiB. */
        val appRssMib: Double?,
        val ts: Long = System.currentTimeMillis(),
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var job: Job? = null
    @Volatile private var enabled = false

    /** Previous /proc/stat sample (system-wide jiffies). */
    private var prevSysTotal = 0L
    private var prevSysBusy = 0L
    /** Previous /proc/self/stat sample (process jiffies). */
    private var prevAppTotal = 0L
    private var prevAppCpu = 0L

    fun start(context: Context) {
        if (enabled) return
        enabled = true
        val appCtx = context.applicationContext
        job = scope.launch {
            android.util.Log.i(TAG, "starting (interval=${SAMPLE_INTERVAL_MS}ms)")
            // Warm-up: take a first sample so the next interval has a
            // delta to compute against. Without this the first logged
            // sample would say cpu=0% (or worse, NaN).
            sample(appCtx, warmup = true)
            while (enabled) {
                delay(SAMPLE_INTERVAL_MS)
                sample(appCtx, warmup = false)
            }
        }
    }

    fun stop() {
        enabled = false
        job?.cancel()
        job = null
        _snapshot.value = null
        android.util.Log.i(TAG, "stopped")
    }

    private fun sample(context: Context, warmup: Boolean) {
        val batteryC = readBatteryC(context)
        val cpuZoneC = readHottestThermalZoneC()
        val sysCpu = readSysCpuPct()
        val appCpu = readAppCpuPct()
        val rss = readAppRssMib()
        if (warmup) {
            android.util.Log.d(TAG, "warm-up sample done")
            return
        }
        val snap = Snapshot(
            batteryC = batteryC,
            cpuZoneC = cpuZoneC,
            sysCpuPct = sysCpu,
            appCpuPct = appCpu,
            appRssMib = rss,
        )
        _snapshot.value = snap
        android.util.Log.i(
            TAG,
            buildString {
                append("bat=")
                append(batteryC?.let { "%.1f°C".format(it) } ?: "n/a")
                append(" cpu=")
                append(cpuZoneC?.let { "%.1f°C".format(it) } ?: "n/a")
                append(" sys=")
                append(sysCpu?.let { "%.0f%%".format(it) } ?: "n/a")
                append(" app=")
                append(appCpu?.let { "%.0f%%".format(it) } ?: "n/a")
                append(" rss=")
                append(rss?.let { "%.1fMiB".format(it) } ?: "n/a")
            },
        )
    }

    // ───────── battery temp ─────────

    /** Returns °C, or null if the sticky broadcast wasn't found. */
    private fun readBatteryC(context: Context): Double? {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: return null
        if (tenths == Int.MIN_VALUE) return null
        return tenths / 10.0
    }

    // ───────── CPU thermal zones ─────────

    /**
     * Walk `/sys/class/thermal/thermal_zone*`, prefer zones whose `type`
     * looks like a CPU (tsens / cpuN / soc / silver / gold). Picks the
     * hottest one. Returns null if no zones readable.
     */
    private fun readHottestThermalZoneC(): Double? {
        val zones = File("/sys/class/thermal").listFiles { f ->
            f.name.startsWith("thermal_zone")
        } ?: return null
        var best: Double? = null
        for (z in zones) {
            val type = SilentlyTry.nullOnError { File(z, "type").readText().trim() }.orEmpty()
            // Skip obviously-not-CPU zones (battery, modem, gpu, etc).
            val likelyCpu = listOf("cpu", "tsens", "soc", "silver", "gold", "apc").any {
                type.contains(it, ignoreCase = true)
            }
            if (!likelyCpu) continue
            val tempStr = SilentlyTry.nullOnError { File(z, "temp").readText().trim() } ?: continue
            val raw = tempStr.toLongOrNull() ?: continue
            // Most kernels report milli-°C (e.g. 45000 = 45.0 °C). A few
            // older ones report °C × 10. Sanity-divide: anything ≥ 1000
            // is milli-°C; otherwise treat as tenths.
            val c = if (raw >= 1000) raw / 1000.0 else raw / 10.0
            if (c <= 0 || c > 150) continue  // garbage
            if (best == null || c > best) best = c
        }
        return best
    }

    // ───────── system CPU ─────────

    /**
     * /proc/stat first line:
     *   `cpu user nice system idle iowait irq softirq steal guest guest_nice`
     *
     * Busy = sum of all except idle (and iowait, conventionally not
     * counted as load). Returns percent in [0, 100].
     */
    private fun readSysCpuPct(): Double? {
        val line = SilentlyTry.nullOnError {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        } ?: return null
        val cols = line.split(" ").filter { it.isNotEmpty() }
        if (cols.size < 5 || cols[0] != "cpu") return null
        val nums = cols.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val user = nums[0]
        val nice = nums[1]
        val system = nums[2]
        val idle = nums[3]
        val iowait = nums.getOrNull(4) ?: 0L
        val irq = nums.getOrNull(5) ?: 0L
        val softirq = nums.getOrNull(6) ?: 0L
        val busy = user + nice + system + irq + softirq
        val total = busy + idle + iowait
        val deltaTotal = total - prevSysTotal
        val deltaBusy = busy - prevSysBusy
        prevSysTotal = total
        prevSysBusy = busy
        if (deltaTotal <= 0) return null
        return 100.0 * deltaBusy / deltaTotal
    }

    // ───────── app CPU ─────────

    /**
     * /proc/self/stat fields (1-indexed in the man page):
     *   14 utime  — user-mode jiffies for this process
     *   15 stime  — kernel-mode jiffies for this process
     *
     * Compare against /proc/stat's total jiffies delta. Multi-core: a
     * single thread saturating one core gives 100% / N_CORES sys-wide,
     * so we multiply by N_CORES to give the user a "one core busy"
     * = 100% intuition.
     */
    private fun readAppCpuPct(): Double? {
        val statLine = SilentlyTry.nullOnError {
            File("/proc/self/stat").readText()
        } ?: return null
        // The comm field can contain spaces and parens; skip past the
        // closing ')'. Everything after is space-separated.
        val rparen = statLine.lastIndexOf(')')
        if (rparen < 0) return null
        val tail = statLine.substring(rparen + 2).split(" ")
        // After comm comes state (3), so utime is index 11 of `tail`
        // (0-indexed: 3=state, … 13=utime → tail[11]).
        val utime = tail.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = tail.getOrNull(12)?.toLongOrNull() ?: return null
        val appCpu = utime + stime
        // For the system-side baseline we need the SAME jiffies window
        // as the system CPU calculation used. We can't share state
        // cleanly across reads, so just re-read /proc/stat.
        val sysLine = SilentlyTry.nullOnError {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        } ?: return null
        val cols = sysLine.split(" ").filter { it.isNotEmpty() }.drop(1)
            .mapNotNull { it.toLongOrNull() }
        if (cols.size < 4) return null
        val sysTotal = cols.sum()
        val deltaSys = sysTotal - prevAppTotal
        val deltaApp = appCpu - prevAppCpu
        prevAppTotal = sysTotal
        prevAppCpu = appCpu
        if (deltaSys <= 0) return null
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return 100.0 * deltaApp / deltaSys * cores
    }

    // ───────── app RSS ─────────

    /**
     * /proc/self/status's `VmRSS:` line gives resident-set size in KiB.
     * Returns MiB or null.
     */
    private fun readAppRssMib(): Double? {
        val status = SilentlyTry.nullOnError {
            File("/proc/self/status").readText()
        } ?: return null
        val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
        val kib = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: return null
        return kib / 1024.0
    }
}
