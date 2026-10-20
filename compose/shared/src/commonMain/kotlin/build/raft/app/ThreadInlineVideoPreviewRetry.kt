package build.raft.app

import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget

/**
 * Bounded backoff for the inline video preview link.
 *
 * Right after a video is sent the server may not serve the attachment link yet
 * (the upload is still being finalised / the message link is not visible yet),
 * so the first `GET /attachments/:id/url?disposition=inline` can fail
 * transiently. Instead of showing "preview unavailable" at once, the card keeps
 * a processing state and retries: attempts 1..[maxAttempts] spaced by
 * [initialDelayMs] doubling up to [maxDelayMs] (default 2s, 4s, 8s, 16s, about
 * 30s in total). A realtime update can wake a waiting retry early; that still
 * counts as an attempt, so the budget stays bounded.
 */
internal data class ThreadInlineVideoRetryPolicy(
    val maxAttempts: Int = 5,
    val initialDelayMs: Long = 2_000L,
    val maxDelayMs: Long = 16_000L
) {
    /** Delay before [nextAttempt] (1-based; the first attempt has none), or null once the budget is spent. */
    fun delayBeforeAttempt(nextAttempt: Int): Long? {
        if (nextAttempt <= 1) return 0L
        if (nextAttempt > maxAttempts) return null
        var delayMs = initialDelayMs
        repeat(nextAttempt - 2) {
            delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
        }
        return delayMs.coerceAtMost(maxDelayMs)
    }

    companion object {
        val Default = ThreadInlineVideoRetryPolicy()
    }
}

internal enum class ThreadInlineVideoFailureKind {
    /** Worth retrying: the video may still be processing or the network blipped. */
    Transient,

    /** The server gave a definitive answer (auth, permission, bad request); retrying will not help. */
    Hard
}

internal sealed interface ThreadInlineVideoFetchOutcome {
    data class Ready(val url: String, val expiresAt: String?) : ThreadInlineVideoFetchOutcome

    data class Failed(val code: Int, val kind: ThreadInlineVideoFailureKind) : ThreadInlineVideoFetchOutcome
}

/** Transient: transport failure, 2xx without a usable link, 404 (not visible yet), 408/409/423/425/429 and 5xx. */
internal fun threadInlineVideoFailureKind(code: Int): ThreadInlineVideoFailureKind =
    when {
        code <= 0 -> ThreadInlineVideoFailureKind.Transient
        code in 200..299 -> ThreadInlineVideoFailureKind.Transient
        code == 404 || code == 408 || code == 409 || code == 423 || code == 425 || code == 429 ->
            ThreadInlineVideoFailureKind.Transient
        code >= 500 -> ThreadInlineVideoFailureKind.Transient
        else -> ThreadInlineVideoFailureKind.Hard
    }

internal fun ThreadApiResult<ThreadAttachmentOpenTarget>.toInlineVideoFetchOutcome(): ThreadInlineVideoFetchOutcome =
    when (this) {
        is ThreadApiResult.Success -> {
            val url = data.url.trim()
            if (url.isNotBlank()) {
                ThreadInlineVideoFetchOutcome.Ready(url, data.expiresAt)
            } else {
                ThreadInlineVideoFetchOutcome.Failed(200, ThreadInlineVideoFailureKind.Transient)
            }
        }
        is ThreadApiResult.Failure -> ThreadInlineVideoFetchOutcome.Failed(code, threadInlineVideoFailureKind(code))
    }

/**
 * Runs the fetch with [policy], publishing every state change through [onState].
 * [awaitRetry] waits up to the given delay before the next attempt; the caller
 * returns early from it when a realtime update arrives.
 */
internal suspend fun runThreadInlineVideoPreviewFetch(
    policy: ThreadInlineVideoRetryPolicy,
    fetch: suspend () -> ThreadInlineVideoFetchOutcome,
    awaitRetry: suspend (delayMs: Long) -> Unit,
    onState: (ThreadInlineVideoPreviewUiState) -> Unit
): ThreadInlineVideoFetchOutcome {
    var attempt = 1
    onState(ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Loading, attempt = attempt))
    while (true) {
        val outcome = fetch()
        when (outcome) {
            is ThreadInlineVideoFetchOutcome.Ready -> {
                onState(
                    ThreadInlineVideoPreviewUiState(
                        status = ThreadInlineVideoPreviewStatus.Ready,
                        url = outcome.url,
                        expiresAt = outcome.expiresAt,
                        attempt = attempt
                    )
                )
                return outcome
            }
            is ThreadInlineVideoFetchOutcome.Failed -> {
                val delayMs =
                    if (outcome.kind == ThreadInlineVideoFailureKind.Transient) {
                        policy.delayBeforeAttempt(attempt + 1)
                    } else {
                        null
                    }
                if (delayMs == null) {
                    onState(
                        ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Error, attempt = attempt)
                    )
                    return outcome
                }
                onState(
                    ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Processing, attempt = attempt)
                )
                awaitRetry(delayMs)
                attempt += 1
            }
        }
    }
}
