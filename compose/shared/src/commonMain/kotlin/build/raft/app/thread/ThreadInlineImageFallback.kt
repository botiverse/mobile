package build.raft.app

import androidx.compose.runtime.compositionLocalOf

internal data class ThreadInlineImageFallbackState(
    val requestedAttachmentIds: Set<String> = emptySet(),
    val resolvedUrls: Map<String, String> = emptyMap()
) {
    fun beginRequest(attachmentId: String): Pair<ThreadInlineImageFallbackState, Boolean> {
        if (attachmentId.isBlank() || attachmentId in requestedAttachmentIds) return this to false
        return copy(requestedAttachmentIds = requestedAttachmentIds + attachmentId) to true
    }

    fun resolve(attachmentId: String, url: String): ThreadInlineImageFallbackState {
        val cleanUrl = url.trim()
        if (attachmentId.isBlank() || cleanUrl.isBlank()) return this
        return copy(resolvedUrls = resolvedUrls + (attachmentId to cleanUrl))
    }
}

internal fun shouldRequestThreadInlineImageFallbackForMissingThumbnail(
    thumbnailUrl: String?,
    fallbackUrl: String?
): Boolean = thumbnailUrl.isNullOrBlank() && fallbackUrl.isNullOrBlank()

/**
 * What a thread inline image loads. An image this device just uploaded ([localModel], see
 * `RaftSentAttachmentLocalImages`) renders from the local copy and never asks for the remote
 * inline URL; the remote thumbnail / fallback stays as the error fallback.
 *
 * One that may animate (`mayAnimate`: GIF / WebP) moves to its server thumbnail once the message
 * has one: that bounded animated WebP (slock #16159) is the only source a tile plays. The
 * full-size local copy shows a still until then, and stays the fallback (and the underlay while
 * the thumbnail loads).
 */
internal data class ThreadInlineImageSourcePlan(
    val source: String?,
    val fallbackSource: String?,
    val requestRemoteFallback: Boolean,
    /** [source] is the server thumbnail: the only source an animated image plays from. */
    val sourceIsThumbnail: Boolean = false
)

internal fun threadInlineImageSourcePlan(
    thumbnailUrl: String?,
    fallbackUrl: String?,
    localModel: String?,
    mayAnimate: Boolean = false
): ThreadInlineImageSourcePlan {
    val thumbnail = thumbnailUrl?.takeIf { it.isNotBlank() }
    val fallback = fallbackUrl?.takeIf { it.isNotBlank() }
    val local = localModel?.takeIf { it.isNotBlank() }
    if (local != null) {
        if (mayAnimate && thumbnail != null) {
            return ThreadInlineImageSourcePlan(
                source = thumbnail,
                fallbackSource = local,
                requestRemoteFallback = false,
                sourceIsThumbnail = true
            )
        }
        return ThreadInlineImageSourcePlan(
            source = local,
            fallbackSource = thumbnail ?: fallback,
            requestRemoteFallback = false
        )
    }
    return ThreadInlineImageSourcePlan(
        source = thumbnail,
        fallbackSource = fallback,
        requestRemoteFallback = shouldRequestThreadInlineImageFallbackForMissingThumbnail(thumbnail, fallback),
        sourceIsThumbnail = thumbnail != null
    )
}

internal data class ThreadInlineImageFallbackHost(
    val state: ThreadInlineImageFallbackState,
    val requestFallback: (attachmentId: String) -> Unit
)

internal val LocalThreadInlineImageFallbackHost =
    compositionLocalOf<ThreadInlineImageFallbackHost?> { null }
