package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the transport reports for an upload request (`slock.attachment.upload_finished`). */
class RaftUploadWireFactsTest {
    @Test
    fun uploadTimesAreReadFromTheEngineFacts() {
        // Android / desktop (OkHttp EventListener) and iOS (NSURLSessionTaskMetrics) use the same tokens.
        val okHttp =
            "reusedConnection=false connectionAttempts=1 dnsMs=4 connectTotalMs=31 tlsMs=22 negotiatedProtocol=http/1.1 " +
                "addressFamily=ipv4 tlsVersion=TLSv1.3 connectSetupMs=57 requestBodyMs=1840 responseWaitMs=312"
        val darwin =
            "reusedConnection=false dnsMs=4 connectTotalMs=31 tlsMs=22 negotiatedProtocol=h2 transactions=1 " +
                "connectSetupMs=57 requestBodyMs=1840 responseWaitMs=312"

        for (facts in listOf(okHttp, darwin)) {
            assertEquals(
                RaftUploadWireFacts(
                    requestBytes = 2_048L,
                    connectMs = 57L,
                    requestBodyMs = 1_840L,
                    responseWaitMs = 312L,
                    edgePop = "NRT57-P2",
                ),
                raftUploadWireFacts(requestBytes = 2_048L, facts = facts, edgePop = "NRT57-P2"),
                facts,
            )
        }
    }

    @Test
    fun aValueTheEngineDidNotMeasureIsAbsentNotZero() {
        val cases: List<Pair<String?, RaftUploadWireFacts>> =
            listOf(
                // No facts at all (an engine without metrics, or a request that never started).
                null to RaftUploadWireFacts(requestBytes = 10L),
                // A reused connection has no setup time.
                "reusedConnection=true requestBodyMs=900 responseWaitMs=3" to
                    RaftUploadWireFacts(requestBytes = 10L, requestBodyMs = 900L, responseWaitMs = 3L),
                // The body went out but no answer came.
                "reusedConnection=true requestBodyMs=900" to RaftUploadWireFacts(requestBytes = 10L, requestBodyMs = 900L),
                // The request ended while the body was still going out: a partial time, no body time.
                "reusedConnection=false connectSetupMs=80 requestBodyPartialMs=4200" to
                    RaftUploadWireFacts(requestBytes = 10L, connectMs = 80L, requestBodyPartialMs = 4_200L),
                // A finished body has no partial time.
                "requestBodyMs=900 requestBodyPartialMs=4200" to RaftUploadWireFacts(requestBytes = 10L, requestBodyMs = 900L),
                // A wait without a body time has no last byte to start from.
                "reusedConnection=true responseWaitMs=40" to RaftUploadWireFacts(requestBytes = 10L),
                // Malformed or negative values are not measurements.
                "connectSetupMs=x requestBodyMs=abc responseWaitMs=-3" to RaftUploadWireFacts(requestBytes = 10L),
                "connectSetupMs=-2 requestBodyMs=-1 responseWaitMs=7" to RaftUploadWireFacts(requestBytes = 10L),
                // A measured zero stays a zero.
                "connectSetupMs=0 requestBodyMs=0 responseWaitMs=0" to
                    RaftUploadWireFacts(requestBytes = 10L, connectMs = 0L, requestBodyMs = 0L, responseWaitMs = 0L),
            )

        cases.forEach { (facts, expected) ->
            assertEquals(expected, raftUploadWireFacts(requestBytes = 10L, facts = facts, edgePop = null), "facts=$facts")
        }
        // An unknown body length is absent too.
        listOf(null, 0L, -1L).forEach { bytes ->
            assertNull(raftUploadWireFacts(requestBytes = bytes, facts = null, edgePop = null).requestBytes, "bytes=$bytes")
        }
    }

