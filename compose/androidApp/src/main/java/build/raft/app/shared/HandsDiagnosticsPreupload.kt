package build.raft.app.shared

import build.raft.app.FeedbackDiagnosticsReference
import build.raft.app.data.FEEDBACK_DIAGNOSTICS_CONTENT_TYPE
import build.raft.app.data.FEEDBACK_DIAGNOSTICS_MAX_BYTES
import build.raft.app.data.encodeSharedFeedbackDiagnosticsPresignRequest
import build.raft.app.data.parseSharedFeedbackDiagnosticsPresignResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hands' public feedback presign + direct R2 PUT flow for the standalone feedback WebView.
 *
 * The SDK's regular [build.hands.update.HandsFeedback] client intentionally hides this path and
 * uses a blocking request body. This small client keeps the signed URL and client key native,
 * streams the ZIP with cancellation/progress, and returns only the controlled ticket reference.
 */
internal class HandsDiagnosticsPreupload(
    private val baseUrl: String,
    private val appSlug: String,
    private val clientKey: String,
    private val httpClient: OkHttpClient,
) {
    private val activeCall = AtomicReference<Call?>(null)

    suspend fun upload(
        file: File,
        fileName: String = file.name,
        contentType: String = FEEDBACK_DIAGNOSTICS_CONTENT_TYPE,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): FeedbackDiagnosticsReference {
        val size = file.length()
        require(size in 1..FEEDBACK_DIAGNOSTICS_MAX_BYTES) {
            "diagnostics archive size is outside the permitted bound"
        }
        require(contentType == FEEDBACK_DIAGNOSTICS_CONTENT_TYPE) {
            "diagnostics archive content type is invalid"
        }
        require(fileName.isNotBlank() && fileName.length <= 256) {
            "diagnostics archive filename is invalid"
        }
        val presignBody = encodeSharedFeedbackDiagnosticsPresignRequest(
            filename = fileName,
            contentType = contentType,
            size = size,
        )
        val presignUrl = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("public/v2/apps")
            .addPathSegment(appSlug)
            .addPathSegments("feedback/presign")
            .build()
        val presignRequest = Request.Builder()
            .url(presignUrl)
            .header("accept", "application/json")
            .header("content-type", "application/json")
            .apply { if (clientKey.isNotBlank()) header("X-Hands-Client-Key", clientKey) }
            .post(presignBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val upload = withContext(Dispatchers.IO) {
            execute(presignRequest).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw HandsDiagnosticsPreuploadException(response.code, "presign failed")
                val parsed = try {
                    parseSharedFeedbackDiagnosticsPresignResponse(body)
                } catch (error: Exception) {
                    throw HandsDiagnosticsPreuploadException(
                        response.code,
                        "presign response invalid",
                        error,
                    )
                }
                PresignedUpload(parsed.uploadUrl, parsed.r2Key)
            }
        }

        val putRequest = Request.Builder()
            .url(upload.url)
            .header("content-type", contentType)
            .put(
                ProgressFileRequestBody(
                    file = file,
                    mediaType = contentType.toMediaType(),
                    onProgress = onProgress,
                ),
            )
            .build()
        withContext(Dispatchers.IO) {
            execute(putRequest).use { response ->
                if (!response.isSuccessful) throw HandsDiagnosticsPreuploadException(response.code, "diagnostics upload failed")
            }
        }
        return FeedbackDiagnosticsReference(
            r2Key = upload.r2Key,
            filename = fileName,
            contentType = contentType,
            size = size,
        )
    }

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    private suspend fun execute(request: Request): okhttp3.Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            activeCall.set(call)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                val response = call.execute()
                activeCall.compareAndSet(call, null)
                if (continuation.isActive) continuation.resume(response)
                else response.close()
            } catch (error: Throwable) {
                activeCall.compareAndSet(call, null)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    private data class PresignedUpload(val url: String, val r2Key: String)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal class HandsDiagnosticsPreuploadException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private class ProgressFileRequestBody(
    private val file: File,
    private val mediaType: okhttp3.MediaType,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType(): okhttp3.MediaType = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                sent += read
                onProgress(sent, total)
            }
        }
    }
}
