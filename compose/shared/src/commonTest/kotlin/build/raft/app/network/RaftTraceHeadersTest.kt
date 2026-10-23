package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftTraceHeadersTest {
    @Test
    fun generatesJoinableRequestIdAndTraceparent() {
        var seed = 0
        val headers = raftTraceHeaders(emptyMap()) { size ->
            ByteArray(size) { (seed++).toByte() }
        }

        assertEquals("mobile-000102030405060708090a0b0c0d0e0f", headers["x-request-id"])
        assertEquals(
            "00-000102030405060708090a0b0c0d0e0f-1011121314151617-01",
            headers["traceparent"]
        )
    }

    @Test
    fun preservesCallerProvidedCorrelationHeadersCaseInsensitively() {
        val original = mapOf("X-Request-Id" to "caller-id", "TraceParent" to "caller-trace")

        val headers = raftTraceHeaders(original) { error("random bytes must not be requested") }

        assertTrue(headers === original)
        assertEquals("caller-id", headers["X-Request-Id"])
        assertEquals("caller-trace", headers["TraceParent"])
    }

    @Test
    fun generatedTraceparentNeverUsesForbiddenAllZeroIds() {
        val headers = raftTraceHeaders(emptyMap()) { size -> ByteArray(size) }

        assertEquals(
            "00-00000000000000000000000000000001-0000000000000001-01",
            headers["traceparent"]
        )
        assertEquals("mobile-00000000000000000000000000000001", headers["x-request-id"])
    }
}
