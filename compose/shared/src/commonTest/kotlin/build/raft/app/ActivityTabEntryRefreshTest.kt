package build.raft.app

import build.raft.app.data.ActivityRefreshCoalesceWindowMillis
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxDoneFrontier
import build.raft.app.data.InboxDoneResult
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxRepository
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.SharedActivityStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.activityItem
import build.raft.app.data.activityRefreshJoinsInFlightLoad
import build.raft.app.data.activityStore
import build.raft.app.data.sampleSession
import build.raft.app.data.snapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every entry into the kept-alive Activity tab fetches once, as a quiet refresh that keeps the
 * list; nothing fires while another tab is shown (artin 2026-10-21, Hands 679ef9ce).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityTabEntryRefreshTest {

    @Test
    fun everyEntryRefreshesOnceKeepingTheListAndNothingFiresWhileHidden() = runTest {
        val inbox = SlowInbox(latencyMillis = 1_000)
        val (store, logs) = store(inbox)
        // Activity is the cold-start tab: the mount-time LoadInitial is the only load.
        val panel = Panel(store, initiallyActive = true)
        panel.mount()
        settle()
        assertEquals(1, inbox.started)

        repeat(3) { round ->
            panel.setActive(false)
            // Shell pageDidAppear ticks (app resume, a page popped over another tab) while hidden.
            panel.routeVisibleTick(tick = round + 2)
            advanceTimeBy(ActivityRefreshCoalesceWindowMillis * 3)
            runCurrent()
            assertEquals(round + 1, inbox.started, "hidden round=$round")

            panel.setActive(true)
            panel.setActive(true) // a recomposition with the same value is not another entry
            runCurrent()
            assertEquals(round + 2, inbox.started, "entry round=$round")
            val during = store.state.value
            assertTrue(during.items.isNotEmpty(), "list kept during the entry refresh, round=$round")
            assertFalse(during.loading, "no full-screen loading, round=$round")
            assertFalse(during.pullRefreshing, "no pull-refresh chrome, round=$round")
            settle()
            assertFalse(store.state.value.refreshing, "round=$round")
        }
        assertEquals(0, logs.count { "phase=abandoned" in it }, logs.joinToString("\n"))
    }

    @Test
    fun aPanelComposedHiddenRefreshesOnItsFirstEntry() = runTest {
        // Kept alive / rebuilt by a server switch while another tab is selected.
        val inbox = SlowInbox(latencyMillis = 500)
        val (store, _) = store(inbox)
        val panel = Panel(store, initiallyActive = false)
        panel.mount()
        advanceTimeBy(ActivityRefreshCoalesceWindowMillis * 2)
        runCurrent()
        assertEquals(1, inbox.started)

        panel.setActive(true)
        runCurrent()
        assertEquals(2, inbox.started)
    }

    @Test
    fun theSameFrameEntryBurstJoinsOneLoadInsteadOfCancellingIt() = runTest {
        // Session read suspends before the load publishes refreshing=true, as on device.
        val inbox = SlowInbox(latencyMillis = 1_000)
        val (store, logs) = store(inbox, sessionReadMillis = 20)
        val panel = Panel(store, initiallyActive = false)
        panel.mount()
        settle()
        val before = inbox.started
        logs.clear()

        // One entry: shell SetSelectedTab -> Refresh, the active edge, the stale pageDidAppear tick.
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        panel.setActive(true)
        runCurrent()
        panel.routeVisibleTick(tick = 7)
        settle()

        assertEquals(before + 1, inbox.started)
        assertEquals(0, logs.count { "phase=abandoned" in it }, logs.joinToString("\n"))
        assertEquals(2, logs.count { "phase=coalesced" in it }, logs.joinToString("\n"))
    }

    @Test
    fun aLoadStartedWhileHiddenAndStuckOnASlowNetworkDoesNotSwallowTheEntry() = runTest {
        // Hands 679ef9ce: a server switch rebuilt the hidden tab, its LoadInitial hung ~9.4s, and
        // the entries at +3.8s and +8.1s fetched nothing until the user pulled.
        val inbox = SlowInbox(latencyMillis = 1_000)
        val (store, logs) = store(inbox)
        val panel = Panel(store, initiallyActive = false)
        panel.mount()
        settle()
        assertEquals(1, inbox.started)

        inbox.latencyMillis = 10_000
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(2, inbox.started)

        inbox.latencyMillis = 1_000
        advanceTimeBy(3_800)
        panel.setActive(true)
        runCurrent()

        assertEquals(3, inbox.started, logs.joinToString("\n"))
        assertEquals(1, logs.count { "phase=supersede_stale" in it }, logs.joinToString("\n"))
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(store.state.value.refreshing)
        assertEquals(listOf("channel-3"), store.state.value.items.map { it.channelId.value })
    }

    @Test
    fun resumeOnTheVisibleActivityTabRefreshesExactlyOnce() = runTest {
        val inbox = SlowInbox(latencyMillis = 1_000)
        val (store, logs) = store(inbox, sessionReadMillis = 20)
        val panel = Panel(store, initiallyActive = true)
        panel.mount()
        settle()
        val before = inbox.started

        panel.setActive(false) // app to background: the shell page disappears
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(before, inbox.started, "nothing while in the background")
        logs.clear()

        // Resume, one frame: the shell's visible-destination Refresh, the active edge, the tick.
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        panel.setActive(true)
        panel.routeVisibleTick(tick = 2)
        settle()

        assertEquals(before + 1, inbox.started)
        assertEquals(0, logs.count { "phase=abandoned" in it }, logs.joinToString("\n"))
        assertEquals(2, logs.count { "phase=coalesced" in it }, logs.joinToString("\n"))
    }

    @Test
    fun resumeDoesNotJoinALoadThatWasInFlightWhenTheAppWentToTheBackground() = runTest {
        // The monotonic clock does not advance while the device sleeps, so a load stuck across a
        // background can look younger than the join window on resume.
        val inbox = SlowInbox(latencyMillis = 1_000)
        val (store, logs) = store(inbox)
        val panel = Panel(store, initiallyActive = true)
        panel.mount()
        settle()

        inbox.latencyMillis = 30_000
        store.dispatch(SharedActivityIntent.Refresh) // e.g. a realtime-triggered visible refresh
        runCurrent()
        assertEquals(2, inbox.started)
        panel.setActive(false)
        advanceTimeBy(500)
        inbox.latencyMillis = 1_000
        panel.setActive(true)
        runCurrent()

        assertEquals(3, inbox.started, logs.joinToString("\n"))
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("channel-3"), store.state.value.items.map { it.channelId.value })
    }

    @Test
    fun resumeWhileAnotherTabIsShownFiresNothing() = runTest {
        val inbox = SlowInbox(latencyMillis = 1_000)
        val (store, _) = store(inbox)
        val panel = Panel(store, initiallyActive = false) // kept alive behind Home
        panel.mount()
        settle()
        val before = inbox.started

        // Background and resume with Home selected: Activity's active never changes.
        panel.setActive(false)
        panel.routeVisibleTick(tick = 2)
        panel.routeVisibleTick(tick = 3)
        settle()

        assertEquals(before, inbox.started)
    }

    @Test
    fun aVisibleRefreshJoinsOnlyAFreshForegroundResetLoad() {
        val window = ActivityRefreshCoalesceWindowMillis
        val cases =
            listOf(
                null to false, // nothing in flight, or a LoadMore / background reconcile
                0L to true,
                (window - 1) to true,
                window to false,
                9_400L to false,
            )
        cases.forEach { (age, joins) ->
            assertEquals(joins, activityRefreshJoinsInFlightLoad(age, window), "age=$age")
        }
    }

    /**
     * Both platforms run the same shell: Android's Compose bar and rail and iOS's native
     * UITabBar (taps come back through RaftNativeTabBarBridge.selectTab) all change the shell's
     * selected tab, and the keep-alive tab pager places Activity from that selection. Covering
     * the shell (a pushed thread) clears parentVisible on both.
     */
    @Test
    fun everySelectionPathTogglesActivityContentActiveOncePerEntry() {
        data class Step(val selectedTab: String, val parentVisible: Boolean = true)
        val paths =
            mapOf(
                "tab switch" to listOf(Step("home"), Step("activity"), Step("more"), Step("activity")),
                "cold start on Activity" to listOf(Step("activity"), Step("home"), Step("activity")),
                "pushed page over Activity" to
                    listOf(Step("activity"), Step("activity", parentVisible = false), Step("activity")),
                "rail to Members and back" to listOf(Step("home"), Step("activity"), Step("members"), Step("activity")),
                // App background/resume: the host lifecycle pauses the shell pager (Android
                // Activity onPause, iOS ComposeUIViewController lifecycle), clearing parentVisible.
                "background and resume on Activity" to
                    listOf(Step("activity"), Step("activity", parentVisible = false), Step("activity")),
                "background and resume on Home" to
                    listOf(Step("home"), Step("home", parentVisible = false), Step("home")),
            )
        val expectedEntries =
            mapOf(
                "tab switch" to 2,
                "cold start on Activity" to 1,
                "pushed page over Activity" to 1,
                "rail to Members and back" to 2,
                "background and resume on Activity" to 1,
                "background and resume on Home" to 0,
            )
        paths.forEach { (name, steps) ->
            fun activityActive(step: Step): Boolean =
                appShellChildTabContentActive(
                    parentVisible = step.parentVisible,
                    placedActive = appShellActiveChildPagerTab(step.selectedTab) == "activity",
                )
            val edge = ActivityTabEntryRefreshEdge(initiallyActive = activityActive(steps.first()))
            val entries =
                steps.drop(1).count { step -> edge.onActiveChanged(activityActive(step)) == ActivityTabActiveEdge.Entered }
            assertEquals(expectedEntries.getValue(name), entries, name)
        }
    }

    /** Loads run in backgroundScope, which advanceUntilIdle does not wait for. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    /** The panel's two refresh effects (SharedHomeActivityScreen), driven without composition. */
    private class Panel(private val store: SharedActivityStore, initiallyActive: Boolean) {
        private val edge = ActivityTabEntryRefreshEdge(initiallyActive)
        private var active = initiallyActive

        fun mount() = store.dispatch(SharedActivityIntent.LoadInitial)

        fun setActive(value: Boolean) {
            active = value
            when (edge.onActiveChanged(value)) {
                ActivityTabActiveEdge.Entered -> store.dispatch(SharedActivityIntent.Refresh)
                ActivityTabActiveEdge.Left -> store.dispatch(SharedActivityIntent.SurfaceHidden)
                ActivityTabActiveEdge.None -> Unit
            }
        }

        fun routeVisibleTick(tick: Int) {
            if (activityPageDidAppearShouldRefresh(tick, active = active)) {
                store.dispatch(SharedActivityIntent.Refresh)
            }
        }
    }

    private fun TestScope.store(
        inbox: InboxRepository,
        sessionReadMillis: Long = 0,
    ): Pair<SharedActivityStore, MutableList<String>> {
        val logs = mutableListOf<String>()
        val auth = SlowSessionStore(InMemoryRaftAuthSessionStore(sampleSession()), sessionReadMillis)
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                authStore = auth,
                nowMillis = { testScheduler.currentTime },
                diagnosticsLogger = { logs += it },
            )
        return store to logs
    }

    private class SlowInbox(var latencyMillis: Long) : InboxRepository {
        var started = 0
            private set

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            val load = ++started
            delay(latencyMillis)
            return DataResult.Content(snapshot(listOf(activityItem("channel-$load"))))
        }

        override suspend fun loadMore(
            serverId: ServerId,
            filter: InboxFilter,
            offset: Int,
            limit: Int
        ): DataResult<InboxSnapshot> = DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
            DataResult.Content(InboxDoneResult(channelId))
    }

    private class SlowSessionStore(
        private val delegate: InMemoryRaftAuthSessionStore,
        private val readMillis: Long,
    ) : RaftAuthSessionStore by delegate {
        override suspend fun readSession(): StoredAuthSession? {
            if (readMillis > 0) delay(readMillis)
            return delegate.readSession()
        }
    }
}
