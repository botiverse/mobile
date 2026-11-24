package build.raft.app.shared

import android.app.Activity
import build.raft.app.data.RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.SharedSettingsUpdateTransactionState

internal const val PLAY_IN_APP_UPDATE_PROGRESS_TOKEN_PREFIX = "play_in_app:"
internal const val PLAY_FLEXIBLE_UPDATE_REQUEST_CODE = 4208
internal const val PLAY_IMMEDIATE_UPDATE_REQUEST_CODE = 4209

/** Mirrors `com.google.android.play.core.install.model.ActivityResult.RESULT_IN_APP_UPDATE_FAILED`. */
internal const val PLAY_ACTIVITY_RESULT_IN_APP_UPDATE_FAILED = 1

/** Mirrors `com.google.android.play.core.install.model.UpdateAvailability` without pulling Play Core into `src/main`. */
internal object PlayUpdateAvailability {
    const val UNKNOWN = 0
    const val UPDATE_NOT_AVAILABLE = 1
    const val UPDATE_AVAILABLE = 2
    const val DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS = 3
}

/** Mirrors `com.google.android.play.core.install.model.InstallStatus` without pulling Play Core into `src/main`. */
internal object PlayInstallStatus {
    const val UNKNOWN = 0
    const val PENDING = 1
    const val DOWNLOADING = 2
    const val INSTALLING = 3
    const val INSTALLED = 4
    const val FAILED = 5
    const val CANCELED = 6
    const val REQUIRES_UI_INTENT = 10
    const val DOWNLOADED = 11
}

/**
 * Play In-App Updates controller implemented only in the `play` build type (`src/play/`).
 * Every other build type compiles a null-returning [RaftAndroidPlayInAppUpdates.create] stub so
 * Hands APKs never link Google Play Core.
 */
internal interface RaftAndroidPlayUpdateController {
    /**
     * Queries Play for update status and returns a bridge-compatible update status map, or `null`
     * when this install did not come from Google Play (sideloaded `play` builds stay unsupported).
     */
    suspend fun checkStatus(): Map<String, Any>?

    /**
     * Completes an already-downloaded Flexible update (`completeUpdate()`) or launches the Play
     * Flexible in-app update flow, falling back to [onFallbackOpenStore] if Play declines or fails.
     * Returns `null` when this install did not come from Google Play.
     */
    suspend fun startFlexibleUpdateOrComplete(
        activity: Activity?,
        onFallbackOpenStore: (String) -> Unit,
    ): Map<String, Any>?

    /** Returns live download progress for a `play_in_app:` token, or `null` when unavailable. */
    fun readProgress(token: String): Map<String, Any>?

    /**
     * Attempts to launch Google Play's full-screen Immediate update flow (used for
     * [RaftMinBuildGateDecision.Block]). Returns `true` when Play owns the attempt (invoking
     * [onFallbackOpenStore] asynchronously if Play refuses or fails), or `false` when this install
     * is not eligible so the caller opens the store URL directly.
     */
    fun tryStartImmediateUpdate(activity: Activity, onFallbackOpenStore: () -> Unit): Boolean

    /** Checks for completed Flexible downloads (`ReadyToRestart`) and interrupted Immediate flows. */
    fun onActivityResume(activity: Activity)

    /** Handles Play In-App Update activity results (`PLAY_FLEXIBLE_UPDATE_REQUEST_CODE` / `PLAY_IMMEDIATE_UPDATE_REQUEST_CODE`). */
    fun onActivityResult(requestCode: Int, resultCode: Int)

    /** Unregisters Play listeners on host destruction. */
    fun release()
}

internal data class PlayInAppUpdateCopy(
    val available: String = "Raft has an update.",
    val upToDate: String = "Raft is up to date.",
    val downloading: String = "Downloading update...",
    val readyToRestart: String = "Update downloaded. Tap to install.",
    val installing: String = "Installing update...",
    val failed: String = "Update failed.",
    val unavailable: String = "Update information is temporarily unavailable.",
)

