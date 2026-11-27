package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.ConversationRepository
import build.raft.app.data.ConversationDetailResourceSpec
import build.raft.app.data.InMemoryRaftOfflineResourceRows
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftOfflineResourceCache
import build.raft.app.data.StoredConversationDetailOwner
import build.raft.app.data.StoredConversationDetails
import build.raft.app.data.SharedMessagesPayload
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.RaftThreadApi
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadReplyCacheIdentityTransitionAnchor
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.toHomeChannelRow
import build.raft.app.members.resource
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadMessageStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SharedThreadReplyCacheProvenanceTest {
    @Test
    fun staleVisibleParentFromAccountADoesNotSeedAccountBOnSameServer() = runBlocking {
        val authStore = InMemoryRaftAuthSessionStore(session("account-a"))
        val messageStore = SharedThreadMessageStore()
        val dataSource = dataSource(authStore, messageStore)
        val accountA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        authStore.saveSession(session("account-b"))
        val accountB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val accepted =
            dataSource.rememberReplyParentCandidate(
                provenance = accountA,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1"
            )

        assertFalse(accepted)
        assertNotEquals(accountA, accountB)
        assertNull(
            messageStore.getScoped(
                requireNotNull(accountB.messageStoreScopeId),
                ChannelId("channel-1"),
                MessageId(ParentMessageId)
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                requireNotNull(accountB.namespaceId),
                "channel-1",
                ParentMessageId
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                requireNotNull(accountA.namespaceId),
                "channel-1",
                ParentMessageId
            )
        )
    }

    // Offline-first header: the stored detail is fenced from when its request STARTED, so an answer in
    // flight across a lost server (wipeServer) cannot store that server's detail again.
    @Test
    fun canonicalDetailInFlightAcrossALostServerIsNotStored() = runBlocking {
        val details = storedDetails(this)
        val release = CompletableDeferred<Unit>()
        val repository =
            canonicalConversationRepository(directMessageDetail("Cindy", "pixel:cindy"))
                .also { it.releaseThread = release }
        val dataSource =
            dataSource(
                authStore = InMemoryRaftAuthSessionStore(session("account-a")),
                messageStore = SharedThreadMessageStore(),
                conversationRepository = repository,
                canonicalConversationIdentityPublisher = { _, _ -> true },
                storedConversationDetails = details.first,
            )

        val request = async { dataSource.fetchThreadPayload(directMessagePayload(), persistResult = false) }
        repository.threadStarted.await()
        details.second.wipeServer("server-1", accountId = "account-a")
        release.complete(Unit)
        request.await()

        assertNull(details.first.read(detailOwner("account-a"), "dm-1"))

        // Positive control: a request that starts after the wipe is stored.
        dataSource.fetchThreadPayload(directMessagePayload(), persistResult = false)
        assertEquals("Cindy", details.first.read(detailOwner("account-a"), "dm-1")?.name)
    }

    // A conversation that is gone (404 / lost permission) loses its stored header detail; a transient
    // failure keeps it.
    @Test
    fun goneConversationDropsItsStoredHeaderDetailAndTransientFailureKeepsIt() = runBlocking {
        val details = storedDetails(this)
        val owner = detailOwner("account-a")
        val repository = canonicalConversationRepository(directMessageDetail("Cindy", "pixel:cindy"))
        val dataSource =
            dataSource(
                authStore = InMemoryRaftAuthSessionStore(session("account-a")),
                messageStore = SharedThreadMessageStore(),
                conversationRepository = repository,
                canonicalConversationIdentityPublisher = { _, _ -> true },
                storedConversationDetails = details.first,
            )
        details.first.record(owner, "dm-1", directMessageDetail("Cindy", "pixel:cindy"))

        repository.threadResult = DataResult.Error(ScreenError("network_request_failed", retryable = true))
        dataSource.fetchThreadPayload(directMessagePayload(), persistResult = false)
        assertEquals("Cindy", details.first.read(owner, "dm-1")?.name)

        repository.threadResult = DataResult.Error(ScreenError("http_404", retryable = false))
        dataSource.fetchThreadPayload(directMessagePayload(), persistResult = false)
        assertNull(details.first.read(owner, "dm-1"))

        details.first.record(owner, "dm-1", directMessageDetail("Cindy", "pixel:cindy"))
        repository.threadResult =
            DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        dataSource.fetchThreadPayload(directMessagePayload(), persistResult = false)
        assertNull(details.first.read(owner, "dm-1"))
    }

    private fun storedDetails(scope: CoroutineScope): Pair<StoredConversationDetails, RaftOfflineResourceCache> {
        val cache =
            RaftOfflineResourceCache(
                InMemoryRaftOfflineResourceRows(),
                environmentId = { "https://prod.example.test" },
                scope = scope,
            )
        return StoredConversationDetails(cache.resource(ConversationDetailResourceSpec)) to cache
    }

    private fun detailOwner(accountId: String): StoredConversationDetailOwner =
        StoredConversationDetailOwner("https://prod.example.test", accountId, "server-1")

    private fun dataSource(
        authStore: InMemoryRaftAuthSessionStore,
        messageStore: SharedThreadMessageStore,
        environment: () -> String = { "https://prod.example.test" },
        authority: (suspend () -> ThreadReplyCacheIdentitySnapshot?)? = null,
        transitionWaiter:
            (suspend (ThreadReplyCacheIdentityTransitionAnchor?) -> Boolean)? = null,
        conversationRepository: ConversationRepository? = null,
        canonicalConversationIdentityPublisher:
            suspend (ReplyCacheProvenance, ConversationSummary) -> Boolean = { _, _ -> false },
        storedConversationDetails: StoredConversationDetails? = null,
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            api = UnusedThreadApi,
            sessionStore = authStore,
            messageStore = messageStore,
            conversationRepository = conversationRepository,
            cacheEnvironmentIdProvider = environment,
            replyCacheIdentityAuthority = authority ?: {
                authStore.readSession()?.let { session ->
                    ThreadReplyCacheIdentitySnapshot(
                        session = session,
                        environmentId = environment().trim().trimEnd('/'),
                        principalId = session.user.id.trim(),
                        serverId = session.currentServerId.orEmpty(),
                        // A live session always carries a registry generation >= 1; 0 means "no session".
                        authGeneration = 1L,
                        selectionGeneration = 0L,
                    )
                }
            },
            replyCacheIdentityTransitionWaiter = transitionWaiter,
            canonicalConversationIdentityPublisher = canonicalConversationIdentityPublisher,
            storedConversationDetails = storedConversationDetails,
            homeSnapshotProvider = { homeSnapshot() }
        )

    private fun canonicalConversationRepository(
        detail: ConversationSummary,
    ): ReplyThreadAccessConversationRepositoryRecorder =
        ReplyThreadAccessConversationRepositoryRecorder(
            replyResult = DataResult.Error(ScreenError("unused", retryable = false)),
            threadResult =
                DataResult.Content(
                    ThreadSnapshot(
                        conversation = detail,
                        canonicalConversationDetail = detail,
                        messages = emptyList(),
                    )
                ),
        )

    private fun directMessageDetail(name: String, avatar: String): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            kind = ConversationKind.DirectMessage,
            name = name,
            directMessagePeerType = "human",
            directMessagePeerAvatarWireValue = avatar,
            presenceMemberId = MemberId("peer-1"),
        )

    private fun directMessagePayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            status = "loading",
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            channelKind = RaftThreadSurfaceKind.DirectMessage,
            sourceThreadId = "dm-1",
            streamChannelId = "dm-1",
        )

    private fun parentMessage(): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = ParentMessageId,
            authorId = "member-1",
            authorName = "Member",
            authorKind = "human",
            seq = 1,
            body = "parent body",
            threadChannelId = "thread-parent-1",
            replyCount = 1
        )

    private fun homeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Server"),
            currentMember =
                MemberProfile(
                    id = MemberId("member-1"),
                    displayName = "Member",
                    handle = "member",
                    kind = MemberKind.Human
                ),
            channels =
                listOf(
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun session(accountId: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = accountId, email = "$accountId@example.test", displayName = accountId),
            accessToken = "access-$accountId",
            refreshToken = "refresh-$accountId",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Server", slug = "server"))
        )

    private companion object {
        const val ParentMessageId = "5fbf9608-1111-2222-3333-444444444444"

        val UnusedThreadApi =
            object : RaftThreadApi {
                private fun <T> unused(): ThreadApiResult<T> = ThreadApiResult.Failure(500)

                override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?, afterSeq: Long?): ThreadApiResult<ThreadMessagesPage> = unused()
                override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): ThreadApiResult<ThreadMessagesPage> = unused()
                override suspend fun sendMessage(channelId: String, body: String, asTask: Boolean, attachmentIds: List<String>, randomId: String?): ThreadApiResult<ThreadMessagesPage> = unused()
                override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): ThreadApiResult<List<PendingMentionActionExecuteResult>> = unused()
                override suspend fun attachmentUrl(priority: DataRequestPriority, attachmentId: String, disposition: String?): ThreadApiResult<ThreadAttachmentOpenTarget> = unused()
                override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> = unused()
                override suspend fun attachmentComments(attachmentId: String): ThreadApiResult<ThreadAttachmentCommentsPage> = unused()
                override suspend fun sendAttachmentComment(attachmentId: String, body: String, anchor: build.raft.app.data.ThreadAttachmentCommentAnchor?): ThreadApiResult<Unit> = unused()
                override suspend fun markChannelReadToSeq(channelId: String, seq: Long): ThreadApiResult<Unit> = unused()
                override suspend fun markChannelReadAll(channelId: String): ThreadApiResult<Unit> = unused()
                override suspend fun saveMessage(messageId: String): ThreadApiResult<Unit> = unused()
                override suspend fun unsaveMessage(messageId: String): ThreadApiResult<Unit> = unused()
                override suspend fun addReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> = unused()
                override suspend fun removeReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> = unused()
                override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): ThreadApiResult<ThreadActionResult> = unused()
                override suspend fun messageContext(messageId: String, channelId: String?): ThreadApiResult<SharedMessagesPayload> = unused()
            }
    }
}
