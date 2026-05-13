package ai.slock.android.feature.sample

import ai.slock.android.core.data.sample.AgentSampleData
import ai.slock.android.core.model.AttachmentId
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ChannelId
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.ConversationKind
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
import ai.slock.android.core.model.ServerSummary
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
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.channels.ChannelsContent
import ai.slock.android.feature.channels.ChannelsUiState
import ai.slock.android.feature.home.HomeContent
import ai.slock.android.feature.home.HomeUiState
import ai.slock.android.feature.inbox.InboxContent
import ai.slock.android.feature.inbox.InboxUiState
import ai.slock.android.feature.members.MembersContent
import ai.slock.android.feature.members.MembersUiState
import ai.slock.android.feature.settings.SettingsContent
import ai.slock.android.feature.settings.SettingsUiState
import ai.slock.android.feature.tasks.TasksContent
import ai.slock.android.feature.tasks.TasksUiState
import ai.slock.android.feature.thread.ComposerState
import ai.slock.android.feature.thread.ComposerStatus
import ai.slock.android.feature.thread.ThreadContent
import ai.slock.android.feature.thread.ThreadUiState

object SlockStateSamples {
    private val server = ServerSummary(
        id = ServerId("sample-server"),
        name = "Slock Android",
        unreadCount = 7
    )

    private val member = AgentSampleData.apiIntegrationDeveloper.toMemberProfile()

    private val channel = ConversationSummary(
        threadId = ThreadId("sample-thread"),
        channelId = ChannelId("sample-channel"),
        kind = ConversationKind.Channel,
        name = "onboarding-artin",
        subtitle = "Long preview text with English and 中文 mixed for layout coverage",
        unreadCount = 4,
        mentionCount = 1,
        lastMessageId = MessageId("sample-message"),
        firstUnreadMessageId = MessageId("sample-message"),
        lastMessagePreview = "Sample state provider message. 这条消息用于 preview。",
        lastMessageAt = "13:57",
        lastMessageSenderName = "API-Integration-Developer"
    )

    private val messages = listOf(
        Message(
            id = MessageId("sample-message"),
            threadId = channel.threadId,
            channelId = channel.channelId,
            seq = 20,
            author = member,
            timestampLabel = "13:57",
            body = "Sample state provider message. 这条消息用于 preview，不依赖 mock repository。"
        ),
        Message(
            id = MessageId("sample-failed"),
            threadId = channel.threadId,
            channelId = channel.channelId,
            seq = 21,
            author = member,
            timestampLabel = "13:58",
            body = "Failed send sample",
            deliveryState = MessageDeliveryState.Failed
        )
    )

    private val task = TaskSummary(
        id = TaskId("sample-task"),
        title = "Implement data contract sample states",
        status = TaskStatus.InProgress,
        taskNumber = 9,
        messageId = MessageId("sample-message"),
        assignee = member,
        channelName = "onboarding-artin",
        updatedAtLabel = "Now"
    )

