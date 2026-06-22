package ai.slock.compose.data

import ai.slock.compose.model.*
import kotlinx.serialization.Serializable

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
    val lastMessageSenderId: String? = null,
    val lastMessageSenderKind: MemberKind? = null,
    val lastMessageSenderAvatarWireValue: String? = null,
    val threadChannelId: ChannelId? = null,
    val parentChannelId: ChannelId? = null,
    val parentChannelName: String? = null,
    val parentChannelKind: ConversationKind? = null,
    val parentMessageId: MessageId? = null,
    val parentMessagePreview: String? = null,
    val parentMessageSenderId: String? = null,
    val parentMessageSenderKind: MemberKind? = null,
    val latestActivityMessageId: MessageId? = null,
    val latestActivityPreview: String? = null,
    val latestActivityAt: String? = null,
    val latestActivitySenderName: String? = null,
    val latestActivitySenderId: String? = null,
    val latestActivitySenderKind: MemberKind? = null,
    val latestActivitySenderAvatarWireValue: String? = null,
    val replyCount: Int = 0,
    val participantIds: List<MemberId> = emptyList(),
    val taskNumber: Int? = null,
    val taskStatus: TaskStatus? = null,
    val taskClaimedByName: String? = null
)

@Serializable
data class HomeSnapshot(
    val server: ServerSummary,
    val currentMember: MemberProfile,
    val channels: List<ConversationSummary>,
    val directMessages: List<ConversationSummary>,
    val unreadTotal: Int,
    /**
     * #81: explicit source provenance for [unreadTotal]. Matches
     * `HomeParser.kt:97-103` derivation:
     * - `SERVER_UNREAD_SUMMARY` when `payload.serverUnreadSummary` had a
     *   non-null entry for the active server.
     * - `CHANNEL_DM_FALLBACK` when the server summary was missing for the
     *   active server and the count was derived from the channel + DM
     *   unread sum.
     * - `UNKNOWN` only for synthetic / preview snapshots that bypass the
     *   parser.
     *
     * Default is `UNKNOWN` so existing call sites that don't set it (test
     * fixtures, preview builders) keep compiling.
     */
    val unreadSource: AppBadgeSource = AppBadgeSource.UNKNOWN,
    /**
     * #211: per-server sidebar order snapshot, including pinned conversation
     * ids, pinned order, and pinned sort mode. `null` means the sidebar
     * order has not been loaded for this snapshot yet; the visual layer
     * falls back to "no pinned items" until a non-null value arrives.
     */
    val sidebarOrder: SettingsServerSidebarOrderSnapshot? = null
)

data class ServerListSnapshot(
    val servers: List<ServerSummary>,
    val selectedServer: ServerSummary?
)

