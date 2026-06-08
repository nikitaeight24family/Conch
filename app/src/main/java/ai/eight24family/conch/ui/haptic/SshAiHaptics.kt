package ai.eight24family.conch.ui.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import ai.eight24family.conch.util.SilentlyTry

/**
 * Semantic haptic intents the app uses. Each one represents a
 * **meaning** ("tap confirmed", "rejected", "got a tag") — the
 * underlying device-level effect is picked by [SshAiHaptics] at the
 * call site based on what the OEM exposes.
 *
 * Why intents and not raw `VibrationEffect.EFFECT_*` constants:
 * older Androids / cheap OEM vibrators don't have all the
 * pre-defined effects, and forcing every call site to deal with
 * compatibility fallbacks bloats them. Calls just say "this is a
 * Tick"; the manager maps to whatever the device can do.
 *
 * The taxonomy maps cleanly to common modern-device haptic
 * vocabularies (iOS HIG's "selection / impact / notification",
 * Material 3 haptics guidance):
 *
 *  - [Tick]      — finest grain. Sliding through a list of options
 *                  (e.g. each PIN digit press, per-character text
 *                  selection). Many of these per second is fine.
 *  - [Tap]       — discrete button press, link tap. "I noticed you
 *                  touched something."
 *  - [Heavy]     — important event the user should physically feel
 *                  even one-handed: NFC tag captured, security
 *                  notification.
 *  - [Confirm]   — positive completion (login succeeded, file saved,
 *                  install done). Slightly emphatic — the user just
 *                  finished a multi-step thing.
 *  - [Reject]    — negative outcome the user needs to notice (wrong
 *                  PIN, connect failed, auth refused). Distinct
 *                  pattern so it's not confused with [Confirm].
 *  - [GestureEnd] — settling of a swipe / fling (pull-to-refresh
 *                  release, drag-drop snap). One-shot soft impulse.
 */
enum class SshAiHaptic { Tick, Tap, Heavy, Confirm, Reject, GestureEnd }

/**
 * Platform-neutral haptic player. Hidden behind a
 * [LocalSshAiHaptics] CompositionLocal so any composable in the
 * tree can call `haptic.perform(SshAiHaptic.Tap)` without knowing
 * about Vibrator / VibratorManager / API levels.
 *
 * The manager is **always available** — it gracefully no-ops on
 * devices without a vibrator, instead of forcing every call site
 * to null-check. The settings toggle ("Haptic feedback") flips an
 * internal flag; calls become no-ops when off without the call
 * sites caring.
 */
class SshAiHaptics(
    context: Context,
    /** Read from prefs at construction; mutable so Settings can flip
     *  it without forcing a new manager. */
    @Volatile var enabled: Boolean = true,
) {
    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val hasAmplitudeControl: Boolean =
        vibrator?.hasAmplitudeControl() == true

    /**
     * Perform [intent]. No-op when:
     *  - haptics disabled via Settings,
     *  - device has no vibrator,
     *  - vibrator returned null (rare emulator case).
     *
     * The mapping to actual hardware effects is intentionally
     * conservative — short, distinguishable patterns over try-to-
     * be-fancy long sequences. Cheap-OEM vibrators turn fancy
     * patterns into mushy 200ms blobs.
     */
    fun perform(intent: SshAiHaptic) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = predefinedEffect(intent) ?: customWaveform(intent)
            if (effect != null) {
                SilentlyTry.fired("SshAi-Haptics", "vibrate predefined effect") { v.vibrate(effect) }
                return
            }
        }
        // Pre-Q fallback: short one-shot pulses. Length tuned per
        // intent. amplitude only honoured on devices with the
        // capability; defaults to system pattern otherwise.
        val (ms, amp) = legacyPattern(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ampVal = if (hasAmplitudeControl) amp else VibrationEffect.DEFAULT_AMPLITUDE
            SilentlyTry.fired("SshAi-Haptics", "vibrate one-shot effect") {
                v.vibrate(VibrationEffect.createOneShot(ms, ampVal))
            }
        } else {
            @Suppress("DEPRECATION")
            SilentlyTry.fired("SshAi-Haptics", "vibrate ms (legacy)") { v.vibrate(ms) }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun predefinedEffect(intent: SshAiHaptic): VibrationEffect? = when (intent) {
        // EFFECT_TICK: gentlest predefined — perfect for slider-y
        // / per-keypress feedback. Mapped to "low-tick" on modern
        // OEM HAL implementations (Pixel taptic, Samsung's Hi-Fi).
        SshAiHaptic.Tick -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        // EFFECT_CLICK: discrete button-tap feel. Material's "tap"
        // primitive.
        SshAiHaptic.Tap -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        // EFFECT_HEAVY_CLICK: emphatic, what you want for "NFC
        // captured" or other important moments.
        SshAiHaptic.Heavy -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        // EFFECT_DOUBLE_CLICK for confirm — universally readable as
        // "yes that worked". Used by iOS notification "success" too.
        SshAiHaptic.Confirm -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        // No predefined "reject" — fall through to custom pattern.
        SshAiHaptic.Reject -> null
        // No predefined "settle" — fall through.
        SshAiHaptic.GestureEnd -> null
    }

    /** Pre-Q one-shot durations (ms) + amplitude (0..255). */
    private fun legacyPattern(intent: SshAiHaptic): Pair<Long, Int> = when (intent) {
        SshAiHaptic.Tick -> 10L to 60
        SshAiHaptic.Tap -> 18L to 110
        SshAiHaptic.Heavy -> 35L to 200
        SshAiHaptic.Confirm -> 25L to 140  // platform replays oneShot twice via createWaveform — simpler: one pulse
        SshAiHaptic.Reject -> 50L to 220
        SshAiHaptic.GestureEnd -> 14L to 90
    }

    /**
     * Q+ custom waveforms for the two intents without predefined
     * effects (Reject, GestureEnd). Called from [perform] when
     * `predefinedEffect` returns null.
     */
    @Suppress("unused")
    private fun customWaveform(intent: SshAiHaptic): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return when (intent) {
            // Reject: two short pulses with a noticeable gap —
            // "nope-nope" pattern. Recognisable as negative.
            SshAiHaptic.Reject -> VibrationEffect.createWaveform(
                /* timings */ longArrayOf(0, 35, 60, 35),
                /* amplitudes */ intArrayOf(0, 220, 0, 220),
                /* repeat */ -1,
            )
            // GestureEnd: one impulse with quick decay tail.
            SshAiHaptic.GestureEnd -> VibrationEffect.createWaveform(
                longArrayOf(0, 12, 8, 4),
                intArrayOf(0, 130, 60, 30),
                -1,
            )
            else -> null
        }
    }
}

val LocalSshAiHaptics = staticCompositionLocalOf<SshAiHaptics> {
    error("SshAiHaptics not provided — wrap the screen tree in a CompositionLocalProvider")
}

/** Convenience accessor; prefer
 *  `val haptic = LocalSshAiHaptics.current` at call sites for
 *  brevity. (Earlier version tried to wrap this in `remember {}`
 *  but `current` on a CompositionLocal is itself @Composable, so
 *  remember can't enclose it.) */
