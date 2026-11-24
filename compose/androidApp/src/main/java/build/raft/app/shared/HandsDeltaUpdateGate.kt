package build.raft.app.shared

import build.hands.update.HandsUpdateStatus
import build.hands.update.INSTALL_PERMISSION_REQUIRED
import build.hands.update.models.UpdateCheckResponse
import build.raft.app.data.RaftAppUpdatePermissionResumeRepository
import build.raft.app.data.SharedSettingsUpdateTransactionState
import java.util.concurrent.atomic.AtomicBoolean

internal const val HANDS_DELTA_ALPHA_APPLICATION_ID = "build.raft.app.alpha"
internal const val HANDS_DELTA_ALPHA_APP_SLUG = "raft-android-alpha-delta"
internal const val HANDS_DELTA_ALPHA_CHANNEL = "alpha-delta"
internal const val HANDS_DELTA_PRODUCTION_APPLICATION_ID = "build.raft.app"
internal const val HANDS_DELTA_PRODUCTION_APP_SLUG = "raft-android"
internal const val HANDS_DELTA_PREVIEW_APPLICATION_ID = HANDS_DELTA_PRODUCTION_APPLICATION_ID
internal const val HANDS_DELTA_PREVIEW_APP_SLUG = HANDS_DELTA_PRODUCTION_APP_SLUG
internal const val HANDS_DELTA_RELEASE_ALPHA_CHANNEL = "alpha"
internal const val HANDS_DELTA_PREVIEW_CHANNEL = "preview"
internal const val HANDS_DELTA_MAIN_CHANNEL = "main"
internal const val HANDS_UPDATE_MODE_ALPHA_DELTA = "alpha_delta"
internal const val HANDS_UPDATE_MODE_RELEASE_ALPHA_DELTA = "alpha_channel_delta"
internal const val HANDS_UPDATE_MODE_PREVIEW_DELTA = "preview_delta"
internal const val HANDS_UPDATE_MODE_MAIN_DELTA = "main_delta"
internal const val HANDS_UPDATE_MODE_FULL_APK = "full_apk"
internal const val HANDS_DELTA_PROGRESS_TOKEN_PREFIX = "hands_sdk:"

/** The bridge's Hands update actions a person starts: Settings' Check for update, and Install. */
internal val HandsUpdateUserActions = setOf("handsUpdateCheck", "handsUpdateInstall")

/**
 * Whether a Hands update [action] may ask Hands now. A person's own Check for update / Install
 * always may; the automatic status check (`handsUpdateStatus`) only when every signed-in server
 * answers privacy mode off ([automaticAllowed], `RaftServicesPrivacyGate.allowsAutomatic`).
 */
internal fun handsUpdateRequestAllowed(action: String, automaticAllowed: Boolean): Boolean =
    action in HandsUpdateUserActions || automaticAllowed

internal fun handsApkUpdateDisabledResponse(): Map<String, Any> =
    mapOf(
        // The shared bridge parser treats every non-"ok" response as an
        // error.  This is a successful capability response whose payload
        // advertises that in-app APK updates are unavailable for Play.
        "status" to "ok",
        "code" to "hands_apk_update_disabled",
        "updateCheckSupported" to false,
        "updateAvailable" to false,
    )

internal data class HandsUpdateRoute(
    val mode: String,
    val appSlug: String,
    val channel: String,
    val deltaEnabled: Boolean
)

internal data class HandsUpdateDisplaySizes(
    val patchSizeBytes: Long? = null,
    val fullSizeBytes: Long? = null
)

internal fun UpdateCheckResponse.handsUpdateDisplaySizes(deltaEnabled: Boolean): HandsUpdateDisplaySizes {
    if (requireUpdate() == null) return HandsUpdateDisplaySizes()
    val offeredPatch = patch
    val applicablePatchSize =
        offeredPatch?.size_bytes?.takeIf { size ->
            deltaEnabled &&
                current_version_code > 0L &&
                offeredPatch.from_version_code == current_version_code &&
                offeredPatch.algorithm.startsWith("archive-patcher-v1") &&
                !offeredPatch.target_sha256.isNullOrBlank() &&
                size > 0L
        }
    return HandsUpdateDisplaySizes(
        patchSizeBytes = applicablePatchSize,
        fullSizeBytes = asset?.size_bytes?.takeIf { it > 0L }
    )
}

