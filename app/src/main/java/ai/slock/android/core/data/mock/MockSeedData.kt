package ai.slock.android.core.data.mock

import ai.slock.android.core.data.repository.AgentSnapshot
import ai.slock.android.core.data.repository.AccountProfileSnapshot
import ai.slock.android.core.data.repository.AccountSettingsSnapshot
import ai.slock.android.core.data.repository.ChannelSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountStatus
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.MemberSnapshot
import ai.slock.android.core.data.repository.ServerListSnapshot
import ai.slock.android.core.data.repository.SessionSettingsSnapshot
import ai.slock.android.core.data.repository.SettingsComputerSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsMobileToggleSnapshot
import ai.slock.android.core.data.repository.SettingsSectionAvailability
import ai.slock.android.core.data.repository.SettingsSnapshot
import ai.slock.android.core.data.repository.SettingsServerProfileSnapshot
import ai.slock.android.core.data.repository.SettingsServerSectionSnapshot
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.core.data.repository.SettingsSessionSnapshot
import ai.slock.android.core.data.repository.TaskSnapshot
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.data.sample.AgentSampleData
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationPermissions
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerPermissions
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.SessionSnapshot
import ai.slock.android.core.model.SettingsSummary
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId

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
            assignee = apiDeveloper,
            channelName = "onboarding-artin",
            updatedAtLabel = "13:56"
        ),
        TaskSummary(
            id = TaskId("task-review"),
            title = "Review Home/sidebar preview against product sketch",
            status = TaskStatus.Todo,
            taskNumber = 12,
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

    val inboxItems = listOf(
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

    val sessionSettingsSnapshot = SessionSettingsSnapshot(
        sessions = listOf(
            SettingsSessionSnapshot(
                id = "session-current",
                deviceName = "Android emulator",
                browserLabel = "Slock Android",
                locationLabel = "Current device",
                lastActiveLabel = "Active now",
                current = true,
                revokeEnabled = false
            ),
            SettingsSessionSnapshot(
                id = "session-web",
                deviceName = "MacBook Pro",
                browserLabel = "Chrome",
                locationLabel = "Recent login",
                lastActiveLabel = "14 minutes ago"
            )
        )
    )

    val settingsComputersSnapshot = SettingsComputersSnapshot(
        computers = listOf(
            SettingsComputerSnapshot(
                id = "computer-macbook",
                name = "Jiachengs-MacBook-Pro.local",
                daemonVersion = "daemon v0.44.2",
                online = true
            )
        ),
        addComputerUnavailableReason = "Computer management API is not wired on Android yet."
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
                detail = "Admin management endpoint is not wired on Android yet."
            ),
            SettingsServerSectionSnapshot(
                key = "billing",
                title = "Plan & Billing",
                detail = "Billing settings are unavailable until the contract is confirmed."
            ),
            SettingsServerSectionSnapshot(
                key = "pending_invites",
                title = "Pending Invites",
                detail = "Invite listing is unavailable until the contract is confirmed."
            ),
            SettingsServerSectionSnapshot(
                key = "join_links",
                title = "Join Links",
                detail = "Join link management is unavailable until the contract is confirmed."
            ),
            SettingsServerSectionSnapshot(
                key = "onboarding_agent",
                title = "Onboarding Agent",
                detail = "Onboarding agent settings are not wired on Android yet."
            ),
            SettingsServerSectionSnapshot(
                key = "danger",
                title = "Danger Zone",
                detail = "Delete Server remains disabled until endpoint and owner permission contracts are confirmed.",
                state = SettingsSectionAvailability.NoPermission
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
