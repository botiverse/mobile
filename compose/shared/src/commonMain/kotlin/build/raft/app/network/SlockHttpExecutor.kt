package build.raft.app.network

/**
 * Transport-neutral response for the bottom HTTP layer. Maps 1:1 onto
 * HomeNetworkResponse / AuthNetworkResponse in the data layer.
 */
internal data class SlockHttpExecutorResponse(
    val code: Int,
    val bodyText: String?,
    val errorBodyText: String?
) {
    val isSuccessful: Boolean get() = code in 200..299
}

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
 * Android/iOS actuals return the Ktor executor; OHOS returns the
 * NetworkKMM/libcurl executor (Ktor CIO has no native TLS on OHOS).
 */
internal interface SlockHttpExecutor {
    suspend fun execute(
        priority: SlockRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean
    ): SlockHttpExecutorResponse

    suspend fun executeMultipart(
        priority: SlockRequestPriority,
        url: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        headers: Map<String, String>
    ): SlockHttpExecutorResponse
}

internal expect fun createPlatformSlockHttpExecutor(
    warningLogger: (String) -> Unit
): SlockHttpExecutor