internal fun handsDeltaUpdateMode(
    appSlug: String,
    channel: String
): String? =
    when {
        appSlug == HANDS_DELTA_ALPHA_APP_SLUG && channel == HANDS_DELTA_ALPHA_CHANNEL ->
            HANDS_UPDATE_MODE_ALPHA_DELTA
        appSlug == HANDS_DELTA_PRODUCTION_APP_SLUG && channel == HANDS_DELTA_RELEASE_ALPHA_CHANNEL ->
            HANDS_UPDATE_MODE_RELEASE_ALPHA_DELTA
        appSlug == HANDS_DELTA_PRODUCTION_APP_SLUG && channel == HANDS_DELTA_PREVIEW_CHANNEL ->
            HANDS_UPDATE_MODE_PREVIEW_DELTA
        appSlug == HANDS_DELTA_PRODUCTION_APP_SLUG && channel == HANDS_DELTA_MAIN_CHANNEL ->
            HANDS_UPDATE_MODE_MAIN_DELTA
        else -> null
    }

internal fun resolveHandsUpdateRoute(
    deltaEnabled: Boolean,
    defaultAppSlug: String,
    defaultChannel: String,
    deltaAppSlug: String,
    deltaChannel: String
): HandsUpdateRoute {
    val deltaMode = if (deltaEnabled) handsDeltaUpdateMode(deltaAppSlug, deltaChannel) else null
    return if (deltaMode != null) {
        HandsUpdateRoute(
            mode = deltaMode,
            appSlug = deltaAppSlug,
            channel = deltaChannel,
            deltaEnabled = true
        )
    } else {
        HandsUpdateRoute(
            mode = HANDS_UPDATE_MODE_FULL_APK,
            appSlug = defaultAppSlug,
            channel = defaultChannel,
            deltaEnabled = false
        )
    }
}

internal class HandsDeltaUpdateCallOwner {
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    suspend fun <T : Any> runIfEligible(
        eligible: Boolean,
        block: suspend () -> T
    ): T? {
        if (!eligible || !running.compareAndSet(false, true)) return null
        return try {
            block()
        } finally {
            running.set(false)
        }
    }
}

internal fun handsDeltaUpdateAllowed(
    configuredEnabled: Boolean,
    apkUpdateEnabled: Boolean,
    buildApplicationId: String,
    runtimePackageName: String,
    appSlug: String,
    channel: String
): Boolean {
    if (!configuredEnabled || !apkUpdateEnabled) return false
    val isExactAlpha =
        buildApplicationId == HANDS_DELTA_ALPHA_APPLICATION_ID &&
            runtimePackageName == HANDS_DELTA_ALPHA_APPLICATION_ID &&
            appSlug == HANDS_DELTA_ALPHA_APP_SLUG &&
            channel == HANDS_DELTA_ALPHA_CHANNEL
    val isExactRelease =
        buildApplicationId == HANDS_DELTA_PRODUCTION_APPLICATION_ID &&
            runtimePackageName == HANDS_DELTA_PRODUCTION_APPLICATION_ID &&
            appSlug == HANDS_DELTA_PRODUCTION_APP_SLUG &&
            (
                channel == HANDS_DELTA_RELEASE_ALPHA_CHANNEL ||
                    channel == HANDS_DELTA_PREVIEW_CHANNEL ||
                    channel == HANDS_DELTA_MAIN_CHANNEL
                )
    return isExactAlpha || isExactRelease
}

internal fun handsDeltaDownloadProgressToken(
    updateRoute: HandsUpdateRoute,
    state: SharedSettingsUpdateTransactionState,
    targetVersionCode: Long?
): String {
    if (!updateRoute.deltaEnabled || state != SharedSettingsUpdateTransactionState.Downloading) {
        return ""
    }
    return buildString {
        append(HANDS_DELTA_PROGRESS_TOKEN_PREFIX)
        append(updateRoute.appSlug)
        append(':')
        append(updateRoute.channel)
        append(':')
        append(targetVersionCode ?: 0L)
    }
}

