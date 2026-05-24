package ai.slock.android.core.data.mock

import ai.slock.compose.data.AgentSnapshot
import ai.slock.compose.data.AccountProfileSnapshot
import ai.slock.compose.data.AccountSettingsSnapshot
import ai.slock.compose.data.ChannelSnapshot
import ai.slock.compose.data.ConnectedAccountSnapshot
import ai.slock.compose.data.ConnectedAccountStatus
import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.InboxSnapshot
import ai.slock.compose.data.MemberSnapshot
import ai.slock.compose.data.ServerListSnapshot
import ai.slock.compose.data.SettingsBillingCurrentPlanSnapshot
import ai.slock.compose.data.SettingsBillingPlanSnapshot
import ai.slock.compose.data.SettingsBillingSnapshot
import ai.slock.compose.data.SettingsComputerAgentSnapshot
import ai.slock.compose.data.SettingsComputerDetailSnapshot
import ai.slock.compose.data.SettingsComputerSnapshot
import ai.slock.compose.data.SettingsComputerWorkspaceSnapshot
import ai.slock.compose.data.SettingsComputersSnapshot
import ai.slock.compose.data.SettingsMobileSnapshot
import ai.slock.compose.data.SettingsMobileToggleSnapshot
import ai.slock.compose.data.SettingsPlanUsageSnapshot
import ai.slock.compose.data.SettingsSectionAvailability
import ai.slock.compose.data.SettingsSnapshot
import ai.slock.compose.data.SettingsServerAdminSnapshot
import ai.slock.compose.data.SettingsServerOnboardingSnapshot
import ai.slock.compose.data.SettingsServerProfileSnapshot
import ai.slock.compose.data.SettingsServerSectionSnapshot
import ai.slock.compose.data.SettingsServerSnapshot
import ai.slock.compose.data.TaskSnapshot
import ai.slock.compose.data.ThreadSnapshot
import ai.slock.android.core.data.sample.AgentSampleData
import ai.slock.android.core.model.Attachment
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.AttachmentId
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ChannelId
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ConversationKind
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ConversationPermissions
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ConversationSummary
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.InboxItem
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.InboxItemKind
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.MemberId
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.MemberKind
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.MemberProfile
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.Message
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.MessageId
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.Presence
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ServerId
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ServerPermissions
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ServerSummary
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.SessionSnapshot
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.SettingsSummary
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.TaskId
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.TaskStatus
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.TaskSummary
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ThreadId
import ai.slock.compose.model.toMemberProfile

object MockSeedData {
    val server = ServerSummary(
        id = ServerId("server-slock-android"),
        name = "Slock Android",
        description = "Multi-agent Android workspace",
        unreadCount = 12,
        permissions = ServerPermissions(
            canCreateChannel = true,
            canInviteMember = true,
            canManageSettings = false
        )
    )

    val artin = MemberProfile(
        id = MemberId("member-artin"),
        displayName = "Artin",
        handle = "@artin",
        kind = MemberKind.Human,
        roleDescription = "Product owner",
        avatarLabel = "A",
        presence = Presence.Active,
        permissionBadges = listOf("owner")
    )

    val agentProfiles = AgentSampleData.agents

    val androidDeveloper = AgentSampleData.androidDeveloper.toMemberProfile()

    val apiDeveloper = AgentSampleData.apiIntegrationDeveloper.toMemberProfile()

    val qaTester = AgentSampleData.qaTester.toMemberProfile()

    val channels = listOf(
        ConversationSummary(
            threadId = ThreadId("thread-all"),
            channelId = ChannelId("channel-all"),
            kind = ConversationKind.Channel,
            name = "all",
            subtitle = "全员公告与项目同步",
            unreadCount = 3,
            mentionCount = 1,
            lastMessageId = MessageId("message-all-latest"),
            firstUnreadMessageId = MessageId("message-all-unread"),
            lastMessagePreview = "API/Data 测试阻塞已解除。",
            lastMessageAt = "14:00",
            lastMessageSenderName = "QA-Tester"
        ),
        ConversationSummary(
            threadId = ThreadId("thread-onboarding-artin"),
            channelId = ChannelId("channel-onboarding-artin"),
            kind = ConversationKind.Channel,
            name = "onboarding-artin",
            subtitle = "Phase 1 data/mock baseline, UI shell coordination, long mixed 中文 English text coverage",
            unreadCount = 8,
            mentionCount = 2,
            lastMessageId = MessageId("message-2"),
            firstUnreadMessageId = MessageId("message-1"),
            lastMessagePreview = "Contract baseline: models, ScreenLoadState, repository interfaces.",
            lastMessageAt = "13:56",
            lastMessageSenderName = "API-Integration-Developer"
        ),
        ConversationSummary(
            threadId = ThreadId("thread-locked"),
            channelId = ChannelId("channel-locked"),
            kind = ConversationKind.Channel,
            name = "release-readonly",
            subtitle = "Permission disabled sample",
            joined = false,
            permissions = ConversationPermissions(canSend = false, canAttach = false, canCreateTask = false)
        )
    )

