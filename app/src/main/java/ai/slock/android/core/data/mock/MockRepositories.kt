package ai.slock.android.core.data.mock

import ai.slock.android.core.data.repository.AuthSessionRepository
import ai.slock.android.core.data.repository.AgentRepository
import ai.slock.android.core.data.repository.AgentSnapshot
import ai.slock.android.core.data.repository.AccountProfileUpdateRequest
import ai.slock.android.core.data.repository.AccountSettingsSnapshot
import ai.slock.android.core.data.repository.ChannelSnapshot
import ai.slock.android.core.data.repository.ConversationRepository
import ai.slock.android.core.data.repository.CreateServerRequest
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.MemberRepository
import ai.slock.android.core.data.repository.MemberSnapshot
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.ServerListSnapshot
import ai.slock.android.core.data.repository.ServerRepository
import ai.slock.android.core.data.repository.SessionActionResult
import ai.slock.android.core.data.repository.SessionSettingsSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsRepository
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.core.data.repository.SettingsSnapshot
import ai.slock.android.core.data.repository.TaskActionResult
import ai.slock.android.core.data.repository.TaskFilter
import ai.slock.android.core.data.repository.TaskRepository
import ai.slock.android.core.data.repository.TaskSnapshot
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.SessionSnapshot
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError

class MockAuthSessionRepository(
    private val scenario: MockScenario = MockScenario.Content
) : AuthSessionRepository {
    override suspend fun currentSession(): DataResult<SessionSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.session }
}

class MockServerRepository(
    private val scenario: MockScenario = MockScenario.Content
) : ServerRepository {
    override suspend fun getServers(): DataResult<ServerListSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.serverListSnapshot }

    override suspend fun selectServer(serverId: ServerId): DataResult<ServerListSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            MockSeedData.serverListSnapshot.copy(
                selectedServer = MockSeedData.serverListSnapshot.servers.firstOrNull { it.id == serverId }
                    ?: MockSeedData.serverListSnapshot.selectedServer
            )
        }

    override suspend fun createServer(request: CreateServerRequest): DataResult<ServerListSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.serverListSnapshot }

    override suspend fun getHomeSnapshot(serverId: ServerId): DataResult<HomeSnapshot> =
        resultFor(scenario, EmptyReason.NoChannels) { MockSeedData.homeSnapshot }

    override suspend fun getInbox(serverId: ServerId): DataResult<InboxSnapshot> =
        resultFor(scenario, EmptyReason.NoInboxItems) { MockSeedData.inboxSnapshot }

    override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> =
        resultFor(scenario, EmptyReason.NoChannels) { MockSeedData.channelSnapshot }

    override suspend fun getSettings(serverId: ServerId): DataResult<SettingsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.settingsSnapshot }
}

class MockSettingsRepository(
    private val scenario: MockScenario = MockScenario.Content
) : SettingsRepository {
    override suspend fun getAccount(): DataResult<AccountSettingsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.accountSettingsSnapshot }

    override suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            MockSeedData.accountSettingsSnapshot.copy(
                profile = MockSeedData.accountSettingsSnapshot.profile.copy(
                    displayName = request.displayName,
                    handle = request.handle,
                    email = request.email
                )
            )
        }

    override suspend fun getSessions(): DataResult<SessionSettingsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.sessionSettingsSnapshot }

    override suspend fun revokeSession(sessionId: String): DataResult<SessionActionResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SessionActionResult(sessionId = sessionId, revoked = true)
        }

    override suspend fun getComputers(): DataResult<SettingsComputersSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.settingsComputersSnapshot }

    override suspend fun getServerSettings(): DataResult<SettingsServerSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.settingsServerSnapshot }

    override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.settingsMobileSnapshot }
}

