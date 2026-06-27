package ai.slock.compose

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadId

internal data class ThreadLaunchConversationIdentity(
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val channelType: String? = null,
    val thread: ThreadConversation? = null,
    val kind: ConversationKind,
    val name: String,
    val subtitle: String? = null,
    val directMessagePeerType: String? = null,
    val presenceMemberId: MemberId? = null,
    val joined: Boolean = true,
    val archived: Boolean = false,
    val memberCount: Int? = null,
    val jointChannelId: String? = null,
    val jointRole: String? = null,
    val jointPeerServerId: ServerId? = null,
    val jointPeerServerName: String? = null,
    val jointPeerServerSlug: String? = null,
    val jointPeerStatus: String? = null,
    val permissions: ConversationPermissions = ConversationPermissions()
)

internal fun ConversationSummary.toThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity =
    ThreadLaunchConversationIdentity(
        threadId = threadId,
        channelId = channelId,
        channelType = channelType,
        thread = thread,
        kind = kind,
        name = name,
        subtitle = existingThreadChannelHeaderSubtitle(subtitle),
        directMessagePeerType = directMessagePeerType,
        presenceMemberId = presenceMemberId,
        joined = joined,
        archived = archived,
        memberCount = memberCount,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
        jointPeerServerId = jointPeerServerId,
        jointPeerServerName = jointPeerServerName,
        jointPeerServerSlug = jointPeerServerSlug,
        jointPeerStatus = jointPeerStatus,
        permissions = permissions
    )

internal fun ThreadLaunchConversationIdentity.toConversationSummary(): ConversationSummary =
    ConversationSummary(
        threadId = threadId,
        channelId = channelId,
        channelType = channelType,
        thread = thread,
        kind = kind,
        name = name,
        subtitle = subtitle,
        directMessagePeerType = directMessagePeerType,
        presenceMemberId = presenceMemberId,
        joined = joined,
        archived = archived,
        memberCount = memberCount,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
        jointPeerServerId = jointPeerServerId,
        jointPeerServerName = jointPeerServerName,
        jointPeerServerSlug = jointPeerServerSlug,
        jointPeerStatus = jointPeerStatus,
        permissions = permissions
    )

internal fun ThreadLaunchConversationIdentity.resolveThreadRouteIdentity(): ThreadRouteIdentity =
    toConversationSummary().resolveThreadRouteIdentity()
