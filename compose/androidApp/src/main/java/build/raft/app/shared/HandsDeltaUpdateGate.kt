package build.raft.app.shared

import build.hands.update.models.UpdateCheckResponse
import build.raft.app.data.SharedSettingsUpdateTransactionState
import java.util.concurrent.atomic.AtomicBoolean

internal const val HANDS_DELTA_ALPHA_APPLICATION_ID = "build.raft.app.alpha"
internal const val HANDS_DELTA_ALPHA_APP_SLUG = "raft-android-alpha-delta"
internal const val HANDS_DELTA_ALPHA_CHANNEL = "alpha-delta"
internal const val HANDS_UPDATE_MODE_ALPHA_DELTA = "alpha_delta"
internal const val HANDS_UPDATE_MODE_FULL_APK = "full_apk"

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

internal fun resolveHandsUpdateRoute(
    alphaDeltaEnabled: Boolean,
    defaultAppSlug: String,
    defaultChannel: String,
    deltaAppSlug: String,
    deltaChannel: String
): HandsUpdateRoute =
    if (alphaDeltaEnabled) {
        HandsUpdateRoute(
            mode = HANDS_UPDATE_MODE_ALPHA_DELTA,
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
    buildApplicationId: String,
    runtimePackageName: String,
    appSlug: String,
    channel: String
): Boolean =
    configuredEnabled &&
        buildApplicationId == HANDS_DELTA_ALPHA_APPLICATION_ID &&
        runtimePackageName == HANDS_DELTA_ALPHA_APPLICATION_ID &&
        appSlug == HANDS_DELTA_ALPHA_APP_SLUG &&
        channel == HANDS_DELTA_ALPHA_CHANNEL

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
    name == "state_changed"

private fun String?.handsLogToken(): String =
    this
        ?.takeIf { it.isNotBlank() }
        ?.map { character ->
            if (character.isLetterOrDigit() || character in "._-") character else '_'
        }
        ?.joinToString("")
        ?.take(80)
        ?: "-"
