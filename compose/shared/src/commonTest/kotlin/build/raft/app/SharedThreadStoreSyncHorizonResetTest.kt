package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageRangeResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.realtime.RealtimeSyncHorizon
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `sync:resume` that answers `horizonReset: true` (botiverse/slock #10982: more late messages than the
 * server sends) asks the open thread to re-read its latest page through the existing gap catch-up; the
 * per-channel `/messages/sync` delta is never used for it.
 */
class SharedThreadStoreSyncHorizonResetTest {
    private class Outcome(val catchUpCalls: Int, val messageSyncCalls: Int, val messageIds: List<String>)

    private suspend fun outcomeAfter(event: RealtimeRefreshRequired): Outcome = coroutineScope {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api =
            SharedThreadStoreTest.FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false)
            )
        // The latest page now holds a late message (seq 9) next to the shown one.
        api.catchUpBodyTexts.addLast(pageRangeResponse(9L..10L))
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = api,
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    ),
                scope = scope,
            )
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        store.setViewportFollowsLatest(true)

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(event = event, activeReplyParentMessageId = null))

        val outcome = Outcome(api.catchUpCalls, api.messageSyncCalls, store.state.value.payload.messages.map { it.id })
        scopeJob.cancelAndJoin()
        outcome
    }

    @Test
    fun horizonResetReReadsTheLatestPageThroughTheGapCatchUp() = runBlocking {
        val reset = outcomeAfter(RealtimeRefreshRequired(scope = RealtimeRefreshScope.Home, reason = RealtimeSyncHorizon.ResetReason))
        assertEquals(1, reset.catchUpCalls)
        assertEquals(0, reset.messageSyncCalls, "never the per-channel /messages/sync delta")
        assertEquals(listOf("m9", "m10"), reset.messageIds, "the late message is on screen")

        val otherRefresh =
            outcomeAfter(
                RealtimeRefreshRequired(scope = RealtimeRefreshScope.Home, reason = "transport_reconnect_resume_unavailable")
            )
        assertEquals(0, otherRefresh.catchUpCalls, "only the horizon reset asks the open thread")
    }
}
