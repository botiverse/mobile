package ai.slock.compose

import ai.slock.compose.data.DataResult
import ai.slock.compose.data.HomeNetworkResponse
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.ReadReceiptResult
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
        return if (response.isSuccessful) {
            val id = ChannelId(channelId)
            localWriteThrough?.clearConversationUnread(id, "shared_thread_read_all")
            DataResult.Content(ReadReceiptResult(channelId = id, changed = true))
        } else {
            DataResult.Error(
                ScreenError(
                    code = response.errorBodyText?.takeIf { it.isNotBlank() } ?: "thread_read_receipt_network",
                    retryable = true
                )
            )
        }
    }
}

internal data class SharedThreadState(
    val payload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    val highlightedMentions: Set<String> = emptySet()
)

internal sealed interface SharedThreadIntent {
    data class Start(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data object Refresh : SharedThreadIntent
    data class SetPayload(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data class ApplyRealtimeEvent(val event: RealtimeEvent, val activeReplyParentMessageId: MessageId? = null) :
        SharedThreadIntent
    data object MarkVisibleReadAll : SharedThreadIntent
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
            is SharedThreadIntent.ApplyRealtimeEvent ->
                applyRealtimeEvent(
                    event = intent.event,
                    activeReplyParentMessageId = intent.activeReplyParentMessageId
                )
            SharedThreadIntent.MarkVisibleReadAll -> markVisibleReadAll()
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
        if (merged != existing) {
            _state.value = _state.value.copy(payload = merged)
            dataSource.cachePayload(merged)
            dataSource.writeCachedPayload(merged)
        }
    }

    private suspend fun markVisibleReadAll() {
        val channelId = _state.value.payload.cacheThreadId() ?: return
        dataSource.markChannelReadAll(channelId)
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
        messages = merged
    )
}

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
            copy(replyCount = maxOf(replyCount, event.replyCount))
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
        messageType = update.messageType.ifBlank { messageType },
        systemEventType = update.systemEventType ?: systemEventType,
        metadata = update.metadata.ifEmpty { metadata },
        body = update.body.ifBlank { body },
        replyCount = maxOf(replyCount, update.replyCount),
        taskId = update.taskId ?: taskId,
        taskNumber = update.taskNumber ?: taskNumber,
        taskStatus = update.taskStatus ?: taskStatus,
        taskAssigneeName = update.taskAssigneeName ?: taskAssigneeName,
        attachments = update.attachments.ifEmpty { attachments }
    )

private fun KuiklyThreadMessageItem.toTaskItemOrNull(): KuiklyThreadTaskItem? {
    if (taskId == null && taskNumber == null) return null
    return KuiklyThreadTaskItem(
        id = taskId ?: "task-${taskNumber ?: id}",
        title = body.ifBlank { taskNumber?.let { "Task #$it" } ?: "Task" },
        status = taskStatus ?: TaskStatus.Todo.name,
        taskNumber = taskNumber,
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
        taskAssigneeName = task.assigneeName ?: taskAssigneeName
    )
}

private fun KuiklyThreadMessageItem.withoutTask(): KuiklyThreadMessageItem =
    copy(taskId = null, taskNumber = null, taskStatus = null, taskAssigneeName = null)

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
