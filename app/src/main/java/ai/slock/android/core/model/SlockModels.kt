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
    val avatarUrl: String? = null,
    val avatar: AvatarReference? = AvatarReference.fromWireValue(avatarUrl),
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val lastMessageId: MessageId? = null,
    val firstUnreadMessageId: MessageId? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null,
    val presence: Presence? = null,
    val presenceMemberId: MemberId? = null,
    val hasDraft: Boolean = false,
    val joined: Boolean = true,
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

sealed interface AvatarReference {
    val wireValue: String

    data class Url(val url: String) : AvatarReference {
        override val wireValue: String = url
    }

    data class Pixel(val key: String) : AvatarReference {
        override val wireValue: String = "pixel:$key"
    }

    data class Random(val seed: String) : AvatarReference {
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

data class MemberProfile(
    val id: MemberId,
    val displayName: String,
    val handle: String,
    val kind: MemberKind,
    val roleDescription: String? = null,
    val avatarUrl: String? = null,
    val gravatarHash: String? = null,
    val avatar: AvatarReference? = avatarUrl?.let(AvatarReference::fromWireValue)
        ?: gravatarHash?.toGravatarAvatarReference(),
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
    val avatar: AvatarReference? = AvatarReference.fromWireValue(avatarUrl),
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
            avatarUrl = avatarUrl,
            avatar = avatar,
            avatarLabel = avatarLabel,
            presence = presence,
            permissionBadges = permissionBadges
        )
}

fun String.toGravatarAvatarUrl(): String =
    "https://www.gravatar.com/avatar/${trim().lowercase()}?s=52&d=404"

fun String.toGravatarAvatarReference(): AvatarReference.Url =
    AvatarReference.Url(toGravatarAvatarUrl())

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
    val sizeLabel: String,
    val thumbnailUrl: String? = null
)

enum class TaskStatus {
    Todo,
    InProgress,
    InReview,
    Done,
    Closed
}

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
    data class DirectMessageCreated(val channelId: ChannelId) : RealtimeEvent
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
    data class MemberPresenceUpdated(
        val memberId: MemberId,
        val presence: Presence,
        val kind: MemberKind? = null,
        val statusLabel: String? = null
    ) : RealtimeEvent
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
