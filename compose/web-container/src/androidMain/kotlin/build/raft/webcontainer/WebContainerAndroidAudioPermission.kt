package build.raft.webcontainer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.PermissionRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Process-level Android `RECORD_AUDIO` runtime permission hook for own-origin WebView audio
 * capture (`#proj-workspace:#463`).
 *
 * Fails closed (`false`) whenever no host requester is installed so `:web-container` keeps zero
 * dependencies on internal Raft modules while guaranteeing Android's OS `RECORD_AUDIO` runtime
 * permission is always satisfied before any WebView audio capture grant.
 */
object WebContainerAndroidAudioPermissionBridge {
    @Volatile
    var requester: (suspend (Context) -> Boolean)? = null

    suspend fun ensureRecordAudioPermission(context: Context): Boolean {
        val activeRequester = requester ?: return false
        val appContext = context.applicationContext ?: context
        return activeRequester(appContext)
    }
}

internal fun classifyAndroidPermissionResources(resources: Collection<String>): WebMediaCaptureKind {
    var hasAudio = false
    var hasVideo = false
    var hasOther = false
    for (resource in resources) {
        when (resource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasAudio = true
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasVideo = true
            else -> hasOther = true
        }
    }
    return when {
        hasOther -> WebMediaCaptureKind.Unknown
        hasAudio && hasVideo -> WebMediaCaptureKind.CameraAndMicrophone
        hasVideo -> WebMediaCaptureKind.Camera
        hasAudio -> WebMediaCaptureKind.Microphone
        else -> WebMediaCaptureKind.Unknown
    }
}

/**
 * Coordinates Android `WebChromeClient.onPermissionRequest` and `onPermissionRequestCanceled` for
 * a single WebView host (`WebContainerAndroidView` and legacy `RaftAndroidSandboxWebView`).
 *
 * Fail-closed lifecycle guarantees:
 * - Evaluates every request via [evaluateWebAudioCapturePermission] synchronously on entry.
 *   Inactive/parked/detached hosts, unset allowed origins, non-matching origins (including
 *   sandboxed iframes), and non-audio or mixed `[audio, video]` requests are denied immediately
 *   without invoking [ensureRecordAudioPermission].
 * - Tracks at most one in-flight [PermissionRequest]; a superseding request cleanly cancels and
 *   denies the prior request first.
 * - `onPermissionRequestCanceled(request)` cancels the in-flight OS permission coroutine and drops
 *   the reference without calling `grant()` or `deny()` on the already-canceled request.
 * - `cancelAll()` (on destroy, keep-alive park, detach, recreation, renderer death, main-frame
 *   navigation, or policy change) cancels the coroutine, denies any unsettled request once, and
 *   releases all references so no [PermissionRequest] ever leaks.
 * - Right before calling `request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))` on
 *   the main thread, re-verifies non-cancellation, active/foreground host state, and exact origin
 *   policy match; otherwise denies cleanly.
 */
