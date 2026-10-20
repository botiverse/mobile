package build.raft.app.network

import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NetworkKmmRequestLifetimeTest {
    @Test
    fun concurrentComposerUploadsAndSendRemainProcessBoundAcrossVisibilityEdges() = runBlocking {
        val coordinator = RaftNetworkRequestLifetimeCoordinator(nowMillis = { 1_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val callbacks = Channel<Pair<NetworkRequest, (NetworkResponse) -> Unit>>(Channel.UNLIMITED)
        var startCount = 0
        var cancelCount = 0
        val executor =
            NetworkKmmRaftHttpExecutor(
                requestLifetimeCoordinator = coordinator,
                requestStarterOverride =
                    RaftNetworkRequestStarter { request, callback ->
                        startCount += 1
                        callbacks.trySend(request to callback).getOrThrow()
                        RaftNetworkRequestCancelHandle { cancelCount += 1 }
                    }
            )
        val sendPending =
            async {
                executor.execute(
                    priority = RaftRequestPriority.Foreground,
                    url = "https://example.test/api/messages",
                    method = "POST",
                    headers = mapOf(HEADER_SERVER_ID to "server-1"),
                    bodyText = "{}",
                    jsonBody = true
                )
            }
        val firstUploadPending =
            async {
                executor.executeMultipart(
                    priority = RaftRequestPriority.Foreground,
                    url = "https://example.test/api/attachments/upload",
                    fields = mapOf("channelId" to "channel-1"),
                    files =
                        listOf(
                            MultipartFile(
                                name = "files",
                                fileName = "first.jpg",
                                mimeType = "image/jpeg",
                                bytes = byteArrayOf(1)
                            )
                        ),
                    headers = emptyMap(),
                    onUploadProgress = null
                )
            }
        val secondUploadPending =
            async {
                executor.executeMultipart(
                    priority = RaftRequestPriority.Foreground,
                    url = "https://example.test/api/attachments/upload",
                    fields = mapOf("channelId" to "channel-1"),
                    files =
                        listOf(
                            MultipartFile(
                                name = "files",
                                fileName = "second.jpg",
                                mimeType = "image/jpeg",
                                bytes = byteArrayOf(2)
                            )
                        ),
                    headers = emptyMap(),
                    onUploadProgress = null
                )
            }
        val pending = listOf(sendPending, firstUploadPending, secondUploadPending)
        val startedRequests = List(pending.size) { callbacks.receive() }

        val background = coordinator.onProcessVisibilityChanged(foreground = false)
        val foreground = coordinator.onProcessVisibilityChanged(foreground = true)

        assertEquals(0, background.retiredRequestCount)
        assertEquals(0, foreground.retiredRequestCount)
        assertFalse(pending.any { it.isCompleted })

        startedRequests.forEach { (request, callback) ->
            callback(
                NetworkResponse(
                    request = request,
                    statusCode = 200,
                    headers = emptyMap(),
                    body = NetworkResponseBody("{}".encodeToByteArray())
                )
            )
        }
        val responses = withTimeout(250L) { pending.map { it.await() } }

        assertEquals(listOf(200, 200, 200), responses.map { it.code })
        assertEquals(listOf("{}", "{}", "{}"), responses.map { it.bodyText })
        assertEquals(3, startCount)
        assertEquals(0, cancelCount)
    }

    @Test
    fun callbackCompletionReturnsOnTheOwningCoroutineWithNoExtraAttempt() = runBlocking {
        val coordinator = RaftNetworkRequestLifetimeCoordinator(nowMillis = { 2_000L })
        var startCount = 0
        val executor =
            NetworkKmmRaftHttpExecutor(
                requestLifetimeCoordinator = coordinator,
                requestStarterOverride =
                    RaftNetworkRequestStarter { request, callback ->
                        startCount += 1
                        callback(
                            NetworkResponse(
                                request = request,
                                statusCode = 200,
                                headers = emptyMap(),
                                body = NetworkResponseBody("ok".encodeToByteArray())
                            )
                        )
                        RaftNetworkRequestCancelHandle { error("completed call must not cancel") }
                    }
            )

        val response =
            executor.execute(
                priority = RaftRequestPriority.Foreground,
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
