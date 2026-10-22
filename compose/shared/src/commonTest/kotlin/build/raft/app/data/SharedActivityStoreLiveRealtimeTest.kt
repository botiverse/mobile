package build.raft.app.data

import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hands feedback bc6e392e (Android 1.15.2): the AppShell domain sink applies Activity realtime
 * under the realtime authority registry lock. Waiting there for an Activity load held every later
 * realtime event; the live apply returns as soon as the patch is applied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreLiveRealtimeTest {
    @Test
    fun liveApplyPatchesWhileActivityLoadIsStillOnTheNetwork() = runTest {
        val row = threadItem("thread-1").copy(replyCount = 1, latestActivitySeq = "20")
        val secondLoad = CompletableDeferred<Unit>()
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(row))),
                beforeInitialLoad = { call -> if (call >= 2) secondLoad.await() }
            )
        val store = activityStore(backgroundScope, inbox, nowMillis = { testScheduler.currentTime })
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(2, inbox.initialLoadCount, "the refresh load is on the network")
        val reply = SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("reply-2", channelId = "thread-1", seq = 21L)))

        val live = async { store.applyLiveRealtime(reply) }
        runCurrent()

        assertTrue(live.isCompleted, "the live apply must not wait for the in-flight Activity load")
        assertEquals(MessageId("reply-2"), store.state.value.items.single().latestActivityMessageId)
        val settled = async { store.acceptRealtime(reply) }
        runCurrent()
        assertFalse(settled.isCompleted, "acceptRealtime keeps its settle contract for callers that need it")
        secondLoad.complete(Unit)
        settled.cancel()
    }
}
