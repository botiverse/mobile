package ai.slock.compose

import ai.slock.compose.data.DataResult
import ai.slock.compose.data.EmptyReason
import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.ThreadActionResult
import ai.slock.compose.data.AttachmentUploadFile
import ai.slock.compose.data.AttachmentUploadRequest
import ai.slock.compose.data.AttachmentUploadResult
import ai.slock.compose.data.CreateAgentBody
import ai.slock.compose.data.CreateChannelRequest
import ai.slock.compose.data.appendSharedThreadSentMessageFromPage
import ai.slock.compose.data.buildSharedThreadPayloadFromMessagesPage
import ai.slock.compose.data.buildCreateAgentBody
import ai.slock.compose.data.buildMarkAgentActionExecutedBody
import ai.slock.compose.data.buildMarkChannelActionExecutedBody
import ai.slock.compose.data.buildMarkChannelMembersActionExecutedBody
import ai.slock.compose.data.ChannelFilesSnapshot
import ai.slock.compose.data.ChannelMemberActionResult
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.ConversationRepository
import ai.slock.compose.data.AddChannelMemberRequest
import ai.slock.compose.data.ChannelRepository
import ai.slock.compose.data.ReadReceiptResult
import ai.slock.compose.data.RemoveChannelMemberRequest
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.DataRequestPriority
import ai.slock.compose.data.MemberRepository
import ai.slock.compose.data.MessageMutationSummary
import ai.slock.compose.data.MessageReactionSummary
import ai.slock.compose.data.PendingMentionActionExecuteResult
import ai.slock.compose.data.SlockAgentsApiClient
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.SlockIntegrationsActionsPushApiClient
import ai.slock.compose.data.SavedStatusRepository
import ai.slock.compose.data.SlockThreadApi
import ai.slock.compose.data.SlockThreadApiClient
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.SharedThreadNetworkApi
import ai.slock.compose.data.ThreadActionCard
import ai.slock.compose.data.ThreadAttachmentCommentsPage
import ai.slock.compose.data.ThreadCandidateProofGate
import ai.slock.compose.data.ThreadIdentityProof
import ai.slock.compose.data.ThreadRouteId
import ai.slock.compose.data.ThreadSnapshot
import ai.slock.compose.data.ThreadApiResult
import ai.slock.compose.data.ThreadAttachmentOpenTarget
import ai.slock.compose.data.ThreadMessagesPage
import ai.slock.compose.data.authSessionRequiredResult
import ai.slock.compose.data.map
import ai.slock.compose.data.toMessageMetadata
import ai.slock.compose.data.toThreadRouteId
import ai.slock.compose.data.withExecutedAgent
import ai.slock.compose.data.withExecutedChannel
import ai.slock.compose.data.withExecutedChannelMembers
import ai.slock.compose.model.Attachment
import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageCommentHostSource
import ai.slock.compose.model.MessageCommentRef
import ai.slock.compose.model.MessageDeliveryState
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageReaction
import ai.slock.compose.model.MessageType
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskDeleted
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.ThreadUpdated
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import ai.slock.compose.model.formatSlockTimestampLabel
import ai.slock.compose.model.isGravatarAvatarWireValue
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

