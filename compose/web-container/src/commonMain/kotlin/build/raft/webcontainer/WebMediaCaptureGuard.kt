package build.raft.webcontainer

/** Media capture permission kinds requested by a web document (e.g. iOS WebKit `WKMediaCaptureType`). */
enum class WebMediaCaptureKind {
    Camera,
    Microphone,
    CameraAndMicrophone,
    Unknown,
}

/**
 * Default policy denial reason when media capture is disabled for a web surface (e.g. third-party
 * sites, sandboxed iframes, iOS WebKit `WKPermissionDecisionDeny`, or surfaces that did not opt
 * into own-origin audio capture). Recorded on the `MediaCapturePermission` telemetry event and
 * diagnostics lines.
 */
const val WEB_MEDIA_CAPTURE_DENIED_REASON: String = "web_media_capture_disabled"

/** Android WebView `PermissionRequest.RESOURCE_AUDIO_CAPTURE` identifier. */
const val WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE: String = "android.webkit.resource.AUDIO_CAPTURE"

/** Outcome of evaluating a WebView audio-capture permission request against [WebNavigationPolicy]. */
sealed interface WebAudioCapturePermissionDecision {
    data class AllowAudioCapture(val origin: WebContainerOrigin) : WebAudioCapturePermissionDecision

    data class Deny(val reason: String) : WebAudioCapturePermissionDecision
}

/**
 * Evaluates whether a WebView media permission request is eligible for own-origin audio capture
 * (`#proj-workspace:#463`).
 *
 * Fail-closed contract:
 * - [allowedOrigin] must be explicitly configured (non-null) with an `http` or `https` scheme.
 * - [requestedResources] must be non-empty and contain ONLY [audioCaptureResource] (camera/video,
 *   MIDI, protected media, unknown, or mixed `[audio, video]` requests are denied immediately).
 * - [requestOriginUrl] must parse strictly as a [WebContainerOrigin] and match [allowedOrigin]
 *   exactly on scheme, host, and effective port (subdomains, lookalikes, sandboxed content iframe
 *   domains such as `*.raftusercontent.com`, scheme downgrades, port mismatches, and opaque
 *   origins are all denied).
 */
fun evaluateWebAudioCapturePermission(
    requestOriginUrl: String?,
    requestedResources: Collection<String>,
    allowedOrigin: WebContainerOrigin?,
    audioCaptureResource: String = WEB_PERMISSION_RESOURCE_AUDIO_CAPTURE,
): WebAudioCapturePermissionDecision {
    if (allowedOrigin == null) {
        return WebAudioCapturePermissionDecision.Deny(WEB_MEDIA_CAPTURE_DENIED_REASON)
    }
    if (allowedOrigin.scheme != "https" && allowedOrigin.scheme != "http") {
        return WebAudioCapturePermissionDecision.Deny("scheme_not_allowed")
    }
    if (requestedResources.isEmpty()) {
        return WebAudioCapturePermissionDecision.Deny("empty_resources")
    }
    if (!requestedResources.all { it == audioCaptureResource }) {
        return WebAudioCapturePermissionDecision.Deny("non_audio_resource_requested")
    }
    val requestOrigin =
        requestOriginUrl?.let(::parseWebContainerOrigin)
            ?: return WebAudioCapturePermissionDecision.Deny("invalid_request_origin")
    if (
        requestOrigin.scheme != allowedOrigin.scheme ||
        requestOrigin.host != allowedOrigin.host ||
        requestOrigin.effectivePort != allowedOrigin.effectivePort
    ) {
        return WebAudioCapturePermissionDecision.Deny("origin_not_allowed")
    }
    return WebAudioCapturePermissionDecision.AllowAudioCapture(requestOrigin)
}
