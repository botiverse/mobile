package build.raft.app

import build.raft.app.data.AttachmentOpenTarget
import build.raft.app.data.AttachmentUploadRequest
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.ChannelFilesSnapshot
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SendMessageResult
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadListSnapshot
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred

internal class ReplyThreadAccessConversationRepositoryRecorder(
    var replyResult: DataResult<ThreadSnapshot>,
    var releaseReply: CompletableDeferred<Unit>? = null,
    var parentResult: DataResult<ThreadSnapshot> =
        DataResult.Error(ScreenError(code = "reply_access_parent_context_unavailable", retryable = false)),
    var releaseParent: CompletableDeferred<Unit>? = null,
    var threadResult: DataResult<ThreadSnapshot> =
        DataResult.Error(ScreenError(code = "reply_access_thread_unavailable", retryable = false)),
    var releaseThread: CompletableDeferred<Unit>? = null,
) : ConversationRepository {
    val replyStarted = CompletableDeferred<Unit>()
    val parentStarted = CompletableDeferred<Unit>()
    val threadStarted = CompletableDeferred<Unit>()
    val replyRequests = mutableListOf<Pair<ChannelId, MessageId>>()
    val parentRequests = mutableListOf<Pair<MessageId, ChannelId?>>()
    val replyPriorities = mutableListOf<DataRequestPriority>()

    override suspend fun getReplyThread(
        parentChannelId: ChannelId,
        parentMessageId: MessageId,
        includeFollowedSummaries: Boolean,
        limit: Int
    ): DataResult<ThreadSnapshot> {
        replyRequests += parentChannelId to parentMessageId
        replyStarted.complete(Unit)
        releaseReply?.await()
        return replyResult
    }

    override suspend fun getReplyThread(
        parentChannelId: ChannelId,
        parentMessageId: MessageId,
        includeFollowedSummaries: Boolean,
        limit: Int,
        priority: DataRequestPriority
    ): DataResult<ThreadSnapshot> {
        replyPriorities += priority
        return getReplyThread(parentChannelId, parentMessageId, includeFollowedSummaries, limit)
    }

    override suspend fun getMessageContext(
        messageId: MessageId,
        channelId: ChannelId?
    ): DataResult<ThreadSnapshot> {
        parentRequests += messageId to channelId
        parentStarted.complete(Unit)
        releaseParent?.await()
        return parentResult
    }

    override suspend fun getThread(
        threadId: ThreadId,
        includeFollowedSummaries: Boolean,
        limit: Int
    ): DataResult<ThreadSnapshot> {
        threadStarted.complete(Unit)
        releaseThread?.await()
        return threadResult
    }

    override suspend fun loadOlderMessages(
        threadId: ThreadId,
        beforeSeq: Long,
        limit: Int
    ): DataResult<ThreadSnapshot> = error("unused")

    override suspend fun loadNewerMessages(
        threadId: ThreadId,
        afterSeq: Long,
        limit: Int
    ): DataResult<ThreadSnapshot> = error("unused")

    override suspend fun syncNewerMessages(
        threadId: ThreadId,
        sinceSeq: Long,
        limit: Int
    ): DataResult<ThreadSnapshot> = error("unused")

    override suspend fun getChannelThreads(
        channelId: ChannelId,
        limit: Int
    ): DataResult<ThreadListSnapshot> = error("unused")

    override suspend fun getFollowedThreads(
        serverId: ServerId,
        limit: Int
    ): DataResult<ThreadListSnapshot> = error("unused")

    override suspend fun createOrOpenThread(
        channelId: ChannelId,
        parentMessageId: MessageId
    ): DataResult<ThreadActionResult> = error("unused")

    override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> = error("unused")

    override suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> = error("unused")

    override suspend fun markThreadDone(threadChannelId: ChannelId): DataResult<ThreadActionResult> = error("unused")

    override suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult> = error("unused")

    override suspend fun uploadAttachments(
        request: AttachmentUploadRequest
    ): DataResult<AttachmentUploadResult> = error("unused")

    override suspend fun getAttachmentOpenTarget(
        attachmentId: AttachmentId
    ): DataResult<AttachmentOpenTarget> = error("unused")

    override suspend fun getChannelFiles(
        channelId: ChannelId,
        limit: Int,
        cursor: String?
    ): DataResult<ChannelFilesSnapshot> = error("unused")

    override suspend fun sendMessage(
        channelId: ChannelId,
        body: String,
        attachmentIds: List<AttachmentId>,
        asTask: Boolean,
        randomId: String?
    ): DataResult<SendMessageResult> = error("unused")
}

internal fun unresolvedReplyThreadAccessPayload(
    parentMessageId: String = "m1"
): KuiklyThreadScreenPayload =
    KuiklyThreadScreenPayload(
        instanceId = "reply-access-$parentMessageId",
        title = ThreadReplyPageTitle,
        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
        sourceThreadId = null,
        streamChannelId = null,
        parentChannelId = "channel-1",
        parentMessageId = parentMessageId,
        originParentMessageId = parentMessageId,
        parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
        parentChannelKind = "channel",
        status = "loading",
        showModeTabs = false,
        showReplyActions = false,
        canSend = true
    )

internal fun replyThreadAccessIdentityAuthority(
    sessionStore: SlockAuthSessionStore,
    environmentId: String,
): suspend () -> ThreadReplyCacheIdentitySnapshot? = {
    sessionStore.readSession()?.let { session ->
        ThreadReplyCacheIdentitySnapshot(
            session = session,
            environmentId = environmentId,
            principalId = session.user.id,
            serverId = session.currentServerId.orEmpty(),
            authGeneration = 0L,
            selectionGeneration = 0L,
        )
    }
}

internal fun replyThreadAccessSnapshot(
    body: String = "reply body"
): ThreadSnapshot {
    val parentChannelId = ChannelId("channel-1")
    val parentMessageId = MessageId("m1")
    val streamChannelId = ChannelId("thread-m1")
    fun message(id: MessageId, threadId: ThreadId, channelId: ChannelId, seq: Long, text: String): Message =
        Message(
            id = id,
            threadId = threadId,
            channelId = channelId,
            author =
                MemberProfile(
                    id = MemberId("u1"),
                    displayName = "Artin",
                    handle = "artin",
                    kind = MemberKind.Human,
                    presence = Presence.Active
                ),
            seq = seq,
            timestampLabel = "2026-07-24T12:00:00Z",
            body = text
        )
    val parent = message(parentMessageId, ThreadId(parentChannelId.value), parentChannelId, 1L, "parent body")
    val reply = message(MessageId("reply-1"), ThreadId(streamChannelId.value), streamChannelId, 2L, body)
    return ThreadSnapshot(
        conversation =
            ConversationSummary(
                threadId = ThreadId(streamChannelId.value),
                channelId = streamChannelId,
                kind = ConversationKind.Thread,
                name = "general",
                thread =
                    ThreadConversation(
                        parentChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        streamChannelId = streamChannelId,
                        parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                        parentChannelKind = "channel"
                    )
            ),
        messages = listOf(reply),
        parentMessage = parent
    )
}
