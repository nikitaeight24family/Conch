package ai.eight24family.conch.adb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "The app needs this phone's shell and cannot get it alone" — raised from
 * anywhere, answered in one place.
 *
 * ⛔ THIS IS A REQUEST FOR A WIZARD, NOT A TRIP TO SETTINGS. It used to move
 * the user to the Settings tab with the Phone-bridge category pre-selected —
 * better than a dead sentence, and still wrong: it took the screen away from
 * whatever they were doing, landed them in a page of explanation, and left
 * getting back as their problem. The owner's rule is shorter than the page was:
 * either the app fixes it itself, or it takes the user by the hand
 * (2026-09-06). So this raises a modal over whatever is on screen, walks the
 * two Android switches step by step, watches for each one to land, and closes
 * itself when the machine is up.
 *
 * ⚠ THE REQUEST IS STICKY, and that is what makes it work when nobody is
 * looking. An agent asking for a shell at three in the morning cannot show a
 * dialog to a dark screen; it posts a notification and leaves this set, so the
 * wizard is standing there the moment the app is next opened. Cleared when the
 * user acts on it or dismisses it — never by a timer, because an unanswered
 * request has not stopped being true.
 *
 * Lives in `adb`, not in `ui`, so the layers that discover the problem —
 * the shell, the agent bridge, the connection pool — can raise it without
 * reaching up into Compose.
 */
object PhoneBridgeSetup {

    /**
     * @param why one short line naming what the user was trying to do, shown
     *   under the wizard's title so a modal that appears on its own explains
     *   itself. Null for "they asked for this directly".
     */
    data class Request(val why: String? = null)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** Ask for the wizard. Idempotent: a second ask while one is standing keeps
     *  the FIRST reason, which is the one the user is closest to. */
    fun ask(why: String? = null) {
        if (_pending.value == null) _pending.value = Request(why)
    }

    /** The user closed it, or it finished. */
    fun clear() {
        _pending.value = null
    }

    /**
     * Ask, and reach the owner even if nobody is holding the phone.
     *
     * For the requests that do not come from a tap: an agent on a server asking
     * to run a command here, at any hour. The modal cannot appear on a dark
     * screen, and the request alone would sit unseen until the app happened to
     * be opened — so this also puts one line in the shade, whose tap opens
     * Conch onto the waiting wizard.
     *
     * ⛔ NEVER OVER AN OPEN PAIRING DIALOG. The code box is a notification with
     * the same id, and posting this on top of it would take away the field the
     * user is typing into, in the seconds it is valid.
     */
    fun askAndNotify(context: android.content.Context, why: String) {
        ask(why)
        if (PairingWatcher.livePort.value != null) return
        PairingNotifier.report(
            context,
            "An agent needs this phone",
            "$why Open Conch — two taps and it is done.",
        )
    }

    /** Where the flow is, entirely from what can be observed right now. */
    enum class Step {
        /** Asking the phone. */
        CHECKING,

        /** Android will not arm wireless debugging without a Wi-Fi association,
         *  and no app can turn the radio on for the user. */
        WIFI,

        /** Wi-Fi is there; the debugging switch is not on. */
        ARM,

        /** Android's pairing dialog is open RIGHT NOW — six digits are on
         *  screen and the notification is waiting for them. */
        PAIR,

        /** Shell obtained. Nothing left for the user to do. */
        READY,
    }

    /**
     * @param needsPairing the phone has answered and refused our key, so
     *   arming the switch alone will not be enough — observed, never guessed
     *   (see [LocalAdbShell.needsPairing]).
     */
    data class Status(val step: Step, val needsPairing: Boolean = false)

    /**
     * One look at the world.
     *
     * ORDER IS THE POINT. The pairing dialog wins over everything: if it is
     * open, the user is standing in front of six digits that expire when they
     * navigate away, and telling them about Wi-Fi at that moment would throw
     * the code away. After that it is the cheapest true statement first —
     * a radio the user can see, then a switch they cannot.
     */
    suspend fun look(): Status {
        // ⛔ userInitiated, EVERY TICK, and the backoff is the reason. A failed
        // dial parks adbd for 20 s — a guard written for the two-second pollers
        // that would otherwise hammer a port with nothing behind it. Inside
        // this flow that guard is exactly wrong: someone is standing in front
        // of the phone having just flipped the switch, and the wizard promising
        // "this moves on by itself" must not then sit out a cooldown before
        // noticing. The cost is a live mDNS window instead of a cheap no —
        // which is what makes the step change within a second of the switch.
        if (LocalAdbShell.check(userInitiated = true)) return Status(Step.READY)
        // ⚠ READ THE WATCHER'S ANSWER, DO NOT ASK mDNS AGAIN. A lookup of our
        // own costs its full 6 s timeout when no dialog is open — which is
        // most ticks — and would turn a 1.5 s poll into a screen that updates
        // every seven seconds. [PairingWatcher] is already asking on exactly
        // this cadence, for the notification; this is the same answer, free.
        if (PairingWatcher.livePort.value != null) return Status(Step.PAIR, needsPairing = true)
        return guess()
    }

    /**
     * The same answer without asking the phone — for the instant the flow
     * appears.
     *
     * ⚠ IT CAN ONLY EVER BE PESSIMISTIC. [look] pays a real dial, and against a
     * phone with nothing listening that is a live mDNS window: up to ten
     * seconds of "Checking…" as the first thing a modal shows. This fills that
     * gap with the two facts that need no network — the radio, and whether the
     * phone has refused our key before — and never with READY, which is the one
     * state that must be proven before it is said.
     */
    fun guess(): Status {
        val needsPairing = LocalAdbShell.needsPairing()
        if (LocalAdbShell.wifiIsOff()) return Status(Step.WIFI, needsPairing)
        return Status(Step.ARM, needsPairing)
    }
}
