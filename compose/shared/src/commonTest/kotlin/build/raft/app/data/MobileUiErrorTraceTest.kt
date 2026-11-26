@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class MobileUiErrorTraceTest {
    private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"

    @Test
    fun closedSurfaceAndErrorCodeEnumsMatchRegistryContract() {
        assertEquals(
            setOf("toast", "screen_error", "dialog", "inline_banner", "webview"),
            MobileUiErrorSurface.entries.map { it.wireValue }.toSet(),
        )
        assertEquals(
            setOf(
                "network_offline",
                "network_timeout",
                "network_error",
                "edge_blocked",
                "http_401",
                "http_403",
                "http_404",
                "http_409",
                "http_422",
                "http_429",
                "http_5xx",
                "http_4xx",
                "auth_expired",
                "step_up_required",
                "permission_denied",
                "server_refusal",
                "webview_load_failed",
                "webview_timeout",
                "webview_ssl_error",
                "webview_render_gone",
                "upload_failed",
                "send_failed",
                "parse_error",
                "unknown",
            ),
            MobileUiErrorCode.entries.map { it.wireValue }.toSet(),
        )
    }

    @Test
    fun screenErrorAndPermissionBlockerClassificationMapsToClosedEnum() {
        assertEquals(
            MobileUiErrorCode.EdgeBlocked,
            classifyMobileUiErrorCode(ScreenError(code = "http_403", serverMessage = "edge_blocked")),
        )
        assertEquals(
            MobileUiErrorCode.Http5xx,
            classifyMobileUiErrorCode(ScreenError(code = "http_500", serverMessage = "SQLSTATE internal panic")),
        )
        assertEquals(
            MobileUiErrorCode.Http404,
            classifyMobileUiErrorCode(ScreenError(code = "http_404")),
        )
        assertEquals(
            MobileUiErrorCode.Http409,
            classifyMobileUiErrorCode(ScreenError(code = "http_409")),
        )
        assertEquals(
            MobileUiErrorCode.Http422,
            classifyMobileUiErrorCode(ScreenError(code = "http_422")),
        )
        assertEquals(
            MobileUiErrorCode.Http429,
            classifyMobileUiErrorCode(ScreenError(code = "http_429")),
        )
        assertEquals(
            MobileUiErrorCode.NetworkError,
            classifyMobileUiErrorCode(ScreenError(code = "tasks_http_0")),
        )
        assertEquals(
            MobileUiErrorCode.NetworkTimeout,
            classifyMobileUiErrorCode(ScreenError(code = "network_timeout")),
        )
        assertEquals(
            MobileUiErrorCode.AuthExpired,
            classifyMobileUiErrorCode(ScreenError(code = "auth_required")),
        )
        assertEquals(
            MobileUiErrorCode.UploadFailed,
            classifyMobileUiErrorCode(ScreenError(code = "http_403", serverCode = "file_upload_quota_exceeded")),
        )
        assertEquals(
            MobileUiErrorCode.ParseError,
            classifyMobileUiErrorCode(ScreenError(code = "parse_error")),
        )
        assertEquals(
            MobileUiErrorCode.ServerRefusal,
            classifyMobileUiErrorCode(ScreenError(code = "custom_refusal", serverCode = "channel_archived")),
        )
        assertEquals(
            MobileUiErrorCode.AuthExpired,
            classifyMobileUiErrorCode(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session")),
        )
        assertEquals(
            MobileUiErrorCode.PermissionDenied,
            classifyMobileUiErrorCode(PermissionBlocker(code = "forbidden")),
        )
        assertEquals(
            MobileUiErrorCode.StepUpRequired,
            classifyMobileUiErrorCode(
                PermissionBlocker(code = "forbidden", serverCode = "second_factor_step_up_required"),
            ),
        )
        assertEquals(
            MobileUiErrorCode.EdgeBlocked,
            classifyMobileUiErrorCode(PermissionBlocker(code = "http_403", serverMessage = "edge_blocked")),
        )
    }

    @Test
    fun routeNormalizationMapsAllowlistedRoutesAndCollapsesUnknownToOther() {
        assertEquals("thread", normalizeMobileUiErrorRoute("compose_thread"))
        assertEquals("activity", normalizeMobileUiErrorRoute("compose_home_activity"))
        assertEquals("artifact_viewer", normalizeMobileUiErrorRoute("compose_artifact_viewer"))
        assertEquals("settings_webview", normalizeMobileUiErrorRoute("compose_settings_webview"))
        assertEquals("settings_webview", normalizeMobileUiErrorRoute("settings_marketplace"))
        assertEquals("billing", normalizeMobileUiErrorRoute("compose_settings_billing"))
        assertEquals("channel_settings", normalizeMobileUiErrorRoute("compose_channel_settings"))
        assertEquals("home", normalizeMobileUiErrorRoute("compose_slock_app_shell"))
        assertEquals("in_app_browser", normalizeMobileUiErrorRoute("in_app_browser"))
        assertEquals("raft_app", normalizeMobileUiErrorRoute("raft_app"))

        assertEquals("other", normalizeMobileUiErrorRoute("secret-room-019abc"))
        assertEquals("other", normalizeMobileUiErrorRoute("https://evil.example/private?token=1"))
        assertEquals("other", normalizeMobileUiErrorRoute(""))
        assertEquals("other", normalizeMobileUiErrorRoute(null))
    }

    @Test
    fun emittedRecordCarriesOnlySchemaAttributesAndHonorsThrottleAndTraceLink() {
        var nowMono = 1_000L
        val emitted = mutableListOf<Triple<String, JsonObject, String?>>()
        val recorder =
            MobileUiErrorTraceRecorder(
                nowMonotonicMs = { nowMono },
                resolveServerId = { "srv-1" },
                resolveSampleRate = { 1.0 },
                emitEvent = { serverId, attrs, linkedTraceId -> emitted += Triple(serverId, attrs, linkedTraceId) },
            )

        assertTrue(
            recorder.record(
                surface = MobileUiErrorSurface.Toast,
                route = "compose_thread",
                errorCode = MobileUiErrorCode.Http5xx,
                requestTraceId = traceId.uppercase(),
            ),
        )
        val (serverId, attrs, linkedTrace) = emitted.single()
        assertEquals("srv-1", serverId)
        assertEquals(traceId, linkedTrace)
        assertEquals(
            setOf("surface", "route", "error_code", "has_request_trace", "request_trace_id", "suppressed_count", "sample_rate"),
            attrs.keys,
        )
        assertEquals("toast", attrs.getValue("surface").jsonPrimitive.content)
        assertEquals("thread", attrs.getValue("route").jsonPrimitive.content)
        assertEquals("http_5xx", attrs.getValue("error_code").jsonPrimitive.content)
        assertEquals(true, attrs.getValue("has_request_trace").jsonPrimitive.boolean)
        assertEquals(traceId, attrs.getValue("request_trace_id").jsonPrimitive.content)
        assertEquals(0, attrs.getValue("suppressed_count").jsonPrimitive.int)
        assertEquals(1.0, attrs.getValue("sample_rate").jsonPrimitive.double)

        // Same signature within 60 s is throttled and carried to next window emission.
        nowMono = 10_000L
        assertFalse(recorder.record(MobileUiErrorSurface.Toast, "compose_thread", MobileUiErrorCode.Http5xx, traceId))
        nowMono = 20_000L
        assertFalse(recorder.record(MobileUiErrorSurface.Toast, "compose_thread", MobileUiErrorCode.Http5xx, traceId))
        nowMono = 61_000L
        assertTrue(recorder.record(MobileUiErrorSurface.Toast, "compose_thread", MobileUiErrorCode.Http5xx, "invalid-id"))
        val (_, secondAttrs, secondTrace) = emitted[1]
        assertNull(secondTrace)
        assertEquals(false, secondAttrs.getValue("has_request_trace").jsonPrimitive.boolean)
        assertNull(secondAttrs["request_trace_id"])
        assertEquals(2, secondAttrs.getValue("suppressed_count").jsonPrimitive.int)
    }

    @Test
    fun samplingHonorsRateAndZeroKillSwitch() {
        var sampleRate = 0.0
        var draw = 0.0
        val emitted = mutableListOf<JsonObject>()
        val recorder =
            MobileUiErrorTraceRecorder(
                nowMonotonicMs = { 1_000L },
                resolveServerId = { "srv-1" },
                resolveSampleRate = { sampleRate },
                random = { draw },
                emitEvent = { _, attrs, _ -> emitted += attrs },
            )

        assertFalse(recorder.record(MobileUiErrorSurface.ScreenError, "home", MobileUiErrorCode.NetworkError))
        assertTrue(emitted.isEmpty())

        sampleRate = 0.5
        draw = 0.75
        assertFalse(recorder.record(MobileUiErrorSurface.ScreenError, "home", MobileUiErrorCode.NetworkError))
        draw = 0.25
        assertTrue(recorder.record(MobileUiErrorSurface.ScreenError, "home", MobileUiErrorCode.NetworkError))
        assertEquals(0.5, emitted.single().getValue("sample_rate").jsonPrimitive.double)

        // Signed-out (no server id) does not emit.
        val signedOut =
            MobileUiErrorTraceRecorder(
                resolveServerId = { null },
                resolveSampleRate = { 1.0 },
                emitEvent = { _, attrs, _ -> emitted += attrs },
            )
        assertFalse(signedOut.record(MobileUiErrorSurface.Dialog, "settings", MobileUiErrorCode.Unknown))
    }
}