    private val inboxItems = listOf(
        InboxItem(
            key = "channel:${channel.channelId?.value}",
            kind = InboxItemKind.Channel,
            channelId = channel.channelId ?: ChannelId("sample-channel"),
            channelName = channel.name,
            unreadCount = 4,
            firstUnreadMessageId = MessageId("sample-message"),
            lastMessageId = MessageId("sample-message"),
            lastMessagePreview = channel.lastMessagePreview,
            lastMessageAt = channel.lastMessageAt,
            lastMessageSenderName = channel.lastMessageSenderName
        ),
        InboxItem(
            key = "thread:sample-thread-replies",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("sample-thread-replies"),
            channelName = "Thread replies",
            unreadCount = 2,
            firstUnreadMessageId = MessageId("sample-reply-1"),
            threadChannelId = ChannelId("sample-thread-replies"),
            parentChannelId = channel.channelId,
            parentChannelName = channel.name,
            parentChannelKind = ConversationKind.Channel,
            parentMessageId = MessageId("sample-parent-message"),
            parentMessagePreview = "聊天详情页在这里，请设计",
            latestActivityMessageId = MessageId("sample-reply-2"),
            latestActivityPreview = "UI 只消费 ThreadUiState，不直接接 socket。",
            latestActivityAt = "Now",
            latestActivitySenderName = "Cindy",
            replyCount = 3,
            participantIds = listOf(member.id),
            taskNumber = 7,
            taskStatus = TaskStatus.InProgress,
            taskClaimedByName = "Android-Developer"
        ),
        InboxItem(
            key = "channel:product",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("sample-product"),
            channelName = "product",
            unreadCount = 1,
            firstUnreadMessageId = MessageId("sample-product-message"),
            lastMessageId = MessageId("sample-product-message"),
            lastMessagePreview = "Inbox list needs to match the design density before real data arrives.",
            lastMessageAt = "18:13",
            lastMessageSenderName = "Product-UX-Designer"
        ),
        InboxItem(
            key = "thread:workspace-preview",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("sample-workspace-thread"),
            channelName = "Workspace preview",
            unreadCount = 0,
            threadChannelId = ChannelId("sample-workspace-thread"),
            parentChannelId = ChannelId("sample-android-dev"),
            parentChannelName = "android-dev",
            parentChannelKind = ConversationKind.Channel,
            parentMessageId = MessageId("sample-workspace-parent"),
            parentMessagePreview = "Workspace tab needs a file tree and previews.",
            latestActivityMessageId = MessageId("sample-workspace-reply"),
            latestActivityPreview = "Markdown and image preview sample states are ready for QA.",
            latestActivityAt = "17:44",
            latestActivitySenderName = "Android-Developer",
            replyCount = 5,
            participantIds = listOf(member.id)
        ),
        InboxItem(
            key = "channel:qa",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("sample-qa-channel"),
            channelName = "qa",
            unreadCount = 0,
            lastMessageId = MessageId("sample-qa-message"),
            lastMessagePreview = "Full Gradle verification passed after the latest visual polish.",
            lastMessageAt = "16:55",
            lastMessageSenderName = "QA-Tester"
        ),
        InboxItem(
            key = "thread:auth-contract",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("sample-auth-thread"),
            channelName = "Auth contract",
            unreadCount = 0,
            threadChannelId = ChannelId("sample-auth-thread"),
            parentChannelId = ChannelId("sample-android-dev"),
            parentChannelName = "android-dev",
            parentChannelKind = ConversationKind.Channel,
            parentMessageId = MessageId("sample-auth-parent"),
            parentMessagePreview = "Research Web login flow and wire real token session.",
            latestActivityMessageId = MessageId("sample-auth-reply"),
            latestActivityPreview = "AuthRepository and secure session wiring are now split into phase two.",
            latestActivityAt = "18:02",
            latestActivitySenderName = "API-Integration-Developer",
            replyCount = 2,
            participantIds = listOf(member.id),
            taskNumber = 80,
            taskStatus = TaskStatus.InReview
        )
    )

    val homeContent = HomeUiState(
        loadState = ScreenLoadState.Content(
            HomeContent(
                server = server,
                currentMember = member,
                channels = listOf(channel),
                directMessages = emptyList(),
                unreadTotal = 4
            )
        ),
        selectedServerId = server.id.value,
        selectedThreadId = channel.threadId.value
    )

    val threadContent = ThreadUiState(
        loadState = ScreenLoadState.Content(
            ThreadContent(
                header = channel,
                messages = messages,
                tasks = listOf(task),
                canLoadMoreBefore = true,
                hasGap = false,
                historyLimited = false,
                newUpdateCount = 2,
                highlightedMessageId = MessageId("sample-message")
            )
        ),
        composerState = ComposerState(status = ComposerStatus.IdleEmpty)
    )

    val composerIdleEmpty = threadContent.copy(
        composerState = ComposerState(status = ComposerStatus.IdleEmpty)
    )

    val composerReady = threadContent.copy(
        composerState = ComposerState(value = "Ready to send", status = ComposerStatus.Ready)
    )

    val composerUploading = threadContent.copy(
        composerState = ComposerState(
            value = "Uploading attachment",
            status = ComposerStatus.Uploading,
            attachmentIds = listOf(AttachmentId("sample-uploading"))
        )
    )

    val composerSending = threadContent.copy(
        composerState = ComposerState(value = "Sending message", status = ComposerStatus.Sending)
    )

    val composerFailed = threadContent.copy(
        composerState = ComposerState(
            value = "Retry this message",
            status = ComposerStatus.Failed,
            errorCode = "send_failed"
        )
    )

