package build.raft.app.data

import build.raft.app.base.BridgeModule
import build.raft.app.toJsonObject
import build.raft.app.compat.json.JSONObject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/*
 * Kuikly entry points into the host-agnostic data layer. Data classes take [build.raft.app.host.HostCapabilities];
 * Kuikly pages hold a [BridgeModule], so these keep their existing `bridge = …` call sites unchanged.
 */

internal fun BridgeSettingsPlatformCapabilities(
    bridge: BridgeModule,
    copiedMessage: String,
    bridgeCallContext: CoroutineContext = EmptyCoroutineContext,
    feedbackVisualSubmission: SettingsHandsFeedbackSubmission? = null,
    openUrlHandler: ((String) -> Unit)? = null,
): BridgeSettingsPlatformCapabilities =
    BridgeSettingsPlatformCapabilities(
        host = bridge.hostCapabilities,
        copiedMessage = copiedMessage,
        bridgeCallContext = bridgeCallContext,
        feedbackVisualSubmission = feedbackVisualSubmission,
        openUrlHandler = openUrlHandler,
    )

internal fun BridgeSlockAvatarNativeCache(
    bridge: BridgeModule,
    bridgeCallContext: CoroutineContext,
): BridgeSlockAvatarNativeCache =
    BridgeSlockAvatarNativeCache(host = bridge.hostCapabilities, bridgeCallContext = bridgeCallContext)

/** Kuikly bridge callbacks still hand pages a Kuikly `JSONObject`. */
internal fun parseSharedThreadPickedPlatformFiles(response: JSONObject?): List<PlatformFile> =
    parseSharedThreadPickedPlatformFiles(response?.toJsonObject())
