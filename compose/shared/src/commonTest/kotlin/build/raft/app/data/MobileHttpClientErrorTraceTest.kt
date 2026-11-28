@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.raftIsTelemetrySelfApiRoute
import build.raft.app.network.raftMobileApiRouteTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class MobileHttpClientErrorTraceTest {
    private val sentTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val fallbackTraceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val spanId = "00f067aa0ba902b7"
    private val baseEpochMs = 1_795_500_000_000L

    private fun RaftWebSpanRecord.str(key: String): String? = attrs[key]?.jsonPrimitive?.content

    @Test
    fun routeTemplateMatchesAllowlistAndCollapsesDynamicSegmentsAndNonApiUrls() {
        assertEquals(
            "/api/channels/:id/messages",
            raftMobileApiRouteTemplate("https://api.raft.build/api/channels/ch_019abc/messages?limit=50#top"),
        )
        assertEquals(
            "/api/messages/channel/:id/threads/:id",
            raftMobileApiRouteTemplate("https://api.raft.build/api/messages/channel/c1/threads/t2"),
        )
        assertEquals(
            "/api/invites/:id/accept",
            raftMobileApiRouteTemplate("https://api.raft.build/api/invites/secret-token-xyz/accept"),
        )
        assertEquals(
            "/api/servers/:id/scope-attestation",
            raftMobileApiRouteTemplate("https://api.raft.build/api/servers/srv-99/scope-attestation"),
        )
        assertEquals(
            "/api/attachments/:id/draft",
            raftMobileApiRouteTemplate("https://api.raft.build/api/attachments/att_123/draft"),
        )
        assertEquals(
            ":other",
            raftMobileApiRouteTemplate("https://bucket.r2.test/obj/key?X-Amz-Signature=secret"),
        )
        assertEquals(":other", raftMobileApiRouteTemplate(""))
        assertEquals(":other", raftMobileApiRouteTemplate(null))
    }

    @Test
    fun selfTelemetryRoutesAreExcludedToPreventRecursiveEmission() {
        assertTrue(raftIsTelemetrySelfApiRoute("/api/web-traces"))
        assertTrue(raftIsTelemetrySelfApiRoute("/api/web-traces/batch"))
        assertTrue(raftIsTelemetrySelfApiRoute("/api/scope-attestation"))
        assertTrue(raftIsTelemetrySelfApiRoute("/api/auth/scope-attestation"))
        assertTrue(raftIsTelemetrySelfApiRoute("/api/servers/:id/scope-attestation"))
        assertFalse(raftIsTelemetrySelfApiRoute("/api/messages"))
        assertFalse(raftIsTelemetrySelfApiRoute("/api/channels/:id/messages"))

        val emitted = mutableListOf<RaftWebSpanRecord>()
        val recorder =
            MobileHttpClientErrorTraceRecorder(
                nowEpochMs = { baseEpochMs },
                nowMonotonicMs = { 1_000L },
                resolveServerId = { "srv-1" },
                resolveSampleRate = { 1.0 },
                newSpanIds = { fallbackTraceId to spanId },
                emitSpan = { _, span -> emitted += span },
            )
        assertFalse(
            recorder.recordFailure(
                method = "POST",
                url = "https://traces.raft.build/api/web-traces",
                requestHeaders = mapOf(HEADER_SERVER_ID to "srv-1"),
                sentTraceId = sentTraceId,
                statusCode = 500,
                errorBodyText = """{"error":"boom"}""",
                transportDiagnostics = null,
                durationMs = 45L,
                redactSensitive = false,
            ),
        )
        assertFalse(
            recorder.recordFailure(
                method = "POST",
                url = "https://api.raft.build/api/servers/srv-1/scope-attestation",
                requestHeaders = mapOf(HEADER_SERVER_ID to "srv-1"),
                sentTraceId = sentTraceId,
                statusCode = 503,
                errorBodyText = """{"error":"unavailable"}""",
                transportDiagnostics = null,
                durationMs = 20L,
                redactSensitive = false,
            ),
        )
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun successesCancellationsAndRedactSensitiveRequestsAreSkipped() {
        assertNull(classifyMobileHttpFailure(200, null, null))
        assertNull(classifyMobileHttpFailure(204, null, null))
        assertNull(classifyMobileHttpFailure(304, null, null))
        assertNull(classifyMobileHttpFailure(0, null, "engine=ktor status=none kind=CANCELLED detail=caller_cancelled"))
        assertNull(classifyMobileHttpFailure(0, "network_request_lifecycle_retired", "detail=lifecycle_retired"))
        assertNull(classifyMobileHttpFailure(0, "network_request_mutation_outcome_unknown", "detail=mutation_outcome_unknown"))

        val emitted = mutableListOf<RaftWebSpanRecord>()
        val recorder =
            MobileHttpClientErrorTraceRecorder(
                nowEpochMs = { baseEpochMs },
                nowMonotonicMs = { 1_000L },
                resolveServerId = { "srv-1" },
                resolveSampleRate = { 1.0 },
                newSpanIds = { fallbackTraceId to spanId },
                emitSpan = { _, span -> emitted += span },
            )
        assertFalse(
            recorder.recordFailure(
                method = "PUT",
                url = "https://bucket.r2.test/obj/key?X-Amz-Signature=secret",
                requestHeaders = emptyMap(),
                sentTraceId = null,
                statusCode = 403,
                errorBodyText = "attachment_presigned_upload_http_403",
                transportDiagnostics = null,
                durationMs = 120L,
                redactSensitive = true,
            ),
        )
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun edgeBlockedClassifiesAsNetworkErrorPreservingHttpStatusAndErrorCode() {
        val emitted = mutableListOf<RaftWebSpanRecord>()
        val recorder =
            MobileHttpClientErrorTraceRecorder(
                nowEpochMs = { baseEpochMs },
                nowMonotonicMs = { 1_000L },
                resolveServerId = { "srv-1" },
                resolveSampleRate = { 1.0 },
                newSpanIds = { fallbackTraceId to spanId },
                emitSpan = { _, span -> emitted += span },
            )
        assertTrue(
            recorder.recordFailure(
                method = "get",
                url = "https://api.raft.build/api/channels/ch-1/messages?limit=50",
                requestHeaders = mapOf(HEADER_SERVER_ID to "srv-1"),
                sentTraceId = sentTraceId,
                statusCode = 403,
                errorBodyText = """{"error":"edge_blocked"}""",
                transportDiagnostics = "engine=okhttp status=403 kind=none",
                durationMs = 85L,
                redactSensitive = false,
            ),
        )
        val span = emitted.single()
        assertEquals(MOBILE_HTTP_CLIENT_SPAN_NAME, span.name)
        assertEquals("error", span.status)
        assertEquals(sentTraceId, span.traceId)
        assertEquals(spanId, span.spanId)
        assertEquals("GET", span.str("method"))
        assertEquals("/api/channels/:id/messages", span.str("http.route"))
        assertEquals("network_error", span.str("status_bucket"))
        assertEquals(403, span.attrs.getValue("status_code").jsonPrimitive.int)
        assertEquals("edge_blocked", span.str("error_code"))
        assertEquals(0, span.attrs.getValue("suppressed_count").jsonPrimitive.int)
        assertEquals(1.0, span.attrs.getValue("sample_rate").jsonPrimitive.double)
        assertNull(span.attrs["trace_id"])
        assertEquals(raftEpochMillisUtcIso(baseEpochMs - 85L), span.startTime)
        assertEquals(raftEpochMillisUtcIso(baseEpochMs), span.endTime)

        val bareMarker = assertNotNull(classifyMobileHttpFailure(503, "edge_blocked", null))
        assertEquals(MobileHttpStatusBucket.NetworkError, bareMarker.statusBucket)
        assertEquals(503, bareMarker.statusCode)
        assertEquals(MobileHttpErrorCode.EdgeBlocked, bareMarker.errorCode)
    }

    @Test
    fun client4xxServer5xxAndTransportErrorCodesMatchSchema() {
        val c404 = assertNotNull(classifyMobileHttpFailure(404, """{"error":"not_found"}""", null))
        assertEquals(MobileHttpStatusBucket.Client4xx, c404.statusBucket)
        assertEquals(404, c404.statusCode)
        assertNull(c404.errorCode)

        val c502 = assertNotNull(classifyMobileHttpFailure(502, "bad gateway", null))
        assertEquals(MobileHttpStatusBucket.Server5xx, c502.statusBucket)
        assertEquals(502, c502.statusCode)
        assertNull(c502.errorCode)

        val transportCases =
            listOf(
                Triple("kind=DNS", null, MobileHttpErrorCode.Dns),
                Triple("kind=CONNECT", null, MobileHttpErrorCode.Connect),
                Triple("kind=TIMEOUT", null, MobileHttpErrorCode.Timeout),
                Triple("kind=TLS", null, MobileHttpErrorCode.Tls),
                Triple("stream_reset: PROTOCOL_ERROR", null, MobileHttpErrorCode.Reset),
                Triple("NSURLErrorNotConnectedToInternet", null, MobileHttpErrorCode.Offline),
                Triple("unexpected_engine_state", null, MobileHttpErrorCode.Other),
            )
        for ((diag, body, expectedCode) in transportCases) {
            val classified = assertNotNull(classifyMobileHttpFailure(0, body, diag), diag)
            assertEquals(MobileHttpStatusBucket.NetworkError, classified.statusBucket, diag)
            assertEquals(0, classified.statusCode, diag)
            assertEquals(expectedCode, classified.errorCode, diag)
        }

        // 4xx/5xx omit error_code from attrs and fall back to fresh traceId if sentTraceId is invalid.
        val emitted = mutableListOf<RaftWebSpanRecord>()
        val recorder =
            MobileHttpClientErrorTraceRecorder(
                nowEpochMs = { baseEpochMs },
                nowMonotonicMs = { 1_000L },
                resolveServerId = { "srv-1" },
                resolveSampleRate = { 1.0 },
                newSpanIds = { fallbackTraceId to spanId },
                emitSpan = { _, span -> emitted += span },
            )
        assertTrue(
            recorder.recordFailure(
                method = "POST",
                url = "https://api.raft.build/api/messages",
                requestHeaders = emptyMap(),
                sentTraceId = "not-valid-hex",
                statusCode = 500,
                errorBodyText = """{"error":"internal"}""",
                transportDiagnostics = null,
                durationMs = 200L,
                redactSensitive = false,
            ),
        )
        val span = emitted.single()
        assertEquals(fallbackTraceId, span.traceId)
        assertEquals("5xx", span.str("status_bucket"))
        assertEquals(500, span.attrs.getValue("status_code").jsonPrimitive.int)
        assertNull(span.attrs["error_code"])
    }

    @Test
    fun rollingSixtySecondThrottleCarriesSuppressedCountOnNextWindowEmission() {
        var nowMono = 1_000L
        val emitted = mutableListOf<RaftWebSpanRecord>()
        val recorder =
            MobileHttpClientErrorTraceRecorder(
                nowEpochMs = { baseEpochMs + nowMono },
                nowMonotonicMs = { nowMono },
                resolveServerId = { "srv-1" },
                resolveSampleRate = { 1.0 },
                newSpanIds = { fallbackTraceId to spanId },
                emitSpan = { _, span -> emitted += span },
            )

        fun fail(url: String = "https://api.raft.build/api/messages/channel/c1", code: Int = 500): Boolean =
            recorder.recordFailure(
                method = "GET",
                url = url,
                requestHeaders = emptyMap(),
                sentTraceId = sentTraceId,
                statusCode = code,
                errorBodyText = null,
                transportDiagnostics = null,
                durationMs = 50L,
                redactSensitive = false,
            )

        // First occurrence emits with suppressed_count = 0.
        assertTrue(fail())
        assertEquals(0, emitted.last().attrs.getValue("suppressed_count").jsonPrimitive.int)

        // Three same-signature repeats within 60 s are suppressed (no standalone flush).
        nowMono = 5_000L
        assertFalse(fail())
        nowMono = 30_000L
        assertFalse(fail())
        nowMono = 60_999L
        assertFalse(fail())
        assertEquals(1, emitted.size)

        // Different route signature emits independently without affecting the first bucket.
        nowMono = 15_000L
        assertTrue(fail(url = "https://api.raft.build/api/tasks", code = 500))
        assertEquals(0, emitted.last().attrs.getValue("suppressed_count").jsonPrimitive.int)

        // Next failure after 60 s carries suppressed_count = 3 and resets the counter.
        nowMono = 61_000L
        assertTrue(fail())
        assertEquals(3, emitted.last().attrs.getValue("suppressed_count").jsonPrimitive.int)

        // Subsequent window with no repeats emits suppressed_count = 0.
        nowMono = 121_000L
        assertTrue(fail())
        assertEquals(0, emitted.last().attrs.getValue("suppressed_count").jsonPrimitive.int)
    }

    @Test
    fun samplingHonorsFlagRateAndZeroKillSwitch() {
        var sampleRate = 0.0
        var draw = 0.0
        val emitted = mutableListOf<RaftWebSpanRecord>()
        val recorder =
            MobileHttpClientErrorTraceRecorder(
                nowEpochMs = { baseEpochMs },
                nowMonotonicMs = { 1_000L },
                resolveServerId = { "srv-1" },
                resolveSampleRate = { sampleRate },
                random = { draw },
                newSpanIds = { fallbackTraceId to spanId },
                emitSpan = { _, span -> emitted += span },
            )

        // Explicit 0 kill switch suppresses even when random draw is 0.0.
        assertFalse(
            recorder.recordFailure(
                method = "GET",
                url = "https://api.raft.build/api/tasks",
                requestHeaders = emptyMap(),
                sentTraceId = sentTraceId,
                statusCode = 500,
                errorBodyText = null,
                transportDiagnostics = null,
                durationMs = 10L,
                redactSensitive = false,
            ),
        )
        assertTrue(emitted.isEmpty())

        // Fractional rate drops draws >= rate and records sample_rate on draws < rate.
        sampleRate = 0.25
        draw = 0.5
        assertFalse(
            recorder.recordFailure(
                method = "GET",
                url = "https://api.raft.build/api/tasks",
                requestHeaders = emptyMap(),
                sentTraceId = sentTraceId,
                statusCode = 500,
                errorBodyText = null,
                transportDiagnostics = null,
                durationMs = 10L,
                redactSensitive = false,
            ),
        )
        draw = 0.1
        assertTrue(
            recorder.recordFailure(
                method = "GET",
                url = "https://api.raft.build/api/tasks",
                requestHeaders = emptyMap(),
                sentTraceId = sentTraceId,
                statusCode = 500,
                errorBodyText = null,
                transportDiagnostics = null,
                durationMs = 10L,
                redactSensitive = false,
            ),
        )
        assertEquals(0.25, emitted.single().attrs.getValue("sample_rate").jsonPrimitive.double)

        // Signed-out (no server id) never emits.
        val signedOut =
            MobileHttpClientErrorTraceRecorder(
                resolveServerId = { null },
                resolveSampleRate = { 1.0 },
                emitSpan = { _, span -> emitted += span },
            )
        assertFalse(
            signedOut.recordFailure(
                method = "GET",
                url = "https://api.raft.build/api/tasks",
                requestHeaders = emptyMap(),
                sentTraceId = sentTraceId,
                statusCode = 500,
                errorBodyText = null,
                transportDiagnostics = null,
                durationMs = 10L,
                redactSensitive = false,
            ),
        )
    }
}
