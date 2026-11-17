package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * Backoff before each retry of the signed-URL request: 0.5 s, 1 s, 2 s, then every 4 s (the last
 * entry repeats) until [RaftFilePreviewUrlRetryBudgetMillis] is spent.
 */
internal val RaftFilePreviewUrlRetryBackoffMillis: List<Long> = listOf(500L, 1_000L, 2_000L, 4_000L)

/**
 * How long a tapped image keeps trying for its signed URL before the viewer shows the failed state
 * (Hands 43837e84). Two quick retries gave up in about 2 s, while the host reachability gate was
 * still cooling down after a network drop (its cooldown grows to 15 s), so an image tapped just
 * after a blip never opened. The viewer stays on its loading state meanwhile.
 */
internal const val RaftFilePreviewUrlRetryBudgetMillis: Long = 20_000L

/**
 * Whether a failed signed-URL request is worth repeating. Only a request that never got an HTTP
 * status (code 0: network, TLS, timeout, unreachable host) is; any HTTP status, 4xx included,
 * is the server's answer and repeating it does not change it. A request retired on purpose
 * (lifecycle cancel) is not repeated either.
 */
internal fun raftFilePreviewUrlFailureIsRetryable(failure: ThreadApiResult.Failure): Boolean =
    failure.code == 0 && failure.errorBodyText?.startsWith("network_request_") != true

/**
 * Runs [request], retrying retryable failures after each [backoffMillis] wait ([wait] is injectable)
 * for as long as the next attempt still starts within [budgetMillis] of the first, measured on
 * [timeSource]. A request that itself ran out the budget (a 20 s timeout) is not repeated.
 */
internal suspend fun fetchAttachmentUrlWithRetry(
    backoffMillis: List<Long> = RaftFilePreviewUrlRetryBackoffMillis,
    budgetMillis: Long = RaftFilePreviewUrlRetryBudgetMillis,
    timeSource: TimeSource = TimeSource.Monotonic,
    wait: suspend (Long) -> Unit = { delay(it) },
    onRetry: (attempt: Int, failure: ThreadApiResult.Failure) -> Unit = { _, _ -> },
    request: suspend () -> ThreadApiResult<ThreadAttachmentOpenTarget>
): ThreadApiResult<ThreadAttachmentOpenTarget> {
    val started = timeSource.markNow()
    var attempt = 0
    while (true) {
        val result = request()
        if (result !is ThreadApiResult.Failure) return result
        if (backoffMillis.isEmpty() || !raftFilePreviewUrlFailureIsRetryable(result)) return result
        val pause = backoffMillis[minOf(attempt, backoffMillis.lastIndex)]
        if (started.elapsedNow().inWholeMilliseconds + pause > budgetMillis) return result
        onRetry(attempt + 1, result)
        wait(pause)
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
        titleDesc = Res.string.file_preview_image.desc(),
        imageUrl = resolvedImageUrl,
        imageFullLoadFailed = remoteImageUrl == null && resolvedImageUrl != null,
        message = "",
        messageDesc =
            if (resolvedImageUrl == null) {
                Res.string.file_preview_error_image_unavailable.desc()
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
