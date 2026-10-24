package build.raft.app.data

import build.raft.app.toRaftAvatarLabel
import build.raft.app.model.ForwardMessageAttachment
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.ForwardMessageSource
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
    val resolvedAuthorName = author?.uniqueName?.takeIf { it.isNotBlank() }
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
    return ForwardMessageSource(label = label, thread = target.type == "thread")
}