internal fun handsDeltaDownloadProgressTokenTargetVersionCode(token: String): Long? {
    if (!token.startsWith(HANDS_DELTA_PROGRESS_TOKEN_PREFIX)) return null
    return token.substringAfterLast(':').toLongOrNull()?.takeIf { it > 0L }
}

internal fun handsDeltaProgressPayload(
    token: String,
    status: HandsUpdateStatus
): Map<String, Any>? {
    if (!token.startsWith(HANDS_DELTA_PROGRESS_TOKEN_PREFIX)) return null
    val expectedTarget = handsDeltaDownloadProgressTokenTargetVersionCode(token)
    if (expectedTarget != null && status.targetVersionCode != null && status.targetVersionCode != expectedTarget) {
        return null
    }
    val progress = status.progress
    val downloadedBytes = progress?.downloadedBytes?.coerceAtLeast(0L) ?: 0L
    val totalBytes = progress?.totalBytes?.takeIf { it > 0L } ?: -1L
    return when (SharedSettingsUpdateTransactionState.fromWire(status.state.wireValue)) {
        SharedSettingsUpdateTransactionState.Downloading ->
            mapOf(
                "status" to "ok",
                "token" to token,
                "phase" to if (progress?.paused == true) "paused" else "downloading",
                "downloadedBytes" to downloadedBytes,
                "totalBytes" to totalBytes
            )
        SharedSettingsUpdateTransactionState.ReadyToInstall,
        SharedSettingsUpdateTransactionState.InstallerOpened,
        SharedSettingsUpdateTransactionState.Installed ->
            mapOf(
                "status" to "ok",
                "token" to token,
                "phase" to "ready",
                "downloadedBytes" to downloadedBytes,
                "totalBytes" to totalBytes
            )
        SharedSettingsUpdateTransactionState.Failed,
        SharedSettingsUpdateTransactionState.Stale ->
            mapOf(
                "status" to "ok",
                "token" to token,
                "phase" to "failed"
            )
        SharedSettingsUpdateTransactionState.Idle,
        SharedSettingsUpdateTransactionState.Checking,
        SharedSettingsUpdateTransactionState.Patching,
        SharedSettingsUpdateTransactionState.Unknown -> null
    }
}

internal fun handsUpdateLanguageTag(
    appLanguageTag: String?,
    systemLanguageTag: String?
): String =
    appLanguageTag.normalizedHandsLanguageTag()
        ?: systemLanguageTag.normalizedHandsLanguageTag()
        ?: "en"

private fun String?.normalizedHandsLanguageTag(): String? =
    this
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf {
            it.isNotBlank() &&
                !it.equals("system", ignoreCase = true) &&
                !it.equals("C", ignoreCase = true) &&
                !it.equals("POSIX", ignoreCase = true)
        }

internal data class HandsUpdateBridgeDecision(
    val state: SharedSettingsUpdateTransactionState,
    val targetVersionCode: Long?,
    val installedVersionCode: Long?,
    val updateAvailable: Boolean,
    val retryable: Boolean,
    val targetDrifted: Boolean
)

internal data class HandsUpdateTargetIdentity(
    val versionCode: Long?,
    val buildId: String?,
    val assetSha256: String?
) {
    fun differsFrom(other: HandsUpdateTargetIdentity): Boolean {
        if (versionCode == null || other.versionCode == null) return false
        return versionCode != other.versionCode ||
            normalizedBuildId(buildId).differsWhenComparable(normalizedBuildId(other.buildId)) ||
            normalizedAssetSha256(assetSha256).differsWhenComparable(normalizedAssetSha256(other.assetSha256))
    }
}

