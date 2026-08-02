package ai.eight24family.conch.agent

import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.ssh.SshClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ServerStats(
    val hostname: String? = null,
    val osPretty: String? = null,
    val uptime: String? = null,
    /** Total CPU utilisation across all cores, percent (0..100). */
    val cpuPercent: Int? = null,
    val cpuCount: Int? = null,
    val memUsedMb: Long? = null,
    val memTotalMb: Long? = null,
    val diskUsedHuman: String? = null,
    val diskTotalHuman: String? = null,
    val diskUsedPercent: Int? = null,
    /** Bytes/s incoming on non-loopback ifaces (sampled over 1 second). */
    val netRxBps: Long? = null,
    val netTxBps: Long? = null,
    /** Round-trip time of a single tiny exec channel (`echo` round trip). */
    val sshLatencyMs: Long? = null,
    /** CPU package / package-equivalent temperature in °C. Read from
     *  the first available thermal_zone. `null` on hosts that don't
     *  expose `/sys/class/thermal` (containers, some cloud VPS where
     *  kernel runs on host's bare metal but exposes nothing to the
     *  guest). */
    val cpuTempC: Float? = null,
    /** System load averages — 1, 5, 15 minute. Three independent
     *  fields rather than a triplet so consumers can selectively
     *  display only the one they care about. */
    val loadAvg1m: Float? = null,
    val loadAvg5m: Float? = null,
    val loadAvg15m: Float? = null,
    /** `uname -r` — kernel release. Lets the user see at a glance
     *  whether a server is running 6.x vs an ancient 4.x. */
    val kernel: String? = null,
    /** `uname -m` — machine architecture (`x86_64`, `aarch64`, `armv7l`).
     *  Useful for "is this Arm or x86" snap call. */
    val arch: String? = null,
) {
    fun memUsedPercent(): Int? {
        val u = memUsedMb ?: return null
        val t = memTotalMb ?: return null
        if (t <= 0) return null
        return (u * 100L / t).toInt().coerceIn(0, 100)
    }
}

/**
 * One-shot probe that runs a single bash script over SSH and parses the
 * results into a structured [ServerStats].
 *
 * Latency measurement uses the SAME executor that runs the heavy script,
 * so when callers pass a live-channel exec we measure real channel-RTT
 * (~2× network RTT). Passing a fresh-connect exec instead would include
 * ~10× RTT of TCP+SSH handshake, which makes the number meaningless.
 */
class ServerStatsProbe(private val ssh: SshClient) {

    /**
     * Run via the supplied [executor]. Pass an `AgentSession::execOnLive`
     * reference for the fast path; the [Server]+[ServerSecrets] overload
     * is still here as a fallback when no live session is available.
     */
    suspend fun probe(executor: suspend (String) -> String?): Result<ServerStats> =
        withContext(Dispatchers.IO) {
            val latencyMs: Long? = run {
                val t0 = System.nanoTime()
                val out = executor("echo PONG")
                if (out?.contains("PONG") == true) {
                    (System.nanoTime() - t0) / 1_000_000L
                } else null
            }
            val raw = executor(SCRIPT) ?: return@withContext Result.failure(
                IllegalStateException("Probe returned no output")
            )
            Result.success(parse(raw).copy(sshLatencyMs = latencyMs))
        }

    /** Fallback path: open a fresh SSH connection. Latency will be inflated. */
    suspend fun probe(server: Server, secrets: ServerSecrets): Result<ServerStats> =
        probe { cmd -> ssh.execute(server, secrets, cmd).getOrNull() }