    val directMessages = listOf(
        ConversationSummary(
            threadId = ThreadId("dm-android"),
            channelId = ChannelId("dm-android"),
            kind = ConversationKind.DirectMessage,
            name = "Android-Developer",
            subtitle = "Component/state integration",
            avatarUrl = "pixel:diamond",
            lastMessageId = MessageId("message-dm-latest"),
            lastMessagePreview = "ThreadUiState sample provider is ready.",
            lastMessageAt = "Now",
            lastMessageSenderName = "Android-Developer"
        )
    )

    val messages = listOf(
        Message(
            id = MessageId("message-1"),
            threadId = ThreadId("thread-onboarding-artin"),
            channelId = ChannelId("channel-onboarding-artin"),
            seq = 1001,
            author = artin,
            timestampLabel = "13:55",
            body = "现在开始实现不和 UI 冲突的数据层。This message intentionally mixes English and 中文 to verify wrapping."
        ),
        Message(
            id = MessageId("message-2"),
            threadId = ThreadId("thread-onboarding-artin"),
            channelId = ChannelId("channel-onboarding-artin"),
            seq = 1002,
            author = apiDeveloper,
            timestampLabel = "13:56",
            body = "Contract baseline: models, ScreenLoadState, repository interfaces, mock implementation, sample state provider, tests.",
            attachments = listOf(
                Attachment(
                    id = AttachmentId("attachment-contract"),
                    name = "phase1-contract.md",
                    mimeType = "text/markdown",
                    sizeLabel = "18 KB"
                )
            )
        ),
        Message(
            id = MessageId("message-failed-send"),
            threadId = ThreadId("thread-onboarding-artin"),
            channelId = ChannelId("channel-onboarding-artin"),
            seq = 1004,
            author = androidDeveloper,
            timestampLabel = "13:57",
            body = "Send failure sample for retry UI.",
            deliveryState = MessageDeliveryState.Failed
        )
    )

    val tasks = listOf(
        TaskSummary(
            id = TaskId("task-6"),
            title = "Android Phase 1: Build task/member/empty-error components with previews",
            status = TaskStatus.InProgress,
            taskNumber = 6,
            messageId = MessageId("message-task-6"),
            channelId = ChannelId("channel-onboarding-artin"),
            threadId = ThreadId("thread-onboarding-artin"),
            assignee = androidDeveloper,
            channelName = "onboarding-artin",
            updatedAtLabel = "13:55"
        ),
        TaskSummary(
            id = TaskId("task-9"),
            title = "API/Data Phase 1: Implement models, screen state contracts, repository interfaces, mock seed/repositories, and tests",
            status = TaskStatus.InProgress,
            taskNumber = 9,
            messageId = MessageId("message-task-9"),
            channelId = ChannelId("channel-onboarding-artin"),
            threadId = ThreadId("thread-onboarding-artin"),
            assignee = apiDeveloper,
            channelName = "onboarding-artin",
            updatedAtLabel = "13:56"
        ),
        TaskSummary(
            id = TaskId("task-review"),
            title = "Review Home/sidebar preview against product sketch",
            status = TaskStatus.Todo,
            taskNumber = 12,
            channelId = ChannelId("channel-onboarding-artin"),
            threadId = ThreadId("thread-onboarding-artin"),
            assignee = null,
            channelName = "android-dev",
            updatedAtLabel = "Today",
            canClaim = false
        )
    )

    val members = listOf(artin) + AgentSampleData.agentMembers

    val session = SessionSnapshot(
        currentServerId = server.id,
        currentMember = apiDeveloper,
        availableServers = listOf(server)
    )

    val serverListSnapshot = ServerListSnapshot(
        servers = listOf(server),
        selectedServer = server
    )

    val homeSnapshot = HomeSnapshot(
        server = server,
        currentMember = apiDeveloper,
        channels = channels,
        directMessages = directMessages,
        unreadTotal = channels.sumOf { it.unreadCount }
    )

    val threadSnapshot = ThreadSnapshot(
        conversation = channels[1],
        messages = messages,
        canLoadMoreBefore = true,
        historyLimited = false,
        highlightedMessageId = MessageId("message-2")
    )

    val taskSnapshot = TaskSnapshot(
        tasks = tasks,
        summaryCounts = TaskStatus.entries.associateWith { status -> tasks.count { it.status == status } }
    )

