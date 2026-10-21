package build.raft.app.shared

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import build.raft.app.R
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.downloads.RaftDownloadCenter
import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.downloads.RaftDownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Android face of the shared download centre (`RaftDownloadCenterProcess`): one ongoing
 * progress notification while downloads run, and a notification per download that finishes
 * or fails while it was running. Tapping a finished one opens the file (its Downloads URI);
 * the others open the app.
 *
 * While anything runs, [RaftDownloadForegroundService] (type `dataSync`) keeps the process alive
 * so a download continues after the user leaves the app. The Play build removes that service
 * (its manifest, like the background connection's, needs no Play Console FGS declaration):
 * there the notification is posted plainly and the download lives as long as Android keeps the
 * process.
 *
 * Notifications need POST_NOTIFICATIONS (Android 13+); without it nothing is posted, and the
 * foreground service still runs (Android allows it, the notification is just not shown).
 */
internal object RaftDownloadNotifications {
    private const val LOG_TAG = "RaftDownloadNotifications"
    internal const val CHANNEL_ID = "raft-downloads-v1"
    internal const val PROGRESS_NOTIFICATION_ID = 0x52444C50 // "RDLP"
    private const val FINISHED_NOTIFICATION_BASE = 0x52444C00

    @Volatile
    private var latestProgress: Notification? = null

    @Volatile
    internal var hasActiveDownloads: Boolean = false
        private set

    private var attached = false

    /** Main thread, once (from `RaftDownloadCenterProcess.onCreated`). */
    fun attach(context: Context, center: RaftDownloadCenter) {
        if (attached) return
        attached = true
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var previous: Map<String, RaftDownloadStatus> = center.state.value.entries.associate { it.id to it.status }
            center.state
                .map { it.entries }
                .distinctUntilChanged { old, new -> old.notificationSignature() == new.notificationSignature() }
                .collect { entries ->
                    runCatching { render(appContext, entries, previous) }
                        .onFailure { error ->
                            RaftAppLog.w(LOG_TAG, "download_notification_failed errorType=${error::class.simpleName}")
                        }
                    previous = entries.associate { it.id to it.status }
                }
        }
    }

    /** The progress notification the service promotes with (built before it is started). */
    internal fun currentProgressNotification(context: Context): Notification =
        latestProgress ?: progressBuilder(context, emptyList()).build()

    private fun render(context: Context, entries: List<RaftDownloadEntry>, previous: Map<String, RaftDownloadStatus>) {
        val active = entries.filter { it.status.isActive }
        val manager = NotificationManagerCompat.from(context)
        val canPost = AndroidUnreadNotificationPoster.hasNotificationPermission(context)
        if (canPost) ensureChannel(context)
        hasActiveDownloads = active.isNotEmpty()
        if (active.isNotEmpty()) {
            val notification = progressBuilder(context, active).build()
            latestProgress = notification
            if (canPost) notifySafely(manager, PROGRESS_NOTIFICATION_ID, notification)
            RaftDownloadForegroundService.ensureRunning(context)
        } else {
            latestProgress = null
            RaftDownloadForegroundService.stopIfIdle()
            manager.cancel(PROGRESS_NOTIFICATION_ID)
        }
        if (!canPost) return
        entries.forEach { entry ->
            val was = previous[entry.id] ?: return@forEach
            if (!was.isActive) return@forEach
            when (entry.status) {
                RaftDownloadStatus.Completed -> notifySafely(manager, finishedId(entry), finishedNotification(context, entry, failed = false))
                RaftDownloadStatus.Failed -> notifySafely(manager, finishedId(entry), finishedNotification(context, entry, failed = true))
                else -> Unit
            }
        }
    }

    private fun progressBuilder(context: Context, active: List<RaftDownloadEntry>): NotificationCompat.Builder {
        val single = active.singleOrNull()
        val title =
            if (single != null || active.isEmpty()) {
                context.getString(R.string.raft_download_notification_downloading, single?.fileName.orEmpty())
            } else {
                context.getString(R.string.raft_download_notification_more, active.size)
            }
        val total = active.sumOf { it.totalBytes ?: 0L }
        val done = active.sumOf { entry -> entry.totalBytes?.let { entry.downloadedBytes.coerceAtMost(it) } ?: 0L }
        val determinate = active.isNotEmpty() && active.all { (it.totalBytes ?: 0L) > 0L } && total > 0L
        val percent = if (determinate) ((done * 100L) / total).toInt() else 0
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (determinate) "$percent%" else null)
            .setProgress(100, percent, !determinate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchAppIntent(context, PROGRESS_NOTIFICATION_ID))
    }

    private fun finishedNotification(context: Context, entry: RaftDownloadEntry, failed: Boolean): Notification {
        val openFile = if (failed) null else entry.localUri?.let { openFileIntent(context, entry, it) }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.getString(
                    if (failed) R.string.raft_download_notification_failed else R.string.raft_download_notification_completed,
                    entry.fileName
                )
            )
            .setContentText(
                context.getString(
                    if (failed) R.string.raft_download_notification_tap_retry else R.string.raft_download_notification_tap_open
                )
            )
            .setAutoCancel(true)
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openFile ?: launchAppIntent(context, finishedId(entry)))
            .build()
    }

    private fun openFileIntent(context: Context, entry: RaftDownloadEntry, uri: String): PendingIntent? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()?.takeIf { it.scheme == "content" } ?: return null
        val view =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(parsed, entry.mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (view.resolveActivity(context.packageManager) == null) return null
        return PendingIntent.getActivity(
            context,
            finishedId(entry),
            view,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun launchAppIntent(context: Context, requestCode: Int): PendingIntent? {
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                putExtra(EXTRA_RAFT_APP_OPEN_SOURCE, RAFT_APP_OPEN_SOURCE_APP_NOTIFICATION)
            } ?: return null
        return PendingIntent.getActivity(
            context,
            requestCode,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @SuppressLint("MissingPermission") // hasNotificationPermission is checked by the caller.
    private fun notifySafely(manager: NotificationManagerCompat, id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }

    private fun finishedId(entry: RaftDownloadEntry): Int = FINISHED_NOTIFICATION_BASE xor (entry.id.hashCode() and 0x7FFFFF00)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.raft_download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.raft_download_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    /** Whole percent and status per row: progress samples inside the same percent do not re-post. */
    private fun List<RaftDownloadEntry>.notificationSignature(): List<String> =
        map { "${it.id}|${it.status}|${it.percent ?: (it.downloadedBytes shr 20)}|${it.saving}" }
}

/**
 * Keeps the process in the foreground-service state while the download centre has an active
 * download, so leaving the app does not freeze or kill the transfer. Type `dataSync`
 * (FOREGROUND_SERVICE_DATA_SYNC): a user-started transfer of their own files. Android 15 caps
 * dataSync at 6 hours a day; [onTimeout] stops it and the download continues while the process
 * lives.
 *
 * Never stopped with `stopService` (a stop before `onStartCommand` would crash the app): the
 * service stops itself after promoting when nothing is active.
 */
class RaftDownloadForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPending = false
        val promoted =
            try {
                val notification = RaftDownloadNotifications.currentProgressNotification(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        RaftDownloadNotifications.PROGRESS_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(RaftDownloadNotifications.PROGRESS_NOTIFICATION_ID, notification)
                }
                true
            } catch (error: Exception) {
                RaftAppLog.w(LOG_TAG, "download_service_start_failed errorType=${error::class.simpleName}")
                false
            }
        if (!promoted || !RaftDownloadNotifications.hasActiveDownloads) {
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        RaftAppLog.w(LOG_TAG, "download_service_timeout")
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "RaftDownloadService"

        @Volatile
        private var instance: RaftDownloadForegroundService? = null

        @Volatile
        private var startPending = false

        @Volatile
        private var declared: Boolean? = null

        /** False in the Play build, whose manifest removes this service. */
        private fun isDeclared(context: Context): Boolean =
            declared ?: runCatching {
                context.packageManager.getServiceInfo(ComponentName(context, RaftDownloadForegroundService::class.java), 0)
                true
            }.getOrDefault(false).also { declared = it }

        /** Downloads start from the visible app, where a foreground-service start is allowed. */
        internal fun ensureRunning(context: Context) {
            val appContext = context.applicationContext
            if (instance != null || startPending || !isDeclared(appContext)) return
            startPending = true
            runCatching {
                ContextCompat.startForegroundService(appContext, Intent(appContext, RaftDownloadForegroundService::class.java))
            }.onFailure { error ->
                startPending = false
                RaftAppLog.w(LOG_TAG, "download_service_start_request_failed errorType=${error::class.simpleName}")
            }
        }

        /** A pending start stops itself in onStartCommand once it has promoted. */
        internal fun stopIfIdle() {
            val running = instance ?: return
            if (RaftDownloadNotifications.hasActiveDownloads) return
            running.stopForeground(STOP_FOREGROUND_REMOVE)
            running.stopSelf()
        }
    }
}
