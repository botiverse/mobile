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
 * Ticket 67609860 (artin, Android 1.17.0): the Activity header's top-right indicator showed for
 * every refresh, realtime-triggered background loads included, so it was up almost constantly.
 * It now shows only for a refresh that runs when the app returns or the page becomes visible
 * after a while ([ActivityHeaderRefreshStaleAfterMillis]); background loads show nothing, and a
 * user pull shows only the pull spinner (artin task #22). [SharedActivityState.headerRefreshing]
 * is that decision.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreHeaderRefreshTest {
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

    /** Wall clock the store reads for the rows' age; the open reads the rows at [WallStart]. */
    private var wallMillis = WallStart

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
            nowMillis = { testScheduler.currentTime },
            nowEpochMillis = { wallMillis }
        ).also { store ->
            store.dispatch(SharedActivityIntent.LoadInitial)
            runCurrent()
            assertTrue(store.state.value.loaded, "the rows are shown before the refresh")
            assertFalse(store.state.value.headerRefreshing)
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
    fun resumeRefreshAfterAWhileShowsTheHeaderIndicator() = runTest {
        val gates = Gates()
        val store = store(gates)
        wallMillis += ActivityHeaderRefreshStaleAfterMillis

        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()

        assertTrue(store.state.value.refreshing)
        assertFalse(store.state.value.pullRefreshing)
        assertTrue(store.state.value.headerRefreshing)
        gates.gate(2).complete(true)
        runCurrent()
        assertFalse(store.state.value.headerRefreshing)
    }

    @Test
    fun resumeAfterALongSuspensionShowsItEvenWhenRealtimeRefreshedBeforeIt() = runTest {
        val gates = Gates()
        val store = store(gates)
        wallMillis += 30_000L
        startRealtimeReconcile(store)
        gates.gate(2).complete(true)
        runCurrent()
        wallMillis += 104 * 60_000L

        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()

        assertTrue(store.state.value.headerRefreshing)
        gates.gate(3).complete(true)
        runCurrent()
        assertFalse(store.state.value.headerRefreshing)
    }

    @Test
    fun reEntryRightAfterRowsWereReadRefreshesSilently() = runTest {
        val gates = Gates()
        val store = store(gates)
        wallMillis += ActivityHeaderRefreshStaleAfterMillis - 1L

        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()

        assertTrue(store.state.value.refreshing, "the refresh still runs")
        assertFalse(store.state.value.headerRefreshing)
        gates.gate(2).complete(true)
        runCurrent()
        assertFalse(store.state.value.refreshing)
    }

    @Test
    fun realtimeBackgroundRefreshShowsNoIndicator() = runTest {
        val gates = Gates()
        val store = store(gates)
        wallMillis += 10 * ActivityHeaderRefreshStaleAfterMillis

        startRealtimeReconcile(store)

        assertTrue(store.state.value.backgroundRefreshing, "the reconcile is on the network")
        assertFalse(store.state.value.refreshing, "the page keeps its rows and state")
        assertFalse(store.state.value.pullRefreshing)
        assertFalse(store.state.value.headerRefreshing)
        gates.gate(2).complete(true)
        runCurrent()
        assertFalse(store.state.value.backgroundRefreshing)
        assertFalse(store.state.value.headerRefreshing)
    }

    @Test
    fun failedBackgroundRefreshStaysOutOfThePage() = runTest {
        val gates = Gates()
        val store = store(gates)

        startRealtimeReconcile(store)
        gates.gate(2).complete(false)
        runCurrent()

        assertFalse(store.state.value.backgroundRefreshing)
        assertFalse(store.state.value.headerRefreshing)
        assertEquals(null, store.state.value.errorCode, "a background failure stays out of the page")
    }

    @Test
    fun userPullShowsOnlyThePullSpinner() = runTest {
        val gates = Gates()
        val store = store(gates)
        wallMillis += 10 * ActivityHeaderRefreshStaleAfterMillis

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        assertTrue(store.state.value.pullRefreshing)
        assertFalse(store.state.value.headerRefreshing)
        gates.gate(2).complete(true)
        runCurrent()
        assertFalse(store.state.value.pullRefreshing)
        assertFalse(store.state.value.headerRefreshing)
    }

    @Test
    fun userPullJoiningAResumeRefreshHandsTheChromeToThePullSpinner() = runTest {
        val gates = Gates()
        val store = store(gates)
        wallMillis += ActivityHeaderRefreshStaleAfterMillis
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertTrue(store.state.value.headerRefreshing)

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        assertTrue(store.state.value.pullRefreshing, "the pull joined the resume refresh")
        assertFalse(store.state.value.headerRefreshing, "never two spinners")
        gates.gate(2).complete(true)
        runCurrent()
        assertFalse(store.state.value.pullRefreshing)
        assertFalse(store.state.value.headerRefreshing)
    }

    @Test
    fun userPullOverAnInFlightReconcileShowsOnlyThePull() = runTest {
        val gates = Gates()
        val store = store(gates)
        startRealtimeReconcile(store)

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()

        assertTrue(store.state.value.pullRefreshing)
        assertFalse(store.state.value.backgroundRefreshing, "the pull superseded the reconcile")
        assertFalse(store.state.value.headerRefreshing)
        gates.gate(3).complete(true)
        runCurrent()
        assertFalse(store.state.value.pullRefreshing)
        assertFalse(store.state.value.headerRefreshing)
    }

    @Test
    fun headerRefreshingNeedsAnAllowedForegroundRefreshAndNoPull() {
        assertTrue(SharedActivityState(refreshing = true, headerRefreshAllowed = true).headerRefreshing)
        assertFalse(SharedActivityState(refreshing = true).headerRefreshing, "a corrective refresh")
        assertFalse(SharedActivityState(backgroundRefreshing = true, headerRefreshAllowed = true).headerRefreshing)
        assertFalse(
            SharedActivityState(refreshing = true, pullRefreshing = true, headerRefreshAllowed = true).headerRefreshing
        )
        assertFalse(SharedActivityState().headerRefreshing)
    }

    private companion object {
        const val WallStart = 1_000_000_000L
    }
}
