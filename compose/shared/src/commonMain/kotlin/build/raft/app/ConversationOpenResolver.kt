package build.raft.app

import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId

internal enum class ConversationOpenSource {
    ActivityRow,
    Notification
}

internal data class ConversationOpenSourceMessageIdentity(
    val source: ConversationOpenSource,
    val messageId: String?,
    val channelId: String?,
    val threadChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val conversationKind: ConversationKind? = null,
    val parentConversationKind: ConversationKind? = null,
    val firstUnreadMessageId: String? = null,
    val lastMessageId: String? = null,
    val latestActivityMessageId: String? = null,
    val unreadCount: Int = 0,
    val originFallback: ThreadLaunchOriginFallback? = null
) {
    // Order IS the contract: ids that name the conversation itself (stream, channel)
    // resolve before parent/anchor ids, so a row lands on its OWN projection before any
    // parent or message-derived association can claim it (t432 family ③).
    fun lookupIds(): List<String> =
        listOfNotNull(
            threadChannelId,
            channelId,
            parentMessageId,
            parentChannelId,
            messageId,
            firstUnreadMessageId,
            lastMessageId,
            latestActivityMessageId
        ).mapNotNull { it.takeIf(String::isNotBlank) }
}

internal sealed interface ConversationOpenResolution {
    data class Resolved(val request: HomeMessageOpenRequest) : ConversationOpenResolution
    data class Pending(
        val sourceMessageIdentity: ConversationOpenSourceMessageIdentity,
        val reason: String
    ) : ConversationOpenResolution
}

internal fun resolveOpenRequest(
    serverId: String,
    sourceMessageIdentity: ConversationOpenSourceMessageIdentity,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState(),
    diagnosticsLogger: (String) -> Unit = {}
): ConversationOpenResolution {
    var hitLookupId: String? = null
    val projectionHit =
        sourceMessageIdentity
            .lookupIds()
            .firstNotNullOfOrNull { id -> conversationDirectory.resolve(id)?.also { hitLookupId = id } }
            ?.takeIf { projection -> serverId.isBlank() || projection.serverId == serverId || projection.serverId.isBlank() }
    // task #441: a Thread-attested source may only take identity from its OWN reply-thread
    // row. The lookup list falls through to parent/anchor ids when the thread's row is not
    // registered yet, and a parent CHANNEL row hit there must not become this thread's
    // identity — that is the #415 parent-claims-child misroute returning through the lookup
    // fallback instead of aliases (Quiver feedback 20d596f9: Activity reply row opened the
    // outer channel stream). Rejecting the hit routes the source to the trusted typed
    // rebuild below (Activity rows carry parentChannelKind), or Pending without one.
    val projection =
        projectionHit?.takeIf { hit ->
            sourceMessageIdentity.conversationKind != ConversationKind.Thread ||
                hit.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
        }
    // Two-tier drop (t5/#419): a projection that resolved but whose reply-thread parent shape
    // is untrustworthy (builder returned null) may only be reconstructed from a trusted typed
    // parentConversationKind on the source; otherwise it stays unresolved and must NOT fall
    // back to a channel-shaped guess (#415). A projection that never resolved is still just
    // hydrating. The distinction is the Pending reason.
    val conversation =
        projection?.toThreadLaunchConversationIdentity(conversationDirectory)
            ?: sourceMessageIdentity.toThreadLaunchConversationIdentity()
    val resolution =
        if (conversation == null) {
            ConversationOpenResolution.Pending(
                sourceMessageIdentity,
                reason =
                    when {
                        projection != null -> "thread_parent_unresolved"
                        projectionHit != null -> "thread_identity_projection_ineligible"
                        else -> "conversation_hydrating"
                    }
            )
        } else {
            ConversationOpenResolution.Resolved(
                HomeMessageOpenRequest(
                    conversationIdentity = conversation,
                    anchorMessageId = sourceMessageIdentity.resolveOpenAnchorMessageId(conversation),
                    originFallback =
                        sourceMessageIdentity.originFallback
                            ?: projection.toThreadLaunchOriginFallback()
                )
            )
        }
    diagnosticsLogger(
        "conversation_open_resolution source=${sourceMessageIdentity.source} " +
            "kind=${sourceMessageIdentity.conversationKind?.name ?: "_"} " +
            "hitVia=${hitLookupId ?: "_"} " +
            "hitSurface=${projectionHit?.surfaceKind ?: "_"} " +
            "hitEligible=${projectionHit != null && projection != null} " +
            "result=" +
            when (resolution) {
                is ConversationOpenResolution.Resolved ->
                    "resolved:${resolution.request.conversationIdentity.kind.name}"
                is ConversationOpenResolution.Pending -> "pending:${resolution.reason}"
            }
    )
    return resolution
}

