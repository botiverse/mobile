package build.raft.webcontainer

/** Media capture permission kinds requested by a web document (e.g. iOS WebKit `WKMediaCaptureType`). */
enum class WebMediaCaptureKind {
    Camera,
    Microphone,
    CameraAndMicrophone,
    Unknown,
}

/**
 * Why every web page's camera / microphone request is refused: no web page gets a capture device
 * (artin, #proj-mobile:7420d9de, 2026-11-26), as Raft Desktop's built-in browser denies every
 * permission request on its session. The iOS `WKUIDelegate` answers
 * `requestMediaCapturePermissionForOrigin` with `WKPermissionDecisionDeny` before WebKit reaches
 * `tccd`, so a page never raises a system prompt and never crashes the app on a missing usage
 * description (Hands iOS `22cc5a87` / `1fa0888e`, #wg-mobile-feedback:#308). Recorded on the
 * `MediaCapturePermission` telemetry event and the diagnostics line.
 */
const val WEB_MEDIA_CAPTURE_DENIED_REASON: String = "web_media_capture_disabled"
