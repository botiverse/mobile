package ai.slock.compose

import ai.slock.compose.data.DataResult
import ai.slock.compose.data.EmptyReason
import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.ThreadActionResult
import ai.slock.compose.data.AttachmentUploadFile
import ai.slock.compose.data.AttachmentUploadRequest
import ai.slock.compose.data.AttachmentUploadResult
import ai.slock.compose.data.appendSharedThreadSentMessageFromPage
import ai.slock.compose.data.buildSharedThreadPayloadFromMessagesPage
import ai.slock.compose.data.ChannelFilesSnapshot
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
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.SlockThreadApi
import ai.slock.compose.data.SlockThreadApiClient
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.SharedThreadNetworkApi
import ai.slock.compose.data.ThreadSnapshot
import ai.slock.compose.data.ThreadApiResult
import ai.slock.compose.data.ThreadAttachmentOpenTarget
import ai.slock.compose.data.ThreadMessagesPage
import ai.slock.compose.data.map
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
import ai.slock.compose.model.MessageDeliveryState
import ai.slock.compose.model.MessageId
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

internal class SharedThreadDataSource(
    private val api: SlockThreadApi,
    private val sessionStore: SlockAuthSessionStore,
    private val localWriteThrough: ComposeLocalWriteThrough? = null,
    private val memberRepository: MemberRepository? = null,
    private val conversationRepository: ConversationRepository? = null,
    private val channelRepository: ChannelRepository? = null
) {
    constructor(
        networkApi: SharedThreadNetworkApi,
        sessionStore: SlockAuthSessionStore,
        localWriteThrough: ComposeLocalWriteThrough? = null,
        memberRepository: MemberRepository? = null,
        conversationRepository: ConversationRepository? = null,
        channelRepository: ChannelRepository? = null
    ) : this(
        api = SlockThreadApiClient(networkApi),
        sessionStore = sessionStore,
        localWriteThrough = localWriteThrough,
        memberRepository = memberRepository,
        conversationRepository = conversationRepository,
        channelRepository = channelRepository
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

    suspend fun fetchThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        persistResult: Boolean = true
    ): DataResult<KuiklyThreadScreenPayload> {
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val session =
            readSession()
                ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        val payloadResult =
            if (conversationRepository != null) {
                fetchThreadPayloadFromRepository(
                    session = session,
                    fallbackCode = "thread_network",
                    call = { repository -> repository.getThread(ThreadId(threadId), limit = SharedThreadPaging.initialPageSize) },
                    transform = { snapshot -> snapshot.toKuiklyThreadPayload(basePayload, session.user.id) }
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
                        basePayload = basePayload,
                        page = page,
                        session = session
                    ).withCachedSenderProfiles(session)
                )
            }
        val payload =
            when (payloadResult) {
                is DataResult.Content -> payloadResult.data
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
                ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
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

    suspend fun enrichThreadMembers(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val repository = memberRepository ?: return payload
        val threadId = payload.sourceThreadId?.takeIf { it.isNotBlank() } ?: return payload
        val channelMembers =
            try {
                repository.getChannelMembers(DataRequestPriority.Visible, ChannelId(threadId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return payload
            }
        if (channelMembers !is DataResult.Content) return payload
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
        return payload.copy(
            membersCount = members.size,
            members = members.map { it.toKuiklyThreadMemberItem() },
            memberCandidates = candidates,
            canAddMembers = channelMembers.data.canAddMembers
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
        val nextPayload = enrichThreadMembers(payload)
        return nextPayload
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

    suspend fun attachmentUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentUrl(attachmentId)

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
                title = "Message Threads",
                subtitle = basePayload.title,
                status = "loading",
                sourceThreadId = threadChannelId,
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
                canLoadMoreAfter = false
            )
        return when (val loaded = fetchThreadPayload(replyBase, persistResult = false)) {
            is DataResult.Content -> {
                val payload =
                    loaded.data.copy(
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
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
            ?: return
        localWriteThrough?.writeRealtimeEvent(serverId, event)
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
                ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
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
                ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        return if (conversationRepository != null) {
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
    }

    private suspend fun fetchThreadPayloadFromRepository(
        session: StoredAuthSession,
        fallbackCode: String,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>,
        transform: (ThreadSnapshot) -> KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
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
        return result.map { snapshot -> transform(snapshot).withCachedSenderProfiles(session) }
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
    const val initialPageSize = 10
    const val pageSize = initialPageSize
}
private const val SharedThreadFirstFrameDelayMillis = 16L

internal data class SharedThreadState(
    val payload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    val highlightedMentions: Set<String> = emptySet()
)

internal sealed interface SharedThreadIntent {
    data class Start(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data object Refresh : SharedThreadIntent
    data class AppendSentPage(val page: ThreadMessagesPage) : SharedThreadIntent
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
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(SharedThreadState())
    val state: StateFlow<SharedThreadState> = _state.asStateFlow()
    private var loadJob: Job? = null

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
            is SharedThreadIntent.ApplyMessageReactionSelection,
            is SharedThreadIntent.ApplyMessageReactions,
            is SharedThreadIntent.ApplyMessageMutation,
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
            is SharedThreadIntent.ApplyMessageReactionSelection ->
                applyMessageReactionSelection(intent.messageId, intent.emoji, intent.selected)
            is SharedThreadIntent.ApplyMessageReactions ->
                applyMessageReactions(intent.messageId, intent.reactions)
            is SharedThreadIntent.ApplyMessageMutation -> applyMessageMutation(intent.summary)
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
    }

    private suspend fun start(initialPayload: KuiklyThreadScreenPayload) {
        dataSource.cachePayload(initialPayload)
        val threadId = initialPayload.sourceThreadId?.takeIf { it.isNotBlank() }
        val cached = threadId?.let(dataSource::cachedPayload)?.withRouteContext(initialPayload)
        val current = _state.value.payload.takeIf { it.sourceThreadId == threadId && it.hasRenderableThreadContent() }
        val visiblePayload =
            cached
                ?: current
                ?: if (threadId == null) {
                    initialPayload
                } else {
                    initialPayload.copy(status = "loading", errorCode = null)
                }
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
        if (threadId == null) {
            return
        }
        if (cached == null && current == null && !visiblePayload.hasRenderableThreadContent()) {
            val persistentCached = dataSource.readCachedPayload(initialPayload)
            if (persistentCached != null && _state.value.payload.sourceThreadId == threadId) {
                reducePayloadPatch { currentPayload ->
                    currentPayload.withContentPatchFrom(persistentCached.withRouteContext(initialPayload))
                }
            }
        }
        refresh(initialPayload)
    }

    private suspend fun refresh(basePayload: KuiklyThreadScreenPayload) {
        val existing = _state.value.payload
        val hasRenderableContent = existing.hasRenderableThreadContent()
        val loadingBase =
            if (hasRenderableContent) {
                existing.withRouteContext(basePayload)
            } else {
                basePayload.copy(status = "loading", errorCode = null)
            }
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
        if (emitted && visiblePayload != null) {
            writeCachedPayloadAfterFrame(_state.value.payload)
        }
    }

    private suspend fun loadMembers() {
        val existing = _state.value.payload
        if (existing.sourceThreadId.isNullOrBlank()) return
        val enrichedPayload = dataSource.enrichThreadMembers(existing)
        if (enrichedPayload == existing) return
        reducePayloadPatch(cachePayload = true) { currentPayload -> currentPayload.withMembersPatchFrom(enrichedPayload) }
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
        val merged =
            existing.applyRealtimeEvent(
                event = event,
                currentUserId = dataSource.readSession()?.user?.id,
                activeReplyParentMessageId = activeReplyParentMessageId
        )
        dataSource.writeRealtimeEvent(event)
        val stableMerged = merged.preserveStableTimelineFrom(existing)
        reducePayloadPatchAndWriteCache(
            cachePayload = true,
            writeCachedPayload = true
        ) { currentPayload -> currentPayload.withContentPatchFrom(stableMerged) }
    }

    private suspend fun markVisibleReadAll() {
        val channelId = _state.value.payload.cacheThreadId() ?: return
        dataSource.markChannelReadAll(channelId)
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
    private val dataSource: SharedThreadDataSource
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadStore(dataSource = dataSource, scope = viewModelScope)
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

private fun KuiklyThreadScreenPayload.withRouteContext(base: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    copy(
        instanceId = base.instanceId.ifBlank { instanceId },
        title = base.title.ifBlank { title },
        subtitle = base.subtitle ?: subtitle,
        sourceThreadId = base.sourceThreadId ?: sourceThreadId,
        navigationLabel = base.navigationLabel,
        selectedTab = base.selectedTab,
        showModeTabs = base.showModeTabs,
        alignOwnMessagesRight = base.alignOwnMessagesRight,
        localOffsetMinutes = base.localOffsetMinutes,
        todayEpochDay = base.todayEpochDay ?: todayEpochDay,
        canSend = base.canSend,
        joined = base.joined,
        membersCount = if (base.membersCount > 0) base.membersCount else membersCount,
        composerPayload = base.composerPayload.ifBlank { composerPayload },
        emptyTitle = base.emptyTitle,
        emptyDetail = base.emptyDetail
    )

internal fun KuiklyThreadScreenPayload.preserveStableTimelineFrom(
    previous: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload {
    val stableMessages = messages.preserveStableItemsFrom(previous.messages) { item -> item.id }
    val stableTasks = tasks.preserveStableItemsFrom(previous.tasks) { item -> item.id }
    val stableOrigin =
        if (originMessage != null && originMessage == previous.originMessage) {
            previous.originMessage
        } else {
            originMessage
        }
    if (stableMessages === messages && stableTasks === tasks && stableOrigin === originMessage) {
        return this
    }
    return copy(messages = stableMessages, originMessage = stableOrigin, tasks = stableTasks)
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
    todayEpochDay?.let { today ->
        formatSlockTimestampLabel(value, localOffsetMinutes, today)
    } ?: value

private fun KuiklyThreadScreenPayload.hasRenderableThreadContent(): Boolean =
    messages.isNotEmpty() || originMessage != null || tasks.isNotEmpty()

private fun KuiklyThreadScreenPayload.cacheThreadId(): String? =
    sourceThreadId?.takeIf { it.isNotBlank() }

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
    val updatedMessages = messages.map { item -> if (item.id == incoming.id) item.mergeRealtimeUpdate(incoming) else item }
    val mergedMessages =
        if (updatedMessages.any { it.id == incoming.id }) {
            updatedMessages
        } else {
            updatedMessages + incoming
        }
    val updatedOrigin =
        if (originMessage?.id == incoming.id) {
            originMessage.mergeRealtimeUpdate(incoming)
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

internal fun KuiklyThreadScreenPayload.withContentPatchFrom(patch: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    copy(
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
        composerPayload = patch.composerPayload,
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

private fun KuiklyThreadMessageItem.mergeRealtimeUpdate(update: KuiklyThreadMessageItem): KuiklyThreadMessageItem =
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
        taskId = update.taskId ?: taskId,
        taskNumber = update.taskNumber ?: taskNumber,
        taskStatus = update.taskStatus ?: taskStatus,
        taskAssigneeId = update.taskAssigneeId ?: taskAssigneeId,
        taskAssigneeName = update.taskAssigneeName ?: taskAssigneeName,
        reactions = update.reactions.ifEmpty { reactions },
        attachments = update.attachments.ifEmpty { attachments }
    )

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
    val parentId = parentMessage?.id?.value
    val cachedMessages =
        messages
            .filterNot { message -> parentId != null && message.id.value == parentId }
            .map { message -> message.toKuiklyThreadMessageItem(currentUserId) }
    return base.copy(
        title = conversation.name.takeIf { it.isNotBlank() } ?: base.title,
        subtitle = conversation.subtitle ?: base.subtitle,
        status = if (cachedMessages.isEmpty() && parentMessage == null && tasks.isEmpty()) "empty" else "content",
        sourceThreadId =
            base.sourceThreadId
                ?: conversation.thread?.streamChannelId?.value
                ?: conversation.channelId?.value
                ?: conversation.threadId.value,
        messages = cachedMessages,
        originMessage = parentMessage?.toKuiklyThreadMessageItem(currentUserId),
        originParentMessageId = parentMessage?.id?.value ?: base.originParentMessageId,
        originStatus = if (parentMessage != null) "loaded" else base.originStatus,
        tasks = tasks.map { task -> task.toKuiklyThreadTaskItem() },
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        joined = conversation.joined,
        canSend = conversation.permissions.canSend,
        membersCount = conversation.memberCount ?: base.membersCount,
        errorCode = null
    )
}

private fun KuiklyThreadScreenPayload.toThreadSnapshot(): ThreadSnapshot? {
    if (!hasRenderableThreadContent()) return null
    val threadIdValue = cacheThreadId() ?: return null
    val threadId = ThreadId(threadIdValue)
    val channelId = ChannelId(threadIdValue)
    val conversation =
        ConversationSummary(
            threadId = threadId,
            channelId = channelId,
            kind = if (originParentMessageId.isNullOrBlank()) ConversationKind.Channel else ConversationKind.Thread,
            name = title,
            subtitle = subtitle,
            joined = joined,
            memberCount = membersCount.takeIf { it > 0 },
            permissions = ConversationPermissions(canSend = canSend)
        )
    val parent = originMessage?.toMessage(threadId = threadId, channelId = channelId)
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
        deliveryState = MessageDeliveryState.Sent,
        task = taskSummary,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.let(::MessageId)
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
        thumbnailUrl = thumbnailUrl
    )

private fun Message.toKuiklyThreadMessageItem(currentUserId: String?): KuiklyThreadMessageItem =
    KuiklyThreadMessageItem(
        id = id.value,
        authorId = author.id.value,
        authorName = author.displayName,
        authorKind = if (author.kind == MemberKind.Agent) "agent" else "human",
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
        taskId = task?.id?.value,
        taskNumber = task?.taskNumber,
        taskStatus = task?.status?.name,
        taskAssigneeId = task?.assignee?.id?.value,
        taskAssigneeName = task?.assignee?.displayName,
        attachments = attachments.map { attachment ->
            KuiklyThreadAttachmentItem(
                id = attachment.id.value,
                name = attachment.name,
                mimeType = attachment.mimeType,
                sizeLabel = attachment.sizeLabel,
                thumbnailUrl = attachment.thumbnailUrl
            )
        }
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
        assigneeId = assignee?.id?.value,
        assigneeName = assignee?.displayName,
        updatedAtLabel = updatedAtLabel
    )
