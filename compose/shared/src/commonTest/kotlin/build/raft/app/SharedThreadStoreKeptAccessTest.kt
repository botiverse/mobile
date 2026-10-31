package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hands 4cf4c781: reopening a conversation whose memory frame is shown keeps the access this owner
 * already resolved for the same route, so the composer / Join bar does not vanish while the
 * refresh runs; only a terminal refresh result changes it.
 */
class SharedThreadStoreKeptAccessTest {
    private fun joinedSnapshot(body: String) =
        threadSnapshot(body = body, conversation = conversationSummary().copy(joined = true))

    private class Harness(
        val repository: FakeConversationRepository,
        val dataSource: SharedThreadDataSource,
        val scope: CoroutineScope,
    ) {
        fun open(): SharedThreadStore =
            SharedThreadStore(dataSource = dataSource, scope = scope).also { store ->
                store.dispatch(SharedThreadIntent.Start(basePayload()))
            }
    }

    private fun TestScope.harness(): Harness {
        val repository = FakeConversationRepository(threadResult = DataResult.Content(joinedSnapshot("first body")))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        return Harness(repository, dataSource, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)))
    }

    private fun SharedThreadState.footerReady(): Boolean =
        payload.isThreadPrimaryFooterReady(timelineSlice = timelineSlice, accessResolution = accessResolution)

    @Test
    fun reopenShowingTheMemoryFrameKeepsResolvedAccessWhileTheRefreshRuns() = runTest {
        val harness = harness()
        val first = harness.open()
        advanceUntilIdle()
        val resolved = first.state.value
        assertEquals(ThreadAccessResolution.Resolved, resolved.accessResolution, "first open resolves")
        assertTrue(resolved.canonicalAccessResolved, "first open is canonical")

        // Reopen: the canonical refresh is held, as on a slow link.
        harness.repository.threadResult = DataResult.Content(joinedSnapshot("second body"))
        harness.repository.getThreadStarted = CompletableDeferred()
        harness.repository.releaseGetThread = CompletableDeferred()
        val reopened = harness.open()
        harness.repository.getThreadStarted?.await()

        val inFlight = reopened.state.value
        assertEquals("first body", inFlight.payload.messages.singleOrNull()?.body, "memory frame shown")
        assertEquals(ThreadAccessResolution.Resolved, inFlight.accessResolution, "access kept while refreshing")
        assertTrue(inFlight.writeActionsEnabled, "write actions kept while refreshing")
        assertTrue(inFlight.payload.joined, "membership kept while refreshing")
        assertTrue(inFlight.footerReady(), "composer footer rendered while refreshing")

        harness.repository.releaseGetThread?.complete(Unit)
        advanceUntilIdle()
        val refreshed = reopened.state.value
        assertEquals("second body", refreshed.payload.messages.singleOrNull()?.body)
        assertEquals(ThreadAccessResolution.Resolved, refreshed.accessResolution)
        assertTrue(refreshed.canonicalAccessResolved, "the refresh still records canonical access")
        harness.scope.cancel()
    }

    @Test
    fun refreshResultsChangeKeptAccessOnlyWhenTheyCarryAccess() = runTest {
        // (case, reopen refresh result, expected resolution, expected joined, kept on the next open)
        data class Case(
            val name: String,
            val result: DataResult<ThreadSnapshot>,
            val resolution: ThreadAccessResolution,
            val joined: Boolean,
            val keptNext: Boolean,
        )
        val cases =
            listOf(
                Case(
                    "no permission",
                    DataResult.NoPermission(PermissionBlocker(code = "forbidden")),
                    ThreadAccessResolution.NoPermission,
                    joined = false,
                    keptNext = false,
                ),
                Case(
                    "not a member anymore",
                    DataResult.Content(
                        threadSnapshot(body = "second body", conversation = conversationSummary().copy(joined = false))
                    ),
                    ThreadAccessResolution.Resolved,
                    joined = false,
                    keptNext = true,
                ),
                Case(
                    "transient error",
                    DataResult.Error(ScreenError(code = "network", retryable = true)),
                    ThreadAccessResolution.Resolved,
                    joined = true,
                    keptNext = true,
                ),
            )
        cases.forEach { case ->
            val harness = harness()
            harness.open()
            advanceUntilIdle()

            harness.repository.threadResult = case.result
            val reopened = harness.open()
            advanceUntilIdle()
            val afterRefresh = reopened.state.value
            assertEquals(case.resolution, afterRefresh.accessResolution, case.name)
            assertEquals(case.joined, afterRefresh.payload.joined, case.name)
            if (case.resolution == ThreadAccessResolution.NoPermission) {
                assertFalse(afterRefresh.writeActionsEnabled, case.name)
            }

            // The next open only keeps access the last refresh left resolved.
            harness.repository.getThreadStarted = CompletableDeferred()
            harness.repository.releaseGetThread = CompletableDeferred()
            val next = harness.open()
            harness.repository.getThreadStarted?.await()
            assertEquals(
                if (case.keptNext) ThreadAccessResolution.Resolved else ThreadAccessResolution.Loading,
                next.state.value.accessResolution,
                "${case.name}: next open",
            )
            harness.repository.releaseGetThread?.complete(Unit)
            advanceUntilIdle()
            harness.scope.cancel()
        }
    }
}
