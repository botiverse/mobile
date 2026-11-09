package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftInFlightRequestsTest {
    private fun start(id: String, timeout: Long = 30_000L) =
        "shared_network slock_http_execute_start path=https://api.raft.build/api/channels/inbox method=GET " +
            "configTotalTimeoutMillis=$timeout configConnectTimeoutMillis=5000 clientRequestId=$id headers=none"

    @Test
    fun finishedAndFailedRequestsLeaveTheTableAndHungOnesAreListed() {
        val requests = RaftInFlightRequests()
        requests.observe(start("a"), 0L)
        requests.observe(start("b"), 10L)
        requests.observe(start("c", timeout = 5_000L), 20L)
        requests.observe("shared_network slock_http_execute_finish path=x code=200 clientRequestId=a durationMs=5", 30L)
        requests.observe("shared_network slock_http_execute_failed path=x method=GET clientRequestId=b durationMs=9", 40L)

        val open = requests.unfinished(nowMillis = 10_020L)

        assertEquals(1, open.size)
        val line = open.single()
        assertTrue(line, line.startsWith("network_request_unfinished clientRequestId=c ageMs=10000 timeoutMs=5000 overdue=true"))
        assertTrue(line, line.contains("path=https://api.raft.build/api/channels/inbox method=GET"))
        // Must persist: it may not read as the routine start the policy drops.
        assertTrue(RaftPersistentDiagnosticsPolicy.shouldPersist("warn", line))
    }

    @Test
    fun boundedAndIgnoresLinesWithoutARequestId() {
        val requests = RaftInFlightRequests(capacity = 2)
        requests.observe(start(""), 0L)
        requests.observe("thread_api ok", 0L)
        listOf("a", "b", "c").forEach { requests.observe(start(it), 0L) }

        val open = requests.unfinished(1L)

        assertEquals(listOf("b", "c"), open.map { it.substringAfter("clientRequestId=").substringBefore(' ') })
        assertFalse(open.single { it.contains("=c ") }.contains("overdue=true"))
    }
}
