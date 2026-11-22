package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
