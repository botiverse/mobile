package build.raft.app.data

import build.raft.app.model.*

enum class DataRequestPriority {
    Foreground,
    Visible,
    Background
}

interface AuthSessionRepository {
    suspend fun currentSession(): DataResult<SessionSnapshot>
}

interface ServerRepository {
    suspend fun getServers(): DataResult<ServerListSnapshot>
    suspend fun selectServer(serverId: ServerId): DataResult<ServerListSnapshot>
    suspend fun createServer(request: CreateServerRequest): DataResult<ServerListSnapshot>
    suspend fun getHomeSnapshot(serverId: ServerId): DataResult<HomeSnapshot>
    suspend fun getInbox(serverId: ServerId): DataResult<InboxSnapshot>
    suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot>
    suspend fun getSettings(serverId: ServerId): DataResult<SettingsSnapshot>
}

/**
 * Typed Settings data boundary used by shared Settings pages.
 *
 * Settings sections should flow through these typed snapshots/results or
 * platform capabilities. Do not add UI-local raw JSON readers or standalone API
 * docs for Billing/Server/Account fields; put non-obvious field mappings on
 * the request/snapshot type that owns them.
 */
interface SettingsRepository {
    suspend fun getAccount(): DataResult<AccountSettingsSnapshot>
    suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot>
    suspend fun changeAccountPassword(request: AccountPasswordChangeRequest): DataResult<AccountSettingsSnapshot>
    suspend fun uploadAccountAvatar(request: AccountAvatarUploadRequest): DataResult<AccountSettingsSnapshot>
    suspend fun startAccountProviderLink(request: AccountProviderLinkStartRequest): DataResult<AccountProviderLinkStartResult>
    /**
     * `PATCH /api/auth/me` `{ shareUsageData }` (RFC-067 "Share usage data"); the content is the choice the
     * server now holds, read back from the returned user.
     */
    suspend fun updateShareUsageData(enabled: Boolean): DataResult<AccountUsageDataSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
    suspend fun getConnectedApps(): DataResult<SettingsConnectedAppsSnapshot>
    suspend fun registerConnectedApp(
        request: SettingsConnectedAppRegisterRequest
    ): DataResult<SettingsConnectedAppsSnapshot>
    suspend fun updateConnectedApp(
        request: SettingsConnectedAppUpdateRequest
    ): DataResult<SettingsConnectedAppsSnapshot>
    suspend fun uploadConnectedAppLogo(
        request: SettingsConnectedAppLogoUploadRequest
    ): DataResult<SettingsConnectedAppsSnapshot>
    suspend fun clearConnectedAppLogo(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot>
    suspend fun deleteConnectedApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot>
    suspend fun requestConnectedAppPublish(
        request: SettingsConnectedAppPublishRequest
    ): DataResult<SettingsConnectedAppsSnapshot>
    suspend fun installMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot>
    suspend fun uninstallMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot>
    suspend fun getComputers(): DataResult<SettingsComputersSnapshot>
    suspend fun getComputerDetail(machineId: String): DataResult<SettingsComputerDetailSnapshot>
    suspend fun getComputerDetailForComputer(computer: SettingsComputerSnapshot): DataResult<SettingsComputerDetailSnapshot> =
        getComputerDetail(computer.id)
    suspend fun scanComputerWorkspaces(machineId: String): DataResult<List<SettingsComputerWorkspaceSnapshot>> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
    suspend fun registerComputer(request: SettingsComputerRegisterRequest): DataResult<SettingsComputerRegistrationResult>
    suspend fun renameComputer(request: SettingsComputerRenameRequest): DataResult<SettingsComputerSnapshot>
    suspend fun updateComputerDescription(request: SettingsComputerDescriptionUpdateRequest): DataResult<SettingsComputerSnapshot>
    suspend fun deleteComputer(machineId: String): DataResult<SettingsComputerActionResult>
    suspend fun rotateComputerKey(machineId: String): DataResult<SettingsComputerKeyRotationResult>
    /** `POST servers/:id/machines/:machineId/computer/restart` (managed Computers, online only). */
    suspend fun restartComputer(machineId: String): DataResult<SettingsComputerRestartResult> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
    /** `POST servers/:id/machines/:machineId/computer/upgrade` with the consented `targetVersion`. */
    suspend fun upgradeComputer(request: SettingsComputerUpgradeRequest): DataResult<SettingsComputerUpgradeRequestSnapshot> =
        DataResult.Empty(EmptyReason.NoSettingsAvailable)
    suspend fun getComputerRuntimeModels(
        machineId: String,
        runtime: String
    ): DataResult<SettingsComputerRuntimeModelsSnapshot>
    suspend fun deleteComputerWorkspace(request: SettingsComputerWorkspaceDeleteRequest): DataResult<SettingsComputerActionResult>
    suspend fun getBilling(): DataResult<SettingsBillingSnapshot>
    suspend fun getServerSettings(): DataResult<SettingsServerSnapshot>
    suspend fun updateServerProfile(request: SettingsServerProfileUpdateRequest): DataResult<SettingsServerSnapshot>
    suspend fun createServerInvite(request: SettingsServerInviteCreateRequest): DataResult<SettingsServerActionResult>
    suspend fun deleteServer(request: SettingsServerDeleteRequest): DataResult<SettingsServerActionResult>
    suspend fun getAppearanceSettings(): DataResult<SettingsAppearanceSnapshot>
    suspend fun updateAppearanceSettings(request: SettingsAppearanceUpdateRequest): DataResult<SettingsAppearanceSnapshot>
    suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot>
    suspend fun getLanguageRegionSettings(): DataResult<SettingsLanguageRegionSnapshot>
    suspend fun updateLanguageRegionPreference(
        request: SettingsLanguageRegionPreferenceUpdateRequest
    ): DataResult<SettingsLanguageRegionSnapshot>
    /**
     * Task #43: create a server join link with no expiry / no max-uses.
     * Exposed on the main [SettingsRepository] interface (not just
     * [SettingsServerActionRepository]) so callers that don't already
     * have a `SettingsServerActionRepository` handle (e.g. the Members
     * page data source) can call it through the single `SettingsRepository`
     * instance. Returns the new link snapshot with its URL populated.
     */
    suspend fun createServerJoinLinkForInvite(
        request: SettingsServerJoinLinkCreateRequest
    ): DataResult<SettingsServerJoinLinkSnapshot>
    /**
     * Task #43: list server join links. The first entry is the canonical
     * "anyone with this link can join" link the React `InviteHumanDialog`
     * shows. The list endpoint is read-only; mutation goes through
     * [createServerJoinLinkForInvite] / [revokeServerJoinLink] on
     * [SettingsServerActionRepository].
     */
    suspend fun listServerJoinLinks(serverId: String): DataResult<List<SettingsServerJoinLinkSnapshot>>
}

interface SettingsServerActionRepository {
    suspend fun revokeServerInvite(inviteId: String): DataResult<SettingsServerActionResult>
    suspend fun createServerJoinLink(request: SettingsServerJoinLinkCreateRequest): DataResult<SettingsServerJoinLinkSnapshot>
    suspend fun revokeServerJoinLink(linkId: String): DataResult<SettingsServerActionResult>
    suspend fun setServerMute(enabled: Boolean): DataResult<SettingsMobileSnapshot>

