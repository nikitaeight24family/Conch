package ai.eight24family.conch.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.adb.PairingNotifier
import ai.eight24family.conch.adb.PairingWatcher
import ai.eight24family.conch.adb.PhoneBridgeSetup
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.delay

/**
 * The one place the app asks for something it cannot do itself.
 *
 * ⛔ WHY A MODAL AND NOT A PAGE. Everything that needs this phone's shell used
 * to end the same way: a sentence in a row, clipped at two lines, beside a
 * button that could not help. The best of them sent the user to the Settings
 * tab — which is still the app walking away from its own problem: the screen
 * they were on is gone, the thing they asked for is forgotten, and a page of
 * explanation is what they get instead of the two taps they need (owner,
 * 2026-09-06: either the app fixes it itself, or it leads by the hand).
 *
 * So this appears OVER whatever is on screen, from anywhere, via
 * [PhoneBridgeSetup.ask] — including from an agent's request while the phone is
 * in a pocket, because that request is sticky and waits here.
 *
 * ⭐ IT DOES EVERYTHING THE PLATFORM ALLOWS AND ASKS FOR THE REST. Exactly two
 * things on this path belong to the user — a radio Android will not let an app
 * switch on (below API 29 it will, and then this asks for nothing) and a
 * six-digit code Android shows in its own dialog. Everything around them is the
 * app's work and happens here without being asked: watching for each switch to
 * land, catching Android's pairing dialog, starting the Linux the moment a
 * shell exists, and closing when the machine is up.
 *
 * ⚠ NOTHING HERE IS A CHECKLIST THE USER TICKS. Every step advances because the
 * app OBSERVED it, never because someone pressed "next" — a wizard that trusts
 * a button ends with a confident "Done ✓" over a phone that is still off.
 */
@Composable
fun PhoneBridgeWizardHost() {
    val pending by PhoneBridgeSetup.pending.collectAsState()
    val request = pending ?: return
    val cyan = MaterialTheme.colorScheme.primary
    androidx.compose.ui.window.Dialog(onDismissRequest = { PhoneBridgeSetup.clear() }) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RectangleShape,
            border = BorderStroke(1.dp, cyan),
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Wake this phone",
                    color = cyan,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                // A modal that appears on its own says why it is here — the
                // request carries the sentence from whoever raised it.
                request.why?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PhoneBridgeSteps(onFinished = { PhoneBridgeSetup.clear() })
                TextButton(
                    onClick = { PhoneBridgeSetup.clear() },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Not now") }
            }
        }
    }
}

/**
 * The live flow itself, framed by whoever hosts it — the modal above, or the
 * Phone-bridge section in Settings.
 *
 * ⛔ ONE IMPLEMENTATION, TWO FRAMES. The settings page carried its own copy of
 * these steps, and the two drifted: the page knew about the hotspot trick and
 * the modal did not; the page started the pairing watcher on a button press,
 * so a dialog already open when the user arrived was never noticed. Whatever is
 * true about this flow has to be true in both places at once, so there is only
 * one of it.
 *
 * @param onFinished called once, after the machine is actually up — the host
 *   dismisses itself. Never called for a step the user merely walked past.
 */
