package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.activityReplyPayload
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyThreadConversation
import build.raft.app.SharedThreadStoreTestFixtures.unjoinedHomeSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.dispatch
import build.raft.app.thread.isThreadPrimaryFooterReady
import build.raft.app.thread.resolvedTaskLookupChannelId
import build.raft.app.thread.shouldRenderThreadAccessRetry
import build.raft.app.thread.shouldShowThreadComposerForReadyContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedThreadStoreReplyParentAccessTest {
    @Test
    fun activityReplyThreadUsesJoinedDirectMessageParentAccess() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = activityReplyPayload(
            parentKind = ConversationKind.DirectMessage,
            parentChannelId = "dm-1",
            parentMessageId = "dm-parent-message",
            threadId = "dm-thread-1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "dm-thread-1",
                            body = "network dm reply",
                            conversation =
                                replyThreadConversation(
                                    threadId = "dm-thread-1",
                                    parentChannelId = "dm-1",
                                    parentMessageId = "dm-parent-message",
                                    joined = false,
                                    canSend = false
                                )
                        )
                    )
            )
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(joinedDmHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached dm reply", channelId = "dm-thread-1").copy(
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false)
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(RaftThreadSurfaceKind.DirectMessage, resolvedFrame.payload.parentSurfaceKind)
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertEquals("dm-1", resolvedFrame.payload.parentChannelId)
        assertEquals(1, channelRepository.directMessageCalls)
        scope.cancel()
    }
    @Test
    fun activityReplyThreadKeepsCanonicalRepliesReadableWhenParentAccessCandidateIsMissing() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            id = "reply-1",
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    )
            )
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(replyChildOnlyHomeSnapshot(joined = true, canSend = true))
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(10_000) {
            while (
                store.state.value.accessResolution != ThreadAccessResolution.Resolved ||
                store.state.value.writeActionsEnabled
            ) {
                delay(10)
            }
        }

        val degradedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, degradedFrame.accessResolution)
        assertFalse(degradedFrame.accessResolution.shouldRenderThreadAccessRetry())
        assertEquals(listOf("network reply"), degradedFrame.payload.messages.map { it.body })
        assertFalse(degradedFrame.writeActionsEnabled)
        assertFalse(degradedFrame.payload.joined)
        assertFalse(degradedFrame.payload.canSend)
        assertFalse(degradedFrame.payload.composer.enabled)
        assertTrue(
            degradedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = degradedFrame.timelineSlice,
                accessResolution = degradedFrame.accessResolution
            )
        )
        assertFalse(
            shouldShowThreadComposerForReadyContent(
                joined = degradedFrame.payload.joined,
                showComposer = degradedFrame.payload.showComposer,
                selectedTab = degradedFrame.payload.selectedTab
            )
        )
        assertEquals(
            null,
            degradedFrame.payload.resolvedTaskLookupChannelId(
                writeActionsEnabled = degradedFrame.writeActionsEnabled
            )
        )
        scope.cancel()
    }
    @Test
    fun warmCacheRefreshFailureDoesNotReenableStaleFooterAccess() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                releaseHome.await()
                DataResult.Content(unjoinedHomeSnapshot())
            }
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Error(ScreenError(code = "offline", retryable = true))
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(contentPayload("cached body").copy(joined = true, canSend = true))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        repository.getThreadStarted?.await()
        repository.releaseGetThread?.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Error) {
                delay(10)
            }
        }
        releaseHome.complete(Unit)
        delay(50)

        val failedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Error, failedFrame.accessResolution)
        assertEquals("content", failedFrame.payload.status)
        assertFalse(
            failedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = failedFrame.timelineSlice,
                accessResolution = failedFrame.accessResolution
            )
        )
        scope.cancel()
    }
    @Test
    fun resolvedAccessStaysReadyDuringOrdinaryBackgroundRefresh() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = FakeConversationRepository()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        repository.getThreadStarted = CompletableDeferred()
        repository.releaseGetThread = CompletableDeferred()
        store.dispatch(SharedThreadIntent.Refresh)
        repository.getThreadStarted?.await()

        val refreshingFrame = store.state.value
        assertTrue(refreshingFrame.payload.backgroundRefreshing)
        assertEquals(ThreadAccessResolution.Resolved, refreshingFrame.accessResolution)
        assertTrue(
            refreshingFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = refreshingFrame.timelineSlice,
                accessResolution = refreshingFrame.accessResolution
            )
        )

        repository.releaseGetThread?.complete(Unit)
        scope.cancel()
    }
    private fun joinedDmHomeSnapshotWithConflictingReplyChild(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(
                        threadId = "dm-thread-1",
                        parentChannelId = "dm-1",
                        parentMessageId = "dm-parent-message",
                        joined = false,
                        canSend = false
                    ).toHomeChannelRow()
                ),
            directMessages =
                listOf(
                    conversationSummary("dm-1").copy(
                        kind = ConversationKind.DirectMessage,
                        channelId = ChannelId("dm-1"),
                        joined = true,
                        permissions = ConversationPermissions(canSend = true)
                    ).toHomeDirectMessageRow()
                ),
            unreadTotal = 0
        )
    private fun replyChildOnlyHomeSnapshot(
        joined: Boolean,
        canSend: Boolean
    ): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(joined = joined, canSend = canSend).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )
}
