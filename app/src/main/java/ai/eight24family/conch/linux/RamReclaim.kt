package ai.eight24family.conch.linux

import ai.eight24family.conch.adb.LocalAdbShell
import kotlinx.coroutines.delay

/**
 * Freeing ram for a model, with the one real lever Android leaves.
 *
 * Classic "ram cleaner" apps died with Android 14: killBackgroundProcesses
 * now only kills the CALLER's own processes, so a normal app cannot reclaim
 * anything. Conch is not a normal app on a bridged phone — the phone bridge
 * holds the shell uid, and shell may run `am kill-all`: the system's own
 * "kill every process that is SAFE to kill" (cached, non-foreground — a
 * playing music player or the foreground app are never touched; it is the
 * same reaping the kernel would do under pressure, just NOW instead of
 * mid-mmap).
 *
 * Measured on the owner's phone before this existed (2026-09-01):
 * MemAvailable 5.1G → 7.4G, +2.3G from one call — the difference between
 * "ram busy" and "fits" for the 4B class.
 *
 * Honest by construction: the outcome is MEASURED (availMem before/after,
 * the same number every fits verdict reads), and a dead bridge is its own
 * outcome — the UI points at Settings → Phone bridge, never at adb.
 */
object RamReclaim {

    sealed interface Outcome {
        /** availMem delta, measured — what the tap actually bought. */
        data class Freed(val freedBytes: Long, val availAfter: Long) : Outcome
        /** The shell isn't reachable (bridge not armed / Wi-Fi off). */
        data object BridgeDown : Outcome
    }

    /** availMem right after the last successful clean — the measured "nothing
     *  left to reclaim" baseline. Session-scoped on purpose: cached processes
     *  are a live population, and a reboot/app restart resets everything. */
    @Volatile private var lastCleanAvail: Long? = null

    /** Cache must re-accumulate at least this much below the post-clean
     *  baseline before the button is worth offering again — a clean right
     *  after a clean measures ~0 and only teaches distrust. */
    private const val REAPPEAR_DROP_BYTES = 512_000_000L

    /** Whether tapping would plausibly buy anything: never cleaned this
     * session, or availMem has sunk ≥0.5G below the last clean's result.
     * */
    fun worthOffering(currentAvail: Long): Boolean =
        lastCleanAvail?.let { currentAvail < it - REAPPEAR_DROP_BYTES } ?: true

    suspend fun freeUp(): Outcome {
        val before = PhoneResources.read().ramFreeBytes
        LocalAdbShell.exec("am kill-all") ?: return Outcome.BridgeDown
        // Give lmkd/kernel a beat to account the kills before measuring.
        delay(1_500)
        val after = PhoneResources.read().ramFreeBytes
        lastCleanAvail = after
        return Outcome.Freed((after - before).coerceAtLeast(0L), after)
    }
}
