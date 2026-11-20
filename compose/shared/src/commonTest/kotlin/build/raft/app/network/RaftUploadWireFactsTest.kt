package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the transport reports for an upload request (`slock.attachment.upload_finished`). */
class RaftUploadWireFactsTest {
    @Test
    fun phaseTimesAreReadFromTheEngineFacts() {
        // Android / desktop (OkHttp EventListener) and iOS (NSURLSessionTaskMetrics) use the same tokens.
        val okHttp =
            "reusedConnection=false connectionAttempts=1 dnsMs=4 connectTotalMs=31 tlsMs=22 negotiatedProtocol=http/1.1 " +
                "addressFamily=ipv4 tlsVersion=TLSv1.3 requestBodyMs=1840 responseWaitMs=312"
        val darwin =
            "reusedConnection=true dnsMs=0 connectTotalMs=0 tlsMs=0 negotiatedProtocol=h2 transactions=1 " +
                "requestBodyMs=1840 responseWaitMs=312"

        for (facts in listOf(okHttp, darwin)) {
            assertEquals(
                RaftUploadWireFacts(requestBytes = 2_048L, requestBodyMs = 1_840L, responseWaitMs = 312L, edgePop = "NRT57-P2"),
                raftUploadWireFacts(requestBytes = 2_048L, facts = facts, edgePop = "NRT57-P2"),
                facts,
            )
        }
    }

    @Test
    fun aPhaseTheEngineDidNotMeasureIsAbsentNotZero() {
        val cases: List<Pair<String?, RaftUploadWireFacts>> =
            listOf(
                // No facts at all (an engine without metrics, or a request that never started).
                null to RaftUploadWireFacts(requestBytes = 10L),
                // The body went out but no answer came.
                "reusedConnection=true requestBodyMs=900" to RaftUploadWireFacts(requestBytes = 10L, requestBodyMs = 900L),
                // A wait without a body time has no last byte to start from.
                "reusedConnection=true responseWaitMs=40" to RaftUploadWireFacts(requestBytes = 10L),
                // Malformed or negative values are not measurements.
                "requestBodyMs=abc responseWaitMs=-3" to RaftUploadWireFacts(requestBytes = 10L),
                "requestBodyMs=-1 responseWaitMs=7" to RaftUploadWireFacts(requestBytes = 10L),
                // A measured zero stays a zero.
                "requestBodyMs=0 responseWaitMs=0" to RaftUploadWireFacts(requestBytes = 10L, requestBodyMs = 0L, responseWaitMs = 0L),
            )

        cases.forEach { (facts, expected) ->
            assertEquals(expected, raftUploadWireFacts(requestBytes = 10L, facts = facts, edgePop = null), "facts=$facts")
        }
        assertNull(raftUploadWireFacts(requestBytes = -1L, facts = null, edgePop = null).requestBytes)
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
    fun presignedPutHeadersCarryARequestIdAndNoTraceparent() {
        var seed = 0
        val headers = raftRequestIdHeaders(mapOf("Content-Type" to "image/jpeg")) { size -> ByteArray(size) { (seed++).toByte() } }

        assertEquals(
            mapOf("Content-Type" to "image/jpeg", "x-request-id" to "mobile-000102030405060708090a0b0c0d0e0f"),
            headers,
        )
        val own = mapOf("X-Request-Id" to "caller-id")
        assertEquals(own, raftRequestIdHeaders(own) { error("random bytes must not be requested") })
    }

    @Test
    fun traceIdsFormatTheSampledTraceparent() {
        var seed = 0
        val ids = newRaftTraceIds { size -> ByteArray(size) { (seed++).toByte() } }

        assertEquals("000102030405060708090a0b0c0d0e0f", ids.traceId)
        assertEquals("1011121314151617", ids.spanId)
        assertEquals("00-000102030405060708090a0b0c0d0e0f-1011121314151617-01", ids.traceparent)
    }
}
