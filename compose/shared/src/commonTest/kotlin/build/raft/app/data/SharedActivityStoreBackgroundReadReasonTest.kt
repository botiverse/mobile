package build.raft.app.data

import build.raft.app.model.ActivityChanged
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 228: the Activity list reads nothing in the background; the one read owed for the return is
 * labelled `visibility`, a realtime reconcile in the foreground `realtime_fallback`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreBackgroundReadReasonTest {
    private class RecordingInbox(private val inner: FakeInboxRepository) : InboxRepository by inner {
        val reasons = mutableListOf<InboxFetchReason?>()

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            reasons += kotlinx.coroutines.currentCoroutineContext()[InboxFetchReasonContext]?.reason
            return inner.loadInitial(serverId, filter, limit)
        }
    }

    @Test
    fun theReturnReadIsVisibilityAndTheForegroundReconcileIsRealtimeFallback() = runTest {
        val inbox = RecordingInbox(FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", 1))))))
        val foreground = MutableStateFlow(true)
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = 2_500L,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground
            )
        val changed = SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "sync_changes"))
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(listOf<InboxFetchReason?>(InboxFetchReason.Initial), inbox.reasons)

        advanceTimeBy(10_000L)
        store.dispatch(changed)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(InboxFetchReason.RealtimeFallback, inbox.reasons.last())
        val foregroundReads = inbox.reasons.size

        foreground.value = false
        runCurrent()
        repeat(3) {
            store.dispatch(changed)
            advanceTimeBy(60_000L)
            runCurrent()
        }
        assertEquals(foregroundReads, inbox.reasons.size, "no read in the background")

        foreground.value = true
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(foregroundReads + 1, inbox.reasons.size, "one read for the return")
        assertEquals(InboxFetchReason.Visibility, inbox.reasons.last())
    }
}