    val composerNoPermission = threadContent.copy(
        composerState = ComposerState(
            enabled = false,
            status = ComposerStatus.NoPermission,
            noPermissionCode = "channel_readonly"
        )
    )

    val composerBranches = listOf(
        composerIdleEmpty,
        composerReady,
        composerUploading,
        composerSending,
        composerFailed,
        composerNoPermission
    )

    val tasksContent = TasksUiState(
        loadState = ScreenLoadState.Content(
            TasksContent(
                summaryCounts = mapOf(TaskStatus.InProgress to 1),
                tasks = listOf(task)
            )
        )
    )

    val membersContent = MembersUiState(
        loadState = ScreenLoadState.Content(
            MembersContent(
                server = server,
                members = listOf(
                    MemberProfile(
                        id = MemberId("sample-artin"),
                        displayName = "artin",
                        handle = "@artin",
                        kind = MemberKind.Human,
                        roleDescription = "Product owner",
                        avatarUrl = "https://cdn.example.com/artin.png",
                        avatarLabel = "A",
                        presence = Presence.Active
                    )
                ) + AgentSampleData.agentMembers
            )
        )
    )

    val inboxContent = InboxUiState(
        loadState = ScreenLoadState.Content(
            InboxContent(
                server = server,
                items = inboxItems,
                totalCount = 102,
                totalUnreadCount = 7,
                hasMore = false
            )
        )
    )

    val channelsContent = ChannelsUiState(
        loadState = ScreenLoadState.Content(ChannelsContent(server, listOf(channel)))
    )

    val settingsContent = SettingsUiState(
        loadState = ScreenLoadState.Content(
            SettingsContent(
                SettingsSummary(
                    server = server,
                    notificationEnabled = true,
                    compactModeEnabled = false,
                    manageableSections = listOf("Profile", "Notifications")
                )
            )
        )
    )

    val loading = ScreenLoadState.Loading
    val empty = ScreenLoadState.Empty(EmptyReason.NoSearchResults)
    val error = ScreenLoadState.Error(ScreenError(code = "sample_error", retryable = true))
    val noPermission = ScreenLoadState.NoPermission(
        PermissionBlocker(code = "sample_no_permission", requiredCapability = "send_message")
    )

    val homeBranches = listOf(
        HomeUiState(ScreenLoadState.Loading),
        homeContent,
        HomeUiState(ScreenLoadState.Empty(EmptyReason.NoChannels)),
        HomeUiState(error),
        HomeUiState(noPermission)
    )

    val threadBranches = listOf(
        ThreadUiState(ScreenLoadState.Loading),
        threadContent,
        ThreadUiState(ScreenLoadState.Empty(EmptyReason.NoMessages)),
        ThreadUiState(error),
        ThreadUiState(noPermission)
    )

    val tasksBranches = listOf(
        TasksUiState(ScreenLoadState.Loading),
        tasksContent,
        TasksUiState(ScreenLoadState.Empty(EmptyReason.NoTasks)),
        TasksUiState(error),
        TasksUiState(noPermission)
    )

    val membersBranches = listOf(
        MembersUiState(ScreenLoadState.Loading),
        membersContent,
        MembersUiState(ScreenLoadState.Empty(EmptyReason.NoMembers)),
        MembersUiState(error),
        MembersUiState(noPermission)
    )

    val inboxBranches = listOf(
        InboxUiState(ScreenLoadState.Loading),
        inboxContent,
        InboxUiState(ScreenLoadState.Empty(EmptyReason.NoInboxItems)),
        InboxUiState(error),
        InboxUiState(noPermission)
    )

    val channelsBranches = listOf(
        ChannelsUiState(ScreenLoadState.Loading),
        channelsContent,
        ChannelsUiState(ScreenLoadState.Empty(EmptyReason.NoChannels)),
        ChannelsUiState(error),
        ChannelsUiState(noPermission)
    )

    val settingsBranches = listOf(
        SettingsUiState(ScreenLoadState.Loading),
        settingsContent,
        SettingsUiState(ScreenLoadState.Empty(EmptyReason.NoSettingsAvailable)),
        SettingsUiState(error),
        SettingsUiState(noPermission)
    )
}
