package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.network.raftValidTraceIdOrNull
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical mobile user-visible error telemetry (`#proj-mobile:#399`, `botiverse/slock#16971`):
 * emits `mobile.ui_error` records (`otel.logs`) whenever an error surfaces to the user (toast,
 * screen error, dialog, inline banner, or WebView error state).
 *
 * Strictly closed-set `surface`, allowlisted `route`, and closed-set lowercase `error_code`
 * matching `docs/observability/reading-telemetry.md`. Never emits localized copy, stack traces,
 * or raw URLs.
 */
@RaftCoreInternalApi const val MOBILE_UI_ERROR_EVENT_NAME: String = "mobile.ui_error"

@RaftCoreInternalApi enum class MobileUiErrorSurface(val wireValue: String) {
    Toast("toast"),
    ScreenError("screen_error"),
    Dialog("dialog"),
    InlineBanner("inline_banner"),
    EmbeddedWebView("webview"),
}

@RaftCoreInternalApi enum class MobileUiErrorCode(val wireValue: String) {
    NetworkOffline("network_offline"),
    NetworkTimeout("network_timeout"),
    NetworkError("network_error"),
    EdgeBlocked("edge_blocked"),
    Http401("http_401"),
    Http403("http_403"),
    Http404("http_404"),
    Http409("http_409"),
    Http422("http_422"),
    Http429("http_429"),
    Http5xx("http_5xx"),
    Http4xx("http_4xx"),
    AuthExpired("auth_expired"),
    StepUpRequired("step_up_required"),
    PermissionDenied("permission_denied"),
    ServerRefusal("server_refusal"),
    WebViewLoadFailed("webview_load_failed"),
    WebViewTimeout("webview_timeout"),
    WebViewSslError("webview_ssl_error"),
    WebViewRenderGone("webview_render_gone"),
    UploadFailed("upload_failed"),
    SendFailed("send_failed"),
    ParseError("parse_error"),
    Unknown("unknown"),
}

private val AllowedMobileUiErrorRoutes: Set<String> = setOf(
    "home",
    "activity",
    "thread",
    "channel",
    "dm",
    "tasks",
    "members",
    "computers",
    "artifacts",
    "artifact",
    "artifact_viewer",
    "settings",
    "settings_webview",
    "search",
    "files",
    "auth",
    "in_app_browser",
    "raft_app",
    "feedback",
    "onboarding",
    "assistant",
    "share",
    "channel_settings",
    "server_settings",
    "billing",
    "workspace",
    "prewarm_pool",
    "other",
)

/**
 * Maps an internal page name or surface tag into the closed low-cardinality `route` allowlist
 * (anything unknown collapses to `"other"` so channel IDs, slugs, and user names never leak).
 */
@RaftCoreInternalApi fun normalizeMobileUiErrorRoute(raw: String?): String {
    val clean = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return "other"
    if (clean in AllowedMobileUiErrorRoutes) return clean
    return when {
        "artifact" in clean -> "artifact_viewer"
        "in_app_browser" in clean || "browser" in clean -> "in_app_browser"
        "raft_app" in clean || "raft-app" in clean -> "raft_app"
        "thread" in clean -> "thread"
        "activity" in clean || "inbox" in clean -> "activity"
        "task" in clean -> "tasks"
        "computer" in clean || "machine" in clean -> "computers"
        "member" in clean -> "members"
        "channel_settings" in clean || "channel_info" in clean -> "channel_settings"
        "channel" in clean -> "channel"
        "server_settings" in clean -> "server_settings"
        "billing" in clean -> "billing"
        "feedback" in clean -> "feedback"
        "settings_webview" in clean ||
            "settings_marketplace" in clean ||
            "settings_administration" in clean -> "settings_webview"
        "setting" in clean -> "settings"
        "search" in clean -> "search"
        "file" in clean -> "files"
        "auth" in clean || "login" in clean || "sign_in" in clean -> "auth"
        "onboarding" in clean -> "onboarding"
        "assistant" in clean -> "assistant"
        "share" in clean -> "share"
        "app_shell" in clean || "home" in clean -> "home"
        else -> "other"
    }
}

private val HttpStatusCodeSuffixRegex = Regex("(?:^|_)(?:http_|code_)(\\d{3}|0)$")
private val ExactHttpStatusCodeRegex = Regex("^(\\d{3}|0)$")

