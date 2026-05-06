package ai.slock.android.core.model

data class SessionSnapshot(
    val currentServerId: ServerId,
    val currentMember: MemberProfile,
    val availableServers: List<ServerSummary>
)

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

data class ServerPermissions(
    val canCreateChannel: Boolean = true,
    val canInviteMember: Boolean = true,
    val canManageSettings: Boolean = false
)

enum class ConversationKind {
    Channel,
    DirectMessage,
    Inbox,
    System
}

data class ConversationSummary(
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val kind: ConversationKind,
    val name: String,
    val subtitle: String? = null,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val lastMessageId: MessageId? = null,
    val firstUnreadMessageId: MessageId? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null,
    val archived: Boolean = false,
    val permissions: ConversationPermissions = ConversationPermissions()
)

data class ConversationPermissions(
    val canRead: Boolean = true,
    val canSend: Boolean = true,
    val canAttach: Boolean = true,
    val canCreateTask: Boolean = true
)

enum class MemberKind {
    Human,
    Agent,
    System
}

enum class Presence {
    Active,
    Away,
    Offline
}

data class MemberProfile(
    val id: MemberId,
    val displayName: String,
    val handle: String,
    val kind: MemberKind,
    val roleDescription: String? = null,
    val avatarLabel: String = displayName.take(1).uppercase(),
    val presence: Presence = Presence.Offline,
    val permissionBadges: List<String> = emptyList()
)

data class AgentProfile(
    val id: MemberId,
    val userId: String,
    val displayName: String,
    val handle: String,
    val roleDescription: String? = null,
    val avatarUrl: String? = null,
    val avatarLabel: String = displayName.take(1).uppercase(),
    val presence: Presence = Presence.Offline,
    val statusLabel: String? = null,
    val runtime: String? = null,
    val model: String? = null,
    val reasoning: String? = null,
    val hasOnlineMachine: Boolean = false,
    val permissionBadges: List<String> = emptyList()
) {
    fun toMemberProfile(): MemberProfile =
        MemberProfile(
            id = id,
            displayName = displayName,
            handle = handle,
            kind = MemberKind.Agent,
            roleDescription = roleDescription,
            avatarLabel = avatarLabel,
            presence = presence,
            permissionBadges = permissionBadges
        )
}

data class Message(
    val id: MessageId,
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val seq: Long? = null,
    val author: MemberProfile,
    val timestampLabel: String,
    val body: String,
    val messageType: MessageType = MessageType.Chat,
    val attachments: List<Attachment> = emptyList(),
    val deliveryState: MessageDeliveryState = MessageDeliveryState.Sent,
    val task: TaskSummary? = null,
    val replyCount: Int = 0,
    val parentMessageId: MessageId? = null,
    val updatedAtLabel: String? = null
)

enum class MessageType {
    Chat,
    System
}

enum class MessageDeliveryState {
    Sending,
    Sent,
    Failed
}

data class Attachment(
    val id: AttachmentId,
    val name: String,
    val mimeType: String,
    val sizeLabel: String
)

enum class TaskStatus {
    Todo,
    InProgress,
    InReview,
    Done
}

data class TaskSummary(
    val id: TaskId,
    val title: String,
    val status: TaskStatus,
    val taskNumber: Int? = null,
    val messageId: MessageId? = null,
    val assignee: MemberProfile? = null,
    val channelName: String? = null,
    val updatedAtLabel: String,
    val canClaim: Boolean = true
)

enum class InboxItemKind {
    Channel,
    DirectMessage,
    Thread
}

data class InboxItem(
    val key: String,
    val kind: InboxItemKind,
    val channelId: ChannelId,
    val channelName: String,
    val unreadCount: Int = 0,
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

sealed interface RealtimeEvent {
    data class MessageNew(val message: Message) : RealtimeEvent
    data class MessageUpdated(val message: Message) : RealtimeEvent
    data class ThreadUpdated(
        val parentMessageId: MessageId,
        val threadChannelId: ChannelId,
        val replyCount: Int,
        val lastReplyAt: String?,
        val participantIds: List<MemberId>
    ) : RealtimeEvent
    data class TaskCreated(val tasks: List<TaskSummary>) : RealtimeEvent
    data class TaskUpdated(val task: TaskSummary) : RealtimeEvent
    data class TaskDeleted(val taskId: TaskId) : RealtimeEvent
    data class SyncResumeResponse(
        val messages: List<Message>,
        val currentSeq: Long,
        val hasMore: Boolean
    ) : RealtimeEvent
    data class Heartbeat(val seq: Long) : RealtimeEvent
}

data class SettingsSummary(
    val server: ServerSummary,
    val notificationEnabled: Boolean,
    val compactModeEnabled: Boolean,
    val manageableSections: List<String>
)
