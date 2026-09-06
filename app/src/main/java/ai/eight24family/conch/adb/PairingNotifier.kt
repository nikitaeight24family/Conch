package ai.eight24family.conch.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import ai.eight24family.conch.R
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Asking for the pairing code WITHOUT taking the screen away from it.
 *
 * ⛔ THE PROBLEM THIS EXISTS TO SOLVE. Android shows the six digits inside its
 * own dialog, and that dialog cancels the pairing the instant it closes. So an
 * input field on our own screen is unusable by construction: to reach it the
 * user has to leave the dialog, which throws the code away. Telling people to
 * arrange a split screen is not a flow anyone will follow (owner, 2026-08-29,
 * and he was right to be blunt about it).
 *
 * A notification is the way out. The shade draws OVER the settings dialog
 * without dismissing it, and a direct-reply action gives a text field right
 * there. The user reads the code and types it two centimetres below, never
 * leaving the screen that shows it.
 *
 * The port is not asked for at all: while that dialog is open the phone
 * advertises `_adb-tls-pairing._tcp` over mDNS, so [PairingWatcher] finds it and
 * the notification appears by itself the moment the dialog does.
 */
object PairingNotifier {

    private const val CHANNEL_ID = "conch_phone_pairing"
    private const val NOTIFICATION_ID = 4711
    const val EXTRA_PORT = "port"
    const val KEY_CODE = "pairing_code"

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Phone pairing",
                // HIGH so it arrives as a heads-up over the settings dialog. This
                // notification exists for exactly the seconds a code is on
                // screen; arriving quietly would make it useless.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Asks for the six-digit code while Android is showing it"
                setShowBadge(false)
            },
        )
    }

    /** Show the input, bound to the port the dialog is currently advertising. */
    fun askForCode(context: Context, port: Int) {
        ensureChannel(context)
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("Six digits")
            .build()
        val intent = Intent(context, PairingReplyReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(EXTRA_PORT, port)
        val pending = PendingIntent.getBroadcast(
            context,
            port, // distinct per port so a new dialog cannot reuse a stale extra
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_agent_claude, "Pair", pending,
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(false).build()

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_claude)
            .setContentTitle("Enter the pairing code")
            .setContentText("Type the six digits Android is showing — no need to leave that screen.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(false)
            .setAutoCancel(false)
            .addAction(action)
            .build()
        SilentlyTry.fired("Conch-Pairing", "post pairing notification") {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        }
    }

    /**
     * Replace the input with the outcome.
     *
     * Tapping it opens the app — which matters most in the successful case: the
     * user is standing in Android's Settings when it arrives, and this is the
     * one tap that gets them back to what they were doing. [openApp] below tries
     * to do it for them; this is what remains when the system says no.
     */
    fun report(context: Context, title: String, body: String) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_claude)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    appIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .build()
        SilentlyTry.fired("Conch-Pairing", "post pairing result") {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        }
    }

    private fun appIntent(context: Context) =
        Intent(context, ai.eight24family.conch.MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /**
     * Bring Conch back to the front, so the pairing ends where it started.
     *
     * ⚠ BEST EFFORT, AND THE FALLBACK IS NOT OPTIONAL. Android blocks activity
     * starts from the background, with exemptions — and this case fits one: the
     * app has a task with a back stack sitting in Recents, because the user was
     * in it moments ago on their way here. That is why this is worth trying at
     * all. It is still the platform's decision, it varies by OEM, and a refusal
     * is silent, so [report]'s tap target stays the guaranteed path and the
     * return is re-attempted whenever the app next comes forward by any route.
     *
     * Nothing here is destructive: at worst the app does not come forward and
     * the user taps the notification.
     */
    fun openApp(context: Context) {
        SilentlyTry.fired("Conch-Pairing", "bring the app back after pairing") {
            context.startActivity(appIntent(context))
        }
    }

    fun clear(context: Context) {
        SilentlyTry.fired("Conch-Pairing", "clear pairing notification") {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}

/**
 * Watches for Android's pairing dialog and raises the input the moment it opens.
 *
 * Started when the user asks to pair, and it stops itself: after a success, or
 * after [WINDOW_MS] with nothing found. A watcher that ran forever would be a
 * background mDNS loop nobody asked for.
 */
object PairingWatcher {

    /** How long to wait for the dialog before giving up. */
    private const val WINDOW_MS = 3 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var lastPort: Int? = null

    /**
     * The port Android's pairing dialog is advertising right now, or null.
     *
     * ⭐ PUBLISHED SO NOBODY ASKS mDNS TWICE. The wizard needs the same fact
     * this loop already learns every 1.5 s, and a second lookup of its own
     * would pay the full 6 s discovery timeout on every tick where no dialog
     * is open — which is nearly all of them. One asker, one answer, and the
     * screen advances the instant the notification does.
     */
    private val _livePort = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val livePort: kotlinx.coroutines.flow.StateFlow<Int?> = _livePort

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            val until = System.currentTimeMillis() + WINDOW_MS
            while (isActive && System.currentTimeMillis() < until) {
                val port = PhoneBridgePairing.findPairingPort(app)
                _livePort.value = port
                if (port != null && port != lastPort) {
                    lastPort = port
                    android.util.Log.i("Conch-Pairing", "pairing dialog is open on port $port")
                    PairingNotifier.askForCode(app, port)
                }
                delay(1_500)
            }
            _livePort.value = null
            android.util.Log.i("Conch-Pairing", "pairing window closed")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastPort = null
        _livePort.value = null
    }
}

/** Receives the typed code from the notification and does the pairing. */
class PairingReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PORT, 0)
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotifier.KEY_CODE)?.toString()?.filter(Char::isDigit)
        if (port <= 0 || code.isNullOrEmpty()) {
            PairingNotifier.report(context, "Nothing to pair with", "No code was entered.")
            return
        }
        val app = context.applicationContext
        // goAsync would cap us at ~10 s; pairing is a TLS handshake plus a key
        // exchange and can outlive that on a cold radio. The app's own scope
        // carries it instead, and the notification reports the outcome.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            when (val r = PhoneBridgePairing.pair(app, port, code)) {
                is PhoneBridgePairing.Outcome.Paired -> {
                    PairingWatcher.stop()
                    // The phone just accepted us — do not sit out a backoff that
                    // was started by the pre-pairing failures, and OPEN the
                    // connection here rather than waiting to be asked: adbd needs
                    // a moment after pairing to advertise its connect service, and
                    // nothing else in the app opens a session on its own.
                    LocalAdbShell.retryNow()
                    val deadline = System.currentTimeMillis() + 15_000
                    while (System.currentTimeMillis() < deadline) {
                        if (LocalAdbShell.check(userInitiated = true)) break
                        kotlinx.coroutines.delay(1_500)
                    }
                    // ORDER: arm the return BEFORE the app can possibly come
                    // forward, or it would arrive with nothing to act on.
                    val fromChat = ai.eight24family.conch.ui.navigation.PhoneBridgeReturn.someoneIsWaiting()
                    ai.eight24family.conch.ui.navigation.PhoneBridgeReturn.paired()
                    PairingNotifier.report(
                        app,
                        "Phone paired ✓",
                        if (fromChat) {
                            "Taking you back to your chat — tap here if it does not come forward."
                        } else {
                            "Opening Conch — tap here if it does not come forward. You will not " +
                                "need a code again."
                        },
                    )
                    // ⛔ ALWAYS, not only when a chat is waiting. The user is standing
                    // in Android's Settings having just finished a job for THIS app
                    PairingNotifier.openApp(app)
                }
                PhoneBridgePairing.Outcome.WrongCode -> PairingNotifier.report(
                    app,
                    "That code did not match",
                    "Open the pairing dialog again — the code changes every time.",
                )
                PhoneBridgePairing.Outcome.NoPairingDialog -> PairingNotifier.report(
                    app,
                    "The pairing dialog closed",
                    "Android cancels pairing when its dialog is dismissed. Open it again.",
                )
                is PhoneBridgePairing.Outcome.Failed -> PairingNotifier.report(
                    app, "Pairing failed", r.reason,
                )
            }
        }
    }
}
