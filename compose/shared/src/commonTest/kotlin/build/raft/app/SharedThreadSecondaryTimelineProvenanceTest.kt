package build.raft.app

import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.SavedMessageStatusSnapshot
import build.raft.app.data.SavedStatusRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesSyncHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SharedThreadSecondaryTimelineProvenanceTest {
    @Test
    fun blockedAccountASummaryCannotPublishOrCacheAfterSameServerSwitchToAccountB() = runBlocking {
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(coroutineContext + storeJob)
        val accountASession = session("account-a")
        val authStore = InMemorySlockAuthSessionStore(accountASession)
        val summaryStarted = CompletableDeferred<Unit>()
        val releaseSummary = CompletableDeferred<Unit>()
        val repository =
            BlockingSecondaryTimelineRepository(
                delegate =
                    ReplyThreadAccessConversationRepositoryRecorder(
                        replyResult = DataResult.Error(ScreenError("unused", retryable = false)),
                        threadResult = DataResult.Content(initialSnapshot()),
                    ),
                summaryStarted = summaryStarted,
                releaseSummary = releaseSummary,
            )
        val savedRepository = RecordingSavedStatusRepository()
        val payloadStore = SharedThreadPayloadStore(scope = storeScope)
        var accountId = "account-a"
        var authGeneration = 1L
        val dataSource =
            SharedThreadDataSource(
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = authStore,
                conversationRepository = repository,
                payloadStore = payloadStore,
                savedStatusRepository = savedRepository,
                replyCacheIdentityAuthority = {
                    ThreadReplyCacheIdentitySnapshot(
                        session = session(accountId),
                        environmentId = "https://prod.example.test",
                        principalId = accountId,
                        serverId = ServerIdValue,
                        authGeneration = authGeneration,
                        selectionGeneration = 1L,
                    )
                },
            )
        val aborted = mutableListOf<Pair<ThreadRepliesPageIdentity, Long>>()
        var expectedLateAbort: CompletableDeferred<Unit>? = null
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = storeScope,
                threadRepliesSyncHost =
                    ThreadRepliesSyncHost(
                        onAuthoritativeRebaselineAborted = { page, generation ->
                            aborted += page to generation
                            expectedLateAbort?.complete(Unit)
                        },
                    ),
            )

        try {
            store.accept(SharedThreadIntent.Start(routePayload()))
            val beforeSummary = store.state.value.payload
            assertEquals(0, beforeSummary.messages.single().replyCount)
            assertFalse(beforeSummary.messages.single().isThreadFollowed)
            assertFalse(beforeSummary.messages.single().isSaved)
            val abortCountBeforeSecondaryRefresh = aborted.size

            store.accept(SharedThreadIntent.FirstContentReady)
            summaryStarted.await()

            accountId = "account-b"
            authGeneration = 2L
            val accountB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
            assertEquals("account-b", accountB.principalId)

            val lateAbort = CompletableDeferred<Unit>()
            expectedLateAbort = lateAbort
            releaseSummary.complete(Unit)
            lateAbort.await()

            val afterLateSummary = store.state.value.payload
            val message = afterLateSummary.messages.single()
            assertEquals(0, message.replyCount)
            assertFalse(message.isThreadFollowed)
            assertFalse(message.isSaved)
            assertEquals(emptyList(), savedRepository.requests)
            assertEquals(abortCountBeforeSecondaryRefresh + 1, aborted.size)
            assertEquals("account-a", aborted.last().first.principalId)

            assertEquals(
                0,
                payloadStore
                    .applyReplyCountFacts(ServerIdValue, ThreadIdValue, "account-a", beforeSummary)
                    .messages
                    .single()
                    .replyCount,
            )
            assertEquals(
                0,
                payloadStore
                    .applyReplyCountFacts(ServerIdValue, ThreadIdValue, "account-b", beforeSummary)
                    .messages
                    .single()
                    .replyCount,
            )
        } finally {
            store.clear()
            payloadStore.clearForTest()
            storeJob.cancel()
        }
    }

    private class BlockingSecondaryTimelineRepository(
        delegate: ConversationRepository,
        private val summaryStarted: CompletableDeferred<Unit>,
        private val releaseSummary: CompletableDeferred<Unit>,
    ) : ConversationRepository by delegate {
        override suspend fun getThreadReplySummaries(
            threadId: ThreadId,
            parentMessageIds: List<MessageId>,
        ): DataResult<ThreadReplySummariesSnapshot> {
            summaryStarted.complete(Unit)
            releaseSummary.await()
            return DataResult.Content(lateSummary())
        }
    }

    private class RecordingSavedStatusRepository : SavedStatusRepository {
        val requests = mutableListOf<Pair<ServerId, List<MessageId>>>()

        override suspend fun checkSavedMessages(
            serverId: ServerId,
            messageIds: List<MessageId>,
        ): DataResult<SavedMessageStatusSnapshot> {
            requests += serverId to messageIds
            return DataResult.Content(SavedMessageStatusSnapshot(setOf(MessageId(MessageIdValue))))
        }
    }

    private companion object {
        const val ServerIdValue = "server-1"
        const val ThreadIdValue = "channel-1"
        const val MessageIdValue = "m1"
        const val ReplyThreadIdValue = "thread-m1"

        fun session(accountId: String): StoredAuthSession =
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = accountId,
                        email = "$accountId@example.test",
                        displayName = accountId,
                    ),
                accessToken = "access-$accountId",
                refreshToken = "refresh-$accountId",
                selectedServerId = ServerIdValue,
                servers =
                    listOf(
                        StoredAuthServer(
                            id = ServerIdValue,
                            name = "Server",
                            slug = "server",
                        ),
                    ),
            )

        fun routePayload(): KuiklyThreadScreenPayload =
            KuiklyThreadScreenPayload(
                instanceId = "secondary-provenance-channel-1",
                status = "loading",
                title = "general",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = KuiklyThreadSurfaceKind.Channel,
                sourceThreadId = ThreadIdValue,
                streamChannelId = ThreadIdValue,
            )

        fun initialSnapshot(): ThreadSnapshot =
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId(ThreadIdValue),
                        channelId = ChannelId(ThreadIdValue),
                        kind = ConversationKind.Channel,
                        name = "general",
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId(MessageIdValue),
                            threadId = ThreadId(ThreadIdValue),
                            channelId = ChannelId(ThreadIdValue),
                            threadChannelId = ChannelId(ReplyThreadIdValue),
                            seq = 1L,
                            author =
                                MemberProfile(
                                    id = MemberId("member-a"),
                                    displayName = "Account A",
                                    handle = "account-a",
                                    kind = MemberKind.Human,
                                ),
                            timestampLabel = "2026-08-19T00:00:00Z",
                            body = "account A canonical message",
                            replyCount = 0,
                            isThreadFollowed = false,
                            isSaved = false,
                        ),
                    ),
            )

        fun lateSummary(): ThreadReplySummariesSnapshot =
            ThreadReplySummariesSnapshot(
                threads =
                    listOf(
                        InboxItem(
                            key = "thread:$MessageIdValue",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId(ReplyThreadIdValue),
                            channelName = "reply thread",
                            threadChannelId = ChannelId(ReplyThreadIdValue),
                            parentChannelId = ChannelId(ThreadIdValue),
                            parentMessageId = MessageId(MessageIdValue),
                            replyCount = 7,
                        ),
                    ),
                followedThreadKeys = setOf("thread:$MessageIdValue"),
            )
    }
}