    @Test
    fun edgePopIsTheCloudFrontHeaderInTheWebsShapeOnly() {
        // Web `edgePopOf` (botiverse/slock packages/web/src/observability/resourceTiming.ts).
        val kept: List<Pair<Map<String, List<String>>, String>> =
            listOf(
                mapOf("x-amz-cf-pop" to listOf("NRT57-P2")) to "NRT57-P2",
                mapOf("X-Amz-Cf-Pop" to listOf("SFO53-P7")) to "SFO53-P7",
                mapOf("X-AMZ-CF-POP" to listOf(" HKG62-C1 ")) to "HKG62-C1",
                mapOf("x-amz-cf-pop" to listOf("LHR3")) to "LHR3",
            )
        kept.forEach { (headers, expected) -> assertEquals(expected, raftEdgePopOrNull(headers), "$headers") }

        val dropped: List<Map<String, List<String>>> =
            listOf(
                emptyMap<String, List<String>>(),
                mapOf("x-amz-cf-id" to listOf("NRT57-P2")),
                mapOf("x-amz-cf-pop" to emptyList<String>()),
                mapOf("x-amz-cf-pop" to listOf("")),
                mapOf("x-amz-cf-pop" to listOf("nrt57-p2")),
                mapOf("x-amz-cf-pop" to listOf("NRT57-P2; https://bucket.test/key?X-Amz-Signature=abc")),
                mapOf("x-amz-cf-pop" to listOf("TOKYO")),
            )
        dropped.forEach { headers -> assertNull(raftEdgePopOrNull(headers), "$headers") }
    }

    @Test
    fun presignedPutHeadersCarryARequestIdAndNeverATraceparent() {
        var seed = 0
        val headers = raftRequestIdHeaders(mapOf("Content-Type" to "image/jpeg")) { size -> ByteArray(size) { (seed++).toByte() } }

        assertEquals(
            mapOf("Content-Type" to "image/jpeg", "x-request-id" to "mobile-000102030405060708090a0b0c0d0e0f"),
            headers,
        )
        // A caller's own request id is kept; a caller's traceparent is not sent to the storage host.
        assertEquals(
            mapOf("X-Request-Id" to "caller-id"),
            raftRequestIdHeaders(mapOf("X-Request-Id" to "caller-id", "TraceParent" to "00-a-b-01")) { error("random bytes must not be requested") },
        )
    }

    @Test
    fun traceIdsFormatTheSampledTraceparentAndItsTraceIdIsReadBackForTheLog() {
        var seed = 0
        val ids = newRaftTraceIds { size -> ByteArray(size) { (seed++).toByte() } }

        assertEquals("000102030405060708090a0b0c0d0e0f", ids.traceId)
        assertEquals("1011121314151617", ids.spanId)
        assertEquals("00-000102030405060708090a0b0c0d0e0f-1011121314151617-01", ids.traceparent)
        assertEquals(ids.traceId, mapOf("Traceparent" to ids.traceparent).raftSentTraceIdOrNull())
        assertNull(mapOf("traceparent" to "caller-trace").raftSentTraceIdOrNull())
        assertNull(emptyMap<String, String>().raftSentTraceIdOrNull())
    }

    /** The owner's rule: an error's text can hold a URL or a file name and never reaches telemetry. */
    @Test
    fun aTransportErrorIsNamedByItsClassNeverByItsMessage() {
        val secret = "https://bucket.r2.test/obj/key?X-Amz-Signature=abc /private/var/mobile/IMG_0042 holiday.jpg"
        val errors =
            listOf(
                IllegalStateException(secret),
                RuntimeException(secret, IllegalArgumentException(secret)),
                io.ktor.client.plugins.HttpRequestTimeoutException("https://bucket.r2.test/obj/key?X-Amz-Signature=abc", 30_000L),
                io.ktor.client.network.sockets.SocketTimeoutException(secret),
            )

        val tokens = errors.map(::raftTransportErrorToken)

        tokens.forEach { token ->
            assertTrue(Regex("""^(other|[a-z]+:[A-Za-z]+)$""").matches(token), token)
            assertFalse("bucket" in token || "Signature" in token || "IMG_0042" in token || "holiday" in token, token)
        }
        assertEquals(listOf("other", "other"), tokens.take(2))
        // The platform names a class it lists (okhttp:… on the JVM); the common fallback is ktor:….
        assertTrue(tokens[2].endsWith(":HttpRequestTimeoutException"), tokens[2])
        assertTrue(tokens[3].endsWith(":SocketTimeoutException"), tokens[3])
    }
}
