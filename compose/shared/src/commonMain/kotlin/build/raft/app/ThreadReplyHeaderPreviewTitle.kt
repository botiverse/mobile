package build.raft.app

/**
 * task #1143: a reply thread's header reads `@author: one-line root preview` instead of the
 * generic 「消息列」 once the root message is loaded. Returns null when the root is not loaded
 * (or carries neither text nor attachments) so the caller keeps the generic title.
 *
 * Only a loaded [KuiklyThreadMessageItem] qualifies: the launch fallback preview has no author
 * display name, and an id-shaped author would render as `@<uuid>`.
 */
internal fun replyThreadHeaderPreviewTitle(
    origin: KuiklyThreadMessageItem?,
    imageLabel: String,
    fileLabel: String
): String? {
    if (origin == null) return null
    val author =
        threadHeaderSafeTitleOrNull(
            origin.externalAuthor?.displayName?.takeIf { it.isNotBlank() }
                ?: origin.authorName.takeIf { it.isNotBlank() }
                ?: origin.authorHandle,
            setOf(origin.authorId)
        ) ?: return null
    val body =
        origin.body.toReplyThreadHeaderPlainPreview().takeIf { it.isNotBlank() }
            ?: origin.attachments.firstOrNull()?.let { attachment ->
                if (attachment.mimeType.startsWith("image/", ignoreCase = true)) imageLabel else fileLabel
            }
            ?: return null
    return "@$author: $body"
}

private val ReplyThreadHeaderFencedCode = Regex("```[^\\n]*\\n?")
private val ReplyThreadHeaderMarkdownLink = Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)")
private val ReplyThreadHeaderLinePrefix = Regex("(?m)^\\s*(?:#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+[.)]\\s+)")
private val ReplyThreadHeaderInlineMarkers = Regex("\\*\\*|__|~~|`")
private val ReplyThreadHeaderWhitespace = Regex("\\s+")

// The header is a single ellipsized line, so length is bounded by layout; this only has to
// keep markdown syntax from reading as content.
internal fun String.toReplyThreadHeaderPlainPreview(): String =
    replace(ReplyThreadHeaderFencedCode, "")
        .replace(ReplyThreadHeaderMarkdownLink, "$1")
        .replace(ReplyThreadHeaderLinePrefix, "")
        .replace(ReplyThreadHeaderInlineMarkers, "")
        .replace(ReplyThreadHeaderWhitespace, " ")
        .trim()

/** task #1143: the reply count rides the subtitle line so it stays visible while scrolling. */
internal fun replyThreadHeaderSubtitle(subtitle: String?, replyCountLabel: String?): String? =
    listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, replyCountLabel?.takeIf { it.isNotBlank() })
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
