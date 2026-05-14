package ai.slock.android.core.data.mock

import ai.slock.android.core.data.repository.AuthSessionRepository
import ai.slock.android.core.data.repository.AgentRepository
import ai.slock.android.core.data.repository.AgentActivityEntry
import ai.slock.android.core.data.repository.AgentActivitySnapshot
import ai.slock.android.core.data.repository.AgentDetailSnapshot
import ai.slock.android.core.data.repository.AgentDmSnapshot
import ai.slock.android.core.data.repository.AgentDmSummary
import ai.slock.android.core.data.repository.AgentProfileUpdateRequest
import ai.slock.android.core.data.repository.AgentSnapshot
import ai.slock.android.core.data.repository.AccountProfileUpdateRequest
import ai.slock.android.core.data.repository.AccountSettingsSnapshot
import ai.slock.android.core.data.repository.AgentReminderSnapshot
import ai.slock.android.core.data.repository.AgentReminderSummary
import ai.slock.android.core.data.repository.AgentSkillGroup
import ai.slock.android.core.data.repository.AgentSkillPathGroup
import ai.slock.android.core.data.repository.AgentSkillSummary
import ai.slock.android.core.data.repository.AgentSkillsSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceChildrenSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceFilePreviewSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceTreeSnapshot
import ai.slock.android.core.data.repository.AttachmentOpenTarget
import ai.slock.android.core.data.repository.ChannelFilesSnapshot
import ai.slock.android.core.data.repository.AttachmentUploadRequest
import ai.slock.android.core.data.repository.AttachmentUploadResult
import ai.slock.android.core.data.repository.AddChannelMemberRequest
import ai.slock.android.core.data.repository.ChannelMemberActionResult
import ai.slock.android.core.data.repository.ChannelMemberSnapshot
import ai.slock.android.core.data.repository.ChannelArchiveResult
import ai.slock.android.core.data.repository.ChannelDeleteResult
import ai.slock.android.core.data.repository.ChannelJoinResult
import ai.slock.android.core.data.repository.ChannelSnapshot
import ai.slock.android.core.data.repository.ChannelUnreadSnapshot
import ai.slock.android.core.data.repository.ConversationRepository
import ai.slock.android.core.data.repository.CreateChannelRequest
import ai.slock.android.core.data.repository.CreateChannelResult
import ai.slock.android.core.data.repository.CreateServerRequest
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.DirectMessageSnapshot
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.MemberRepository
import ai.slock.android.core.data.repository.MemberSnapshot
import ai.slock.android.core.data.repository.OpenDirectMessageResult
import ai.slock.android.core.data.repository.ReadReceiptRepository
import ai.slock.android.core.data.repository.ReadReceiptResult
import ai.slock.android.core.data.repository.RemoveChannelMemberRequest
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.ServerListSnapshot
import ai.slock.android.core.data.repository.ServerRepository
import ai.slock.android.core.data.repository.SessionActionResult
import ai.slock.android.core.data.repository.SessionSettingsSnapshot
import ai.slock.android.core.data.repository.SettingsComputerDetailSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsBillingCurrentPlanSnapshot
import ai.slock.android.core.data.repository.SettingsBillingPlanSnapshot
import ai.slock.android.core.data.repository.SettingsBillingSnapshot
import ai.slock.android.core.data.repository.SettingsPlanUsageSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsComputerWorkspaceSnapshot
import ai.slock.android.core.data.repository.SettingsRepository
import ai.slock.android.core.data.repository.SettingsServerActionResult
import ai.slock.android.core.data.repository.SettingsServerDeleteRequest
import ai.slock.android.core.data.repository.SettingsServerInviteCreateRequest
import ai.slock.android.core.data.repository.SettingsServerProfileUpdateRequest
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.core.data.repository.SettingsSnapshot
import ai.slock.android.core.data.repository.TaskActionResult
import ai.slock.android.core.data.repository.TaskFilter
import ai.slock.android.core.data.repository.TaskRepository
import ai.slock.android.core.data.repository.TaskSnapshot
import ai.slock.android.core.data.repository.ThreadActionResult
import ai.slock.android.core.data.repository.ThreadListSnapshot
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
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
import ai.slock.compose.model.SlockWorkspaceFileKind
import ai.slock.compose.model.SlockWorkspaceFilePreview
import ai.slock.compose.model.SlockWorkspaceNode
import ai.slock.compose.model.topLevelThreadId
import ai.slock.compose.network.SlockRequestPriority

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

    override suspend fun getComputerDetail(machineId: String): DataResult<SettingsComputerDetailSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            MockSeedData.settingsComputerDetailSnapshot.copy(
                computer = MockSeedData.settingsComputerDetailSnapshot.computer.copy(id = machineId)
            )
        }

    override suspend fun getBilling(): DataResult<SettingsBillingSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsBillingSnapshot(
                currentPlan = SettingsBillingCurrentPlanSnapshot(
                    planName = "Team",
                    usageRows = listOf(
                        SettingsPlanUsageSnapshot(label = "Active members", value = "12"),
                        SettingsPlanUsageSnapshot(label = "Messages this month", value = "4.2k")
                    ),
                    trialActive = true
                ),
                plans = listOf(
                    SettingsBillingPlanSnapshot(
                        name = "Starter",
                        price = "$0",
                        features = listOf("Core chat", "Workspace members")
                    ),
                    SettingsBillingPlanSnapshot(
                        name = "Team",
                        price = "$12 / user",
                        features = listOf("Advanced permissions", "Billing controls"),
                        current = true
                    ),
                    SettingsBillingPlanSnapshot(
                        name = "Enterprise",
                        price = "Contact sales",
                        features = listOf("SAML", "Audit log", "Priority support"),
                        comingSoon = true
                    )
                )
            )
        }

    override suspend fun getServerSettings(): DataResult<SettingsServerSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.settingsServerSnapshot }

    override suspend fun updateServerProfile(
        request: SettingsServerProfileUpdateRequest
    ): DataResult<SettingsServerSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            MockSeedData.settingsServerSnapshot.copy(
                profile = MockSeedData.settingsServerSnapshot.profile.copy(name = request.name)
            )
        }

    override suspend fun createServerInvite(
        request: SettingsServerInviteCreateRequest
    ): DataResult<SettingsServerActionResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsServerActionResult(success = true, message = request.email)
        }

    override suspend fun deleteServer(
        request: SettingsServerDeleteRequest
    ): DataResult<SettingsServerActionResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsServerActionResult(success = request.confirmationName.isNotBlank())
        }

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

    override suspend fun getChannelThreads(channelId: ChannelId, limit: Int): DataResult<ThreadListSnapshot> =
        resultFor(scenario, EmptyReason.NoInboxItems) {
            ThreadListSnapshot(
                server = MockSeedData.server,
                threads = MockSeedData.inboxItems.filter { it.kind == InboxItemKind.Thread }
            )
        }

    override suspend fun getFollowedThreads(serverId: ServerId, limit: Int): DataResult<ThreadListSnapshot> =
        resultFor(scenario, EmptyReason.NoInboxItems) {
            ThreadListSnapshot(
                server = MockSeedData.server,
                threads = MockSeedData.inboxItems.filter { it.kind == InboxItemKind.Thread }
            )
        }

    override suspend fun createOrOpenThread(
        channelId: ChannelId,
        parentMessageId: MessageId
    ): DataResult<ThreadActionResult> =
        threadAction(channelId, parentMessageId = parentMessageId, replyCount = 1)

    override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> =
        threadAction(ChannelId("thread-${parentMessageId.value}"), parentMessageId = parentMessageId, replyCount = 1)

    override suspend fun followThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
        threadAction(threadChannelId)

    override suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
        threadAction(threadChannelId)

    override suspend fun markThreadDone(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
        threadAction(threadChannelId)

    override suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
        threadAction(threadChannelId)

    override suspend fun uploadAttachments(request: AttachmentUploadRequest): DataResult<AttachmentUploadResult> =
        when {
            scenario == MockScenario.NoPermission -> noPermissionResult()
            scenario == MockScenario.Error -> errorResult("attachment_upload_failed")
            request.files.isEmpty() -> DataResult.Empty(EmptyReason.NoMessages)
            else -> DataResult.Content(
                AttachmentUploadResult(
                    request.files.mapIndexed { index, file ->
                        Attachment(
                            id = AttachmentId("attachment-local-${request.channelId.value}-$index"),
                            name = file.fileName,
                            mimeType = file.mimeType ?: "application/octet-stream",
                            sizeLabel = file.sizeBytes?.let { "$it bytes" }.orEmpty()
                        )
                    }
                )
            )
        }

    override suspend fun getAttachmentOpenTarget(attachmentId: AttachmentId): DataResult<AttachmentOpenTarget> =
        when (scenario) {
            MockScenario.NoPermission -> noPermissionResult()
            MockScenario.Error -> errorResult("attachment_open_failed")
            MockScenario.Empty -> DataResult.Empty(EmptyReason.NoMessages)
            MockScenario.Content -> DataResult.Content(
                AttachmentOpenTarget(
                    attachmentId = attachmentId,
                    url = "https://example.invalid/attachments/${attachmentId.value}"
                )
            )
        }

    override suspend fun getChannelFiles(
        channelId: ChannelId,
        limit: Int,
        cursor: String?
    ): DataResult<ChannelFilesSnapshot> =
        resultFor(scenario, EmptyReason.NoMessages) {
            ChannelFilesSnapshot(
                channelId = channelId,
                items = MockSeedData.threadSnapshot.messages
                    .flatMap { message -> message.attachments }
                    .distinctBy { it.id }
                    .map { attachment ->
                        ai.slock.android.core.data.repository.ChannelFileItem(
                            attachment = attachment,
                            createdAt = MockSeedData.threadSnapshot.messages.firstOrNull()?.timestampLabel,
                            messageId = MockSeedData.threadSnapshot.messages.firstOrNull()?.id,
                            sourceType = "message"
                        )
                    }
            )
        }

    override suspend fun sendMessage(
        channelId: ChannelId,
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
                        threadId = topLevelThreadId(channelId),
                        channelId = channelId,
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

    private fun threadAction(
        threadChannelId: ChannelId,
        parentMessageId: MessageId? = null,
        replyCount: Int = 0
    ): DataResult<ThreadActionResult> =
        when (scenario) {
            MockScenario.NoPermission -> noPermissionResult()
            MockScenario.Error -> errorResult("thread_action_failed")
            else -> DataResult.Content(
                ThreadActionResult(
                    threadChannelId = threadChannelId,
                    changed = true,
                    parentMessageId = parentMessageId,
                    replyCount = replyCount
                )
            )
        }
}

class MockReadReceiptRepository(
    private val scenario: MockScenario = MockScenario.Content
) : ReadReceiptRepository {
    override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
        resultFor(scenario, EmptyReason.NoInboxItems) {
            ChannelUnreadSnapshot(
                server = MockSeedData.server,
                unreadCounts = (MockSeedData.homeSnapshot.channels + MockSeedData.homeSnapshot.directMessages)
                    .mapNotNull { conversation ->
                        val id = conversation.channelId ?: return@mapNotNull null
                        id to conversation.unreadCount
                    }
                    .filter { it.second > 0 }
                    .toMap()
            )
        }

    override suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult> =
        readResult(channelId, seq)

    override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> =
        readResult(channelId, seq = null)

    override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> =
        when (scenario) {
            MockScenario.NoPermission -> noPermissionResult()
            MockScenario.Error -> errorResult("read_receipt_failed")
            else -> DataResult.Content(ReadReceiptResult(channelId = channelId, unreadCount = 1, changed = true))
        }

    override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> =
        readResult(channelId = null, seq = null)

    private fun readResult(channelId: ChannelId?, seq: Long?): DataResult<ReadReceiptResult> =
        when (scenario) {
            MockScenario.NoPermission -> noPermissionResult()
            MockScenario.Error -> errorResult("read_receipt_failed")
            else -> DataResult.Content(ReadReceiptResult(channelId = channelId, seq = seq, unreadCount = 0, changed = true))
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

    override suspend fun getChannelTasks(
        serverId: ServerId,
        channelId: ChannelId,
        filter: TaskFilter
    ): DataResult<TaskSnapshot> {
        val snapshot = MockSeedData.taskSnapshot
        val tasks = snapshot.tasks.filter { task ->
            task.channelId == channelId && (filter.statuses.isEmpty() || task.status in filter.statuses)
        }
        return resultFor(scenario, EmptyReason.NoTasks) {
            TaskSnapshot(
                tasks = tasks,
                summaryCounts = TaskStatus.entries.associateWith { status ->
                    tasks.count { it.status == status }
                }
            )
        }
    }

    override suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult> =
        resultFor(scenario, EmptyReason.NoTasks) {
            TaskActionResult(
                MockSeedData.tasks.firstOrNull { it.id == taskId } ?: MockSeedData.tasks.first()
            )
        }

    override suspend fun updateTaskStatus(
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> =
        resultFor(scenario, EmptyReason.NoTasks) {
            val task = MockSeedData.tasks.firstOrNull { it.id == taskId } ?: MockSeedData.tasks.first()
            TaskActionResult(task.copy(status = status))
        }
}

class MockMemberRepository(
    private val scenario: MockScenario = MockScenario.Content
) : MemberRepository {
    override suspend fun getMembers(
        priority: SlockRequestPriority,
        serverId: ServerId
    ): DataResult<MemberSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) { MockSeedData.memberSnapshot }

    override suspend fun getMemberProfile(
        priority: SlockRequestPriority,
        memberId: MemberId
    ): DataResult<MemberProfile> =
        resultFor(scenario, EmptyReason.NoMembers) {
            MockSeedData.memberSnapshot.members.firstOrNull { it.id == memberId }
                ?: MockSeedData.memberSnapshot.members.first()
        }

    override suspend fun updateAgentProfile(
        priority: SlockRequestPriority,
        request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile> =
        resultFor(scenario, EmptyReason.NoMembers) {
            val existing = MockSeedData.memberSnapshot.members
                .firstOrNull { it.id == request.agentId }
                ?: MockSeedData.memberSnapshot.members.first { it.kind == MemberKind.Agent }
            existing.copy(
                displayName = request.displayName.trim(),
                description = request.description?.trim()?.takeIf { it.isNotBlank() }
            )
        }

    override suspend fun getAgentDetail(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentDetailSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentDetailSnapshot(
                agentId = agentId,
                directMessages = mockAgentDms,
                reminders = mockAgentReminders,
                activity = mockAgentActivity,
                skills = mockAgentSkills(agentId)
            )
        }

    override suspend fun getAgentDirectMessages(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentDmSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentDmSnapshot(agentId = agentId, directMessages = mockAgentDms)
        }

    override suspend fun getAgentReminders(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentReminderSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentReminderSnapshot(agentId = agentId, reminders = mockAgentReminders)
        }

    override suspend fun getAgentActivity(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentActivitySnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentActivitySnapshot(agentId = agentId, activity = mockAgentActivity)
        }

    override suspend fun getAgentWorkspaces(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentWorkspaceSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentWorkspaceSnapshot(
                agentId = agentId,
                computerId = "mbp-14",
                computerName = "MacBook Pro",
                workspaces = listOf(
                    SettingsComputerWorkspaceSnapshot(
                        id = "slock-android",
                        name = "Slock",
                        path = "/Users/artin/AndroidStudioProjects/Slock",
                        statusLabel = "Active",
                        lastActiveLabel = "2026-05-13T19:30:00Z"
                    )
                )
            )
        }

    override suspend fun getAgentWorkspaceTree(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentWorkspaceTreeSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentWorkspaceTreeSnapshot(
                agentId = agentId,
                rootPath = "/Users/artin/AndroidStudioProjects/Slock",
                root = mockWorkspaceRoot
            )
        }

    override suspend fun getAgentWorkspaceChildren(
        priority: SlockRequestPriority,
        agentId: MemberId,
        dirPath: String
    ): DataResult<AgentWorkspaceChildrenSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentWorkspaceChildrenSnapshot(
                agentId = agentId,
                dirPath = dirPath,
                children = mockWorkspaceRoot.findByPath(dirPath)?.children.orEmpty()
            )
        }

    override suspend fun readAgentWorkspaceFile(
        priority: SlockRequestPriority,
        agentId: MemberId,
        path: String
    ): DataResult<AgentWorkspaceFilePreviewSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentWorkspaceFilePreviewSnapshot(
                agentId = agentId,
                path = path,
                preview = mockWorkspaceRoot.findByPath(path)?.preview
                    ?: SlockWorkspaceFilePreview.Unsupported(
                        path = path,
                        reason = "Mock preview unavailable."
                    )
            )
        }

    override suspend fun getAgentSkills(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentSkillsSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) { mockAgentSkills(agentId) }

    override suspend fun getChannelMembers(
        priority: SlockRequestPriority,
        channelId: ChannelId
    ): DataResult<ChannelMemberSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            ChannelMemberSnapshot(
                channelId = channelId,
                members = MockSeedData.memberSnapshot.members,
                canAddMembers = true
            )
        }

    override suspend fun addChannelMember(
        priority: SlockRequestPriority,
        request: AddChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> =
        resultFor(scenario, EmptyReason.NoMembers) {
            val member = MockSeedData.memberSnapshot.members
                .firstOrNull { it.id == request.memberId }
            ChannelMemberActionResult(
                channelId = request.channelId,
                memberId = request.memberId,
                member = member,
                added = true
            )
        }

    override suspend fun removeChannelMember(
        priority: SlockRequestPriority,
        request: RemoveChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> =
        resultFor(scenario, EmptyReason.NoMembers) {
            val member = MockSeedData.memberSnapshot.members
                .firstOrNull { it.id == request.memberId }
            ChannelMemberActionResult(
                channelId = request.channelId,
                memberId = request.memberId,
                member = member,
                added = false
            )
        }

    private val mockAgentDms = listOf(
        AgentDmSummary(
            id = "agent-dm-1",
            peerId = "user-1",
            peerName = "Artin",
            lastMessageAt = "14:20",
            lastMessagePreview = "Latest handoff is ready."
        )
    )

    private val mockAgentReminders = listOf(
        AgentReminderSummary(
            id = "reminder-1",
            title = "Follow up on Android QA",
            fireAt = "Tomorrow 09:00",
            recurrenceDescription = "One time",
            status = "scheduled"
        )
    )

    private val mockAgentActivity = listOf(
        AgentActivityEntry(
            id = "activity-1",
            type = "thinking",
            title = "Thinking",
            body = "Reviewing recent Android blockers.",
            timestamp = "14:22"
        )
    )

    private fun mockAgentSkills(agentId: MemberId): AgentSkillsSnapshot =
        AgentSkillsSnapshot(
            agentId = agentId,
            groups = listOf(
                AgentSkillGroup(
                    title = "Global",
                    count = 1,
                    paths = listOf(
                        AgentSkillPathGroup(
                            path = "~/.codex/skills",
                            count = 1,
                            skills = listOf(
                                AgentSkillSummary(
                                    name = "mock-skill",
                                    detail = "Mock skill from repository data."
                                )
                            )
                        )
                    )
                )
            )
        )

    private val mockWorkspaceRoot = SlockWorkspaceNode(
        id = "/",
        name = "workspace",
        path = "/",
        kind = SlockWorkspaceFileKind.Folder,
        children = listOf(
            SlockWorkspaceNode(
                id = "README.md",
                name = "README.md",
                path = "README.md",
                kind = SlockWorkspaceFileKind.Markdown,
                preview = SlockWorkspaceFilePreview.Text(
                    path = "README.md",
                    content = "# Slock\n\nMock workspace preview.",
                    markdown = true
                )
            ),
            SlockWorkspaceNode(
                id = "app",
                name = "app",
                path = "app",
                kind = SlockWorkspaceFileKind.Folder,
                children = listOf(
                    SlockWorkspaceNode(
                        id = "app/build.gradle.kts",
                        name = "build.gradle.kts",
                        path = "app/build.gradle.kts",
                        kind = SlockWorkspaceFileKind.Text,
                        preview = SlockWorkspaceFilePreview.Text(
                            path = "app/build.gradle.kts",
                            content = "plugins { /* mock */ }",
                            markdown = false
                        )
                    )
                )
            )
        )
    )

    private fun SlockWorkspaceNode.findByPath(targetPath: String): SlockWorkspaceNode? {
        if (path == targetPath) return this
        return children.firstNotNullOfOrNull { it.findByPath(targetPath) }
    }
}

class MockChannelRepository(
    private val scenario: MockScenario = MockScenario.Content
) : ai.slock.android.core.data.repository.ChannelRepository {
    override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> =
        resultFor(scenario, EmptyReason.NoChannels) { MockSeedData.homeSnapshot }

    override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> =
        resultFor(scenario, EmptyReason.NoChannels) { MockSeedData.channelSnapshot }

    override suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot> =
        resultFor(scenario, EmptyReason.NoChannels) {
            DirectMessageSnapshot(server = MockSeedData.server, directMessages = MockSeedData.directMessages)
        }

    override suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult> =
        openMockDirectMessage()

    override suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult> =
        openMockDirectMessage()

    override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> =
        resultFor(scenario, EmptyReason.NoChannels) {
            val channel = MockSeedData.channelSnapshot.channels.first().copy(
                name = request.name,
                subtitle = request.description
            )
            CreateChannelResult(
                channel = channel,
                addedAgentMemberIds = request.agentMemberIds,
                addedUserMemberIds = request.userMemberIds
            )
        }

    override suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult> =
        resultFor(scenario, EmptyReason.NoChannels) {
            val channel = MockSeedData.channelSnapshot.channels
                .firstOrNull { it.channelId == channelId }
                ?.copy(joined = true)
            ChannelJoinResult(channelId = channelId, joined = true, channel = channel)
        }

    override suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult> =
        resultFor(scenario, EmptyReason.NoChannels) {
            val channel = MockSeedData.channelSnapshot.channels
                .firstOrNull { it.channelId == channelId }
                ?.copy(archived = true)
            ChannelArchiveResult(channelId = channelId, archived = true, channel = channel)
        }

    override suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult> =
        resultFor(scenario, EmptyReason.NoChannels) {
            ChannelDeleteResult(channelId = channelId, deleted = true)
        }

    private fun openMockDirectMessage(): DataResult<OpenDirectMessageResult> =
        resultFor(scenario, EmptyReason.NoChannels) {
            val directMessages = MockSeedData.directMessages
            OpenDirectMessageResult(
                conversation = directMessages.first(),
                directMessages = directMessages
            )
        }
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
