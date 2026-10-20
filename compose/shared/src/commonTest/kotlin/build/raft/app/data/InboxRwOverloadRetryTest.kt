package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /channels/inbox` retries once on a RisingWave overload, like web
 * `getWithRwOverloadRetry` (botiverse/slock 1bb70cbeb, web/src/api/overloadRetry.ts).
 * The 503 shape is the server's (`channels.threads.api.test.ts:592-599` @ 1bb70cbeb):
 * status 503, `retry-after: 2`, body `{error, code:"rw_overloaded", retryable:true}`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxRwOverloadRetryTest {

    @Test
    fun overloadRetriesOnceAfterRetryAfterOnVirtualTime() = runTest {
        listOf(
            mapOf("Retry-After" to listOf("2")) to 2_000L,
            mapOf("retry-after" to listOf("60")) to 10_000L, // capped like web
            emptyMap<String, List<String>>() to 2_000L // missing header -> default
        ).forEach { (headers, expectedDelay) ->
            val network = ScriptedInboxClient(this, listOf(overloaded(headers), ok()))
            val started = currentTime

            val result = RealInboxRepository(InMemoryRaftAuthSessionStore(sampleSession()), network).loadInitial(ServerId("server-1"))

            assertTrue(result is DataResult.Empty, "the retried 200 (no rows) is used: headers=$headers result=$result")
            assertEquals(listOf(started, started + expectedDelay), network.requestTimes, "headers=$headers")
        }
    }

    @Test
    fun oneRetryOnlyAndOnlyForTheRetryableOverloadCode() = runTest {
        val twice = ScriptedInboxClient(this, listOf(overloaded(), overloaded(), ok()))
        val failed = RealInboxRepository(InMemoryRaftAuthSessionStore(sampleSession()), twice).loadInitial(ServerId("server-1"))
        assertEquals(2, twice.requestTimes.size, "never a second retry")
        assertEquals("server_unavailable", (failed as DataResult.Error).error.code)

        listOf(
            HomeNetworkResponse(503, null, errorBodyText = """{"error":"x","code":"rw_unconfigured","retryable":false}"""),
            HomeNetworkResponse(503, null, errorBodyText = """{"error":"x","code":"rw_overloaded","retryable":false}"""),
            HomeNetworkResponse(500, null, errorBodyText = """{"error":"x","code":"rw_overloaded","retryable":true}""")
        ).forEach { response ->
            val network = ScriptedInboxClient(this, listOf(response, ok()))
            RealInboxRepository(InMemoryRaftAuthSessionStore(sampleSession()), network).loadInitial(ServerId("server-1"))
            assertEquals(1, network.requestTimes.size, "no retry for $response")
        }
    }

    @Test
    fun activityUnreadSummaryReadUsesTheSameRetry() = runTest {
        val network = ScriptedInboxClient(this, listOf(overloaded(), ok()))
        val result =
            ProcessActivityUnreadRepository(InMemoryRaftAuthSessionStore(sampleSession()), network).load(
                ProcessActivityUnreadOwner(environmentId = "https://api.example.test", accountId = "user-1", serverId = "server-1"),
                DataRequestPriority.Background
            )
        assertEquals(ActivityUnreadSummarySnapshot(totalUnreadCount = 3, totalCount = 4), (result as DataResult.Content).data)
        assertEquals(2, network.requestTimes.size)
    }

    private fun overloaded(headers: Map<String, List<String>> = mapOf("retry-after" to listOf("2"))): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 503,
            bodyText = null,
            errorBodyText = """{"error":"Temporarily overloaded, retry shortly","code":"rw_overloaded","retryable":true}""",
            responseHeaders = headers
        )

    private fun ok(): HomeNetworkResponse =
        HomeNetworkResponse(200, """{"items":[],"hasMore":false,"totalCount":4,"totalUnreadCount":3,"activeUnreadCount":3}""")

    private class ScriptedInboxClient(
        private val testScope: TestScope,
        private val responses: List<HomeNetworkResponse>
    ) : RaftNetworkClient {
        val requestTimes = mutableListOf<Long>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            check(path == "channels/inbox" && method == "GET") { "unexpected $method $path" }
            requestTimes += testScope.currentTime
            return responses[requestTimes.size - 1]
        }
    }
}