    val memberSnapshot = MemberSnapshot(
        server = server,
        members = members
    )

    val agentSnapshot = AgentSnapshot(
        server = server,
        agents = agentProfiles
    )

    val inboxItems: List<InboxItem> = listOf(
        InboxItem(
            key = "channel:channel-onboarding-artin",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-onboarding-artin"),
            channelName = "onboarding-artin",
            unreadCount = 8,
            firstUnreadMessageId = MessageId("message-1"),
            lastMessageId = MessageId("message-2"),
            lastMessagePreview = "Contract baseline: models, ScreenLoadState, repository interfaces.",
            lastMessageAt = "13:56",
            lastMessageSenderName = "API-Integration-Developer"
        ),
        InboxItem(
            key = "thread:thread-chat-detail",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("thread-chat-detail"),
            channelName = "Thread replies",
            unreadCount = 2,
            firstUnreadMessageId = MessageId("reply-1"),
            threadChannelId = ChannelId("thread-chat-detail"),
            parentChannelId = ChannelId("channel-android-dev"),
            parentChannelName = "android-dev",
            parentChannelKind = ConversationKind.Channel,
            parentMessageId = MessageId("parent-chat-detail"),
            parentMessagePreview = "聊天详情页在这里，请设计",
            latestActivityMessageId = MessageId("reply-2"),
            latestActivityPreview = "ThreadUiState 缺字段就补 contract，不绕 mock。",
            latestActivityAt = "14:05",
            latestActivitySenderName = "Cindy",
            replyCount = 5,
            participantIds = listOf(apiDeveloper.id, androidDeveloper.id),
            taskNumber = 7,
            taskStatus = TaskStatus.InProgress,
            taskClaimedByName = "Android-Developer"
        ),
        InboxItem(
            key = "dm:dm-android",
            kind = InboxItemKind.DirectMessage,
            channelId = ChannelId("dm-android"),
            channelName = "Android-Developer",
            unreadCount = 0,
            lastMessageId = MessageId("message-dm-latest"),
            lastMessagePreview = "ThreadUiState sample provider is ready.",
            lastMessageAt = "Now",
            lastMessageSenderName = "Android-Developer"
        )
    )

    val inboxSnapshot = InboxSnapshot(
        server = server,
        items = inboxItems,
        hasMore = true
    )

    val channelSnapshot = ChannelSnapshot(
        server = server,
        channels = channels
    )

    val settingsSnapshot = SettingsSnapshot(
        settings = SettingsSummary(
            server = server,
            notificationEnabled = true,
            compactModeEnabled = false,
            manageableSections = listOf("Profile", "Notifications")
        )
    )

    val accountSettingsSnapshot = AccountSettingsSnapshot(
        profile = AccountProfileSnapshot(
            userId = artin.id.value,
            displayName = artin.displayName,
            handle = artin.handle,
            email = "artin@slock.ai",
            avatarLabel = artin.avatarLabel,
            verified = true,
            roleLabel = "Owner"
        ),
        connectedAccounts = listOf(
            ConnectedAccountSnapshot(
                provider = "Google",
                identifier = "artin@slock.ai",
                status = ConnectedAccountStatus.Connected
            ),
            ConnectedAccountSnapshot(
                provider = "GitHub",
                identifier = "github.com/artin",
                status = ConnectedAccountStatus.Connected
            )
        )
    )

    val settingsComputersSnapshot = SettingsComputersSnapshot(
        computers = listOf(
            SettingsComputerSnapshot(
                id = "computer-macbook",
                name = "Jiachengs-MacBook-Pro.local",
                daemonVersion = "daemon v0.44.2",
                online = true,
                hostname = "Jiachengs-MacBook-Pro.local",
                platform = "macOS",
                lastSeenLabel = "Active now"
            )
        ),
        addComputerUnavailableReason = "Computer management API is not wired on Android yet."
    )

