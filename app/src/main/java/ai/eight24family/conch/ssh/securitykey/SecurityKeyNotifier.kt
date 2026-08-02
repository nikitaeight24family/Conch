package ai.eight24family.conch.ssh.securitykey

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.eight24family.conch.MainActivity
import ai.eight24family.conch.R
import ai.eight24family.conch.data.prefs.SkNotificationVisibility
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.SecurityKeyTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Heads-up notification that nudges the user to plug in / tap their
 * security key while the touch dialog is waiting. Without this, a user
 * who switches to Telegram while the chat is opening (and then has the
 * SK touch flow fire) would never see the prompt — the dialog is alive
 * inside our process but the screen is showing somebody else's app.
 *
 * Channel imported with `IMPORTANCE_HIGH` so the system surfaces it as
 * a banner across the top of whatever the user is currently looking
 * at, with a one-shot vibration. Auto-dismissed only via [cancel] when
 * the touch flow leaves the waiting state.
 */
object SecurityKeyNotifier {

    private const val CHANNEL_ID = "sshai_security_key"
    private const val NOTIF_ID = 1003

    enum class Reason { REGISTER, CONNECT }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Security key prompts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Heads-up reminder to plug in or tap your hardware security key"
            enableVibration(true)
        }
        mgr.createNotificationChannel(ch)
    }

    fun post(
        context: Context,
        reason: Reason,
        transport: SecurityKeyTransport,
        target: String? = null,
    ) {
        val visibilityPref = runBlocking {
            ServiceLocator.preferences.skNotificationVisibility.first()
        }
        if (visibilityPref == SkNotificationVisibility.SECRET) {
            Log.d("SecurityKeyNotifier", "skipping SK notification per user preference")
            return
        }
        val mappedVisibility = when (visibilityPref) {
            SkNotificationVisibility.PUBLIC -> NotificationCompat.VISIBILITY_PUBLIC
            SkNotificationVisibility.PRIVATE -> NotificationCompat.VISIBILITY_PRIVATE
            SkNotificationVisibility.SECRET -> NotificationCompat.VISIBILITY_SECRET  // unreachable: early-returned above
        }
        ensureChannel(context)
        val title = when (reason) {
            Reason.REGISTER -> "Add security key"
            Reason.CONNECT -> "Authenticate to ${target ?: "your server"}"
        }
        val text = when (transport) {
            SecurityKeyTransport.USB -> "Plug your security key into USB-C, then tap it."
            SecurityKeyTransport.NFC -> "Hold your security key against the back of the phone."
            SecurityKeyTransport.EITHER -> "Plug in via USB or hold against the back of the phone."
        }
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(mappedVisibility)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOngoing(true)  // user can't swipe-dismiss; we cancel on flow exit
            .setTimeoutAfter(60_000L)  // belt-and-suspenders: auto-dismiss if caller forgets cancel()
            .build()
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, n)
    }

    fun cancel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(NOTIF_ID)
    }
}
