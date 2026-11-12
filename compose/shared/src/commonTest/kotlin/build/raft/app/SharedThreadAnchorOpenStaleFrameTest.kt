package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.threadPayloadOwnerScopeIdForTest
import build.raft.app.SharedThreadStoreTestFixtures.virtualForegroundLoadGate
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Hands 7e198cab (artin, dm #118): a task conversation's message showed in Activity, but opening it showed an
 * older window without it. The memory frame was stale (a realtime write had been refused as `poisoned`), the
 * open painted it as the conversation, and the anchor context that held the message was thrown away as
 * superseded, round after round, until the person left.
 */
class SharedThreadAnchorOpenStaleFrameTest {
    private val conversationId = "task-conv-1"
    private val anchorId = "anchor-1"

    private fun taskRoute(): RaftThreadScreenPayload =
        basePayload().copy(
            instanceId = "shared-shell-thread-$conversationId",
            title = "",
            sourceThreadId = conversationId,
            streamChannelId = conversationId,
            channelKind = RaftTaskConversationChannelKind,
        )

    private fun taskSnapshot(vararg messages: Pair<String, Long>) =
        DataResult.Content(
            threadSnapshot(
                threadId = conversationId,
                messages = messages.map { (id, seq) -> message(threadId = conversationId, id = id, seq = seq, body = "body $id") },
                conversation =
                    conversationSummary(conversationId).copy(
                        kind = ConversationKind.Task,
                        channelType = RaftTaskConversationChannelKind,
                        joined = true,
                    ),
            )
        )

    private class Rig(
        val store: SharedThreadStore,
        val scope: CoroutineScope,
        val payloadStore: SharedThreadPayloadStore,
        val ownerScopeId: String,
        val logs: MutableList<String>,
    )

    /** The memory frame holds only `m1` ("stale body"): the anchor is newer than anything it has. */
    private suspend fun TestScope.open(
        repository: FakeConversationRepository,
        route: RaftThreadScreenPayload = taskRoute().copy(focusedMessageId = anchorId),
        beforeOpen: (SharedThreadPayloadStore, String) -> Unit = { _, _ -> },
    ): Rig {
        val logs = mutableListOf<String>()
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = FakeChannelRepository { DataResult.Content(joinedHomeSnapshot()) },
                payloadStore = payloadStore,
                diagnosticsLogger = { logs += it },
            )
        dataSource.cachePayloadForTest(contentPayloadForRoute(taskRoute(), "stale body"))
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        beforeOpen(payloadStore, ownerScopeId)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = { logs += it })
        store.dispatch(SharedThreadIntent.Start(route))
        runCurrent()
        return Rig(store, scope, payloadStore, ownerScopeId, logs)
    }

    @Test
    fun anOpenAtAMessageTheFrameLacksWaitsAtTheAnchorThenShowsTheFirstAnswerHoldingIt() = runTest {
        val repository =
            FakeConversationRepository(threadResult = taskSnapshot("m1" to 1)).apply {
                messageContextResult = taskSnapshot("m1" to 1, "m2" to 2, anchorId to 3)
                messageContextDelayMillis = 3_500
            }
        lateinit var bumps: kotlinx.coroutines.Job
        val rig = open(repository) { payloadStore, owner ->
            // A busy server: something moves the conversation's freshness generation every second.
            bumps =
                backgroundScope.launch {
                    repeat(30) {
                        delay(1_000)
                        payloadStore.freshnessLedger.advance("server-1", conversationId, owner, cause = "test_bump")
                    }
                }
        }

        val first = rig.store.state.value.payload
        assertEquals("loading", first.status, "the stale frame is not painted as the conversation: $first")
        assertEquals(emptyList(), first.messages.map { it.id })
        assertTrue(
            rig.logs.any { "thread_start_anchor_pending" in it && "anchor=$anchorId" in it && "candidate=memory" in it },
            rig.logs.toString(),
        )

        // The first context answer lands; its generation moved meanwhile, so it is fetched again.
        advanceTimeBy(3_600)
        runCurrent()
        val shown = rig.store.state.value.payload
        assertEquals(listOf("m1", "m2", anchorId), shown.messages.map { it.id }, "state=${rig.store.state.value}")
        assertEquals(anchorId, shown.focusedMessageId, "the page's focus session places the anchor")
        assertTrue(shown.backgroundRefreshing, "the refetch is still running")
        assertEquals(1, repository.getMessageContextRequests.size)
        assertTrue(rig.logs.any { "thread_canonical_superseded" in it && "round=1 cause=test_bump" in it }, rig.logs.toString())
        assertTrue(rig.logs.any { "thread_superseded_anchor_context" in it && "applied=true" in it }, rig.logs.toString())
        bumps.cancel()
        rig.scope.cancel()
    }

    @Test
    fun aFailedLoadOfAnAnchoredOpenFallsBackToTheFrameItHeldBack() = runTest {
        val failure = DataResult.Error(ScreenError(code = "thread_network", retryable = true))
        val repository =
            FakeConversationRepository(threadResult = failure).apply { messageContextResult = failure }
        val rig = open(repository)
        assertEquals("loading", rig.store.state.value.payload.status)

        advanceTimeBy(1_000)
        runCurrent()
        val payload = rig.store.state.value.payload
        // Offline keeps showing what this device has, marked as a failed load.
        assertEquals(listOf("stale body"), payload.messages.map { it.body }, "state=${rig.store.state.value}")
        assertTrue(payload.mainLoadFailed)
        rig.scope.cancel()
    }

    @Test
    fun aPoisonedConversationOpensOnTheNetworkNotItsLocalFrameAndALandedLoadClearsTheMark() = runTest {
        val repository = FakeConversationRepository(threadResult = taskSnapshot("m1" to 1, "m2" to 2)).apply {
            threadDelayMillis = 500
        }
        val rig =
            open(repository, route = taskRoute()) { payloadStore, owner ->
                payloadStore.markConversationStale("server-1", conversationId, owner, "realtime_poisoned:fact_not_in_scope")
            }
        assertEquals("loading", rig.store.state.value.payload.status)
        assertEquals(emptyList(), rig.store.state.value.payload.messages.map { it.id })
        assertTrue(
            rig.logs.any { "thread_start_stale_refetch threadId=$conversationId cause=realtime_poisoned:fact_not_in_scope" in it },
            rig.logs.toString(),
        )

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("m1", "m2"), rig.store.state.value.payload.messages.map { it.id })
        assertNull(rig.payloadStore.staleConversationCause("server-1", conversationId, rig.ownerScopeId))
        rig.scope.cancel()
    }

    @Test
    fun leavingDuringTheLoadLogsTheRefreshAsCancelled() = runTest {
        val repository =
            FakeConversationRepository(threadResult = taskSnapshot("m1" to 1)).apply {
                messageContextResult = taskSnapshot("m1" to 1, anchorId to 3)
                messageContextDelayMillis = 3_500
            }
        val rig = open(repository)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(rig.logs.none { "thread_refresh_result" in it }, rig.logs.toString())

        rig.scope.cancel()
        runCurrent()
        assertTrue(
            rig.logs.any { "thread_refresh_result threadId=$conversationId result=cancelled" in it },
            rig.logs.toString(),
        )
    }
}
