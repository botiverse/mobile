package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * Web #12696: the server sends no event when someone removes you from a channel. The open channel
 * learns it from its own reads: the heartbeat / resume catch-up answers 403 / 404. That marks the
 * conversation lost (the page leaves with "You're no longer in this channel") and stops asking:
 * no later catch-up request for it. A server error, a network failure or a 401 is not that answer.
 */
class SharedThreadStoreGapAccessLostTest {
    private class Outcome(val accessLost: Boolean, val newerMessagesStatus: String, val latestRequests: Int)

    /** Opens channel-1, then two heartbeat gaps and a resume catch-up, each answered with [latest]. */
    private suspend fun heartbeatsAnsweredWith(latest: DataResult<ThreadSnapshot>): Outcome =
        kotlinx.coroutines.coroutineScope {
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + scopeJob)
            val repository = FakeConversationRepository()
            repository.latestResult = latest
            val dataSource =
                testSharedThreadDataSource(
                    storeScope = scope,
                    networkApi = FakeThreadApi(),
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    conversationRepository = repository,
                )
            val store = SharedThreadStore(dataSource = dataSource, scope = scope)
            store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
            assertFalse(store.state.value.conversationAccessLost, "the channel opened normally")

            store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))
            delay(10)
            assertEquals(1, repository.latestRequests.size, "the first heartbeat asks once")
            val lostAfterFirst = store.state.value.conversationAccessLost
            val status = store.state.value.payload.newerMessagesStatus

            store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 95))
            delay(10)
            store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
            delay(10)
            val outcome = Outcome(lostAfterFirst, status, repository.latestRequests.size)
            assertEquals(lostAfterFirst, store.state.value.conversationAccessLost, "the verdict does not flip")
            scopeJob.cancelAndJoin()
            outcome
        }

    @Test
    fun removedFromTheChannelMarksItLostAndStopsAsking() {
        val answers =
            listOf(
                "403 You do not have access to this channel" to
                    DataResult.NoPermission(
                        PermissionBlocker(
                            code = "server_forbidden",
                            requiredCapability = "server_access",
                            serverMessage = "You do not have access to this channel",
                        )
                    ),
                "404 Channel not found" to
                    DataResult.Error(ScreenError(code = "http_404", retryable = false, serverMessage = "Channel not found")),
            )
        answers.forEach { (label, answer) ->
            runTest {
                val outcome = heartbeatsAnsweredWith(answer)
                assertTrue(outcome.accessLost, "$label: the page is asked to leave")
                assertEquals(1, outcome.latestRequests, "$label: no catch-up request after the channel is lost")
            }
        }
    }

    @Test
    fun serverErrorsNetworkFailuresAndSignedOutKeepTheChannelAndKeepAsking() {
        val answers =
            listOf(
                "5xx" to DataResult.Error(ScreenError(code = "server_unavailable", retryable = true)),
                "network" to DataResult.Error(ScreenError(code = "thread_gap_catchup_network", retryable = true)),
                // 401 is the auth route's problem, not this channel's.
                "401" to authSessionRequiredResult(),
            )
        answers.forEach { (label, answer) ->
            runTest {
                val outcome = heartbeatsAnsweredWith(answer)
                assertFalse(outcome.accessLost, "$label: not an access answer")
                assertEquals("error", outcome.newerMessagesStatus, "$label: a failed catch-up as before")
                assertEquals(3, outcome.latestRequests, "$label: later heartbeats and the resume still ask")
            }
        }
    }

    @Test
    fun onlyAChannelLeavesWithTheChannelNotice() {
        val channel = basePayload()
        assertEquals(ChannelId("channel-1"), channel.accessLostChannelIdOrNull())
        assertNull(channel.copy(surfaceKind = RaftThreadSurfaceKind.DirectMessage).accessLostChannelIdOrNull())
        assertNull(channel.copy(surfaceKind = RaftThreadSurfaceKind.ReplyThread).accessLostChannelIdOrNull())
        assertNull(channel.copy(channelKind = RaftTaskConversationChannelKind).accessLostChannelIdOrNull())
    }
}
