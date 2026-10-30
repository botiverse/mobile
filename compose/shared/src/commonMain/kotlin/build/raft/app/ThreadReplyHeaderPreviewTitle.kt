package build.raft.app

import build.raft.app.markdown.markdownToPreviewText

/**
 * task #1143: a reply thread's header reads `@author: one-line root preview` instead of the
 * generic 「消息列」 once the root message is loaded. Returns null when the root is not loaded
 * (or carries neither text nor attachments) so the caller keeps the generic title.
 *
 * Only a loaded [RaftThreadMessageItem] qualifies: the launch fallback preview has no author
 * display name, and an id-shaped author would render as `@<uuid>`.
 */
internal fun replyThreadHeaderPreviewTitle(
    origin: RaftThreadMessageItem?,
    imageLabel: String,
    fileLabel: String,
    /** The localized "External user", shown for an External author the wire did not name. */
    externalFallbackName: String = ""
): String? {
    if (origin == null) return null
    val author =
        threadHeaderSafeTitleOrNull(
            origin.externalAuthor
                ?.let { externalAuthorShownName(it, it.displayName, externalFallbackName) }
                ?.takeIf { it.isNotBlank() }
                ?: origin.authorName.takeIf { it.isNotBlank() }
                ?: origin.authorHandle,
            setOf(origin.authorId)
        ) ?: return null
    val body =
        markdownToPreviewText(origin.body).takeIf { it.isNotBlank() }
            ?: origin.attachments.firstOrNull()?.let { attachment ->
                if (attachment.mimeType.startsWith("image/", ignoreCase = true)) imageLabel else fileLabel
            }
            ?: return null
    return "@$author: $body"
}

/** task #1143: the reply count rides the subtitle line so it stays visible while scrolling. */
internal fun replyThreadHeaderSubtitle(subtitle: String?, replyCountLabel: String?): String? =
    listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, replyCountLabel?.takeIf { it.isNotBlank() })
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

/**
 * The route's render payload has its origin cleared (`withoutThreadTimelineContent`) and the
 * timeline origin has its reply metadata stripped, so the count lives only in the metadata map.
 */
internal fun threadHeaderOriginReplyCount(
    timelineSlice: ThreadTimelineSlice,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>
): Int =
    timelineSlice.originMessage
        ?.let { replyMetadataByParentId[it.id]?.replyCount }
        ?.coerceAtLeast(0)
        ?: 0
