package build.raft.app.core.data.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** artin 2026-10-23: socket.io-client-java runs on an OkHttp client that pings the WebSocket. */
class RaftRealtimeOkHttpTest {
    @Test
    fun realtimeClientPingsTheWebSocketAndLeavesReadsToThePing() {
        val client = RaftRealtimeOkHttp.client

        assertEquals(15_000, client.pingIntervalMillis)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(0, client.readTimeoutMillis)
    }
}
