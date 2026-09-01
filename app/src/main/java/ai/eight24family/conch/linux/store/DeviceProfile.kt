package ai.eight24family.conch.linux.store

import android.os.Build
import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.LocalLlmEngine
import ai.eight24family.conch.linux.PhoneResources
import java.io.File

/**
 * What the store KNOWS about this phone — the whole point of the store.
 *
 * Every number here is read from the device or measured on it, never asked of
 * the user: soc + ram + disk decide WHICH shelf rows can run at all, the cpu
 * feature flags and the gpu front decide how fast, and the moment any model
 * has a measured speed on this phone, every other row's estimate is re-derived
 * from that measurement (see [bwGbps]) — the store gets smarter about the
 * device the more it's used, per-device, with nothing leaving the phone.
 */
object DeviceProfile {

    data class Profile(
        /** Marketing-ish device name (Build.MODEL — "CPH2671"). */
        val device: String,
        /** SoC id — Build.SOC_MODEL on API 31+ ("SM8750-AB"), Build.HARDWARE
         *  ("qcom") on older phones: coarser, still matchable. */
        val soc: String,
        val ramTotalBytes: Long,
        val diskFreeBytes: Long,
        val cores: Int,
        /** The inference-relevant ISA extensions, straight off /proc/cpuinfo:
         *  fp16 math, int8 dot product, int8 matmul, scalable vectors. */
        val fp16: Boolean,
        val dotprod: Boolean,
        val i8mm: Boolean,
        val sve: Boolean,
        /** A vendor OpenCL front exists — the gpu offload door is present.
         *  Whether it WORKS is a separate, measured fact (ModelRecords). */
        val gpuFront: Boolean,
    )

    @Volatile private var cached: Profile? = null

    // ── the gpu, named ──

    data class Gpu(
        /** "Adreno 830" — the actual silicon, or null when nothing answered. */
        val model: String?,
        /** Driver version when Android's updatable-driver mechanism carries it
         *  (ro.gfx.driver.N names a package; its versionName IS the driver
         *  version). Null = driver ships in the system image, no version API. */
        val driverVersion: String?,
        val driverUpdatable: Boolean,
    )

    @Volatile private var gpuCached: Gpu? = null

    /** Read once per process: kgsl sysfs names Adreno silicon exactly
     *  (verified readable from app context on the owner's phone, 2026-09-01:
     *  "Adreno830v2"); Mali exposes gpuinfo; the egl prop is the coarse
     *  fallback. Driver version comes from the updatable-driver package when
     *  the phone has one. */
    fun gpu(): Gpu = gpuCached ?: run {
        val model = runCatching {
            File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim()
                .takeIf { it.isNotEmpty() }
                ?.let { raw ->
                    Regex("(?i)adreno(\\d+)").find(raw)?.let { "Adreno ${it.groupValues[1]}" } ?: raw
                }
        }.getOrNull()
            ?: runCatching {
                File("/sys/class/misc/mali0/device/gpuinfo").readText().trim()
                    .takeIf { it.isNotEmpty() }?.substringBefore('\n')
            }.getOrNull()
            ?: prop("ro.hardware.egl")?.takeIf { it.isNotEmpty() }
                ?.replaceFirstChar { it.uppercase() }
        var driverVersion: String? = null
        var updatable = false
        for (slot in 0..1) {
            val pkg = prop("ro.gfx.driver.$slot")?.takeIf { it.isNotBlank() } ?: continue
            runCatching {
                val pm = ai.eight24family.conch.di.ServiceLocator.appContext.packageManager
                driverVersion = pm.getPackageInfo(pkg, 0).versionName
                updatable = true
            }
        }
        Gpu(model, driverVersion, updatable).also { gpuCached = it }
    }

