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
 * The reply count a reply thread's header shows (web ThreadPanelHeader): every reply, the ones
 * behind the history window too, so it agrees with the paywall's count -- the larger of the loaded
 * replies plus [hiddenHistoryCount] and the origin's reply summary. Null while it is unknown (none
 * known yet and the replies are still loading or failed to load): that is not "0 replies".
 *
 * The route's render payload has its origin cleared (`withoutThreadTimelineContent`) and the
 * timeline origin has its reply metadata stripped, so the summary lives only in the metadata map.
 */
internal fun threadHeaderReplyCount(
    timelineSlice: ThreadTimelineSlice,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    hiddenHistoryCount: Int = 0
): Int? {
    val origin = timelineSlice.originMessage
    val summaryCount = origin?.let { replyMetadataByParentId[it.id]?.replyCount }?.coerceAtLeast(0) ?: 0
    val loadedCount = timelineSlice.messages.count { it.id != origin?.id } + hiddenHistoryCount.coerceAtLeast(0)
    val replyCount = maxOf(loadedCount, summaryCount)
    val loading = timelineSlice.status == "loading" || timelineSlice.originStatus == "loading"
    val failed = timelineSlice.status == "error" || timelineSlice.mainLoadFailed
    return replyCount.takeUnless { it == 0 && (loading || failed) }
}
