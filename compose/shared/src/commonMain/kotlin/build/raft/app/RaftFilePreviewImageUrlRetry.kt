package build.raft.app

import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlinx.coroutines.delay

/** Backoff before each retry of the signed-URL request: two retries, at 0.5 s and 1.5 s. */
internal val RaftFilePreviewUrlRetryBackoffMillis: List<Long> = listOf(500L, 1_500L)

/**
 * Whether a failed signed-URL request is worth repeating. Only a request that never got an HTTP
 * status (code 0: network, TLS, timeout, unreachable host) is; any HTTP status, 4xx included,
 * is the server's answer and repeating it does not change it. A request retired on purpose
 * (lifecycle cancel) is not repeated either.
 */
internal fun raftFilePreviewUrlFailureIsRetryable(failure: ThreadApiResult.Failure): Boolean =
    failure.code == 0 && failure.errorBodyText?.startsWith("network_request_") != true

/** Runs [request], retrying retryable failures after each [backoffMillis] wait ([wait] is injectable). */
internal suspend fun fetchAttachmentUrlWithRetry(
    backoffMillis: List<Long> = RaftFilePreviewUrlRetryBackoffMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
    onRetry: (attempt: Int, failure: ThreadApiResult.Failure) -> Unit = { _, _ -> },
    request: suspend () -> ThreadApiResult<ThreadAttachmentOpenTarget>
): ThreadApiResult<ThreadAttachmentOpenTarget> {
    var attempt = 0
    while (true) {
        val result = request()
        if (result !is ThreadApiResult.Failure) return result
        if (attempt >= backoffMillis.size || !raftFilePreviewUrlFailureIsRetryable(result)) return result
        onRetry(attempt + 1, result)
        wait(backoffMillis[attempt])
        attempt += 1
    }
}

/**
 * The image payload once the signed-URL request has answered. A failed request keeps the thumbnail
 * that was already on screen as the image and flags [RaftFilePreviewPayload.imageFullLoadFailed], so
 * the viewer can offer a retry instead of an empty error state; with no thumbnail it is the
 * unavailable state.
 */
internal fun RaftFilePreviewPayload.withImageUrlResult(
    result: ThreadApiResult<ThreadAttachmentOpenTarget>,
    expiresAtSink: ((String?) -> Unit)? = null
): RaftFilePreviewPayload {
    val remoteImageUrl =
        when (result) {
            is ThreadApiResult.Success -> {
                expiresAtSink?.invoke(result.data.expiresAt)
                result.data.url.takeIf { it.isNotBlank() }
            }
            is ThreadApiResult.Failure -> null
        }
    val thumbnailUrl = currentImageThumbnailUrl() ?: imageUrl?.trim()?.takeIf { it.isNotBlank() }
    val resolvedImageUrl = remoteImageUrl ?: thumbnailUrl
    return copy(
        kind = "image",
        titleDesc = MR.strings.file_preview_image.desc(),
        imageUrl = resolvedImageUrl,
        imageFullLoadFailed = remoteImageUrl == null && resolvedImageUrl != null,
        message = "",
        messageDesc =
            if (resolvedImageUrl == null) {
                MR.strings.file_preview_error_image_unavailable.desc()
            } else {
                null
            },
        error = if (resolvedImageUrl == null) "image_preview_unavailable" else null
    )
}

/** Retry from the failed state: back to the loading page, still showing the thumbnail. */
internal fun RaftFilePreviewPayload.withImageFullLoadRetry(): RaftFilePreviewPayload =
    copy(
        kind = "loading",
        requestedKind = "image",
        imageFullLoadFailed = false,
        message = "",
        messageDesc = null,
        error = null
    )
