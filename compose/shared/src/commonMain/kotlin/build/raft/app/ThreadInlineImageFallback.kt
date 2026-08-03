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

internal data class ThreadInlineImageFallbackHost(
    val state: ThreadInlineImageFallbackState,
    val requestFallback: (attachmentId: String) -> Unit
)

internal val LocalThreadInlineImageFallbackHost =
    compositionLocalOf<ThreadInlineImageFallbackHost?> { null }
