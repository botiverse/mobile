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

/** iOS `Info.plist` key required before touching `AVCaptureDevice` / WebKit microphone capture. */
const val IOS_MICROPHONE_USAGE_DESCRIPTION_KEY: String = "NSMicrophoneUsageDescription"

/** Platform OS microphone authorization status mapped from iOS `AVAuthorizationStatus`. */
enum class WebOsMicrophonePermissionStatus {
    Authorized,
    NotDetermined,
    Denied,
    Restricted,
}

/** Returns `true` when [rawValue] is a non-blank [String] usage description from `Info.plist`. */
fun isValidIosUsageDescription(rawValue: Any?): Boolean =
    (rawValue as? String)?.isNotBlank() == true

/**
 * Strictly parses `(protocol, host, port)` components (such as iOS WebKit's `WKSecurityOrigin`,
 * where `port == 0` denotes the scheme's default port) into a normalized [WebContainerOrigin],
 * or returns `null` when opaque, blank, malformed, or out of range.
 */
fun parseWebSecurityOrigin(
    protocol: String?,
    host: String?,
    port: Long = 0L,
): WebContainerOrigin? {
    val cleanProtocol = protocol?.trim()?.lowercase().orEmpty()
    val cleanHost = host?.trim()?.lowercase().orEmpty()
    if (cleanProtocol.isEmpty() || cleanHost.isEmpty()) return null
    if (port < 0L || port > 65535L) return null
    if (
        cleanHost.any {
            it.code < 0x20 || it.isWhitespace() || it == '@' || it == '\\' || it == '/' || it == '?' || it == '#'
        }
    ) {
        return null
    }
    val formattedHost =
        when {
            cleanHost.startsWith("[") -> {
                if (!cleanHost.endsWith("]") || cleanHost.length <= 2) return null
                cleanHost
            }
            cleanHost.contains(':') -> {
                // Bare IPv6 literals always contain at least two colons; reject `host:port` smuggling.
                if (cleanHost.count { it == ':' } < 2) return null
                "[$cleanHost]"
            }
            else -> cleanHost
        }
    val raw = if (port > 0L) "$cleanProtocol://$formattedHost:$port" else "$cleanProtocol://$formattedHost"
    return parseWebContainerOrigin(raw)
}

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

/**
 * Evaluates whether a media capture request carrying explicit [kind] and [isMainFrame] metadata
 * (such as iOS `WKUIDelegate.webView(_:requestMediaCapturePermissionFor:initiatedByFrame:type:decisionHandler:)`)
 * is eligible for own-origin audio-only capture (`#wg-mobile-sync:#362`).
 *
 * Fail-closed contract:
 * - [allowedOrigin] must be explicitly configured (non-null) with an `http` or `https` scheme.
 * - [kind] must be strictly [WebMediaCaptureKind.Microphone] (Camera, CameraAndMicrophone, and
 *   Unknown are denied immediately).
 * - [isMainFrame] must be `true` (all subframes / iframes, including sandboxed content frames,
 *   are denied immediately).
 * - Both [requestOrigin] and [frameOrigin] must parse cleanly (non-null) and match [allowedOrigin]
 *   strictly on scheme, host, and effective port.
 */
fun evaluateWebMediaCaptureAudioPermission(
    kind: WebMediaCaptureKind,
    isMainFrame: Boolean,
    requestOrigin: WebContainerOrigin?,
    allowedOrigin: WebContainerOrigin?,
    frameOrigin: WebContainerOrigin? = requestOrigin,
): WebAudioCapturePermissionDecision {
    if (allowedOrigin == null) {
        return WebAudioCapturePermissionDecision.Deny(WEB_MEDIA_CAPTURE_DENIED_REASON)
    }
    if (allowedOrigin.scheme != "https" && allowedOrigin.scheme != "http") {
        return WebAudioCapturePermissionDecision.Deny("scheme_not_allowed")
    }
    if (kind != WebMediaCaptureKind.Microphone) {
        return WebAudioCapturePermissionDecision.Deny("non_audio_resource_requested")
    }
    if (!isMainFrame) {
        return WebAudioCapturePermissionDecision.Deny("subframe_not_allowed")
    }
    if (requestOrigin == null || frameOrigin == null) {
        return WebAudioCapturePermissionDecision.Deny("invalid_request_origin")
    }
    if (
        requestOrigin.scheme != allowedOrigin.scheme ||
        requestOrigin.host != allowedOrigin.host ||
        requestOrigin.effectivePort != allowedOrigin.effectivePort ||
        frameOrigin.scheme != allowedOrigin.scheme ||
        frameOrigin.host != allowedOrigin.host ||
        frameOrigin.effectivePort != allowedOrigin.effectivePort
    ) {
        return WebAudioCapturePermissionDecision.Deny("origin_not_allowed")
    }
    return WebAudioCapturePermissionDecision.AllowAudioCapture(requestOrigin)
}