internal data class PlayInAppUpdateSnapshot(
    val updateAvailability: Int,
    val installStatus: Int,
    val availableVersionCode: Long,
    val currentVersionCode: Long,
    val currentVersionLabel: String,
    val updatePriority: Int = 0,
    val stalenessDays: Int? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytesToDownload: Long = 0L,
    val installErrorCode: Int = 0,
    val flexibleAllowed: Boolean = false,
    val immediateAllowed: Boolean = false,
    val listingUrl: String,
)

internal data class PlayInAppUpdateStateMapping(
    val transactionState: SharedSettingsUpdateTransactionState,
    val updateAvailable: Boolean,
    val targetVersionCode: Long,
    val retryable: Boolean,
    val statusLabel: String,
    val actionLabel: String,
    val detail: String,
    val errorCode: String,
    val progressToken: String,
)

internal fun playInAppUpdateProgressToken(
    state: SharedSettingsUpdateTransactionState,
    targetVersionCode: Long,
): String {
    if (state != SharedSettingsUpdateTransactionState.Downloading || targetVersionCode <= 0L) {
        return ""
    }
    return "$PLAY_IN_APP_UPDATE_PROGRESS_TOKEN_PREFIX$targetVersionCode"
}

internal fun playInAppUpdateProgressTokenVersionCode(token: String): Long? {
    if (!token.startsWith(PLAY_IN_APP_UPDATE_PROGRESS_TOKEN_PREFIX)) return null
    return token.removePrefix(PLAY_IN_APP_UPDATE_PROGRESS_TOKEN_PREFIX)
        .toLongOrNull()
        ?.takeIf { it > 0L }
}

/** `major.minor.patch` for standard Raft version codes (`major*10M + minor*100k + patch*1k + build`). */
internal fun playVersionLabelOfCode(versionCode: Long): String {
    if (versionCode < RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR) return ""
    val major = versionCode / 10_000_000L
    val minor = (versionCode / 100_000L) % 100L
    val patch = (versionCode / 1_000L) % 100L
    if (major > 209L) return ""
    return "$major.$minor.$patch"
}

