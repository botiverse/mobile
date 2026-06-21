package ai.slock.android.core.data.mock

import ai.slock.compose.data.AuthSessionRepository
import ai.slock.compose.data.AgentRepository
import ai.slock.compose.data.AgentActivityEntry
import ai.slock.compose.data.AgentActivitySnapshot
import ai.slock.compose.data.AgentDetailSnapshot
import ai.slock.compose.data.AgentDmSnapshot
import ai.slock.compose.data.AgentDmSummary
import ai.slock.compose.data.AgentProfileUpdateRequest
import ai.slock.compose.data.AgentSnapshot
import ai.slock.compose.data.AccountAvatarUploadRequest
import ai.slock.compose.data.AccountPasswordChangeRequest
import ai.slock.compose.data.AccountProfileUpdateRequest
import ai.slock.compose.data.AccountProviderLinkStartRequest
import ai.slock.compose.data.AccountProviderLinkStartResult
import ai.slock.compose.data.AccountSettingsSnapshot
import ai.slock.compose.data.AgentReminderSnapshot
import ai.slock.compose.data.AgentReminderSummary
import ai.slock.compose.data.AgentSkillGroup
import ai.slock.compose.data.AgentSkillPathGroup
import ai.slock.compose.data.AgentSkillSummary
import ai.slock.compose.data.AgentSkillsSnapshot
import ai.slock.compose.data.AgentLifecycleActionResult
import ai.slock.compose.data.AgentMachineAssignmentRequest
import ai.slock.compose.data.AgentResetRequest
import ai.slock.compose.data.AgentWorkspaceSnapshot
import ai.slock.compose.data.AgentWorkspaceChildrenSnapshot
import ai.slock.compose.data.AgentWorkspaceFilePreviewSnapshot
import ai.slock.compose.data.AgentWorkspaceTreeSnapshot
import ai.slock.compose.data.AttachmentOpenTarget
import ai.slock.compose.data.ChannelFilesSnapshot
import ai.slock.compose.data.AttachmentUploadRequest
import ai.slock.compose.data.AttachmentUploadResult
import ai.slock.compose.data.AddChannelMemberRequest
import ai.slock.compose.data.ChannelMemberActionResult
import ai.slock.compose.data.ChannelMemberSnapshot
import ai.slock.compose.data.ChannelArchiveResult
import ai.slock.compose.data.ChannelDeleteResult
import ai.slock.compose.data.ChannelFileItem
import ai.slock.compose.data.ChannelJoinResult
import ai.slock.compose.data.ChannelLeaveResult
import ai.slock.compose.data.ChannelRepository
import ai.slock.compose.data.ChannelSnapshot
import ai.slock.compose.data.ChannelUnreadSnapshot
import ai.slock.compose.data.ConversationRepository
import ai.slock.compose.data.CreateChannelRequest
import ai.slock.compose.data.CreateChannelResult
import ai.slock.compose.data.CreateServerRequest
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.DirectMessageSnapshot
import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.InboxSnapshot
import ai.slock.compose.data.MemberRepository
import ai.slock.compose.data.MemberSnapshot
import ai.slock.compose.data.OpenDirectMessageResult
import ai.slock.compose.data.ReadReceiptRepository
import ai.slock.compose.data.ReadReceiptResult
import ai.slock.compose.data.RemoveChannelMemberRequest
import ai.slock.compose.data.SendMessageResult
import ai.slock.compose.data.ServerListSnapshot
import ai.slock.compose.data.ServerRepository
import ai.slock.compose.data.SettingsComputerDetailSnapshot
import ai.slock.compose.data.SettingsComputerActionResult
import ai.slock.compose.data.SettingsComputerKeyRotationResult
import ai.slock.compose.data.SettingsComputerRegisterRequest
import ai.slock.compose.data.SettingsComputerRegistrationResult
import ai.slock.compose.data.SettingsComputerRenameRequest
import ai.slock.compose.data.SettingsComputerRuntimeModelsSnapshot
import ai.slock.compose.data.SettingsComputerSnapshot
import ai.slock.compose.data.SettingsComputerWorkspaceDeleteRequest
import ai.slock.compose.data.SettingsComputersSnapshot
import ai.slock.compose.data.SettingsConnectedAppBuiltInSnapshot
import ai.slock.compose.data.SettingsConnectedAppClientSnapshot
import ai.slock.compose.data.SettingsConnectedAppInstalledSnapshot
import ai.slock.compose.data.SettingsConnectedAppMarketplaceSnapshot
import ai.slock.compose.data.SettingsConnectedAppPublishRequest
import ai.slock.compose.data.SettingsConnectedAppRegisterRequest
import ai.slock.compose.data.SettingsConnectedAppSecretSnapshot
import ai.slock.compose.data.SettingsConnectedAppUpdateRequest
import ai.slock.compose.data.SettingsConnectedAppLogoUploadRequest
import ai.slock.compose.data.SettingsConnectedAppsSnapshot
import ai.slock.compose.data.SettingsBillingCurrentPlanSnapshot
import ai.slock.compose.data.SettingsBillingPlanSnapshot
import ai.slock.compose.data.SettingsBillingSnapshot
import ai.slock.compose.data.SettingsAppearanceSnapshot
import ai.slock.compose.data.SettingsAppearanceUpdateRequest
import ai.slock.compose.data.SettingsPlanUsageSnapshot
import ai.slock.compose.data.SettingsMobileSelectionOptionSnapshot
import ai.slock.compose.data.SettingsMobileSelectionSnapshot
import ai.slock.compose.data.SettingsMobileSnapshot
import ai.slock.compose.data.SettingsLanguageRegionPreferenceUpdateRequest
import ai.slock.compose.data.SettingsLanguageRegionSnapshot
import ai.slock.compose.data.SettingsComputerWorkspaceSnapshot
import ai.slock.compose.data.SettingsRepository
import ai.slock.compose.data.SettingsServerActionResult
import ai.slock.compose.data.SettingsServerDeleteRequest
import ai.slock.compose.data.SettingsServerInviteCreateRequest
import ai.slock.compose.data.SettingsServerJoinLinkCreateRequest
import ai.slock.compose.data.SettingsServerJoinLinkSnapshot
import ai.slock.compose.data.SettingsServerProfileUpdateRequest
import ai.slock.compose.data.SettingsServerSnapshot
import ai.slock.compose.data.SettingsSnapshot
import ai.slock.compose.data.TaskActionResult
import ai.slock.compose.data.TaskFilter
import ai.slock.compose.data.TaskRepository
import ai.slock.compose.data.TaskSnapshot
import ai.slock.compose.data.ThreadActionResult
import ai.slock.compose.data.ThreadListSnapshot
import ai.slock.compose.data.ThreadSnapshot
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
import ai.slock.compose.data.DataRequestPriority

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

    override suspend fun changeAccountPassword(request: AccountPasswordChangeRequest): DataResult<AccountSettingsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.accountSettingsSnapshot }

    override suspend fun uploadAccountAvatar(request: AccountAvatarUploadRequest): DataResult<AccountSettingsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            MockSeedData.accountSettingsSnapshot.copy(
                profile = MockSeedData.accountSettingsSnapshot.profile.copy(avatarUrl = request.file.previewUri)
            )
        }

    override suspend fun startAccountProviderLink(request: AccountProviderLinkStartRequest): DataResult<AccountProviderLinkStartResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            AccountProviderLinkStartResult(
                providerId = request.providerId,
                url = "https://app.raft.build/auth/${request.providerId}/link"
            )
        }

    override suspend fun getConnectedApps(): DataResult<SettingsConnectedAppsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { mockConnectedApps() }

    override suspend fun registerConnectedApp(
        request: SettingsConnectedAppRegisterRequest
    ): DataResult<SettingsConnectedAppsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            mockConnectedApps(
                oneTimeClientSecret =
                    SettingsConnectedAppSecretSnapshot(
                        clientId = request.clientId ?: "mock-client",
                        clientSecret = "secret_mock_shown_once"
                    )
            )
        }

    override suspend fun updateConnectedApp(
        request: SettingsConnectedAppUpdateRequest
    ): DataResult<SettingsConnectedAppsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { mockConnectedApps() }

    override suspend fun uploadConnectedAppLogo(
        request: SettingsConnectedAppLogoUploadRequest
    ): DataResult<SettingsConnectedAppsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            mockConnectedApps(registered = listOf(mockConnectedAppClient().copy(logoUrl = request.file.previewUri ?: "mock://${request.file.fileName}")))
        }

    override suspend fun clearConnectedAppLogo(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { mockConnectedApps(registered = listOf(mockConnectedAppClient())) }

    override suspend fun deleteConnectedApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { mockConnectedApps(registered = emptyList()) }

    override suspend fun requestConnectedAppPublish(
        request: SettingsConnectedAppPublishRequest
    ): DataResult<SettingsConnectedAppsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            mockConnectedApps(
                registered =
                    listOf(mockConnectedAppClient().copy(publishStatus = "pending_review", category = request.category))
            )
        }

    override suspend fun installMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { mockConnectedApps(marketplaceInstalled = true) }

    override suspend fun uninstallMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { mockConnectedApps(marketplaceInstalled = false) }

    override suspend fun getComputers(): DataResult<SettingsComputersSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.settingsComputersSnapshot }

    override suspend fun getComputerDetail(machineId: String): DataResult<SettingsComputerDetailSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            MockSeedData.settingsComputerDetailSnapshot.copy(
                computer = MockSeedData.settingsComputerDetailSnapshot.computer.copy(id = machineId)
            )
        }

    override suspend fun registerComputer(
        request: SettingsComputerRegisterRequest
    ): DataResult<SettingsComputerRegistrationResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsComputerRegistrationResult(
                machine = MockSeedData.settingsComputersSnapshot.computers.first().copy(
                    id = request.name.ifBlank { "mock-computer" }.lowercase().replace(" ", "-"),
                    name = request.name.ifBlank { "Mock computer" }
                ),
                apiKeyPrefix = "slock_mock"
            )
        }

    override suspend fun renameComputer(
        request: SettingsComputerRenameRequest
    ): DataResult<SettingsComputerSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            MockSeedData.settingsComputersSnapshot.computers.first().copy(
                id = request.machineId,
                name = request.name
            )
        }

    override suspend fun deleteComputer(machineId: String): DataResult<SettingsComputerActionResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsComputerActionResult(machineId = machineId, success = true, action = "delete")
        }

    override suspend fun rotateComputerKey(machineId: String): DataResult<SettingsComputerKeyRotationResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsComputerKeyRotationResult(machineId = machineId, apiKeyPrefix = "slock_rotated")
        }

    override suspend fun getComputerRuntimeModels(
        machineId: String,
        runtime: String
    ): DataResult<SettingsComputerRuntimeModelsSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsComputerRuntimeModelsSnapshot(
                machineId = machineId,
                runtime = runtime,
                models = listOf("gpt-5", "gpt-5-mini"),
                defaultModel = "gpt-5"
            )
        }

    override suspend fun deleteComputerWorkspace(
        request: SettingsComputerWorkspaceDeleteRequest
    ): DataResult<SettingsComputerActionResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsComputerActionResult(
                machineId = request.machineId,
                success = true,
                action = "delete_workspace",
                directoryName = request.directoryName
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
            val current = MockSeedData.settingsServerSnapshot.profile
            MockSeedData.settingsServerSnapshot.copy(
                profile =
                    current.copy(
                        name = request.name ?: current.name,
                        hideHumansFromMembers = request.hideHumansFromMembers ?: current.hideHumansFromMembers
                    )
            )
        }

    override suspend fun createServerInvite(
        request: SettingsServerInviteCreateRequest
    ): DataResult<SettingsServerActionResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsServerActionResult(success = true, message = request.email)
        }

    override suspend fun createServerJoinLinkForInvite(
        request: SettingsServerJoinLinkCreateRequest
    ): DataResult<SettingsServerJoinLinkSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { mockServerJoinLink() }

    override suspend fun listServerJoinLinks(serverId: String): DataResult<List<SettingsServerJoinLinkSnapshot>> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            MockSeedData.settingsServerSnapshot.joinLinks.ifEmpty { listOf(mockServerJoinLink()) }
        }

    override suspend fun deleteServer(
        request: SettingsServerDeleteRequest
    ): DataResult<SettingsServerActionResult> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsServerActionResult(success = request.confirmationName.isNotBlank())
        }

    override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.settingsMobileSnapshot }

    override suspend fun getLanguageRegionSettings(): DataResult<SettingsLanguageRegionSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { MockSeedData.settingsLanguageRegionSnapshot }

    override suspend fun getAppearanceSettings(): DataResult<SettingsAppearanceSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) { SettingsAppearanceSnapshot() }

    override suspend fun updateAppearanceSettings(
        request: SettingsAppearanceUpdateRequest
    ): DataResult<SettingsAppearanceSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            SettingsAppearanceSnapshot(
                messageBodyFontSize =
                    SettingsMobileSelectionSnapshot(
                        selectedKey = request.preferredMessageBodyFontSize,
                        selectedLabel = request.preferredMessageBodyFontSize,
                        statusLabel = request.preferredMessageBodyFontSize,
                        detail = "Adjusts message body text only.",
                        options =
                            listOf(
                                SettingsMobileSelectionOptionSnapshot("sm", "Small"),
                                SettingsMobileSelectionOptionSnapshot("md", "Medium"),
                                SettingsMobileSelectionOptionSnapshot("lg", "Large")
                            )
                    )
            )
        }

    override suspend fun updateLanguageRegionPreference(
        request: SettingsLanguageRegionPreferenceUpdateRequest
    ): DataResult<SettingsLanguageRegionSnapshot> =
        resultFor(scenario, EmptyReason.NoSettingsAvailable) {
            MockSeedData.settingsLanguageRegionSnapshot.copy(
                autoTranslation =
                    request.autoTranslationEnabled?.let {
                        MockSeedData.settingsLanguageRegionSnapshot.autoTranslation.copy(
                            enabled = it,
                            statusLabel = if (it) "Enabled" else "Disabled"
                        )
                    } ?: MockSeedData.settingsLanguageRegionSnapshot.autoTranslation
            )
        }

    private fun mockServerJoinLink(): SettingsServerJoinLinkSnapshot =
        SettingsServerJoinLinkSnapshot(
            id = "join-link-mock",
            label = "Anyone with this link",
            url = "https://example.invalid/join/mock",
            maxUsesLabel = "Unlimited",
            usageLabel = "0 uses",
            expiresAtLabel = "Never expires"
        )

    private fun mockConnectedApps(
        marketplaceInstalled: Boolean = true,
        registered: List<SettingsConnectedAppClientSnapshot> = listOf(mockConnectedAppClient()),
        oneTimeClientSecret: SettingsConnectedAppSecretSnapshot? = null
    ): SettingsConnectedAppsSnapshot =
        SettingsConnectedAppsSnapshot(
            marketplace =
                listOf(
                    SettingsConnectedAppMarketplaceSnapshot(
                        id = "marketplace-client",
                        clientId = "marketplace-client",
                        name = "Raft Calendar",
                        category = "productivity",
                        developer = "Raft",
                        description = "Create reminders from workspace activity.",
                        homepageDomain = "raft.build",
                        callbackDomain = "calendar.raft.build",
                        installed = marketplaceInstalled,
                        dataAccessSummary = "Profile and reminders"
                    )
                ),
            builtIn =
                listOf(
                    SettingsConnectedAppBuiltInSnapshot(
                        id = "built-in-client",
                        clientId = "raft-actions",
                        name = "Raft Actions",
                        description = "Built-in agent action callbacks.",
                        homepageDomain = "raft.build"
                    )
                ),
            installed =
                if (marketplaceInstalled) {
                    listOf(
                        SettingsConnectedAppInstalledSnapshot(
                            id = "marketplace-client",
                            clientId = "marketplace-client",
                            name = "Raft Calendar",
                            developer = "Raft",
                            description = "Create reminders from workspace activity.",
                            homepageDomain = "raft.build",
                            callbackDomain = "calendar.raft.build",
                            origin = "marketplace"
                        )
                    )
                } else {
                    emptyList()
                },
            registered = registered,
            canManage = true,
            oneTimeClientSecret = oneTimeClientSecret
        )

    private fun mockConnectedAppClient(): SettingsConnectedAppClientSnapshot =
        SettingsConnectedAppClientSnapshot(
            id = "local-client",
            serverId = "server-1",
            clientId = "local-client",
            appType = "server_local",
            publishStatus = "draft",
            category = "productivity",
            dataAccessSummary = "Profile and identity",
            name = "Local OAuth Client",
            description = "Private server integration.",
            homepageDomain = "example.invalid",
            callbackDomain = "example.invalid",
            returnUrl = "https://example.invalid/oauth/callback"
        )
}

