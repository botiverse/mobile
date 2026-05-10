package ai.slock.android.core.data.local

import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.explicitMessageStreamChannelId
import ai.slock.android.core.model.messageStreamChannelId
import ai.slock.android.core.model.withTitleFallback
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.thread.ComposerState
import ai.slock.android.feature.thread.ComposerStatus
import ai.slock.android.feature.thread.ThreadContent
import ai.slock.android.feature.thread.ThreadUiState

class SlockLocalWriteThrough(
    private val store: SlockLocalStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = {}
) {
    fun readThreadTimeline(threadId: ThreadId): LocalThreadTimeline? =
        store.getThreadTimeline(threadId).also { timeline ->
            logger(
                "read_thread threadId=${threadId.value} hit=${timeline != null} messages=${timeline?.messages?.size ?: 0} tasks=${timeline?.tasks?.size ?: 0}"
            )
        }

    fun readThreadUiState(
        threadId: ThreadId,
        fallbackHeader: ConversationSummary? = null,
        parentMessage: Message? = null
    ): ThreadUiState? =
        readThreadTimeline(threadId)?.toThreadUiState(
            fallbackHeader = fallbackHeader,
            parentMessage = parentMessage
        )

    fun writeThreadResult(
        result: DataResult<ThreadSnapshot>,
        source: String,
        fallbackThreadId: ThreadId? = null,
        fallbackHeader: ConversationSummary? = null
    ) {
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger("write_thread_skip source=$source reason=${result.localWriteSkipReason()} fallbackThreadId=${fallbackThreadId?.value.orEmpty()}")
            return
        }
        writeThreadSnapshot(snapshot, source, fallbackHeader)
    }

    fun writeSendResult(result: DataResult<SendMessageResult>, source: String) {
        val message = (result as? DataResult.Content)?.data?.message
        if (message == null) {
            logger("write_send_skip source=$source reason=${result.localWriteSkipReason()}")
            return
        }
        writeMessages(listOf(message), source)
    }

    fun writeRealtimeEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.MessageNew -> writeMessages(listOf(event.message), "socket_message_new")
            is RealtimeEvent.MessageUpdated ->
                writeMessages(listOf(event.message), "socket_message_updated")
            is RealtimeEvent.SyncResumeResponse ->
                writeMessages(event.messages, "socket_sync_resume")
            is RealtimeEvent.TaskCreated -> writeTasks(event.tasks, "socket_task_created")
            is RealtimeEvent.TaskUpdated -> writeTasks(listOf(event.task), "socket_task_updated")
            else -> Unit
        }
    }

    private fun writeThreadSnapshot(
        snapshot: ThreadSnapshot,
        source: String,
        fallbackHeader: ConversationSummary? = null
    ) {
        val conversation = snapshot.conversation.withTitleFallback(fallbackHeader)
        requireValidSnapshotId(conversation.threadId.value, "conversation.threadId", source)
        requireValidSnapshotId(conversation.channelId?.value, "conversation.channelId", source)
        val syncedAtMs = clock()
        val streamChannelId = conversation.explicitMessageStreamChannelId()
        store.upsertConversation(conversation, syncedAtMs)
        val persistedMessages = mutableListOf<Message>()
        snapshot.parentMessage?.let { parentMessage ->
            if (writeMessage(parentMessage, syncedAtMs, source, parentMessage.channelId ?: streamChannelId)) {
                persistedMessages += parentMessage
            }
        }
        snapshot.messages.forEach { message ->
            if (writeMessage(message, syncedAtMs, source, message.channelId ?: streamChannelId)) {
                persistedMessages += message
            }
        }
        snapshot.tasks.forEach { store.upsertTaskReference(it, syncedAtMs) }
        val timelineSeqs = persistedMessages.mapNotNull(Message::seq)
        store.upsertSyncCursor(
            LocalSyncCursor(
                scopeId = requireNotNull(streamChannelId) {
                    "Missing local thread stream channel id for ${conversation.threadId.value}"
                }.value,
                scopeKind = LocalSyncScopeKind.Thread,
                latestSeq = snapshot.lastSeq.takeIf { it > 0L },
                oldestLoadedSeq = timelineSeqs.minOrNull(),
                newestLoadedSeq = timelineSeqs.maxOrNull(),
                hasLoadedOldest = !snapshot.canLoadMoreBefore,
                hasLoadedNewest = !snapshot.canLoadMoreAfter,
                updatedAtMs = syncedAtMs
            )
        )
        logger(
            "write_thread source=$source threadId=${conversation.threadId.value} streamChannelId=${streamChannelId.value} messages=${snapshot.messages.size} parent=${snapshot.parentMessage != null} tasks=${snapshot.tasks.size} lastSeq=${snapshot.lastSeq}"
        )
    }

    private fun writeMessages(messages: List<Message>, source: String) {
        if (messages.isEmpty()) return
        val syncedAtMs = clock()
        messages.forEach { message ->
            writeMessage(message, syncedAtMs, source, message.channelId)
        }
        messages.mapNotNull { message -> message.channelId?.let { it to message } }
            .groupBy({ it.first }, { it.second })
            .forEach { (channelId, channelMessages) ->
                val seqs = channelMessages.mapNotNull(Message::seq)
                store.upsertSyncCursor(
                    LocalSyncCursor(
                        scopeId = channelId.value,
                        scopeKind = LocalSyncScopeKind.Thread,
                        latestSeq = seqs.maxOrNull(),
                        newestLoadedSeq = seqs.maxOrNull(),
                        updatedAtMs = syncedAtMs
                    )
                )
            }
        logger(
            "write_messages source=$source count=${messages.size} channels=${messages.mapNotNull { it.channelId?.value }.distinct().joinToString()}"
        )
    }

    private fun writeTasks(tasks: List<TaskSummary>, source: String) {
        if (tasks.isEmpty()) return
        val syncedAtMs = clock()
        tasks.forEach { task -> store.upsertTaskReference(task, syncedAtMs) }
        logger("write_tasks source=$source count=${tasks.size}")
    }

    private fun writeMessage(
        message: Message,
        syncedAtMs: Long,
        source: String,
        streamChannelId: ChannelId?
    ): Boolean {
        if (streamChannelId == null) {
            logger(
                "write_message_skip source=$source reason=missing_stream_channel_id messageId=${message.id.value} threadId=${message.threadId.value}"
            )
            return false
        }
        store.upsertMessage(message, syncedAtMs, streamChannelId)
        return true
    }
}