internal fun InboxItem.toConversationOpenSourceMessageIdentity(): ConversationOpenSourceMessageIdentity =
    ConversationOpenSourceMessageIdentity(
        source = ConversationOpenSource.ActivityRow,
        messageId = resolveInboxAnchorMessageId()?.value?.takeIf { it.isNotBlank() },
        channelId = channelId.value.takeIf { it.isNotBlank() },
        threadChannelId = resolveInboxStreamChannelId()?.value?.takeIf { it.isNotBlank() },
        parentChannelId = parentChannelId?.value?.takeIf { it.isNotBlank() },
        parentMessageId = parentMessageId?.value?.takeIf { it.isNotBlank() },
        conversationKind =
            when (kind) {
                InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
                InboxItemKind.Thread -> ConversationKind.Thread
                InboxItemKind.MentionAction -> channelKind ?: ConversationKind.Channel
                InboxItemKind.System -> ConversationKind.System
                InboxItemKind.Channel -> parentChannelKind ?: ConversationKind.Channel
            },
        parentConversationKind = parentChannelKind,
        firstUnreadMessageId = firstUnreadMessageId?.value?.takeIf { it.isNotBlank() },
        lastMessageId = lastMessageId?.value?.takeIf { it.isNotBlank() },
        latestActivityMessageId = latestActivityMessageId?.value?.takeIf { it.isNotBlank() },
        unreadCount = unreadCount,
        originFallback = toActivityOpenThreadLaunchOriginFallback()
    )

internal fun AppShellNotificationThreadLaunchRequest.toConversationOpenSourceMessageIdentity(): ConversationOpenSourceMessageIdentity =
    ConversationOpenSourceMessageIdentity(
        source = ConversationOpenSource.Notification,
        messageId = messageId.takeIf { it.isNotBlank() },
        channelId = channelId?.takeIf { it.isNotBlank() } ?: threadId.takeIf { it.isNotBlank() },
        threadChannelId = threadId.takeIf { it.isNotBlank() },
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() },
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }
    )

private fun ConversationOpenSourceMessageIdentity.toThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity? {
    val kind = conversationKind ?: return null
    val channel = channelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return null
    // A reply thread whose parent kind is unknown must not be reconstructed with a guessed
    // parent shape — that is exactly the #415 misroute. Only a trusted typed
    // parentConversationKind (server-enriched, e.g. Activity InboxItem.parentChannelKind) may
    // rebuild the thread; a null parent kind stays unresolved for the caller to handle.
    if (kind == ConversationKind.Thread && parentConversationKind == null) return null
    return buildThreadLaunchConversationIdentity(
        kind = kind,
        channelId = channel,
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        parentSurfaceKind = parentConversationKind.threadParentSurfaceKindOrNull(),
        parentChannelKind = parentConversationKind.threadChannelKindWireValue()
    )
}

/**
 * Anchor strategies by open source. The third user-visible strategy — the foreground
 * banner's "anchor on the tapped message itself" — lives with the banner
 * (`foregroundBannerAnchorMessageId`, SlockForegroundBannerHost.kt) because that request is
 * built outside the resolver; keep the three in sync when adding a source (t2b/#419).
 */
private fun ConversationOpenSourceMessageIdentity.resolveOpenAnchorMessageId(
    conversation: ThreadLaunchConversationIdentity
): String? =
    when (source) {
        ConversationOpenSource.ActivityRow -> messageId
        ConversationOpenSource.Notification ->
            if (
                conversation.kind != ConversationKind.Thread &&
                !parentChannelId.isNullOrBlank() &&
                !parentMessageId.isNullOrBlank()
            ) {
                parentMessageId
            } else {
                messageId
            }
    }?.takeIf { it.isNotBlank() }

private fun InboxItem.toActivityOpenThreadLaunchOriginFallback(): ThreadLaunchOriginFallback? {
    if (kind != InboxItemKind.Thread) return null
    val parentId = parentMessageId?.value?.takeIf { it.isNotBlank() } ?: return null
    return ThreadLaunchOriginFallback(
        parentChannelId = parentChannelId?.value?.takeIf { it.isNotBlank() },
        parentMessageId = parentId,
        preview = parentMessagePreview?.takeIf { it.isNotBlank() },
        senderId = parentMessageSenderId?.takeIf { it.isNotBlank() },
        senderKind = parentMessageSenderKind,
        expected = true
    )
}