    suspend fun setServerNotificationMode(mode: ServerPushMode): DataResult<SettingsMobileSnapshot> =
        when (mode) {
            ServerPushMode.All -> setServerMute(false)
            ServerPushMode.None -> setServerMute(true)
            ServerPushMode.Mentions ->
                DataResult.Error(ScreenError("settings_server_mentions_only_unavailable", retryable = false))
        }
}

interface SettingsServerManagementRepository {
    suspend fun updateServerAgreement(
        request: SettingsServerAgreementUpdateRequest
    ): DataResult<SettingsServerAgreementSnapshot>

    suspend fun updateServerOnboarding(
        request: SettingsServerOnboardingUpdateRequest
    ): DataResult<SettingsServerOnboardingSnapshot>

    suspend fun getServerTranslationSettings(): DataResult<SettingsServerTranslationSnapshot>
    suspend fun updateServerTranslationSettings(
        request: SettingsServerTranslationUpdateRequest
    ): DataResult<SettingsServerTranslationSnapshot>

    suspend fun getServerSidebarOrder(): DataResult<SettingsServerSidebarOrderSnapshot>
    suspend fun updateServerSidebarOrder(
        request: SettingsServerSidebarOrderUpdateRequest
    ): DataResult<SettingsServerSidebarOrderSnapshot>
    suspend fun getServerOrder(): DataResult<SettingsServerOrderSnapshot>
    suspend fun updateServerOrder(
        request: SettingsServerOrderUpdateRequest
    ): DataResult<SettingsServerOrderSnapshot>

