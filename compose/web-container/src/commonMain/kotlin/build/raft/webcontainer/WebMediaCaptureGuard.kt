package build.raft.webcontainer

/** Media capture permission kinds requested by a web document (e.g. iOS WebKit `WKMediaCaptureType`). */
enum class WebMediaCaptureKind {
    Camera,
    Microphone,
    CameraAndMicrophone,
    Unknown,
}

/**
 * Host decision for an iOS `WKUIDelegate` media capture permission request.
 *
 * Returning [Prompt] forwards the request to WebKit's user consent dialog only after verifying
 * that every required `Info.plist` TCC usage description key is declared and non-blank.
 * Returning [Deny] fails closed before WebKit calls `tccd`, preventing an uncatchable TCC
 * privacy `SIGABRT` (Hands iOS `22cc5a87` / `1fa0888e`, `#wg-mobile-feedback:#308`).
 */
sealed interface IosWebMediaCaptureDecision {
    data object Prompt : IosWebMediaCaptureDecision

    data class Deny(
        val reason: String,
        val missingKeys: List<String> = emptyList(),
    ) : IosWebMediaCaptureDecision
}

/** iOS `Info.plist` usage description key required before prompting for camera capture. */
const val IOS_CAMERA_USAGE_DESCRIPTION_KEY: String = "NSCameraUsageDescription"

/** iOS `Info.plist` usage description key required before prompting for microphone capture. */
const val IOS_MICROPHONE_USAGE_DESCRIPTION_KEY: String = "NSMicrophoneUsageDescription"

/**
 * Maps ABI-stable WebKit `WKMediaCaptureType` raw values (`0` = Camera, `1` = Microphone,
 * `2` = CameraAndMicrophone) to [WebMediaCaptureKind], returning [WebMediaCaptureKind.Unknown]
 * for any unrecognized value.
 */
fun webMediaCaptureKindFromRaw(rawValue: Long): WebMediaCaptureKind =
    when (rawValue) {
        0L -> WebMediaCaptureKind.Camera
        1L -> WebMediaCaptureKind.Microphone
        2L -> WebMediaCaptureKind.CameraAndMicrophone
        else -> WebMediaCaptureKind.Unknown
    }

/**
 * Returns the iOS `Info.plist` usage description keys required before WebKit may prompt for
 * [kind]. Returns an empty list for [WebMediaCaptureKind.Unknown] (which always fails closed).
 */
fun requiredIosUsageDescriptionKeys(kind: WebMediaCaptureKind): List<String> =
    when (kind) {
        WebMediaCaptureKind.Camera -> listOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY)
        WebMediaCaptureKind.Microphone -> listOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY)
        WebMediaCaptureKind.CameraAndMicrophone ->
            listOf(IOS_CAMERA_USAGE_DESCRIPTION_KEY, IOS_MICROPHONE_USAGE_DESCRIPTION_KEY)
        WebMediaCaptureKind.Unknown -> emptyList()
    }

/** Returns `true` when [rawValue] is a non-blank [String] usage description from `Info.plist`. */
fun isValidIosUsageDescription(rawValue: Any?): Boolean =
    (rawValue as? String)?.isNotBlank() == true

/**
 * Evaluates whether an iOS `WKWebView` may forward a media capture permission request of [kind]
 * to WebKit's prompt or must fail closed and deny before `tccd` is invoked.
 *
 * Returns [IosWebMediaCaptureDecision.Prompt] ONLY when [kind] != [WebMediaCaptureKind.Unknown]
 * and [hasUsageDescription] returns `true` for every key in [requiredIosUsageDescriptionKeys].
 */
fun evaluateIosWebMediaCapturePermission(
    kind: WebMediaCaptureKind,
    hasUsageDescription: (String) -> Boolean,
): IosWebMediaCaptureDecision {
    if (kind == WebMediaCaptureKind.Unknown) {
        return IosWebMediaCaptureDecision.Deny(
            reason = "unknown_media_capture_type",
            missingKeys = emptyList(),
        )
    }
    val missingKeys =
        requiredIosUsageDescriptionKeys(kind).filterNot { key ->
            runCatching { hasUsageDescription(key) }.getOrDefault(false)
        }
    if (missingKeys.isNotEmpty()) {
        return IosWebMediaCaptureDecision.Deny(
            reason = "missing_usage_description",
            missingKeys = missingKeys,
        )
    }
    return IosWebMediaCaptureDecision.Prompt
}
