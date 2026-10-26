package build.raft.app.data

import build.raft.app.toRaftAvatarLabel
import build.raft.app.model.ForwardMessageAttachment
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.ForwardMessageSource
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlinx.serialization.Serializable

@Serializable
internal data class WireForwardMessageBundle(
    val id: String? = null,
    val title: String = "",
    val sourceTitle: String = "",
    val totalCount: Int? = null,
    val messages: List<WireForwardMessageItem> = emptyList(),
    val items: List<WireForwardMessageItem> = emptyList()
)

@Serializable
internal data class WireForwardMessageItem(
    val id: String? = null,
    val messageId: String? = null,
    val authorId: String? = null,
    val senderId: String? = null,
    val authorName: String? = null,
    val senderName: String? = null,
    val authorKind: String? = null,
    val senderType: String? = null,
    val avatarLabel: String? = null,
    val avatarWireValue: String? = null,
    val avatarUrl: String? = null,
    val timestampLabel: String? = null,
    val createdAt: String? = null,
    val body: String? = null,
    val content: String? = null,
    val attachmentCount: Int? = null,
    val attachments: List<WireForwardMessageAttachment> = emptyList()
)

@Serializable
internal data class WireForwardMessageAttachment(
    val id: String = ""
)

@Serializable
internal data class WireForwardedBundleItem(
    val index: Int? = null,
    val sourceMessageSeq: Long? = null,
    val sourceIsThreadParent: Boolean? = null,
    val sourceTargetId: String? = null,
    val sourceThreadId: String? = null,
    val parentChannelId: String? = null,
    val sourceMessageId: String? = null,
    val sourceAuthorSnapshot: WireForwardedBundleAuthor? = null,
    val sourceCreatedAt: String? = null,
    val sourceTargetSnapshot: WireForwardedBundleTarget? = null,
    val contentSnapshot: String = "",
    val attachmentSnapshots: List<WireForwardedBundleAttachment> = emptyList(),
    val attachmentPolicy: String? = null,
    val provenanceState: String? = null
)

@Serializable
internal data class WireForwardedBundleAuthor(
    val type: String? = null,
    val id: String? = null,
    val name: String? = null,
    val uniqueName: String? = null
)

@Serializable
internal data class WireForwardedBundleTarget(
    val id: String? = null,
    val type: String? = null,
    val label: String = "",
    val labelVisibility: String? = null
)

@Serializable
internal data class WireForwardedBundleAttachment(
    val id: String? = null,
    val filename: String = "",
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null
)

internal fun WireForwardMessageBundle?.toForwardMessageBundle(): ForwardMessageBundle? {
    val source = this ?: return null
    val forwardedMessages = (source.messages + source.items)
        .mapNotNull { it.toForwardMessageItem() }
        .distinctBy { it.id }
    if (forwardedMessages.isEmpty()) return null
    return ForwardMessageBundle(
        id = source.id?.takeIf { it.isNotBlank() },
        title = source.title.takeIf { it.isNotBlank() } ?: "Chat history",
        sourceTitle = source.sourceTitle,
        totalCount = source.totalCount?.takeIf { it > 0 } ?: forwardedMessages.size,
        messages = forwardedMessages
    )
}

internal fun WireActionCardMetadata?.toForwardMessageBundle(): ForwardMessageBundle? {
    val source = this ?: return null
    if (source.kind != "forwarded-bundle") return null
    val kept = source.forwardedItems
        .orderedForDisplay()
        .mapIndexedNotNull { fallbackIndex, item -> item.toForwardMessageItem(fallbackIndex)?.let { item to it } }
        .distinctBy { (_, message) -> message.id }
    val forwardedMessages = kept.map { (_, message) -> message }
    if (forwardedMessages.isEmpty()) return null
    val sourceTitle = source.forwardedItems
        .firstOrNull()
        ?.sourceTargetSnapshot
        ?.label
        ?.takeIf { it.isNotBlank() }
        .orEmpty()
    return ForwardMessageBundle(
        title = "Forwarded",
        sourceTitle = sourceTitle,
        totalCount = forwardedMessages.size,
        messages = forwardedMessages,
        // The source of the first item the card shows (web: `sourceLabel(firstItem)`).
        visibleSource = kept.first().first.visibleForwardSource()
    )
}

private fun WireForwardMessageItem.toForwardMessageItem(): ForwardMessageItem? {
    val resolvedId = messageId?.takeIf { it.isNotBlank() }
        ?: id?.takeIf { it.isNotBlank() }
        ?: return null
    val resolvedAuthorId = authorId?.takeIf { it.isNotBlank() }
        ?: senderId?.takeIf { it.isNotBlank() }
        ?: "unknown"
    val resolvedAuthorName = authorName?.takeIf { it.isNotBlank() }
        ?: senderName?.takeIf { it.isNotBlank() }
        ?: resolvedAuthorId
    return ForwardMessageItem(
        id = resolvedId,
        authorId = resolvedAuthorId,
        authorName = resolvedAuthorName,
        authorKind = authorKind?.takeIf { it.isNotBlank() }
            ?: senderType?.takeIf { it.isNotBlank() }
            ?: "",
        avatarLabel = avatarLabel?.takeIf { it.isNotBlank() } ?: resolvedAuthorName.toRaftAvatarLabel(),
        avatarWireValue = avatarWireValue?.takeIf { it.isNotBlank() } ?: avatarUrl?.takeIf { it.isNotBlank() },
        timestampLabel = timestampLabel?.takeIf { it.isNotBlank() } ?: createdAt.orEmpty(),
        body = body?.takeIf { it.isNotBlank() } ?: content.orEmpty(),
        attachmentCount = attachmentCount?.takeIf { it > 0 } ?: attachments.size
    )
}