internal class SharedThreadDataSource(
    private val api: SlockThreadApi,
    private val sessionStore: SlockAuthSessionStore,
    private val localWriteThrough: ComposeLocalWriteThrough? = null,
    private val memberRepository: MemberRepository? = null,
    private val conversationRepository: ConversationRepository? = null,
    private val channelRepository: ChannelRepository? = null,
    private val agentsApiClient: SlockAgentsApiClient? = null,
    private val actionCardsApiClient: SlockIntegrationsActionsPushApiClient? = null,
    private val savedStatusRepository: SavedStatusRepository? = null,
    /**
     * Task #43: shared create-agent coordinator used by the action-card
     * path. Mirrors the field on `SharedMembersDataSource`; the actual
     * body construction + API call lives in
     * [ai.slock.compose.data.SharedCreateAgentCoordinator] so the
     * Members page and Thread action card share the same code path.
     */
    private val createAgentCoordinator: ai.slock.compose.data.SharedCreateAgentCoordinator? = null
) {
    /**
     * Public accessor for the [ai.slock.compose.data.SharedCreateAgentCoordinator]
     * field. Used by callers (e.g. `SettingsHomePage` which constructs
     * `SharedThreadDataSource` and forwards to `SharedThreadRouteContent`)
     * that need the coordinator to wire up the action-card create-agent
     * dialog. Returns null when the data source was constructed without
     * a coordinator; in that case the create-agent action card falls back
     * to the legacy name/description/machineId-only path.
     */
    val createAgentCoordinatorOrNull: ai.slock.compose.data.SharedCreateAgentCoordinator?
        get() = createAgentCoordinator

    constructor(
        networkApi: SharedThreadNetworkApi,
        sessionStore: SlockAuthSessionStore,
        localWriteThrough: ComposeLocalWriteThrough? = null,
        memberRepository: MemberRepository? = null,
        conversationRepository: ConversationRepository? = null,
        channelRepository: ChannelRepository? = null,
        agentsApiClient: SlockAgentsApiClient? = null,
        actionCardsApiClient: SlockIntegrationsActionsPushApiClient? = null,
        savedStatusRepository: SavedStatusRepository? = null,
        createAgentCoordinator: ai.slock.compose.data.SharedCreateAgentCoordinator? = null,
        diagnosticsLogger: (String) -> Unit = {}
    ) : this(
        api = SlockThreadApiClient(networkApi, diagnosticsLogger),
        sessionStore = sessionStore,
        localWriteThrough = localWriteThrough,
        memberRepository = memberRepository,
        conversationRepository = conversationRepository,
        channelRepository = channelRepository,
        agentsApiClient = agentsApiClient,
        actionCardsApiClient = actionCardsApiClient,
        savedStatusRepository = savedStatusRepository,
        createAgentCoordinator = createAgentCoordinator
    )

    private val payloadCache = mutableMapOf<String, KuiklyThreadScreenPayload>()
    private val senderProfilesCache = mutableMapOf<String, Map<String, MemberProfile>>()

    suspend fun readSession(): StoredAuthSession? = sessionStore.readSession()

    fun cachePayload(payload: KuiklyThreadScreenPayload) {
        val threadId = payload.sourceThreadId?.takeIf { it.isNotBlank() } ?: return
        if (payload.hasRenderableThreadContent()) {
            payloadCache[threadId] = payload.copy(status = "content", errorCode = null)
        }
    }

    fun cachedPayload(threadId: String): KuiklyThreadScreenPayload? = payloadCache[threadId]

    suspend fun readCachedPayload(basePayload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload? {
        val threadId = basePayload.cacheThreadId() ?: return null
        val snapshot = localWriteThrough?.readThreadSnapshot(ThreadId(threadId)) ?: return null
        val session = readSession()
        val serverId = session?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val proof =
            ThreadCandidateProofGate.prove(
                route = basePayload.toThreadRouteId(serverId),
                candidate = snapshot.toThreadRouteId(serverId)
            )
        if (proof !is ThreadIdentityProof.Accepted) return null
        return snapshot
            .toKuiklyThreadPayload(basePayload, session?.user?.id)
            .trimToLatestThreadWindow(SharedThreadPaging.initialPageSize)
    }

    suspend fun writeCachedPayload(payload: KuiklyThreadScreenPayload) {
        val snapshot = payload.toThreadSnapshot() ?: return
        localWriteThrough?.writeThreadResult(
            result = DataResult.Content(snapshot),
            source = "shared_thread_data_source",
            fallbackThreadId = payload.cacheThreadId()?.let(::ThreadId),
            fallbackHeader = snapshot.conversation,
            fallbackParentMessage = snapshot.parentMessage
        )
    }

    suspend fun commitAgentCreateActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        return commitAgentCreateActionCardInternal(messageId, card)
    }

    /**
     * Task #43: action-card create-agent path that goes through the shared
     * [SharedCreateAgentCoordinator]. The dialog collects a full
     * [SharedCreateAgentDialogSubmit] (name, description, machineId,
     * runtimeConfig, external); the coordinator owns the body construction
     * and the API call. The action card is just the metadata that gets
     * marked executed on success.
     *
     * Replaces the legacy `commitAgentCreateActionCard(messageId, card)`
     * which only used name/description/machineId from the action card.
     */
    suspend fun commitAgentCreateActionCardFromDialog(
        messageId: String,
        card: ThreadActionCard,
        submit: SharedCreateAgentDialogSubmit
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("agent:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val actionsApi = actionCardsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val coordinator = createAgentCoordinator
            ?: return DataResult.Error(ScreenError(code = "action_card_coordinator_missing", retryable = false))

        val created = coordinator.createAgent(submit.toCreateAgentRequest())
        return when (created) {
            is DataResult.Content -> {
                val resultName = created.data.name.takeIf { it.isNotBlank() } ?: submit.name
                val executedCard = card.withExecutedAgent(id = created.data.id, name = resultName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body = buildMarkAgentActionExecutedBody(agentId = created.data.id, agentName = resultName),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "agent_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    suspend fun commitThreadActionCard(
        payload: KuiklyThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        return when {
            card.type.equals("agent:create", ignoreCase = true) ->
                DataResult.Error(ScreenError(code = "action_card_agent_create_dialog_required", retryable = false))
            card.type.equals("channel:create", ignoreCase = true) ->
                commitChannelCreateActionCard(messageId, card)
            card.type.equals("channel:add_member", ignoreCase = true) ->
                commitChannelAddMembersActionCard(payload, messageId, card)
            else ->
                DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
    }

    private suspend fun commitAgentCreateActionCardInternal(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("agent:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val agentName = card.name.trim()
        if (agentName.isBlank()) {
            return DataResult.Error(ScreenError(code = "action_card_agent_name_missing", retryable = false))
        }
        val agentsApi = agentsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_agents_api_missing", retryable = false))
        val actionsApi = actionCardsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))

        return when (
            val created =
                agentsApi.createAgent(
                    buildCreateAgentBody(
                        CreateAgentBody(
                            name = agentName.removePrefix("@"),
                            description = card.description?.takeIf { it.isNotBlank() },
                            machineId = card.requiredComputer?.takeIf { it.isNotBlank() }
                                ?: card.suggestedComputer?.takeIf { it.isNotBlank() }
                        )
                    )
                )
        ) {
            is DataResult.Content -> {
                val resultName = created.data.name.takeIf { it.isNotBlank() } ?: agentName.removePrefix("@")
                val executedCard = card.withExecutedAgent(id = created.data.id, name = resultName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body = buildMarkAgentActionExecutedBody(agentId = created.data.id, agentName = resultName),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "agent_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    private suspend fun commitChannelCreateActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("channel:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val channelName = card.name.trim()
        if (channelName.isBlank()) {
            return DataResult.Error(ScreenError(code = "action_card_channel_name_missing", retryable = false))
        }
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "action_card_channel_repository_missing", retryable = false))
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        return when (
            val created =
                repository.createChannel(
                    CreateChannelRequest(
                        name = channelName,
                        description = card.description?.trim()?.takeIf { it.isNotBlank() },
                        visibility = card.visibility?.trim()?.takeIf { it.isNotBlank() } ?: "public",
                        agentMemberIds = card.initialAgents.cleanActionCardIds(),
                        userMemberIds = card.initialHumans.cleanActionCardIds()
                    )
                )
        ) {
            is DataResult.Content -> {
                val channel = created.data.channel
                val createdChannelId = channel.channelId?.value ?: channel.threadId.value
                val createdChannelName = channel.name.takeIf { it.isNotBlank() } ?: channelName
                val executedCard = card.withExecutedChannel(id = createdChannelId, name = createdChannelName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body =
                        buildMarkChannelActionExecutedBody(
                            channelId = createdChannelId,
                            channelName = createdChannelName
                        ),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "channel_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    private suspend fun commitChannelAddMembersActionCard(
        payload: KuiklyThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("channel:add_member", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val targetChannelId =
            card.channel?.trim()?.takeIf { it.isNotBlank() }
                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "action_card_channel_missing", retryable = false))
        val humanIds = card.humans.cleanActionCardIds()
        val agentIds = card.agents.cleanActionCardIds()
        if (humanIds.isEmpty() && agentIds.isEmpty()) {
            return DataResult.Error(ScreenError(code = "action_card_members_missing", retryable = false))
        }
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "action_card_member_repository_missing", retryable = false))
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val channelId = ChannelId(targetChannelId)
        val addedHumans = mutableListOf<String>()
        val addedAgents = mutableListOf<String>()
        var firstFailure: DataResult<ChannelMemberActionResult>? = null

        for (humanId in humanIds) {
            val result =
                repository.addChannelMember(
                    DataRequestPriority.Foreground,
                    AddChannelMemberRequest(channelId = channelId, memberId = MemberId(humanId), kind = MemberKind.Human)
                )
            if (result is DataResult.Content) {
                addedHumans += humanId
            } else if (firstFailure == null) {
                firstFailure = result
            }
        }
        for (agentId in agentIds) {
            val result =
                repository.addChannelMember(
                    DataRequestPriority.Foreground,
                    AddChannelMemberRequest(channelId = channelId, memberId = MemberId(agentId), kind = MemberKind.Agent)
                )
            if (result is DataResult.Content) {
                addedAgents += agentId
            } else if (firstFailure == null) {
                firstFailure = result
            }
        }
        if (addedHumans.isEmpty() && addedAgents.isEmpty()) {
            return when (val failure = firstFailure) {
                is DataResult.Empty -> DataResult.Empty(failure.reason)
                is DataResult.Error -> failure
                is DataResult.NoPermission -> failure
                else -> DataResult.Error(ScreenError(code = "channel_members_add_failed", retryable = true))
            }
        }
        val channelName =
            payload.title.takeIf { targetChannelId == payload.sourceThreadId && it.isNotBlank() }
                ?: card.resultChannelName?.takeIf { it.isNotBlank() }
                ?: targetChannelId
        val executedCard =
            card.withExecutedChannelMembers(
                channelId = targetChannelId,
                channelName = channelName,
                addedHumanIds = addedHumans,
                addedAgentIds = addedAgents
            )
        return markThreadActionCardExecuted(
            actionsApi = actionsApi,
            messageId = messageId,
            body =
                buildMarkChannelMembersActionExecutedBody(
                    channelId = targetChannelId,
                    channelName = channelName,
                    addedHumanIds = addedHumans,
                    addedAgentIds = addedAgents
                ),
            executedCard = executedCard
        )
    }

    private suspend fun markThreadActionCardExecuted(
        actionsApi: SlockIntegrationsActionsPushApiClient,
        messageId: String,
        body: String,
        executedCard: ThreadActionCard
    ): DataResult<ThreadActionCard> =
        when (val result = actionsApi.markActionExecuted(MessageId(messageId), body)) {
            is DataResult.Content -> DataResult.Content(executedCard)
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "action_card_mark_executed_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }

    suspend fun fetchThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        persistResult: Boolean = true
    ): DataResult<KuiklyThreadScreenPayload> {
        val resolvedBasePayload =
            when (val resolved = resolveReplyThreadStream(basePayload)) {
                is DataResult.Content -> resolved.data
                is DataResult.Empty -> return resolved
                is DataResult.Error -> return resolved
                is DataResult.NoPermission -> return resolved
            }
        val threadId =
            resolvedBasePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        val payloadResult =
            if (conversationRepository != null) {
                fetchInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = resolvedBasePayload,
                    threadId = ThreadId(threadId)
                )
            } else {
                val response =
                    try {
                        api.channelMessages(threadId, limit = SharedThreadPaging.initialPageSize)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_network", retryable = true))
                    }
                val page =
                    when (response) {
                        is ThreadApiResult.Success -> response.data
                        is ThreadApiResult.Failure ->
                            return response.toThreadDataError("thread_network")
                    }
                DataResult.Content(
                    buildSharedThreadPayloadFromMessagesPage(
                        basePayload = resolvedBasePayload,
                        page = page,
                        session = session
                    ).withCachedSenderProfiles(session)
                )
            }
        val payload =
            when (payloadResult) {
                is DataResult.Content -> payloadResult.data.withSavedStatus(session)
                is DataResult.Empty -> return payloadResult
                is DataResult.Error -> return payloadResult
                is DataResult.NoPermission -> return payloadResult
            }
        if (persistResult) {
            cachePayload(payload)
            writeCachedPayload(payload)
        }
        return DataResult.Content(payload)
    }

    private suspend fun resolveReplyThreadStream(
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val repository = conversationRepository ?: return DataResult.Content(basePayload)
        if (!basePayload.needsReplyThreadStreamResolution()) {
            return DataResult.Content(basePayload)
        }
        return createOrOpenReplyThreadStream(basePayload, repository)
    }

    private suspend fun createOrOpenReplyThreadStream(
        basePayload: KuiklyThreadScreenPayload,
        repository: ConversationRepository? = conversationRepository
    ): DataResult<KuiklyThreadScreenPayload> {
        val resolvedRepository =
            repository
                ?: return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        val actionResult =
            try {
                resolvedRepository.createOrOpenThread(ChannelId(parentChannelId), MessageId(parentMessageId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
            }
        return when (actionResult) {
            is DataResult.Content -> DataResult.Content(basePayload.withResolvedReplyThreadAction(actionResult.data))
            is DataResult.Empty -> actionResult
            is DataResult.Error -> actionResult
            is DataResult.NoPermission -> actionResult
        }
    }

    suspend fun fetchWebLinkThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        target: SlockThreadWebLinkTarget
    ): DataResult<KuiklyThreadScreenPayload> {
        val messageId = target.messageId?.takeIf { it.isNotBlank() }
        if (messageId == null || conversationRepository == null) {
            return fetchThreadPayload(basePayload)
        }
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        val contextResult =
            fetchThreadPayloadFromRepository(
                session = session,
                fallbackCode = "thread_weblink_context",
                call = { repository -> repository.getMessageContext(MessageId(messageId)) },
                transform = { snapshot -> snapshot.toKuiklyThreadPayload(basePayload, session.user.id) }
            )
        return when (contextResult) {
            is DataResult.Content -> contextResult
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> fetchThreadPayload(basePayload)
        }
    }

    suspend fun enrichSenderProfiles(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val session = readSession() ?: return payload
        return payload.withSenderProfiles(session)
    }

    suspend fun enrichCachedSenderProfiles(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val session = readSession() ?: return payload
        return payload.withCachedSenderProfiles(session)
    }

    /**
     * Outcome of an attempt to enrich the current thread payload with
     * fresh member / candidate data. The tri-state lets the caller
     * distinguish a real "no change" success from a transient failure
     * (network / repository exception, NoPermission, Error) so that
     * idempotent LoadMembers can decide whether to enter Loaded or
     * Error.
     */
    internal sealed class MembersEnrichOutcome {
        data class Updated(val payload: KuiklyThreadScreenPayload) : MembersEnrichOutcome()
        data object Unchanged : MembersEnrichOutcome()
        data class Failed(val reason: String) : MembersEnrichOutcome()
    }

    suspend fun enrichThreadMembers(payload: KuiklyThreadScreenPayload): MembersEnrichOutcome {
        val repository = memberRepository ?: return MembersEnrichOutcome.Unchanged
        val threadId = payload.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return MembersEnrichOutcome.Unchanged
        val channelMembers =
            try {
                repository.getChannelMembers(DataRequestPriority.Visible, ChannelId(threadId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                return MembersEnrichOutcome.Failed("getChannelMembers threw: ${t::class.simpleName}")
            }
        if (channelMembers !is DataResult.Content) {
            // Reached only for Empty / NoPermission / Error. Treat
            // Empty as success-no-data (Loaded), NoPermission / Error
            // as transient failure so the next modal open can retry.
            return when (val result = channelMembers) {
                is DataResult.Empty -> MembersEnrichOutcome.Unchanged
                is DataResult.NoPermission ->
                    MembersEnrichOutcome.Failed("getChannelMembers: NoPermission")
                is DataResult.Error ->
                    MembersEnrichOutcome.Failed("getChannelMembers: Error(${result.error.code})")
                is DataResult.Content -> error("unreachable: filtered by !is DataResult.Content above")
            }
        }
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val homeAvatarByMemberId =
            serverId
                ?.let { localWriteThrough?.readHomeSnapshot(it)?.directMessageAvatarWireValuesByMemberId() }
                .orEmpty()
        val serverMembers =
            if (serverId == null) {
                null
            } else {
                try {
                    when (val result = repository.getMembers(DataRequestPriority.Visible, serverId)) {
                        is DataResult.Content -> result.data.members
                        is DataResult.Empty,
                        is DataResult.Error,
                        is DataResult.NoPermission -> null
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    null
                }
            }
        val serverMembersByKey = serverMembers.orEmpty().associateBy { it.memberKey }
        val members =
            channelMembers.data.members
                .distinctBy { it.memberKey }
                .map { member ->
                    member
                        .withProfileAvatarFallback(serverMembersByKey[member.memberKey])
                        .withHomeAvatarFallback(homeAvatarByMemberId[member.id.value])
                }
        val memberKeys = members.map { it.memberKey }.toSet()
        val candidates =
            if (serverMembers == null) {
                payload.memberCandidates
            } else {
                serverMembers
                    .filterNot { it.memberKey in memberKeys }
                    .map { it.withHomeAvatarFallback(homeAvatarByMemberId[it.id.value]).toKuiklyThreadMemberItem() }
            }
        return MembersEnrichOutcome.Updated(
            payload.copy(
                membersCount = members.size,
                members = members.map { it.toKuiklyThreadMemberItem() },
                memberCandidates = candidates,
                canAddMembers = channelMembers.data.canAddMembers
            )
        )
    }

    suspend fun addChannelMember(
        payload: KuiklyThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<KuiklyThreadScreenPayload> {
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "thread_member_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val memberKind =
            kind.toThreadMemberKind()
                ?: return DataResult.Error(ScreenError(code = "thread_member_kind_missing", retryable = false))
        val cleanMemberId =
            memberId.takeIf { it.isNotBlank() }?.let(::MemberId)
                ?: return DataResult.Error(ScreenError(code = "thread_member_id_missing", retryable = false))
        return when (
            val result =
                repository.addChannelMember(
                    DataRequestPriority.Foreground,
                    AddChannelMemberRequest(
                        channelId = channelId,
                        memberId = cleanMemberId,
                        kind = memberKind
                    )
                )
        ) {
            is DataResult.Content ->
                DataResult.Content(
                    refreshMembersAfterMutation(
                        payload.copy(addMemberStatus = "idle", addMemberErrorCode = null)
                    )
                )
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun removeChannelMember(
        payload: KuiklyThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<KuiklyThreadScreenPayload> {
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "thread_member_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val memberKind =
            kind.toThreadMemberKind()
                ?: return DataResult.Error(ScreenError(code = "thread_member_kind_missing", retryable = false))
        val cleanMemberId =
            memberId.takeIf { it.isNotBlank() }?.let(::MemberId)
                ?: return DataResult.Error(ScreenError(code = "thread_member_id_missing", retryable = false))
        return when (
            val result =
                repository.removeChannelMember(
                    DataRequestPriority.Foreground,
                    RemoveChannelMemberRequest(
                        channelId = channelId,
                        memberId = cleanMemberId,
                        kind = memberKind
                    )
                )
        ) {
            is DataResult.Content ->
                DataResult.Content(
                    refreshMembersAfterMutation(
                        payload.copy(
                            removeMemberStatus = "idle",
                            removingMemberId = null,
                            removeMemberErrorCode = null
                        )
                    )
                )
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun leaveChannel(payload: KuiklyThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.leaveChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun archiveChannel(payload: KuiklyThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.archiveChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun deleteChannel(payload: KuiklyThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.deleteChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun refreshMembersAfterMutation(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        return when (val outcome = enrichThreadMembers(payload)) {
            is SharedThreadDataSource.MembersEnrichOutcome.Updated -> outcome.payload
            is SharedThreadDataSource.MembersEnrichOutcome.Unchanged,
            is SharedThreadDataSource.MembersEnrichOutcome.Failed -> payload
        }
    }

    suspend fun fetchOlderThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        beforeSeq: Long
    ): DataResult<KuiklyThreadScreenPayload> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "older",
            repositoryCall = { repository, threadId -> repository.loadOlderMessages(threadId, beforeSeq, SharedThreadPaging.pageSize) },
            call = { threadId -> api.channelMessages(threadId, limit = SharedThreadPaging.pageSize, beforeSeq = beforeSeq) }
        )

    suspend fun fetchNewerThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        afterSeq: Long
    ): DataResult<KuiklyThreadScreenPayload> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "newer",
            repositoryCall = { repository, threadId -> repository.loadNewerMessages(threadId, afterSeq, SharedThreadPaging.pageSize) },
            call = { threadId -> api.messageSync(threadId, sinceSeq = afterSeq, limit = SharedThreadPaging.pageSize) }
        )

    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String> = emptyList()
    ): ThreadApiResult<ThreadMessagesPage> =
        api.sendMessage(channelId = channelId, body = body, asTask = asTask, attachmentIds = attachmentIds)

    suspend fun executePendingMentionActions(
        action: String,
        resolutionIds: List<String>
    ): ThreadApiResult<List<PendingMentionActionExecuteResult>> =
        api.executePendingMentionActions(action = action, resolutionIds = resolutionIds)

    suspend fun uploadAttachments(
        channelId: String,
        files: List<AttachmentUploadFile>
    ): DataResult<AttachmentUploadResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "attachment_upload_unavailable", retryable = false))
        if (channelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "missing_channel_id", retryable = false))
        }
        if (files.isEmpty()) {
            return DataResult.Error(ScreenError(code = "attachment_read_failed", retryable = false))
        }
        return repository.uploadAttachments(
            AttachmentUploadRequest(
                channelId = ChannelId(channelId),
                files = files
            )
        )
    }

    suspend fun attachmentUrl(attachmentId: String, disposition: String? = null): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentUrl(attachmentId, disposition)

    suspend fun fetchFilesPayload(
        basePayload: KuiklyThreadScreenPayload,
        cursor: String? = null,
        currentPayload: KuiklyThreadFilesPayload? = null
    ): DataResult<KuiklyThreadFilesPayload> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_files_unavailable", retryable = false))
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.getChannelFiles(ChannelId(threadId), cursor = cursor)) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data.toKuiklyThreadFilesPayload(
                        basePayload = basePayload,
                        currentPayload = currentPayload,
                        append = cursor != null
                    )
                )
            is DataResult.Empty ->
                DataResult.Content(
                    currentPayload
                        ?.takeIf { cursor != null && it.items.isNotEmpty() }
                        ?.copy(status = "content", hasMore = false, loadingMore = false, nextCursor = null, errorCode = null)
                        ?: KuiklyThreadFilesPayload(
                            instanceId = basePayload.instanceId,
                            status = "empty",
                            items = emptyList(),
                            hasMore = false,
                            loadingMore = false,
                            nextCursor = null
                        )
                )
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun saveMessage(messageId: String): DataResult<Unit> {
        if (messageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        val response =
            try {
                api.saveMessage(messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_save_network", retryable = true))
        }
        return response.toUnitDataResult("thread_save_network")
    }

    suspend fun unsaveMessage(messageId: String): DataResult<Unit> {
        if (messageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        val response =
            try {
                api.unsaveMessage(messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_unsave_network", retryable = true))
            }
        return response.toUnitDataResult("thread_unsave_network")
    }

    suspend fun setMessageReaction(messageId: String, emoji: String, selected: Boolean): DataResult<MessageMutationSummary> {
        val cleanMessageId = messageId.trim()
        val cleanEmoji = emoji.trim()
        if (cleanMessageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        if (cleanEmoji.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_reaction_missing", retryable = false))
        }
        val response =
            try {
                if (selected) {
                    api.addReaction(cleanMessageId, cleanEmoji)
                } else {
                    api.removeReaction(cleanMessageId, cleanEmoji)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_reaction_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(response.data)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_reaction_network")
        }
    }

    suspend fun loadAttachmentComments(attachmentId: String): DataResult<ThreadAttachmentCommentsPage> {
        val cleanAttachmentId = attachmentId.trim()
        if (cleanAttachmentId.isBlank()) {
            return DataResult.Error(ScreenError(code = "attachment_id_missing", retryable = false))
        }
        val response =
            try {
                api.attachmentComments(cleanAttachmentId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_attachment_comments_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(response.data)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_attachment_comments_network")
        }
    }

    suspend fun sendAttachmentComment(attachmentId: String, body: String): DataResult<Unit> {
        val cleanAttachmentId = attachmentId.trim()
        val cleanBody = body.trim()
        if (cleanAttachmentId.isBlank() || cleanBody.isBlank()) {
            return DataResult.Error(ScreenError(code = "attachment_comment_missing", retryable = false))
        }
        val response =
            try {
                api.sendAttachmentComment(cleanAttachmentId, cleanBody)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_attachment_comment_send_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(Unit)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_attachment_comment_send_network")
        }
    }

    suspend fun updateTaskStatus(taskId: String, status: TaskStatus): DataResult<Unit> {
        if (taskId.isBlank()) {
            return DataResult.Error(ScreenError(code = "task_id_missing", retryable = false))
        }
        val response =
            try {
                api.updateTaskStatus(taskId, status)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_task_status_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(Unit)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_task_status_network")
        }
    }

    suspend fun createOrOpenReplyThread(
        basePayload: KuiklyThreadScreenPayload,
        parentMessage: KuiklyThreadMessageItem
    ): DataResult<KuiklyThreadScreenPayload> {
        val parentChannelId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        val actionResult =
            if (conversationRepository != null) {
                try {
                    conversationRepository.createOrOpenThread(ChannelId(parentChannelId), MessageId(parentMessage.id))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
                }
            } else {
                val response =
                    try {
                        api.createOrOpenThread(parentChannelId, parentMessage.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
                    }
                when (response) {
                    is ThreadApiResult.Success -> DataResult.Content(response.data)
                    is ThreadApiResult.Failure -> return response.toThreadDataError("thread_reply_network")
                }
            }
        val action =
            when (actionResult) {
                is DataResult.Content -> actionResult.data
                is DataResult.Empty -> {
                    return DataResult.Empty(EmptyReason.NoMessages)
                }
                is DataResult.Error -> return actionResult
                is DataResult.NoPermission -> return actionResult
            }
        val threadChannelId = action.threadChannelId.value
        val resolvedOrigin =
            parentMessage.copy(
                threadChannelId = threadChannelId,
                replyCount = maxOf(parentMessage.replyCount, action.replyCount)
            )
        val replyBase =
            basePayload.copy(
                instanceId = "shared-shell-thread-$threadChannelId",
                title = ThreadReplyPageTitle,
                subtitle = basePayload.replyThreadHeaderSubtitle(),
                status = "loading",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = threadChannelId,
                streamChannelId = threadChannelId,
                parentChannelId = parentChannelId,
                parentMessageId = action.parentMessageId?.value ?: parentMessage.id,
                navigationLabel = "Close",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = false,
                showOpenInChannelAction = true,
                originMessage = resolvedOrigin,
                originParentMessageId = action.parentMessageId?.value ?: parentMessage.id,
                originStatus = "loaded",
                messages = emptyList(),
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = parentMessage.id,
                        enabled = basePayload.canSend,
                        mentionChannelId = parentChannelId,
                        parentMessageId = action.parentMessageId?.value ?: parentMessage.id,
                        streamChannelId = threadChannelId
                    )
            )
        return when (val loaded = fetchThreadPayload(replyBase, persistResult = false)) {
            is DataResult.Content -> {
                val payload =
                    loaded.data.copy(
                        surfaceKind = replyBase.surfaceKind,
                        streamChannelId = replyBase.streamChannelId,
                        parentChannelId = replyBase.parentChannelId,
                        parentMessageId = replyBase.parentMessageId,
                        composer = loaded.data.composer.takeUnless { it.isDefaultThreadComposer() } ?: replyBase.composer,
                        originMessage = loaded.data.originMessage ?: resolvedOrigin,
                        originParentMessageId = loaded.data.originParentMessageId ?: action.parentMessageId?.value ?: parentMessage.id,
                        originStatus = if (loaded.data.originMessage != null) loaded.data.originStatus else "loaded"
                    )
                cachePayload(payload)
                writeCachedPayload(payload)
                DataResult.Content(payload)
            }
            is DataResult.Empty -> loaded
            is DataResult.Error -> loaded
            is DataResult.NoPermission -> loaded
        }
    }

    suspend fun writeRealtimeEvent(event: RealtimeEvent) {
        writeRealtimeEvents(listOf(event))
    }

    suspend fun writeRealtimeEvents(events: List<RealtimeEvent>) {
        if (events.isEmpty()) return
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
            ?: return
        localWriteThrough?.writeRealtimeEvents(serverId, events)
    }

    suspend fun markChannelReadAll(channelId: String): DataResult<ReadReceiptResult> {
        if (channelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        }
        val response =
            try {
                api.markChannelReadAll(channelId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_read_receipt_network", retryable = true))
            }
        return if (response is ThreadApiResult.Success) {
            val id = ChannelId(channelId)
            val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
            localWriteThrough?.clearConversationUnread(id, "shared_thread_read_all", serverId)
            DataResult.Content(ReadReceiptResult(channelId = id, changed = true))
        } else {
            (response as ThreadApiResult.Failure).toThreadDataError("thread_read_receipt_network")
        }
    }

    suspend fun openMessageContext(
        messageId: String,
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val trimmedMessageId = messageId.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        return fetchThreadPayloadFromRepository(
            session = session,
            fallbackCode = "thread_parent_context_network",
            call = { repository -> repository.getMessageContext(MessageId(trimmedMessageId)) },
            transform = { snapshot ->
                snapshot.toKuiklyThreadPayload(basePayload, session.user.id)
                    .copy(showOpenInChannelAction = false)
            }
        )
    }

    suspend fun openCommentHost(
        hostMessageId: String,
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val streamThreadId = basePayload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
        if (basePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread && streamThreadId != null) {
            return fetchThreadPayload(basePayload, persistResult = false)
        }
        return openMessageContext(hostMessageId, basePayload)
    }

    suspend fun fetchQuotedPermalinkPreview(
        permalink: SlockQuotedMessagePermalink
    ): DataResult<ThreadQuotedMessagePreviewUiState> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_permalink_context_unavailable", retryable = false))
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        if (permalink.serverSlug != session.currentServerSlug()) {
            return DataResult.Empty(EmptyReason.NoMessages)
        }
        val snapshot =
            try {
                repository.getMessageContext(MessageId(permalink.messageId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_permalink_context_network", retryable = true))
            }
        return when (snapshot) {
            is DataResult.Content -> {
                val target =
                    snapshot.data.messages.firstOrNull { it.id.value == permalink.messageId }
                        ?: snapshot.data.parentMessage?.takeIf { it.id.value == permalink.messageId }
                        ?: return DataResult.Empty(EmptyReason.NoMessages)
                DataResult.Content(target.toQuotedMessagePreview(permalink, snapshot.data.conversation))
            }
            is DataResult.Empty -> snapshot
            is DataResult.Error -> snapshot
            is DataResult.NoPermission -> snapshot
        }
    }

    private suspend fun fetchThreadPage(
        basePayload: KuiklyThreadScreenPayload,
        source: String,
        repositoryCall: suspend (ConversationRepository, ThreadId) -> DataResult<ThreadSnapshot>,
        call: suspend (threadId: String) -> ThreadApiResult<ThreadMessagesPage>
    ): DataResult<KuiklyThreadScreenPayload> {
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        val result =
            if (conversationRepository != null) {
                fetchThreadPayloadFromRepository(
                    session = session,
                    fallbackCode = "thread_${source}_network",
                    call = { repository -> repositoryCall(repository, ThreadId(threadId)) },
                    transform = { snapshot -> snapshot.toKuiklyThreadPayload(basePayload, session.user.id) }
                )
            } else {
                val response =
                    try {
                        call(threadId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_${source}_network", retryable = true))
                    }
                val page =
                    when (response) {
                        is ThreadApiResult.Success -> response.data
                        is ThreadApiResult.Failure ->
                            return response.toThreadDataError("thread_${source}_network")
                    }
                DataResult.Content(
                    buildSharedThreadPayloadFromMessagesPage(
                        basePayload = basePayload,
                        page = page,
                        session = session
                    ).withCachedSenderProfiles(session)
                )
            }
        return when (result) {
            is DataResult.Content -> DataResult.Content(result.data.withSavedStatus(session))
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun fetchThreadPayloadFromRepository(
        session: StoredAuthSession,
        fallbackCode: String,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>,
        transform: (ThreadSnapshot) -> KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val result = fetchThreadSnapshotFromRepository(fallbackCode, call)
        return result.map { snapshot -> transform(snapshot).withCachedSenderProfiles(session) }
    }

    private suspend fun fetchInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: KuiklyThreadScreenPayload,
        threadId: ThreadId
    ): DataResult<KuiklyThreadScreenPayload> {
        val initialResult =
            fetchThreadSnapshotFromRepository("thread_network") { repository ->
                repository.getThread(threadId, limit = SharedThreadPaging.initialPageSize)
            }
        val initialData = initialResult as? DataResult.Content
        // If the route's stream hint is untrusted (stream != parent channel) and the
        // thread-channel snapshot's reported parent pair differs from the route,
        // fall back to the resolve path which POSTs createOrOpenThread to
        // reconcile. This is the v0.2 defense-in-depth: the snapshot may not
        // carry parent metadata at all (treated as trust), but if it does and
        // disagrees with the canonical route parent pair, we don't show it.
        return when {
            initialData != null &&
                basePayload.hasUntrustedReplyThreadStreamHint() &&
                !initialData.data.parentPairMatchesRoute(basePayload) ->
                fetchResolvedReplyThreadSnapshot(basePayload).map { (resolvedBase, snapshot) ->
                    snapshot
                        .toKuiklyThreadPayload(resolvedBase, session.user.id)
                        .withCachedSenderProfiles(session)
                }
            initialData != null ->
                DataResult.Content(
                    initialData.data
                        .toKuiklyThreadPayload(basePayload, session.user.id)
                        .withCachedSenderProfiles(session)
                )
            else -> initialResult as DataResult<KuiklyThreadScreenPayload>
        }
    }

    private suspend fun fetchInitialReplyThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: KuiklyThreadScreenPayload,
        threadId: ThreadId
    ): DataResult<KuiklyThreadScreenPayload> {
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val initialResult =
            fetchThreadSnapshotFromRepository("thread_network_reply") { repository ->
                repository.getReplyThread(
                    parentChannelId = ChannelId(parentChannelId),
                    parentMessageId = MessageId(parentMessageId),
                    limit = SharedThreadPaging.initialPageSize
                )
            }
        val resolvedBase =
            basePayload.copy(
                sourceThreadId = threadId.value,
                streamChannelId = threadId.value
            )
        return when (initialResult) {
            is DataResult.Content ->
                DataResult.Content(
                    initialResult.data
                        .toKuiklyThreadPayload(resolvedBase, session.user.id)
                        .withCachedSenderProfiles(session)
                )
            is DataResult.Empty -> initialResult
            is DataResult.Error -> initialResult
            is DataResult.NoPermission -> initialResult
        }
    }

    private suspend fun fetchResolvedReplyThreadSnapshot(
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<Pair<KuiklyThreadScreenPayload, ThreadSnapshot>> {
        val resolvedBasePayload =
            when (val resolved = createOrOpenReplyThreadStream(basePayload)) {
                is DataResult.Content -> resolved.data
                is DataResult.Empty -> return resolved
                is DataResult.Error -> return resolved
                is DataResult.NoPermission -> return resolved
            }
        val resolvedThreadId =
            resolvedBasePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: resolvedBasePayload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val resolvedResult =
            fetchThreadSnapshotFromRepository("thread_network") { repository ->
                repository.getThread(ThreadId(resolvedThreadId), limit = SharedThreadPaging.initialPageSize)
            }
        return when (resolvedResult) {
            is DataResult.Content ->
                DataResult.Content(resolvedBasePayload to resolvedResult.data)
            is DataResult.Empty -> resolvedResult
            is DataResult.Error -> resolvedResult
            is DataResult.NoPermission -> resolvedResult
        }
    }

    private suspend fun fetchThreadSnapshotFromRepository(
        fallbackCode: String,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
        val result =
            try {
                call(repository)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
            }
        return result
    }

    private suspend fun KuiklyThreadScreenPayload.withSenderProfiles(
        session: StoredAuthSession
    ): KuiklyThreadScreenPayload {
        val repository = memberRepository ?: return this
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return this
        val homeAvatarByMemberId =
            localWriteThrough
                ?.readHomeSnapshot(serverId)
                ?.directMessageAvatarWireValuesByMemberId()
                .orEmpty()
        senderProfilesCache[serverId.value]?.let { profilesById ->
            return withSenderProfiles(profilesById).withHomeDirectMessageAvatars(homeAvatarByMemberId)
        }
        val result =
            try {
                repository.getMembers(DataRequestPriority.Visible, serverId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return this
            }
        if (result !is DataResult.Content) return this
        val profilesById = result.data.members.associateBy { it.id.value }
        senderProfilesCache[serverId.value] = profilesById
        return withSenderProfiles(profilesById).withHomeDirectMessageAvatars(homeAvatarByMemberId)
    }

    private suspend fun KuiklyThreadScreenPayload.withCachedSenderProfiles(
        session: StoredAuthSession
    ): KuiklyThreadScreenPayload {
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() } ?: return this
        val homeAvatarByMemberId =
            localWriteThrough
                ?.readHomeSnapshot(ServerId(serverId))
                ?.directMessageAvatarWireValuesByMemberId()
                .orEmpty()
        val cachedProfiles = senderProfilesCache[serverId].orEmpty()
        return withSenderProfiles(cachedProfiles).withHomeDirectMessageAvatars(homeAvatarByMemberId)
    }

    private suspend fun KuiklyThreadScreenPayload.withSavedStatus(
        session: StoredAuthSession
    ): KuiklyThreadScreenPayload {
        val repository = savedStatusRepository ?: return this
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return this
        val messageIds =
            (messages.map { it.id } + listOfNotNull(originMessage?.id))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map(::MessageId)
        if (messageIds.isEmpty()) return this
        val result =
            try {
                repository.checkSavedMessages(serverId, messageIds)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return this
            }
        return when (result) {
            is DataResult.Content -> withSavedMessageIds(result.data.savedIds.map { it.value }.toSet())
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> this
        }
    }
}

private fun ThreadApiResult.Failure.toThreadDataError(fallbackCode: String): DataResult.Error =
    DataResult.Error(
        ScreenError(
            code = contractCode ?: errorBodyText?.takeIf { it.isNotBlank() } ?: fallbackCode,
            retryable = contractCode == null
        )
    )

private fun ThreadApiResult<Unit>.toUnitDataResult(fallbackCode: String): DataResult<Unit> =
    when (this) {
        is ThreadApiResult.Success -> DataResult.Content(Unit)
        is ThreadApiResult.Failure -> toThreadDataError(fallbackCode)
    }

internal object SharedThreadPaging {
    const val initialPageSize = 30
    const val pageSize = initialPageSize
}
private const val SharedThreadFirstFrameDelayMillis = 16L
internal const val SharedThreadRealtimePersistenceBatchDelayMillis = 64L

internal data class SharedThreadState(
    val payload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    val highlightedMentions: Set<String> = emptySet(),
    val membersLoadStatus: LoadMembersStatus = LoadMembersStatus.Idle,
    /**
     * Key (typically source thread id) the [membersLoadStatus] is bound to.
     * Switching threads resets the status to Idle for the new key so we
     * never reuse a previous thread's "Loaded" verdict.
     */
    val membersLoadKey: String? = null
)

/**
 * Per-thread members load status. Keyed by [SharedThreadState.membersLoadKey]
 * so that switching to another thread resets the loaded state instead of
 * re-using the previous thread's "Loaded" status.
 */
internal enum class LoadMembersStatus { Idle, InFlight, Loaded, Error }

private data class ThreadReadAllMarker(
    val channelId: String,
    val latestMessageId: String,
    val latestSeq: Long?
)

internal sealed interface SharedThreadIntent {
    data class Start(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data object Refresh : SharedThreadIntent
    data class AppendSentPage(val page: ThreadMessagesPage) : SharedThreadIntent
    data class ApplyLocalSendStarted(val message: KuiklyThreadMessageItem) : SharedThreadIntent
    data class ApplyLocalSendFailure(val messageId: String, val errorCode: String? = null) : SharedThreadIntent
    data class ReplaceLocalSendWithSentPage(val messageId: String, val page: ThreadMessagesPage) : SharedThreadIntent
    data class ApplyMessageReactionSelection(
        val messageId: String,
        val emoji: String,
        val selected: Boolean
    ) : SharedThreadIntent
    data class ApplyMessageReactions(
        val messageId: String,
        val reactions: List<KuiklyThreadReactionItem>
    ) : SharedThreadIntent
    data class ApplyMessageMutation(val summary: MessageMutationSummary) : SharedThreadIntent
    data class ApplyActionCard(val messageId: String, val card: ThreadActionCard) : SharedThreadIntent
    data class ApplyMessageSaved(val messageId: String, val saved: Boolean) : SharedThreadIntent
    data class ApplyTaskStatus(val taskId: String, val statusLabel: String?) : SharedThreadIntent
    data class ApplyMembersPatch(
        val membersCount: Int,
        val members: List<KuiklyThreadMemberItem>,
        val memberCandidates: List<KuiklyThreadMemberItem>,
        val canAddMembers: Boolean,
        val addMemberStatus: String = "idle",
        val addMemberErrorCode: String? = null,
        val removeMemberStatus: String = "idle",
        val removingMemberId: String? = null,
        val removeMemberErrorCode: String? = null
    ) : SharedThreadIntent
    data class SetAddMemberStatus(val status: String, val errorCode: String? = null) : SharedThreadIntent
    data class SetRemoveMemberStatus(
        val status: String,
        val removingMemberId: String?,
        val errorCode: String? = null
    ) : SharedThreadIntent
    data class SetPagingAvailability(
        val canLoadMoreBefore: Boolean,
        val canLoadMoreAfter: Boolean
    ) : SharedThreadIntent
    data class ApplyRealtimeEvent(val event: RealtimeEvent, val activeReplyParentMessageId: MessageId? = null) :
        SharedThreadIntent
    data object MarkVisibleReadAll : SharedThreadIntent
    data object LoadMembers : SharedThreadIntent
    data object LoadOlder : SharedThreadIntent
    data object LoadNewer : SharedThreadIntent
}

internal class SharedThreadStore(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)? = null
) {
    private val _state = MutableStateFlow(SharedThreadState())
    val state: StateFlow<SharedThreadState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var realtimePersistenceJob: Job? = null
    private val pendingRealtimePersistenceEvents = mutableListOf<RealtimeEvent>()
    private var readAllInFlightMarker: ThreadReadAllMarker? = null
    private var pendingReadAllMarker: ThreadReadAllMarker? = null
    private var lastCompletedReadAllMarker: ThreadReadAllMarker? = null

    fun dispatch(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start,
            SharedThreadIntent.Refresh -> {
                loadJob?.cancel()
                loadJob =
                    scope.launch {
                        accept(intent)
                    }
            }
            is SharedThreadIntent.AppendSentPage,
            is SharedThreadIntent.ApplyLocalSendStarted,
            is SharedThreadIntent.ApplyLocalSendFailure,
            is SharedThreadIntent.ReplaceLocalSendWithSentPage,
            is SharedThreadIntent.ApplyMessageReactionSelection,
            is SharedThreadIntent.ApplyMessageReactions,
            is SharedThreadIntent.ApplyMessageMutation,
            is SharedThreadIntent.ApplyActionCard,
            is SharedThreadIntent.ApplyMessageSaved,
            is SharedThreadIntent.ApplyTaskStatus,
            is SharedThreadIntent.ApplyMembersPatch,
            is SharedThreadIntent.SetAddMemberStatus,
            is SharedThreadIntent.SetRemoveMemberStatus,
            is SharedThreadIntent.SetPagingAvailability ->
                scope.launch { accept(intent) }
            is SharedThreadIntent.ApplyRealtimeEvent -> {
                scope.launch {
                    applyRealtimeEvent(
                        event = intent.event,
                        activeReplyParentMessageId = intent.activeReplyParentMessageId
                    )
                }
            }
            SharedThreadIntent.MarkVisibleReadAll -> {
                scope.launch { markVisibleReadAll() }
            }
            SharedThreadIntent.LoadMembers -> {
                scope.launch { loadMembers() }
            }
            SharedThreadIntent.LoadOlder -> {
                scope.launch { loadOlder() }
            }
            SharedThreadIntent.LoadNewer -> {
                scope.launch { loadNewer() }
            }
        }
    }

    suspend fun accept(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start -> start(intent.payload)
            SharedThreadIntent.Refresh -> refresh(_state.value.payload)
            is SharedThreadIntent.AppendSentPage -> appendSentPage(intent.page)
            is SharedThreadIntent.ApplyLocalSendStarted -> applyLocalSendStarted(intent.message)
            is SharedThreadIntent.ApplyLocalSendFailure -> applyLocalSendFailure(intent.messageId, intent.errorCode)
            is SharedThreadIntent.ReplaceLocalSendWithSentPage -> replaceLocalSendWithSentPage(intent.messageId, intent.page)
            is SharedThreadIntent.ApplyMessageReactionSelection ->
                applyMessageReactionSelection(intent.messageId, intent.emoji, intent.selected)
            is SharedThreadIntent.ApplyMessageReactions ->
                applyMessageReactions(intent.messageId, intent.reactions)
            is SharedThreadIntent.ApplyMessageMutation -> applyMessageMutation(intent.summary)
            is SharedThreadIntent.ApplyActionCard -> applyActionCard(intent.messageId, intent.card)
            is SharedThreadIntent.ApplyMessageSaved -> applyMessageSaved(intent.messageId, intent.saved)
            is SharedThreadIntent.ApplyTaskStatus -> applyTaskStatus(intent.taskId, intent.statusLabel)
            is SharedThreadIntent.ApplyMembersPatch -> applyMembersPatch(intent)
            is SharedThreadIntent.SetAddMemberStatus -> setAddMemberStatus(intent.status, intent.errorCode)
            is SharedThreadIntent.SetRemoveMemberStatus ->
                setRemoveMemberStatus(intent.status, intent.removingMemberId, intent.errorCode)
            is SharedThreadIntent.SetPagingAvailability ->
                setPagingAvailability(intent.canLoadMoreBefore, intent.canLoadMoreAfter)
            is SharedThreadIntent.ApplyRealtimeEvent ->
                applyRealtimeEvent(
                    event = intent.event,
                    activeReplyParentMessageId = intent.activeReplyParentMessageId
                )
            SharedThreadIntent.MarkVisibleReadAll -> markVisibleReadAll()
            SharedThreadIntent.LoadMembers -> loadMembers()
            SharedThreadIntent.LoadOlder -> loadOlder()
            SharedThreadIntent.LoadNewer -> loadNewer()
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
        realtimePersistenceJob?.cancel()
        realtimePersistenceJob = null
        pendingRealtimePersistenceEvents.clear()
        readAllInFlightMarker = null
        pendingReadAllMarker = null
        lastCompletedReadAllMarker = null
    }

    private suspend fun start(initialPayload: KuiklyThreadScreenPayload) {
        dataSource.cachePayload(initialPayload)
        val threadId = initialPayload.sourceThreadId?.takeIf { it.isNotBlank() }
        val cached =
            threadId
                ?.let(dataSource::cachedPayload)
                ?.takeIf { it.isUsableCachedPayloadForRoute(initialPayload) }
                ?.withRouteContext(initialPayload)
        val current =
            _state.value.payload.takeIf {
                it.isUsableCachedPayloadForRoute(initialPayload) && it.hasRenderableThreadContent()
            }
        diagnosticsLogger?.invoke(
            "thread_start threadId=${threadId.orEmpty()} cachedMemory=${cached != null || current != null} " +
                "status=${initialPayload.status} messages=${initialPayload.messages.size} " +
                initialPayload.originDiagnostics()
        )
        val visiblePayload =
            cached
                ?: current
                ?: if (threadId == null) {
                    initialPayload
                } else {
                    initialPayload.copy(status = "loading", errorCode = null)
                }
        diagnosticsLogger?.invoke(
            "thread_visible_payload threadId=${threadId.orEmpty()} status=${visiblePayload.status} " +
                "messages=${visiblePayload.messages.size} " + visiblePayload.originDiagnostics()
        )
        reduceState(
            SharedThreadState(
                payload = visiblePayload,
                highlightedMentions = emptySet()
            )
        )
        delay(SharedThreadFirstFrameDelayMillis)
        val session = dataSource.readSession()
        if (threadId == null || _state.value.payload.sourceThreadId == threadId) {
            reduceState(_state.value.copy(highlightedMentions = session.threadSelfMentionNames()))
        }
        if (threadId != null && cached == null && current == null && !visiblePayload.hasRenderableThreadContent()) {
            val persistentCacheStartedAt = TimeSource.Monotonic.markNow()
            val persistentCached = dataSource.readCachedPayload(initialPayload)
            diagnosticsLogger?.invoke(
                "thread_persistent_cache_result threadId=$threadId hit=${persistentCached != null} " +
                    "messages=${persistentCached?.messages?.size ?: 0} " +
                    "durationMs=${persistentCacheStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            if (persistentCached != null && _state.value.payload.isUsableCachedPayloadForRoute(initialPayload)) {
                reducePayloadPatch { currentPayload ->
                    val patch = persistentCached.withRouteContext(initialPayload)
                    diagnosticsLogger?.invoke(
                        "thread_persistent_cache_patch threadId=$threadId status=${patch.status} " +
                            "messages=${patch.messages.size} " + patch.originDiagnostics()
                    )
                    currentPayload.withContentPatchFrom(patch)
                }
            }
        } else {
            diagnosticsLogger?.invoke(
                "thread_persistent_cache_result threadId=${threadId.orEmpty()} hit=false messages=0 durationMs=0 reason=skipped_memory"
            )
        }
        refresh(initialPayload)
    }

    private suspend fun refresh(basePayload: KuiklyThreadScreenPayload) {
        val refreshStartedAt = TimeSource.Monotonic.markNow()
        val existing = _state.value.payload
        val hasRenderableContent = existing.hasRenderableThreadContent()
        diagnosticsLogger?.invoke(
            "thread_refresh_start threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "existingStatus=${existing.status} existingMessages=${existing.messages.size} " +
                "hasRenderableContent=$hasRenderableContent"
        )
        val loadingBase =
            if (hasRenderableContent) {
                existing.withRouteContext(basePayload)
            } else {
                basePayload.copy(status = "loading", errorCode = null)
            }
        diagnosticsLogger?.invoke(
            "thread_loading_patch threadId=${basePayload.sourceThreadId.orEmpty()} status=${loadingBase.status} " +
                "messages=${loadingBase.messages.size} " + loadingBase.originDiagnostics()
        )
        reducePayloadPatch { currentPayload -> currentPayload.withContentPatchFrom(loadingBase) }
        if (!hasRenderableContent) {
            delay(SharedThreadFirstFrameDelayMillis)
        }
        val result = dataSource.fetchThreadPayload(basePayload, persistResult = false)
        val visiblePayload =
            (result as? DataResult.Content)
                ?.data
                ?.withRouteContext(basePayload)
                ?.preserveStableTimelineFrom(_state.value.payload)
        val nextPayload =
            when (result) {
                is DataResult.Content ->
                    visiblePayload ?: result.data.withRouteContext(basePayload)
                is DataResult.Empty ->
                    _state.value.payload.asRefreshFailed("empty_thread")
                is DataResult.Error ->
                    _state.value.payload.asRefreshFailed(result.error.code)
                is DataResult.NoPermission ->
                    _state.value.payload.asRefreshFailed(result.blocker.code)
            }
        val emitted =
            reducePayloadPatch(cachePayload = visiblePayload != null) { currentPayload ->
                currentPayload.withContentPatchFrom(nextPayload)
            }
        diagnosticsLogger?.invoke(
            "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "result=${result.threadDiagnosticsResult()} messages=${nextPayload.messages.size} " +
                "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds} " +
                nextPayload.originDiagnostics()
        )
        if (emitted && visiblePayload != null) {
            writeCachedPayloadAfterFrame(_state.value.payload)
            // Explicit Refresh must always re-fetch members, even if
            // the thread is already in Loaded state.
            loadMembers(force = true)
        }
    }

    /**
     * Load the current thread's members, with idempotency guards.
     *
     * Behaviour:
     * - No-op when the current source thread is missing.
     * - No-op when the bound status is [LoadMembersStatus.InFlight] for
     *   the current thread key (concurrent taps coalesce to one request).
     * - No-op when the bound status is [LoadMembersStatus.Loaded] for the
     *   current thread key, unless [force] is true.
     * - Resets to [LoadMembersStatus.Idle] when the bound key changes
     *   (switching threads) so a previous thread's "Loaded" verdict
     *   never leaks into the next thread.
     */
    private suspend fun loadMembers(force: Boolean = false) {
        val membersStartedAt = TimeSource.Monotonic.markNow()
        val existing = _state.value.payload
        val threadKey = existing.sourceThreadId.orEmpty()
        if (threadKey.isBlank()) return
        val currentState = _state.value
        if (currentState.membersLoadKey != threadKey) {
            // Switched threads: reset status so the previous verdict does not leak.
            _state.value = currentState.copy(
                membersLoadKey = threadKey,
                membersLoadStatus = LoadMembersStatus.Idle
            )
        }
        val resolvedStatus = _state.value.membersLoadStatus
        if (!force && resolvedStatus == LoadMembersStatus.InFlight) {
            diagnosticsLogger?.invoke(
                "thread_members_load_skip threadId=$threadKey reason=in_flight"
            )
            return
        }
        if (!force && resolvedStatus == LoadMembersStatus.Loaded) {
            diagnosticsLogger?.invoke(
                "thread_members_load_skip threadId=$threadKey reason=already_loaded"
            )
            return
        }
        diagnosticsLogger?.invoke(
            "thread_members_load_start threadId=$threadKey " +
                "existingMembers=${existing.members.size} existingCandidates=${existing.memberCandidates.size}"
        )
        _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.InFlight)
        when (val outcome = dataSource.enrichThreadMembers(existing)) {
            is SharedThreadDataSource.MembersEnrichOutcome.Updated -> {
                reducePayloadPatch(cachePayload = true) { currentPayload ->
                    currentPayload.withMembersPatchFrom(outcome.payload)
                }
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=changed members=${outcome.payload.members.size} " +
                        "candidates=${outcome.payload.memberCandidates.size} canAddMembers=${outcome.payload.canAddMembers} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is SharedThreadDataSource.MembersEnrichOutcome.Unchanged -> {
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=unchanged members=${existing.members.size} candidates=${existing.memberCandidates.size} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is SharedThreadDataSource.MembersEnrichOutcome.Failed -> {
                // Transient failure: keep status as Error so the next
                // modal open (or Refresh) can retry. Do not reduce payload.
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Error)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=failed reason=${outcome.reason} " +
                        "members=${existing.members.size} candidates=${existing.memberCandidates.size} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
        }
    }

    private fun writeCachedPayloadAfterFrame(payload: KuiklyThreadScreenPayload) {
        scope.launch {
            delay(SharedThreadFirstFrameDelayMillis)
            dataSource.writeCachedPayload(payload)
        }
    }

    private suspend fun applyRealtimeEvent(
        event: RealtimeEvent,
        activeReplyParentMessageId: MessageId? = null
    ) {
        val existing = _state.value.payload
        val rawMerged =
            existing.applyRealtimeEvent(
                event = event,
                currentUserId = dataSource.readSession()?.user?.id,
                activeReplyParentMessageId = activeReplyParentMessageId
            )
        val merged =
            if (rawMerged === existing) {
                rawMerged
            } else {
                dataSource.enrichCachedSenderProfiles(rawMerged)
            }
        val stableMerged = merged.preserveStableTimelineFrom(existing)
        val emitted =
            reducePayloadPatch(cachePayload = true) { currentPayload ->
                currentPayload.withContentPatchFrom(stableMerged)
            }
        if (emitted) {
            writeCachedPayloadAfterFrame(_state.value.payload)
        }
        enqueueRealtimePersistence(event)
    }

    private fun enqueueRealtimePersistence(event: RealtimeEvent) {
        pendingRealtimePersistenceEvents += event
        if (realtimePersistenceJob?.isActive == true) return
        startRealtimePersistenceJob()
    }

    private fun startRealtimePersistenceJob() {
        realtimePersistenceJob =
            scope.launch {
                delay(SharedThreadRealtimePersistenceBatchDelayMillis)
                val events = pendingRealtimePersistenceEvents.toList()
                pendingRealtimePersistenceEvents.clear()
                try {
                    runCatching {
                        dataSource.writeRealtimeEvents(events)
                    }.onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        diagnosticsLogger?.invoke(
                            "thread_realtime_persist_failed events=${events.size} type=${throwable::class.simpleName} message=${throwable.message.orEmpty()}"
                        )
                    }
                } finally {
                    realtimePersistenceJob = null
                    if (pendingRealtimePersistenceEvents.isNotEmpty()) {
                        startRealtimePersistenceJob()
                    }
                }
            }
    }

    private suspend fun markVisibleReadAll() {
        val marker = _state.value.payload.readAllMarker() ?: return
        if (marker == lastCompletedReadAllMarker) {
            diagnosticsLogger?.invoke(
                "thread_read_all_skip channelId=${marker.channelId} reason=already_marked latest=${marker.latestMessageId}"
            )
            return
        }
        if (readAllInFlightMarker != null) {
            pendingReadAllMarker = marker
            diagnosticsLogger?.invoke(
                "thread_read_all_skip channelId=${marker.channelId} reason=in_flight latest=${marker.latestMessageId}"
            )
            return
        }

        var nextMarker: ThreadReadAllMarker? = marker
        while (nextMarker != null) {
            val current = nextMarker
            pendingReadAllMarker = null
            readAllInFlightMarker = current
            val startedAt = TimeSource.Monotonic.markNow()
            diagnosticsLogger?.invoke(
                "thread_read_all_start channelId=${current.channelId} latest=${current.latestMessageId}"
            )
            try {
                val result = dataSource.markChannelReadAll(current.channelId)
                if (result is DataResult.Content) {
                    lastCompletedReadAllMarker = current
                }
                diagnosticsLogger?.invoke(
                    "thread_read_all_result channelId=${current.channelId} result=${result.threadDiagnosticsResult()} " +
                        "latest=${current.latestMessageId} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
            } finally {
                readAllInFlightMarker = null
            }
            val pending = pendingReadAllMarker
            nextMarker =
                if (pending != null && pending != current && pending != lastCompletedReadAllMarker) {
                    pending
                } else {
                    pendingReadAllMarker = null
                    null
                }
        }
    }

    private suspend fun loadOlder() {
        val existing = _state.value.payload
        if (!existing.canLoadMoreBefore || existing.olderMessagesStatus == "loading") return
        val beforeSeq = existing.messages.mapNotNull { it.seq }.minOrNull()
        if (beforeSeq == null) {
            reducePayloadPatch { currentPayload ->
                currentPayload.copy(olderMessagesStatus = "error", errorCode = "missing_before_seq")
            }
            return
        }
        reducePayloadPatch { currentPayload -> currentPayload.copy(olderMessagesStatus = "loading", errorCode = null) }
        val result = dataSource.fetchOlderThreadPayload(existing, beforeSeq)
        val nextPayload =
            when (result) {
                is DataResult.Content -> {
                    val current = _state.value.payload
                    current
                        .mergePagedMessages(result.data.messages, before = true)
                        .preserveStableTimelineFrom(current)
                }
                is DataResult.Empty ->
                    _state.value.payload.copy(canLoadMoreBefore = false, olderMessagesStatus = "idle")
                is DataResult.Error ->
                    _state.value.payload.copy(olderMessagesStatus = "error", errorCode = result.error.code)
                is DataResult.NoPermission ->
                    _state.value.payload.copy(olderMessagesStatus = "error", errorCode = result.blocker.code)
            }
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { currentPayload -> currentPayload.withContentPatchFrom(nextPayload) }
    }

    private suspend fun loadNewer() {
        val existing = _state.value.payload
        if (!existing.canLoadMoreAfter || existing.newerMessagesStatus == "loading") return
        val afterSeq = existing.messages.mapNotNull { it.seq }.maxOrNull()
        if (afterSeq == null) {
            reducePayloadPatch { currentPayload ->
                currentPayload.copy(newerMessagesStatus = "error", errorCode = "missing_after_seq")
            }
            return
        }
        reducePayloadPatch { currentPayload -> currentPayload.copy(newerMessagesStatus = "loading", errorCode = null) }
        val result = dataSource.fetchNewerThreadPayload(existing, afterSeq)
        val nextPayload =
            when (result) {
                is DataResult.Content -> {
                    val current = _state.value.payload
                    current
                        .mergePagedMessages(result.data.messages, before = false)
                        .preserveStableTimelineFrom(current)
                }
                is DataResult.Empty ->
                    _state.value.payload.copy(canLoadMoreAfter = false, newerMessagesStatus = "idle")
                is DataResult.Error ->
                    _state.value.payload.copy(newerMessagesStatus = "error", errorCode = result.error.code)
                is DataResult.NoPermission ->
                    _state.value.payload.copy(newerMessagesStatus = "error", errorCode = result.blocker.code)
            }
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { currentPayload -> currentPayload.withContentPatchFrom(nextPayload) }
    }

    private suspend fun appendSentPage(page: ThreadMessagesPage) {
        if (page.messages.isEmpty()) return
        val currentPayload = _state.value.payload
        val appended =
            appendSharedThreadSentMessageFromPage(
                basePayload = currentPayload,
                page = page,
                session = dataSource.readSession()
            ).preserveStableTimelineFrom(currentPayload)
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { payload -> payload.withContentPatchFrom(appended) }
    }

    private fun applyLocalSendStarted(message: KuiklyThreadMessageItem) {
        reducePayloadPatch { payload ->
            payload.withLocalSendStarted(message)
        }
    }

    private fun applyLocalSendFailure(messageId: String, errorCode: String?) {
        reducePayloadPatch { payload ->
            payload.withLocalSendFailure(messageId, errorCode)
        }
    }

    private suspend fun replaceLocalSendWithSentPage(messageId: String, page: ThreadMessagesPage) {
        if (page.messages.isEmpty()) return
        val currentPayload = _state.value.payload
        val appended =
            appendSharedThreadSentMessageFromPage(
                basePayload = currentPayload,
                page = page,
                session = dataSource.readSession()
            )
        val replaced =
            appended
                .withoutLocalSendMessage(messageId)
                .preserveStableTimelineFrom(currentPayload)
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { payload -> payload.withContentPatchFrom(replaced) }
    }

    private fun applyMessageReactionSelection(messageId: String, emoji: String, selected: Boolean) {
        reducePayloadPatch(cachePayload = true) { payload ->
            payload.withMessageReaction(messageId, emoji, selected)
        }
    }

    private fun applyMessageReactions(messageId: String, reactions: List<KuiklyThreadReactionItem>) {
        reducePayloadPatch(cachePayload = true) { payload ->
            payload.withMessageReactions(messageId, reactions)
        }
    }

    private suspend fun applyMessageMutation(summary: MessageMutationSummary) {
        val currentUserId = dataSource.readSession()?.user?.id
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { payload -> payload.withMessageMutationSummary(summary, currentUserId) }
    }

    private suspend fun applyActionCard(messageId: String, card: ThreadActionCard) {
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { payload -> payload.withActionCard(messageId, card) }
    }

    private suspend fun applyMessageSaved(messageId: String, saved: Boolean) {
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { payload -> payload.withMessageSaved(messageId, saved) }
    }

    private suspend fun applyTaskStatus(taskId: String, statusLabel: String?) {
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { payload -> payload.withTaskStatusLabel(taskId, statusLabel) }
    }

    private suspend fun applyMembersPatch(patch: SharedThreadIntent.ApplyMembersPatch) {
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { payload ->
            payload.copy(
                membersCount = patch.membersCount,
                members = patch.members,
                memberCandidates = patch.memberCandidates,
                canAddMembers = patch.canAddMembers,
                addMemberStatus = patch.addMemberStatus,
                addMemberErrorCode = patch.addMemberErrorCode,
                removeMemberStatus = patch.removeMemberStatus,
                removingMemberId = patch.removingMemberId,
                removeMemberErrorCode = patch.removeMemberErrorCode
            )
        }
    }

    private fun setAddMemberStatus(status: String, errorCode: String?) {
        reducePayloadPatch {
            it.copy(addMemberStatus = status, addMemberErrorCode = errorCode)
        }
    }

    private fun setRemoveMemberStatus(status: String, removingMemberId: String?, errorCode: String?) {
        reducePayloadPatch {
            it.copy(
                removeMemberStatus = status,
                removingMemberId = removingMemberId,
                removeMemberErrorCode = errorCode
            )
        }
    }

    private fun setPagingAvailability(canLoadMoreBefore: Boolean, canLoadMoreAfter: Boolean) {
        reducePayloadPatch {
            it.copy(
                canLoadMoreBefore = canLoadMoreBefore,
                canLoadMoreAfter = canLoadMoreAfter
            )
        }
    }

    private fun reduceState(nextState: SharedThreadState): Boolean {
        if (nextState == _state.value) return false
        _state.value = nextState
        return true
    }

    private suspend fun reducePayloadPatchAndWriteCache(
        cachePayload: Boolean = false,
        writeCachedPayload: Boolean = false,
        patch: (KuiklyThreadScreenPayload) -> KuiklyThreadScreenPayload
    ): Boolean {
        val nextPayload = patch(_state.value.payload)
        val emitted = reduceState(_state.value.copy(payload = nextPayload))
        if (emitted && cachePayload) {
            dataSource.cachePayload(nextPayload)
        }
        if (emitted && writeCachedPayload) {
            dataSource.writeCachedPayload(nextPayload)
        }
        return emitted
    }

    private fun reducePayloadPatch(
        cachePayload: Boolean = false,
        patch: (KuiklyThreadScreenPayload) -> KuiklyThreadScreenPayload
    ): Boolean {
        val nextPayload = patch(_state.value.payload)
        val emitted = reduceState(_state.value.copy(payload = nextPayload))
        if (emitted && cachePayload) {
            dataSource.cachePayload(nextPayload)
        }
        return emitted
    }
}

internal class SharedThreadViewModel(
    private val dataSource: SharedThreadDataSource,
    private val diagnosticsLogger: ((String) -> Unit)? = null
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadStore(dataSource = dataSource, scope = viewModelScope, diagnosticsLogger = diagnosticsLogger)
        }
    private val store: SharedThreadStore by storeDelegate
    val state: StateFlow<SharedThreadState>
        get() = store.state

    fun dispatch(intent: SharedThreadIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

private fun KuiklyThreadScreenPayload.asRefreshFailed(code: String): KuiklyThreadScreenPayload =
    if (hasRenderableThreadContent()) {
        copy(status = "content", errorCode = code)
    } else {
        copy(status = "error", errorCode = code)
    }

private fun DataResult<*>.threadDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }

private fun KuiklyThreadScreenPayload.originDiagnostics(): String =
    "surface=$surfaceKind parentChannelId=${parentChannelId.orEmpty()} " +
        "parentMessageId=${parentMessageId.orEmpty()} originParentMessageId=${originParentMessageId.orEmpty()} " +
        "originStatus=$originStatus hasOriginMessage=${originMessage != null} " +
        "hasOriginPreview=${!originPreview.isNullOrBlank()}"

private fun KuiklyThreadScreenPayload.withRouteContext(base: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    if (hasResolvedReplyThreadStreamFrom(base)) {
        copy(
            navigationLabel = base.navigationLabel,
            selectedTab = base.selectedTab,
            showModeTabs = base.showModeTabs,
            alignOwnMessagesRight = base.alignOwnMessagesRight,
            localOffsetMinutes = base.localOffsetMinutes,
            todayEpochDay = base.todayEpochDay ?: todayEpochDay,
            nowEpochMillis = base.nowEpochMillis ?: nowEpochMillis,
            timeFormat = base.timeFormat,
            canSend = base.canSend,
            joined = base.joined,
            membersCount = if (base.membersCount > 0) base.membersCount else membersCount,
            emptyTitle = base.emptyTitle,
            emptyDetail = base.emptyDetail
        )
    } else {
        copy(
            instanceId = base.instanceId.ifBlank { instanceId },
            sourceThreadId = base.sourceThreadId ?: sourceThreadId,
            navigationLabel = base.navigationLabel,
            selectedTab = base.selectedTab,
            showModeTabs = base.showModeTabs,
            alignOwnMessagesRight = base.alignOwnMessagesRight,
            localOffsetMinutes = base.localOffsetMinutes,
            todayEpochDay = base.todayEpochDay ?: todayEpochDay,
            nowEpochMillis = base.nowEpochMillis ?: nowEpochMillis,
            timeFormat = base.timeFormat,
            canSend = base.canSend,
            joined = base.joined,
            membersCount = if (base.membersCount > 0) base.membersCount else membersCount,
            composer = base.composer.takeUnless { it.isDefaultThreadComposer() } ?: composer,
            emptyTitle = base.emptyTitle,
            emptyDetail = base.emptyDetail
        )
    }

private fun KuiklyThreadScreenPayload.needsReplyThreadStreamResolution(): Boolean {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    val parentMessage = parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: return false
    val streamCandidate =
        streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        parentMessage.isNotBlank() &&
        (streamCandidate == null || streamCandidate == parentChannel)
}

private fun KuiklyThreadScreenPayload.hasUntrustedReplyThreadStreamHint(): Boolean {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: return false
    val streamCandidate =
        streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return false
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        streamCandidate != parentChannel
}

private fun ThreadSnapshot.parentPairMatchesRoute(base: KuiklyThreadScreenPayload): Boolean {
    val expectedParentChannelId = base.parentChannelId?.takeIf { it.isNotBlank() } ?: return true
    val expectedParentMessageId =
        base.parentMessageId?.takeIf { it.isNotBlank() }
            ?: base.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return true
    val thread = conversation.thread
    if (thread != null) {
        return thread.parentChannelId.value == expectedParentChannelId &&
            thread.parentMessageId.value == expectedParentMessageId
    }
    val parent = parentMessage ?: return true
    return parent.channelId?.value == expectedParentChannelId &&
        parent.id.value == expectedParentMessageId
}

private fun KuiklyThreadScreenPayload.hasResolvedReplyThreadStreamFrom(base: KuiklyThreadScreenPayload): Boolean {
    val source = sourceThreadId?.takeIf { it.isNotBlank() } ?: return false
    val baseSource =
        base.streamChannelId?.takeIf { it.isNotBlank() }
            ?: base.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: base.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return false
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        base.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        source != baseSource
}

private fun KuiklyThreadScreenPayload.withResolvedReplyThreadAction(
    action: ThreadActionResult
): KuiklyThreadScreenPayload {
    val threadChannelId = action.threadChannelId.value.takeIf { it.isNotBlank() } ?: return this
    val resolvedParentMessageId =
        action.parentMessageId?.value?.takeIf { it.isNotBlank() }
            ?: parentMessageId
            ?: originParentMessageId
    return copy(
        instanceId = "shared-shell-thread-$threadChannelId",
        sourceThreadId = threadChannelId,
        streamChannelId = threadChannelId,
        parentMessageId = resolvedParentMessageId,
        originParentMessageId = resolvedParentMessageId ?: originParentMessageId,
        originMessage =
            originMessage?.copy(
                threadChannelId = threadChannelId,
                replyCount = maxOf(originMessage.replyCount, action.replyCount)
            ),
        composer =
            composer.copy(
                parentMessageId = resolvedParentMessageId ?: composer.parentMessageId,
                streamChannelId = threadChannelId
            )
    )
}

internal fun KuiklyThreadScreenPayload.preserveStableTimelineFrom(
    previous: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload {
    val stableMessages = messages.preserveStableItemsFrom(previous.messages) { item -> item.id }
    val stableTasks = tasks.preserveStableItemsFrom(previous.tasks) { item -> item.id }
    val preservePreviousOrigin = shouldPreserveLoadedOriginFrom(previous)
    val stableOrigin =
        when {
            preservePreviousOrigin -> previous.originMessage
            originMessage != null && originMessage == previous.originMessage -> previous.originMessage
            else -> originMessage
        }
    val stableOriginParentMessageId =
        if (preservePreviousOrigin) {
            previous.originParentMessageId ?: previous.originMessage?.id ?: originParentMessageId
        } else {
            originParentMessageId
        }
    val stableOriginPreview =
        if (preservePreviousOrigin) previous.originPreview else originPreview
    val stableOriginStatus =
        if (preservePreviousOrigin) previous.originStatus.takeIf { it == "loaded" } ?: "loaded" else originStatus
    val stableOriginErrorCode =
        if (preservePreviousOrigin) previous.originErrorCode else originErrorCode
    if (
        stableMessages === messages &&
        stableTasks === tasks &&
        stableOrigin === originMessage &&
        stableOriginParentMessageId == originParentMessageId &&
        stableOriginPreview == originPreview &&
        stableOriginStatus == originStatus &&
        stableOriginErrorCode == originErrorCode
    ) {
        return this
    }
    return copy(
        messages = stableMessages,
        originMessage = stableOrigin,
        originPreview = stableOriginPreview,
        originParentMessageId = stableOriginParentMessageId,
        originStatus = stableOriginStatus,
        originErrorCode = stableOriginErrorCode,
        tasks = stableTasks
    )
}

private fun KuiklyThreadScreenPayload.shouldPreserveLoadedOriginFrom(
    previous: KuiklyThreadScreenPayload
): Boolean {
    val previousOrigin = previous.originMessage ?: return false
    if (originMessage != null) return false
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread || previous.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) {
        return false
    }
    if (originStatus != "missing" && originStatus != "loading") return false
    val incomingOriginId =
        originParentMessageId?.takeIf { it.isNotBlank() }
            ?: parentMessageId?.takeIf { it.isNotBlank() }
            ?: return false
    val previousOriginId =
        previous.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: previous.parentMessageId?.takeIf { it.isNotBlank() }
            ?: previousOrigin.id.takeIf { it.isNotBlank() }
    return previousOriginId == incomingOriginId && previousOrigin.id == incomingOriginId
}

private inline fun <T, K> List<T>.preserveStableItemsFrom(
    previous: List<T>,
    keySelector: (T) -> K
): List<T> {
    if (isEmpty() || previous.isEmpty()) return this
    val previousByKey = previous.associateBy(keySelector)
    var reusedAnyReference = false
    val stableItems =
        map { incoming ->
            val previousItem = previousByKey[keySelector(incoming)]
            if (previousItem != null && previousItem == incoming) {
                reusedAnyReference = reusedAnyReference || previousItem !== incoming
                previousItem
            } else {
                incoming
            }
        }
    val fullyMatchesPrevious =
        stableItems.size == previous.size &&
            stableItems.indices.all { index -> stableItems[index] === previous[index] }
    return when {
        fullyMatchesPrevious -> previous
        reusedAnyReference -> stableItems
        else -> this
    }
}

private val MemberProfile.memberKey: String
    get() = "${kind.name}:${id.value}"

private fun MemberProfile.withProfileAvatarFallback(profile: MemberProfile?): MemberProfile {
    profile ?: return this
    return copy(
        avatarUrl = avatarUrl ?: profile.avatarUrl,
        gravatarHash = gravatarHash ?: profile.gravatarHash,
        avatar = avatar ?: profile.avatar,
        avatarLabel = avatarLabel.ifBlank { profile.avatarLabel }
    )
}

private fun MemberProfile.withHomeAvatarFallback(avatarWireValue: String?): MemberProfile {
    val wireValue = avatarWireValue?.takeIf { it.isNotBlank() } ?: return this
    val currentWireValue = avatar?.wireValue ?: avatarUrl
    if (!currentWireValue.isNullOrBlank()) return this
    return copy(
        avatarUrl = wireValue,
        avatar = AvatarReference.fromWireValue(wireValue),
        gravatarHash = null
    )
}

private fun MemberProfile.toKuiklyThreadMemberItem(): KuiklyThreadMemberItem =
    KuiklyThreadMemberItem(
        id = id.value,
        displayName = displayName,
        handle = handle,
        roleLabel =
            roleDescription
                ?: when (kind) {
                    MemberKind.Agent -> "Agent"
                    MemberKind.Human -> "Member"
                    MemberKind.System -> "System"
                },
        avatarUrl = avatarWireValueForAvatarSlot(),
        avatarLabel = avatarLabel,
        kind = kind.name.lowercase()
    )

private fun String?.toThreadMemberKind(): MemberKind? =
    when (this?.trim()?.lowercase()) {
        "agent" -> MemberKind.Agent
        "human", "member", "user" -> MemberKind.Human
        "system" -> MemberKind.System
        else -> null
    }

private fun ChannelFilesSnapshot.toKuiklyThreadFilesPayload(
    basePayload: KuiklyThreadScreenPayload,
    currentPayload: KuiklyThreadFilesPayload?,
    append: Boolean
): KuiklyThreadFilesPayload {
    val incoming = items.map { item ->
        val attachment = item.attachment
        KuiklyThreadFileItem(
            attachmentId = attachment.id.value,
            name = attachment.name,
            sizeLabel = attachment.sizeLabel,
            createdAtLabel = basePayload.formatFilesTimestampLabel(item.createdAt.orEmpty()),
            sourceLabel = item.filesSourceLabel(),
            mimeType = attachment.mimeType,
            thumbnailUrl = attachment.thumbnailUrl
        )
    }
    val merged =
        if (append) {
            currentPayload.orEmptyFilesPayload(basePayload).items + incoming
        } else {
            incoming
        }.distinctBy { item -> item.attachmentId }
    return KuiklyThreadFilesPayload(
        instanceId = basePayload.instanceId,
        status = if (merged.isEmpty()) "empty" else "content",
        items = merged,
        hasMore = hasMore,
        loadingMore = false,
        nextCursor = nextCursor,
        errorCode = null
    )
}

private fun KuiklyThreadFilesPayload?.orEmptyFilesPayload(basePayload: KuiklyThreadScreenPayload): KuiklyThreadFilesPayload =
    this ?: KuiklyThreadFilesPayload(instanceId = basePayload.instanceId)

private fun ai.slock.compose.data.ChannelFileItem.filesSourceLabel(): String =
    sourceParentMessageShortId
        ?.takeIf { it.isNotBlank() }
        ?.let { "Thread #$it" }
        ?: sourceType
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { char -> char.uppercaseChar() }
            .orEmpty()

private fun KuiklyThreadScreenPayload.formatFilesTimestampLabel(value: String): String =
    formatSlockTimestampLabel(value, toSlockTimeContext())

private fun KuiklyThreadScreenPayload.hasRenderableThreadContent(): Boolean =
    messages.isNotEmpty() || originMessage != null || tasks.isNotEmpty()

private fun KuiklyThreadScreenPayload.cacheThreadId(): String? =
    sourceThreadId?.takeIf { it.isNotBlank() }

private fun KuiklyThreadScreenPayload.isUsableCachedPayloadForRoute(route: KuiklyThreadScreenPayload): Boolean =
    ThreadCandidateProofGate
        .prove(route = route.toThreadRouteId(), candidate = toThreadRouteId())
        .let { it is ThreadIdentityProof.Accepted }

private fun KuiklyThreadScreenPayload.toThreadRouteId(serverId: ServerId? = null): ThreadRouteId {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val parentMessageStr =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
    val parentMessage = parentMessageStr?.let(::MessageId)
    val stream = streamChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val source = sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
    val isReplyThread =
        surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
            (parentChannel != null && parentMessage != null)
    return when {
        isReplyThread && parentChannel != null && parentMessage != null -> {
            // v0.2 contract: ReplyThread requires explicit threadChannelId. If stream is
            // missing, we still construct ReplyThread (callers handle missing stream via
            // #275-style `activity_thread_channel_missing` internal state + refresh).
            val resolvedStream = stream ?: source?.let { ChannelId(it.value) }
            if (resolvedStream != null) {
                ThreadRouteId.ReplyThread(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    threadChannelId = resolvedStream,
                    serverId = serverId
                )
            } else {
                // Fallback: synthesize from source threadId to keep v0.2 proof path alive.
                val synthesized = source?.let { ChannelId(it.value) } ?: parentChannel
                ThreadRouteId.ReplyThread(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    threadChannelId = synthesized,
                    serverId = serverId
                )
            }
        }
        else -> ThreadRouteId.ChannelThread(
            channelId = source?.let { ChannelId(it.value) } ?: parentChannel ?: ChannelId(""),
            serverId = serverId
        )
    }
}

private fun KuiklyThreadScreenPayload.readAllMarker(): ThreadReadAllMarker? {
    val channelId = cacheThreadId() ?: return null
    val latest = messages.lastOrNull() ?: return null
    return ThreadReadAllMarker(
        channelId = channelId,
        latestMessageId = latest.id,
        latestSeq = latest.seq
    )
}

private fun KuiklyThreadScreenPayload.applyRealtimeEvent(
    event: RealtimeEvent,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId? = null
): KuiklyThreadScreenPayload =
    when (event) {
        is MessageNew ->
            applyRealtimeMessages(listOf(event.message), currentUserId, activeReplyParentMessageId)
        is MessageUpdated ->
            applyRealtimeMessageUpdate(event.message, currentUserId, activeReplyParentMessageId)
        is SyncResumeResponse ->
            applyRealtimeMessages(event.messages, currentUserId, activeReplyParentMessageId)
        is ThreadUpdated ->
            applyThreadUpdated(event)
        is TaskCreated ->
            mergeRealtimeTasks(event.tasks)
        is TaskUpdated ->
            mergeRealtimeTasks(listOf(event.task))
        is TaskDeleted ->
            removeRealtimeTask(event.taskId)
        else -> this
    }

private fun KuiklyThreadScreenPayload.applyRealtimeMessages(
    messages: List<Message>,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId?
): KuiklyThreadScreenPayload {
    val relevant =
        messages.filter { message ->
            message.matchesSharedThread(this, activeReplyParentMessageId)
        }
    if (relevant.isEmpty()) return this
    val incoming = relevant.map { message -> message.toKuiklyThreadMessageItem(currentUserId) }
    val merged =
        (this.messages + incoming)
            .distinctBy { item -> item.id }
    return copy(
        status = "content",
        errorCode = null,
        messages = merged.sortedByThreadTimeline()
    )
}

private fun KuiklyThreadScreenPayload.mergePagedMessages(
    pageMessages: List<KuiklyThreadMessageItem>,
    before: Boolean
): KuiklyThreadScreenPayload {
    val incoming = pageMessages.filterNot { pageMessage -> messages.any { it.id == pageMessage.id } }
    val mergedMessages =
        if (before) {
            (incoming + messages).distinctBy { it.id }
        } else {
            (messages + incoming).distinctBy { it.id }
        }.sortedByThreadTimeline()
    val hasMore = incoming.size >= SharedThreadPaging.pageSize
    return copy(
        status = "content",
        errorCode = null,
        messages = mergedMessages,
        canLoadMoreBefore = if (before) hasMore else canLoadMoreBefore,
        canLoadMoreAfter = if (before) canLoadMoreAfter else hasMore,
        olderMessagesStatus = if (before) "idle" else olderMessagesStatus,
        newerMessagesStatus = if (before) newerMessagesStatus else "idle"
    )
}

private fun KuiklyThreadScreenPayload.withLocalSendStarted(
    message: KuiklyThreadMessageItem
): KuiklyThreadScreenPayload {
    val sendingMessage =
        message.copy(
            deliveryState = ThreadMessageDeliverySending,
            pendingSendErrorCode = null
        )
    val nextMessages =
        if (messages.any { it.id == sendingMessage.id }) {
            messages.map { existing -> if (existing.id == sendingMessage.id) sendingMessage else existing }
        } else {
            messages + sendingMessage
        }
    return copy(
        status = "content",
        errorCode = null,
        messages = nextMessages.sortedByThreadTimeline()
    )
}

private fun KuiklyThreadScreenPayload.withLocalSendFailure(
    messageId: String,
    errorCode: String?
): KuiklyThreadScreenPayload =
    copy(
        messages =
            messages.map { message ->
                if (message.id == messageId && message.pendingSendContext != null) {
                    message.copy(
                        deliveryState = ThreadMessageDeliveryFailed,
                        pendingSendErrorCode = errorCode
                    )
                } else {
                    message
                }
            }
    )

private fun KuiklyThreadScreenPayload.withoutLocalSendMessage(messageId: String): KuiklyThreadScreenPayload =
    copy(messages = messages.filterNot { it.id == messageId && it.pendingSendContext != null })

private fun KuiklyThreadScreenPayload.trimToLatestThreadWindow(limit: Int): KuiklyThreadScreenPayload {
    if (limit <= 0 || messages.isEmpty()) return this
    val sortedMessages = messages.sortedByThreadTimeline()
    if (sortedMessages.size <= limit) {
        return if (sortedMessages == messages) this else copy(messages = sortedMessages)
    }
    return copy(
        status = "content",
        errorCode = null,
        messages = sortedMessages.takeLast(limit),
        canLoadMoreBefore = true,
        olderMessagesStatus = "idle"
    )
}

private fun List<KuiklyThreadMessageItem>.sortedByThreadTimeline(): List<KuiklyThreadMessageItem> =
    sortedWith(
        Comparator { left, right ->
            val leftLocal = left.hasLocalSendState()
            val rightLocal = right.hasLocalSendState()
            if (leftLocal != rightLocal) {
                return@Comparator if (leftLocal) 1 else -1
            }
            if (left.seq != null && right.seq != null) {
                compareValues(left.seq, right.seq).takeIf { it != 0 }?.let { return@Comparator it }
            }
            left.timestampLabel.compareTo(right.timestampLabel).takeIf { it != 0 }
                ?: left.id.compareTo(right.id)
        }
    )

private fun KuiklyThreadScreenPayload.applyRealtimeMessageUpdate(
    message: Message,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId?
): KuiklyThreadScreenPayload {
    val matchesExisting = messages.any { it.id == message.id.value } || originMessage?.id == message.id.value
    if (!matchesExisting && !message.matchesSharedThread(this, activeReplyParentMessageId)) return this
    val incoming = message.toKuiklyThreadMessageItem(currentUserId)
    val reactionUpdate = message.reactions.toRealtimeThreadReactionItems(currentUserId)
    val updatedMessages =
        messages.map { item ->
            if (item.id == incoming.id) item.mergeRealtimeUpdate(incoming, reactionUpdate) else item
        }
    val mergedMessages =
        if (updatedMessages.any { it.id == incoming.id }) {
            updatedMessages
        } else {
            updatedMessages + incoming
        }
    val updatedOrigin =
        if (originMessage?.id == incoming.id) {
            originMessage.mergeRealtimeUpdate(incoming, reactionUpdate)
        } else {
            originMessage
        }
    return copy(
        status = "content",
        errorCode = null,
        messages = mergedMessages.distinctBy { it.id },
        originMessage = updatedOrigin
    )
}

private fun KuiklyThreadScreenPayload.applyThreadUpdated(event: ThreadUpdated): KuiklyThreadScreenPayload {
    fun KuiklyThreadMessageItem.updateIfMatches(): KuiklyThreadMessageItem =
        if (id == event.parentMessageId.value) {
            copy(
                replyCount = maxOf(replyCount, event.replyCount),
                unreadReplyCount = event.unreadCount ?: unreadReplyCount,
                firstUnreadReplyMessageId =
                    when {
                        event.unreadCount == 0 -> null
                        event.firstUnreadMessageId != null -> event.firstUnreadMessageId.value
                        else -> firstUnreadReplyMessageId
                    }
            )
        } else {
            this
        }
    val updatedMessages = messages.map { it.updateIfMatches() }
    val updatedOrigin = originMessage?.updateIfMatches()
    if (updatedMessages == messages && updatedOrigin == originMessage) return this
    return copy(messages = updatedMessages, originMessage = updatedOrigin)
}

private fun KuiklyThreadScreenPayload.mergeRealtimeTasks(tasks: List<TaskSummary>): KuiklyThreadScreenPayload {
    if (tasks.isEmpty()) return this
    val taskReferences =
        messages.mapNotNull { it.toTaskItemOrNull() } +
            listOfNotNull(originMessage?.toTaskItemOrNull()) +
            this.tasks
    val mergedTasks =
        tasks.map { task ->
            val incoming = task.toKuiklyThreadTaskItem()
            taskReferences.firstOrNull { reference -> incoming.matchesTaskReference(reference) }
                ?.mergeTaskReference(incoming)
                ?: incoming
        }
    val updatedMessages = messages.map { message -> message.withRealtimeTask(mergedTasks) }
    val updatedOrigin = originMessage?.withRealtimeTask(mergedTasks)
    val updatedTaskIds = mergedTasks.map { it.id }.toSet()
    val updatedTaskNumbers = mergedTasks.mapNotNull { it.taskNumber }.toSet()
    val updatedTasks =
        (this.tasks.filterNot { task ->
            task.id in updatedTaskIds ||
                (task.taskNumber != null && task.taskNumber in updatedTaskNumbers)
        } + mergedTasks)
            .sortedWith(compareBy(nullsLast<Int>()) { task -> task.taskNumber })
    return copy(messages = updatedMessages, originMessage = updatedOrigin, tasks = updatedTasks)
}

private fun KuiklyThreadScreenPayload.removeRealtimeTask(taskId: TaskId): KuiklyThreadScreenPayload =
    copy(
        messages = messages.map { message -> if (message.taskId == taskId.value) message.withoutTask() else message },
        originMessage = originMessage?.let { message -> if (message.taskId == taskId.value) message.withoutTask() else message },
        tasks = tasks.filterNot { task -> task.id == taskId.value }
    )

internal fun KuiklyThreadScreenPayload.withTaskStatus(taskId: String, status: TaskStatus): KuiklyThreadScreenPayload =
    withTaskStatusLabel(taskId, status.name)

internal fun KuiklyThreadScreenPayload.withTaskStatusLabel(taskId: String, statusLabel: String?): KuiklyThreadScreenPayload =
    copy(
        messages = messages.map { message ->
            if (message.taskId == taskId) message.copy(taskStatus = statusLabel) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.taskId == taskId) message.copy(taskStatus = statusLabel) else message
        },
        tasks = tasks.map { task ->
            if (task.id == taskId && statusLabel != null) task.copy(status = statusLabel) else task
        }
    )

internal fun KuiklyThreadScreenPayload.withActionCard(messageId: String, card: ThreadActionCard): KuiklyThreadScreenPayload =
    copy(
        messages = messages.map { message ->
            if (message.id == messageId) message.withActionCard(card) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == messageId) message.withActionCard(card) else message
        }
    )

private fun KuiklyThreadMessageItem.withActionCard(card: ThreadActionCard): KuiklyThreadMessageItem =
    copy(metadata = metadata.filterKeys { !it.startsWith("actionCard.") } + card.toMessageMetadata())

private fun List<String>.cleanActionCardIds(): List<String> =
    map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

internal fun KuiklyThreadScreenPayload.withContentPatchFrom(patch: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
    val patchResolvesReplyThreadStream = patch.hasResolvedReplyThreadStreamFrom(this)
    return copy(
        instanceId = if (patchResolvesReplyThreadStream) patch.instanceId else instanceId,
        title = patch.title,
        subtitle = patch.subtitle,
        sourceThreadId = if (patchResolvesReplyThreadStream) patch.sourceThreadId else sourceThreadId,
        streamChannelId = if (patchResolvesReplyThreadStream) patch.streamChannelId else streamChannelId,
        parentChannelId = patch.parentChannelId ?: parentChannelId,
        parentMessageId = patch.parentMessageId ?: parentMessageId,
        status = patch.status,
        messages = patch.messages,
        originMessage = patch.originMessage,
        originPreview = patch.originPreview,
        originParentMessageId = patch.originParentMessageId,
        originStatus = patch.originStatus,
        originErrorCode = patch.originErrorCode,
        tasks = patch.tasks,
        canLoadMoreBefore = patch.canLoadMoreBefore,
        canLoadMoreAfter = patch.canLoadMoreAfter,
        olderMessagesStatus = patch.olderMessagesStatus,
        newerMessagesStatus = patch.newerMessagesStatus,
        errorCode = patch.errorCode,
        filesPayload = patch.filesPayload,
        composer = patch.composer,
        showComposer = patch.showComposer,
        joined = patch.joined,
        canSend = patch.canSend,
        emptyTitle = patch.emptyTitle,
        emptyDetail = patch.emptyDetail,
        membersCount = patch.membersCount,
        members = patch.members,
        memberCandidates = patch.memberCandidates,
        canAddMembers = patch.canAddMembers,
        addMemberStatus = patch.addMemberStatus,
        addMemberErrorCode = patch.addMemberErrorCode,
        removeMemberStatus = patch.removeMemberStatus,
        removingMemberId = patch.removingMemberId,
        removeMemberErrorCode = patch.removeMemberErrorCode,
        settingsActions = patch.settingsActions,
        showMembersAction = patch.showMembersAction,
        showSettingsAction = patch.showSettingsAction,
        showOpenInChannelAction = patch.showOpenInChannelAction,
        showMessageSaveAction = patch.showMessageSaveAction,
        showTaskOpenActions = patch.showTaskOpenActions,
        showTaskStatusActions = patch.showTaskStatusActions,
        joinLabel = patch.joinLabel
    )
}

internal fun KuiklyThreadScreenPayload.withMembersPatchFrom(patch: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    copy(
        membersCount = patch.membersCount,
        members = patch.members,
        memberCandidates = patch.memberCandidates,
        canAddMembers = patch.canAddMembers,
        addMemberStatus = patch.addMemberStatus,
        addMemberErrorCode = patch.addMemberErrorCode,
        removeMemberStatus = patch.removeMemberStatus,
        removingMemberId = patch.removingMemberId,
        removeMemberErrorCode = patch.removeMemberErrorCode
    )

internal fun KuiklyThreadScreenPayload.withMessageReaction(
    messageId: String,
    emoji: String,
    selected: Boolean
): KuiklyThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    val cleanEmoji = emoji.trim()
    if (cleanMessageId.isBlank() || cleanEmoji.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.withReactionSelection(cleanEmoji, selected) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.withReactionSelection(cleanEmoji, selected) else message
        }
    )
}

internal fun KuiklyThreadScreenPayload.withMessageReactions(
    messageId: String,
    reactions: List<KuiklyThreadReactionItem>
): KuiklyThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    if (cleanMessageId.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.copy(reactions = reactions) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.copy(reactions = reactions) else message
        }
    )
}

internal fun KuiklyThreadScreenPayload.withMessageSaved(
    messageId: String,
    saved: Boolean
): KuiklyThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    if (cleanMessageId.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.copy(isSaved = saved) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.copy(isSaved = saved) else message
        }
    )
}

internal fun KuiklyThreadScreenPayload.withSavedMessageIds(savedIds: Set<String>): KuiklyThreadScreenPayload =
    copy(
        messages = messages.map { message -> message.copy(isSaved = message.id in savedIds) },
        originMessage = originMessage?.let { message -> message.copy(isSaved = message.id in savedIds) }
    )

private fun KuiklyThreadScreenPayload.withMessageMutationSummary(
    summary: MessageMutationSummary,
    currentUserId: String?
): KuiklyThreadScreenPayload {
    val messageId = summary.messageId.trim()
    if (messageId.isBlank()) return this
    val reactions = summary.reactions.toThreadReactionItems(currentUserId)
    return copy(
        messages = messages.map { message ->
            if (message.id == messageId) message.copy(reactions = reactions) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == messageId) message.copy(reactions = reactions) else message
        }
    )
}

private fun KuiklyThreadMessageItem.withReactionSelection(emoji: String, selected: Boolean): KuiklyThreadMessageItem {
    var matched = false
    val updated =
        reactions.mapNotNull { reaction ->
            if (reaction.emoji != emoji) return@mapNotNull reaction
            matched = true
            val delta =
                when {
                    selected && !reaction.selected -> 1
                    !selected && reaction.selected -> -1
                    else -> 0
                }
            val nextCount = (reaction.count + delta).coerceAtLeast(0)
            if (nextCount > 0) {
                reaction.copy(count = nextCount, selected = selected)
            } else {
                null
            }
        }
    val nextReactions =
        if (!matched && selected) {
            updated + KuiklyThreadReactionItem(emoji = emoji, count = 1, selected = true)
        } else {
            updated
        }
    return copy(reactions = nextReactions)
}

private fun List<MessageReactionSummary>.toThreadReactionItems(currentUserId: String?): List<KuiklyThreadReactionItem> =
    mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        KuiklyThreadReactionItem(
            emoji = emoji,
            count = count,
            selected = currentUserId != null && currentUserId in reaction.reactorIds
        )
    }

private fun List<MessageReaction>?.toRealtimeThreadReactionItems(currentUserId: String?): List<KuiklyThreadReactionItem>? =
    this?.mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        KuiklyThreadReactionItem(
            emoji = emoji,
            count = count,
            selected = currentUserId != null && currentUserId in reaction.reactorIds
        )
    }

private fun Message.matchesSharedThread(
    payload: KuiklyThreadScreenPayload,
    activeReplyParentMessageId: MessageId?
): Boolean {
    val selectedThreadId = payload.cacheThreadId()
    val originParentMessageId = payload.originParentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
    return selectedThreadId?.let { selectedId ->
        threadId.value == selectedId ||
            channelId?.value == selectedId ||
            threadChannelId?.value == selectedId
    } == true ||
        originParentMessageId?.let { parentMessageId == it } == true ||
        activeReplyParentMessageId?.let { parentMessageId == it } == true
}

private fun KuiklyThreadMessageItem.mergeRealtimeUpdate(
    update: KuiklyThreadMessageItem,
    reactionUpdate: List<KuiklyThreadReactionItem>? = null
): KuiklyThreadMessageItem =
    copy(
        authorId = update.authorId.ifBlank { authorId },
        authorName = update.authorName.takeUnless { it.isBlank() || it == update.authorId } ?: authorName,
        isOwn = update.isOwn,
        avatarLabel = update.avatarLabel.ifBlank { avatarLabel },
        avatarWireValue = update.avatarWireValue ?: avatarWireValue,
        gravatarHash = update.gravatarHash ?: gravatarHash,
        timestampLabel = update.timestampLabel.ifBlank { timestampLabel },
        seq = update.seq ?: seq,
        messageType = update.messageType.ifBlank { messageType },
        systemEventType = update.systemEventType ?: systemEventType,
        metadata = update.metadata.ifEmpty { metadata },
        body = update.body.ifBlank { body },
        threadChannelId = update.threadChannelId ?: threadChannelId,
        replyCount = maxOf(replyCount, update.replyCount),
        unreadReplyCount = maxOf(unreadReplyCount, update.unreadReplyCount),
        firstUnreadReplyMessageId = update.firstUnreadReplyMessageId ?: firstUnreadReplyMessageId,
        isSaved = isSaved || update.isSaved,
        taskId = update.taskId ?: taskId,
        taskNumber = update.taskNumber ?: taskNumber,
        taskStatus = update.taskStatus ?: taskStatus,
        taskAssigneeId = update.taskAssigneeId ?: taskAssigneeId,
        taskAssigneeName = update.taskAssigneeName ?: taskAssigneeName,
        reactions = reactionUpdate ?: update.reactions.ifEmpty { reactions },
        attachments = mergeAttachmentCommentMetadata(update.attachments),
        commentRef = update.commentRef ?: commentRef
    )

private fun KuiklyThreadMessageItem.mergeAttachmentCommentMetadata(
    updates: List<KuiklyThreadAttachmentItem>
): List<KuiklyThreadAttachmentItem> {
    if (updates.isEmpty()) return attachments
    if (attachments.isEmpty()) return updates
    val previousById = attachments.associateBy { it.id }
    return updates.map { update ->
        val previous = previousById[update.id]
        if (previous != null && update.commentCount == 0 && previous.commentCount > 0) {
            update.copy(commentCount = previous.commentCount)
        } else {
            update
        }
    }
}

private fun KuiklyThreadMessageItem.withSenderProfile(profile: MemberProfile?): KuiklyThreadMessageItem {
    profile ?: return this
    val profileAvatarWireValue = profile.avatar?.wireValue ?: profile.avatarUrl
    val isAgent = profile.kind == MemberKind.Agent || authorKind.equals("agent", ignoreCase = true)
    return copy(
        authorName = authorName.takeUnless { it.isBlank() || it == authorId || it == "Unknown" } ?: profile.displayName,
        authorKind =
            when (profile.kind) {
                MemberKind.Agent -> "agent"
                MemberKind.Human -> "human"
                else -> authorKind
            },
        avatarLabel = profile.avatarLabel.ifBlank { avatarLabel },
        avatarWireValue = avatarWireValue ?: profileAvatarWireValue,
        gravatarHash = if (isAgent) null else gravatarHash ?: profile.gravatarHash
    )
}

private fun KuiklyThreadScreenPayload.withSenderProfiles(
    profilesById: Map<String, MemberProfile>
): KuiklyThreadScreenPayload =
    copy(
        messages = messages.map { it.withSenderProfile(profilesById[it.authorId]) },
        originMessage = originMessage?.withSenderProfile(profilesById[originMessage.authorId])
    )

private fun KuiklyThreadScreenPayload.withHomeDirectMessageAvatars(
    avatarByMemberId: Map<String, String>
): KuiklyThreadScreenPayload {
    if (avatarByMemberId.isEmpty()) return this
    return copy(
        messages = messages.map { it.withHomeDirectMessageAvatar(avatarByMemberId[it.authorId]) },
        originMessage = originMessage?.withHomeDirectMessageAvatar(avatarByMemberId[originMessage.authorId])
    )
}

private fun KuiklyThreadMessageItem.withHomeDirectMessageAvatar(avatarWireValue: String?): KuiklyThreadMessageItem {
    val wireValue = avatarWireValue?.takeIf { it.isNotBlank() } ?: return this
    val isAgent = authorKind.equals("agent", ignoreCase = true)
    if (!isAgent) return this
    val currentWireValue = this.avatarWireValue?.takeIf { it.isNotBlank() }
    return copy(
        avatarWireValue =
            if (currentWireValue == null || currentWireValue.isGravatarAvatarWireValue()) {
                wireValue
            } else {
                currentWireValue
            },
        gravatarHash = null
    )
}

private fun HomeSnapshot.directMessageAvatarWireValuesByMemberId(): Map<String, String> =
    directMessages.mapNotNull { conversation ->
        val memberId = conversation.presenceMemberId?.value ?: return@mapNotNull null
        val avatarWireValue =
            conversation.avatar?.wireValue
                ?: conversation.avatarUrl
                ?: return@mapNotNull null
        memberId to avatarWireValue
    }.toMap()

private fun KuiklyThreadMessageItem.toTaskItemOrNull(): KuiklyThreadTaskItem? {
    if (taskId == null && taskNumber == null) return null
    return KuiklyThreadTaskItem(
        id = taskId ?: "task-${taskNumber ?: id}",
        title = body.ifBlank { taskNumber?.let { "Task #$it" } ?: "Task" },
        status = taskStatus ?: TaskStatus.Todo.name,
        taskNumber = taskNumber,
        messageId = id,
        assigneeId = taskAssigneeId,
        assigneeName = taskAssigneeName,
        updatedAtLabel = timestampLabel
    )
}

private fun KuiklyThreadMessageItem.withRealtimeTask(tasks: List<KuiklyThreadTaskItem>): KuiklyThreadMessageItem {
    val task = tasks.firstOrNull { it.matchesMessage(this) } ?: return this
    return copy(
        taskId = task.id,
        taskNumber = task.taskNumber ?: taskNumber,
        taskStatus = task.status,
        taskAssigneeId = task.assigneeId ?: taskAssigneeId,
        taskAssigneeName = task.assigneeName ?: taskAssigneeName
    )
}

private fun KuiklyThreadMessageItem.withoutTask(): KuiklyThreadMessageItem =
    copy(taskId = null, taskNumber = null, taskStatus = null, taskAssigneeId = null, taskAssigneeName = null)

private fun KuiklyThreadTaskItem.matchesMessage(message: KuiklyThreadMessageItem): Boolean =
    id == message.taskId ||
        id == message.id ||
        (taskNumber != null && taskNumber == message.taskNumber)

private fun KuiklyThreadTaskItem.matchesTaskReference(referenceTask: KuiklyThreadTaskItem): Boolean =
    id == referenceTask.id ||
        (taskNumber != null && taskNumber == referenceTask.taskNumber)

private fun KuiklyThreadTaskItem.mergeTaskReference(incomingTask: KuiklyThreadTaskItem): KuiklyThreadTaskItem =
    incomingTask.copy(
        title = incomingTask.title.ifBlank { title },
        taskNumber = incomingTask.taskNumber ?: taskNumber,
        assigneeName = incomingTask.assigneeName ?: assigneeName,
        updatedAtLabel = incomingTask.updatedAtLabel.ifBlank { updatedAtLabel }
    )

private fun ThreadSnapshot.toKuiklyThreadPayload(
    base: KuiklyThreadScreenPayload,
    currentUserId: String?
): KuiklyThreadScreenPayload {
    val routeIsReplyThread =
        base.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
            conversation.kind == ConversationKind.Thread ||
            conversation.thread != null
    val parentId =
        if (routeIsReplyThread) {
            parentMessage?.id?.value
                ?: base.parentMessageId?.takeIf { it.isNotBlank() }
                ?: base.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentMessageId?.value
        } else {
            null
        }
    val resolvedParentMessage =
        parentMessage
            ?: parentId?.let { id -> messages.firstOrNull { message -> message.id.value == id } }
    val surfaceKind =
        when {
            conversation.kind == ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
            routeIsReplyThread &&
                (resolvedParentMessage != null || !base.parentMessageId.isNullOrBlank() || !base.originParentMessageId.isNullOrBlank()) ->
                KuiklyThreadSurfaceKind.ReplyThread
            else -> base.surfaceKind
        }
    val streamChannelId =
        base.streamChannelId
            ?: conversation.thread?.streamChannelId?.value
            ?: if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                null
            } else {
                conversation.channelId?.value ?: conversation.threadId.value
            }
    val parentChannelId = base.parentChannelId ?: conversation.thread?.parentChannelId?.value
    val parentMessageId = base.parentMessageId ?: resolvedParentMessage?.id?.value ?: conversation.thread?.parentMessageId?.value
    val cachedMessages =
        messages
            .filterNot { message -> parentId != null && message.id.value == parentId }
            .map { message -> message.toKuiklyThreadMessageItem(currentUserId) }
    val headerContext =
        SharedThreadHeaderContextStore.conversationContext(
            surfaceKind = surfaceKind,
            conversation = conversation,
            originMessage = resolvedParentMessage?.toKuiklyThreadMessageItem(currentUserId),
            parentMessageId = parentId,
            dmPeerName = base.dmPeerName?.takeIf { it.isNotBlank() }
        )
    val dmPeerAvatarWireValue = conversation.avatar?.wireValue ?: conversation.avatarUrl
    val isDirectMessage = surfaceKind == KuiklyThreadSurfaceKind.DirectMessage
    return base.copy(
        title = headerContext.title,
        subtitle = headerContext.subtitle,
        status = if (cachedMessages.isEmpty() && headerContext.originMessage == null && tasks.isEmpty()) "empty" else "content",
        sourceThreadId =
            base.sourceThreadId
                ?: conversation.thread?.streamChannelId?.value
                ?: if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                    null
                } else {
                    conversation.channelId?.value ?: conversation.threadId.value
                },
        surfaceKind = surfaceKind,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        memberScopedMentions = base.memberScopedMentions || conversation.usesMemberScopedMentions(),
        messages = cachedMessages,
        originMessage = headerContext.originMessage,
        originPreview = headerContext.originPreview,
        originParentMessageId = headerContext.originParentMessageId,
        focusedMessageId = base.focusedMessageId,
        originStatus = headerContext.originStatus,
        originErrorCode = headerContext.originErrorCode,
        tasks = tasks.map { task -> task.toKuiklyThreadTaskItem() },
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        joined = conversation.joined,
        canSend = conversation.permissions.canSend,
        membersCount = conversation.memberCount ?: base.membersCount,
        errorCode = null,
        dmPeerName = base.dmPeerName ?: conversation.name.takeIf { isDirectMessage },
        dmPeerAvatarUrl = base.dmPeerAvatarUrl ?: dmPeerAvatarWireValue.takeIf { isDirectMessage },
        dmPeerAvatarLabel = base.dmPeerAvatarLabel ?: conversation.name.take(2).uppercase().takeIf { isDirectMessage },
        dmPeerGravatarUrl = base.dmPeerGravatarUrl ?: conversation.gravatarUrl.takeIf { isDirectMessage }
    )
}

private fun KuiklyThreadScreenPayload.toThreadSnapshot(): ThreadSnapshot? {
    if (!hasRenderableThreadContent()) return null
    val threadIdValue = cacheThreadId() ?: return null
    val threadId = ThreadId(threadIdValue)
    val channelId = ChannelId(threadIdValue)
    val replyParentChannelId = parentChannelId?.takeIf { it.isNotBlank() }
    val replyParentMessageId =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
    val snapshotKind =
        when (surfaceKind) {
            KuiklyThreadSurfaceKind.DirectMessage -> ConversationKind.DirectMessage
            KuiklyThreadSurfaceKind.ReplyThread -> ConversationKind.Thread
            else -> if (originParentMessageId.isNullOrBlank()) ConversationKind.Channel else ConversationKind.Thread
        }
    val conversation =
        ConversationSummary(
            threadId = threadId,
            channelId = channelId,
            thread =
                if (snapshotKind == ConversationKind.Thread &&
                    replyParentChannelId != null &&
                    replyParentMessageId != null
                ) {
                    ThreadConversation(
                        parentChannelId = ChannelId(replyParentChannelId),
                        parentMessageId = MessageId(replyParentMessageId),
                        streamChannelId = channelId
                    )
                } else {
                    null
                },
            kind = snapshotKind,
            name = title,
            subtitle = subtitle,
            joined = joined,
            memberCount = membersCount.takeIf { it > 0 },
            permissions = ConversationPermissions(canSend = canSend)
        )
    val parentChannel =
        if (snapshotKind == ConversationKind.Thread && replyParentChannelId != null) {
            ChannelId(replyParentChannelId)
        } else {
            channelId
        }
    val parentThread =
        if (snapshotKind == ConversationKind.Thread && replyParentChannelId != null) {
            ThreadId(replyParentChannelId)
        } else {
            threadId
        }
    val parent = originMessage?.toMessage(threadId = parentThread, channelId = parentChannel)
    val snapshotMessages =
        (listOfNotNull(parent) + messages.map { item -> item.toMessage(threadId, channelId) })
            .distinctBy { it.id }
    return ThreadSnapshot(
        conversation = conversation,
        messages = snapshotMessages,
        parentMessage = parent,
        tasks = tasks.map { task -> task.toTaskSummary(threadId = threadId, channelId = channelId) },
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter
    )
}

private fun KuiklyThreadMessageItem.toMessage(
    threadId: ThreadId,
    channelId: ChannelId
): Message {
    val taskSummary =
        if (taskId != null || taskNumber != null) {
            TaskSummary(
                id = TaskId(taskId ?: "task-${taskNumber ?: id}"),
                title = body.ifBlank { taskNumber?.let { "Task #$it" } ?: "Task" },
                status = TaskStatus.fromWireValue(taskStatus) ?: TaskStatus.Todo,
                taskNumber = taskNumber,
                messageId = MessageId(id),
                channelId = channelId,
                threadId = threadId,
                assignee = taskAssigneeId?.takeIf { it.isNotBlank() }?.let { id ->
                    val name = taskAssigneeName?.takeIf { it.isDisplaySafeTaskAssigneeName(id) } ?: id
                    MemberProfile(
                        id = MemberId(id),
                        displayName = name,
                        handle = name.removePrefix("@"),
                        kind = MemberKind.Human
                    )
                },
                updatedAtLabel = timestampLabel
            )
        } else {
            null
        }
    return Message(
        id = MessageId(id),
        threadId = threadId,
        channelId = channelId,
        threadChannelId = threadChannelId?.let(::ChannelId),
        author = toMemberProfile(),
        seq = seq,
        timestampLabel = timestampLabel,
        body = body,
        messageType = if (messageType.equals("System", ignoreCase = true)) MessageType.System else MessageType.Chat,
        systemEventType = systemEventType,
        metadata = metadata,
        attachments = attachments.map { attachment -> attachment.toAttachment() },
        deliveryState = deliveryState.toMessageDeliveryState(),
        task = taskSummary,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.let(::MessageId),
        isSaved = isSaved,
        commentRef = commentRef?.toMessageCommentRef()
    )
}

private fun KuiklyThreadMessageItem.toMemberProfile(): MemberProfile =
    MemberProfile(
        id = MemberId(authorId),
        displayName = authorName,
        handle = authorName.removePrefix("@"),
        kind = if (authorKind.equals("agent", ignoreCase = true)) MemberKind.Agent else MemberKind.Human,
        avatarUrl = avatarWireValue?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        gravatarHash = gravatarHash,
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = avatarLabel.ifBlank { authorName.take(1).uppercase() },
        presence = Presence.Offline
    )

private fun KuiklyThreadAttachmentItem.toAttachment(): Attachment =
    Attachment(
        id = AttachmentId(id),
        name = name,
        mimeType = mimeType,
        sizeLabel = sizeLabel,
        thumbnailUrl = thumbnailUrl,
        commentCount = commentCount
    )

internal fun StoredAuthSession.currentServerSlug(): String? =
    selectedServerSlug?.takeIf { it.isNotBlank() }
        ?: currentServerId
            ?.let { selectedId -> servers.firstOrNull { it.id == selectedId }?.slug }
            ?.takeIf { it.isNotBlank() }
        ?: servers.firstOrNull()?.slug?.takeIf { it.isNotBlank() }

private fun Message.toQuotedMessagePreview(
    permalink: SlockQuotedMessagePermalink,
    conversation: ConversationSummary
): ThreadQuotedMessagePreviewUiState =
    ThreadQuotedMessagePreviewUiState(
        status = "loaded",
        permalink = permalink,
        channelName = conversation.quotedPreviewChannelName(permalink),
        channelKind = if (conversation.kind == ConversationKind.DirectMessage || permalink.routeKind == "dm") "dm" else "channel",
        isThread = permalink.threadParentMessageId != null || conversation.kind == ConversationKind.Thread,
        isArchived = conversation.archived,
        timestampLabel = timestampLabel,
        authorName = author.displayName,
        authorKind = if (author.kind == MemberKind.Agent) "agent" else "human",
        authorSubtitle = author.description ?: author.roleDescription,
        content = body,
        attachmentLabel = attachments.quotedPreviewAttachmentLabel()
    )

private fun ConversationSummary.quotedPreviewChannelName(permalink: SlockQuotedMessagePermalink): String =
    name.takeIf { it.isNotBlank() }
        ?: if (kind == ConversationKind.DirectMessage || permalink.routeKind == "dm") {
            "Direct message"
        } else {
            "Channel"
        }

private fun List<Attachment>.quotedPreviewAttachmentLabel(): String? {
    if (isEmpty()) return null
    val imageCount = count { it.mimeType.startsWith("image/", ignoreCase = true) }
    return when {
        size == 1 && imageCount == 1 -> "1 image"
        size == 1 -> "1 file"
        imageCount == size -> "$imageCount images"
        else -> "$size attachments"
    }
}

private fun KuiklyThreadMessageCommentRef.toMessageCommentRef(): MessageCommentRef? {
    val cleanAttachmentId = attachmentId.takeIf { it.isNotBlank() } ?: return null
    val cleanFilename = filename.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentRef(
        attachmentId = AttachmentId(cleanAttachmentId),
        filename = cleanFilename,
        hostMessageId = hostMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        hostSource = hostSource?.toMessageCommentHostSource(),
        anchorLabel = anchorLabel?.takeIf { it.isNotBlank() }
    )
}

private fun KuiklyThreadMessageCommentHostSource.toMessageCommentHostSource(): MessageCommentHostSource? {
    val cleanType = type.takeIf { it == "channel" || it == "thread" } ?: return null
    val cleanRouteKind = routeKind.takeIf { it == "channel" || it == "dm" } ?: return null
    val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentHostSource(
        type = cleanType,
        routeKind = cleanRouteKind,
        channelId = ChannelId(cleanChannelId),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        rootThreadChannelId = rootThreadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    )
}

private fun Message.toKuiklyThreadMessageItem(currentUserId: String?): KuiklyThreadMessageItem =
    KuiklyThreadMessageItem(
        id = id.value,
        authorId = author.id.value,
        authorName = author.displayName,
        authorKind =
            when (author.kind) {
                MemberKind.Agent -> "agent"
                MemberKind.System -> "system"
                else -> "human"
            },
        isOwn = currentUserId != null && author.id.value == currentUserId,
        avatarLabel = author.avatarLabel,
        avatarWireValue = author.avatar?.wireValue ?: author.avatarUrl,
        gravatarHash = author.gravatarHash,
        seq = seq,
        timestampLabel = timestampLabel,
        messageType = if (messageType == MessageType.System) "System" else "Chat",
        systemEventType = systemEventType,
        metadata = metadata,
        body = body,
        threadChannelId = threadChannelId?.value,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.value,
        isSaved = isSaved,
        taskId = task?.id?.value,
        taskNumber = task?.taskNumber,
        taskStatus = task?.status?.name,
        taskAssigneeId = task?.assignee?.id?.value,
        taskAssigneeName = task?.assignee?.displayName,
        deliveryState = deliveryState.toKuiklyThreadDeliveryState(),
        attachments = attachments.map { attachment ->
            KuiklyThreadAttachmentItem(
                id = attachment.id.value,
                name = attachment.name,
                mimeType = attachment.mimeType,
                sizeLabel = attachment.sizeLabel,
                thumbnailUrl = attachment.thumbnailUrl,
                commentCount = attachment.commentCount
            )
        },
        reactions = reactions.toRealtimeThreadReactionItems(currentUserId).orEmpty(),
        commentRef = commentRef?.toKuiklyThreadCommentRef()
    )

private fun String.toMessageDeliveryState(): MessageDeliveryState =
    when (trim().lowercase()) {
        ThreadMessageDeliverySending -> MessageDeliveryState.Sending
        ThreadMessageDeliveryFailed -> MessageDeliveryState.Failed
        else -> MessageDeliveryState.Sent
    }

private fun MessageDeliveryState.toKuiklyThreadDeliveryState(): String =
    when (this) {
        MessageDeliveryState.Sending -> ThreadMessageDeliverySending
        MessageDeliveryState.Failed -> ThreadMessageDeliveryFailed
        MessageDeliveryState.Sent -> ThreadMessageDeliverySent
    }

private fun MessageCommentRef.toKuiklyThreadCommentRef(): KuiklyThreadMessageCommentRef =
    KuiklyThreadMessageCommentRef(
        attachmentId = attachmentId.value,
        filename = filename,
        hostMessageId = hostMessageId?.value,
        hostSource = hostSource?.toKuiklyThreadCommentHostSource(),
        anchorLabel = anchorLabel
    )

private fun MessageCommentHostSource.toKuiklyThreadCommentHostSource(): KuiklyThreadMessageCommentHostSource =
    KuiklyThreadMessageCommentHostSource(
        type = type,
        routeKind = routeKind,
        channelId = channelId.value,
        parentMessageId = parentMessageId?.value,
        threadChannelId = threadChannelId?.value,
        rootThreadChannelId = rootThreadChannelId?.value
    )

private fun KuiklyThreadTaskItem.toTaskSummary(
    threadId: ThreadId,
    channelId: ChannelId
): TaskSummary {
    val assigneeKey =
        assigneeId?.takeIf { it.isNotBlank() }
            ?: assigneeName?.takeIf { it.isDisplaySafeTaskAssigneeName() }
    return TaskSummary(
        id = TaskId(id),
        title = title,
        status = TaskStatus.fromWireValue(status) ?: TaskStatus.Todo,
        taskNumber = taskNumber,
        channelId = channelId,
        threadId = threadId,
        assignee = assigneeKey?.let { id ->
            val name = assigneeName?.takeIf { it.isDisplaySafeTaskAssigneeName(id) } ?: id
            MemberProfile(
                id = MemberId(id),
                displayName = name,
                handle = name.removePrefix("@"),
                kind = MemberKind.Human
            )
        },
        updatedAtLabel = updatedAtLabel
    )
}

private fun TaskSummary.toKuiklyThreadTaskItem(): KuiklyThreadTaskItem =
    KuiklyThreadTaskItem(
        id = id.value,
        title = title,
        status = status.name,
        taskNumber = taskNumber,
        messageId = messageId?.value,
        assigneeId = assignee?.id?.value,
        assigneeName = assignee?.displayName,
        updatedAtLabel = updatedAtLabel
    )
