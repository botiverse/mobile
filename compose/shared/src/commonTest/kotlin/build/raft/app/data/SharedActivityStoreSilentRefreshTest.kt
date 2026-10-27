package build.raft.app.data

import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageNew
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin 10-27: a refresh the user did not pull (re-entry, resume, realtime / sync reconcile,
 * server switch over cached rows) is a silent refresh, and the Activity header shows its
 * top-right indicator for it; the user's pull keeps the pull indicator as its only chrome
 * (artin task #22). [SharedActivityState.silentRefreshing] is that distinction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreSilentRefreshTest {
    private val row = threadItem("thread-channel-1", unreadCount = 0).copy(replyCount = 2)

    /** Load N (1-based) waits on its gate, or throws when the gate says so. */
    private class Gates {
        val gates = mutableMapOf<Int, CompletableDeferred<Boolean>>()

        fun gate(call: Int): CompletableDeferred<Boolean> = gates.getOrPut(call) { CompletableDeferred() }

        suspend fun before(call: Int) {
            if (call == 1) return
            if (!gate(call).await()) error("load $call failed")
        }
    }

    private fun TestScope.store(gates: Gates): SharedActivityStore {
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(row))),
                beforeInitialLoad = { call -> gates.before(call) }
            )
        return activityStore(
            backgroundScope,
            inbox,
            realtimeBackgroundRefreshIntervalMillis = 0L,
            nowMillis = { testScheduler.currentTime }
        ).also { store ->
            store.dispatch(SharedActivityIntent.LoadInitial)
            runCurrent()
            assertTrue(store.state.value.loaded, "the rows are shown before the refresh")
            assertFalse(store.state.value.silentRefreshing)
        }
    }

    private fun TestScope.startRealtimeReconcile(store: SharedActivityStore) {
        backgroundScope.launch {
            store.acceptRealtime(
                SharedActivityIntent.ApplyRealtimeEvent(
                    MessageNew(
                        message(
                            id = "message-new",
                            channelId = "thread-channel-1",
                            conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
                        )
                    )
                )
            )
        }
        runCurrent()
    }

    @Test
    fun foregroundRefreshOverShownRowsIsSilent() = runTest {
        val gates = Gates()
        val store = store(gates)

        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()

        assertTrue(store.state.value.refreshing)
        assertFalse(store.state.value.pullRefreshing)
        assertTrue(store.state.value.silentRefreshing)
        gates.gate(2).complete(true)
        runCurrent()
        assertFalse(store.state.value.silentRefreshing)
    }

    @Test
    fun realtimeBackgroundReconcileIsSilentWithoutTouchingPageRefreshState() = runTest {
        val gates = Gates()
        val store = store(gates)

        startRealtimeReconcile(store)

        assertTrue(store.state.value.backgroundRefreshing, "the reconcile is on the network")
        assertFalse(store.state.value.refreshing, "the page keeps its rows and state")
        assertFalse(store.state.value.pullRefreshing)
        assertTrue(store.state.value.silentRefreshing)
        gates.gate(2).complete(true)
        runCurrent()
        assertFalse(store.state.value.backgroundRefreshing)
        assertFalse(store.state.value.silentRefreshing)
    }

    @Test
    fun failedBackgroundReconcileStillEndsTheSilentRefresh() = runTest {
        val gates = Gates()
        val store = store(gates)

        startRealtimeReconcile(store)
        assertTrue(store.state.value.silentRefreshing)
        gates.gate(2).complete(false)
        runCurrent()

        assertFalse(store.state.value.backgroundRefreshing)
        assertFalse(store.state.value.silentRefreshing)
        assertEquals(null, store.state.value.errorCode, "a background failure stays out of the page")
    }

    @Test
    fun userPullIsNotSilent() = runTest {
        val gates = Gates()
        val store = store(gates)

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        assertTrue(store.state.value.pullRefreshing)
        assertFalse(store.state.value.silentRefreshing)
        gates.gate(2).complete(true)
        runCurrent()
        assertFalse(store.state.value.pullRefreshing)
        assertFalse(store.state.value.silentRefreshing)
    }

    @Test
    fun userPullOverAnInFlightReconcileLeavesOnlyThePull() = runTest {
        val gates = Gates()
        val store = store(gates)
        startRealtimeReconcile(store)
        assertTrue(store.state.value.silentRefreshing)

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        assertTrue(store.state.value.pullRefreshing)
        assertFalse(store.state.value.backgroundRefreshing, "the pull superseded the reconcile")
        assertFalse(store.state.value.silentRefreshing)
        gates.gate(3).complete(true)
        runCurrent()
        assertFalse(store.state.value.pullRefreshing)
        assertFalse(store.state.value.silentRefreshing)
    }

    @Test
    fun overlappingFlagsResolveToThePullAlone() {
        val both = SharedActivityState(refreshing = true, pullRefreshing = true, backgroundRefreshing = true)
        assertFalse(both.silentRefreshing)
        assertTrue(SharedActivityState(backgroundRefreshing = true).silentRefreshing)
        assertTrue(SharedActivityState(refreshing = true).silentRefreshing)
        assertFalse(SharedActivityState().silentRefreshing)
    }
}