private fun WireForwardedBundleItem.toForwardMessageItem(fallbackIndex: Int): ForwardMessageItem? {
    val resolvedId = sourceMessageId?.takeIf { it.isNotBlank() }
        ?: index?.let { "forwarded-$it" }
        ?: "forwarded-$fallbackIndex"
    val author = sourceAuthorSnapshot
    val resolvedAuthorId = author?.id?.takeIf { it.isNotBlank() } ?: "unknown"
    // web `authorLabel`: "@uniqueName", else the display name.
    val resolvedAuthorName = author?.uniqueName?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("@")) it else "@$it" }
        ?: author?.name?.takeIf { it.isNotBlank() }
        ?: resolvedAuthorId
    return ForwardMessageItem(
        id = resolvedId,
        authorId = resolvedAuthorId,
        authorName = resolvedAuthorName,
        authorKind = author?.type?.takeIf { it.isNotBlank() }.orEmpty(),
        avatarLabel = resolvedAuthorName.removePrefix("@").toRaftAvatarLabel(),
        timestampLabel = sourceCreatedAt.orEmpty(),
        body = contentSnapshot,
        attachmentCount = attachmentSnapshots.size,
        attachments =
            attachmentSnapshots.map {
                ForwardMessageAttachment(
                    filename = it.filename,
                    mimeType = it.mimeType,
                    id = it.id?.takeIf(String::isNotBlank),
                    width = it.width?.takeIf { width -> width > 0 },
                    height = it.height?.takeIf { height -> height > 0 }
                )
            },
        attachmentsProjected = attachmentPolicy == "projected"
    )
}

/**
 * web ForwardedBundleCard `sourceLabel`: a source is named only when the original is available
 * and its target is a public conversation that is not a direct message.
 */
internal fun WireForwardedBundleItem.visibleForwardSource(): ForwardMessageSource? {
    if (provenanceState != "available") return null
    val target = sourceTargetSnapshot ?: return null
    val label = target.label.takeIf { it.isNotBlank() } ?: return null
    if (target.type == "dm" || target.labelVisibility != "public") return null
    val thread = target.type == "thread"
    // web `canOpenSourceLabel`: a thread opens through its parent channel (the link resolver
    // opens a reply's thread), anything else through its own conversation.
    val messageId = sourceMessageId?.takeIf { it.isNotBlank() }
    val channelId =
        if (thread) {
            parentChannelId?.takeIf { it.isNotBlank() && !sourceThreadId.isNullOrBlank() }
        } else {
            sourceTargetId?.takeIf { it.isNotBlank() }
        }
    return ForwardMessageSource(
        label = label,
        thread = thread,
        channelId = channelId?.takeIf { messageId != null },
        messageId = messageId?.takeIf { channelId != null }
    )
}

/**
 * web `orderForwardedBundleItemsForDisplay`: by the visible time, then the source seq, index and
 * message id; a thread bundle with parent markers puts its parent first.
 */
internal fun List<WireForwardedBundleItem>.orderedForDisplay(): List<WireForwardedBundleItem> {
    val byVisibleOrder =
        Comparator<IndexedValue<WireForwardedBundleItem>> { a, b ->
            val aTime = parseRaftTimestampEpochMillisOrNull(a.value.sourceCreatedAt)
            val bTime = parseRaftTimestampEpochMillisOrNull(b.value.sourceCreatedAt)
            when {
                aTime != null && bTime != null && aTime != bTime -> aTime.compareTo(bTime)
                aTime != null && bTime == null -> -1
                aTime == null && bTime != null -> 1
                else -> {
                    val aSeq = a.value.sourceMessageSeq
                    val bSeq = b.value.sourceMessageSeq
                    val aIndex = a.value.index
                    val bIndex = b.value.index
                    val aId = a.value.sourceMessageId?.takeIf { it.isNotEmpty() }
                    val bId = b.value.sourceMessageId?.takeIf { it.isNotEmpty() }
                    when {
                        aSeq != null && bSeq != null && aSeq != bSeq -> aSeq.compareTo(bSeq)
                        aIndex != null && bIndex != null && aIndex != bIndex -> aIndex.compareTo(bIndex)
                        aId != null && bId != null && aId != bId -> aId.compareTo(bId)
                        else -> a.index.compareTo(b.index)
                    }
                }
            }
        }
    val positioned = withIndex().toList()
    val threadBundle = any { it.sourceTargetSnapshot?.type == "thread" }
    if (!threadBundle) return positioned.sortedWith(byVisibleOrder).map { it.value }
    val hasParentMarker = any { it.sourceIsThreadParent == true }
    val hasCompleteMarkers = all { it.sourceIsThreadParent != null }
    if (!hasParentMarker && !hasCompleteMarkers) return this
    val parent = positioned.firstOrNull { it.value.sourceIsThreadParent == true }
    val replies = positioned.filter { it.value.sourceIsThreadParent != true }.sortedWith(byVisibleOrder).map { it.value }
    return if (parent != null) listOf(parent.value) + replies else replies
}
