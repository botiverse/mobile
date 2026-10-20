package build.raft.app.shared

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import build.raft.app.R
import build.raft.app.core.data.diagnostics.SlockAppLog

/**
 * Foreground service for Android devices without Google Play services (no FCM): it only keeps the process
 * in the foreground-service state so the OS does not freeze or kill it, which lets the existing app-scope
 * realtime connection ([AndroidAppScopeRealtimeOwner], reconnect allowed through its background keep-alive)
 * keep posting local message notifications. It never opens a connection of its own.
 *
 * Type `remoteMessaging` (API 34+, FOREGROUND_SERVICE_REMOTE_MESSAGING): receiving messages from a server
 * for the user; `dataSync` is time-limited on Android 15 and the wrong category.
 *
 * Started by [AndroidRealtimeKeepAliveController] only while the app is visible (activity resume, or a
 * Settings change made in the app), which every Android version allows; it then stays up in the background
 * until sign-out, the preference is turned off, or Play services shows up.
 */
class RaftBackgroundConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val systemRestart = intent == null
        val hasSession = AndroidAppScopeRealtimeOwner.hasRealtimeOwnerSession()
        if (!RaftBackgroundConnectionPolicy.shouldKeepRunningAfterServiceStart(systemRestart, hasSession)) {
            // A sticky restart lands in a fresh process: only the UI opens the realtime connection, so there is
            // nothing to keep alive. Stop before startForeground (a restart is not a foreground-service start).
            SlockAppLog.w(LOG_TAG, "background_connection_service_restart_stop reason=no_realtime_session")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val started =
            runCatching {
                ensureChannel(this)
                val notification = buildNotification(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }.onFailure { error ->
                // ForegroundServiceStartNotAllowedException (background start without an exemption) or a
                // missing permission: give up quietly, the next foreground resume retries.
                SlockAppLog.w(
                    LOG_TAG,
                    "background_connection_service_start_failed errorType=${error::class.simpleName ?: "unknown"} " +
                        "message=${error.message.orEmpty()}"
                )
            }.isSuccess
        if (!started) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        SlockAppLog.i(
            LOG_TAG,
            "background_connection_service_started restart=$systemRestart has_session=$hasSession " +
                "source=${intent?.getStringExtra(EXTRA_SOURCE).orEmpty()}"
        )
        AndroidAppScopeRealtimeOwner.enableBackgroundKeepAlive(
            context = applicationContext,
            source = "background_connection_service"
        )
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        SlockAppLog.i(LOG_TAG, "background_connection_service_destroyed")
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "RaftBackgroundConnection"
        internal const val CHANNEL_ID = "raft-background-connection-v1"
        private const val NOTIFICATION_ID = 0x52414654 // "RAFT"
        private const val EXTRA_SOURCE = "source"

        @Volatile
        internal var running: Boolean = false
            private set

        @Volatile
        private var declared: Boolean? = null

        /** False in the Play build, whose manifest removes this service. */
        internal fun isDeclared(context: Context): Boolean =
            declared ?: runCatching {
                val appContext = context.applicationContext
                appContext.packageManager.getServiceInfo(
                    ComponentName(appContext, RaftBackgroundConnectionService::class.java),
                    0
                )
                true
            }.getOrDefault(false).also { declared = it }

        /** Must be called while the app is visible: background starts are refused on Android 12+. */
        internal fun start(context: Context, source: String) {
            val appContext = context.applicationContext
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, RaftBackgroundConnectionService::class.java)
                        .putExtra(EXTRA_SOURCE, source)
                )
            }.onFailure { error ->
                SlockAppLog.w(
                    LOG_TAG,
                    "background_connection_service_start_request_failed source=$source " +
                        "errorType=${error::class.simpleName ?: "unknown"} message=${error.message.orEmpty()}"
                )
            }
        }

        internal fun stop(context: Context, reason: String) {
            val appContext = context.applicationContext
            val wasRunning = running
            runCatching {
                appContext.stopService(Intent(appContext, RaftBackgroundConnectionService::class.java))
            }
            if (wasRunning) {
                SlockAppLog.i(LOG_TAG, "background_connection_service_stop reason=$reason")
            }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.raft_background_connection_channel_name),
                    // Silent and collapsed (as Element's "listening for events"): no sound, no status-bar icon.
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = context.getString(R.string.raft_background_connection_channel_description)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }

        private fun buildNotification(context: Context): Notification {
            val launchIntent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            val contentIntent =
                launchIntent?.let {
                    PendingIntent.getActivity(
                        context,
                        NOTIFICATION_ID,
                        it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.raft_background_connection_notification_title))
                .setContentText(context.getString(R.string.raft_background_connection_notification_text))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent)
                .build()
        }
    }
}
