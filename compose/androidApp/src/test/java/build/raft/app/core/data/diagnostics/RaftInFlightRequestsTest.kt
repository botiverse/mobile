package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftInFlightRequestsTest {
    private fun start(id: String, timeout: Long? = 30_000L, path: String = "https://api.raft.build/api/channels/inbox") =
        "shared_network slock_http_execute_start path=$path method=GET " +
            (timeout?.let { "configTotalTimeoutMillis=$it " } ?: "") +
            "configConnectTimeoutMillis=5000 clientRequestId=$id headers=none"

    @Test
    fun finishedAndFailedRequestsLeaveTheTableAndHungOnesAreListed() {
        val requests = RaftInFlightRequests()
        requests.observe(start("a"), 0L)
        requests.observe(start("b"), 10L)
        requests.observe(start("c", timeout = 5_000L), 20L)
        requests.observe("shared_network slock_http_execute_finish path=x code=200 clientRequestId=a durationMs=5", 30L)
        requests.observe("shared_network slock_http_execute_failed path=x method=GET clientRequestId=b durationMs=9", 40L)

        val unfinished = requests.unfinished(nowMillis = 10_020L)

        assertNull(unfinished.finishUnobserved)
        val open = unfinished.open
        assertEquals(1, open.size)
        val line = open.single()
        assertTrue(line, line.startsWith("network_request_unfinished clientRequestId=c ageMs=10000 timeoutMs=5000 overdue=true"))
        assertTrue(line, line.contains("path=https://api.raft.build/api/channels/inbox method=GET"))
        // Must persist: it may not read as the routine start the policy drops.
        assertTrue(RaftPersistentDiagnosticsPolicy.shouldPersist("warn", line))
    }

    @Test
    fun aRequestOpenPastItsTimeoutAndTheGraceLostItsEndLineAndIsReportedOnce() {
        val requests = RaftInFlightRequests()
        requests.observe(start("lost"), 0L)
        requests.observe(start("late"), 60_000L)

        val first = requests.unfinished(nowMillis = 120_000L)

        // 120 s after the start of a request with a 30 s timeout: it ended, its finish line was lost.
        assertEquals(
            "network_request_finish_unobserved count=1 oldestAgeMs=120000 paths=https://api.raft.build/api/channels/inbox",
            first.finishUnobserved,
        )
        assertTrue(RaftPersistentDiagnosticsPolicy.shouldPersist("info", first.finishUnobserved!!))
        // 60 s: past its timeout, at the end of the grace: still listed, as overdue.
        val late = first.open.single()
        assertTrue(late, late.startsWith("network_request_unfinished clientRequestId=late ageMs=60000 timeoutMs=30000 overdue=true"))

        val second = requests.unfinished(nowMillis = 120_000L)

        assertNull(second.finishUnobserved)
        assertEquals(listOf("late"), second.open.map { it.substringAfter("clientRequestId=").substringBefore(' ') })
    }

    @Test
    fun aStartWithoutATimeoutStaysListedUpToTheCeilingAndTheLostPathsAreBounded() {
        val requests = RaftInFlightRequests()
        (1..10).forEach { n -> requests.observe(start("r$n", timeout = null, path = "https://api.raft.build/api/p$n"), 0L) }
        requests.observe(start("r11", timeout = null, path = "https://api.raft.build/api/p1"), 0L)

        val early = requests.unfinished(nowMillis = 10 * 60_000L)

        assertNull(early.finishUnobserved)
        assertEquals(11, early.open.size)
        assertTrue(early.open.all { it.contains(" timeoutMs=-1 overdue=false ") })

        // Past the ceiling (the longest timeout a request is given, 30 min, plus the grace).
        val late = requests.unfinished(nowMillis = 31 * 60_000L)

        assertEquals(
            "network_request_finish_unobserved count=11 oldestAgeMs=1860000 paths=" +
                (1..8).joinToString(",") { "https://api.raft.build/api/p$it" } + ",+2",
            late.finishUnobserved,
        )
        assertTrue(late.open.isEmpty())
    }

    @Test
    fun boundedAndIgnoresLinesWithoutARequestId() {
        val requests = RaftInFlightRequests(capacity = 2)
        requests.observe(start(""), 0L)
        requests.observe("thread_api ok", 0L)
        listOf("a", "b", "c").forEach { requests.observe(start(it), 0L) }

        val open = requests.unfinished(1L).open

        assertEquals(listOf("b", "c"), open.map { it.substringAfter("clientRequestId=").substringBefore(' ') })
        assertFalse(open.single { it.contains("=c ") }.contains("overdue=true"))
    }
}
