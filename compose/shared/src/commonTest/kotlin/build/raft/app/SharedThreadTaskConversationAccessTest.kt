package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.unjoinedHomeSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.TaskConversationChannelRef
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Hands fa151b10: a task conversation has no Home row, so its composer waited for the whole
 * canonical load (messages/context, then channels/:id, then channels/:parent) before it mounted.
 * Its write authority is its task channel's, so the task channel's Home row resolves it first.
 */
class SharedThreadTaskConversationAccessTest {
    private val conversationId = "task-conv-1"

    private fun taskRoute(): RaftThreadScreenPayload =
        basePayload().copy(
            instanceId = "shared-shell-thread-$conversationId",
            title = "",
            sourceThreadId = conversationId,
            streamChannelId = conversationId,
            channelKind = RaftTaskConversationChannelKind,
        )

    private fun TestScope.startTaskConversation(home: HomeSnapshot): Triple<SharedThreadStore, FakeConversationRepository, CoroutineScope> {
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = conversationId,
                            body = "network body",
                            conversation =
                                conversationSummary(conversationId).copy(
                                    kind = ConversationKind.Task,
                                    channelType = RaftTaskConversationChannelKind,
                                    joined = true,
                                ),
                        )
                    )
            ).apply {
                // The canonical load never answers in this test: only the Home row can resolve access.
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
                taskConversationRefs =
                    mapOf(
                        ChannelId(conversationId) to
                            TaskConversationChannelRef(
                                conversationChannelId = ChannelId(conversationId),
                                parentChannelId = ChannelId("channel-1"),
                                taskId = TaskId("task-1"),
                            )
                    )
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = FakeChannelRepository { DataResult.Content(home) },
            )
        // The durable / memory frame a reopen shows while the canonical load runs.
        dataSource.cachePayloadForTest(contentPayloadForRoute(taskRoute(), "cached body"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(taskRoute()))
        return Triple(store, repository, scope)
    }

    @Test
    fun joinedTaskChannelMountsTheComposerBeforeTheCanonicalLoad() = runTest {
        val (store, repository, scope) = startTaskConversation(joinedHomeSnapshot())
        repository.getThreadStarted?.await()
        // Well inside the initial lookup's 1 s deadline; the canonical load is still held.
        advanceTimeBy(500)
        runCurrent()

        val state = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, state.accessResolution, "state=$state")
        assertEquals(listOf("cached body"), state.payload.messages.map { it.body })
        assertFalse(state.canonicalAccessResolved)
        assertTrue(state.writeActionsEnabled)
        assertTrue(state.payload.joined)
        assertTrue(state.payload.canSend)
        assertTrue(state.payload.composer.enabled)
        assertTrue(state.payload.isThreadPrimaryFooterReady(state.timelineSlice, state.accessResolution))
        assertEquals(listOf(ChannelId(conversationId)), repository.taskConversationRefRequests)
        scope.cancel()
    }

    @Test
    fun unjoinedTaskChannelStillWaitsForTheCanonicalLoad() = runTest {
        val (store, repository, scope) = startTaskConversation(unjoinedHomeSnapshot())
        repository.getThreadStarted?.await()
        // Past the initial lookup's own deadline: nothing but the canonical load may resolve it now.
        advanceTimeBy(5_000)
        runCurrent()

        val state = store.state.value
        assertEquals(ThreadAccessResolution.Loading, state.accessResolution)
        assertFalse(state.writeActionsEnabled)
        assertFalse(state.payload.isThreadPrimaryFooterReady(state.timelineSlice, state.accessResolution))
        scope.cancel()
    }
}