internal fun handsUpdateBridgeDecision(
    sdkStateWire: String,
    sdkTarget: HandsUpdateTargetIdentity,
    sdkInstalledVersionCode: Long?,
    sdkRetryable: Boolean,
    offerChecked: Boolean,
    offerAvailable: Boolean,
    offerTarget: HandsUpdateTargetIdentity
): HandsUpdateBridgeDecision {
    val sdkState = SharedSettingsUpdateTransactionState.fromWire(sdkStateWire)
    val installedTarget =
        sdkTarget.versionCode != null &&
            sdkInstalledVersionCode != null &&
            sdkInstalledVersionCode >= sdkTarget.versionCode
    val targetDrifted =
        !installedTarget &&
            sdkState.hasInFlightTarget() &&
            sdkTarget.versionCode != null &&
            offerChecked &&
            (!offerAvailable || sdkTarget.differsFrom(offerTarget))
    val state =
        when {
            installedTarget || sdkState == SharedSettingsUpdateTransactionState.Installed ->
                SharedSettingsUpdateTransactionState.Installed
            targetDrifted -> SharedSettingsUpdateTransactionState.Stale
            else -> sdkState
        }
    val updateAvailable =
        when {
            state == SharedSettingsUpdateTransactionState.Installed -> false
            targetDrifted ->
                offerAvailable &&
                    offerTarget.versionCode != null &&
                    (sdkInstalledVersionCode == null || offerTarget.versionCode > sdkInstalledVersionCode)
            sdkState.hasInFlightTarget() ->
                (sdkTarget.versionCode ?: offerTarget.versionCode)?.let { target ->
                    (!offerChecked || offerAvailable) &&
                        (sdkInstalledVersionCode == null || target > sdkInstalledVersionCode)
                } == true
            offerChecked -> offerAvailable
            else -> false
        }
    return HandsUpdateBridgeDecision(
        state = state,
        targetVersionCode =
            if (targetDrifted && offerAvailable) {
                offerTarget.versionCode
            } else {
                sdkTarget.versionCode ?: offerTarget.versionCode
            },
        installedVersionCode = sdkInstalledVersionCode,
        updateAvailable = updateAvailable,
        retryable =
            when {
                state == SharedSettingsUpdateTransactionState.Installed -> false
                targetDrifted -> updateAvailable
                else -> sdkRetryable
            },
        targetDrifted = targetDrifted
    )
}

/** Reported for a waiting update when the build does not declare REQUEST_INSTALL_PACKAGES. */
internal const val HANDS_DELTA_INSTALL_UNSUPPORTED = "install_unsupported"

/** How the Hands delta route (Alpha, Preview, and Main) shows its transaction. */
internal enum class HandsDeltaInstallPresentation {
    /** The state's own status and detail. */
    AsReported,

    /** "Permission required", with the copy that leads to Raft's "Install unknown apps" switch. */
    PermissionRequired,

    /** No REQUEST_INSTALL_PACKAGES in the manifest: no switch for Raft, the installer cannot open. */
    InstallUnsupported
}

internal data class HandsDeltaInstallPermissionDecision(
    /** Install leads to the switch (explained first) instead of calling `UpdateChecker.install()`. */
    val openPermissionSettings: Boolean,
    val presentation: HandsDeltaInstallPresentation,
    /** The error code the bridge reports for the transaction. */
    val errorCode: String?
)

/**
 * The Hands delta route (Alpha, Preview, and Main) while Android may block installs from Raft.
 * Since hands-android-sdk 0.14
 * an update downloaded or patched without that permission stops at READY_TO_INSTALL with
 * [INSTALL_PERMISSION_REQUIRED] and the SDK opens nothing; another `install()` only checks Hands
 * again. The code stays until the installer opens, so it is read against [canInstall]
 * (`UpdateChecker.canInstallUpdates()`) at the time of the call:
 *
 * - An APK waits (ready, or opened before) and Raft may not install: Install leads to the switch,
 *   and the update shows "Permission required". Without the permission in the manifest
 *   ([permissionDeclared] false) there is no switch to lead to: nothing opens.
 * - The permission code once Raft may install, or on a transaction that no longer waits: dropped,
 *   so a granted permission shows a plain Install.
 * - Anything else as the SDK reports it. A download is not held back: the permission is asked for
 *   once the APK is ready.
 */
