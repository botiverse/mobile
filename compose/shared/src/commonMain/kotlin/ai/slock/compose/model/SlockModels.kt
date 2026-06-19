package ai.slock.compose.model

import kotlinx.serialization.Serializable

// Session and Server Models
@Serializable
data class SessionSnapshot(
    val currentServerId: ServerId,
    val currentMember: MemberProfile,
    val availableServers: List<ServerSummary>
)

@Serializable
data class ServerSummary(
    val id: ServerId,
    val name: String,
    val slug: String? = null,
    val role: String? = null,
    val plan: String? = null,
    val onboardingAgentId: String? = null,
    val description: String? = null,
    val unreadCount: Int = 0,
    val permissions: ServerPermissions = ServerPermissions()
)

@Serializable
data class ServerPermissions(
    val canCreateChannel: Boolean = true,
    val canInviteMember: Boolean = true,
    val canManageSettings: Boolean = false
)

// Conversation Models
@Serializable
enum class ConversationKind {
    Channel,
    Joint,
    Thread,
    DirectMessage,
    Inbox,
    System
}

@Serializable
data class ThreadConversation(
    val parentChannelId: ChannelId,
    val parentMessageId: MessageId,
    val streamChannelId: ChannelId? = null
)

@Serializable
data class ConversationSummary(
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val channelType: String? = null,
    val thread: ThreadConversation? = null,
    val kind: ConversationKind,
    val name: String,
    val subtitle: String? = null,
    val directMessagePeerType: String? = null,
    val avatarUrl: String? = null,
    val avatar: AvatarReference? = null,
    val unreadCount: Int = 0,
    val unreadCountKnown: Boolean = false,
    val mentionCount: Int = 0,
    val mentionCountKnown: Boolean = false,
    val lastMessageId: MessageId? = null,
    val firstUnreadMessageId: MessageId? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null,
    val originMessagePreview: String? = null,
    val originMessageSenderId: String? = null,
    val originMessageSenderKind: MemberKind? = null,
    val originMessageExpected: Boolean = false,
    val presence: Presence? = null,
    val agentStatus: AgentStatus? = null,
    val presenceMemberId: MemberId? = null,
    val hasDraft: Boolean = false,
    val hasDraftKnown: Boolean = false,
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

@Serializable
data class ConversationPermissions(
    val canRead: Boolean = true,
    val canSend: Boolean = true,
    val canAttach: Boolean = true,
    val canCreateTask: Boolean = true
)

// Member Models
@Serializable
enum class MemberKind {
    Human,
    Agent,
    System
}

@Serializable
enum class Presence {
    Active,
    Away,
    Offline
}

@Serializable
enum class AgentStatus {
    Online,
    Working,
    Thinking,
    Idle,
    Disconnected,
    Offline,
    Error;

    val label: String
        get() = when (this) {
            Online -> "Online"
            Working -> "Working"
            Thinking -> "Thinking"
            Idle -> "Idle"
            Disconnected -> "Disconnected"
            Offline -> "Offline"
            Error -> "Error"
        }

    val presence: Presence
        get() = when (this) {
            Online, Working, Thinking -> Presence.Active
            Idle -> Presence.Away
            Disconnected, Offline, Error -> Presence.Offline
        }

    companion object {
        fun fromWireValue(value: String?): AgentStatus? =
            when (value?.trim()?.lowercase()?.replace('-', '_')) {
                "online", "active", "available", "connected" -> Online
                "working", "running", "busy" -> Working
                "thinking" -> Thinking
                "idle", "away" -> Idle
                "disconnected", "not_connected", "notconnected" -> Disconnected
                "offline", "inactive" -> Offline
                "error", "failed", "failure" -> Error
                else -> null
            }
    }
}

// Avatar Models
@Serializable
sealed class AvatarReference {
    abstract val wireValue: String

    @Serializable
    data class Url(val url: String) : AvatarReference() {
        override val wireValue: String = url
    }

    @Serializable
    data class Pixel(val key: String) : AvatarReference() {
        override val wireValue: String = "pixel:$key"
    }

    @Serializable
    data class Random(val seed: String) : AvatarReference() {
        override val wireValue: String = "random:$seed"
    }

    companion object {
        fun fromWireValue(value: String?): AvatarReference? {
            val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return when {
                normalized.startsWith("pixel:") ->
                    Pixel(normalized.removePrefix("pixel:").ifBlank { "robot" })
                normalized.startsWith("random:") ->
                    Random(normalized.removePrefix("random:").ifBlank { "default" })
                normalized.startsWith("http://") || normalized.startsWith("https://") ->
                    Url(normalized)
                else -> null
            }
        }
    }
}

@Serializable
data class MemberProfile(
    val id: MemberId,
    val displayName: String,
    val handle: String,
    val kind: MemberKind,
    val description: String? = null,
    val roleDescription: String? = null,
    val avatarUrl: String? = null,
    val gravatarHash: String? = null,
    val avatar: AvatarReference? = null,
    val avatarLabel: String = displayName.take(1).uppercase(),
    val presence: Presence = Presence.Offline,
    val agentStatus: AgentStatus? = null,
    val joinedAt: String? = null,
    val membershipStatus: String? = null,
    val createdAgents: List<AgentProfile> = emptyList(),
    val permissionBadges: List<String> = emptyList()
)

@Serializable
data class AgentProfile(
    val id: MemberId,
    val userId: String,
    val displayName: String,
    val handle: String,
    val description: String? = null,
    val roleDescription: String? = null,
    val avatarUrl: String? = null,
    val avatar: AvatarReference? = null,
    val avatarLabel: String = displayName.take(1).uppercase(),
    val presence: Presence = Presence.Offline,
    val agentStatus: AgentStatus? = null,
    val statusLabel: String? = null,
    val runtime: String? = null,
    val model: String? = null,
    val reasoning: String? = null,
    val hasOnlineMachine: Boolean = false,
    val permissionBadges: List<String> = emptyList()
)

// Message Models
@Serializable
data class Message(
    val id: MessageId,
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val threadChannelId: ChannelId? = null,
    val seq: Long? = null,
    val author: MemberProfile,
    val timestampLabel: String,
    val body: String,
    val messageType: MessageType = MessageType.Chat,
    val systemEventType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
    val deliveryState: MessageDeliveryState = MessageDeliveryState.Sent,
    val task: TaskSummary? = null,
    val replyCount: Int = 0,
    val unreadReplyCount: Int = 0,
    val firstUnreadReplyMessageId: MessageId? = null,
    val isSaved: Boolean = false,
    val parentMessageId: MessageId? = null,
    val updatedAtLabel: String? = null,
    val reactions: List<MessageReaction>? = null,
    val senderMembershipStatus: String? = null
)

@Serializable
data class MessageReaction(
    val emoji: String,
    val count: Int,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList()
)

@Serializable
enum class MessageType {
    Chat,
    System
}

@Serializable
enum class MessageDeliveryState {
    Sending,
    Sent,
    Failed
}

@Serializable
data class Attachment(
    val id: AttachmentId,
    val name: String,
    val mimeType: String,
    val sizeLabel: String,
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null
)

// Task Models
@Serializable
enum class TaskStatus {
    Todo,
    InProgress,
    InReview,
    Done,
    Closed;

    companion object {
        fun fromWireValue(value: String?): TaskStatus? =
            when (value?.trim()?.lowercase()) {
                "todo" -> Todo
                "in_progress" -> InProgress
                "in_review" -> InReview
                "done" -> Done
                "closed" -> Closed
                else -> null
            }
    }
}

@Serializable
data class TaskSummary(
    val id: TaskId,
    val title: String,
    val status: TaskStatus,
    val taskNumber: Int? = null,
    val messageId: MessageId? = null,
    val parentMessageId: MessageId? = null,
    val channelId: ChannelId? = null,
    val threadId: ThreadId? = null,
    val isThreadTask: Boolean = false,
    val assignee: MemberProfile? = null,
    /** Task creator (sender of the original task message). Mirrors React `createdBy*`
     *  fields so the Tasks panel can show a Creator filter chip. Nullable for
     *  backward-compat with cached snapshots that pre-date this field. */
    val creator: MemberProfile? = null,
    val channelName: String? = null,
    val updatedAtLabel: String,
    val canClaim: Boolean = true
)

// Inbox Models
@Serializable
enum class InboxItemKind {
    Channel,
    DirectMessage,
    Thread,
    System
}

@Serializable
data class InboxItem(
    val key: String,
    val kind: InboxItemKind,
    val channelId: ChannelId,
    val channelName: String,
    val unreadCount: Int = 0,
    val hasMention: Boolean = false,
    val firstUnreadMessageId: MessageId? = null,
    val lastMessageId: MessageId? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null,
    val threadChannelId: ChannelId? = null,
    val parentChannelId: ChannelId? = null,
    val parentChannelName: String? = null,
    val parentChannelKind: ConversationKind? = null,
    val parentMessageId: MessageId? = null,
    val parentMessagePreview: String? = null,
    val latestActivityMessageId: MessageId? = null,
    val latestActivityPreview: String? = null,
    val latestActivityAt: String? = null,
    val latestActivitySenderName: String? = null,
    val replyCount: Int = 0,
    val participantIds: List<MemberId> = emptyList(),
    val taskNumber: Int? = null,
    val taskStatus: TaskStatus? = null,
    val taskClaimedByName: String? = null
)

// Realtime Event Models
@Serializable
sealed interface RealtimeEvent

@Serializable
data class MessageNew(val message: Message) : RealtimeEvent

@Serializable
data class MessageUpdated(val message: Message) : RealtimeEvent

@Serializable
data class DirectMessageCreated(val channelId: ChannelId) : RealtimeEvent

@Serializable
data class ThreadUpdated(
    val parentMessageId: MessageId,
    val threadChannelId: ChannelId,
    val replyCount: Int,
    val lastReplyAt: String?,
    val participantIds: List<MemberId>,
    val unreadCount: Int? = null,
    val firstUnreadMessageId: MessageId? = null
) : RealtimeEvent

@Serializable
data class TaskCreated(val tasks: List<TaskSummary>) : RealtimeEvent

@Serializable
data class TaskUpdated(val task: TaskSummary) : RealtimeEvent

@Serializable
data class TaskDeleted(val taskId: TaskId) : RealtimeEvent

@Serializable
data class MemberPresenceUpdated(
    val memberId: MemberId,
    val presence: Presence,
    val kind: MemberKind? = null,
    val agentStatus: AgentStatus? = null,
    val statusLabel: String? = null,
    val serverSeq: Long? = null
) : RealtimeEvent

@Serializable
data class RealtimeRefreshRequired(
    val scope: RealtimeRefreshScope,
    val reason: String,
    val serverId: ServerId? = null,
    val channelId: ChannelId? = null,
    val memberId: MemberId? = null,
    val subjectId: String? = null
) : RealtimeEvent

@Serializable
data class ServerMembershipRemoved(
    val serverId: ServerId
) : RealtimeEvent

@Serializable
data class ServerDeleted(
    val serverId: ServerId
) : RealtimeEvent

@Serializable
data class ServerPlanUpdated(
    val serverId: ServerId,
    val plan: String
) : RealtimeEvent

@Serializable
data class AgentCreated(
    val agent: AgentProfile
) : RealtimeEvent

@Serializable
enum class RealtimeRefreshScope {
    Home,
    ChannelMembers,
    ServerMembers,
    Agents,
    Machines,
    Announcements,
    Reminders,
    Settings
}

@Serializable
data class SyncResumeResponse(
    val messages: List<Message>,
    val currentSeq: Long,
    val hasMore: Boolean
) : RealtimeEvent

@Serializable
data class Heartbeat(val seq: Long) : RealtimeEvent

// Settings Models
@Serializable
data class SettingsSummary(
    val server: ServerSummary,
    val notificationEnabled: Boolean,
    val compactModeEnabled: Boolean,
    val manageableSections: List<String>
)