    /** One system property, via the world-executable getprop — no hidden API. */
    private fun prop(name: String): String? = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", name))
            .inputStream.bufferedReader().use { it.readLine()?.trim() }
    }.getOrNull()

    // ── live CPU load, app-readable, no bridge ──
    //
    // /proc/stat is SELinux-denied to an ordinary app (measured), so true
    // %-utilization needs the phone bridge. cpufreq IS readable (measured:
    // cpu0 960/3533 MHz), so the honest app-side signal is aggregate clock:
    // Σ current / Σ max across the cores. It reads how hard the governor is
    // driving the silicon right now — imperfect (race-to-idle can read low
    // under bursty load), so it is labeled "clock", never "utilization".

    private val cpuCount: Int by lazy {
        runCatching {
            File("/sys/devices/system/cpu")
                .listFiles { f -> f.name.matches(Regex("cpu\\d+")) }?.size
        }.getOrNull()?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
    }

    /** 0f..1f aggregate clock across cores, or null when cpufreq is closed. */
    fun cpuClockLoad(): Float? = runCatching {
        var cur = 0L
        var max = 0L
        for (i in 0 until cpuCount) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
            val c = File("$base/scaling_cur_freq").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
                ?: continue
            val m = File("$base/cpuinfo_max_freq").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
                ?: File("$base/scaling_max_freq").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
                ?: continue
            cur += c; max += m
        }
        if (max > 0) (cur.toFloat() / max).coerceIn(0f, 1f) else null
    }.getOrNull()

    // ── temperature ──
    //
    // CPU/SoC thermal zones are SELinux-blocked to an ordinary app here
    // (measured — only generic zones read), so the honest at-rest number is
    // the battery temperature via the sticky intent, LABELED "batt". A direct
    // sysfs cpu/tsens zone is tried first for phones that expose one; during
    // inference LocalLlmTelemetry's bridge path reports the real CPU zone.

    /** (celsius, "cpu"|"batt") or null. */
    fun temperature(): Pair<Float, String>? {
        runCatching {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
                ?.forEach { z ->
                    val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrNull() ?: return@forEach
                    if (type.contains("cpu") || type.contains("tsens") || type.startsWith("soc")) {
                        val raw = runCatching { File(z, "temp").readText().trim().toFloatOrNull() }.getOrNull()
                            ?: return@forEach
                        val c = when {
                            raw > 1000 -> raw / 1000f
                            raw > 200 -> raw / 10f
                            else -> raw
                        }
                        if (c in 10f..120f) return c to "cpu"
                    }
                }
        }
        val batt = runCatching {
            ai.eight24family.conch.di.ServiceLocator.appContext.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
            )?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.div(10f)
        }.getOrNull()
        return batt?.let { it to "batt" }
    }

    fun read(): Profile = cached ?: run {
        val res = PhoneResources.read()
        val features = runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }?.lowercase() ?: ""
            }
        }.getOrDefault("")
        Profile(
            device = Build.MODEL ?: "phone",
            soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
            ramTotalBytes = res.ramTotalBytes,
            diskFreeBytes = res.diskFreeBytes,
            cores = Runtime.getRuntime().availableProcessors(),
            fp16 = "asimdhp" in features || "fphp" in features,
            dotprod = "asimddp" in features,
            i8mm = "i8mm" in features,
            sve = "sve" in features,
            gpuFront = LocalLlmEngine.gpuFrontPresent(),
        ).also { cached = it }
    }

    // ── the capacity gate ──

    /** The most ram a resident model may claim on this phone before Android
     *  starts killing what the user has open. TOTAL-ram based on purpose: the
     *  store's "can this phone run it AT ALL" must not flap with whatever is
     *  open right now — availMem drives the live fits/tight/short line instead. */
    fun capacityBytes(cat: StoreCatalog.Catalog, p: Profile = read()): Long =
        (p.ramTotalBytes * cat.capacityFraction).toLong()

    fun runsOnThisPhone(e: StoreCatalog.Entry, cat: StoreCatalog.Catalog, p: Profile = read()): Boolean =
        needBytes(e) <= capacityBytes(cat, p)

    /** Total resident need: weights + KV at the engine's real context + the
     *  compute/runtime slice. Builtins defer to [LocalLlm.ramNeeded]'s tuned
     *  constant — same number the local-models rows show. */
    fun needBytes(e: StoreCatalog.Entry): Long {
        LocalLlm.byId(e.id)?.let { return LocalLlm.ramNeeded(it) }
        return e.bytes + e.kvPerTok * LocalLlmEngine.CTX_TOKENS + StoreCatalog.COMPUTE_BYTES
    }

    // ── the speed estimate ──

    /**
     * Effective memory bandwidth for token generation, GB/s.
     *
     * SELF-CALIBRATING: generation on a phone is memory-bound, so one measured
     * model (tok/s × bytes-read-per-token) reveals the device's real effective
     * bandwidth, and that single number re-prices every other shelf row. Until
     * anything is measured, the manifest's SoC-class table stands in.
     */
    fun bwGbps(cat: StoreCatalog.Catalog, p: Profile = read()): Double = bwInfo(cat, p).first

    /** (effective GB/s, measured?) — measured=true when the number came from
     *  a real generation on this phone, false when it's the SoC-class guess. */
    fun bwInfo(cat: StoreCatalog.Catalog, p: Profile = read()): Pair<Double, Boolean> {
        val measured = ModelRecords.all().mapNotNull { (id, rec) ->
            val tokS = rec.tokS ?: return@mapNotNull null
            val active = cat.models.firstOrNull { it.id == id }?.activeBytes
                ?: LocalLlm.byId(id)?.bytes ?: return@mapNotNull null
            tokS * active / 1e9
        }.maxOrNull()
        if (measured != null && measured > 0.5) return measured to true
        val soc = p.soc.lowercase()
        return (cat.bw.firstOrNull { c -> c.match.any { soc.contains(it) } }?.gbps
            ?: cat.defaultGbps).toDouble() to false
    }

    /** "~12 tok/s" until this phone has measured itself. */
    fun estTokS(e: StoreCatalog.Entry, cat: StoreCatalog.Catalog, p: Profile = read()): Int =
        (bwGbps(cat, p) * 1e9 / e.activeBytes).toInt().coerceIn(1, 99)
}
