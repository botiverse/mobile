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
import build.raft.app.core.data.diagnostics.RaftAppLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service for Android devices without Google Play services (no FCM): it only keeps the process
 * in the foreground-service state so the OS does not freeze or kill it, which lets the existing app-scope
 * realtime connection ([AndroidAppScopeRealtimeOwner], reconnect allowed through its background keep-alive)
 * keep posting local message notifications. It never opens a connection of its own.
 *
 * Type `remoteMessaging` (API 34+, FOREGROUND_SERVICE_REMOTE_MESSAGING): receiving messages from a server
 * for the user; `dataSync` is time-limited on Android 15 and the wrong category.
 *
 * Started by [AndroidRealtimeKeepAliveController] while the app is visible (activity resume, or a Settings
 * change made in the app) or as it leaves the foreground (inside Android's foreground-to-background grace),
 * which every Android version allows; it then stays up in the background
 * until sign-out, the preference is turned off, or Play services shows up.
 */
class RaftBackgroundConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val systemRestart = intent == null
        if (intent != null && intent.action != ACTION_STOP) {
            // This command answers one startForegroundService request.
            pendingStarts.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        val hasSession = AndroidAppScopeRealtimeOwner.hasRealtimeOwnerSession()
        val decision =
            runCatching { AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(applicationContext) }
                .getOrElse { RaftBackgroundConnectionPolicy.Decision.Stop("decision_failed") }
        val outcome =
            RaftBackgroundConnectionPolicy.startCommandOutcome(
                isSystemRestart = systemRestart,
                hasRealtimeSession = hasSession,
                stopRequested = intent?.action == ACTION_STOP,
                decision = decision,
            )
        if (outcome is RaftBackgroundConnectionPolicy.StartCommandOutcome.StopWithoutForeground) {
            // A sticky restart lands in a fresh process: only the UI opens the realtime connection (the shared
            // realtime authority that grants it lives in the UI-hosted store), so there is nothing to keep alive
            // even when a session is stored. Stop before startForeground (a restart is not a foreground-service
            // start); the next activity resume or background transition requests the service again.
            // has_session is the in-memory realtime owner, stored_session the persisted sign-in.
            val storedSession =
                runCatching { AndroidRealtimeSessionAvailability.hasStoredRealtimeSession(applicationContext) }
                    .getOrDefault(false)
            RaftAppLog.w(
                LOG_TAG,
                "background_connection_service_restart_stop has_session=$hasSession stored_session=$storedSession " +
                    "decision=$decision"
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A startForegroundService start is pending: startForeground must come first, before anything that
        // could decide to stop, or Android kills the app ("did not then call Service.startForeground()").
        if (!promoteToForeground()) {
            // Residual risk: if startForeground itself threw, the system may still raise the foreground
            // timeout crash for this start; nothing else can satisfy it.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (outcome is RaftBackgroundConnectionPolicy.StartCommandOutcome.PromoteThenStop) {
            RaftAppLog.w(LOG_TAG, "background_connection_service_start_then_stop reason=${outcome.reason}")
            if (stopSelfResult(startId)) {
                running = false
            }
            return START_NOT_STICKY
        }
        running = true
        RaftAppLog.w(
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

    /** Posts the foreground notification. Never throws; false when the service could not be promoted. */
    private fun promoteToForeground(): Boolean {
        val notification =
            runCatching {
                ensureChannel(this)
                buildNotification(this)
            }.getOrElse { error ->
                RaftAppLog.w(
                    LOG_TAG,
                    "background_connection_service_notification_failed " +
                        "errorType=${error::class.simpleName ?: "unknown"}"
                )
                // Minimal notification so startForeground can still be called.
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .setSilent(true)
                    .build()
            }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (error: Exception) {
            // ForegroundServiceStartNotAllowedException (background start without an exemption) or a
            // SecurityException (missing FOREGROUND_SERVICE_* permission): give up, the next resume retries.
            RaftAppLog.w(
                LOG_TAG,
                "background_connection_service_start_failed errorType=${error::class.simpleName ?: "unknown"} " +
                    "message=${error.message.orEmpty()}"
            )
            false
        }
    }

    override fun onDestroy() {
        running = false
        pendingStarts.set(0)
        stopQueued = false
        RaftAppLog.w(LOG_TAG, "background_connection_service_destroyed")
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "RaftBackgroundConnection"
        internal const val CHANNEL_ID = "raft-background-connection-v1"
        private const val NOTIFICATION_ID = 0x52414654 // "RAFT"
        private const val EXTRA_SOURCE = "source"
        private const val ACTION_STOP = "build.raft.app.action.STOP_BACKGROUND_CONNECTION"

        @Volatile
        internal var running: Boolean = false
            private set

        /**
         * A `startForegroundService` was issued and `onStartCommand` has not run yet. While true the service
         * must not be stopped with `stopService` (Android would crash the app); a stop action is sent instead.
         */
        private val pendingStarts = AtomicInteger(0)

        internal val startPending: Boolean
            get() = pendingStarts.get() > 0

        /** A stop action was queued behind a pending start; a later start request supersedes it. */
        @Volatile
        internal var stopQueued: Boolean = false
            private set

        /** Running, or about to run: a start request is still waiting for `onStartCommand`. */
        internal val active: Boolean
            get() = running || startPending

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

        /**
         * Must be called while the app is visible or has just left the foreground: other background starts are
         * refused on Android 12+ (the request then fails and is logged).
         */
        internal fun start(context: Context, source: String) {
            val appContext = context.applicationContext
            pendingStarts.incrementAndGet()
            stopQueued = false
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, RaftBackgroundConnectionService::class.java)
                        .putExtra(EXTRA_SOURCE, source)
                )
            }.onFailure { error ->
                pendingStarts.updateAndGet { (it - 1).coerceAtLeast(0) }
                RaftAppLog.w(
                    LOG_TAG,
                    "background_connection_service_start_request_failed source=$source " +
                        "errorType=${error::class.simpleName ?: "unknown"} message=${error.message.orEmpty()}"
                )
            }
        }

        internal fun stop(context: Context, reason: String) {
            val appContext = context.applicationContext
            val wasRunning = running
            if (startPending) {
                stopQueued = true
                // stopService now would destroy the service before it called startForeground, which crashes
                // the app. Queue a stop action behind the pending start instead: onStartCommand promotes to
                // the foreground first and then stops. (startService is allowed from the background here
                // because the service already has a start requested.)
                val requested =
                    runCatching {
                        appContext.startService(
                            Intent(appContext, RaftBackgroundConnectionService::class.java)
                                .setAction(ACTION_STOP)
                                .putExtra(EXTRA_SOURCE, reason)
                        )
                    }.onFailure { error ->
                        // The pending onStartCommand still re-evaluates the policy and stops when it says so.
                        RaftAppLog.w(
                            LOG_TAG,
                            "background_connection_service_stop_request_failed reason=$reason " +
                                "errorType=${error::class.simpleName ?: "unknown"}"
                        )
                    }.isSuccess
                RaftAppLog.w(LOG_TAG, "background_connection_service_stop_pending reason=$reason requested=$requested")
                return
            }
            runCatching {
                appContext.stopService(Intent(appContext, RaftBackgroundConnectionService::class.java))
            }
            if (wasRunning) {
                RaftAppLog.w(LOG_TAG, "background_connection_service_stop reason=$reason")
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
                    putExtra(EXTRA_RAFT_APP_OPEN_SOURCE, RAFT_APP_OPEN_SOURCE_APP_NOTIFICATION)
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
