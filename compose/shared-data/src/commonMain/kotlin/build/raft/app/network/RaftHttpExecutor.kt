package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

/**
 * Transport-neutral response for the bottom HTTP layer. Maps 1:1 onto
 * HomeNetworkResponse / AuthNetworkResponse in the data layer.
 */
@RaftCoreInternalApi data class RaftHttpExecutorResponse(
    val code: Int,
    val bodyText: String?,
    val errorBodyText: String?,
    val transportDiagnostics: String? = null,
    val responseHeaders: Map<String, List<String>> = emptyMap(),
    /** Upload requests only (multipart POST, presigned PUT): what the transport measured. */
    val uploadFacts: RaftUploadWireFacts? = null,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Wire-side only: the bytes an executor handed to the engine so far. It never reaches UI or
 * stores; a transfer's owner feeds it to a [build.raft.app.transfer.RaftTransferMeter].
 */
data class RaftWireTransferProgress(
    val bytesTransferred: Long,
    val bytesTotal: Long? = null
)

/** A one-attempt binary request body.  The stream is opened once per transport attempt. */
@RaftCoreInternalApi data class RaftHttpFileBody(
    val path: String,
    val contentType: String,
    val contentLength: Long,
    val openStream: suspend () -> RaftByteStream,
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
 * Every platform gets [KtorRaftHttpExecutor] (OkHttp on Android, NSURLSession
 * on iOS); the NetworkKMM curl executor only behind the Developer override
 * (see [createPlatformRaftHttpExecutor]).
 */
@RaftCoreInternalApi interface RaftHttpExecutor {
    suspend fun execute(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean
    ): RaftHttpExecutorResponse

    /**
     * [execute] under its own whole-call budget ([totalTimeoutMillis]) instead of the ordinary one, with
     * no shorter idle timeout: the upload session's complete call
     * ([RAFT_ATTACHMENT_UPLOAD_COMPLETE_TIMEOUT_MILLIS]). The default keeps test doubles
     * source-compatible; they have no budget of their own.
     */
    suspend fun executeWithTotalTimeout(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean,
        totalTimeoutMillis: Long,
    ): RaftHttpExecutorResponse = execute(priority, url, method, headers, bodyText, jsonBody)

    suspend fun executeMultipart(
        priority: RaftRequestPriority,
        url: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        headers: Map<String, String>,
        onUploadProgress: ((RaftWireTransferProgress) -> Unit)? = null
    ): RaftHttpExecutorResponse

    /** Binary in-memory request; default keeps test doubles source-compatible. */
    suspend fun executeBytes(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        onUploadProgress: ((RaftWireTransferProgress) -> Unit)? = null,
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
        onUploadProgress: ((RaftWireTransferProgress) -> Unit)? = null,
    ): RaftHttpExecutorResponse =
        RaftHttpExecutorResponse(
            code = 501,
            bodyText = null,
            errorBodyText = "file_request_unsupported",
        )
}