class WebContainerAndroidAudioPermissionController(
    private val scope: CoroutineScope,
    private val contextProvider: () -> Context,
    private val allowedOriginProvider: () -> WebContainerOrigin?,
    private val isHostForegroundActive: () -> Boolean,
    private val ensureRecordAudioPermission: suspend (Context) -> Boolean =
        WebContainerAndroidAudioPermissionBridge::ensureRecordAudioPermission,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onDecision: ((granted: Boolean, kind: WebMediaCaptureKind, reason: String, origin: String) -> Unit)? = null,
) {
    private class PendingRequest(
        val request: PermissionRequest,
        val requestedResources: List<String>,
        val originLabel: String,
    ) {
        val settled = AtomicBoolean(false)

        @Volatile
        var canceledByWebView: Boolean = false
        var job: Job? = null

        fun denyOnce() {
            if (canceledByWebView) return
            if (settled.compareAndSet(false, true)) {
                runCatching { request.deny() }
            }
        }

        fun grantAudioOnce(): Boolean {
            if (canceledByWebView) return false
            if (!settled.compareAndSet(false, true)) return false
            return runCatching {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                true
            }.getOrElse {
                runCatching { request.deny() }
                false
            }
        }
    }

    private var activePending: PendingRequest? = null

    val hasPendingRequest: Boolean
        get() = activePending != null

    fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        val requestOriginRaw = runCatching { request.origin?.toString() }.getOrNull()
        val resources = runCatching { request.resources?.toList() }.getOrNull().orEmpty()
        val kind = classifyAndroidPermissionResources(resources)
        val sanitizedOrigin = sanitizeOriginForLog(requestOriginRaw)
        if (!isHostForegroundActive()) {
            onDecision?.invoke(false, kind, "host_inactive", sanitizedOrigin)
            runCatching { request.deny() }
            return
        }
        val decision =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = requestOriginRaw,
                requestedResources = resources,
                allowedOrigin = allowedOriginProvider(),
                audioCaptureResource = PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            )
        if (decision !is WebAudioCapturePermissionDecision.AllowAudioCapture) {
            val reason = (decision as WebAudioCapturePermissionDecision.Deny).reason
            onDecision?.invoke(false, kind, reason, sanitizedOrigin)
            runCatching { request.deny() }
            return
        }

        cancelAll()

        val pending =
            PendingRequest(
                request = request,
                requestedResources = resources,
                originLabel = decision.origin.asOriginString(),
            )
        activePending = pending

        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val osGranted =
                    try {
                        ensureRecordAudioPermission(contextProvider())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        false
                    }
                runOnMainThread {
                    completePendingOnMain(pending = pending, osGranted = osGranted)
                }
            }
        pending.job = job
        job.start()
    }

    fun onPermissionRequestCanceled(request: PermissionRequest?) {
        if (request == null) return
        val current = activePending ?: return
        if (current.request !== request) return
        activePending = null
        current.canceledByWebView = true
        current.settled.set(true)
        current.job?.cancel()
        current.job = null
        onDecision?.invoke(false, WebMediaCaptureKind.Microphone, "request_canceled", current.originLabel)
    }

    fun cancelAll() {
        val current = activePending ?: return
        activePending = null
        current.job?.cancel()
        current.job = null
        current.denyOnce()
    }

    private fun completePendingOnMain(
        pending: PendingRequest,
        osGranted: Boolean,
    ) {
        if (pending.canceledByWebView) {
            if (activePending === pending) {
                activePending = null
            }
            pending.job = null
            return
        }
        if (activePending !== pending) {
            pending.denyOnce()
            return
        }
        activePending = null
        pending.job = null

        if (!osGranted) {
            onDecision?.invoke(false, WebMediaCaptureKind.Microphone, "os_permission_denied", pending.originLabel)
            pending.denyOnce()
            return
        }
        if (!isHostForegroundActive()) {
            onDecision?.invoke(false, WebMediaCaptureKind.Microphone, "host_inactive_after_prompt", pending.originLabel)
            pending.denyOnce()
            return
        }
        val requestOriginRaw = runCatching { pending.request.origin?.toString() }.getOrNull()
        val recheck =
            evaluateWebAudioCapturePermission(
                requestOriginUrl = requestOriginRaw,
                requestedResources = pending.requestedResources,
                allowedOrigin = allowedOriginProvider(),
                audioCaptureResource = PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            )
        if (recheck !is WebAudioCapturePermissionDecision.AllowAudioCapture) {
            val reason = (recheck as WebAudioCapturePermissionDecision.Deny).reason
            onDecision?.invoke(false, WebMediaCaptureKind.Microphone, reason, pending.originLabel)
            pending.denyOnce()
            return
        }
        val granted = pending.grantAudioOnce()
        onDecision?.invoke(
            granted,
            WebMediaCaptureKind.Microphone,
            if (granted) "granted" else "grant_failed",
            recheck.origin.asOriginString(),
        )
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun sanitizeOriginForLog(raw: String?): String =
        raw?.let(::parseWebContainerOrigin)?.asOriginString() ?: "<opaque>"
}