internal fun playInAppUpdateStateMapping(
    snapshot: PlayInAppUpdateSnapshot,
    copy: PlayInAppUpdateCopy = PlayInAppUpdateCopy(),
): PlayInAppUpdateStateMapping {
    val availabilityHasUpdate =
        snapshot.updateAvailability == PlayUpdateAvailability.UPDATE_AVAILABLE ||
            snapshot.updateAvailability == PlayUpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
    val newerVersionCode =
        snapshot.availableVersionCode.takeIf { code ->
            code > 0L && (snapshot.currentVersionCode <= 0L || code > snapshot.currentVersionCode)
        } ?: 0L
    val hasNewerAvailable = availabilityHasUpdate && newerVersionCode > 0L
    val targetVersionCode =
        when {
            newerVersionCode > 0L -> newerVersionCode
            snapshot.availableVersionCode > 0L -> snapshot.availableVersionCode
            else -> 0L
        }

    return when (snapshot.installStatus) {
        PlayInstallStatus.DOWNLOADED ->
            PlayInAppUpdateStateMapping(
                transactionState = SharedSettingsUpdateTransactionState.ReadyToRestart,
                updateAvailable = true,
                targetVersionCode = targetVersionCode,
                retryable = true,
                statusLabel = "Restart",
                actionLabel = "Install",
                detail = copy.readyToRestart,
                errorCode = "",
                progressToken = "",
            )
        PlayInstallStatus.PENDING,
        PlayInstallStatus.DOWNLOADING ->
            PlayInAppUpdateStateMapping(
                transactionState = SharedSettingsUpdateTransactionState.Downloading,
                updateAvailable = true,
                targetVersionCode = targetVersionCode,
                retryable = false,
                statusLabel = "Downloading",
                actionLabel = "Downloading",
                detail = copy.downloading,
                errorCode = "",
                progressToken =
                    playInAppUpdateProgressToken(
                        state = SharedSettingsUpdateTransactionState.Downloading,
                        targetVersionCode = targetVersionCode,
                    ),
            )
        PlayInstallStatus.INSTALLING ->
            PlayInAppUpdateStateMapping(
                transactionState = SharedSettingsUpdateTransactionState.ReadyToRestart,
                updateAvailable = true,
                targetVersionCode = targetVersionCode,
                retryable = true,
                statusLabel = "Installing",
                actionLabel = "Installing",
                detail = copy.installing,
                errorCode = "",
                progressToken = "",
            )
        PlayInstallStatus.INSTALLED ->
            PlayInAppUpdateStateMapping(
                transactionState = SharedSettingsUpdateTransactionState.Installed,
                updateAvailable = false,
                targetVersionCode = targetVersionCode,
                retryable = false,
                statusLabel = "Installed",
                actionLabel = "Installed",
                detail = copy.upToDate,
                errorCode = "",
                progressToken = "",
            )
        PlayInstallStatus.FAILED ->
            PlayInAppUpdateStateMapping(
                transactionState = SharedSettingsUpdateTransactionState.Failed,
                updateAvailable = hasNewerAvailable,
                targetVersionCode = if (hasNewerAvailable) targetVersionCode else 0L,
                retryable = hasNewerAvailable,
                statusLabel = "Failed",
                actionLabel = if (hasNewerAvailable) "Update" else "",
                detail = copy.failed,
                errorCode =
                    if (snapshot.installErrorCode != 0) {
                        "play_in_app_update_failed_${snapshot.installErrorCode}"
                    } else {
                        "play_in_app_update_failed"
                    },
                progressToken = "",
            )
        PlayInstallStatus.CANCELED ->
            PlayInAppUpdateStateMapping(
                transactionState = SharedSettingsUpdateTransactionState.Idle,
                updateAvailable = hasNewerAvailable,
                targetVersionCode = if (hasNewerAvailable) targetVersionCode else 0L,
                retryable = hasNewerAvailable,
                statusLabel = if (hasNewerAvailable) "Update available" else "Current",
                actionLabel = if (hasNewerAvailable) "Update" else "",
                detail = if (hasNewerAvailable) copy.available else copy.upToDate,
                errorCode = "",
                progressToken = "",
            )
        else ->
            when {
                hasNewerAvailable ->
                    PlayInAppUpdateStateMapping(
                        transactionState = SharedSettingsUpdateTransactionState.Idle,
                        updateAvailable = true,
                        targetVersionCode = targetVersionCode,
                        retryable = true,
                        statusLabel = "Update available",
                        actionLabel = "Update",
                        detail = copy.available,
                        errorCode = "",
                        progressToken = "",
                    )
                snapshot.updateAvailability == PlayUpdateAvailability.UPDATE_NOT_AVAILABLE ||
                    (availabilityHasUpdate && snapshot.availableVersionCode > 0L && snapshot.availableVersionCode <= snapshot.currentVersionCode) ->
                    PlayInAppUpdateStateMapping(
                        transactionState = SharedSettingsUpdateTransactionState.Idle,
                        updateAvailable = false,
                        targetVersionCode = 0L,
                        retryable = false,
                        statusLabel = "Current",
                        actionLabel = "",
                        detail = copy.upToDate,
                        errorCode = "",
                        progressToken = "",
                    )
                else ->
                    PlayInAppUpdateStateMapping(
                        transactionState = SharedSettingsUpdateTransactionState.Idle,
                        updateAvailable = false,
                        targetVersionCode = 0L,
                        retryable = false,
                        statusLabel = "Unavailable",
                        actionLabel = "",
                        detail = copy.unavailable,
                        errorCode = "store_update_unknown",
                        progressToken = "",
                    )
            }
    }
}

