package ai.eight24family.conch.ssh.securitykey

import android.app.Activity
import android.app.Application
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Thin wrapper around `YubiKitManager` that turns its callback-shaped
 * USB / NFC discovery API into suspend functions, so callers can write
 * sequential coroutine flows like:
 *
 *     val device = manager.awaitUsb(timeoutMs = 30_000)
 *     val result = registrar.register(device, ...)
 *
 * Despite the `YubiKey…` class names, what we use here is purely the
 * generic transport plumbing: `YubiKeyDevice` is the supertype of every
 * USB/NFC FIDO authenticator yubikit can see. The CTAP2 protocol on top
 * is vendor-neutral, so a Nitrokey / SoloKey / Token2 plugged into the
 * same transport works through the same code path. Yubico's library
 * stops being yubikey-specific the moment you reach for FIDO; their
 * own docs spell that out.
 *
 * One [SecurityKeyManager] per process is enough — instantiate it from
 * the Application object and reuse. NFC discovery, however, is keyed
 * to a specific [Activity] (it owns the foreground reader-mode
 * dispatch), so [awaitNfc] takes one as a parameter.
 */
class SecurityKeyManager(application: Application) {

    private val ymgr = YubiKitManager(application)

    /**
     * Start USB device discovery and suspend until one shows up. Returns
     * `null` if [timeoutMs] elapses without a device. Cancels discovery
     * on suspend cancellation, so the caller can call this from a
     * coroutine that's tied to the dialog's lifecycle and have it stop
     * cleanly when the dialog is dismissed.
     */
    suspend fun awaitUsb(timeoutMs: Long = 60_000): YubiKeyDevice? {
        val signal = CompletableDeferred<YubiKeyDevice>()
        // Allow vendor permission popups so the user can grant access on
        // first plug-in. Without this the Listener never fires for a
        // never-before-seen USB device — Android holds the device until
        // permission is granted.
        val cfg = UsbConfiguration().handlePermissions(true)
        ymgr.startUsbDiscovery(cfg) { device: UsbYubiKeyDevice ->
            // Multiple devices arrive as multiple callbacks; we just take
            // the first and stop listening.
            if (!signal.isCompleted) signal.complete(device)
        }
        return try {
            withTimeout(timeoutMs) { signal.await() }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            SilentlyTry.fired("SshAi-SK-Mgr", "stop usb discovery") { ymgr.stopUsbDiscovery() }
        }
    }

    /**
     * Start NFC reader-mode on [activity] and run [op] inside yubikit's
     * NFC callback when a tag is presented. Returns whatever [op]
     * returned, or `null` on timeout / no tag.
     *
     * Why callback-based instead of returning a [YubiKeyDevice] like
     * USB does: an [NfcYubiKeyDevice] is backed by an `IsoDep` whose
     * underlying tag handle is **only valid inside the callback**.
     * Once Android NFC dispatches the tag once and we return from the
     * listener, the next `openConnection()` on the device fails with
     * `java.io.IOException` from `BasicTagTechnology.connect` —
     * confirmed in 1.0.8 first-test logs. So all CTAP operations
     * must run synchronously under the listener; pulling a device out
     * to use it later is broken by design.
     *
     * USB has the opposite shape (handles are stable across callbacks),
     * which is why [awaitUsb] still returns a device.
     *
     * Throws [NfcNotAvailable] when the device has no NFC hardware OR
     * the user has NFC switched off in system settings — caller maps
     * that to a clear "Enable NFC in settings" message.
     *
     * Blocks the calling coroutine on yubikit's worker thread (which
     * is NOT the main thread, so the user's CTAP touch wait is safe
     * even though it locks this lambda for tens of seconds).
     */
    suspend fun <T> withNfc(
        activity: Activity,
        timeoutMs: Long = 60_000,
        op: (YubiKeyDevice) -> T,
    ): T? {
        val tag = "SshAi-SK-Mgr"
        val result = CompletableDeferred<T>()
        val cfg = NfcConfiguration().timeout(2_000)
        try {
            android.util.Log.d(tag, "startNfcDiscovery: activity=${activity.javaClass.simpleName}")
            ymgr.startNfcDiscovery(cfg, activity) { device: NfcYubiKeyDevice ->
                android.util.Log.d(tag, "NFC listener fired: device=${device.javaClass.simpleName} tag=${device.tag}")
                if (result.isCompleted) {
                    android.util.Log.d(tag, "  result already complete, ignoring")
                    return@startNfcDiscovery
                }
                try {
                    result.complete(op(device))
                } catch (t: Throwable) {
                    android.util.Log.e(tag, "  op threw", t)
                    result.completeExceptionally(t)
                }
            }
            android.util.Log.d(tag, "startNfcDiscovery returned (reader mode armed)")
        } catch (t: Throwable) {
            android.util.Log.e(tag, "startNfcDiscovery THREW", t)
            throw t
        }
        return try {
            withTimeout(timeoutMs) { result.await() }
        } catch (_: TimeoutCancellationException) {
            android.util.Log.w(tag, "withNfc: timed out after ${timeoutMs}ms")
            null
        } finally {
            SilentlyTry.fired("SshAi-SK-Mgr", "stop nfc discovery") { ymgr.stopNfcDiscovery(activity) }
        }
    }
}
