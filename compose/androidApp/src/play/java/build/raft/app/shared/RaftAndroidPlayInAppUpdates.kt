package build.raft.app.shared

import android.app.Activity
import android.app.Application
import build.raft.app.BuildConfig
import build.raft.app.RaftHostStrings
import build.raft.app.core.data.diagnostics.RaftAppLog
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Google Play In-App Updates (`play` build type only). Supports Flexible updates (download with
 * progress, `onResume` recovery, and `completeUpdate()` restart) plus Immediate updates for
 * [build.raft.app.data.RaftMinBuildGateDecision.Block], with seamless fallback to the Play Store
 * listing URL. Every other build type compiles a null stub so Hands APKs never link Play Core.
 */
internal object RaftAndroidPlayInAppUpdates {
    private const val LOG_TAG = "SlockPlayUpdate"
    private const val APP_UPDATE_INFO_TIMEOUT_MS = 15_000L

    fun create(
        application: Application,
        onStatusChanged: () -> Unit,
    ): RaftAndroidPlayUpdateController = PlayUpdateController(application, onStatusChanged)

    private class PlayUpdateController(
        private val application: Application,
        private val onStatusChanged: () -> Unit,
    ) : RaftAndroidPlayUpdateController {
        private val installedFromPlay: Boolean by lazy {
            RaftAndroidInstallReferrer.installedFromGooglePlay(application)
        }
        private val appUpdateManager: AppUpdateManager by lazy {
            AppUpdateManagerFactory.create(application)
        }
        private val listingUrl: String
            get() = "https://play.google.com/store/apps/details?id=${application.packageName}"

        private val listenerRegistered = AtomicBoolean(false)
        private val stateLock = Any()

        private var cachedInstallStatus: Int = PlayInstallStatus.UNKNOWN
        private var cachedTargetVersionCode: Long = 0L
        private var cachedBytesDownloaded: Long = 0L
        private var cachedTotalBytes: Long = 0L
        private var cachedInstallErrorCode: Int = 0
        private var lastLoggedInstallStatus: Int = -1

        private val installStateListener =
            InstallStateUpdatedListener { state ->
                val status = state.installStatus()
                val bytesDownloaded = state.bytesDownloaded().coerceAtLeast(0L)
                val totalBytes = state.totalBytesToDownload().coerceAtLeast(0L)
                val errorCode = state.installErrorCode()
                val shouldLog: Boolean
                val shouldNotify: Boolean
                synchronized(stateLock) {
                    val previousStatus = cachedInstallStatus
                    cachedInstallStatus = status
                    cachedBytesDownloaded = bytesDownloaded
                    cachedTotalBytes = totalBytes
                    cachedInstallErrorCode = errorCode
                    shouldLog = status != lastLoggedInstallStatus
                    if (shouldLog) {
                        lastLoggedInstallStatus = status
                    }
                    shouldNotify =
                        status != previousStatus &&
                            status in
                            setOf(
                                InstallStatus.PENDING,
                                InstallStatus.DOWNLOADING,
                                InstallStatus.DOWNLOADED,
                                InstallStatus.INSTALLING,
                                InstallStatus.INSTALLED,
                                InstallStatus.FAILED,
                                InstallStatus.CANCELED,
                            )
                }
                if (shouldLog) {
                    RaftAppLog.infoAlways(
                        LOG_TAG,
                        "install_state status=$status error=$errorCode bytes=$bytesDownloaded/$totalBytes"
                    )
                }
                if (shouldNotify) {
                    onStatusChanged()
                }
            }

        override suspend fun checkStatus(): Map<String, Any>? {
            if (!installedFromPlay) return null
            ensureListenerRegistered()
            val info = awaitAppUpdateInfo()
            if (info == null) {
                val unknownSnapshot =
                    PlayInAppUpdateSnapshot(
                        updateAvailability = PlayUpdateAvailability.UNKNOWN,
                        installStatus = currentCachedInstallStatus(),
                        availableVersionCode = currentCachedTargetVersionCode(),
                        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        currentVersionLabel = BuildConfig.VERSION_NAME,
                        listingUrl = listingUrl,
                    )
                val mapping = playInAppUpdateStateMapping(unknownSnapshot, localizedCopy())
                return playInAppUpdateStatusBridgePayload(unknownSnapshot, mapping, System.currentTimeMillis())
            }
            val snapshot = snapshotFrom(info)
            val mapping = playInAppUpdateStateMapping(snapshot, localizedCopy())
            return playInAppUpdateStatusBridgePayload(snapshot, mapping, System.currentTimeMillis())
        }

        override suspend fun startFlexibleUpdateOrComplete(
            activity: Activity?,
            onFallbackOpenStore: (String) -> Unit,
        ): Map<String, Any>? {
            if (!installedFromPlay) return null
            ensureListenerRegistered()
            val info = awaitAppUpdateInfo()
            if (info == null) {
                RaftAppLog.w(LOG_TAG, "flexible_update_fallback reason=app_update_info_unavailable")
                withContext(Dispatchers.Main.immediate) {
                    onFallbackOpenStore(listingUrl)
                }
                val fallbackSnapshot =
                    PlayInAppUpdateSnapshot(
                        updateAvailability = PlayUpdateAvailability.UNKNOWN,
                        installStatus = PlayInstallStatus.UNKNOWN,
                        availableVersionCode = 0L,
                        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        currentVersionLabel = BuildConfig.VERSION_NAME,
                        listingUrl = listingUrl,
                    )
                return playInAppUpdateStatusBridgePayload(
                    snapshot = fallbackSnapshot,
                    mapping = playInAppUpdateStateMapping(fallbackSnapshot, localizedCopy()),
                    checkedAtMillis = System.currentTimeMillis(),
                )
            }
            val snapshot = snapshotFrom(info)
            val copy = localizedCopy()
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                RaftAppLog.infoAlways(LOG_TAG, "complete_update_started latest=${info.availableVersionCode()}")
                appUpdateManager.completeUpdate().addOnFailureListener { error ->
                    RaftAppLog.w(LOG_TAG, "complete_update_failed error=${error.javaClass.simpleName}")
                }
                synchronized(stateLock) {
                    cachedInstallStatus = PlayInstallStatus.INSTALLING
                }
                val installingSnapshot = snapshot.copy(installStatus = PlayInstallStatus.INSTALLING)
                val mapping = playInAppUpdateStateMapping(installingSnapshot, copy)
                return playInAppUpdateStatusBridgePayload(installingSnapshot, mapping, System.currentTimeMillis())
            }
            if (
                info.installStatus() == InstallStatus.PENDING ||
                info.installStatus() == InstallStatus.DOWNLOADING ||
                info.installStatus() == InstallStatus.INSTALLING
            ) {
                val mapping = playInAppUpdateStateMapping(snapshot, copy)
                return playInAppUpdateStatusBridgePayload(snapshot, mapping, System.currentTimeMillis())
            }
            val available =
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            if (!available || info.availableVersionCode().toLong() <= BuildConfig.VERSION_CODE.toLong()) {
                val mapping = playInAppUpdateStateMapping(snapshot, copy)
                return playInAppUpdateStatusBridgePayload(snapshot, mapping, System.currentTimeMillis())
            }
            val hostActivity = activity?.takeUnless { it.isFinishing || it.isDestroyed }
            val flexibleOptions = AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE)
            if (hostActivity != null && info.isUpdateTypeAllowed(flexibleOptions)) {
                val started =
                    withContext(Dispatchers.Main.immediate) {
                        runCatching {
                            appUpdateManager.startUpdateFlowForResult(
                                info,
                                hostActivity,
                                flexibleOptions,
                                PLAY_FLEXIBLE_UPDATE_REQUEST_CODE,
                            )
                        }.onFailure { error ->
                            RaftAppLog.w(
                                LOG_TAG,
                                "flexible_update_start_threw latest=${info.availableVersionCode()} error=${error.javaClass.simpleName}"
                            )
                        }.getOrDefault(false)
                    }
                if (started) {
                    RaftAppLog.infoAlways(
                        LOG_TAG,
                        "flexible_update_started latest=${info.availableVersionCode()} priority=${info.updatePriority()}"
                    )
                    val mapping = playInAppUpdateStateMapping(snapshot, copy)
                    return playInAppUpdateStatusBridgePayload(snapshot, mapping, System.currentTimeMillis())
                }
                RaftAppLog.w(LOG_TAG, "flexible_update_fallback reason=start_declined latest=${info.availableVersionCode()}")
            } else {
                val reason = if (hostActivity == null) "no_activity" else "flexible_not_allowed"
                RaftAppLog.w(LOG_TAG, "flexible_update_fallback reason=$reason latest=${info.availableVersionCode()}")
            }
            withContext(Dispatchers.Main.immediate) {
                onFallbackOpenStore(listingUrl)
            }
            val mapping = playInAppUpdateStateMapping(snapshot, copy)
            return playInAppUpdateStatusBridgePayload(snapshot, mapping, System.currentTimeMillis())
        }

        override fun readProgress(token: String): Map<String, Any>? {
            if (!installedFromPlay) return null
            val status: Int
            val targetVersionCode: Long
            val bytesDownloaded: Long
            val totalBytes: Long
            synchronized(stateLock) {
                status = cachedInstallStatus
                targetVersionCode = cachedTargetVersionCode
                bytesDownloaded = cachedBytesDownloaded
                totalBytes = cachedTotalBytes
            }
            return playInAppUpdateProgressPayload(
                token = token,
                installStatus = status,
                targetVersionCode = targetVersionCode,
                bytesDownloaded = bytesDownloaded,
                totalBytesToDownload = totalBytes,
            )
        }

        override fun tryStartImmediateUpdate(activity: Activity, onFallbackOpenStore: () -> Unit): Boolean {
            if (!installedFromPlay) return false
            if (activity.isFinishing || activity.isDestroyed) return false
            appUpdateManager.appUpdateInfo.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    RaftAppLog.w(
                        LOG_TAG,
                        "immediate_update_fallback reason=info_failed error=${task.exception?.javaClass?.simpleName ?: "unknown"}"
                    )
                    onFallbackOpenStore()
                    return@addOnCompleteListener
                }
                if (activity.isFinishing || activity.isDestroyed) {
                    onFallbackOpenStore()
                    return@addOnCompleteListener
                }
                val info = task.result
                val immediateOptions = AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)
                val eligible =
                    (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
                        info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) &&
                        info.isUpdateTypeAllowed(immediateOptions)
                if (!eligible) {
                    RaftAppLog.w(
                        LOG_TAG,
                        "immediate_update_fallback reason=not_allowed availability=${info.updateAvailability()}"
                    )
                    onFallbackOpenStore()
                    return@addOnCompleteListener
                }
                val started =
                    runCatching {
                        appUpdateManager.startUpdateFlowForResult(
                            info,
                            activity,
                            immediateOptions,
                            PLAY_IMMEDIATE_UPDATE_REQUEST_CODE,
                        )
                    }.onFailure { error ->
                        RaftAppLog.w(
                            LOG_TAG,
                            "immediate_update_start_threw latest=${info.availableVersionCode()} error=${error.javaClass.simpleName}"
                        )
                    }.getOrDefault(false)
                if (started) {
                    RaftAppLog.infoAlways(LOG_TAG, "immediate_update_started latest=${info.availableVersionCode()}")
                } else {
                    RaftAppLog.w(LOG_TAG, "immediate_update_fallback reason=start_declined latest=${info.availableVersionCode()}")
                    onFallbackOpenStore()
                }
            }
            return true
        }

        override fun onActivityResume(activity: Activity) {
            if (!installedFromPlay) return
            if (activity.isFinishing || activity.isDestroyed) return
            ensureListenerRegistered()
            appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
                snapshotFrom(info)
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    RaftAppLog.infoAlways(LOG_TAG, "resume_detected_downloaded latest=${info.availableVersionCode()}")
                    onStatusChanged()
                    return@addOnSuccessListener
                }
                val immediateOptions = AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)
                if (
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                    info.isUpdateTypeAllowed(immediateOptions) &&
                    !activity.isFinishing &&
                    !activity.isDestroyed
                ) {
                    runCatching {
                        appUpdateManager.startUpdateFlowForResult(
                            info,
                            activity,
                            immediateOptions,
                            PLAY_IMMEDIATE_UPDATE_REQUEST_CODE,
                        )
                    }.onSuccess { restarted ->
                        RaftAppLog.infoAlways(
                            LOG_TAG,
                            "resume_immediate_update restarted=$restarted latest=${info.availableVersionCode()}"
                        )
                    }.onFailure { error ->
                        RaftAppLog.w(
                            LOG_TAG,
                            "resume_immediate_update_failed error=${error.javaClass.simpleName}"
                        )
                    }
                }
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int) {
            val label = playInAppUpdateActivityResultLabel(requestCode, resultCode) ?: return
            val flow = if (requestCode == PLAY_IMMEDIATE_UPDATE_REQUEST_CODE) "immediate" else "flexible"
            RaftAppLog.infoAlways(LOG_TAG, "activity_result flow=$flow result=$label resultCode=$resultCode")
            if (resultCode == PLAY_ACTIVITY_RESULT_IN_APP_UPDATE_FAILED) {
                synchronized(stateLock) {
                    cachedInstallStatus = PlayInstallStatus.FAILED
                }
            }
            onStatusChanged()
        }

        override fun release() {
            if (listenerRegistered.compareAndSet(true, false)) {
                runCatching { appUpdateManager.unregisterListener(installStateListener) }
            }
        }

        private fun ensureListenerRegistered() {
            if (listenerRegistered.compareAndSet(false, true)) {
                runCatching { appUpdateManager.registerListener(installStateListener) }
                    .onFailure { error ->
                        listenerRegistered.set(false)
                        RaftAppLog.w(LOG_TAG, "register_listener_failed error=${error.javaClass.simpleName}")
                    }
            }
        }

        private suspend fun awaitAppUpdateInfo(): AppUpdateInfo? =
            withTimeoutOrNull(APP_UPDATE_INFO_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    appUpdateManager.appUpdateInfo.addOnCompleteListener { task ->
                        val result =
                            if (task.isSuccessful) {
                                task.result
                            } else {
                                RaftAppLog.w(
                                    LOG_TAG,
                                    "app_update_info_failed error=${task.exception?.javaClass?.simpleName ?: "unknown"}"
                                )
                                null
                            }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            }

        private fun snapshotFrom(info: AppUpdateInfo): PlayInAppUpdateSnapshot {
            val availableVersionCode = info.availableVersionCode().toLong().coerceAtLeast(0L)
            val infoStatus = info.installStatus()
            val infoDownloaded = info.bytesDownloaded().coerceAtLeast(0L)
            val infoTotal = info.totalBytesToDownload().coerceAtLeast(0L)
            val resolvedStatus: Int
            val resolvedDownloaded: Long
            val resolvedTotal: Long
            val resolvedErrorCode: Int
            synchronized(stateLock) {
                if (availableVersionCode > 0L) {
                    cachedTargetVersionCode = availableVersionCode
                }
                if (infoStatus != InstallStatus.UNKNOWN) {
                    cachedInstallStatus = infoStatus
                    if (infoStatus == InstallStatus.DOWNLOADING) {
                        cachedBytesDownloaded = maxOf(cachedBytesDownloaded, infoDownloaded)
                        cachedTotalBytes = maxOf(cachedTotalBytes, infoTotal)
                    } else {
                        cachedBytesDownloaded = infoDownloaded
                        cachedTotalBytes = infoTotal
                    }
                    if (infoStatus != InstallStatus.FAILED) {
                        cachedInstallErrorCode = 0
                    }
                } else if (
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    cachedInstallStatus !in setOf(PlayInstallStatus.PENDING, PlayInstallStatus.DOWNLOADING, PlayInstallStatus.DOWNLOADED)
                ) {
                    cachedInstallStatus = PlayInstallStatus.UNKNOWN
                    cachedInstallErrorCode = 0
                }
                resolvedStatus = cachedInstallStatus
                resolvedDownloaded = cachedBytesDownloaded
                resolvedTotal = cachedTotalBytes
                resolvedErrorCode = cachedInstallErrorCode
            }
            return PlayInAppUpdateSnapshot(
                updateAvailability = info.updateAvailability(),
                installStatus = resolvedStatus,
                availableVersionCode = availableVersionCode,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                currentVersionLabel = BuildConfig.VERSION_NAME,
                updatePriority = info.updatePriority().coerceAtLeast(0),
                stalenessDays = info.clientVersionStalenessDays()?.coerceAtLeast(0),
                bytesDownloaded = resolvedDownloaded,
                totalBytesToDownload = resolvedTotal,
                installErrorCode = resolvedErrorCode,
                flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                listingUrl = listingUrl,
            )
        }

        private fun currentCachedInstallStatus(): Int = synchronized(stateLock) { cachedInstallStatus }

        private fun currentCachedTargetVersionCode(): Long = synchronized(stateLock) { cachedTargetVersionCode }

        private fun localizedCopy(): PlayInAppUpdateCopy {
            val defaults = PlayInAppUpdateCopy()
            return PlayInAppUpdateCopy(
                available = runCatching { RaftHostStrings.appUpdateDetail(updateAvailable = true, sizeLabel = null) }.getOrDefault(defaults.available),
                upToDate = runCatching { RaftHostStrings.appUpdateDetail(updateAvailable = false, sizeLabel = null) }.getOrDefault(defaults.upToDate),
                downloading = runCatching { RaftHostStrings.appUpdateDownloading() }.getOrDefault(defaults.downloading),
                readyToRestart = runCatching { RaftHostStrings.appUpdateDownloaded() }.getOrDefault(defaults.readyToRestart),
                installing = runCatching { RaftHostStrings.appUpdateInstallerOpened() }.getOrDefault(defaults.installing),
                failed = runCatching { RaftHostStrings.appUpdateInstallFailed() }.getOrDefault(defaults.failed),
                unavailable = defaults.unavailable,
            )
        }
    }
}
