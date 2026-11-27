package build.raft.app

import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesSyncHost
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.SharedThreadStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedThreadSecondaryTimelineProvenanceTest {
    @Test
    fun blockedAccountARefreshCannotPublishErrorOrCacheAfterSameServerSwitchToAccountB() = runBlocking {
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(coroutineContext + storeJob)
        val accountASession = session("account-a")
        val authStore = InMemoryRaftAuthSessionStore(accountASession)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val repository =
            BlockingRefreshRepository(
                delegate =
                    ReplyThreadAccessConversationRepositoryRecorder(
                        replyResult = DataResult.Error(ScreenError("unused", retryable = false)),
                        threadResult = DataResult.Content(initialSnapshot()),
                    ),
                refreshStarted = refreshStarted,
                releaseRefresh = releaseRefresh,
            )
        val payloadStore = SharedThreadPayloadStore(scope = storeScope)
        var accountId = "account-a"
        var authGeneration = 1L
        val dataSource =
            SharedThreadDataSource(
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = authStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                payloadStore = payloadStore,
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
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = storeScope,
                threadRepliesSyncHost =
                    ThreadRepliesSyncHost(
                        onAuthoritativeRebaselineAborted = { page, generation ->
                            aborted += page to generation
                        },
                    ),
            )

        try {
            store.accept(SharedThreadIntent.Start(routePayload()))
            assertEquals("account A canonical message", store.state.value.payload.messages.single().body)
            assertNull(store.state.value.payload.errorCode)
            val accountA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
            payloadStore.clearForTest()
            val abortCountBeforeRefresh = aborted.size

            repository.blockRefresh = true
            val refresh = storeScope.launch { store.accept(SharedThreadIntent.Refresh) }
            refreshStarted.await()
            val stateBeforeOwnerSwitch = store.state.value
            assertTrue(stateBeforeOwnerSwitch.payload.backgroundRefreshing)
            assertNull(stateBeforeOwnerSwitch.payload.errorCode)

            accountId = "account-b"
            authGeneration = 2L
            val accountB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
            assertEquals("account-b", accountB.principalId)

            releaseRefresh.complete(Unit)
            refresh.join()

            assertEquals(stateBeforeOwnerSwitch, store.state.value)
            assertNull(store.state.value.payload.errorCode)
            assertEquals(abortCountBeforeRefresh + 1, aborted.size)
            assertEquals("account-a", aborted.last().first.principalId)
            assertNull(
                payloadStore.get(
                    serverId = ServerIdValue,
                    threadId = ThreadIdValue,
                    environmentId = accountA.environmentId,
                    principalId = "account-a",
                    ownerScopeId = requireNotNull(accountA.threadPayloadOwnerScopeId),
                )
            )
            assertNull(
                payloadStore.get(
                    serverId = ServerIdValue,
                    threadId = ThreadIdValue,
                    environmentId = accountB.environmentId,
                    principalId = "account-b",
                    ownerScopeId = requireNotNull(accountB.threadPayloadOwnerScopeId),
                )
            )
        } finally {
            releaseRefresh.complete(Unit)
            store.clear()
            payloadStore.clearForTest()
            storeJob.cancel()
        }
    }

    private class BlockingRefreshRepository(
        private val delegate: ConversationRepository,
        private val refreshStarted: CompletableDeferred<Unit>,
        private val releaseRefresh: CompletableDeferred<Unit>,
    ) : ConversationRepository by delegate {
        var blockRefresh: Boolean = false

        // Message storage Phase 2b: the store asks with captureRawMessagePage; route it to this fake's answer.
        override suspend fun getThread(
            threadId: ThreadId,
            includeFollowedSummaries: Boolean,
            limit: Int,
            captureRawMessagePage: Boolean,
        ): DataResult<ThreadSnapshot> = getThread(threadId, includeFollowedSummaries, limit)

        override suspend fun getThread(
            threadId: ThreadId,
            includeFollowedSummaries: Boolean,
            limit: Int,
        ): DataResult<ThreadSnapshot> {
            if (blockRefresh) {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
            }
            return delegate.getThread(threadId, includeFollowedSummaries, limit)
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

        fun routePayload(): RaftThreadScreenPayload =
            RaftThreadScreenPayload(
                instanceId = "secondary-provenance-channel-1",
                status = "loading",
                title = "general",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftThreadSurfaceKind.Channel,
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
    }
}
