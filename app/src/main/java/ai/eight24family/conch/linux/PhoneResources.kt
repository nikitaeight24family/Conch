package ai.eight24family.conch.linux

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import ai.eight24family.conch.di.ServiceLocator

/**
 * What this phone has to spare, RIGHT NOW.
 *
 * Read through the platform's own accounting ([ActivityManager.getMemoryInfo]
 * / [StatFs]) rather than /proc, which newer Android keeps from apps anyway.
 * `availMem` is the number the system itself would use to decide what to kill,
 * which makes it the honest input for "will this model fit" — not MemFree,
 * which on Android is always near zero by design.
 *
 * Cheap enough to poll while a screen that shows it is open; not cached.
 */
object PhoneResources {

    data class Snapshot(
        val ramFreeBytes: Long,
        val ramTotalBytes: Long,
        val diskFreeBytes: Long,
        val diskTotalBytes: Long = 0L,
    )

    fun read(): Snapshot {
        val am = ServiceLocator.appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val disk = StatFs(Environment.getDataDirectory().path)
        return Snapshot(
            ramFreeBytes = mi.availMem,
            ramTotalBytes = mi.totalMem,
            diskFreeBytes = disk.availableBytes,
            diskTotalBytes = disk.totalBytes,
        )
    }

    /** "0.8", "15.1" — one decimal is all a human compares. Locale-pinned:
     *  a ru-locale phone would otherwise print "0,8" into monospace rows. */
    fun gb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f", bytes / 1_073_741_824.0)

    /** "12.3 MB/s" / "480 kB/s" — the human end of a byte rate. */
    fun rate(bytesPerSec: Long): String =
        if (bytesPerSec >= 1_000_000L) {
            String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000.0)
        } else "${bytesPerSec / 1_000} kB/s"
}