class MockConversationRepository(
    private val scenario: MockScenario = MockScenario.Content
) : ConversationRepository {
    override suspend fun getThread(
        threadId: ThreadId,
        includeFollowedSummaries: Boolean,
        limit: Int
    ): DataResult<ThreadSnapshot> =
        resultFor(scenario, EmptyReason.NoMessages) { MockSeedData.threadSnapshot }

    override suspend fun getReplyThread(
        parentChannelId: ChannelId,
        parentMessageId: MessageId,
        includeFollowedSummaries: Boolean,
        limit: Int
    ): DataResult<ThreadSnapshot> =
        resultFor(scenario, EmptyReason.NoMessages) { MockSeedData.threadSnapshot }

    override suspend fun getMessageContext(messageId: MessageId): DataResult<ThreadSnapshot> =
        resultFor(scenario, EmptyReason.NoMessages) {
            MockSeedData.threadSnapshot.copy(highlightedMessageId = messageId)
        }

    override suspend fun loadOlderMessages(
        threadId: ThreadId,
        beforeSeq: Long,
        limit: Int
    ): DataResult<ThreadSnapshot> =
        resultFor(scenario, EmptyReason.NoMessages) {
            MockSeedData.threadSnapshot.copy(canLoadMoreBefore = beforeSeq > 1000)
        }

    override suspend fun loadNewerMessages(
        threadId: ThreadId,
        afterSeq: Long,
        limit: Int
    ): DataResult<ThreadSnapshot> =
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
                        ChannelFileItem(
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
        priority: DataRequestPriority,
        serverId: ServerId
    ): DataResult<MemberSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) { MockSeedData.memberSnapshot }

    override suspend fun getMemberProfile(
        priority: DataRequestPriority,
        memberId: MemberId
    ): DataResult<MemberProfile> =
        resultFor(scenario, EmptyReason.NoMembers) {
            MockSeedData.memberSnapshot.members.firstOrNull { it.id == memberId }
                ?: MockSeedData.memberSnapshot.members.first()
        }

    override suspend fun updateAgentProfile(
        priority: DataRequestPriority,
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
        priority: DataRequestPriority,
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
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentDmSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentDmSnapshot(agentId = agentId, directMessages = mockAgentDms)
        }

    override suspend fun getAgentReminders(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentReminderSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentReminderSnapshot(agentId = agentId, reminders = mockAgentReminders)
        }

    override suspend fun getAgentActivity(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentActivitySnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentActivitySnapshot(agentId = agentId, activity = mockAgentActivity)
        }

    override suspend fun getAgentWorkspaces(
        priority: DataRequestPriority,
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
                        name = "Raft",
                        path = "/Users/artin/AndroidStudioProjects/Slock",
                        statusLabel = "Active",
                        lastActiveLabel = "2026-05-13T19:30:00Z"
                    )
                )
            )
        }

    override suspend fun getAgentWorkspaceTree(
        priority: DataRequestPriority,
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
        priority: DataRequestPriority,
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
        priority: DataRequestPriority,
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
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentSkillsSnapshot> =
        resultFor(scenario, EmptyReason.NoMembers) { mockAgentSkills(agentId) }

    override suspend fun startAgent(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentLifecycleActionResult> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentLifecycleActionResult(agentId = agentId, success = true, action = "start")
        }

    override suspend fun stopAgent(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentLifecycleActionResult> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentLifecycleActionResult(agentId = agentId, success = true, action = "stop")
        }

    override suspend fun resetAgent(
        priority: DataRequestPriority,
        request: AgentResetRequest
    ): DataResult<AgentLifecycleActionResult> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentLifecycleActionResult(
                agentId = request.agentId,
                success = true,
                action = "reset",
                resetMode = request.mode
            )
        }

    override suspend fun deleteAgent(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentLifecycleActionResult> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentLifecycleActionResult(agentId = agentId, success = true, action = "delete")
        }

    override suspend fun assignAgentMachine(
        priority: DataRequestPriority,
        request: AgentMachineAssignmentRequest
    ): DataResult<AgentLifecycleActionResult> =
        resultFor(scenario, EmptyReason.NoMembers) {
            AgentLifecycleActionResult(
                agentId = request.agentId,
                success = true,
                action = "assign_machine",
                machineId = request.machineId
            )
        }

    override suspend fun getChannelMembers(
        priority: DataRequestPriority,
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
        priority: DataRequestPriority,
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
        priority: DataRequestPriority,
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
                    content = "# Raft\n\nMock workspace preview.",
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
) : ChannelRepository {
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

    override suspend fun leaveChannel(channelId: ChannelId): DataResult<ChannelLeaveResult> =
        resultFor(scenario, EmptyReason.NoChannels) {
            ChannelLeaveResult(channelId = channelId, left = true)
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
