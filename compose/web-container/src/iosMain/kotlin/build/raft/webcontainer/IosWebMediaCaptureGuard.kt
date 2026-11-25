@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.webcontainer

import platform.Foundation.NSBundle
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKSecurityOrigin

/**
 * Returns `true` only when [bundle] declares a non-blank string for [key] in both its base
 * `Info.plist` ([NSBundle.infoDictionary]) and its effective localized info dictionary
 * ([NSBundle.objectForInfoDictionaryKey]).
 *
 * Checking both fails closed if either the base `Info.plist` entry is absent (which causes iOS
 * `tccd` to abort with `SIGABRT` even if a localized strings file exists) or a localization
 * overrides the key with a blank string.
 */
fun iosBundleHasUsageDescription(
    key: String,
    bundle: NSBundle = NSBundle.mainBundle,
): Boolean {
    val rawInfo = runCatching { bundle.infoDictionary?.get(key) }.getOrNull()
    val effective = runCatching { bundle.objectForInfoDictionaryKey(key) }.getOrNull()
    return isValidIosUsageDescription(rawInfo) && isValidIosUsageDescription(effective)
}

/** Converts WebKit's [WKMediaCaptureType] into the cross-platform [WebMediaCaptureKind]. */
fun WKMediaCaptureType.toWebMediaCaptureKind(): WebMediaCaptureKind =
    when (this) {
        WKMediaCaptureType.WKMediaCaptureTypeCamera -> WebMediaCaptureKind.Camera
        WKMediaCaptureType.WKMediaCaptureTypeMicrophone -> WebMediaCaptureKind.Microphone
        WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone -> WebMediaCaptureKind.CameraAndMicrophone
    }

/** Converts [IosWebMediaCaptureDecision] into WebKit's [WKPermissionDecision]. */
fun IosWebMediaCaptureDecision.toWkPermissionDecision(): WKPermissionDecision =
    when (this) {
        IosWebMediaCaptureDecision.Prompt -> WKPermissionDecision.WKPermissionDecisionPrompt
        is IosWebMediaCaptureDecision.Deny -> WKPermissionDecision.WKPermissionDecisionDeny
    }

/** Formats a `WKSecurityOrigin` for diagnostics without query strings or paths. */
fun formatIosSecurityOriginForLog(origin: WKSecurityOrigin): String {
    val protocol = origin.protocol.trim().lowercase()
    val rawHost = origin.host.trim().lowercase()
    if (protocol.isEmpty() || rawHost.isEmpty()) return "<opaque>"
    val host = if (rawHost.contains(':') && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
    val port = origin.port
    return if (port > 0L) "$protocol://$host:$port" else "$protocol://$host"
}