internal fun handsDeltaInstallPermissionDecision(
    state: SharedSettingsUpdateTransactionState,
    errorCode: String?,
    canInstall: Boolean,
    permissionDeclared: Boolean
): HandsDeltaInstallPermissionDecision {
    val waiting =
        state == SharedSettingsUpdateTransactionState.ReadyToInstall ||
            state == SharedSettingsUpdateTransactionState.InstallerOpened
    return when {
        waiting && !canInstall && permissionDeclared ->
            HandsDeltaInstallPermissionDecision(
                openPermissionSettings = true,
                presentation = HandsDeltaInstallPresentation.PermissionRequired,
                errorCode = INSTALL_PERMISSION_REQUIRED
            )
        waiting && !canInstall ->
            HandsDeltaInstallPermissionDecision(
                openPermissionSettings = false,
                presentation = HandsDeltaInstallPresentation.InstallUnsupported,
                errorCode = HANDS_DELTA_INSTALL_UNSUPPORTED
            )
        else ->
            HandsDeltaInstallPermissionDecision(
                openPermissionSettings = false,
                presentation = HandsDeltaInstallPresentation.AsReported,
                errorCode = errorCode.takeUnless { it == INSTALL_PERMISSION_REQUIRED }
            )
    }
}

/** What one resume did about an update that waited for the install permission. */
internal enum class HandsDeltaPermissionResume(val diagnosticName: String) {
    /** Nothing waited (every ordinary resume). */
    NotWaiting("not_waiting"),

    /** Raft may install now: the waiting installer was reopened. */
    Reopened("reopened"),

    /** Still not allowed: nothing opens; Install leads to the switch again only when tapped. */
    StillDenied("still_denied")
}

/**
 * One resume after Raft led to its "Install unknown apps" switch ([marker]). Reopens the waiting
 * installer ([reopen], `UpdateChecker.reopenPendingInstaller()`: no new check, no new download) when
 * [canInstall] now. The marker is taken either way, so a denial never leads back into Settings.
 */
internal suspend fun handsDeltaPermissionResume(
    marker: RaftAppUpdatePermissionResumeRepository,
    canInstall: () -> Boolean,
    reopen: suspend () -> Unit
): HandsDeltaPermissionResume {
    if (!marker.take()) return HandsDeltaPermissionResume.NotWaiting
    if (!canInstall()) return HandsDeltaPermissionResume.StillDenied
    reopen()
    return HandsDeltaPermissionResume.Reopened
}

private fun normalizedBuildId(value: String?): String? =
    value?.trim()?.takeIf(String::isNotBlank)

private fun normalizedAssetSha256(value: String?): String? =
    value?.trim()?.lowercase()?.takeIf(String::isNotBlank)

private fun String?.differsWhenComparable(other: String?): Boolean =
    this != null && other != null && this != other

private fun SharedSettingsUpdateTransactionState.hasInFlightTarget(): Boolean =
    when (this) {
        SharedSettingsUpdateTransactionState.Checking,
        SharedSettingsUpdateTransactionState.Patching,
        SharedSettingsUpdateTransactionState.Downloading,
        SharedSettingsUpdateTransactionState.ReadyToInstall,
        SharedSettingsUpdateTransactionState.InstallerOpened -> true
        SharedSettingsUpdateTransactionState.Unknown,
        SharedSettingsUpdateTransactionState.Idle,
        SharedSettingsUpdateTransactionState.Failed,
        SharedSettingsUpdateTransactionState.Stale,
        SharedSettingsUpdateTransactionState.Installed -> false
    }

internal fun handsUpdateEventLogLine(
    name: String,
    stateWire: String,
    targetVersionCode: Long?,
    errorCode: String?,
    requestedLanguageTag: String?,
    resolvedLanguageTag: String?
): String =
    "update_event " +
        "name=${name.handsLogToken()} " +
        "state=${stateWire.handsLogToken()} " +
        "target=${targetVersionCode ?: 0L} " +
        "error=${errorCode.handsLogToken()} " +
        "requested_lang=${requestedLanguageTag.handsLogToken()} " +
        "resolved_lang=${resolvedLanguageTag.handsLogToken()}"

internal fun handsUpdateEventShouldRefresh(name: String): Boolean =
    name == "state_changed" || name == "patch_started" || name == "patch_fallback"

private fun String?.handsLogToken(): String =
    this
        ?.takeIf { it.isNotBlank() }
        ?.map { character ->
            if (character.isLetterOrDigit() || character in "._-") character else '_'
        }
        ?.joinToString("")
        ?.take(80)
        ?: "-"
