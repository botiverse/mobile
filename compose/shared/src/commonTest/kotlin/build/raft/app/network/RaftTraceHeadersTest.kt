package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
