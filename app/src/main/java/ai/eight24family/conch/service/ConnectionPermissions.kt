package ai.eight24family.conch.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The three OS-level things Conch needs in place for "never drop the SSH
 * connection without the user's consent" to actually hold up:
 *
 *  1. **POST_NOTIFICATIONS** (Android 13+). The persistent connection
 *     notification IS the foreground-service evidence — without it
 *     `startForeground()` raises SecurityException and the service dies.
 *     Runtime permission since SDK 33. Below 33: always granted.
 *
 *  2. **Ignore battery optimisations**. Without this, Doze and App
 *     Standby will yank our foreground service after the screen has
 *     been off for ~20–30 minutes "to save battery", which kills the
 *     pooled SSH transport and forces a fresh touch / password on
 *     return. Granted via the system dialog from
 *     `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; the user can also
 *     manage it later in Settings → Battery → "Unrestricted".
 *
 *  3. **Vendor auto-start / background-run whitelist**. Pure-Android
 *     phones don't need this, but Xiaomi/Huawei/Oppo/Vivo/Honor/etc
 *     ship their own kill-switch separate from upstream Doze that
 *     evicts background apps on whatever schedule the OEM thinks
 *     "optimises battery". There's no programmatic API to query the
 *     state — we just deep-link the user into the OEM page so they
 *     can flip the switch manually. Pure-Android (Pixel, stock) just
 *     hides this row.
 *
 * Each function here is best-effort. If a piece doesn't apply (older
 * Android, stock OEM) we return `Status.NotApplicable` and the UI
 * skips its row. Nothing here throws — call sites can safely run on
 * arbitrary devices.
 */
object ConnectionPermissions {

    enum class Status {
        /** Already in place — no action needed. */
        Granted,
        /** Action exists, user hasn't taken it yet. */
        Pending,
        /** OS/device doesn't expose this knob; render no row. */
        NotApplicable,
    }

    // ── 1. POST_NOTIFICATIONS ─────────────────────────────────────────────
    fun notificationStatus(ctx: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Status.Granted
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) Status.Granted else Status.Pending
    }

    // ── 2. Ignore battery optimisations ───────────────────────────────────
    fun batteryStatus(ctx: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Status.Granted
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return Status.Pending
        val ok = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        return if (ok) Status.Granted else Status.Pending
    }

    /**
     * Build the `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent. Caller
     * starts it as a regular Activity intent — it pops the system dialog
     * "Allow Conch to run in the background? [Allow] [Deny]". Note:
     * Google Play frowns on apps that request this WITHOUT a clear
     * background-need justification; persistent SSH counts (see
     * PERMISSION_DECLARATIONS.md).
     */
    fun batteryRequestIntent(ctx: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }
    }

    /** Fallback for users who don't see the system dialog (already prompted
     *  once and denied, or OEM strips the dialog). Opens the per-app battery
     *  settings page where they can flip "Unrestricted" / "Don't optimise". */
    fun batteryAppDetailIntent(ctx: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }
    }

    // ── 3. OEM auto-start whitelist ───────────────────────────────────────
    /**
     * Returns [Status.Pending] only when the device's manufacturer is one
     * of the heavy "kill background apps on a schedule" OEMs AND we have
     * a known deep-link for their auto-start manager. There's no way to
     * read back "is Conch on the whitelist?" from the OEM page, so the
     * status here is really "we believe this device requires manual
     * action, the user hasn't told us they've done it yet". Tracked by
     * a separate "acknowledged" pref the UI manages.
     */
    fun oemAutoStartStatus(ctx: Context, acknowledged: Boolean): Status {
        if (oemAutoStartIntent(ctx) == null) return Status.NotApplicable
        return if (acknowledged) Status.Granted else Status.Pending
    }

    /**
     * Best-effort OEM auto-start / background-run-allow settings page.
     * Walks a list of vendor activity components in MANUFACTURER-priority
     * order, returning the first one the PackageManager can resolve. Null
     * if the device is stock Android (Pixel, GrapheneOS, AOSP) — those
     * users get the row hidden and rely on the battery-opt grant alone.
     *
     * Sourced from a long-running community spreadsheet of "what activity
     * to launch to open vendor X's autostart screen" — these can move
     * between OS releases on the same OEM so we always try-resolve before
     * launching.
     */
    fun oemAutoStartIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        // Priority by current device manufacturer first, then a generic
        // sweep so an oem-skinned ROM (LineageOS-on-Xiaomi, etc) still
        // catches the right page.
        val manuf = Build.MANUFACTURER?.lowercase().orEmpty()
        val candidates = mutableListOf<Pair<String, String>>()
        when {
            manuf.contains("xiaomi") || manuf.contains("redmi") || manuf.contains("poco") -> {
                candidates += "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
                candidates += "com.miui.securitycenter" to "com.miui.permcenter.MainAcitivty"
            }
            manuf.contains("huawei") || manuf.contains("honor") -> {
                candidates += "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
                candidates += "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            }
            manuf.contains("oppo") -> {
                candidates += "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                candidates += "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity"
                candidates += "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"
            }
            manuf.contains("vivo") || manuf.contains("iqoo") -> {
                candidates += "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                candidates += "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            }
            manuf.contains("oneplus") -> {
                // Same ColorOS under the hood as Oppo.
                candidates += "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                candidates += "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            }
            manuf.contains("letv") || manuf.contains("leeco") -> {
                candidates += "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity"
            }
            manuf.contains("asus") -> {
                candidates += "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity"
            }
            manuf.contains("samsung") -> {
                // Samsung doesn't have a strict autostart page — its
                // equivalent is the per-app "Sleeping apps" exclusion list
                // inside Device Care. Deep-link there.
                candidates += "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
            }
        }
        // Generic sweep regardless of manufacturer, picks up rebranded ROMs.
        candidates += "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
        candidates += "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val info = pm.resolveActivity(intent, 0)
            if (info != null) return intent
        }
        return null
    }

    /** True if the user-facing checklist needs any action right now. */
    fun anythingPending(ctx: Context, oemAcknowledged: Boolean): Boolean {
        return notificationStatus(ctx) == Status.Pending ||
            batteryStatus(ctx) == Status.Pending ||
            oemAutoStartStatus(ctx, oemAcknowledged) == Status.Pending
    }
}