internal fun playInAppUpdateProgressPayload(
    token: String,
    installStatus: Int,
    targetVersionCode: Long,
    bytesDownloaded: Long,
    totalBytesToDownload: Long,
): Map<String, Any>? {
    if (!token.startsWith(PLAY_IN_APP_UPDATE_PROGRESS_TOKEN_PREFIX)) return null
    val expectedVersionCode = playInAppUpdateProgressTokenVersionCode(token)
    if (expectedVersionCode != null && targetVersionCode > 0L && targetVersionCode != expectedVersionCode) {
        return null
    }
    val downloaded = bytesDownloaded.coerceAtLeast(0L)
    val total = totalBytesToDownload.takeIf { it > 0L } ?: -1L
    val phase =
        when (installStatus) {
            PlayInstallStatus.PENDING,
            PlayInstallStatus.DOWNLOADING -> "downloading"
            PlayInstallStatus.DOWNLOADED,
            PlayInstallStatus.INSTALLING,
            PlayInstallStatus.INSTALLED -> "ready"
            PlayInstallStatus.FAILED,
            PlayInstallStatus.CANCELED -> "failed"
            else -> return null
        }
    return mapOf(
        "status" to "ok",
        "token" to token,
        "phase" to phase,
        "downloadedBytes" to downloaded,
        "totalBytes" to total,
    )
}

internal fun playInAppUpdateStatusBridgePayload(
    snapshot: PlayInAppUpdateSnapshot,
    mapping: PlayInAppUpdateStateMapping,
    checkedAtMillis: Long,
): Map<String, Any> {
    val latestVersionCode = mapping.targetVersionCode
    val latestVersionLabel =
        if (latestVersionCode > 0L) {
            playVersionLabelOfCode(latestVersionCode).ifBlank { latestVersionCode.toString() }
        } else {
            ""
        }
    return mapOf(
        "status" to "ok",
        "updateCheckSupported" to true,
        "updateTransactionState" to mapping.transactionState.wireValue,
        "updateTransactionTargetVersionCode" to latestVersionCode,
        "updateTransactionRetryable" to mapping.retryable,
        "updateAvailable" to mapping.updateAvailable,
        "currentVersionLabel" to snapshot.currentVersionLabel.ifBlank { snapshot.currentVersionCode.toString() },
        "currentVersionCode" to snapshot.currentVersionCode,
        "latestVersionLabel" to latestVersionLabel,
        "latestVersionCode" to latestVersionCode,
        "updatePriority" to snapshot.updatePriority.coerceAtLeast(0),
        "updateStalenessDays" to (snapshot.stalenessDays?.coerceAtLeast(0)?.toLong() ?: -1L),
        "updateDownloadUrl" to snapshot.listingUrl,
        "updateDownloadProgressToken" to mapping.progressToken,
        "updateCheckStatusLabel" to mapping.statusLabel,
        "updateCheckDetail" to mapping.detail,
        "updateCheckActionLabel" to mapping.actionLabel,
        "updateCheckErrorCode" to mapping.errorCode,
        "updateCheckedAtLabel" to checkedAtMillis.toString(),
    )
}

internal fun shouldInterceptPlayImmediateUpdate(
    url: String,
    packageName: String,
    minBuildGateDecision: RaftMinBuildGateDecision,
): Boolean {
    if (minBuildGateDecision !is RaftMinBuildGateDecision.Block) return false
    val pkg = packageName.trim()
    if (pkg.isEmpty()) return false
    val normalized = url.trim()
    val expectedListing = "https://play.google.com/store/apps/details?id=$pkg"
    return normalized == expectedListing || normalized.startsWith("$expectedListing&")
}

internal fun playInAppUpdateActivityResultLabel(
    requestCode: Int,
    resultCode: Int,
): String? {
    if (requestCode != PLAY_FLEXIBLE_UPDATE_REQUEST_CODE && requestCode != PLAY_IMMEDIATE_UPDATE_REQUEST_CODE) {
        return null
    }
    return when (resultCode) {
        Activity.RESULT_OK -> "ok"
        Activity.RESULT_CANCELED -> "canceled"
        PLAY_ACTIVITY_RESULT_IN_APP_UPDATE_FAILED -> "failed"
        else -> "unknown_$resultCode"
    }
}
