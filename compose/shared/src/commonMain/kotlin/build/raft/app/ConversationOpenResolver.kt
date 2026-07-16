package build.raft.app

import build.raft.app.data.AuthPendingOpenRequest
import build.raft.app.data.InboxItem
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskSummary

internal enum class ConversationOpenSource {
    ActivityRow,
    Notification,
    ForegroundBanner,
    Task,
    Markdown,
    ParentConversation,
    AuthResume
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
    // task #441 (superseding the old ordered-fallback contract): the lookup resolves ONLY
    // ids that name the conversation itself (thread stream, channel). The parent/anchor
    // fallback tail (parentMessageId, parentChannelId, message ids) is deleted — a parent
    // or association row can no longer be hit into becoming this source's identity at all
    // (artin: "兜底命中那些都删掉吧"). Parent ids still feed the trusted typed rebuild and
    // anchors; they just never LOOK UP a target.
    fun lookupIds(): List<String> =
        listOfNotNull(
            threadChannelId,
            channelId
        ).mapNotNull { it.takeIf(String::isNotBlank) }

    // Thread-shaped means the source demonstrably describes a reply thread: wire-attested
    // kind, or a parent message id (notification requests carry no typed kind but a thread
    // notification always names its parent message). Used to refuse non-reply-thread
    // projection hits — a thread request's channelId can name the PARENT channel on some
    // wire shapes, and that self-lookup hit must not become the thread's identity either.
    fun isThreadShaped(): Boolean =
        conversationKind == ConversationKind.Thread || !parentMessageId.isNullOrBlank()
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
    // task #441: a thread-shaped source (any origin — Activity row, notification, banner)
    // may only take identity from its OWN reply-thread row. Even with the lookup fallback
    // tail deleted, a thread request's channelId can name the PARENT channel on some wire
    // shapes — a non-reply-thread hit must not become the thread's identity (the #415
    // parent-claims-child misroute; Quiver feedback 20d596f9: Activity reply row opened the
    // outer channel stream). Rejecting the hit routes the source to the trusted typed
    // rebuild below (Activity rows carry parentChannelKind), or an honest Pending — never
    // a guessed parent surface, regardless of source.
    val directProjection =
        projectionHit?.takeIf { hit ->
            !sourceMessageIdentity.isThreadShaped() ||
                hit.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
        }
    val localReplyResolution =
        if (directProjection == null) {
            conversationDirectory.resolveLocalReplyProjection(
                serverId = serverId,
                sourceMessageIdentity = sourceMessageIdentity
            )
        } else {
            LocalReplyProjectionResolution()
        }
    val projection = directProjection ?: localReplyResolution.projection
    // Two-tier drop (t5/#419): a projection that resolved but whose reply-thread parent shape
    // is untrustworthy (builder returned null) may only be reconstructed from a trusted typed
    // parentConversationKind on the source; otherwise it stays unresolved and must NOT fall
    // back to a channel-shaped guess (#415). A projection that never resolved is still just
    // hydrating. The distinction is the Pending reason.
    val authoritativeParentKind =
        sourceMessageIdentity.parentConversationKind
            ?: sourceMessageIdentity.parentChannelId
                ?.let(conversationDirectory::resolve)
                ?.takeUnless { it.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread }
                ?.conversationKind
    val conversation =
        projection?.toThreadLaunchConversationIdentity(conversationDirectory)
            ?: if (localReplyResolution.conflict) {
                null
            } else {
                sourceMessageIdentity.toThreadLaunchConversationIdentity(authoritativeParentKind)
            }
    val resolution =
        if (conversation == null) {
            ConversationOpenResolution.Pending(
                sourceMessageIdentity,
                reason =
                    when {
                        projection != null -> "thread_parent_unresolved"
                        localReplyResolution.conflict -> "thread_projection_conflict"
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
            "hitEligible=${directProjection != null} " +
            "localReply=${localReplyResolution.projection?.canonicalChannelId ?: "_"} " +
            "localReplyConflict=${localReplyResolution.conflict} " +
            "result=" +
            when (resolution) {
                is ConversationOpenResolution.Resolved ->
                    "resolved:${resolution.request.conversationIdentity.kind.name}"
                is ConversationOpenResolution.Pending -> "pending:${resolution.reason}"
            }
    )
    return resolution
}

/**
 * Joint-channel notification payloads can carry the peer/source server's channel ids while
 * still being qualified with the active local server id. Resolve that wire identity onto the
 * active server only when the directory proves the complete reply relation: one local reply
 * projection owns the same parent message and its local parent is the projection addressed by
 * the source parent id. This is deliberately narrower than adding parent ids back to lookupIds:
 * a parent row can qualify a reply projection, but can never become the opened conversation.
 */
private data class LocalReplyProjectionResolution(
    val projection: SharedConversationDirectoryProjection? = null,
    val conflict: Boolean = false
)

private fun SharedConversationDirectoryState.resolveLocalReplyProjection(
    serverId: String,
    sourceMessageIdentity: ConversationOpenSourceMessageIdentity
): LocalReplyProjectionResolution {
    if (!sourceMessageIdentity.isThreadShaped()) return LocalReplyProjectionResolution()
    val parentMessageId =
        sourceMessageIdentity.parentMessageId?.takeIf { it.isNotBlank() }
            ?: return LocalReplyProjectionResolution()
    val localReply =
        resolve(parentMessageId)
            ?.takeIf { projection ->
                projection.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                    projection.parentMessageId == parentMessageId &&
                    (serverId.isBlank() || projection.serverId == serverId || projection.serverId.isBlank())
            }
            ?: return LocalReplyProjectionResolution()
    val sourceParentId =
        sourceMessageIdentity.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return LocalReplyProjectionResolution()
    val localParentId =
        localReply.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return LocalReplyProjectionResolution()
    val sourceParent =
        resolve(sourceParentId)
            ?.takeUnless { it.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread }
            ?: return LocalReplyProjectionResolution()
    val localParent =
        resolve(localParentId)
            ?.takeUnless { it.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread }
            ?: return LocalReplyProjectionResolution()
    if (sourceParent.canonicalChannelId != localParent.canonicalChannelId) {
        return LocalReplyProjectionResolution(conflict = true)
    }
    if (
        sourceParent.serverId.isNotBlank() &&
        serverId.isNotBlank() &&
        sourceParent.serverId != serverId
    ) {
        return LocalReplyProjectionResolution(conflict = true)
    }
    val typedParentKind = sourceMessageIdentity.parentConversationKind
    if (typedParentKind != null && typedParentKind != localParent.conversationKind) {
        return LocalReplyProjectionResolution(conflict = true)
    }
    return LocalReplyProjectionResolution(projection = localReply)
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
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() },
        conversationKind = conversationKind
    )

internal fun TaskSummary.toConversationOpenSourceMessageIdentity(
    parentConversationKind: ConversationKind? = null
): ConversationOpenSourceMessageIdentity {
    val parentId =
        parentMessageId?.value?.takeIf { it.isNotBlank() }
            ?: messageId?.value?.takeIf { it.isNotBlank() }
    val streamId = threadId?.value?.takeIf { it.isNotBlank() }
    val parentChannel = channelId?.value?.takeIf { it.isNotBlank() }
    val focusedId = messageId?.value?.takeIf { it.isNotBlank() && it != parentId }
    return ConversationOpenSourceMessageIdentity(
        source = ConversationOpenSource.Task,
        messageId = focusedId,
        channelId = streamId ?: parentChannel,
        threadChannelId = streamId,
        parentChannelId = parentChannel,
        parentMessageId = parentId,
        conversationKind = ConversationKind.Thread,
        parentConversationKind = parentConversationKind
    )
}

internal fun TaskSummary.resolveTaskThreadOpenRequest(
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState,
    parentConversationKind: ConversationKind? = null
): HomeMessageOpenRequest? =
    (resolveOpenRequest(
        serverId,
        toConversationOpenSourceMessageIdentity(parentConversationKind),
        conversationDirectory
    )
        as? ConversationOpenResolution.Resolved)
        ?.request

internal fun AuthPendingOpenRequest.Thread.toConversationOpenSourceMessageIdentity(): ConversationOpenSourceMessageIdentity {
    val kind =
        if (surfaceKind == KuiklyThreadSurfaceKind.Channel) {
            parseConversationKindOrNull(channelKind)
        } else {
            parseConversationKindOrNull(surfaceKind)
        }
    val streamId = streamChannelId?.takeIf { it.isNotBlank() } ?: sourceThreadId?.takeIf { it.isNotBlank() }
    return ConversationOpenSourceMessageIdentity(
        source = ConversationOpenSource.AuthResume,
        messageId = focusedMessageId?.takeIf { it.isNotBlank() },
        channelId = streamId ?: parentChannelId?.takeIf { it.isNotBlank() },
        threadChannelId = streamId.takeIf { kind == ConversationKind.Thread },
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() },
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() },
        conversationKind = kind,
        parentConversationKind =
            typedReplyParentConversationKindOrNull(parentSurfaceKind, parentChannelKind)
    )
}