private fun extractHttpStatusCode(rawCode: String?): Int? {
    val clean = rawCode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val match = HttpStatusCodeSuffixRegex.find(clean) ?: ExactHttpStatusCodeRegex.find(clean) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/** Classifies a structured [ScreenError] into the closed `mobile.ui_error` `error_code` set. */
@RaftCoreInternalApi fun classifyMobileUiErrorCode(error: ScreenError): MobileUiErrorCode {
    if (error.secondFactorStepUp != null) return MobileUiErrorCode.StepUpRequired
    if (error.serverMessage.isEdgeBlockedMarker()) return MobileUiErrorCode.EdgeBlocked
    val status = extractHttpStatusCode(error.code)
    val serverCode = error.serverCode?.trim()?.takeIf { it.isNotEmpty() }
    val primaryCode = serverCode ?: error.code
    val primary =
        classifyMobileUiErrorCode(
            httpStatus = status,
            rawCode = primaryCode,
            fallback = MobileUiErrorCode.Unknown,
        )
    if (primary != MobileUiErrorCode.Unknown && primary != MobileUiErrorCode.ServerRefusal) {
        return primary
    }
    val secondary =
        if (serverCode != null && serverCode != error.code) {
            classifyMobileUiErrorCode(
                httpStatus = status,
                rawCode = error.code,
                fallback = primary,
            )
        } else {
            primary
        }
    if (secondary != MobileUiErrorCode.Unknown && secondary != MobileUiErrorCode.ServerRefusal) {
        return secondary
    }
    return when (error.errorPresentation().key) {
        ErrorPresentationKey.EdgeBlocked -> MobileUiErrorCode.EdgeBlocked
        ErrorPresentationKey.SessionExpired -> MobileUiErrorCode.AuthExpired
        ErrorPresentationKey.RequestTimedOut -> MobileUiErrorCode.NetworkTimeout
        ErrorPresentationKey.ServerUnreachable,
        ErrorPresentationKey.NetworkConnectionFailed -> MobileUiErrorCode.NetworkError
        ErrorPresentationKey.ServerUnavailable -> MobileUiErrorCode.Http5xx
        ErrorPresentationKey.ItemNotFound -> MobileUiErrorCode.Http404
        ErrorPresentationKey.PermissionDenied -> MobileUiErrorCode.PermissionDenied
        ErrorPresentationKey.TooManyAttempts -> MobileUiErrorCode.Http429
        ErrorPresentationKey.ResponseUnreadable -> MobileUiErrorCode.ParseError
        ErrorPresentationKey.AttachmentTooLarge,
        ErrorPresentationKey.AttachmentMonthlyQuotaExceeded -> MobileUiErrorCode.UploadFailed
        else -> secondary
    }
}

/** Classifies a [PermissionBlocker] into the closed `mobile.ui_error` `error_code` set. */
@RaftCoreInternalApi fun classifyMobileUiErrorCode(blocker: PermissionBlocker): MobileUiErrorCode {
    if (blocker.serverMessage.isEdgeBlockedMarker()) return MobileUiErrorCode.EdgeBlocked
    if (blocker.isAuthSessionBlocker()) return MobileUiErrorCode.AuthExpired
    val status = extractHttpStatusCode(blocker.code)
    val serverCode = blocker.serverCode?.trim()?.takeIf { it.isNotEmpty() }
    val primaryCode = serverCode ?: blocker.code
    val primary =
        classifyMobileUiErrorCode(
            httpStatus = status,
            rawCode = primaryCode,
            fallback = MobileUiErrorCode.PermissionDenied,
        )
    if (primary != MobileUiErrorCode.PermissionDenied) return primary
    return if (serverCode != null && serverCode != blocker.code) {
        classifyMobileUiErrorCode(
            httpStatus = status,
            rawCode = blocker.code,
            fallback = MobileUiErrorCode.PermissionDenied,
        )
    } else {
        primary
    }
}

/** Classifies an HTTP status and/or machine error code into [MobileUiErrorCode]. */
@RaftCoreInternalApi fun classifyMobileUiErrorCode(
    httpStatus: Int? = null,
    rawCode: String? = null,
    fallback: MobileUiErrorCode = MobileUiErrorCode.Unknown,
): MobileUiErrorCode {
    val code = rawCode?.trim()?.lowercase().orEmpty()
    when {
        "edge_blocked" in code -> return MobileUiErrorCode.EdgeBlocked
        "step_up" in code || "second_factor" in code || "mfa" in code -> return MobileUiErrorCode.StepUpRequired
        "auth_expired" in code ||
            "session_expired" in code ||
            "auth_required" in code ||
            "auth_session" in code ||
            "unauthorized" in code ||
            "unauthenticated" in code -> return MobileUiErrorCode.AuthExpired
        "permission" in code || "forbidden" in code || "not_allowed" in code || "read_only" in code ->
            return MobileUiErrorCode.PermissionDenied
        "offline" in code || "no_network" in code -> return MobileUiErrorCode.NetworkOffline
        "timeout" in code || "timed_out" in code || "stalled" in code -> return MobileUiErrorCode.NetworkTimeout
        "network" in code || "dns" in code || "connect" in code || "tls" in code -> return MobileUiErrorCode.NetworkError
        "not_found" in code -> return MobileUiErrorCode.Http404
        "conflict" in code -> return MobileUiErrorCode.Http409
        "rate_limited" in code || "too_many_requests" in code || "too_many_attempts" in code ->
            return MobileUiErrorCode.Http429
        "upload" in code -> return MobileUiErrorCode.UploadFailed
        "send" in code -> return MobileUiErrorCode.SendFailed
        "parse" in code || "decode" in code || "malformed" in code -> return MobileUiErrorCode.ParseError
    }
    val effectiveStatus = httpStatus ?: extractHttpStatusCode(code)
    return when (effectiveStatus) {
        0 -> MobileUiErrorCode.NetworkError
        401 -> MobileUiErrorCode.Http401
        403 -> MobileUiErrorCode.Http403
        404 -> MobileUiErrorCode.Http404
        409 -> MobileUiErrorCode.Http409
        422 -> MobileUiErrorCode.Http422
        429 -> MobileUiErrorCode.Http429
        in 400..499 -> MobileUiErrorCode.Http4xx
        in 500..599 -> MobileUiErrorCode.Http5xx
        else -> if (code.isNotEmpty() && fallback == MobileUiErrorCode.Unknown) MobileUiErrorCode.ServerRefusal else fallback
    }
}

private object MobileUiErrorMonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * Emits sampled, throttled `mobile.ui_error` records to [RaftClientTraceEvents.emitUiError].
 * Injectable dependencies for deterministic unit testing.
 */
@RaftCoreInternalApi class MobileUiErrorTraceRecorder(
    private val nowMonotonicMs: () -> Long = MobileUiErrorMonotonicClock::nowMs,
    private val resolveServerId: () -> String? = ::activeMobileUiErrorTraceServerId,
    private val resolveSampleRate: (String) -> Double = { serverId ->
        resolveActiveServerTraceSampleRate(
            serverId = serverId,
            traceName = MOBILE_UI_ERROR_TRACE_NAME,
            fallback = TRACE_SAMPLE_ERROR_DEFAULT_RATE,
        )
    },
    private val random: () -> Double = { Random.nextDouble() },
    private val throttle: MobileSignatureThrottle = MobileSignatureThrottle(),
    private val emitEvent: (serverId: String, attrs: JsonObject, traceId: String?) -> Unit = { serverId, attrs, traceId ->
        RaftClientTraceEvents.emitUiError(serverId, attrs, traceId)
    },
) {
    /** Emits one `mobile.ui_error` record if signed in, sampled, and outside the 60 s throttle. */
    fun record(
        surface: MobileUiErrorSurface,
        route: String?,
        errorCode: MobileUiErrorCode,
        requestTraceId: String? = null,
        serverIdOverride: String? = null,
    ): Boolean {
        val serverId = (serverIdOverride ?: resolveServerId())?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val rate = resolveSampleRate(serverId)
        if (!rate.isFinite() || rate <= 0.0) return false
        val clampedRate = rate.coerceAtMost(1.0)
        if (!traceSampled(clampedRate, random)) return false

        val normalizedRoute = normalizeMobileUiErrorRoute(route)
        val throttleKey = "${surface.wireValue}|$normalizedRoute|${errorCode.wireValue}"
        val suppressedCount = throttle.tryAcquire(throttleKey, nowMonotonicMs()) ?: return false

        val validTraceId = raftValidTraceIdOrNull(requestTraceId)
        val attrs =
            buildMap<String, JsonPrimitive> {
                put("surface", JsonPrimitive(surface.wireValue))
                put("route", JsonPrimitive(normalizedRoute))
                put("error_code", JsonPrimitive(errorCode.wireValue))
                put("has_request_trace", JsonPrimitive(validTraceId != null))
                if (validTraceId != null) {
                    put("request_trace_id", JsonPrimitive(validTraceId))
                }
                put("suppressed_count", JsonPrimitive(suppressedCount))
                put("sample_rate", JsonPrimitive(clampedRate))
            }
        return runCatching {
            emitEvent(serverId, JsonObject(attrs), validTraceId)
            true
        }.getOrDefault(false)
    }
}

@RaftCoreInternalApi object MobileUiErrorTrace {
    private val defaultRecorder = MobileUiErrorTraceRecorder()

    fun record(
        surface: MobileUiErrorSurface,
        route: String?,
        errorCode: MobileUiErrorCode,
        requestTraceId: String? = null,
        serverIdOverride: String? = null,
    ) {
        runCatching {
            defaultRecorder.record(
                surface = surface,
                route = route,
                errorCode = errorCode,
                requestTraceId = requestTraceId,
                serverIdOverride = serverIdOverride,
            )
        }
    }
}

private fun activeMobileUiErrorTraceServerId(): String? =
    runCatching {
        RaftDataStoreRegistry.currentOrNull()
            ?.authStore
            ?.readInMemorySessionSnapshot()
            ?.session
            ?.currentServerId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
