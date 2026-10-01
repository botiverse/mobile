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
        SlockHttpFileBody(
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
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 1_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        var captured: NetworkRequest? = null
        val executor =
            NetworkKmmSlockHttpExecutor(
                requestLifetimeCoordinator = coordinator,
                requestStarterOverride =
                    SlockNetworkRequestStarter { request, callback ->
                        captured = request
                        callback(okResponse(request))
                        SlockNetworkRequestCancelHandle { }
                    },
            )

        val response =
            executor.executeFile(
                priority = SlockRequestPriority.Foreground,
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
        val coordinator = SlockNetworkRequestLifetimeCoordinator(nowMillis = { 1_000L })
        coordinator.onProcessVisibilityChanged(foreground = true)
        val observed = mutableListOf<SlockTransferProgress>()
        val executor =
            NetworkKmmSlockHttpExecutor(
                requestLifetimeCoordinator = coordinator,
                requestStarterOverride =
                    SlockNetworkRequestStarter { request, callback ->
                        request.progress.uploadProgress?.invoke(NetworkTransferProgress(64L, 256L))
                        request.progress.uploadProgress?.invoke(NetworkTransferProgress(256L, 256L))
                        callback(okResponse(request))
                        SlockNetworkRequestCancelHandle { }
                    },
            )

        executor.executeFile(
            priority = SlockRequestPriority.Foreground,
            url = "https://signed.example/object",
            method = "PUT",
            headers = emptyMap(),
            body = fileBody(),
            onUploadProgress = observed::add,
        )

        assertEquals(
            listOf(SlockTransferProgress(64L, 256L), SlockTransferProgress(256L, 256L)),
            observed,
        )
    }
}