    val settingsComputerDetailSnapshot = SettingsComputerDetailSnapshot(
        computer = settingsComputersSnapshot.computers.first(),
        workspaces = listOf(
            SettingsComputerWorkspaceSnapshot(
                id = "workspace-slock",
                name = "Slock",
                path = "~/AndroidStudioProjects/Slock",
                statusLabel = "active",
                lastActiveLabel = "Active now"
            )
        ),
        agents = listOf(
            SettingsComputerAgentSnapshot(
                id = "agent-settings",
                name = "Android-Settings-Integrator",
                statusLabel = "active",
                ownerLabel = "Settings",
                memberProfile = ai.slock.android.core.model.MemberProfile(
                    id = ai.slock.android.core.model.MemberId("agent-settings"),
                    displayName = "Android-Settings-Integrator",
                    handle = "@Android-Settings-Integrator",
                    kind = ai.slock.android.core.model.MemberKind.Agent,
                    description = "Settings real-data wiring and acceptance",
                    roleDescription = "agent",
                    avatarUrl = "random:android-settings-integrator",
                    avatarLabel = "A",
                    presence = ai.slock.android.core.model.Presence.Active,
                    agentStatus = ai.slock.android.core.model.AgentStatus.Online
                )
            )
        ),
        sections = listOf(
            SettingsServerSectionSnapshot(
                key = "machine_status_events",
                title = "Status Events",
                detail = "Realtime status events are available; REST history is unavailable."
            ),
            SettingsServerSectionSnapshot(
                key = "machine_logs",
                title = "Logs",
                detail = "Machine logs endpoint is not confirmed."
            )
        )
    )

    val settingsServerSnapshot = SettingsServerSnapshot(
        profile = SettingsServerProfileSnapshot(
            name = server.name,
            slug = "slock-android"
        ),
        sections = listOf(
            SettingsServerSectionSnapshot(
                key = "admins",
                title = "Admins",
                detail = "1 admin",
                state = SettingsSectionAvailability.Available
            ),
            SettingsServerSectionSnapshot(
                key = "billing",
                title = "Plan & Billing",
                detail = "Current plan: Hobby. Usage rows: 3.",
                state = SettingsSectionAvailability.Available
            ),
            SettingsServerSectionSnapshot(
                key = "pending_invites",
                title = "Pending Invites",
                detail = "Invite creation is confirmed; no pending-invite listing endpoint in upstream source.",
                state = SettingsSectionAvailability.Empty
            ),
            SettingsServerSectionSnapshot(
                key = "join_links",
                title = "Join Links",
                detail = "Upstream source accepts join-link tokens, but no Settings join-link management endpoint is confirmed.",
                state = SettingsSectionAvailability.Empty
            ),
            SettingsServerSectionSnapshot(
                key = "onboarding_agent",
                title = "Onboarding Agent",
                detail = "The onboarding setup reminder can appear when the server has no computer or agent.",
                state = SettingsSectionAvailability.Available
            ),
            SettingsServerSectionSnapshot(
                key = "danger",
                title = "Danger Zone",
                detail = "Delete Server is disabled in this Android build.",
                state = SettingsSectionAvailability.NoPermission
            )
        ),
        admins = listOf(
            SettingsServerAdminSnapshot(
                id = "admin-1",
                name = "Slock Admin",
                detail = "admin@slock.ai",
                role = "Admin"
            )
        ),
        usageRows = listOf(
            SettingsPlanUsageSnapshot("Computers", "1 / Unlimited"),
            SettingsPlanUsageSnapshot("Agents", "10 / Unlimited"),
            SettingsPlanUsageSnapshot("Channels", "12 / Unlimited")
        ),
        onboarding = SettingsServerOnboardingSnapshot(
            setupModalReminderOptOut = false,
            statusLabel = "Reminder active",
            detail = "The onboarding setup reminder can appear when the server has no computer or agent."
        )
    )

    val settingsBillingSnapshot = SettingsBillingSnapshot(
        currentPlan = SettingsBillingCurrentPlanSnapshot(
            planName = "Hobby",
            trialActive = true,
            usageRows = listOf(
                SettingsPlanUsageSnapshot("Messages", "4,120 / 10,000"),
                SettingsPlanUsageSnapshot("Members", "8 / 20"),
                SettingsPlanUsageSnapshot("AI agents", "5 active")
            )
        ),
        plans = listOf(
            SettingsBillingPlanSnapshot(
                name = "Hobby",
                price = "$0 / month",
                features = listOf("Core chat and task workflows", "Up to 20 members", "Local Android preview support"),
                current = true
            ),
            SettingsBillingPlanSnapshot(
                name = "Team",
                price = "$29 / month",
                features = listOf("Shared team spaces", "More AI agent capacity", "Priority workspace controls")
            ),
            SettingsBillingPlanSnapshot(
                name = "Business",
                price = "Custom",
                features = listOf("Advanced audit and billing controls", "Dedicated workspace support"),
                comingSoon = true
            )
        )
    )

    val settingsMobileSnapshot = SettingsMobileSnapshot(
        pushNotifications = SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Disabled",
            detail = "Notifications can arrive while Slock is in the background. This Android build can preview the local state before the push API is connected."
        ),
        muteServer = SettingsMobileToggleSnapshot(
            enabled = false,
            available = false,
            statusLabel = "Not muted",
            detail = "Server mute is unavailable until the mobile settings endpoint is confirmed."
        ),
        sendTestEnabled = true
    )
}
