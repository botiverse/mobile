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
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import build.raft.app.MainActivity
import build.raft.app.R
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.downloads.RaftDownloadCenter
import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.downloads.RaftDownloadStatus
import build.raft.app.transfer.RaftTransferProgress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * foreground service still runs (Android allows it, the notification is just not shown). The
 * channel is created regardless of that permission: `startForeground` rejects a notification on a
 * channel that does not exist (CannotPostForegroundServiceNotificationException, a crash), and
 * creating a channel needs no permission.
 */
/** Action carried by download progress / fallback notifications to open Raft's Download Center (#proj-mobile:#21). */
internal const val ACTION_RAFT_OPEN_DOWNLOADS = "build.raft.app.action.OPEN_DOWNLOADS"

private const val ANDROID_PACKAGE_ARCHIVE_MIME = "application/vnd.android.package-archive"

internal fun resolveDownloadNotificationMimeType(mimeType: String?, fileName: String): String {
    val extension = fileName.trim().substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (extension == "apk") return ANDROID_PACKAGE_ARCHIVE_MIME
    val declared = mimeType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') }
    if (declared.equals(ANDROID_PACKAGE_ARCHIVE_MIME, ignoreCase = true)) {
        return ANDROID_PACKAGE_ARCHIVE_MIME
    }
    val genericDeclared =
        declared == null ||
            declared.equals("application/octet-stream", ignoreCase = true) ||
            declared.equals("binary/octet-stream", ignoreCase = true) ||
            declared == "*/*"
    if (!genericDeclared) return declared
    if (extension.isNotEmpty()) {
        val inferred = runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) }.getOrNull()
        if (!inferred.isNullOrBlank()) return inferred
    }
    return declared ?: "*/*"
}