private fun ConversationOpenSourceMessageIdentity.toThreadLaunchConversationIdentity(
    authoritativeParentKind: ConversationKind?
): ThreadLaunchConversationIdentity? {
    val kind = conversationKind ?: return null
    val parentKind = parentConversationKind ?: authoritativeParentKind
    if (kind == ConversationKind.Thread) {
        val parentChannel = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return null
        val parentMessage = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId) ?: return null
        val typedParentKind = parentKind ?: return null
        return buildThreadLaunchConversationIdentity(
            kind = ConversationKind.Thread,
            channelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: parentChannel,
            threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
            parentChannelId = parentChannel,
            parentMessageId = parentMessage,
            parentSurfaceKind = typedParentKind.threadParentSurfaceKindOrNull(),
            parentChannelKind = typedParentKind.threadChannelKindWireValue()
        )
    }
    val channel = channelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return null
    return buildThreadLaunchConversationIdentity(
        kind = kind,
        channelId = channel,
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        parentSurfaceKind = parentKind.threadParentSurfaceKindOrNull(),
        parentChannelKind = parentKind.threadChannelKindWireValue()
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
        ConversationOpenSource.ForegroundBanner,
        ConversationOpenSource.Task,
        ConversationOpenSource.Markdown,
        ConversationOpenSource.ParentConversation,
        ConversationOpenSource.AuthResume -> messageId
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
