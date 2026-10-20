package build.raft.app.network

import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.NetworkTransferProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/**
 * task #321 / #783 follow-up teeth: presigned direct-upload file bodies carry
 * the 30-minute total budget (not the multipart 3-minute budget), and the
 * byte-level progress seam remains wired on the real NetworkKMM request.
 */
class NetworkKmmDirectUploadTimeoutTest {

    private fun fileBody(contentLength: Long = 256L) =
        RaftHttpFileBody(
            path = "native://large.bin",
            contentType = "application/octet-stream",
            contentLength = contentLength,
            openStream = {
                NetworkByteStream(
                    contentLength = contentLength,
                    readAllBlock = { error("whole-file read forbidden") },
                    readChunksBlock = { },
                )
            },
        )

    private fun okResponse(request: NetworkRequest) =
        NetworkResponse(
            request = request,
            statusCode = 200,
            headers = emptyMap(),
            body = NetworkResponseBody("ok".encodeToByteArray()),
        )

    @Test
    fun directFileUploadCarriesThirtyMinuteBudget() = runBlocking {
        val coordinator = RaftNetworkRequestLifetimeCoordinator(nowMillis = { 1_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        var captured: NetworkRequest? = null
        val executor =
            NetworkKmmRaftHttpExecutor(
                requestLifetimeCoordinator = coordinator,
                requestStarterOverride =
                    RaftNetworkRequestStarter { request, callback ->
                        captured = request
                        callback(okResponse(request))
                        RaftNetworkRequestCancelHandle { }
                    },
            )

        val response =
            executor.executeFile(
                priority = RaftRequestPriority.Foreground,
                url = "https://signed.example/object",
                method = "PUT",
                headers = emptyMap(),
                body = fileBody(),
                onUploadProgress = null,
            )

        assertEquals(200, response.code)
        assertEquals(
            1_800_000L,
            assertNotNull(captured).policy.timeoutMillis,
            "direct-upload streaming PUT must carry the 30-minute total budget",
        )
    }

    @Test
    fun directFileUploadForwardsByteProgress() = runBlocking {
        val coordinator = RaftNetworkRequestLifetimeCoordinator(nowMillis = { 1_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val observed = mutableListOf<RaftTransferProgress>()
        val executor =
            NetworkKmmRaftHttpExecutor(
                requestLifetimeCoordinator = coordinator,
                requestStarterOverride =
                    RaftNetworkRequestStarter { request, callback ->
                        request.progress.uploadProgress?.invoke(NetworkTransferProgress(64L, 256L))
                        request.progress.uploadProgress?.invoke(NetworkTransferProgress(256L, 256L))
                        callback(okResponse(request))
                        RaftNetworkRequestCancelHandle { }
                    },
            )

        executor.executeFile(
            priority = RaftRequestPriority.Foreground,
            url = "https://signed.example/object",
            method = "PUT",
            headers = emptyMap(),
            body = fileBody(),
            onUploadProgress = observed::add,
        )

        assertEquals(
            listOf(RaftTransferProgress(64L, 256L), RaftTransferProgress(256L, 256L)),
            observed,
        )
    }
}
