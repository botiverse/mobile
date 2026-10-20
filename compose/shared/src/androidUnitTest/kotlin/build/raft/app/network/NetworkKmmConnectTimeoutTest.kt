package build.raft.app.network

import com.tencent.kmm.network.export.NetworkPriority
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * task #1138: ordinary requests (login, lists, plain GET/POST) must carry an
 * explicit connect timeout from the app-side constant instead of inheriting
 * the NetworkKMM library default. The factory is the single source; these
 * tests pin the factory and the built requests, so a new call site that
 * bypasses the factory cannot stay green by accident.
 */
class NetworkKmmConnectTimeoutTest {

    private fun okResponse(request: NetworkRequest) =
        NetworkResponse(
            request = request,
            statusCode = 200,
            headers = emptyMap(),
            body = NetworkResponseBody(bytes = """{"ok":true}""".encodeToByteArray())
        )

    private fun capturingExecutor(
        logs: MutableList<String> = mutableListOf(),
        onRequest: (NetworkRequest) -> Unit
    ) =
        NetworkKmmRaftHttpExecutor(
            warningLogger = { logs.add(it) },
            requestStarterOverride = { request, onComplete ->
                onRequest(request)
                onComplete(okResponse(request))
                RaftNetworkRequestCancelHandle {}
            }
        )

    @Test
    fun factoryAlwaysSetsTheAppConnectTimeout() {
        val policy = raftNetworkRequestPolicy(totalTimeoutMillis = 30_000L)

        assertEquals(30_000L, policy.timeoutMillis)
        assertEquals(NetworkPriority.NORMAL, policy.priority)
        assertEquals(
            RAFT_CONNECT_TIMEOUT_MILLIS,
            policy.streamTimeouts.connectTimeoutMillis,
            "the factory must never leave connect to the library default"
        )
        assertEquals(10_000L, RAFT_CONNECT_TIMEOUT_MILLIS, "task #1138 pins 10s (equal to today's library default)")
    }

    @Test
    fun factoryPassesAnExplicitConnectTimeoutThrough() {
        val policy =
            raftNetworkRequestPolicy(
                totalTimeoutMillis = 180_000L,
                priority = NetworkPriority.HIGH,
                connectTimeoutMillis = 4_321L
            )

        assertEquals(180_000L, policy.timeoutMillis)
        assertEquals(NetworkPriority.HIGH, policy.priority)
        assertEquals(4_321L, policy.streamTimeouts.connectTimeoutMillis)
    }

    @Test
    fun ordinaryRequestCarriesTheAppConnectTimeoutAndLogsIt() = runTest {
        var captured: NetworkRequest? = null
        val logs = mutableListOf<String>()
        val executor = capturingExecutor(logs) { captured = it }

        val response =
            executor.execute(
                priority = RaftRequestPriority.Foreground,
                url = "https://api.test/api/servers",
                method = "GET",
                headers = emptyMap(),
                bodyText = null,
                jsonBody = false
            )

        val request = assertNotNull(captured)
        assertEquals(200, response.code)
        assertEquals(30_000L, request.policy.timeoutMillis, "ordinary total budget unchanged")
        assertEquals(
            RAFT_CONNECT_TIMEOUT_MILLIS,
            request.policy.streamTimeouts.connectTimeoutMillis,
            "login/list style requests must not inherit the library connect default"
        )
        val start = logs.firstOrNull { it.startsWith("slock_networkkmm_execute_start") }
        assertNotNull(start, "execute_start line must be logged")
        assertTrue(
            start.contains("configConnectTimeoutMillis=$RAFT_CONNECT_TIMEOUT_MILLIS"),
            "telemetry must report the configured connect timeout: $start"
        )
        assertTrue(start.contains("configTotalTimeoutMillis=30000"), start)
    }

    @Test
    fun multipartRequestCarriesTheAppConnectTimeout() = runTest {
        var captured: NetworkRequest? = null
        val logs = mutableListOf<String>()
        val executor =
            NetworkKmmRaftHttpExecutor(
                warningLogger = { logs.add(it) },
                multipartTransportOverride = { request ->
                    captured = request
                    okResponse(request)
                }
            )

        executor.executeMultipart(
            priority = RaftRequestPriority.Foreground,
            url = "https://api.test/api/attachments/upload",
            fields = mapOf("channelId" to "c1"),
            files =
                listOf(
                    MultipartFile(
                        name = "files",
                        fileName = "small.txt",
                        mimeType = "text/plain",
                        bytes = "hello".encodeToByteArray()
                    )
                ),
            headers = emptyMap()
        )

        val request = assertNotNull(captured)
        assertEquals(30_000L, request.policy.timeoutMillis)
        assertEquals(RAFT_CONNECT_TIMEOUT_MILLIS, request.policy.streamTimeouts.connectTimeoutMillis)
        val start = logs.firstOrNull { it.startsWith("slock_networkkmm_multipart_start") }
        assertNotNull(start)
        assertTrue(start.contains("configConnectTimeoutMillis=$RAFT_CONNECT_TIMEOUT_MILLIS"), start)
    }

    @Test
    fun presignedUploadsKeepTheirOwnConnectBudgetThroughTheFactory() = runTest {
        var captured: NetworkRequest? = null
        val executor = capturingExecutor { captured = it }

        executor.executeBytes(
            priority = RaftRequestPriority.Foreground,
            url = "https://example.test/r2-bucket/key",
            method = "PUT",
            headers = emptyMap(),
            bodyBytes = "hello".encodeToByteArray()
        )

        val request = assertNotNull(captured)
        assertEquals(
            10_000L,
            request.policy.streamTimeouts.connectTimeoutMillis,
            "presigned PUT keeps its documented 10s connect budget (task #1135)"
        )
    }
}
