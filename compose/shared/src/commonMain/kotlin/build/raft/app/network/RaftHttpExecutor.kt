package build.raft.app.network

import com.tencent.kmm.network.export.NetworkByteStream

/**
 * Transport-neutral response for the bottom HTTP layer. Maps 1:1 onto
 * HomeNetworkResponse / AuthNetworkResponse in the data layer.
 */
internal data class RaftHttpExecutorResponse(
    val code: Int,
    val bodyText: String?,
    val errorBodyText: String?,
    val transportDiagnostics: String? = null,
    val responseHeaders: Map<String, List<String>> = emptyMap()
) {
    val isSuccessful: Boolean get() = code in 200..299
}

data class RaftTransferProgress(
    val bytesTransferred: Long,
    val bytesTotal: Long? = null
)

/** A one-attempt binary request body.  The stream is opened once per transport attempt. */
internal data class RaftHttpFileBody(
    val path: String,
    val contentType: String,
    val contentLength: Long,
    val openStream: suspend () -> NetworkByteStream,
)

/**
 * The single seam between Slock's shared network semantics (auth middleware,
 * refresh, scheduling, diagnostics, URL assembly, fileRef resolution — all in
 * commonMain above this interface) and the platform HTTP engine below it.
 *
 * Contract:
 * - [url] is already absolute with encoded query; executors must not re-derive
 *   base URLs or re-encode.
 * - [method] is an upper-case HTTP verb.
 * - Executors map transport failures to code 0 + errorBodyText and rethrow
 *   CancellationException; they never throw for HTTP-level errors.
 * - No transport-level retry: refresh/retry policy is owned by the callers.
 *
 * Every platform gets the NetworkKMM executor (the library's
 * pooled ktor engines on Android/iOS) — see task #12.
 */
internal interface RaftHttpExecutor {
    suspend fun execute(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean
    ): RaftHttpExecutorResponse

    suspend fun executeMultipart(
        priority: RaftRequestPriority,
        url: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        headers: Map<String, String>,
        onUploadProgress: ((RaftTransferProgress) -> Unit)? = null
    ): RaftHttpExecutorResponse

    /** Binary in-memory request; default keeps test doubles source-compatible. */
    suspend fun executeBytes(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        onUploadProgress: ((RaftTransferProgress) -> Unit)? = null,
    ): RaftHttpExecutorResponse =
        RaftHttpExecutorResponse(
            code = 501,
            bodyText = null,
            errorBodyText = "binary_request_unsupported",
        )

    /** Streaming file request; default keeps test doubles source-compatible. */
    suspend fun executeFile(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: RaftHttpFileBody,
        onUploadProgress: ((RaftTransferProgress) -> Unit)? = null,
    ): RaftHttpExecutorResponse =
        RaftHttpExecutorResponse(
            code = 501,
            bodyText = null,
            errorBodyText = "file_request_unsupported",
        )
}
