package ai.eight24family.conch.linux

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import ai.eight24family.conch.adb.LocalAdbShell
import ai.eight24family.conch.di.ServiceLocator
import java.io.File

/**
 * Live numbers for the local-inference bar: what the ENGINE is doing to this
 * phone right now — cpu, ram, heat. Shown in the chat where the plan-limits
 * bar would be: a local model has no quota, its cost is hardware.
 *
 * Reading another process's /proc is allowed for the same uid, and the engine
 * is our child — cpu% and rss come straight from `/proc/<pid>/stat|status`,
 * no shell involved. The CPU temperature is shell territory (thermal zones
 * are hidden from apps): where the phone bridge is armed we read the hottest
 * cpu/soc zone once discovered and cached; without it the battery temperature
 * stands in, labeled as such — a stand-in the user can see, not a lie.
 */
object LocalLlmTelemetry {

    data class Snapshot(
        /** Engine cpu, % of ALL cores (0–100); null when the engine is off. */
        val cpuPct: Int?,
        /** Engine resident memory; null when the engine is off. */
        val rssBytes: Long?,
        val ramFreeBytes: Long,
        val ramTotalBytes: Long,
        val tempC: Float?,
        /** "cpu" when a thermal zone answered over the bridge, "batt" for the
         *  battery fallback — the label is part of the truth. */
        val tempSource: String,
        /** Prompt-digestion progress (0–100) of the slot the engine is chewing
         *  RIGHT NOW, cache included — the answer to "5 minutes and nothing":
         *  a 7K codex prompt at 4B pace IS minutes, and the number moving is
         *  what separates working from dead. Null when nothing is processing. */
        val prefillPct: Int? = null,
    )

    private var lastPid = -1
    private var lastTicks = -1L
    private var lastAtMs = 0L

    /** Sysfs path of the chosen cpu/soc thermal zone, discovered once per
     *  process; "" = discovery ran and found nothing (don't retry each tick). */
    @Volatile private var thermalZonePath: String? = null

    suspend fun sample(): Snapshot {
        val res = PhoneResources.read()
        val pid = LocalLlmEngine.pid()
        var cpu: Int? = null
        var rss: Long? = null
        if (pid != null) {
            rss = readRssBytes(pid)
            val ticks = readCpuTicks(pid)
            val now = System.currentTimeMillis()
            if (ticks != null) {
                if (pid == lastPid && lastTicks >= 0 && now > lastAtMs) {
                    val dtSec = (now - lastAtMs) / 1000.0
                    val dTicks = (ticks - lastTicks).coerceAtLeast(0)
                    // USER_HZ is 100 on every Android kernel config in use.
                    val corePct = (dTicks / 100.0) / dtSec * 100.0
                    cpu = (corePct / Runtime.getRuntime().availableProcessors())
                        .toInt().coerceIn(0, 100)
                }
                lastPid = pid; lastTicks = ticks; lastAtMs = now
            }
        } else {
            lastPid = -1; lastTicks = -1
        }
        val (temp, source) = readTemp()
        return Snapshot(
            cpuPct = cpu,
            rssBytes = rss,
            ramFreeBytes = res.ramFreeBytes,
            ramTotalBytes = res.ramTotalBytes,
            tempC = temp,
            tempSource = source,
            prefillPct = if (pid != null) readPrefillPct() else null,
        )
    }




    /** `/slots` on the serving engine: the processing slot's prompt progress,
     *  cached tokens included (a resumed turn starts at its LCP reuse, not 0). */
    private fun readPrefillPct(): Int? = runCatching {
        val c = java.net.URL("${LocalLlmEngine.BASE_URL}/slots")
            .openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 1_000; c.readTimeout = 1_000
        val body = c.inputStream.bufferedReader().readText().also { c.disconnect() }
        val arr = org.json.JSONArray(body)
        for (i in 0 until arr.length()) {
            val slot = arr.optJSONObject(i) ?: continue
            if (!slot.optBoolean("is_processing")) continue
            val total = slot.optInt("n_prompt_tokens", 0)
            if (total <= 0) continue
            val done = slot.optInt("n_prompt_tokens_cache", 0) +
                slot.optInt("n_prompt_tokens_processed", 0)
            return (done * 100 / total).coerceIn(0, 100)
        }
        null
    }.getOrNull()

    private fun readCpuTicks(pid: Int): Long? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        // Fields 14+15 (utime+stime), counted AFTER the parenthesised comm —
        // which may itself contain spaces, so split after the closing paren.
        val rest = stat.substringAfterLast(')').trim().split(' ')
        rest[11].toLong() + rest[12].toLong()
    }.getOrNull()

    private fun readRssBytes(pid: Int): Long? = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024L)
        }
    }.getOrNull()

    private suspend fun readTemp(): Pair<Float?, String> {
        // Bridge path: the HOTTEST cpu core zone — a single fixed sensor
        // undersells the peak, and "cpu temperature" means the worst core
        // (measured on the owner's phone: 24 cpu-* zones spread 69.1–73.7°C
        // under load). Paths discovered once; each tick is one shell trip.
        if (thermalZonePath == null) {
            thermalZonePath = LocalAdbShell.exec(
                "for z in /sys/class/thermal/thermal_zone*; do " +
                    "t=\$(cat \$z/type 2>/dev/null); case \$t in cpu*|*soc*) printf '%s/temp ' \$z;; esac; done",
            )?.stdout?.trim()?.takeIf { it.startsWith("/sys/") } ?: ""
        }
        thermalZonePath?.takeIf { it.isNotEmpty() }?.let { zones ->
            LocalAdbShell.exec("cat $zones 2>/dev/null | sort -n | tail -1")
                ?.stdout?.trim()?.toFloatOrNull()
                ?.let { raw ->
                    // Zones report m°C (45000) or d°C (450) depending on driver.
                    val c = when {
                        raw > 1000 -> raw / 1000f
                        raw > 200 -> raw / 10f
                        else -> raw
                    }
                    if (c in 10f..120f) return c to "cpu"
                }
        }
        // Battery fallback — available to every app via the sticky intent.
        val batt = runCatching {
            ServiceLocator.appContext.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.div(10f)
        }.getOrNull()
        return batt to "batt"
    }
}