internal fun isDownloadNotificationAndroidPackage(mimeType: String, fileName: String): Boolean =
    mimeType.substringBefore(';').trim().equals(ANDROID_PACKAGE_ARCHIVE_MIME, ignoreCase = true) ||
        fileName.trim().endsWith(".apk", ignoreCase = true)

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

    private val attached = AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val notificationDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val channelLock = Any()

    @Volatile
    private var channelEnsuredContext: Context? = null

    private class CachedLaunchTemplate(val appContext: Context, val intent: Intent)

    private class CachedProgressPendingIntent(val appContext: Context, val pendingIntent: PendingIntent)

    private val launchIntentLock = Any()

    @Volatile
    private var cachedLaunchTemplate: CachedLaunchTemplate? = null

    @Volatile
    private var cachedProgressPendingIntent: CachedProgressPendingIntent? = null

    /** Attaches once (from `RaftDownloadCenterProcess.onCreated`); collects & notifies off-main on a serialized IO lane. */
    fun attach(context: Context, center: RaftDownloadCenter) {
        if (!attached.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + notificationDispatcher).launch {
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
    internal fun currentProgressNotification(context: Context): Notification {
        ensureChannel(context)
        return latestProgress ?: progressBuilder(context, emptyList()).build()
    }

    private fun render(context: Context, entries: List<RaftDownloadEntry>, previous: Map<String, RaftDownloadStatus>) {
        val active = entries.filter { it.status.isActive }
        val manager = NotificationManagerCompat.from(context)
        val canPost = AndroidUnreadNotificationPoster.hasNotificationPermission(context)
        ensureChannel(context)
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
        // The rows' own meter percents, weighted by size: the same numbers the centre shows.
        val combined =
            RaftTransferProgress.combinedPercent(
                active.map { entry -> entry.transfer ?: RaftTransferProgress.notStarted(entry.totalBytes) }
            )
        val determinate = combined != null
        val percent = combined ?: 0
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
            .setContentIntent(progressLaunchAppIntent(context))
    }

    private fun finishedNotification(
        context: Context,
        entry: RaftDownloadEntry,
        failed: Boolean,
        canLaunchApkInstaller: () -> Boolean = {
            androidPackageInstallDecision(
                installPermissionDeclared = AndroidPackageInstallPermission.isDeclared(context),
                canRequestPackageInstalls = AndroidPackageInstallPermission.canRequest(context),
            ) == AndroidPackageInstallDecision.Launch
        },
        canResolveViewIntent: (Intent) -> Boolean = { it.resolveActivity(context.packageManager) != null },
    ): Notification {
        val openFile =
            if (failed) {
                null
            } else {
                entry.localUri?.let { openFileIntent(context, entry, it, canLaunchApkInstaller, canResolveViewIntent) }
            }
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

    private fun openFileIntent(
        context: Context,
        entry: RaftDownloadEntry,
        uri: String,
        canLaunchApkInstaller: () -> Boolean,
        canResolveViewIntent: (Intent) -> Boolean,
    ): PendingIntent? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()?.takeIf { it.scheme == "content" } ?: return null
        val effectiveMime = resolveDownloadNotificationMimeType(entry.mimeType, entry.fileName)
        if (isDownloadNotificationAndroidPackage(effectiveMime, entry.fileName) && !canLaunchApkInstaller()) {
            // Avoid launching Android's package installer directly when Raft lacks install
            // permission (Google Play builds or "Install unknown apps" off): the installer would
            // finish silently. Fall back to the in-app Download Center where the install gate runs.
            return null
        }
        val view =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(parsed, effectiveMime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!canResolveViewIntent(view)) return null
        return PendingIntent.getActivity(
            context,
            finishedId(entry),
            view,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun progressLaunchAppIntent(context: Context): PendingIntent? {
        val appContext = context.applicationContext
        cachedProgressPendingIntent?.takeIf { it.appContext === appContext }?.let { return it.pendingIntent }
        synchronized(launchIntentLock) {
            cachedProgressPendingIntent?.takeIf { it.appContext === appContext }?.let { return it.pendingIntent }
            val created = launchAppIntent(appContext, PROGRESS_NOTIFICATION_ID) ?: return null
            cachedProgressPendingIntent = CachedProgressPendingIntent(appContext, created)
            return created
        }
    }

    private fun resolveLaunchIntent(context: Context): Intent {
        val appContext = context.applicationContext
        cachedLaunchTemplate?.takeIf { it.appContext === appContext }?.let { return Intent(it.intent) }
        synchronized(launchIntentLock) {
            cachedLaunchTemplate?.takeIf { it.appContext === appContext }?.let { return Intent(it.intent) }
            val resolved =
                Intent(appContext, MainActivity::class.java).apply {
                    action = ACTION_RAFT_OPEN_DOWNLOADS
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(EXTRA_RAFT_APP_OPEN_SOURCE, RAFT_APP_OPEN_SOURCE_APP_NOTIFICATION)
                }
            cachedLaunchTemplate = CachedLaunchTemplate(appContext, resolved)
            return Intent(resolved)
        }
    }

    private fun launchAppIntent(context: Context, requestCode: Int): PendingIntent? {
        val appContext = context.applicationContext
        val launch = resolveLaunchIntent(appContext)
        return PendingIntent.getActivity(
            appContext,
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

    internal fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appContext = context.applicationContext
        if (channelEnsuredContext === appContext) return
        synchronized(channelLock) {
            if (channelEnsuredContext === appContext) return
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        appContext.getString(R.string.raft_download_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = appContext.getString(R.string.raft_download_channel_description)
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
            channelEnsuredContext = appContext
        }
    }

    internal fun progressLaunchAppIntentForTest(context: Context): PendingIntent? =
        progressLaunchAppIntent(context)

    internal fun finishedNotificationForTest(
        context: Context,
        entry: RaftDownloadEntry,
        failed: Boolean,
        canLaunchApkInstaller: () -> Boolean = {
            androidPackageInstallDecision(
                installPermissionDeclared = AndroidPackageInstallPermission.isDeclared(context),
                canRequestPackageInstalls = AndroidPackageInstallPermission.canRequest(context),
            ) == AndroidPackageInstallDecision.Launch
        },
        canResolveViewIntent: (Intent) -> Boolean = { it.resolveActivity(context.packageManager) != null },
    ): Notification = finishedNotification(context, entry, failed, canLaunchApkInstaller, canResolveViewIntent)

    internal fun setHasActiveDownloadsForTests(active: Boolean) {
        hasActiveDownloads = active
    }

    internal fun resetCachesForTests() {
        channelEnsuredContext = null
        cachedLaunchTemplate = null
        cachedProgressPendingIntent = null
        latestProgress = null
        hasActiveDownloads = false
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
    @Volatile
    private var lastStartId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var clearedStartPending = false
        try {
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
            if (!promoted) {
                startPending = false
                clearedStartPending = true
                stopSelf(startId)
                return START_NOT_STICKY
            }
            // Publish instance before clearing startPending (reverse of ensureRunning()'s read
            // order: startPending then instance) and before reading hasActiveDownloads (reverse of
            // render()'s write order: hasActiveDownloads = false before stopIfIdle() reads instance)
            // so neither ensureRunning() nor stopIfIdle() can observe a gap across the handoff.
            lastStartId = startId
            instance.set(this)
            startPending = false
            clearedStartPending = true
            if (!RaftDownloadNotifications.hasActiveDownloads) {
                if (instance.compareAndSet(this, null)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    // A new download may have set hasActiveDownloads = true while instance was
                    // still published (so its ensureRunning returned early). Re-request if active.
                    if (RaftDownloadNotifications.hasActiveDownloads) {
                        ensureRunning(applicationContext)
                    }
                }
                return START_NOT_STICKY
            }
            return START_NOT_STICKY
        } finally {
            if (!clearedStartPending) {
                startPending = false
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        RaftAppLog.w(LOG_TAG, "download_service_timeout")
        instance.compareAndSet(this, null)
        stopSelf(startId)
    }

    override fun onDestroy() {
        instance.compareAndSet(this, null)
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "RaftDownloadService"

        private val instance = AtomicReference<RaftDownloadForegroundService?>(null)

        private val startLock = Any()

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
            // Check startPending before instance (reverse of onStartCommand's write order:
            // instance.set(this) before startPending = false) so we never observe both false
            // during promotion.
            if (startPending || instance.get() != null) return
            if (!isDeclared(appContext)) return
            synchronized(startLock) {
                if (startPending || instance.get() != null) return
                startPending = true
            }
            runCatching {
                ContextCompat.startForegroundService(appContext, Intent(appContext, RaftDownloadForegroundService::class.java))
            }.onFailure { error ->
                startPending = false
                RaftAppLog.w(LOG_TAG, "download_service_start_request_failed errorType=${error::class.simpleName}")
            }
        }

        /** A pending start stops itself in onStartCommand once it has promoted. */
        internal fun stopIfIdle() {
            val running = instance.get() ?: return
            if (RaftDownloadNotifications.hasActiveDownloads) return
            // Atomically clear instance before stopping so a back-to-back download starting before
            // onDestroy() issues startForegroundService() instead of seeing a stale instance, and
            // stopSelf(lastStartId) avoids cancelling a newer start request.
            if (!instance.compareAndSet(running, null)) return
            runCatching {
                running.stopForeground(STOP_FOREGROUND_REMOVE)
                running.stopSelf(running.lastStartId)
            }
        }

        internal fun resetForTests() {
            instance.set(null)
            startPending = false
            declared = null
        }
    }
}
