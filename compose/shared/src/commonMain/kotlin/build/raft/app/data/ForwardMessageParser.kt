package build.raft.app.data

import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
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
    val gravatarHash: String? = null,
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
        avatarLabel = avatarLabel?.takeIf { it.isNotBlank() } ?: resolvedAuthorName.take(1).uppercase(),
        avatarWireValue = avatarWireValue?.takeIf { it.isNotBlank() } ?: avatarUrl?.takeIf { it.isNotBlank() },
        gravatarHash = gravatarHash?.takeIf { it.isNotBlank() },
        timestampLabel = timestampLabel?.takeIf { it.isNotBlank() } ?: createdAt.orEmpty(),
        body = body?.takeIf { it.isNotBlank() } ?: content.orEmpty(),
        attachmentCount = attachmentCount?.takeIf { it > 0 } ?: attachments.size
    )
}
