package ai.slock.android.feature.sample

import ai.slock.android.core.data.sample.AgentSampleData
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.SettingsSummary
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.channels.ChannelsContent
import ai.slock.android.feature.channels.ChannelsUiState
import ai.slock.android.feature.home.HomeContent
import ai.slock.android.feature.home.HomeUiState
import ai.slock.android.feature.settings.SettingsContent
import ai.slock.android.feature.settings.SettingsUiState
import ai.slock.android.feature.tasks.TasksContent
import ai.slock.android.feature.tasks.TasksUiState
import ai.slock.compose.model.toMemberProfile

object SlockStateSamples {
    private val server = ServerSummary(
        id = ServerId("sample-server"),
        name = "Raft Android",
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

    val tasksContent = TasksUiState(
        loadState = ScreenLoadState.Content(
            TasksContent(
                summaryCounts = mapOf(TaskStatus.InProgress to 1),
                tasks = listOf(task)
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

    val tasksBranches = listOf(
        TasksUiState(ScreenLoadState.Loading),
        tasksContent,
        TasksUiState(ScreenLoadState.Empty(EmptyReason.NoTasks)),
        TasksUiState(error),
        TasksUiState(noPermission)
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