class MockConversationRepository(
    private val scenario: MockScenario = MockScenario.Content
) : ConversationRepository {
    override suspend fun getThread(threadId: ThreadId): DataResult<ThreadSnapshot> =
        resultFor(scenario, EmptyReason.NoMessages) { MockSeedData.threadSnapshot }

    override suspend fun getMessageContext(messageId: MessageId): DataResult<ThreadSnapshot> =
        resultFor(scenario, EmptyReason.NoMessages) {
            MockSeedData.threadSnapshot.copy(highlightedMessageId = messageId)
        }

    override suspend fun loadOlderMessages(threadId: ThreadId, beforeSeq: Long): DataResult<ThreadSnapshot> =
        resultFor(scenario, EmptyReason.NoMessages) {
            MockSeedData.threadSnapshot.copy(canLoadMoreBefore = beforeSeq > 1000)
        }

    override suspend fun loadNewerMessages(threadId: ThreadId, afterSeq: Long): DataResult<ThreadSnapshot> =
        resultFor(scenario, EmptyReason.NoMessages) {
            MockSeedData.threadSnapshot.copy(canLoadMoreAfter = afterSeq < 1004)
        }

    override suspend fun sendMessage(
        threadId: ThreadId,
        body: String,
        attachmentIds: List<AttachmentId>,
        asTask: Boolean
    ): DataResult<SendMessageResult> =
        when {
            scenario == MockScenario.NoPermission -> noPermissionResult()
            scenario == MockScenario.Error || body.contains("fail", ignoreCase = true) -> errorResult("send_failed")
            else -> DataResult.Content(
                SendMessageResult(
                    Message(
                        id = MessageId("message-local-${body.hashCode()}"),
                        threadId = threadId,
                        channelId = ChannelId(threadId.value),
                        seq = MockSeedData.threadSnapshot.lastSeq + 1,
                        author = MockSeedData.apiDeveloper,
                        timestampLabel = "Now",
                        body = body,
                        deliveryState = MessageDeliveryState.Sent,
                        task = if (asTask) MockSeedData.tasks.first() else null
                    )
                )
            )
        }
}

class MockTaskRepository(
    private val scenario: MockScenario = MockScenario.Content
) : TaskRepository {
    override suspend fun getTasks(serverId: ServerId, filter: TaskFilter): DataResult<TaskSnapshot> {
        val snapshot = if (filter.statuses.isEmpty()) {
            MockSeedData.taskSnapshot
        } else {
            val tasks = MockSeedData.tasks.filter { it.status in filter.statuses }
            TaskSnapshot(
                tasks = tasks,
                summaryCounts = TaskStatus.entries.associateWith { status -> tasks.count { it.status == status } }
            )
        }
        return resultFor(scenario, EmptyReason.NoTasks) { snapshot }
    }

    override suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult> =
        resultFor(scenario, EmptyReason.NoTasks) {
            TaskActionResult(
                MockSeedData.tasks.firstOrNull { it.id == taskId } ?: MockSeedData.tasks.first()
            )
        }
}

class MockMemberRepository(
    private val scenario: MockScenario = MockScenario.Content
) : MemberRepository {
    override suspend fun getMembers(serverId: ServerId): DataResult<MemberSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) { MockSeedData.memberSnapshot }
}

class MockAgentRepository(
    private val scenario: MockScenario = MockScenario.Content
) : AgentRepository {
    override suspend fun getAgents(serverId: ServerId): DataResult<AgentSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) { MockSeedData.agentSnapshot }
}

private inline fun <T> resultFor(
    scenario: MockScenario,
    emptyReason: EmptyReason,
    content: () -> T
): DataResult<T> =
    when (scenario) {
        MockScenario.Content -> DataResult.Content(content())
        MockScenario.Empty -> DataResult.Empty(emptyReason)
        MockScenario.Error -> errorResult()
        MockScenario.NoPermission -> noPermissionResult()
    }

private fun <T> errorResult(code: String = "mock_error"): DataResult<T> =
    DataResult.Error(ScreenError(code = code, retryable = true))

private fun <T> noPermissionResult(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "mock_no_permission", requiredCapability = "mock_access"))