private fun requireValidSnapshotId(value: String?, fieldName: String, source: String) {
    require(!value.isNullOrBlank() && value != "unknown-channel") {
        "Invalid local write-through id source=$source field=$fieldName value=${value.orEmpty()}"
    }
}

fun LocalThreadTimeline.toThreadUiState(
    fallbackHeader: ConversationSummary? = null,
    parentMessage: Message? = null
): ThreadUiState {
    val header =
        conversation.copy(
            name = conversation.name.takeIf { it.isNotBlank() } ?: fallbackHeader?.name.orEmpty(),
            channelId = conversation.channelId ?: fallbackHeader?.channelId,
            avatarUrl = conversation.avatarUrl ?: fallbackHeader?.avatarUrl
        )
    return ThreadUiState(
        loadState =
            ScreenLoadState.Content(
                ThreadContent(
                    header = header,
                    messages = messages,
                    tasks = tasks,
                    canLoadMoreBefore = cursor?.hasLoadedOldest == false,
                    canLoadMoreAfter = cursor?.hasLoadedNewest == false,
                    parentMessage = parentMessage
                )
            ),
        composerState = header.toLocalComposerState()
    )
}

private fun ConversationSummary.toLocalComposerState(): ComposerState =
    ComposerState(
        status =
            if (permissions.canSend) {
                ComposerStatus.IdleEmpty
            } else {
                ComposerStatus.NoPermission
            },
        enabled = permissions.canSend,
        noPermissionCode = if (permissions.canSend) null else "cannot_send"
    )

private fun DataResult<*>.localWriteSkipReason(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }
