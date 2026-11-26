@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.webcontainer

import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKSecurityOrigin

/** Converts WebKit's [WKMediaCaptureType] into the cross-platform [WebMediaCaptureKind]. */
fun WKMediaCaptureType.toWebMediaCaptureKind(): WebMediaCaptureKind =
    when (this) {
        WKMediaCaptureType.WKMediaCaptureTypeCamera -> WebMediaCaptureKind.Camera
        WKMediaCaptureType.WKMediaCaptureTypeMicrophone -> WebMediaCaptureKind.Microphone
        WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone -> WebMediaCaptureKind.CameraAndMicrophone
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