    suspend fun updateServerMemberRole(
        request: SettingsServerMemberRoleUpdateRequest
    ): DataResult<SettingsServerActionResult>

    suspend fun updateSystemChannelVisibility(
        allChannelId: String,
        hide: Boolean
    ): DataResult<SettingsServerActionResult>

    suspend fun restoreSystemChannel(): DataResult<SettingsServerActionResult>

    suspend fun removeServerMember(memberId: String): DataResult<SettingsServerActionResult>
    suspend fun leaveServer(): DataResult<SettingsServerActionResult>
}

interface ConversationRepository {
    suspend fun getConversationDetail(
        channelId: ChannelId,
        priority: DataRequestPriority = DataRequestPriority.Foreground,
    ): DataResult<ConversationSummary> =
        DataResult.Error(ScreenError(code = "conversation_detail_unavailable", retryable = true))

    suspend fun getLatestChannelMessage(
        channelId: ChannelId,
        priority: DataRequestPriority = DataRequestPriority.Background,
    ): DataResult<Message> =
        DataResult.Empty(EmptyReason.NoMessages)

    suspend fun getThread(
        threadId: ThreadId,
        includeFollowedSummaries: Boolean = false,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<ThreadSnapshot>
    suspend fun getThreadReplySummaries(
        threadId: ThreadId,
        parentMessageIds: List<MessageId>
    ): DataResult<ThreadReplySummariesSnapshot> =
        DataResult.Content(ThreadReplySummariesSnapshot(threads = emptyList()))
    suspend fun getReplyThread(
        parentChannelId: ChannelId,
        parentMessageId: MessageId,
        includeFollowedSummaries: Boolean = false,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<ThreadSnapshot>
    suspend fun getReplyThread(
        parentChannelId: ChannelId,
        parentMessageId: MessageId,
        includeFollowedSummaries: Boolean = false,
        limit: Int = DEFAULT_PAGE_LIMIT,
        priority: DataRequestPriority
    ): DataResult<ThreadSnapshot> =
        getReplyThread(parentChannelId, parentMessageId, includeFollowedSummaries, limit)
    /**
     * Task #14b: [captureRawMessagePage] asks the implementation to also carry the reply page's raw
     * `messages` array ([ThreadSnapshot.rawMessagePage]) so the Activity prewarm can persist it.
     * Implementations that cannot capture it ignore the flag (nothing is persisted then).
     */
    suspend fun getReplyThread(
        parentChannelId: ChannelId,
        parentMessageId: MessageId,
        includeFollowedSummaries: Boolean = false,
        limit: Int = DEFAULT_PAGE_LIMIT,
        priority: DataRequestPriority,
        captureRawMessagePage: Boolean
    ): DataResult<ThreadSnapshot> =
        getReplyThread(parentChannelId, parentMessageId, includeFollowedSummaries, limit, priority)
    suspend fun getMessageContext(messageId: MessageId, channelId: ChannelId? = null): DataResult<ThreadSnapshot>
    suspend fun getMessageContext(
        messageId: MessageId,
        channelId: ChannelId? = null,
        priority: DataRequestPriority
    ): DataResult<ThreadSnapshot> = getMessageContext(messageId, channelId)
    suspend fun loadOlderMessages(
        threadId: ThreadId,
        beforeSeq: Long,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<ThreadSnapshot>
    suspend fun loadNewerMessages(
        threadId: ThreadId,
        afterSeq: Long,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<ThreadSnapshot>
    suspend fun syncNewerMessages(
        threadId: ThreadId,
        sinceSeq: Long,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<ThreadSnapshot>
    /**
     * Task #168 step 1: the latest page of [threadId] (`messages/channel/{id}?limit=` with no cursor)
     * for the gap catch-up, carrying the raw page for the durable window. Repositories that do not
     * capture raw pages answer with their plain latest page.
     */
    suspend fun loadLatestMessages(
        threadId: ThreadId,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<ThreadSnapshot> = getThread(threadId, includeFollowedSummaries = false, limit = limit)
    suspend fun getChannelThreads(channelId: ChannelId, limit: Int = DEFAULT_PAGE_LIMIT): DataResult<ThreadListSnapshot>
    suspend fun getFollowedThreads(serverId: ServerId, limit: Int = DEFAULT_PAGE_LIMIT): DataResult<ThreadListSnapshot>
    suspend fun createOrOpenThread(channelId: ChannelId, parentMessageId: MessageId): DataResult<ThreadActionResult>
    suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult>
    suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult>
    suspend fun markThreadDone(
        threadChannelId: ChannelId,
        frontier: InboxDoneFrontier = InboxDoneFrontier.Legacy
    ): DataResult<ThreadActionResult>
    suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult>
    suspend fun getAttachmentUploadCapabilities(): DataResult<AttachmentUploadCapabilities> =
        DataResult.Error(ScreenError(code = "attachment_upload_capabilities_unavailable", retryable = true))

    /**
     * Typed direct-session attachment operations.  These defaults deliberately fail closed so
     * legacy/test repositories cannot accidentally be treated as an atomic Gallery uploader.
     * The presigned URL carried by [AttachmentUploadSessionCreateResult] is process-local and
     * must never be copied into a durable snapshot or diagnostic string.
     */
    suspend fun createAttachmentUploadSession(
        request: AttachmentUploadSessionCreateRequest
    ): DataResult<AttachmentUploadSessionCreateResult> =
        DataResult.Error(ScreenError("attachment_upload_session_unavailable", retryable = false))

    suspend fun putAttachmentUploadBytes(
        upload: AttachmentUploadSessionPut,
        bytes: ByteArray,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null,
    ): DataResult<Unit> =
        DataResult.Error(ScreenError("attachment_upload_session_put_unavailable", retryable = false))

    suspend fun putAttachmentUploadFile(
        upload: AttachmentUploadSessionPut,
        file: PlatformFile,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null,
    ): DataResult<Unit> =
        DataResult.Error(ScreenError("attachment_upload_session_put_unavailable", retryable = false))

    /**
     * Resolve a platform file's authoritative length before a direct-session create request.
     * Implementations must reject ambiguous `fileRef` + in-memory byte inputs rather than silently
     * choosing one source.
     */
    suspend fun prepareAttachmentUploadFile(
        file: PlatformFile,
    ): DataResult<PlatformFile> =
        DataResult.Error(ScreenError("attachment_upload_file_prepare_unavailable", retryable = false))

    suspend fun completeAttachmentUploadSession(
        uploadId: String
    ): DataResult<AttachmentUploadSessionCompleteResult> =
        DataResult.Error(ScreenError("attachment_upload_session_unavailable", retryable = false))

    suspend fun cancelAttachmentUploadSession(
        uploadId: String
    ): DataResult<AttachmentUploadSessionView> =
        DataResult.Error(ScreenError("attachment_upload_session_unavailable", retryable = false))

    suspend fun statusAttachmentUploadSession(
        uploadId: String
    ): DataResult<AttachmentUploadSessionView> =
        DataResult.Error(ScreenError("attachment_upload_session_unavailable", retryable = false))

    suspend fun uploadAttachments(request: AttachmentUploadRequest): DataResult<AttachmentUploadResult>
    suspend fun getAttachmentOpenTarget(
        attachmentId: AttachmentId
    ): DataResult<AttachmentOpenTarget>
    suspend fun getChannelFiles(
        channelId: ChannelId,
        limit: Int = 50,
        cursor: String? = null
    ): DataResult<ChannelFilesSnapshot>
    suspend fun sendMessage(
        channelId: ChannelId,
        body: String,
        attachmentIds: List<AttachmentId> = emptyList(),
        asTask: Boolean = false,
        randomId: String? = null
    ): DataResult<SendMessageResult>

    suspend fun executePendingMentionActions(
        action: String,
        resolutionIds: List<String>
    ): DataResult<List<PendingMentionActionExecuteResult>> =
        DataResult.Error(ScreenError(code = "mention_actions_unavailable", retryable = false))
}

interface TaskRepository {
    suspend fun getTasks(serverId: ServerId, filter: TaskFilter): DataResult<TaskSnapshot>
    suspend fun getChannelTasks(
        serverId: ServerId,
        channelId: ChannelId,
        filter: TaskFilter
    ): DataResult<TaskSnapshot>
    suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult>
    suspend fun updateTaskStatus(
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult>
}

interface MemberRepository {
    suspend fun getMembers(
        priority: DataRequestPriority,
        serverId: ServerId
    ): DataResult<MemberSnapshot>
    suspend fun getMemberProfile(
        priority: DataRequestPriority,
        memberId: MemberId,
        serverId: ServerId? = null
    ): DataResult<MemberProfile>
    suspend fun updateAgentProfile(
        priority: DataRequestPriority,
        request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile>
    suspend fun uploadAgentAvatar(
        priority: DataRequestPriority,
        request: AgentAvatarUploadRequest
    ): DataResult<MemberProfile>
    suspend fun getAgentDetail(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentDetailSnapshot>
    suspend fun getAgentDirectMessages(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentDmSnapshot>
    suspend fun getAgentChannels(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentChannelSnapshot> = DataResult.Empty(EmptyReason.NoMembers)
    suspend fun getAgentReminders(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentReminderSnapshot>
    suspend fun getAgentActivity(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentActivitySnapshot>
    suspend fun getAgentWorkspaces(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentWorkspaceSnapshot>
    suspend fun getAgentWorkspaceTree(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentWorkspaceTreeSnapshot>
    suspend fun getAgentWorkspaceChildren(
        priority: DataRequestPriority,
        agentId: MemberId,
        dirPath: String
    ): DataResult<AgentWorkspaceChildrenSnapshot>
    suspend fun readAgentWorkspaceFile(
        priority: DataRequestPriority,
        agentId: MemberId,
        path: String
    ): DataResult<AgentWorkspaceFilePreviewSnapshot>
    suspend fun getAgentSkills(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentSkillsSnapshot>
    suspend fun getAgentAppLogins(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentAppLoginSnapshot> =
        DataResult.Empty(EmptyReason.NoMembers)
    suspend fun startAgent(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentLifecycleActionResult>
    suspend fun stopAgent(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentLifecycleActionResult>
    suspend fun resetAgent(
        priority: DataRequestPriority,
        request: AgentResetRequest
    ): DataResult<AgentLifecycleActionResult>
    suspend fun deleteAgent(
        priority: DataRequestPriority,
        agentId: MemberId,
        workspaceChoice: AgentArchiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep
    ): DataResult<AgentLifecycleActionResult>
    suspend fun assignAgentMachine(
        priority: DataRequestPriority,
        request: AgentMachineAssignmentRequest
    ): DataResult<AgentLifecycleActionResult>
    suspend fun getChannelMembers(
        priority: DataRequestPriority,
        channelId: ChannelId
    ): DataResult<ChannelMemberSnapshot>
    suspend fun addChannelMember(
        priority: DataRequestPriority,
        request: AddChannelMemberRequest
    ): DataResult<ChannelMemberActionResult>

    /**
     * Atomic batch add of channel members (task #352): mixed humans + agents in
     * one request. Default implementation is unsupported so existing fakes keep
     * compiling; RealMemberRepository overrides it.
     */
    suspend fun addChannelMembersBatch(
        priority: DataRequestPriority,
        request: AddChannelMembersBatchRequest
    ): DataResult<AddChannelMembersBatchResult> =
        DataResult.Error(ScreenError(code = "channel_member_batch_unsupported"))
    suspend fun removeChannelMember(
        priority: DataRequestPriority,
        request: RemoveChannelMemberRequest
    ): DataResult<ChannelMemberActionResult>
}

interface AgentRepository {
    suspend fun getAgents(serverId: ServerId): DataResult<AgentSnapshot>
}

interface ChannelRepository {
    suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot>
    suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot>
    suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot>
    suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult>
    suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult>
    suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult>
    suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult>
    suspend fun leaveChannel(channelId: ChannelId): DataResult<ChannelLeaveResult>
    suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult>
    suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult>
    suspend fun updateChannel(channelId: ChannelId, visibility: ChannelVisibility): DataResult<ChannelUpdateResult>
    suspend fun updateChannelProfile(request: ChannelProfileUpdateRequest): DataResult<ChannelProfileUpdateResult>
    suspend fun setChannelActivityMuted(channelId: ChannelId, muted: Boolean): DataResult<ChannelActivityMuteSettings>
}

enum class ChannelVisibility(val wireValue: String) {
    Public("public"),
    Private("private")
}

data class ChannelUpdateResult(val channelId: ChannelId)

data class ChannelProfileUpdateRequest(
    val channelId: ChannelId,
    val name: String,
    val description: String
)

data class ChannelProfileUpdateResult(
    val channelId: ChannelId,
    val name: String,
    val description: String?
)

data class ChannelActivityMuteSettings(
    val channelId: ChannelId,
    val activityNotificationPreference: ChannelActivityNotificationPreference,
    /**
     * Server-authoritative per-(member, channel) notification-preferences
     * version. Optimistic client predictions deliberately leave this null so
     * only a response or realtime event can advance the durable version gate.
     */
    val prefsVersion: Long? = null
) {
    constructor(
        channelId: ChannelId,
        activityMuted: Boolean,
        muteFromSeq: Long?,
        activityMuteSupported: Boolean,
        prefsVersion: Long? = null
    ) : this(
        channelId = channelId,
        activityNotificationPreference =
            ChannelActivityNotificationPreference(
                activityMuted = activityMuted,
                muteFromSeq = muteFromSeq,
                activityMuteSupported = activityMuteSupported
            ),
        prefsVersion = prefsVersion
    )

    val activityMuted: Boolean
        get() = activityNotificationPreference.activityMuted
    val muteFromSeq: Long?
        get() = activityNotificationPreference.muteFromSeq
    val activityMuteSupported: Boolean
        get() = activityNotificationPreference.activityMuteSupported
}

interface ReadReceiptRepository {
    suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot>
    suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult>
    suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult>
    suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult>
    suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult>
}

interface InboxRepository {
    suspend fun loadInitial(
        serverId: ServerId,
        filter: InboxFilter = InboxFilter.All,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<InboxSnapshot>

    suspend fun loadMore(
        serverId: ServerId,
        filter: InboxFilter = InboxFilter.All,
        offset: Int,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<InboxSnapshot>

    suspend fun refresh(
        serverId: ServerId,
        filter: InboxFilter = InboxFilter.All,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<InboxSnapshot> = loadInitial(serverId, filter, limit)

    suspend fun markDone(
        channelId: ChannelId,
        frontier: InboxDoneFrontier = InboxDoneFrontier.Legacy
    ): DataResult<InboxDoneResult>
}

interface SavedRepository {
    suspend fun loadInitial(
        serverId: ServerId,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<SavedSnapshot>

    suspend fun loadMore(
        serverId: ServerId,
        offset: Int,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<SavedSnapshot>

    suspend fun saveMessage(
        serverId: ServerId,
        messageId: MessageId
    ): DataResult<SavedMessageMutationResult>

    suspend fun unsaveMessage(
        serverId: ServerId,
        messageId: MessageId
    ): DataResult<SavedMessageMutationResult>
}

interface SavedStatusRepository {
    suspend fun checkSavedMessages(
        serverId: ServerId,
        messageIds: List<MessageId>
    ): DataResult<SavedMessageStatusSnapshot>
}

interface SearchRepository {
    suspend fun search(
        serverId: ServerId,
        query: String,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
        options: SearchQueryOptions = SearchQueryOptions()
    ): DataResult<SearchSnapshot>
}

data class SearchQueryOptions(
    val sort: SearchSort = SearchSort.Relevance,
    val senderIds: List<String> = emptyList(),
    val scopes: List<SearchScope> = emptyList(),
    val channelId: ChannelId? = null,
    val afterIso: String? = null,
    val beforeIso: String? = null
)

fun SearchQueryOptions.hasMeaningfulFilter(): Boolean =
    senderIds.any { it.isNotBlank() } ||
        scopes.isNotEmpty() ||
        (channelId?.value?.isNotBlank() == true) ||
        !afterIso.isNullOrBlank() ||
        !beforeIso.isNullOrBlank()

enum class SearchScope(val key: String, val label: String) {
    Mentioned("mentioned", "@ Me"),
    Humans("humans", "Humans"),
    Agents("agents", "Agents")
}

internal fun List<SearchScope>.exclusiveSenderTypeWireValue(): String? {
    val hasHumans = SearchScope.Humans in this
    val hasAgents = SearchScope.Agents in this
    if (hasHumans == hasAgents) return null
    return if (hasHumans) "user" else "agent"
}

enum class SearchSort {
    Relevance,
    Recent;

    val wireValue: String?
        get() =
            when (this) {
                Relevance -> null
                Recent -> "recent"
            }
}

interface RealtimeRepository {
    suspend fun connect(serverId: ServerId, lastSeq: Long = 0): DataResult<Unit>
    fun disconnect()
    fun joinChannel(channelId: ChannelId)
    fun leaveChannel(channelId: ChannelId)
    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription
}

fun interface RealtimeSubscription {
    fun close()
}

data class TaskFilter(
    val statuses: Set<TaskStatus> = emptySet(),
    val assignedToMeOnly: Boolean = false
)

enum class InboxFilter(val wireValue: String) {
    All("all"),
    Unread("unread"),
    Mentions("mentions")
}

const val DEFAULT_PAGE_LIMIT = 30

data class AccountProfileUpdateRequest(
    val displayName: String,
    val handle: String,
    val email: String
)

data class AccountPasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String
)

data class AccountAvatarUploadRequest(
    val file: PlatformFile
)

data class AccountProviderLinkStartRequest(
    val providerId: String,
    val returnTo: String = ""
)

data class AccountProviderLinkStartResult(
    val providerId: String,
    /** Null means the platform-owned mobile OAuth flow started successfully. */
    val url: String?
)

data class SettingsConnectedAppRegisterRequest(
    val name: String,
    val clientId: String? = null,
    val description: String? = null,
    val homepageUrl: String? = null,
    val returnUrl: String? = null,
    val agentManifestUrl: String? = null
)

data class SettingsConnectedAppUpdateRequest(
    val id: String,
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val returnUrl: String? = null,
    val agentManifestUrl: String? = null
)

data class SettingsConnectedAppLogoUploadRequest(
    val id: String,
    val file: PlatformFile
)

data class SettingsConnectedAppPublishRequest(
    val id: String,
    val category: String,
    val dataAccessSummary: String? = null
)

data class SettingsServerProfileUpdateRequest(
    val name: String? = null,
    val hideHumansFromMembers: Boolean? = null
)

data class SettingsServerInviteCreateRequest(
    val email: String,
    /** `member` | `guest` (server `POST /servers/:id/invites`); null lets the server default to member. */
    val role: String? = null
)

data class SettingsServerDeleteRequest(
    val confirmationName: String
)

data class SettingsServerJoinLinkCreateRequest(
    val expiresAt: String? = null,
    val maxUses: Int? = null
)

data class SettingsServerAgreementUpdateRequest(
    val enabled: Boolean,
    val title: String,
    val bodyMarkdown: String
)

data class SettingsServerOnboardingUpdateRequest(
    val setupModalReminderOptOut: Boolean? = null,
    val onboardingAgentId: String? = null,
    val clearOnboardingAgent: Boolean = false,
    val agentAllChannelGreetingEnabled: Boolean? = null
)

data class SettingsServerTranslationUpdateRequest(
    val translationEnabled: Boolean
)

data class SettingsServerSidebarOrderUpdateRequest(
    val channelOrder: List<String>? = null,
    val agentOrder: List<String>? = null,
    val dmOrder: List<String>? = null,
    val channelSortMode: String? = null,
    val jointChannelSortMode: String? = null,
    val dmSortMode: String? = null,
    val pinnedSortMode: String? = null,
    val pinned: List<SettingsServerSidebarPinnedItem>? = null,
    val pinnedChannelIds: List<String>? = null,
    val pinnedAgentIds: List<String>? = null,
    val pinnedOrder: List<String>? = null,
    val hiddenDmIds: List<String>? = null,
    val channelPanelTabOrder: List<String>? = null,
    val agentPanelTabOrder: List<String>? = null,
    // task #512: custom-section fields. The server requires sectionsVersion
    // (OCC) whenever any of customSections/sectionOrder/sectionPlacements is
    // present; it answers a stale version with 409 SIDEBAR_SECTIONS_VERSION_CONFLICT.
    val customSections: List<SettingsServerSidebarCustomSection>? = null,
    val sectionOrder: List<String>? = null,
    val sectionPlacements: List<SettingsServerSidebarSectionPlacement>? = null,
    val sectionsVersion: Long? = null
)

data class SettingsServerOrderSnapshot(
    val serverOrder: List<String> = emptyList()
)

data class SettingsServerOrderUpdateRequest(
    val serverOrder: List<String>
)

data class SettingsServerMemberRoleUpdateRequest(
    val memberId: String,
    val role: String
)

data class SettingsServerActionResult(
    val success: Boolean,
    val message: String? = null
)

data class SettingsComputerRegisterRequest(
    val name: String
)

data class SettingsComputerRenameRequest(
    val machineId: String,
    val name: String
)

data class SettingsComputerDescriptionUpdateRequest(
    val machineId: String,
    val description: String
)

data class SettingsComputerWorkspaceDeleteRequest(
    val machineId: String,
    val directoryName: String
)

data class SettingsComputerRegistrationResult(
    val machine: SettingsComputerSnapshot,
    val apiKey: String? = null,
    val apiKeyPrefix: String
)

data class SettingsComputerKeyRotationResult(
    val machineId: String,
    val apiKeyPrefix: String
)

data class SettingsComputerRuntimeModelsSnapshot(
    val machineId: String,
    val runtime: String,
    val models: List<String>,
    val defaultModel: String? = null
)

/** A web Upgrade click: the policy's exact target, as `POST …/computer/upgrade` requires it. */
data class SettingsComputerUpgradeRequest(
    val machineId: String,
    val targetVersion: String
)

/** A restart the server relayed (or queued); [requestId] correlates the `computer:restart:done` frame. */
data class SettingsComputerRestartResult(
    val machineId: String,
    val requestId: String? = null,
    val queued: Boolean = false
)

data class SettingsComputerActionResult(
    val machineId: String,
    val success: Boolean,
    val action: String,
    val directoryName: String? = null
)

data class CreateServerRequest(
    val name: String,
    val slug: String? = null,
    val region: String? = null
)

data class CreateChannelRequest(
    val name: String,
    val description: String? = null,
    val visibility: String = "public",
    val agentMemberIds: List<String> = emptyList(),
    val userMemberIds: List<String> = emptyList(),
    val targetServerSlug: String? = null,
    val invitedPeople: List<String> = emptyList()
)

data class AddChannelMemberRequest(
    val channelId: ChannelId,
    val memberId: MemberId,
    val kind: MemberKind
)

/**
 * Atomic batch add of channel members (task #352, Web #7209).
 *
 * One request carries BOTH humans and agents (server dedupes, validates the
 * whole batch, then writes; a later failure rolls back earlier members, so there
 * is no partial success). This is the preferred path for multi-select add;
 * [AddChannelMemberRequest] remains for single adds.
 */
data class AddChannelMembersBatchRequest(
    val channelId: ChannelId,
    val userIds: List<MemberId>,
    val agentIds: List<MemberId>
)

/**
 * Result of an atomic batch add. [addedUserIds]/[addedAgentIds] were newly added;
 * [alreadyMemberUserIds]/[alreadyMemberAgentIds] were requested but already
 * members. Re-sending the same batch is idempotent: `added` is empty and
 * everything requested shows up under `alreadyMembers`.
 */
data class AddChannelMembersBatchResult(
    val addedUserIds: List<String>,
    val addedAgentIds: List<String>,
    val alreadyMemberUserIds: List<String>,
    val alreadyMemberAgentIds: List<String>
) {
    /** True when nothing was newly added (idempotent re-send): still a success. */
    val addedNothing: Boolean get() = addedUserIds.isEmpty() && addedAgentIds.isEmpty()
}

data class RemoveChannelMemberRequest(
    val channelId: ChannelId,
    val memberId: MemberId,
    val kind: MemberKind
)

data class AgentProfileUpdateRequest(
    val agentId: MemberId,
    val displayName: String,
    val description: String?,
    val avatarUrl: String? = null,
    val includeAvatarUrl: Boolean = false,
    val serverRole: String? = null,
    val includeServerRole: Boolean = false,
    val runtimeConfig: build.raft.app.model.RuntimeConfig? = null,
    val includeRuntimeConfig: Boolean = false,
    /**
     * Update-only Built-in secret retention. When true the wire body omits
     * provider.apiKey; the server retains it only if provider identity still
     * matches. This never recovers or serializes the existing write-only value.
     */
    val retainOmittedBuiltInProviderSecret: Boolean = false,
    val runtimeResetMode: AgentResetMode? = null,
    /**
     * kimi-sdk (task #275): the exact runtime-form ref validated against the
     * live catalog at save time. Emitted on the wire only together with a
     * non-null runtimeConfig (see buildSharedAgentProfileUpdateBody); a null
     * value keeps the legacy no-ref retention path.
     */
    val formDefinitionRef: SharedRuntimeFormDefinitionRef? = null,
    /**
     * Runtime form v2 edit: `formDefinitionRef {protocolVersion:2}` + `formValues`.
     * Use with includeRuntimeConfig = false; a blank writeOnly field (sent as "")
     * means "keep the stored value".
     */
    val runtimeFormV2: build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody? = null
)

data class AgentAvatarUploadRequest(
    val agentId: MemberId,
    val file: PlatformFile
)

enum class AgentResetMode(val wireValue: String) {
    Session("session"),
    Restart("restart"),
    Full("full")
}

data class AgentResetRequest(
    val agentId: MemberId,
    val mode: AgentResetMode
)

data class AgentMachineAssignmentRequest(
    val agentId: MemberId,
    val machineId: String?
)

data class AgentLifecycleActionResult(
    val agentId: MemberId,
    val success: Boolean,
    val action: String,
    val resetMode: AgentResetMode? = null,
    val machineId: String? = null
)

data class ChannelMemberActionResult(
    val channelId: ChannelId,
    val memberId: MemberId,
    val member: MemberProfile? = null,
    val added: Boolean = true
)