@Composable
fun PhoneBridgeSteps(onFinished: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val cyan = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Starts null, never at a guess: `hasLiveSession` is a field that stays
    // true over a socket the phone closed while nobody was looking, and opening
    // on "Ready ✓" for two seconds is the one thing this must not do.
    var status by remember { mutableStateOf<PhoneBridgeSetup.Status?>(null) }
    var starting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    // The environment is absent, and putting one on someone's phone is not a
    // thing to do while their back is turned — so the flow stops here and waits
    // for a tap. Everything else it does on its own; this one it asks for.
    var offerInstall by remember { mutableStateOf(false) }
    // ⛔ A STEP THAT CANNOT BE COMPLETED IS A TRAP, and this one has a case it
    // cannot see: a phone sharing its own hotspot satisfies Android's rule and
    // still reads as "Wi-Fi off" (the AP state is hidden API). Without a way
    // past, that user watches a wizard ask for something they have already
    // done, forever. One tap says so and the flow moves on; the switch step
    // that follows is the real test either way.
    var pastWifi by remember { mutableStateOf(false) }
    // ⛔ A BUTTON THAT SILENTLY DOES NOTHING IS THE DEAD END AGAIN, WEARING A
    // BUTTON. Opening Android's own page is a request, not a guarantee: an OEM
    // can refuse the deep link, and a phone driven onto a second display (a
    // desktop dock, a scrcpy display — measured on the owner's, 2026-09-06)
    // creates the task and drops it on the floor.
    //
    // ⚠ AND IT IS NOT DETECTED BY WATCHING THE LIFECYCLE, which was the first
    // attempt: "we never lost the foreground" sounds like proof and is not —
    // the refused task DOES take focus for an instant on its way to being
    // dropped, so the app is paused, resumed, and reports success about a page
    // nobody ever saw (measured the same evening). The second tap is the
    // honest signal, because a person only taps a button again when the first
    // tap did nothing.
    var pageTaps by remember { mutableStateOf(0) }
    var installStep by remember { mutableStateOf<String?>(null) }
    val pairingPort by PairingWatcher.livePort.collectAsState()

    LaunchedEffect(attempt) {
        // The first frame, with nothing asked of the network — see
        // [PhoneBridgeSetup.guess]. Without it the modal opens on ten seconds
        // of "Checking…", which is the app thinking where the user expected a
        // step.
        status = PhoneBridgeSetup.guess()
        // A tap on [ install it ] re-enters here with the consent given.
        if (installStep != null) {
            problem = ai.eight24family.conch.linux.LinuxInstaller.install { step ->
                installStep = step
            }
            installStep = null
            if (problem != null) return@LaunchedEffect
            ai.eight24family.conch.linux.LinuxSsh.ensureRow()
        }
        // The watcher is what turns Android's pairing dialog into a code box in
        // the shade. Armed on ENTRY, not on a button, because the dialog may
        // already be open — the user's last attempt, or a second run of this —
        // and the code only exists while it is. `start` is idempotent and this
        // re-arms it: its own window closes after three minutes, and the flow
        // can be on screen for longer.
        while (true) {
            PairingWatcher.start(ctx)
            val looked = PhoneBridgeSetup.look()
            val s = if (pastWifi && looked.step == PhoneBridgeSetup.Step.WIFI) {
                looked.copy(step = PhoneBridgeSetup.Step.ARM)
            } else {
                looked
            }
            status = s
            if (s.step == PhoneBridgeSetup.Step.READY) {
                PairingWatcher.stop()
                PairingNotifier.clear(ctx)
                // ⭐ THE USER'S PART ENDED ONE TICK AGO. The environment needs
                // the shell only to START; from here it is the app's job, and
                // making someone tap "finish" for work they are not doing is
                // the wizard pretending to be busy.
                // ⛔ A SHELL IS NOT A MACHINE. On a phone that has never had the
                // environment unpacked, "wake" has nothing to wake: the row
                // that sent the user here would have said "no Linux
                // environment on this phone yet" and stopped, which is the same
                // dead end in different words.
                val presence = ai.eight24family.conch.linux.LinuxEnv.presence()
                if (presence == ai.eight24family.conch.linux.LinuxEnv.Presence.ABSENT) {
                    offerInstall = true
                    return@LaunchedEffect
                }
                if (presence == ai.eight24family.conch.linux.LinuxEnv.Presence.UNREACHABLE) {
                    // The shell went away between two ticks. Not a verdict —
                    // just look again.
                    delay(1_200)
                    continue
                }
                starting = true
                problem = null
                val dialled = SilentlyTry.logged("Conch-BridgeWizard", "start this phone's Linux") {
                    ServiceLocator.sshConnectionPool.ensureOwnDeviceUp()
                }
                starting = false
                // ⛔ "READY ✓" IS A CLAIM, AND IT HAS TO BE EARNED. A shell is
                // not a running machine: the daemon can fail to come up, and a
                // wizard that closes on a green tick over a dead endpoint sends
                // the user back to the row that sent them here.
                if (dialled !is ai.eight24family.conch.ssh.SshConnectionPool.Dialled.Up) {
                    // ⛔ AND IT DOES NOT LOOP ON IT EITHER. Retrying a daemon
                    // that just refused to start, every couple of seconds,
                    // spends the phone's battery on a verdict that will not
                    // change by itself — and each attempt is a 90-second wait
                    // on the port. Say what happened, and let the person decide
                    // to try again.
                    problem = (dialled as? ai.eight24family.conch.ssh.SshConnectionPool.Dialled.Down)
                        ?.why ?: "The Linux did not come up."
                    return@LaunchedEffect
                }
                done = true
                // Long enough to read "Ready ✓", short enough not to be a wait.
                delay(900)
                onFinished?.invoke()
                return@LaunchedEffect
            }
            delay(1_200)
        }
    }

    val step = status?.step
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            done -> Line("Ready ✓", cyan, bold = true)

            installStep != null -> Busy("Installing Linux — ${installStep.orEmpty()}…")

            offerInstall -> {
                Line("This phone has no Linux yet", fg, bold = true)
                Line(
                    "The whole system ships inside Conch — nothing is downloaded, and it " +
                        "touches nothing outside its own folder.",
                    muted,
                )
                Action("[ install it ]") {
                    offerInstall = false
                    installStep = "starting"
                    attempt++
                }
            }

            problem != null -> {
                Line("The shell is back, the Linux is not", fg, bold = true)
                Line(problem.orEmpty(), muted)
                Action("[ try again ]") { problem = null; attempt++ }
            }

            starting -> Busy("Starting Linux on this phone…")

            step == null -> Busy("Checking this phone…")

            step == PhoneBridgeSetup.Step.WIFI -> {
                Progress(1, status?.needsPairing == true)
                Line("Wi-Fi needs to be on", fg, bold = true)
                Line(
                    "Android hands the shell over only on Wi-Fi. A hotspot counts too — " +
                        "it does not need internet.",
                    muted,
                )
                Action("[ turn Wi-Fi on ]") { openWifi(ctx) }
                TextButton(onClick = { pastWifi = true }) {
                    Text(
                        "I'm sharing a hotspot — go on",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Watching()
            }

            step == PhoneBridgeSetup.Step.ARM -> {
                Progress(2, status?.needsPairing == true)
                Line("Turn on “Wireless debugging”", fg, bold = true)
                Line(
                    "The button opens the page it lives on. Android switches it off at every " +
                        "restart — that is its rule, and nothing on the phone can switch it " +
                        "back on.",
                    muted,
                )
                if (status?.needsPairing == true) {
                    Line(
                        "This phone does not know Conch yet, so tap “Pair device with " +
                            "pairing code” there as well — the six digits are asked for " +
                            "in a notification, over Android's own dialog.",
                        muted,
                    )
                }
                Action("[ open the page ]") {
                    // ORDER MATTERS: the watcher goes first, because Android's
                    // dialog can be open before we are asked again and the code
                    // only exists while it is.
                    PairingWatcher.start(ctx)
                    pageTaps++
                    openWirelessDebugging(ctx)
                }
                if (pageTaps > 1) {
                    // Naming a place is a last resort, and it is allowed here
                    // for one reason: the app tried and this phone said no. It
                    // is still a place ON this phone — never a computer.
                    Line(
                        "This phone would not open that page. It is in Settings → System → " +
                            "Developer options → Wireless debugging.",
                        muted,
                    )
                    Action("[ open Settings ]") {
                        SilentlyTry.fired("Conch-BridgeWizard", "open all settings") {
                            ctx.startActivity(
                                Intent(Settings.ACTION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
                Watching()
            }

            step == PhoneBridgeSetup.Step.PAIR -> {
                Progress(3, true)
                Line("Android is showing six digits", fg, bold = true)
                Line(
                    "Swipe the shade down over it — Conch put a box there. Type them in " +
                        "without closing Android's dialog: it cancels the pairing when it closes.",
                    muted,
                )
                // The shade is swipeable, so the box can be swiped away. Losing
                // it used to mean opening Android's dialog all over again.
                pairingPort?.let { port ->
                    Action("[ show the box again ]") { PairingNotifier.askForCode(ctx, port) }
                }
                Watching()
            }

            else -> Busy("Checking this phone…")
        }
    }
}

/** Where we are, in three words instead of a paragraph. The pairing step shows
 *  only for a phone that has not been paired — most runs are two steps. */
@Composable
private fun Progress(current: Int, withPairing: Boolean) {
    val cyan = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val labels = buildList {
        add("wi-fi")
        add("switch")
        if (withPairing) add("code")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            val n = i + 1
            Text(
                if (n < current) "✓ $label" else "$n $label",
                color = if (n == current) cyan else muted.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (n == current) FontWeight.Bold else FontWeight.Normal,
            )
            if (i != labels.lastIndex) {
                Text(
                    " · ",
                    color = muted.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
    Text(
        text,
        color = color,
        style = if (bold) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The promise that makes this a wizard and not a list of chores: nobody has to
 * come back and press anything. The app is looking, and the step changes by
 * itself the moment the phone does.
 */
@Composable
private fun Watching() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(11.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Conch is watching — this moves on by itself.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Get Wi-Fi on with as little of the user as the platform allows.
 *
 * ⭐ BELOW API 29 THE APP JUST DOES IT — one call, no screen, no step. From 29
 * Google took that away from every app, so the best available is the inline
 * panel: a sheet over Conch with the toggle on it, which keeps the user where
 * they are instead of dropping them into Settings and leaving them to find the
 * way back.
 */
private fun openWifi(ctx: Context) {
    val panel = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
        SilentlyTry.loggedOrElse("Conch-BridgeWizard", "turn wifi on", false) {
            val wm = ctx.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wm?.setWifiEnabled(true) == true
        }
    } else {
        SilentlyTry.loggedOrElse("Conch-BridgeWizard", "open the wifi panel", false) {
            ctx.startActivity(
                Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }
    }
    if (!panel) {
        SilentlyTry.fired("Conch-BridgeWizard", "open wifi settings") {
            ctx.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
