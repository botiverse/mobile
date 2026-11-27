package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.threadPayloadOwnerScopeIdForTest
import build.raft.app.SharedThreadStoreTestFixtures.virtualForegroundLoadGate
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadMessageStore
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class SharedThreadStorePrewarmTest {
    @Test
    fun supersededPrewarmCompletionCannotReplaceTheNewestActivityGeneration() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale =
            contentPayloadForRoute(
                base = route,
                body = "stale generation",
                id = "reply-stale",
                channelId = "thread-m1"
            )
        val fresh =
            contentPayloadForRoute(
                base = route,
                body = "fresh generation",
                id = "reply-fresh",
                channelId = "thread-m1"
            )
        val staleStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val freshStarted = CompletableDeferred<Unit>()
        val releaseFresh = CompletableDeferred<Unit>()
        val stalePrewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    staleStarted.complete(Unit)
                    releaseStale.await()
                    DataResult.Content(stale)
                }
            }
        staleStarted.await()
        payloadStore.freshnessLedger.advance("server-1", "thread-m1", TestThreadOwnerScopeId)
        val freshPrewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    freshStarted.complete(Unit)
                    releaseFresh.await()
                    DataResult.Content(fresh)
                }
            }
        freshStarted.await()

        releaseFresh.complete(Unit)
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(freshPrewarm.await())
        releaseStale.complete(Unit)
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(stalePrewarm.await())

        val consumed =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                payloadStore.joinOrConsumePrewarm("server-1", "thread-m1", TestThreadOwnerScopeId)
            )
        assertEquals("fresh generation", consumed.data.messages.single().body)
        appScope.cancel()
    }
    @Test
    fun ownerTransitionDropsLateReplyPrewarmWithoutWritingEitherOwnerCache() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, prewarmStartIntervalMillis = 0L)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("channel-1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            ).also {
                it.getReplyThreadStarted = CompletableDeferred()
                it.releaseGetReplyThread = CompletableDeferred()
            }
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        val ownerBSession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-b", email = "b@example.test", displayName = "Owner B"),
                accessToken = "access-b",
                refreshToken = "refresh-b"
            )
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                messageStore = SharedThreadMessageStore(scope = backgroundScope),
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-owner-transition", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore,
                replyCacheIdentityAuthority = { identity }
            )
        val ownerA = dataSource.threadPayloadOwnerScopeIdForTest()
        val marker =
            ActivityReplyThreadPrewarmMarker(
                key =
                    ActivityReplyThreadPrewarmKey(
                        serverId = "server-1",
                        parentChannelId = "channel-1",
                        parentMessageId = "m1",
                        threadChannelId = "thread-m1"
                    )
            )
        val prewarm = async { dataSource.prewarmReplyThread(marker) }
        repository.getReplyThreadStarted?.await()

        identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "u-b",
                serverId = "server-1",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        val ownerB = dataSource.threadPayloadOwnerScopeIdForTest()
        repository.releaseGetReplyThread?.complete(Unit)

        val result = assertIs<DataResult.Error>(prewarm.await())
        assertEquals("thread_reply_access_authority_changed", result.error.code)
        assertNull(payloadStore.get("server-1", "thread-m1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerA))
        assertNull(payloadStore.get("server-1", "thread-m1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerB))
    }
    @Test
    fun newerActivityVersionReplacesCompletedPrewarmBeforeVisibleOpen() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, prewarmStartIntervalMillis = 0L)
        fun snapshot(messages: List<Message>) =
            DataResult.Content(
                threadSnapshot(
                    threadId = "thread-m1",
                    messages = messages,
                    parentMessage =
                        message(
                            threadId = "channel-1",
                            id = "m1",
                            seq = 1,
                            body = "parent"
                        ),
                    conversation =
                        conversationSummary("thread-m1").copy(
                            kind = ConversationKind.Thread,
                            channelId = ChannelId("thread-m1"),
                            thread =
                                ThreadConversation(
                                    parentChannelId = ChannelId("channel-1"),
                                    parentMessageId = MessageId("m1"),
                                    streamChannelId = ChannelId("thread-m1")
                                )
                        )
                )
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    snapshot(
                        listOf(
                            message(
                                threadId = "thread-m1",
                                id = "reply-1",
                                seq = 2,
                                body = "reply one"
                            )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-version", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val key =
            ActivityReplyThreadPrewarmKey(
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                threadChannelId = "thread-m1"
            )

        assertIs<DataResult.Content<Unit>>(
            dataSource.prewarmReplyThread(
                ActivityReplyThreadPrewarmMarker(key = key)
            )
        )
        repository.threadResult =
            snapshot(
                listOf(
                    message(
                        threadId = "thread-m1",
                        id = "reply-1",
                        seq = 2,
                        body = "reply one"
                    ),
                    message(
                        threadId = "thread-m1",
                        id = "reply-2",
                        seq = 3,
                        body = "reply two"
                    )
                )
            )
        payloadStore.freshnessLedger.advance(
            "server-1",
            "thread-m1",
            dataSource.threadPayloadOwnerScopeIdForTest()
        )

        assertIs<DataResult.Content<Unit>>(
            dataSource.prewarmReplyThread(
                ActivityReplyThreadPrewarmMarker(key = key)
            )
        )
        val visible =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                dataSource.fetchThreadPayload(
                    basePayload = replyPayload(parentMessageId = "m1", threadId = "thread-m1"),
                    reusePrewarm = true
                )
            )

        assertEquals(2, repository.getReplyThreadRequests.size)
        assertEquals(listOf("reply-1", "reply-2"), visible.data.messages.map { it.id })
        assertEquals(listOf(DataRequestPriority.Background, DataRequestPriority.Background), repository.getReplyThreadPriorities)
    }
}
