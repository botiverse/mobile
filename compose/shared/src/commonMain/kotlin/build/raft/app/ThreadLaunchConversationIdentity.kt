package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId

internal data class ThreadLaunchConversationIdentity(
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val channelType: String? = null,
    val thread: ThreadConversation? = null,
    val kind: ConversationKind,
    val name: String,
    val subtitle: String? = null,
    val joined: Boolean = true,
    val archived: Boolean = false,
    val jointChannelId: String? = null,
    val jointRole: String? = null,
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
        joined = joined,
        archived = archived,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
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
        joined = joined,
        archived = archived,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
        permissions = permissions
    )

internal fun ThreadLaunchConversationIdentity.resolveThreadRouteIdentity(): ThreadRouteIdentity =
    toConversationSummary().resolveThreadRouteIdentity()