    private fun parse(raw: String): ServerStats {
        val sections = sectionize(raw)
        var stats = ServerStats()

        sections["host"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { stats = stats.copy(hostname = it) }
        sections["os"]?.firstOrNull()
            ?.substringAfter("PRETTY_NAME=")
            ?.trim('"', ' ', '\t')
            ?.takeIf { it.isNotBlank() }
            ?.let { stats = stats.copy(osPretty = it) }
        sections["uptime"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { stats = stats.copy(uptime = it) }

        // Kernel + arch — single line each.
        sections["kernel"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            stats = stats.copy(kernel = it.trim())
        }
        sections["arch"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            stats = stats.copy(arch = it.trim())
        }

        // /proc/loadavg format: "0.42 0.31 0.18 1/123 4567"
        sections["loadavg"]?.firstOrNull()?.trim()?.split(Regex("\\s+"))
            ?.let { parts ->
                stats = stats.copy(
                    loadAvg1m = parts.getOrNull(0)?.toFloatOrNull(),
                    loadAvg5m = parts.getOrNull(1)?.toFloatOrNull(),
                    loadAvg15m = parts.getOrNull(2)?.toFloatOrNull(),
                )
            }

        // Thermal zone outputs milli-degrees C (e.g. "45000" = 45.0°C).
        // Some kernels report degrees directly (small numbers like 45);
        // we heuristically pick: > 200 = milli, else direct.
        sections["temp"]?.firstOrNull()?.trim()?.toLongOrNull()?.let { raw ->
            val tempC = if (raw > 200) raw / 1000f else raw.toFloat()
            // Sanity: drop nonsense readings (negative, > 150°C).
            if (tempC in 0f..150f) {
                stats = stats.copy(cpuTempC = tempC)
            }
        }

        sections["cpus"]?.firstOrNull()?.trim()?.toIntOrNull()?.let {
            stats = stats.copy(cpuCount = it)
        }

        // Two snapshots of /proc/stat aggregate cpu line: "cpu user nice system
        // idle iowait irq softirq ...". Compute % busy = (Δactive / Δtotal).
        fun statLine(s: List<String>?): LongArray? = s
            ?.firstOrNull { it.startsWith("cpu ") }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.drop(1)
            ?.mapNotNull { it.toLongOrNull() }
            ?.toLongArray()
            ?.takeIf { it.size >= 4 }

        val a = statLine(sections["stata"])
        val b = statLine(sections["statb"])
        if (a != null && b != null) {
            val totalA = a.sum()
            val totalB = b.sum()
            val idleA = a.getOrNull(3) ?: 0L  // idle column
            val idleB = b.getOrNull(3) ?: 0L
            val dt = totalB - totalA
            val di = idleB - idleA
            if (dt > 0) {
                val busy = ((dt - di).toDouble() / dt * 100.0).toInt().coerceIn(0, 100)
                stats = stats.copy(cpuPercent = busy)
            }
        }

        sections["mem"]?.firstOrNull()?.let { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                stats = stats.copy(
                    memTotalMb = parts.getOrNull(1)?.toLongOrNull(),
                    memUsedMb = parts.getOrNull(2)?.toLongOrNull()
                )
            }
        }
        sections["disk"]?.firstOrNull()?.let { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 5) {
                stats = stats.copy(
                    diskTotalHuman = parts.getOrNull(1),
                    diskUsedHuman = parts.getOrNull(2),
                    diskUsedPercent = parts.getOrNull(4)?.removeSuffix("%")?.toIntOrNull()
                )
            }
        }

        // Net: parse two raw /proc/net/dev snapshots in Kotlin (the awk
        // version was finicky and silently produced empty output on some
        // systems). Sum bytes across non-loopback interfaces.
        fun netTotals(s: List<String>?): Pair<Long, Long>? {
            if (s == null) return null
            var rx = 0L; var tx = 0L; var any = false
            for (line in s) {
                val trimmed = line.trim()
                if (trimmed.isBlank()) continue
                if (trimmed.startsWith("Inter-|") || trimmed.startsWith("face")) continue
                val name = trimmed.substringBefore(':', missingDelimiterValue = "").trim()
                if (name.isEmpty() || name == "lo") continue
                val cols = trimmed.substringAfter(':').trim().split(Regex("\\s+"))
                val rxBytes = cols.getOrNull(0)?.toLongOrNull() ?: continue
                val txBytes = cols.getOrNull(8)?.toLongOrNull() ?: continue
                rx += rxBytes; tx += txBytes; any = true
            }
            return if (any) rx to tx else null
        }
        val nA = netTotals(sections["neta"])
        val nB = netTotals(sections["netb"])
        if (nA != null && nB != null) {
            stats = stats.copy(
                netRxBps = (nB.first - nA.first).coerceAtLeast(0L),
                netTxBps = (nB.second - nA.second).coerceAtLeast(0L)
            )
        }
        return stats
    }

    /** Slice the script's output into named sections. */
    private fun sectionize(raw: String): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        var current: String? = null
        for (line in raw.lineSequence()) {
            if (line.startsWith("---")) {
                current = line.removePrefix("---").trim().lowercase()
                out.getOrPut(current) { mutableListOf() }
                continue
            }
            if (current != null && line.isNotBlank()) {
                out.getOrPut(current) { mutableListOf() }.add(line)
            }
        }
        return out
    }

    companion object {
        // The script takes two snapshots of /proc/stat (cpu) and /proc/net/dev
        // a second apart, plus one-shot facts (host/os/disk/mem). The Kotlin
        // parser does the math — keeps shell-side trivial and survives when
        // some distros lack `awk` quirks or pipe `free` columns differently.
        private val SCRIPT: String = """
            echo "--- host"
            hostname 2>/dev/null
            echo "--- os"
            grep PRETTY_NAME /etc/os-release 2>/dev/null
            echo "--- kernel"
            uname -r 2>/dev/null
            echo "--- arch"
            uname -m 2>/dev/null
            echo "--- uptime"
            uptime -p 2>/dev/null || uptime 2>/dev/null
            echo "--- loadavg"
            cat /proc/loadavg 2>/dev/null
            echo "--- cpus"
            nproc 2>/dev/null
            echo "--- mem"
            free -m 2>/dev/null | grep -E '^Mem'
            echo "--- disk"
            df -h / 2>/dev/null | sed -n '2p'
            echo "--- temp"
            # Try /sys/class/thermal first (kernel-standard), then a
            # few common per-vendor paths. First number wins. Values
            # are in milli-degrees C; the Kotlin parser divides.
            for f in /sys/class/thermal/thermal_zone*/temp \
                     /sys/devices/virtual/thermal/thermal_zone*/temp \
                     /sys/class/hwmon/hwmon*/temp1_input; do
                [ -r "${'$'}f" ] && cat "${'$'}f" 2>/dev/null && break
            done
            echo "--- statA"
            grep '^cpu ' /proc/stat 2>/dev/null
            echo "--- netA"
            cat /proc/net/dev 2>/dev/null
            sleep 1
            echo "--- statB"
            grep '^cpu ' /proc/stat 2>/dev/null
            echo "--- netB"
            cat /proc/net/dev 2>/dev/null
        """.trimIndent()
    }
}
