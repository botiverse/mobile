package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftAgentLounges
import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadId
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadState
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.dispatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Characterization of [SharedThreadStore] before it is split into collaborator classes
 * (#proj-mobile:#166, plan section 4 and the Lagoon review invariants). These cases pin what the
 * store does TODAY, including the exact diagnostics lines scripts and telemetry parse; a refactor
 * that only moves code must keep every one of them green without edits.
 *
 * Pinned here:
 * - Start vs Refresh cancel different job sets (a deferred parent-directory hint survives a
 *   Refresh and is replayed; a Start cancels it).
 * - The visible-read coalesce window on the scheduler's virtual clock.
 * - Where the route stale token is checked after the refresh's suspension points.
 * - Realtime events dispatched from a background (Dispatchers.Default) thread.
 */
class SharedThreadStoreCharacterizationTest {
    @BeforeTest
    fun forgetLounges() {
        RaftAgentLounges.clear()
    }

    @AfterTest
    fun forgetLoungesAfter() {
        RaftAgentLounges.clear()
    }

    // ---- 5. Realtime off the main thread -----------------------------------------------------

    /**
     * Lagoon review invariant 1: realtime events reach the store on Dispatchers.Default. The same
     * `message:new` dispatched from a Default thread into a store whose scope is Default lands in
     * state exactly as it does on the caller's dispatcher.
     *
     * Real threads on purpose: the property is "nothing assumes the main / test thread", which a
     * virtual-time dispatcher cannot express. Waits are event-driven `state.first` (no polling),
     * bounded only so a regression fails with the state instead of hanging.
     */
    @Test
    fun realtimeMessageDispatchedFromADefaultThreadLandsAsOnTheCallersDispatcher() = runBlocking {
        val event =
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        seq = 11L,
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "fresh body"
                    )
                )
            )

        val referenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reference = SharedThreadStore(dataSource = realtimeDataSource(), scope = referenceScope)
        reference.accept(SharedThreadIntent.Start(basePayload()))
        awaitThreadState(reference, "reference write authority") { it.accessResolution == ThreadAccessResolution.Resolved }
        reference.accept(event)
        val expected = awaitThreadState(reference, "reference m2") { state -> state.payload.messages.any { it.id == "m2" } }
        referenceScope.cancel()

        val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SharedThreadStore(dataSource = realtimeDataSource(), scope = defaultScope)
            store.accept(SharedThreadIntent.Start(basePayload()))
            awaitThreadState(store, "write authority") { it.accessResolution == ThreadAccessResolution.Resolved }
            withContext(Dispatchers.Default) { store.dispatch(event) }
            val landed = awaitThreadState(store, "m2 from a Default thread") { state -> state.payload.messages.any { it.id == "m2" } }

            assertEquals(expected.payload.status, landed.payload.status)
            assertEquals(
                expected.payload.messages.filter { it.id == "m2" }.map { it.id to it.body },
                landed.payload.messages.filter { it.id == "m2" }.map { it.id to it.body },
            )
            assertEquals(listOf("m2" to "fresh body"), landed.payload.messages.filter { it.id == "m2" }.map { it.id to it.body })
            assertEquals(expected.payload.messages.map { it.id }.toSet(), landed.payload.messages.map { it.id }.toSet())
        } finally {
            defaultScope.cancel()
        }
    }

    /** Production store defaults (no test `storeScope`): the message / payload stores run on Default. */
    private fun realtimeDataSource(): SharedThreadDataSource =
        testSharedThreadDataSource(
            networkApi = FakeThreadApi(failChannelMessages = true),
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            conversationRepository =
                FakeConversationRepository(
                    threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                ),
        ).also { it.cachePayloadForTest(contentPayload("cached body")) }
}

private suspend fun awaitThreadState(
    store: SharedThreadStore,
    reason: String,
    predicate: (SharedThreadState) -> Boolean,
): SharedThreadState =
    try {
        withTimeout(10_000) { store.state.first(predicate) }
    } catch (_: TimeoutCancellationException) {
        val state = store.state.value
        error(
            "waited for $reason: thread=${state.payload.sourceThreadId} status=${state.payload.status} " +
                "messages=${state.payload.messages.map { it.id }} access=${state.accessResolution} " +
                "authority=${state.replyParentAuthority} lookupComplete=${state.initialHomeAccessLookupComplete} " +
                "canonical=${state.canonicalAccessResolved} writeActions=${state.writeActionsEnabled}"
        )
    }
