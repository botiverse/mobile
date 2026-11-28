@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.webcontainer

import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSBundle
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKSecurityOrigin

/** Converts WebKit's [WKMediaCaptureType] into the cross-platform [WebMediaCaptureKind]. */
fun WKMediaCaptureType.toWebMediaCaptureKind(): WebMediaCaptureKind =
    when (this) {
        WKMediaCaptureType.WKMediaCaptureTypeCamera -> WebMediaCaptureKind.Camera
        WKMediaCaptureType.WKMediaCaptureTypeMicrophone -> WebMediaCaptureKind.Microphone
        WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone -> WebMediaCaptureKind.CameraAndMicrophone
    }

/** Maps a boolean audio capture grant decision into WebKit's [WKPermissionDecision]. */
fun Boolean.toIosMediaCapturePermissionDecision(): WKPermissionDecision =
    if (this) WKPermissionDecision.WKPermissionDecisionGrant else WKPermissionDecision.WKPermissionDecisionDeny

/** Strictly parses a `WKSecurityOrigin` into a normalized [WebContainerOrigin], or `null` when opaque/malformed. */
fun parseIosSecurityOrigin(origin: WKSecurityOrigin): WebContainerOrigin? =
    parseWebSecurityOrigin(
        protocol = origin.protocol,
        host = origin.host,
        port = origin.port.toLong(),
    )

/** Formats a `WKSecurityOrigin` for diagnostics without query strings or paths. */
fun formatIosSecurityOriginForLog(origin: WKSecurityOrigin): String {
    val protocol = origin.protocol.trim().lowercase()
    val rawHost = origin.host.trim().lowercase()
    if (protocol.isEmpty() || rawHost.isEmpty()) return "<opaque>"
    val host = if (rawHost.contains(':') && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
    val port = origin.port
    return if (port > 0L) "$protocol://$host:$port" else "$protocol://$host"
}

/**
 * Returns `true` only when [bundle] declares a non-blank string for [key] in both its base
 * `Info.plist` ([NSBundle.infoDictionary]) and its effective localized info dictionary
 * ([NSBundle.objectForInfoDictionaryKey]), preventing iOS `tccd` `SIGABRT` crashes.
 */
fun iosBundleHasUsageDescription(
    key: String = IOS_MICROPHONE_USAGE_DESCRIPTION_KEY,
    bundle: NSBundle = NSBundle.mainBundle,
): Boolean {
    val rawInfo = runCatching { bundle.infoDictionary?.get(key) }.getOrNull()
    val effective = runCatching { bundle.objectForInfoDictionaryKey(key) }.getOrNull()
    return isValidIosUsageDescription(rawInfo) && isValidIosUsageDescription(effective)
}

/** Reads iOS's current OS-level microphone permission via `AVCaptureDevice.authorizationStatusForMediaType`. */
fun iosMicrophoneAuthorizationStatus(): WebOsMicrophonePermissionStatus =
    when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio)) {
        AVAuthorizationStatusAuthorized -> WebOsMicrophonePermissionStatus.Authorized
        AVAuthorizationStatusNotDetermined -> WebOsMicrophonePermissionStatus.NotDetermined
        AVAuthorizationStatusRestricted -> WebOsMicrophonePermissionStatus.Restricted
        AVAuthorizationStatusDenied -> WebOsMicrophonePermissionStatus.Denied
        else -> WebOsMicrophonePermissionStatus.Denied
    }

/** Requests iOS OS-level microphone access via `AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio)`. */
fun iosRequestMicrophoneAccess(onResult: (Boolean) -> Unit) {
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio) { granted ->
        onResult(granted)
    }
}
