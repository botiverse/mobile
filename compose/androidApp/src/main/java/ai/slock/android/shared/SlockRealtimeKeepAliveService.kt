package ai.slock.android.shared

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.slock.android.MainActivity
import ai.slock.android.R
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger

internal class SlockRealtimeKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val reason = intent.getStringExtra(EXTRA_REASON).takeUnless { it.isNullOrBlank() } ?: "service_stop"
                AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive(reason)
                stopForegroundCompat()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            else -> {
                val source = intent?.getStringExtra(EXTRA_SOURCE).takeUnless { it.isNullOrBlank() } ?: "service_start"
                if (!AndroidRealtimeKeepAliveController.isForegroundKeepAliveEnabled(applicationContext)) {
                    AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("preference_disabled")
                    satisfyForegroundStartContractBeforeStop(source, "preference_disabled")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!AndroidRealtimeSessionAvailability.hasStoredRealtimeSession(applicationContext)) {
                    AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("missing_session")
                    satisfyForegroundStartContractBeforeStop(source, "missing_session")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                return startKeepAliveForeground(source, startId)
            }
        }
    }

    override fun onDestroy() {
        AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("service_destroy")
        super.onDestroy()
    }

    private fun startKeepAliveForeground(source: String, startId: Int): Int {
        ensureNotificationChannel()
        return runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
            AndroidAppScopeRealtimeOwner.install(this)
            AndroidAppScopeRealtimeOwner.enableBackgroundKeepAlive(
                context = applicationContext,
                source = source
            )
            SlockDiagnosticsLogger.d(LOG_TAG, "foreground_service_started source=$source")
            START_STICKY
        }.getOrElse { error ->
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "foreground_service_start_failed source=$source message=${error.message.orEmpty()}"
            )
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    private fun satisfyForegroundStartContractBeforeStop(source: String, reason: String) {
        ensureNotificationChannel()
        runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
            stopForegroundCompat()
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "foreground_service_start_satisfied_before_stop source=$source reason=$reason"
            )
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "foreground_service_start_satisfy_failed source=$source reason=$reason message=${error.message.orEmpty()}"
            )
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?.setClass(this, MainActivity::class.java)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?.let { intent ->
                    PendingIntent.getActivity(
                        this,
                        NOTIFICATION_ID,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notification_realtime_keep_alive_title))
            .setContentText(getString(R.string.notification_realtime_keep_alive_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.notification_realtime_keep_alive_text))
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_realtime_keep_alive_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_realtime_keep_alive_channel_description)
                enableVibration(false)
            }
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "ai.slock.android.action.START_REALTIME_KEEP_ALIVE"
        const val ACTION_STOP = "ai.slock.android.action.STOP_REALTIME_KEEP_ALIVE"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_REASON = "reason"

        private const val LOG_TAG = "SlockRealtimeKeepAlive"
        private const val CHANNEL_ID = "slock-realtime-keepalive-v1"
        private const val NOTIFICATION_ID = 0x51_0C_41
    }
}