/**
 * Fail-closed coordinator for own-origin audio-only WebView media capture permission requests
 * (shared by iOS `WebContainerIosHost` and `RaftIosSandboxWebView`).
 *
 * Guarantees:
 * - Every incoming [deliverDecision] callback is invoked **strictly once** on the host's main
 *   thread (via [runOnMainThread]), never dropped and never double-invoked, satisfying WebKit's
 *   `WKUIDelegate` `decisionHandler` contract across every exit path.
 * - Foreground-active checks, [evaluateWebMediaCaptureAudioPermission], and [hasUsageDescription]
 *   run synchronously before touching OS permission APIs; any failure denies immediately.
 * - `Authorized` grants immediately; `Denied` / `Restricted` deny immediately without prompting.
 * - `NotDetermined` tracks at most one in-flight OS request; superseding requests, [cancelAll]
 *   (dispose, keep-alive park, teardown, content-process termination, main-frame navigation,
 *   window detach, backgrounding, or policy change), or OS prompt completion settle the callback
 *   exactly once on the main thread.
 * - Right before granting after an async OS prompt resolves, re-verifies foreground-active state
 *   and exact origin policy match against [allowedOriginProvider]; otherwise denies cleanly.
 */
class WebMediaCaptureAudioPermissionController(
    private val allowedOriginProvider: () -> WebContainerOrigin?,
    private val isHostForegroundActive: () -> Boolean,
    private val hasUsageDescription: () -> Boolean,
    private val osAuthorizationStatus: () -> WebOsMicrophonePermissionStatus,
    private val requestOsPermission: (onResult: (Boolean) -> Unit) -> Unit,
    private val runOnMainThread: (() -> Unit) -> Unit,
    private val onDecision: (
        (
            granted: Boolean,
            kind: WebMediaCaptureKind,
            reason: String,
            missingKeys: List<String>,
            origin: String,
            isMainFrame: Boolean,
        ) -> Unit
    )? = null,
) {
    private class PendingRequest(
        val kind: WebMediaCaptureKind,
        val isMainFrame: Boolean,
        val requestOrigin: WebContainerOrigin,
        val frameOrigin: WebContainerOrigin,
        val originLabel: String,
        private val deliverDecision: (Boolean) -> Unit,
    ) {
        private val lock = createWebContainerLock()
        private var settled: Boolean = false

        fun settleOnce(granted: Boolean): Boolean {
            val shouldDeliver =
                lock.withLock {
                    if (settled) {
                        false
                    } else {
                        settled = true
                        true
                    }
                }
            if (shouldDeliver) {
                deliverDecision(granted)
            }
            return shouldDeliver
        }
    }

    private val stateLock = createWebContainerLock()
    private var activePending: PendingRequest? = null

    val hasPendingRequest: Boolean
        get() = stateLock.withLock { activePending != null }

    fun handleRequest(
        kind: WebMediaCaptureKind,
        isMainFrame: Boolean,
        requestOrigin: WebContainerOrigin?,
        frameOrigin: WebContainerOrigin? = requestOrigin,
        originLabel: String = requestOrigin?.asOriginString() ?: "<opaque>",
        deliverDecision: (granted: Boolean) -> Unit,
    ) {
        val safeDeliverOnMain: (Boolean) -> Unit = { granted ->
            runOnMainThread { deliverDecision(granted) }
        }

        if (!isHostForegroundActive()) {
            emitDecision(false, kind, "host_inactive", emptyList(), originLabel, isMainFrame)
            safeDeliverOnMain(false)
            return
        }

        val policyDecision =
            evaluateWebMediaCaptureAudioPermission(
                kind = kind,
                isMainFrame = isMainFrame,
                requestOrigin = requestOrigin,
                allowedOrigin = allowedOriginProvider(),
                frameOrigin = frameOrigin,
            )
        if (policyDecision !is WebAudioCapturePermissionDecision.AllowAudioCapture) {
            val reason = (policyDecision as WebAudioCapturePermissionDecision.Deny).reason
            emitDecision(false, kind, reason, emptyList(), originLabel, isMainFrame)
            safeDeliverOnMain(false)
            return
        }

        val canonicalOrigin = policyDecision.origin.asOriginString()
        if (!runCatching { hasUsageDescription() }.getOrDefault(false)) {
            emitDecision(
                granted = false,
                kind = kind,
                reason = "missing_usage_description",
                missingKeys = listOf(IOS_MICROPHONE_USAGE_DESCRIPTION_KEY),
                origin = canonicalOrigin,
                isMainFrame = isMainFrame,
            )
            safeDeliverOnMain(false)
            return
        }

        val status = runCatching { osAuthorizationStatus() }.getOrElse { WebOsMicrophonePermissionStatus.Denied }
        when (status) {
            WebOsMicrophonePermissionStatus.Authorized -> {
                emitDecision(
                    granted = true,
                    kind = kind,
                    reason = "granted",
                    missingKeys = emptyList(),
                    origin = canonicalOrigin,
                    isMainFrame = isMainFrame,
                )
                safeDeliverOnMain(true)
                return
            }
            WebOsMicrophonePermissionStatus.Denied -> {
                emitDecision(
                    granted = false,
                    kind = kind,
                    reason = "os_permission_denied",
                    missingKeys = emptyList(),
                    origin = canonicalOrigin,
                    isMainFrame = isMainFrame,
                )
                safeDeliverOnMain(false)
                return
            }
            WebOsMicrophonePermissionStatus.Restricted -> {
                emitDecision(
                    granted = false,
                    kind = kind,
                    reason = "os_permission_restricted",
                    missingKeys = emptyList(),
                    origin = canonicalOrigin,
                    isMainFrame = isMainFrame,
                )
                safeDeliverOnMain(false)
                return
            }
            WebOsMicrophonePermissionStatus.NotDetermined -> Unit
        }

        val pending =
            PendingRequest(
                kind = kind,
                isMainFrame = isMainFrame,
                requestOrigin = policyDecision.origin,
                frameOrigin = frameOrigin ?: policyDecision.origin,
                originLabel = canonicalOrigin,
                deliverDecision = safeDeliverOnMain,
            )
        val superseded =
            stateLock.withLock {
                val previous = activePending
                activePending = pending
                previous
            }
        if (superseded != null && superseded.settleOnce(false)) {
            emitDecision(
                granted = false,
                kind = superseded.kind,
                reason = "request_superseded",
                missingKeys = emptyList(),
                origin = superseded.originLabel,
                isMainFrame = superseded.isMainFrame,
            )
        }

        val started =
            runCatching {
                requestOsPermission { osGranted ->
                    runOnMainThread {
                        completePendingOnMain(pending = pending, osGranted = osGranted)
                    }
                }
                true
            }.getOrDefault(false)

        if (!started) {
            val removed =
                stateLock.withLock {
                    if (activePending === pending) {
                        activePending = null
                        true
                    } else {
                        false
                    }
                }
            if (removed && pending.settleOnce(false)) {
                emitDecision(
                    granted = false,
                    kind = pending.kind,
                    reason = "os_permission_request_failed",
                    missingKeys = emptyList(),
                    origin = pending.originLabel,
                    isMainFrame = pending.isMainFrame,
                )
            }
        }
    }

    fun cancelAll(reason: String = "canceled") {
        val current =
            stateLock.withLock {
                val old = activePending
                activePending = null
                old
            } ?: return
        if (current.settleOnce(false)) {
            emitDecision(
                granted = false,
                kind = current.kind,
                reason = reason,
                missingKeys = emptyList(),
                origin = current.originLabel,
                isMainFrame = current.isMainFrame,
            )
        }
    }

    private fun completePendingOnMain(
        pending: PendingRequest,
        osGranted: Boolean,
    ) {
        val stillActive =
            stateLock.withLock {
                if (activePending === pending) {
                    activePending = null
                    true
                } else {
                    false
                }
            }
        if (!stillActive) {
            pending.settleOnce(false)
            return
        }
        if (!osGranted) {
            emitDecision(
                granted = false,
                kind = pending.kind,
                reason = "os_permission_denied",
                missingKeys = emptyList(),
                origin = pending.originLabel,
                isMainFrame = pending.isMainFrame,
            )
            pending.settleOnce(false)
            return
        }
        if (!isHostForegroundActive()) {
            emitDecision(
                granted = false,
                kind = pending.kind,
                reason = "host_inactive_after_prompt",
                missingKeys = emptyList(),
                origin = pending.originLabel,
                isMainFrame = pending.isMainFrame,
            )
            pending.settleOnce(false)
            return
        }
        val recheck =
            evaluateWebMediaCaptureAudioPermission(
                kind = pending.kind,
                isMainFrame = pending.isMainFrame,
                requestOrigin = pending.requestOrigin,
                allowedOrigin = allowedOriginProvider(),
                frameOrigin = pending.frameOrigin,
            )
        if (recheck !is WebAudioCapturePermissionDecision.AllowAudioCapture) {
            val reason = (recheck as WebAudioCapturePermissionDecision.Deny).reason
            emitDecision(
                granted = false,
                kind = pending.kind,
                reason = reason,
                missingKeys = emptyList(),
                origin = pending.originLabel,
                isMainFrame = pending.isMainFrame,
            )
            pending.settleOnce(false)
            return
        }
        emitDecision(
            granted = true,
            kind = pending.kind,
            reason = "granted",
            missingKeys = emptyList(),
            origin = recheck.origin.asOriginString(),
            isMainFrame = pending.isMainFrame,
        )
        pending.settleOnce(true)
    }

    private fun emitDecision(
        granted: Boolean,
        kind: WebMediaCaptureKind,
        reason: String,
        missingKeys: List<String>,
        origin: String,
        isMainFrame: Boolean,
    ) {
        runCatching {
            onDecision?.invoke(granted, kind, reason, missingKeys, origin, isMainFrame)
        }
    }
}
