package build.raft.app.network

import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class NetworkKmmRequestLifetimeTest {
    @Test
    fun lifecycleRetiredMutationReturnsOutcomeUnknownAndDoesNotReplay() = runBlocking {
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 1_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val callbackReady = CompletableDeferred<Pair<NetworkRequest, (NetworkResponse) -> Unit>>()
        var startCount = 0
        var cancelCount = 0
        val executor =
            NetworkKmmSlockHttpExecutor(
                requestLifetimeCoordinator = coordinator,
                requestStarterOverride =
                    SlockNetworkRequestStarter { request, callback ->
                        startCount += 1
                        callbackReady.complete(request to callback)
                        SlockNetworkRequestCancelHandle { cancelCount += 1 }
                    }
            )
        val pending =
            async {
                executor.execute(
                    priority = SlockRequestPriority.Foreground,
                    url = "https://example.test/channels/channel-1/threads",
                    method = "POST",
                    headers = emptyMap(),
                    bodyText = "{}",
                    jsonBody = true
                )
            }
        val (request, lateCallback) = callbackReady.await()

        coordinator.onProcessVisibilityChanged(foreground = false)
        val response = withTimeout(250L) { pending.await() }
        lateCallback(
            NetworkResponse(
                request = request,
                statusCode = 200,
                headers = emptyMap(),
                body = NetworkResponseBody("{}".encodeToByteArray())
            )
        )

        assertEquals(0, response.code)
        assertEquals("network_request_mutation_outcome_unknown", response.errorBodyText)
        assertContains(response.transportDiagnostics.orEmpty(), "methodClass=mutation")
        assertContains(response.transportDiagnostics.orEmpty(), "outcome=unknown")
        assertContains(response.transportDiagnostics.orEmpty(), "replay=false")
        assertEquals(1, startCount)
        assertEquals(1, cancelCount)
    }

    @Test
    fun callbackCompletionReturnsOnTheOwningCoroutineWithNoExtraAttempt() = runBlocking {
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 2_000L })
        var startCount = 0
        val executor =
            NetworkKmmSlockHttpExecutor(
                requestLifetimeCoordinator = coordinator,
                requestStarterOverride =
                    SlockNetworkRequestStarter { request, callback ->
                        startCount += 1
                        callback(
                            NetworkResponse(
                                request = request,
                                statusCode = 200,
                                headers = emptyMap(),
                                body = NetworkResponseBody("ok".encodeToByteArray())
                            )
                        )
                        SlockNetworkRequestCancelHandle { error("completed call must not cancel") }
                    }
            )

        val response =
            executor.execute(
                priority = SlockRequestPriority.Foreground,
                url = "https://example.test/messages/channel/channel-1",
                method = "GET",
                headers = emptyMap(),
                bodyText = null,
                jsonBody = false
            )

        assertEquals(200, response.code)
        assertEquals("ok", response.bodyText)
        assertEquals(1, startCount)
    }
}
