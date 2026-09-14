package dev.mobileforge.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.mobileforge.MainActivity
import dev.mobileforge.R

/**
 * Keeps user-started processes alive while the IDE is backgrounded.
 *
 * RISK-002: Android aggressively reclaims background processes, and a `php artisan serve` that
 * dies the moment the user checks a message makes the IDE untrustworthy. A foreground service
 * with a visible notification is the platform's sanctioned way to say "this work is
 * user-initiated and ongoing".
 *
 * It is started only when a long-running process actually exists and stopped as soon as the
 * last one ends: a permanent notification for an idle IDE would be user-hostile, and the
 * platform would be right to penalise it.
 *
 * This does NOT make processes immortal. Under real memory pressure Android will still kill
 * them, which is exactly why the process manager reports [ProcessStatus.KilledBySystem] rather
 * than pretending otherwise.
 */
class RuntimeForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_PROCESS_COUNT, 0) ?: 0
        val summary = intent?.getStringExtra(EXTRA_SUMMARY).orEmpty()

        if (count <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(count, summary))

        // NOT sticky: if Android kills us, silently restarting the service without the
        // processes it was tracking would show a notification for work that is not happening.
        return START_NOT_STICKY
    }

    private fun buildNotification(count: Int, summary: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = if (count == 1) "1 process running" else "$count processes running"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(summary.ifEmpty { "MobileForge is running development tools." })
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .setOngoing(true)
            // Silent: a build server starting is not worth a sound or a heads-up popup.
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Running processes",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while development servers or commands are running."
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "mobileforge.runtime"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_PROCESS_COUNT = "process_count"
        private const val EXTRA_SUMMARY = "summary"

        /**
         * Reflects the current process count into the notification.
         *
         * Passing zero stops the service, so callers can drive this from a single
         * "processes changed" signal without tracking start/stop themselves.
         */
        fun sync(context: Context, processCount: Int, summary: String) {
            val intent = Intent(context, RuntimeForegroundService::class.java)
                .putExtra(EXTRA_PROCESS_COUNT, processCount)
                .putExtra(EXTRA_SUMMARY, summary)

            if (processCount <= 0) {
                context.stopService(intent)
                return
            }

            // No SDK_INT guard: minSdk is 30, so startForegroundService always exists.
            context.startForegroundService(intent)
        }
    }
}
