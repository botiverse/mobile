package ai.slock.android.core.data.repository

import ai.slock.android.core.model.AgentProfile
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.SettingsSummary
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary

data class HomeSnapshot(
    val server: ServerSummary,
    val currentMember: MemberProfile,
    val channels: List<ConversationSummary>,
    val directMessages: List<ConversationSummary>,
    val unreadTotal: Int
)

data class ServerListSnapshot(
    val servers: List<ServerSummary>,
    val selectedServer: ServerSummary?
)

data class ThreadSnapshot(
    val conversation: ConversationSummary,
    val messages: List<Message>,
    val canLoadMoreBefore: Boolean = false,
    val canLoadMoreAfter: Boolean = false,
    val hasGap: Boolean = false,
    val historyLimited: Boolean = false,
    val highlightedMessageId: ai.slock.android.core.model.MessageId? = null,
    val lastSeq: Long = messages.maxOfOrNull { it.seq ?: 0L } ?: 0L
)

data class TaskSnapshot(
    val tasks: List<TaskSummary>,
    val summaryCounts: Map<TaskStatus, Int>
)

data class MemberSnapshot(
    val server: ServerSummary,
    val members: List<MemberProfile>
)

data class AgentSnapshot(
    val server: ServerSummary,
    val agents: List<AgentProfile>,
    val members: List<MemberProfile> = agents.map { it.toMemberProfile() }
)

data class InboxSnapshot(
    val server: ServerSummary,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val totalUnreadCount: Int = items.sumOf { it.unreadCount },
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class ChannelSnapshot(
    val server: ServerSummary,
    val channels: List<ConversationSummary>
)

data class DirectMessageSnapshot(
    val server: ServerSummary,
    val directMessages: List<ConversationSummary>
)

data class SavedSnapshot(
    val server: ServerSummary,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class SearchSnapshot(
    val server: ServerSummary,
    val query: String,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class ServerUnreadSummarySnapshot(
    val server: ServerSummary,
    val summaries: List<ServerUnreadSummary>,
    val selectedServerSummary: ServerUnreadSummary?,
    val totalUnreadCount: Int,
    val inboxUnreadCount: Int = selectedServerSummary?.inboxUnreadCount ?: 0,
    val savedUnreadCount: Int = selectedServerSummary?.savedUnreadCount ?: 0,
    val mentionCount: Int = selectedServerSummary?.mentionCount ?: 0
)

data class ServerUnreadSummary(
    val serverId: ServerId,
    val totalUnreadCount: Int,
    val inboxUnreadCount: Int = 0,
    val channelUnreadCount: Int = 0,
    val directMessageUnreadCount: Int = 0,
    val savedUnreadCount: Int = 0,
    val mentionCount: Int = 0,
    val taskUnreadCount: Int = 0,
    val updatedAt: String? = null
)

data class SettingsSnapshot(
    val settings: SettingsSummary
)

data class AccountSettingsSnapshot(
    val profile: AccountProfileSnapshot,
    val connectedAccounts: List<ConnectedAccountSnapshot>,
    val canEditProfile: Boolean = true
)

data class AccountProfileSnapshot(
    val userId: String,
    val displayName: String,
    val handle: String,
    val email: String,
    val avatarLabel: String,
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val roleLabel: String? = null
)

data class ConnectedAccountSnapshot(
    val provider: String,
    val identifier: String,
    val status: ConnectedAccountStatus
)

enum class ConnectedAccountStatus {
    Connected,
    NotConnected,
    Loading,
    Failed
}

data class SessionSettingsSnapshot(
    val sessions: List<SettingsSessionSnapshot>,
    val canRevokeOtherSessions: Boolean = true
)

data class SettingsSessionSnapshot(
    val id: String,
    val deviceName: String,
    val browserLabel: String,
    val locationLabel: String,
    val lastActiveLabel: String,
    val current: Boolean = false,
    val revokeEnabled: Boolean = true
)

data class SessionActionResult(
    val sessionId: String,
    val revoked: Boolean
)

data class SettingsComputersSnapshot(
    val computers: List<SettingsComputerSnapshot>,
    val canAddComputer: Boolean = false,
    val addComputerUnavailableReason: String? = null
)

data class SettingsComputerSnapshot(
    val id: String,
    val name: String,
    val daemonVersion: String,
    val online: Boolean
)

data class SettingsServerSnapshot(
    val profile: SettingsServerProfileSnapshot,
    val sections: List<SettingsServerSectionSnapshot>
)

data class SettingsServerProfileSnapshot(
    val name: String,
    val slug: String,
    val canSave: Boolean = false
)

data class SettingsServerSectionSnapshot(
    val key: String,
    val title: String,
    val detail: String,
    val state: SettingsSectionAvailability = SettingsSectionAvailability.Unavailable
)

data class SettingsMobileSnapshot(
    val pushNotifications: SettingsMobileToggleSnapshot,
    val muteServer: SettingsMobileToggleSnapshot,
    val sendTestEnabled: Boolean = false
)

data class SettingsMobileToggleSnapshot(
    val enabled: Boolean,
    val available: Boolean,
    val statusLabel: String,
    val detail: String
)

enum class SettingsSectionAvailability {
    Available,
    Empty,
    Unavailable,
    NoPermission
}

data class SendMessageResult(
    val message: Message
)

data class TaskActionResult(
    val task: TaskSummary
)
