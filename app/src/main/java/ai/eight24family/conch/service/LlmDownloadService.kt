package ai.eight24family.conch.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import ai.eight24family.conch.R
import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.PhoneResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a model download alive when the user pockets the phone.
 *
 * A 2 GB file on mobile data outlives any screen, and without a foreground
 * service it dies with the app process — the `.part` resume made that safe,
 * but "safe" is not "finished": the store user expects a download they
 * started to complete, not to wait for them to reopen the app. This service
 * pins the process while [LocalLlm.progress] has entries and shows the one
 * notification Android requires anyway as live progress. When the last
 * download ends — done, failed, or cancelled — it stops itself and the
 * notification goes with it.
 *
 * Deliberately dumb: the DOWNLOADER lives in [LocalLlm] and does not know
 * this service exists. This is a process-lifetime pin with a progress face,
 * nothing more — no state of its own, no commands besides "a download
 * started".
 */
class LlmDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val first = notification("starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, first)
        }
        if (watch == null) {
            watch = scope.launch {
                LocalLlm.progress.collect { live ->
                    if (live.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val text = live.entries.joinToString("  ·  ") { (id, bytes) ->
                            val m = LocalLlm.byId(id)
                            val label = m?.label ?: id
                            val total = m?.bytes?.let { " of ${PhoneResources.gb(it)}" } ?: ""
                            val rate = LocalLlm.speed.value[id]
                                ?.let { " · ${PhoneResources.rate(it)}" } ?: ""
                            "$label ${PhoneResources.gb(bytes)}$total GB$rate"
                        }
                        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        mgr.notify(NOTIF_ID, notification(text))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading model")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "llm_downloads"
        private const val NOTIF_ID = 41

        private fun ensureChannel(context: Context) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Model downloads",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Progress of local model downloads"
                        setShowBadge(false)
                    },
                )
            }
        }

        /** Called by [LocalLlm.startDownload] — always from a user tap, so the
         *  foreground-start restriction never applies. */
        fun start(context: Context) {
            val intent = Intent(context, LlmDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
