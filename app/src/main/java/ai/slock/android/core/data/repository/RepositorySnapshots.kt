package ai.slock.android.core.data.repository

import ai.slock.android.core.model.AgentProfile
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.Attachment
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.AttachmentId
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.ChannelId
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.ConversationSummary
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.InboxItem
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.MemberId
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.MemberProfile
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.Message
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.MessageId
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.ServerId
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.ServerSummary
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.SettingsSummary
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.TaskStatus
import ai.slock.shared.model.toMemberProfile
import ai.slock.android.core.model.TaskSummary
import ai.slock.shared.model.toMemberProfile

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
    val parentMessage: Message? = null,
    val tasks: List<TaskSummary> = emptyList(),
    val canLoadMoreBefore: Boolean = false,
    val canLoadMoreAfter: Boolean = false,
    val hasGap: Boolean = false,
    val historyLimited: Boolean = false,
    val highlightedMessageId: ai.slock.android.core.model.MessageId? = null,
    val newUpdateCount: Int = 0,
    val lastSeq: Long = messages.maxOfOrNull { it.seq ?: 0L } ?: 0L
)

data class ThreadListSnapshot(
    val server: ServerSummary,
    val threads: List<InboxItem>,
    val totalCount: Int = threads.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class AttachmentUploadRequest(
    val channelId: ChannelId,
    val files: List<AttachmentUploadFile>
)

data class AttachmentUploadFile(
    val id: String,
    val fileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val previewUri: String? = null,
    val bytes: ByteArray
)

data class AttachmentUploadResult(
    val attachments: List<Attachment>
) {
    val attachmentIds: List<AttachmentId> = attachments.map { it.id }
}

data class AttachmentOpenTarget(
    val attachmentId: AttachmentId,
    val url: String,
    val expiresAt: String? = null
)

data class TaskSnapshot(
    val tasks: List<TaskSummary>,
    val summaryCounts: Map<TaskStatus, Int>
)

data class MemberSnapshot(
    val server: ServerSummary,
    val members: List<MemberProfile>
)

data class AgentDetailSnapshot(
    val agentId: MemberId,
    val directMessages: List<AgentDmSummary>,
    val reminders: List<AgentReminderSummary>,
    val activity: List<AgentActivityEntry>,
    val skills: AgentSkillsSnapshot = AgentSkillsSnapshot(agentId = agentId)
)

data class AgentDmSnapshot(
    val agentId: MemberId,
    val directMessages: List<AgentDmSummary>
)

data class AgentReminderSnapshot(
    val agentId: MemberId,
    val reminders: List<AgentReminderSummary>
)

data class AgentActivitySnapshot(
    val agentId: MemberId,
    val activity: List<AgentActivityEntry>
)

data class AgentSkillsSnapshot(
    val agentId: MemberId,
    val groups: List<AgentSkillGroup> = emptyList()
)

data class AgentSkillGroup(
    val title: String,
    val count: Int = 0,
    val paths: List<AgentSkillPathGroup> = emptyList()
)

data class AgentSkillPathGroup(
    val path: String,
    val count: Int = 0,
    val skills: List<AgentSkillSummary> = emptyList()
)

data class AgentSkillSummary(
    val name: String,
    val detail: String? = null
)

data class AgentDmSummary(
    val id: String,
    val peerId: String?,
    val peerName: String,
    val peerAvatarUrl: String? = null,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null
)

data class AgentReminderSummary(
    val id: String,
    val title: String,
    val fireAt: String? = null,
    val recurrenceDescription: String? = null,
    val messageRef: String? = null,
    val messagePermalink: String? = null,
    val ownerAgentId: String? = null,
    val status: String? = null
)

data class AgentActivityEntry(
    val id: String,
    val type: String,
    val title: String,
    val body: String? = null,
    val timestamp: String? = null,
    val details: List<Pair<String, String>> = emptyList()
)

data class ChannelMemberSnapshot(
    val channelId: ChannelId,
    val members: List<MemberProfile>,
    val canAddMembers: Boolean = true
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

data class CreateChannelResult(
    val channel: ConversationSummary,
    val addedAgentMemberIds: List<String> = emptyList(),
    val addedUserMemberIds: List<String> = emptyList()
)

data class ChannelJoinResult(
    val channelId: ChannelId,
    val joined: Boolean = true,
    val channel: ConversationSummary? = null
)

data class ChannelArchiveResult(
    val channelId: ChannelId,
    val archived: Boolean = true,
    val channel: ConversationSummary? = null
)

data class ChannelDeleteResult(
    val channelId: ChannelId,
    val deleted: Boolean = true
)

data class ChannelUnreadSnapshot(
    val server: ServerSummary,
    val unreadCounts: Map<ChannelId, Int>,
    val totalUnreadCount: Int = unreadCounts.values.sum()
)

data class ReadReceiptResult(
    val channelId: ChannelId? = null,
    val seq: Long? = null,
    val unreadCount: Int = 0,
    val changed: Boolean = true
)

data class DirectMessageSnapshot(
    val server: ServerSummary,
    val directMessages: List<ConversationSummary>
)

data class OpenDirectMessageResult(
    val conversation: ConversationSummary,
    val directMessages: List<ConversationSummary>
)

data class SavedSnapshot(
    val server: ServerSummary,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class SavedMessageMutationResult(
    val messageId: ai.slock.android.core.model.MessageId
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
    val selectedServerUnreadCount: Int = selectedServerSummary?.totalUnreadCount ?: server.unreadCount,
    val mentionCount: Int = selectedServerSummary?.mentionCount ?: 0
)

data class ServerUnreadSummary(
    val serverId: ServerId,
    val totalUnreadCount: Int,
    val channelUnreadCount: Int = 0,
    val directMessageUnreadCount: Int = 0,
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

data class SettingsBillingSnapshot(
    val currentPlan: SettingsBillingCurrentPlanSnapshot,
    val plans: List<SettingsBillingPlanSnapshot>
)

data class SettingsBillingCurrentPlanSnapshot(
    val planName: String,
    val usageRows: List<SettingsPlanUsageSnapshot>,
    val trialActive: Boolean = false
)

data class SettingsPlanUsageSnapshot(
    val label: String,
    val value: String
)

data class SettingsBillingPlanSnapshot(
    val name: String,
    val price: String,
    val features: List<String>,
    val current: Boolean = false,
    val comingSoon: Boolean = false
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
    val online: Boolean,
    val hostname: String? = null,
    val platform: String? = null,
    val lastSeenLabel: String? = null
)

data class SettingsComputerDetailSnapshot(
    val computer: SettingsComputerSnapshot,
    val workspaces: List<SettingsComputerWorkspaceSnapshot>,
    val agents: List<SettingsComputerAgentSnapshot>,
    val sections: List<SettingsServerSectionSnapshot>,
    val workspacesError: SettingsSectionErrorSnapshot? = null,
    val agentsError: SettingsSectionErrorSnapshot? = null
)

data class SettingsSectionErrorSnapshot(
    val code: String,
    val retryable: Boolean = false
)

data class SettingsComputerWorkspaceSnapshot(
    val id: String,
    val name: String,
    val path: String,
    val statusLabel: String? = null,
    val lastActiveLabel: String? = null
)

data class SettingsComputerAgentSnapshot(
    val id: String,
    val name: String,
    val statusLabel: String? = null,
    val ownerLabel: String? = null
)

data class SettingsServerSnapshot(
    val profile: SettingsServerProfileSnapshot,
    val sections: List<SettingsServerSectionSnapshot>,
    val admins: List<SettingsServerAdminSnapshot> = emptyList(),
    val usageRows: List<SettingsPlanUsageSnapshot> = emptyList(),
    val pendingInvites: List<SettingsServerInviteSnapshot> = emptyList(),
    val joinLinks: List<SettingsServerJoinLinkSnapshot> = emptyList(),
    val onboarding: SettingsServerOnboardingSnapshot? = null
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

data class SettingsServerAdminSnapshot(
    val id: String,
    val name: String,
    val detail: String? = null,
    val role: String? = null
)

data class SettingsServerInviteSnapshot(
    val id: String,
    val email: String,
    val inviterName: String? = null,
    val createdAtLabel: String? = null
)

data class SettingsServerJoinLinkSnapshot(
    val id: String,
    val label: String,
    val url: String? = null,
    val maxUsesLabel: String? = null,
    val usageLabel: String? = null,
    val expiresAtLabel: String? = null
)

data class SettingsServerOnboardingSnapshot(
    val setupModalReminderOptOut: Boolean,
    val statusLabel: String,
    val detail: String
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

data class ThreadActionResult(
    val threadChannelId: ai.slock.android.core.model.ChannelId,
    val changed: Boolean,
    val parentMessageId: ai.slock.android.core.model.MessageId? = null,
    val replyCount: Int = 0
)

data class TaskActionResult(
    val task: TaskSummary
)
