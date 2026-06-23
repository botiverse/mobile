package ai.slock.android.core.model

// Re-export shared identity types
typealias ServerId = ai.slock.compose.model.ServerId
typealias ChannelId = ai.slock.compose.model.ChannelId
typealias ThreadId = ai.slock.compose.model.ThreadId
typealias MessageId = ai.slock.compose.model.MessageId
typealias TaskId = ai.slock.compose.model.TaskId
typealias MemberId = ai.slock.compose.model.MemberId
typealias AttachmentId = ai.slock.compose.model.AttachmentId

// Re-export shared models that are fully compatible
typealias SessionSnapshot = ai.slock.compose.model.SessionSnapshot
typealias ServerSummary = ai.slock.compose.model.ServerSummary
typealias ServerPermissions = ai.slock.compose.model.ServerPermissions
typealias ConversationKind = ai.slock.compose.model.ConversationKind
typealias ThreadConversation = ai.slock.compose.model.ThreadConversation
typealias ConversationPermissions = ai.slock.compose.model.ConversationPermissions
typealias MemberKind = ai.slock.compose.model.MemberKind
typealias Presence = ai.slock.compose.model.Presence
typealias AgentStatus = ai.slock.compose.model.AgentStatus
typealias TaskStatus = ai.slock.compose.model.TaskStatus
typealias AvatarReference = ai.slock.compose.model.AvatarReference
typealias MemberProfile = ai.slock.compose.model.MemberProfile
typealias AgentProfile = ai.slock.compose.model.AgentProfile
typealias Message = ai.slock.compose.model.Message
typealias MessageReaction = ai.slock.compose.model.MessageReaction
typealias MessageType = ai.slock.compose.model.MessageType
typealias MessageDeliveryState = ai.slock.compose.model.MessageDeliveryState
typealias Attachment = ai.slock.compose.model.Attachment
typealias TaskSummary = ai.slock.compose.model.TaskSummary
typealias InboxItemKind = ai.slock.compose.model.InboxItemKind
typealias RealtimeEvent = ai.slock.compose.model.RealtimeEvent
typealias MessageNew = ai.slock.compose.model.MessageNew
typealias MessageUpdated = ai.slock.compose.model.MessageUpdated
typealias ReadStateUpdated = ai.slock.compose.model.ReadStateUpdated
typealias DirectMessageCreated = ai.slock.compose.model.DirectMessageCreated
typealias ThreadUpdated = ai.slock.compose.model.ThreadUpdated
typealias TaskCreated = ai.slock.compose.model.TaskCreated
typealias TaskUpdated = ai.slock.compose.model.TaskUpdated
typealias TaskDeleted = ai.slock.compose.model.TaskDeleted
typealias MemberPresenceUpdated = ai.slock.compose.model.MemberPresenceUpdated
typealias RealtimeRefreshRequired = ai.slock.compose.model.RealtimeRefreshRequired
typealias RealtimeRefreshScope = ai.slock.compose.model.RealtimeRefreshScope
typealias ServerMembershipRemoved = ai.slock.compose.model.ServerMembershipRemoved
typealias ServerDeleted = ai.slock.compose.model.ServerDeleted
typealias ServerPlanUpdated = ai.slock.compose.model.ServerPlanUpdated
typealias AgentCreated = ai.slock.compose.model.AgentCreated
typealias SyncResumeResponse = ai.slock.compose.model.SyncResumeResponse
typealias Heartbeat = ai.slock.compose.model.Heartbeat
typealias SettingsSummary = ai.slock.compose.model.SettingsSummary
typealias InboxItem = ai.slock.compose.data.InboxItem

// Android-specific ConversationSummary (extends shared with Android-specific avatar handling)
typealias ConversationSummary = ai.slock.compose.model.ConversationSummary

private const val THREAD_UNREAD_REPLY_COUNT_METADATA_KEY = "threadUnreadReplyCount"

fun Message.unreadReplyCount(): Int =
    unreadReplyCount.takeIf { it > 0 }
        ?: metadata[THREAD_UNREAD_REPLY_COUNT_METADATA_KEY]?.toIntOrNull()?.coerceAtLeast(0)
        ?: 0

fun Message.withUnreadReplyCount(count: Int): Message =
    copy(
        unreadReplyCount = count.coerceAtLeast(0),
        metadata =
            metadata +
                (
                    THREAD_UNREAD_REPLY_COUNT_METADATA_KEY to
                        count.coerceAtLeast(0).toString()
                    )
    )

// Extension functions are now in shared - just import them from ai.slock.compose.model.*
