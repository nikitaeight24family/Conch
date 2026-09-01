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
 * underlying device-level effect is picked by [ConchHaptics] at the
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
 * - [Tick] — finest grain. Sliding through a list of options (e.g.
 * each PIN digit press, per-character text selection). Many of these
 * per second is fine. - [Tap] — discrete button press, link tap. "I
 * noticed you touched something." - [Heavy] — important event the user
 * should physically feel even one-handed: NFC tag captured, security
 * notification. - [Confirm] — positive completion (login succeeded,
 * file saved, install done). Slightly emphatic — the user just
 * finished a multi-step thing. - [Reject] — negative outcome the user
 * needs to notice (wrong PIN, connect failed, auth refused). Distinct
 * pattern so it's not confused with [Confirm]. - [GestureEnd] —
 * settling of a swipe / fling (pull-to-refresh release, drag-drop
 * snap). One-shot soft impulse. - [TurnEnd] — the agent finished
 * answering. THREE long pulses, deliberately unlike anything else in
 * the app: this is the one event the user is waiting on with the phone
 * in a pocket, so it has to be felt without looking. [Confirm]'s
 * double tick was too close to an ordinary UI ack to notice.
 */
enum class ConchHaptic { Tick, Tap, Heavy, Confirm, Reject, GestureEnd, TurnEnd, TurnPausedBg }

/**
 * Platform-neutral haptic player. Hidden behind a
 * [LocalConchHaptics] CompositionLocal so any composable in the
 * tree can call `haptic.perform(ConchHaptic.Tap)` without knowing
 * about Vibrator / VibratorManager / API levels.
 *
 * The manager is **always available** — it gracefully no-ops on
 * devices without a vibrator, instead of forcing every call site
 * to null-check. The settings toggle ("Haptic feedback") flips an
 * internal flag; calls become no-ops when off without the call
 * sites caring.
 */
class ConchHaptics(
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
    fun perform(intent: ConchHaptic) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = predefinedEffect(intent) ?: customWaveform(intent)
            if (effect != null) {
                SilentlyTry.fired("Conch-Haptics", "vibrate predefined effect") { v.vibrate(effect) }
                return
            }
        }
        // Pre-Q fallback: short one-shot pulses. Length tuned per
        // intent. amplitude only honoured on devices with the
        // capability; defaults to system pattern otherwise.
        val (ms, amp) = legacyPattern(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ampVal = if (hasAmplitudeControl) amp else VibrationEffect.DEFAULT_AMPLITUDE
            SilentlyTry.fired("Conch-Haptics", "vibrate one-shot effect") {
                v.vibrate(VibrationEffect.createOneShot(ms, ampVal))
            }
        } else {
            @Suppress("DEPRECATION")
            SilentlyTry.fired("Conch-Haptics", "vibrate ms (legacy)") { v.vibrate(ms) }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun predefinedEffect(intent: ConchHaptic): VibrationEffect? = when (intent) {
        // EFFECT_TICK: gentlest predefined — perfect for slider-y
        // / per-keypress feedback. Mapped to "low-tick" on modern
        // OEM HAL implementations (Pixel taptic, Samsung's Hi-Fi).
        ConchHaptic.Tick -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        // EFFECT_CLICK: discrete button-tap feel. Material's "tap"
        // primitive.
        ConchHaptic.Tap -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        // EFFECT_HEAVY_CLICK: emphatic, what you want for "NFC
        // captured" or other important moments.
        ConchHaptic.Heavy -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        // EFFECT_DOUBLE_CLICK for confirm — universally readable as
        // "yes that worked". Used by iOS notification "success" too.
        ConchHaptic.Confirm -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        // No predefined "reject" — fall through to custom pattern.
        ConchHaptic.Reject -> null
        // No predefined "settle" — fall through.
        ConchHaptic.GestureEnd -> null
        // Deliberately NOT a predefined effect: every predefined one is a
        // short click, and the point of TurnEnd is length. Falls through to
        // the triple-pulse waveform.
        ConchHaptic.TurnEnd -> null
        // Same family as TurnEnd, one pulse fewer — falls through.
        ConchHaptic.TurnPausedBg -> null
    }

    /** Pre-Q one-shot durations (ms) + amplitude (0..255). */
    private fun legacyPattern(intent: ConchHaptic): Pair<Long, Int> = when (intent) {
        ConchHaptic.Tick -> 10L to 60
        ConchHaptic.Tap -> 18L to 110
        ConchHaptic.Heavy -> 35L to 200
        ConchHaptic.Confirm -> 25L to 140  // platform replays oneShot twice via createWaveform — simpler: one pulse
        ConchHaptic.Reject -> 50L to 220
        ConchHaptic.GestureEnd -> 14L to 90
        // Pre-O devices can't do waveforms at all, so the "triple" collapses
        // to one long buzz. Still unmistakable next to an 18 ms Tap.
        ConchHaptic.TurnEnd -> 500L to 255
        ConchHaptic.TurnPausedBg -> 350L to 255
    }

    /**
     * Q+ custom waveforms for the two intents without predefined
     * effects (Reject, GestureEnd). Called from [perform] when
     * `predefinedEffect` returns null.
     */
    @Suppress("unused")
    private fun customWaveform(intent: ConchHaptic): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return when (intent) {
            // Reject: two short pulses with a noticeable gap —
            // "nope-nope" pattern. Recognisable as negative.
            ConchHaptic.Reject -> VibrationEffect.createWaveform(
                /* timings */ longArrayOf(0, 35, 60, 35),
                /* amplitudes */ intArrayOf(0, 220, 0, 220),
                /* repeat */ -1,
            )
            // GestureEnd: one impulse with quick decay tail.
            ConchHaptic.GestureEnd -> VibrationEffect.createWaveform(
                longArrayOf(0, 12, 8, 4),
                intArrayOf(0, 130, 60, 30),
                -1,
            )
            // TurnEnd: three LONG pulses at full amplitude, the third held
            // longest so the pattern reads as "…and done" rather than a
            // stutter. ~850 ms end to end — long enough to feel through a
            // pocket, short enough not to be a ringtone.
            //
            // Amplitude array only when the device can honour it: on a
            // vibrator without amplitude control the platform quantises to
            // on/off anyway, and a timing-only waveform is the documented
            // way to say that, so cheap OEM motors give three clean buzzes
            // instead of one mushy blob.
            ConchHaptic.TurnEnd -> {
                val timings = longArrayOf(0, 220, 130, 220, 130, 280)
                if (hasAmplitudeControl) {
                    VibrationEffect.createWaveform(
                        timings,
                        intArrayOf(0, 255, 0, 255, 0, 255),
                        -1,
                    )
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }
            }
            // TurnPausedBg: TWO long pulses — same family as TurnEnd's three,
            // one fewer on purpose: "paused, waiting on background work", not
            // "done". The turn resumes by itself when the task-notification
            // lands, so announcing the full "…and done" was a lie the user
            // felt.
            ConchHaptic.TurnPausedBg -> {
                val timings = longArrayOf(0, 220, 130, 280)
                if (hasAmplitudeControl) {
                    VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0, 255), -1)
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }
            }
            else -> null
        }
    }
}

val LocalConchHaptics = staticCompositionLocalOf<ConchHaptics> {
    error("ConchHaptics not provided — wrap the screen tree in a CompositionLocalProvider")
}

/** Convenience accessor; prefer
 *  `val haptic = LocalConchHaptics.current` at call sites for
 *  brevity. (Earlier version tried to wrap this in `remember {}`
 *  but `current` on a CompositionLocal is itself @Composable, so
 *  remember can't enclose it.) */