@Serializable
data class ThreadSnapshot(
    val conversation: ConversationSummary,
    val messages: List<Message>,
    val parentMessage: Message? = null,
    val tasks: List<TaskSummary> = emptyList(),
    val canLoadMoreBefore: Boolean = false,
    val canLoadMoreAfter: Boolean = false,
    val hasGap: Boolean = false,
    val historyLimited: Boolean = false,
    val highlightedMessageId: MessageId? = null,
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
    val bytes: ByteArray = ByteArray(0),
    val fileRef: String? = null
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

data class ChannelFileItem(
    val attachment: Attachment,
    val createdAt: String? = null,
    val messageId: MessageId? = null,
    val channelId: ChannelId? = null,
    val uploaderType: String? = null,
    val uploaderId: String? = null,
    val uploaderName: String? = null,
    val uploaderDisplayName: String? = null,
    val sourceType: String? = null,
    val sourceParentMessageId: MessageId? = null,
    val sourceParentMessageShortId: String? = null
)

data class ChannelFilesSnapshot(
    val channelId: ChannelId,
    val items: List<ChannelFileItem>,
    val hasMore: Boolean = false,
    val nextCursor: String? = null
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
    val summary: AgentDetailSummary? = null,
    val directMessages: List<AgentDmSummary>,
    val reminders: List<AgentReminderSummary>,
    val activity: List<AgentActivityEntry>,
    val skills: AgentSkillsSnapshot = AgentSkillsSnapshot(agentId = agentId)
)

data class AgentDetailSummary(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val status: String? = null,
    val runtime: String? = null,
    val model: String? = null,
    val reasoning: String? = null,
    val executionMode: String? = null,
    val machineId: String? = null,
    val machineName: String? = null,
    val daemonVersion: String? = null,
    val creatorId: String? = null,
    val creatorType: String? = null,
    val envVarNames: List<String> = emptyList(),
    val createdAgents: List<AgentCreatedSummary> = emptyList(),
    val activity: String? = null,
    val activityDetail: String? = null,
    val deletedAt: String? = null
)

data class AgentCreatedSummary(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val runtime: String? = null,
    val status: String? = null
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

data class AgentWorkspaceSnapshot(
    val agentId: MemberId,
    val computerId: String? = null,
    val computerName: String? = null,
    val workspaces: List<SettingsComputerWorkspaceSnapshot> = emptyList()
)

data class AgentWorkspaceTreeSnapshot(
    val agentId: MemberId,
    val rootPath: String,
    val root: SlockWorkspaceNode
)

data class AgentWorkspaceChildrenSnapshot(
    val agentId: MemberId,
    val dirPath: String,
    val children: List<SlockWorkspaceNode>
)

data class AgentWorkspaceFilePreviewSnapshot(
    val agentId: MemberId,
    val path: String,
    val preview: SlockWorkspaceFilePreview
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
    val details: List<Pair<String, String>> = emptyList(),
    val toolName: String? = null,
    val toolInput: String? = null,
    val statusActivity: String? = null,
    val statusDetail: String? = null,
    val expandable: Boolean = false,
    val refTextEnabled: Boolean = false
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

data class InboxDoneResult(
    val channelId: ChannelId
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

data class ChannelLeaveResult(
    val channelId: ChannelId,
    val left: Boolean = true
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

@Serializable
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
    val messageId: MessageId
)

data class SavedMessageStatusSnapshot(
    val savedIds: Set<MessageId>
)

data class SearchSnapshot(
    val server: ServerSummary,
    val query: String,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class SettingsSnapshot(
    val settings: SettingsSummary
)

data class SettingsConnectedAppsSnapshot(
    val marketplace: List<SettingsConnectedAppMarketplaceSnapshot> = emptyList(),
    val builtIn: List<SettingsConnectedAppBuiltInSnapshot> = emptyList(),
    val installed: List<SettingsConnectedAppInstalledSnapshot> = emptyList(),
    val registered: List<SettingsConnectedAppClientSnapshot> = emptyList(),
    val canManage: Boolean = true,
    val oneTimeClientSecret: SettingsConnectedAppSecretSnapshot? = null
)

data class SettingsConnectedAppMarketplaceSnapshot(
    val id: String,
    val clientId: String,
    val name: String,
    val category: String,
    val developer: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val homepageDomain: String? = null,
    val returnUrl: String? = null,
    val callbackDomain: String? = null,
    val logoUrl: String? = null,
    val installed: Boolean = false,
    val dataAccessSummary: String = "Profile and identity"
)

data class SettingsConnectedAppBuiltInSnapshot(
    val id: String,
    val clientId: String,
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val homepageDomain: String? = null,
    val agentManifestUrl: String? = null
)

data class SettingsConnectedAppInstalledSnapshot(
    val id: String,
    val clientId: String,
    val name: String,
    val developer: String,
    val description: String? = null,
    val homepageDomain: String? = null,
    val callbackDomain: String? = null,
    val logoUrl: String? = null,
    val origin: String,
    val editable: Boolean = false
)

data class SettingsConnectedAppClientSnapshot(
    val id: String,
    val serverId: String,
    val clientId: String,
    val appType: String,
    val publishStatus: String,
    val category: String,
    val dataAccessSummary: String? = null,
    val publishRejectionReason: String? = null,
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val homepageDomain: String? = null,
    val returnUrl: String? = null,
    val callbackDomain: String? = null,
    val agentManifestUrl: String? = null,
    val logoUrl: String? = null
)

data class SettingsConnectedAppSecretSnapshot(
    val clientId: String,
    val clientSecret: String
)

data class AccountSettingsSnapshot(
    val profile: AccountProfileSnapshot,
    val connectedAccounts: List<ConnectedAccountSnapshot>,
    val canEditProfile: Boolean = true,
    val canUploadAvatar: Boolean = true,
    val canChangePassword: Boolean = true
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
    val providerId: String,
    val provider: String,
    val identifier: String,
    val status: ConnectedAccountStatus,
    val canConnect: Boolean = status == ConnectedAccountStatus.NotConnected
)

enum class ConnectedAccountStatus {
    Connected,
    NotConnected,
    Loading,
    Failed
}

data class SettingsBillingSnapshot(
    val currentPlan: SettingsBillingCurrentPlanSnapshot,
    val plans: List<SettingsBillingPlanSnapshot>,
    val notices: List<SettingsBillingNoticeSnapshot> = emptyList(),
    val actions: List<SettingsBillingActionSnapshot> = emptyList(),
    val comparePlansUrl: String = "https://raft.build/#pricing"
)

data class SettingsBillingSubscriptionSnapshot(
    val status: String? = null,
    val currentPeriodEnd: String? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val stripeConfigured: Boolean = false,
    val billingInterval: String? = null,
    val canManageBilling: Boolean = false,
    val displayName: String? = null,
    val effectivePlan: String? = null
)

data class SettingsServerUsageSnapshot(
    val agents: Int,
    val machines: Int,
    val channels: Int
)

data class SettingsBillingCurrentPlanSnapshot(
    val planName: String,
    val usageRows: List<SettingsPlanUsageSnapshot>,
    val trialActive: Boolean = false,
    val description: String = "",
    val includedFeatures: List<String> = emptyList(),
    val excludedFeatures: List<String> = emptyList()
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
    val comingSoon: Boolean = false,
    val badgeLabel: String? = null
)

data class SettingsBillingNoticeSnapshot(
    val key: String,
    val title: String,
    val detail: String,
    val tone: String = "info",
    val rows: List<SettingsPlanUsageSnapshot> = emptyList()
)

data class SettingsBillingActionSnapshot(
    val key: String,
    val label: String,
    val detail: String,
    val enabled: Boolean = false,
    val style: String = "secondary"
)

data class SettingsComputersSnapshot(
    val computers: List<SettingsComputerSnapshot>,
    val canAddComputer: Boolean = false,
    val addComputerUnavailableReason: String? = null,
    val latestDaemonVersion: String? = null
)

data class SettingsComputerSnapshot(
    val id: String,
    val name: String,
    val daemonVersion: String,
    val online: Boolean,
    val hostname: String? = null,
    val platform: String? = null,
    val lastSeenLabel: String? = null,
    val runtimes: List<String> = emptyList(),
    val createdAt: String? = null,
    val lastHeartbeat: String? = null,
    val apiKeyPrefix: String? = null,
    val statusVersion: Long = 0L
)

data class SettingsComputerDetailSnapshot(
    val computer: SettingsComputerSnapshot,
    val workspaces: List<SettingsComputerWorkspaceSnapshot>,
    val agents: List<SettingsComputerAgentSnapshot>,
    val sections: List<SettingsServerSectionSnapshot>,
    val workspacesError: SettingsSectionErrorSnapshot? = null,
    val agentsError: SettingsSectionErrorSnapshot? = null,
    /**
     * #78: server-side `manageMachines` capability. Defaults to false to mirror
     * the existing [SettingsComputersSnapshot.canAddComputer] hardcode — actual
     * member-role permission detection is a follow-up. When false, the
     * ComputerDetail page hides rename / delete / rotate-key actions; the
     * server still enforces 403 on the API endpoints so unauthorized users
     * can't bypass a permissive client.
     */
    val canManageMachines: Boolean = false
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
    val ownerLabel: String? = null,
    val memberProfile: MemberProfile? = null
)

data class SettingsServerSnapshot(
    val profile: SettingsServerProfileSnapshot,
    val sections: List<SettingsServerSectionSnapshot>,
    val admins: List<SettingsServerAdminSnapshot> = emptyList(),
    val usageRows: List<SettingsPlanUsageSnapshot> = emptyList(),
    val pendingInvites: List<SettingsServerInviteSnapshot> = emptyList(),
    val joinLinks: List<SettingsServerJoinLinkSnapshot> = emptyList(),
    val agreement: SettingsServerAgreementSnapshot? = null,
    val onboarding: SettingsServerOnboardingSnapshot? = null,
    val translation: SettingsServerTranslationSnapshot? = null,
    val sidebarOrder: SettingsServerSidebarOrderSnapshot? = null
)

data class SettingsServerProfileSnapshot(
    val name: String,
    val slug: String,
    val hideHumansFromMembers: Boolean = false,
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

data class SettingsServerAgreementSnapshot(
    val enabled: Boolean,
    val title: String,
    val bodyMarkdown: String,
    val statusLabel: String,
    val detail: String,
    val canSave: Boolean = false
)

data class SettingsServerOnboardingSnapshot(
    val setupModalReminderOptOut: Boolean,
    val onboardingAgentId: String? = null,
    val agentAllChannelGreetingEnabled: Boolean = true,
    val agents: List<SettingsServerOnboardingAgentSnapshot> = emptyList(),
    val statusLabel: String,
    val detail: String,
    val canSave: Boolean = false
)

data class SettingsServerOnboardingAgentSnapshot(
    val id: String,
    val name: String,
    val displayName: String? = null
)

data class SettingsServerTranslationSnapshot(
    val translationEnabled: Boolean,
    val translationAvailable: Boolean,
    val canManageTranslation: Boolean,
    val statusLabel: String,
    val detail: String
)

@Serializable
data class SettingsServerSidebarOrderSnapshot(
    val channelOrder: List<String> = emptyList(),
    val agentOrder: List<String> = emptyList(),
    val dmOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
    val dmSortMode: String = "manual",
    val pinnedSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedAgentIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val hiddenDmIds: List<String> = emptyList(),
    val channelPanelTabOrder: List<String> = emptyList(),
    val agentPanelTabOrder: List<String> = emptyList()
)

data class SettingsMobileSnapshot(
    val pushNotifications: SettingsMobileToggleSnapshot,
    val muteServer: SettingsMobileToggleSnapshot,
    val sendTestEnabled: Boolean = false,
    val notificationPermission: SettingsMobileActionSnapshot =
        SettingsMobileActionSnapshot(
            available = false,
            statusLabel = "Unsupported",
            detail = "Notification permission is not available on this platform."
        ),
    val notificationSettings: SettingsMobileActionSnapshot =
        SettingsMobileActionSnapshot(
            available = false,
            statusLabel = "Unsupported",
            detail = "System notification settings are not available on this platform."
        ),
    val testNotification: SettingsMobileActionSnapshot =
        SettingsMobileActionSnapshot(
            available = false,
            statusLabel = "Unsupported",
            detail = "Test notifications are not available on this platform."
        ),
    val localePreference: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = AppLocale.Default.languageTag,
            selectedLabel = AppLocale.Default.englishLabel,
            statusLabel = AppLocale.Default.englishLabel,
            detail = "Stored locally on this device.",
            options =
                AppLocale.entries.map {
                    SettingsMobileSelectionOptionSnapshot(
                        key = it.languageTag,
                        label = it.englishLabel
                    )
                }
        ),
    val messageAlignmentPreference: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = MyMessageAlignmentPreference.Default.storageKey,
            selectedLabel = "Left",
            statusLabel = "Left",
            detail = "Stored locally on this device.",
            options =
                MyMessageAlignmentPreference.entries.map {
                    SettingsMobileSelectionOptionSnapshot(
                        key = it.storageKey,
                        label = it.name
                    )
                }
        )
)

data class SettingsMobileToggleSnapshot(
    val enabled: Boolean,
    val available: Boolean,
    val statusLabel: String,
    val detail: String
)

data class SettingsMobileActionSnapshot(
    val available: Boolean,
    val statusLabel: String,
    val detail: String
)

data class SettingsMobileSelectionSnapshot(
    val selectedKey: String,
    val selectedLabel: String,
    val statusLabel: String,
    val detail: String,
    val options: List<SettingsMobileSelectionOptionSnapshot>,
    val available: Boolean = true
)

data class SettingsMobileSelectionOptionSnapshot(
    val key: String,
    val label: String
)

data class SettingsLanguageRegionSnapshot(
    val serverTranslation: SettingsMobileToggleSnapshot,
    val autoTranslation: SettingsMobileToggleSnapshot,
    val browserLanguage: SettingsMobileSelectionSnapshot,
    val timezone: SettingsMobileSelectionSnapshot,
    val timeFormat: SettingsMobileSelectionSnapshot,
    val translationDisplay: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = "translated",
            selectedLabel = "Translated",
            statusLabel = "Translated",
            detail = "Messages display translated text when this server supports it.",
            options =
                listOf(
                    SettingsMobileSelectionOptionSnapshot("translated", "Translated"),
                    SettingsMobileSelectionOptionSnapshot("original", "Original")
                )
        )
)

data class SettingsLanguageRegionAccountPreferences(
    val preferredLanguage: String? = null,
    val preferredTimezone: String? = null,
    val autoTranslationEnabled: Boolean = true,
    val preferredTimeFormat: String? = null,
    val preferredTranslationDisplay: String? = null
)

data class SettingsLanguageRegionPreferenceUpdateRequest(
    val preferredLanguage: String? = null,
    val clearPreferredLanguage: Boolean = false,
    val preferredTimezone: String? = null,
    val clearPreferredTimezone: Boolean = false,
    val autoTranslationEnabled: Boolean? = null,
    val preferredTimeFormat: String? = null,
    val clearPreferredTimeFormat: Boolean = false,
    val preferredTranslationDisplay: String? = null
)

data class SettingsAppearanceSnapshot(
    val messageBodyFontSize: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = "md",
            selectedLabel = "Medium",
            statusLabel = "Medium",
            detail = "Adjusts message body text only.",
            options =
                listOf(
                    SettingsMobileSelectionOptionSnapshot("sm", "Small"),
                    SettingsMobileSelectionOptionSnapshot("md", "Medium"),
                    SettingsMobileSelectionOptionSnapshot("lg", "Large")
                )
        )
)

data class SettingsAppearanceUpdateRequest(
    val preferredMessageBodyFontSize: String
)

enum class SettingsSectionAvailability {
    Available,
    Empty,
    Unavailable,
    NoPermission
}

data class PendingMentionAction(
    val resolutionId: String,
    val messageId: MessageId? = null,
    val targetType: String = "unknown",
    val targetHandle: String = "",
    val reason: String = "",
    val availableActions: List<String> = emptyList(),
    val expiresAt: String? = null
)

data class PendingMentionActionExecuteResult(
    val resolutionId: String,
    val status: String,
    val reason: String? = null
)

data class SendMessageResult(
    val message: Message,
    val pendingMentionActions: List<PendingMentionAction> = emptyList()
)

data class ThreadActionResult(
    val threadChannelId: ChannelId,
    val changed: Boolean,
    val parentMessageId: MessageId? = null,
    val replyCount: Int = 0
)

data class TaskActionResult(
    val task: TaskSummary
)
