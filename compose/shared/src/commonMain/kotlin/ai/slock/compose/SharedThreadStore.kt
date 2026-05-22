package ai.slock.compose

import ai.slock.compose.data.DataResult
import ai.slock.compose.data.HomeNetworkResponse
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.SharedThreadNetworkApi
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.ThreadSnapshot
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
import ai.slock.compose.model.Presence
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ThreadId
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class SharedThreadDataSource(
    private val api: SharedThreadNetworkApi,
    private val sessionStore: SlockAuthSessionStore,
    private val localWriteThrough: ComposeLocalWriteThrough? = null
) {
    private val payloadCache = mutableMapOf<String, KuiklyThreadScreenPayload>()

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
        return snapshot.toKuiklyThreadPayload(basePayload, session?.user?.id)
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

    suspend fun fetchThreadPayload(basePayload: KuiklyThreadScreenPayload): DataResult<KuiklyThreadScreenPayload> {
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val session =
            readSession()
                ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        val response =
            try {
                api.channelMessages(threadId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_network", retryable = true))
            }
        if (!response.isSuccessful || response.bodyText.isNullOrBlank()) {
            return DataResult.Error(
                ScreenError(
                    code = response.errorBodyText?.takeIf { it.isNotBlank() } ?: "thread_network",
                    retryable = true
                )
            )
        }
        val payload =
            buildSharedThreadPayloadFromMessagesResponse(
                basePayload = basePayload,
                bodyText = response.bodyText,
                session = session
            )
        cachePayload(payload)
        writeCachedPayload(payload)
        return DataResult.Content(payload)
    }

    suspend fun sendMessage(channelId: String, body: String, asTask: Boolean): HomeNetworkResponse =
        api.sendMessage(channelId = channelId, body = body, asTask = asTask)

    suspend fun attachmentUrl(attachmentId: String): HomeNetworkResponse =
        api.attachmentUrl(attachmentId)
}

internal data class SharedThreadState(
    val payload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    val highlightedMentions: Set<String> = emptySet()
)

internal sealed interface SharedThreadIntent {
    data class Start(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data object Refresh : SharedThreadIntent
    data class SetPayload(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
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
            is SharedThreadIntent.SetPayload -> {
                _state.value = _state.value.copy(payload = intent.payload)
                dataSource.cachePayload(intent.payload)
                scope.launch {
                    dataSource.writeCachedPayload(intent.payload)
                }
            }
        }
    }

    suspend fun accept(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start -> start(intent.payload)
            SharedThreadIntent.Refresh -> refresh(_state.value.payload)
            is SharedThreadIntent.SetPayload -> {
                _state.value = _state.value.copy(payload = intent.payload)
                dataSource.cachePayload(intent.payload)
                dataSource.writeCachedPayload(intent.payload)
            }
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
    }

    private suspend fun start(initialPayload: KuiklyThreadScreenPayload) {
        dataSource.cachePayload(initialPayload)
        val session = dataSource.readSession()
        val threadId = initialPayload.sourceThreadId?.takeIf { it.isNotBlank() }
        val cached = threadId?.let(dataSource::cachedPayload)?.withRouteContext(initialPayload)
        val current = _state.value.payload.takeIf { it.sourceThreadId == threadId && it.hasRenderableThreadContent() }
        val persistentCached =
            if (cached == null && current == null) {
                dataSource.readCachedPayload(initialPayload)
            } else {
                null
            }
        val visiblePayload =
            cached
                ?: current
                ?: persistentCached
                ?: if (threadId == null) {
                    initialPayload
                } else {
                    initialPayload.copy(status = "loading", errorCode = null)
                }
        _state.value =
            SharedThreadState(
                payload = visiblePayload,
                highlightedMentions = session.threadSelfMentionNames()
            )
        if (threadId != null) {
            refresh(initialPayload)
        }
    }

    private suspend fun refresh(basePayload: KuiklyThreadScreenPayload) {
        val existing = _state.value.payload
        val loadingBase =
            if (existing.hasRenderableThreadContent()) {
                existing.withRouteContext(basePayload)
            } else {
                basePayload.copy(status = "loading", errorCode = null)
            }
        _state.value = _state.value.copy(payload = loadingBase)
        val result = dataSource.fetchThreadPayload(basePayload)
        _state.value =
            when (result) {
                is DataResult.Content ->
                    _state.value.copy(payload = result.data.withRouteContext(basePayload))
                is DataResult.Empty ->
                    _state.value.copy(payload = _state.value.payload.asRefreshFailed("empty_thread"))
                is DataResult.Error ->
                    _state.value.copy(payload = _state.value.payload.asRefreshFailed(result.error.code))
                is DataResult.NoPermission ->
                    _state.value.copy(payload = _state.value.payload.asRefreshFailed(result.blocker.code))
            }
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

private fun KuiklyThreadScreenPayload.hasRenderableThreadContent(): Boolean =
    messages.isNotEmpty() || originMessage != null || tasks.isNotEmpty()

private fun KuiklyThreadScreenPayload.cacheThreadId(): String? =
    sourceThreadId?.takeIf { it.isNotBlank() }

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
                assignee = taskAssigneeName?.takeIf { it.isNotBlank() }?.let { name ->
                    MemberProfile(
                        id = MemberId(name),
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
        author = toMemberProfile(),
        timestampLabel = timestampLabel,
        body = body,
        messageType = if (messageType.equals("System", ignoreCase = true)) MessageType.System else MessageType.Chat,
        systemEventType = systemEventType,
        metadata = metadata,
        attachments = attachments.map { attachment -> attachment.toAttachment() },
        deliveryState = MessageDeliveryState.Sent,
        task = taskSummary,
        replyCount = replyCount
    )
}

private fun KuiklyThreadMessageItem.toMemberProfile(): MemberProfile =
    MemberProfile(
        id = MemberId(authorId),
        displayName = authorName,
        handle = authorName.removePrefix("@"),
        kind = MemberKind.Human,
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
        isOwn = currentUserId != null && author.id.value == currentUserId,
        avatarLabel = author.avatarLabel,
        avatarWireValue = author.avatar?.wireValue ?: author.avatarUrl,
        gravatarHash = author.gravatarHash,
        timestampLabel = timestampLabel,
        messageType = if (messageType == MessageType.System) "System" else "Chat",
        systemEventType = systemEventType,
        metadata = metadata,
        body = body,
        replyCount = replyCount,
        taskId = task?.id?.value,
        taskNumber = task?.taskNumber,
        taskStatus = task?.status?.name,
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
): TaskSummary =
    TaskSummary(
        id = TaskId(id),
        title = title,
        status = TaskStatus.fromWireValue(status) ?: TaskStatus.Todo,
        taskNumber = taskNumber,
        channelId = channelId,
        threadId = threadId,
        assignee = assigneeName?.takeIf { it.isNotBlank() }?.let { name ->
            MemberProfile(
                id = MemberId(name),
                displayName = name,
                handle = name.removePrefix("@"),
                kind = MemberKind.Human
            )
        },
        updatedAtLabel = updatedAtLabel
    )

private fun TaskSummary.toKuiklyThreadTaskItem(): KuiklyThreadTaskItem =
    KuiklyThreadTaskItem(
        id = id.value,
        title = title,
        status = status.name,
        taskNumber = taskNumber,
        assigneeName = assignee?.displayName,
        updatedAtLabel = updatedAtLabel
    )
