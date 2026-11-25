package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedAgentActivityOverviewStoreTest {
    private val agent = MemberId("agent-1")

    private fun turn(id: String, endedAt: Long) = AgentActivityTurn(
        id = id,
        startedAtEpochMillis = endedAt - 1_000,
        endedAtEpochMillis = endedAt,
        outcome = AgentActivityTurnOutcome.Completed,
        trigger = null,
        actions = emptyList(),
        toolCalls = 0,
        problems = emptyList(),
        problemCount = 0,
        messagesSent = 0,
        lastReplyMessageId = null,
    )

    private class Call(val priority: DataRequestPriority, val agentId: MemberId, val before: String?) {
        val result = CompletableDeferred<DataResult<AgentActivityTurnPage>>()
    }

    /** Each request waits until the test answers it, so interleavings are explicit. */
    private class FakeRepository : AgentActivityOverviewRepository {
        val calls = mutableListOf<Call>()
        val replyCalls = mutableListOf<Pair<DataRequestPriority, MessageId>>()

        /** Answers per reply id, in order; the last one repeats. Unscripted ids are unavailable. */
        val replies = mutableMapOf<MessageId, ArrayDeque<AgentActivityTurnReply>>()
        var loadTurnsThrows: (() -> Throwable?)? = null
        var loadReplyThrows: ((MessageId) -> Throwable?)? = null
        var loadOperatorEventsThrows: (() -> Throwable?)? = null

        override suspend fun loadTurns(
            priority: DataRequestPriority,
            agentId: MemberId,
            before: String?,
            limit: Int,
        ): DataResult<AgentActivityTurnPage> {
            val call = Call(priority, agentId, before)
            calls += call
            loadTurnsThrows?.invoke()?.let { throw it }
            return call.result.await()
        }

        /** `since` per operator-events request, in order. */
        val operatorEventCalls = mutableListOf<Long?>()

        /** Answers per operator-events request, in order; the last one repeats. None: an older server (404). */
        val operatorEventAnswers = ArrayDeque<DataResult<AgentOperatorEventsPage>>()

        override suspend fun loadOperatorEvents(
            priority: DataRequestPriority,
            agentId: MemberId,
            sinceEpochMillis: Long?,
        ): DataResult<AgentOperatorEventsPage> {
            operatorEventCalls += sinceEpochMillis
            loadOperatorEventsThrows?.invoke()?.let { throw it }
            return when {
                operatorEventAnswers.size > 1 -> operatorEventAnswers.removeFirst()
                operatorEventAnswers.isNotEmpty() -> operatorEventAnswers.first()
                else -> DataResult.Error(ScreenError("operator_events_404", retryable = false))
            }
        }

        override suspend fun loadReply(priority: DataRequestPriority, messageId: MessageId): AgentActivityTurnReply {
            replyCalls += priority to messageId
            loadReplyThrows?.invoke(messageId)?.let { throw it }
            val answers = replies[messageId] ?: return AgentActivityTurnReply.Unavailable
            return if (answers.size > 1) answers.removeFirst() else answers.first()
        }
    }

    private fun TestScope.store(repository: FakeRepository) =
        SharedAgentActivityOverviewStore(repository, backgroundScope)

    private fun page(vararg turns: AgentActivityTurn, next: String? = null) =
        DataResult.Content(AgentActivityTurnPage(turns.toList(), next))

    private fun keys(store: SharedAgentActivityOverviewStore) = store.state.value.turns.map { it.key }

    /** Opens [agent] and answers the first page with [first]. */
    private fun TestScope.openLoaded(
        repository: FakeRepository,
        first: DataResult<AgentActivityTurnPage>,
    ): SharedAgentActivityOverviewStore {
        val store = store(repository)
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        repository.calls.last().result.complete(first)
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status)
        return store
    }

    /** Past the live-reload debounce; also runs the store's backgroundScope work, which `advanceUntilIdle` skips. */
    private fun TestScope.passDebounce() {
        advanceTimeBy(SharedAgentActivityOverviewStore.LIVE_RELOAD_DEBOUNCE_MILLIS + 1)
        runCurrent()
    }

    @Test
    fun reloadDuringLoadMoreKeepsItSingleFlightAndAppendsItsPage() = runTest {
        val repository = FakeRepository()
        val store = openLoaded(repository, page(turn("t1", 300), turn("t2", 200), next = "200:t2"))
        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        val loadMore = repository.calls[1]

        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        repository.calls[2].result.complete(page(turn("t0", 400), turn("t1", 300), next = "300:t1"))
        runCurrent()
        assertEquals(listOf("t0", "t1", "t2"), keys(store), "the reload keeps the loaded older turn")
        assertEquals("200:t2", store.state.value.next, "and the deeper cursor")
        assertEquals(true, store.state.value.loadingMore, "the reload must not drop the in-flight spinner")

        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        assertEquals(3, repository.calls.size, "still single-flight after the reload landed")

        loadMore.result.complete(page(turn("t3", 100)))
        runCurrent()
        assertEquals(listOf("t0", "t1", "t2", "t3"), keys(store))
        assertEquals(null, store.state.value.next)
        assertEquals(false, store.state.value.loadingMore)
    }

    @Test
    fun reloadMergesTheNewHeadOverLoadedPages() = runTest {
        val repository = FakeRepository()
        val store = openLoaded(repository, page(turn("t1", 400), turn("t2", 300), next = "300:t2"))
        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        repository.calls[1].result.complete(page(turn("t3", 200), turn("t4", 100), next = "100:t4"))
        runCurrent()
        assertEquals(listOf("t1", "t2", "t3", "t4"), keys(store))

        // A new turn shifts the latest page by one: t2 is its last row, t3 and t4 stay loaded.
        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        repository.calls[2].result.complete(page(turn("t0", 500), turn("t1", 400), turn("t2", 300), next = "300:t2"))
        runCurrent()
        assertEquals(listOf("t0", "t1", "t2", "t3", "t4"), keys(store))
        assertEquals("100:t4", store.state.value.next, "the deeper cursor survives")
    }

    @Test
    fun mergeReplacesWhenTheNewHeadDoesNotReachLoadedTurns() {
        val loaded = listOf(turn("t1", 300), turn("t2", 200), turn("t3", 100))
        // More turns finished than a page holds: splicing would leave a gap.
        val gap = AgentActivityTurnPage(listOf(turn("n1", 600), turn("n2", 500)), next = "500:n2")
        assertEquals(gap, mergeAgentActivityHead(loaded, "100:t3", gap))
        // The new page is the whole history.
        val whole = AgentActivityTurnPage(listOf(turn("t0", 400), turn("t1", 300)), next = null)
        assertEquals(whole, mergeAgentActivityHead(loaded, "100:t3", whole))
        // The new page ends on the last loaded row: nothing older to keep, its own cursor stands.
        val same = AgentActivityTurnPage(listOf(turn("t1", 300), turn("t2", 200), turn("t3", 100)), next = "100:t3")
        assertEquals(same, mergeAgentActivityHead(loaded, "100:t3", same))
        // An empty page is the server's answer.
        val empty = AgentActivityTurnPage(emptyList(), next = null)
        assertEquals(empty, mergeAgentActivityHead(loaded, "100:t3", empty))
    }

    @Test
    fun aFailedFirstLoadShowsErrorAndRetryRecovers() = runTest {
        val repository = FakeRepository()
        val store = store(repository)
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        repository.calls[0].result.complete(DataResult.Error(ScreenError("activity_summaries_500")))
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Error("activity_summaries_500"), store.state.value.status)

        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Loading, store.state.value.status, "Retry shows it is loading")
        repository.calls[1].result.complete(page(turn("t1", 300)))
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status)
        assertEquals(listOf("t1"), store.state.value.turns.map { it.key })

        // A failed reload after a success keeps what is shown (web keeps "loaded").
        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        repository.calls[2].result.complete(DataResult.NoPermission(PermissionBlocker("activity_summaries_403")))
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status)
        assertEquals(listOf("t1"), store.state.value.turns.map { it.key })
    }

    @Test
    fun switchingAgentsDropsLateAnswersAndRepliesLoadOnce() = runTest {
        val repository = FakeRepository()
        repository.replies[MessageId("r1")] = ArrayDeque(listOf(AgentActivityTurnReply.Loaded("done", 5L)))
        val store = store(repository)
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        store.dispatch(AgentActivityOverviewIntent.Open(MemberId("agent-2")))
        runCurrent()
        assertEquals(MemberId("agent-2"), repository.calls[1].agentId)
        repository.calls[0].result.complete(page(turn("stale", 999)))
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Loading, store.state.value.status, "agent-1's late page is dropped")
        repository.calls[1].result.complete(page(turn("t1", 300)))
        runCurrent()
        assertEquals(listOf("t1"), store.state.value.turns.map { it.key })

        store.dispatch(AgentActivityOverviewIntent.LoadReply(MessageId("r1")))
        store.dispatch(AgentActivityOverviewIntent.LoadReply(MessageId("r1")))
        store.dispatch(AgentActivityOverviewIntent.LoadReply(MessageId("hidden")))
        runCurrent()
        assertEquals(
            listOf(DataRequestPriority.Visible to MessageId("r1"), DataRequestPriority.Visible to MessageId("hidden")),
            repository.replyCalls
        )
        assertEquals(
            mapOf(
                MessageId("r1") to AgentActivityReplyState.Ready(AgentActivityTurnReply.Loaded("done", 5L)),
                MessageId("hidden") to AgentActivityReplyState.Ready(AgentActivityTurnReply.Unavailable)
            ),
            store.state.value.replies
        )
    }

    @Test
    fun aFailedReplyIsAskedAgainOnTheNextViewAndOnRefresh() = runTest {
        val repository = FakeRepository()
        val flaky = MessageId("flaky")
        repository.replies[flaky] = ArrayDeque(
            listOf(AgentActivityTurnReply.Failed, AgentActivityTurnReply.Failed, AgentActivityTurnReply.Loaded("ok", 7L))
        )
        val store = openLoaded(repository, page(turn("t1", 300)))

        store.dispatch(AgentActivityOverviewIntent.LoadReply(flaky))
        runCurrent()
        assertEquals(AgentActivityReplyState.Failed, store.state.value.replies[flaky], "not cached as unavailable")

        // The turn scrolls back into view.
        store.dispatch(AgentActivityOverviewIntent.LoadReply(flaky))
        runCurrent()
        assertEquals(2, repository.replyCalls.size)
        assertEquals(AgentActivityReplyState.Failed, store.state.value.replies[flaky])

        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        assertEquals(3, repository.replyCalls.size, "Refresh asks failed replies again")
        assertEquals(AgentActivityReplyState.Ready(AgentActivityTurnReply.Loaded("ok", 7L)), store.state.value.replies[flaky])

        store.dispatch(AgentActivityOverviewIntent.LoadReply(flaky))
        runCurrent()
        assertEquals(3, repository.replyCalls.size, "a loaded reply is cached")
    }

    @Test
    fun pullRefreshHoldsRefreshingAndKeepsTheLoadedTurnsWhenItFails() = runTest {
        // Each case: the reload's answer and the turns left on screen once it lands.
        val cases =
            listOf(
                "content" to (page(turn("t2", 2_000), turn("t1", 1_000)) to listOf("t2", "t1")),
                "error" to (DataResult.Error(ScreenError("activity_network")) to listOf("t1")),
            )
        cases.forEach { (label, case) ->
            val (answer, expectedKeys) = case
            val repository = FakeRepository()
            val store = openLoaded(repository, page(turn("t1", 1_000)))

            store.dispatch(AgentActivityOverviewIntent.Refresh)
            runCurrent()
            assertEquals(true, store.state.value.refreshing, label)
            assertEquals(listOf("t1"), keys(store), label)
            assertEquals(DataRequestPriority.Visible, repository.calls.last().priority, label)
            assertEquals(2, repository.calls.size, label)

            repository.calls.last().result.complete(answer)
            runCurrent()
            assertEquals(false, store.state.value.refreshing, label)
            assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status, label)
            assertEquals(expectedKeys, keys(store), label)
        }
    }

    @Test
    fun silentLiveReloadDoesNotReportRefreshing() = runTest {
        val repository = FakeRepository()
        val store = openLoaded(repository, page(turn("t1", 1_000)))

        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        passDebounce()

        assertEquals(2, repository.calls.size)
        assertEquals(DataRequestPriority.Background, repository.calls.last().priority)
        assertEquals(false, store.state.value.refreshing)
    }

    @Test
    fun theNewestPageCarriesTheRunningTurnAndItsWakeIsNotListedTwice() = runTest {
        val repository = FakeRepository()
        val running = turn("wake", 900).copy(running = true, outcome = AgentActivityTurnOutcome.Unknown)
        val store = openLoaded(
            repository,
            DataResult.Content(AgentActivityTurnPage(listOf(turn("wake", 700), turn("t1", 500)), next = "500:t1", current = running)),
        )
        assertEquals(running, store.state.value.running)
        // The empty wake the running work took over (same row id) shows only as the running turn.
        assertEquals(listOf("t1"), store.state.value.finishedTurns.map { it.key })

        // An older page never touches it.
        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        repository.calls.last().result.complete(page(turn("t0", 300)))
        runCurrent()
        assertEquals(running, store.state.value.running)

        // The turn finished: the next latest page has no `current`.
        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        repository.calls.last().result.complete(page(turn("wake", 950), turn("t1", 500), next = "500:t1"))
        runCurrent()
        assertEquals(null, store.state.value.running)
        assertEquals(listOf("wake", "t1", "t0"), store.state.value.finishedTurns.map { it.key })
    }

    private fun operatorEvent(id: String, at: Long, kind: AgentOperatorEventKind = AgentOperatorEventKind.Stop) =
        AgentOperatorEvent(id = id, atEpochMillis = at, kind = kind, actor = AgentOperatorEventActor.User("Artin"))

    private fun events(vararg events: AgentOperatorEvent) =
        DataResult.Content(AgentOperatorEventsPage(events.toList(), truncated = false))

    @Test
    fun operatorEventsFollowEveryLoadOfTheTurnsAndReachAsFarBackAsTheHeldTurns() = runTest {
        val repository = FakeRepository()
        repository.operatorEventAnswers += events(operatorEvent("e1", 150))
        // turn(id, endedAt) starts 1_000 before it ends.
        val store = openLoaded(repository, page(turn("t1", 3_000), turn("t2", 2_000), next = "2000:t2"))
        assertEquals(listOf<Long?>(1_000), repository.operatorEventCalls, "an earlier page is left: since the oldest held start")
        assertEquals(listOf("e1"), store.state.value.operatorEvents.map { it.id })

        // The last page: every event, with no lower bound.
        repository.operatorEventAnswers.clear()
        repository.operatorEventAnswers += events(operatorEvent("e2", 1_500), operatorEvent("e1", 150))
        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        repository.calls.last().result.complete(page(turn("t3", 500)))
        runCurrent()
        assertEquals(listOf<Long?>(1_000, null), repository.operatorEventCalls)
        assertEquals(listOf("e2", "e1"), store.state.value.operatorEvents.map { it.id })

        // A Stop that started no turn shows on the next reload of the turns too.
        repository.operatorEventAnswers.clear()
        repository.operatorEventAnswers += events(operatorEvent("e3", 3_500), operatorEvent("e2", 1_500), operatorEvent("e1", 150))
        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        repository.calls.last().result.complete(page(turn("t1", 3_000), turn("t2", 2_000), next = "2000:t2"))
        runCurrent()
        assertEquals(3, repository.operatorEventCalls.size)
        assertEquals(listOf("e3", "e2", "e1"), store.state.value.operatorEvents.map { it.id })
    }

    @Test
    fun aFailedOperatorEventsRequestKeepsWhatIsShownAndNeverFailsTheOverview() = runTest {
        val repository = FakeRepository()
        val store = openLoaded(repository, page(turn("t1", 3_000)))
        // An older server without the route: the overview shows the turns alone.
        assertEquals(1, repository.operatorEventCalls.size)
        assertEquals(emptyList<AgentOperatorEvent>(), store.state.value.operatorEvents)
        assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status)

        repository.operatorEventAnswers += events(operatorEvent("e1", 2_500))
        repository.operatorEventAnswers += DataResult.Error(ScreenError("operator_events_500"))
        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        repository.calls.last().result.complete(page(turn("t1", 3_000)))
        runCurrent()
        assertEquals(listOf("e1"), store.state.value.operatorEvents.map { it.id })

        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        repository.calls.last().result.complete(page(turn("t1", 3_000)))
        runCurrent()
        assertEquals(listOf("e1"), store.state.value.operatorEvents.map { it.id }, "a failed reload keeps the events shown")
    }

    @Test
    fun observedStoreCatchesUpOnForegroundReconnectAndNetworkRestoredWithoutBurningWindowOnFailure() = runTest {
        val repository = FakeRepository()
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        var clock = 1_000_000L
        val store =
            SharedAgentActivityOverviewStore(
                repository = repository,
                scope = backgroundScope,
                freshness = freshness,
                nowMillis = { clock },
                liveConnectionToken = { null },
            )
        val observer = Any()
        store.dispatch(AgentActivityOverviewIntent.ObserveFreshness(observer, serverId = "server-1"))
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        repository.calls[0].result.complete(page(turn("t1", 1_000)))
        runCurrent()
        assertEquals(listOf("t1"), keys(store))

        // 1. Foreground fails while offline: existing turns stay shown, refreshing stays false,
        //    and the 10 s coalescing window is NOT burned.
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        runCurrent()
        assertEquals(2, repository.calls.size)
        assertEquals(DataRequestPriority.Background, repository.calls[1].priority)
        assertEquals(false, store.state.value.refreshing, "silent catch-up does not show pull-to-refresh")
        repository.calls[1].result.complete(DataResult.Error(ScreenError("activity_offline")))
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status)
        assertEquals(listOf("t1"), keys(store), "failed catch-up keeps existing turns")

        // 2 s later (< 10 s coalesce window): NetworkRestored retries immediately and updates turns.
        clock += 2_000L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(3, repository.calls.size, "failed foreground must not block NetworkRestored")
        assertEquals(DataRequestPriority.Background, repository.calls[2].priority)
        repository.calls[2].result.complete(page(turn("t2", 2_000), turn("t1", 1_000)))
        runCurrent()
        assertEquals(listOf("t2", "t1"), keys(store))

        // Redundant NetworkRestored 1 s after a successful catch-up coalesces inside 10 s.
        clock += 1_000L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(3, repository.calls.size)

        // 2. Reconnect for another server is ignored; own-server new transport epoch refreshes even inside 10 s.
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-2", transportEpoch = 1L))
        runCurrent()
        assertEquals(3, repository.calls.size)

        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-1", transportEpoch = 2L))
        runCurrent()
        assertEquals(4, repository.calls.size)
        repository.calls[3].result.complete(page(turn("t3", 3_000), turn("t2", 2_000), turn("t1", 1_000)))
        runCurrent()
        assertEquals(listOf("t3", "t2", "t1"), keys(store))

        // Duplicate Reconnect for the same transport epoch (`rooms:joined` after `connected`) is ignored.
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-1", transportEpoch = 2L))
        runCurrent()
        assertEquals(4, repository.calls.size)
    }

    @Test
    fun midFlightNetworkRestoredDuringFailingForegroundCatchUpFiresFollowUpImmediately() = runTest {
        val repository = FakeRepository()
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        var clock = 1_000_000L
        val store =
            SharedAgentActivityOverviewStore(
                repository = repository,
                scope = backgroundScope,
                freshness = freshness,
                nowMillis = { clock },
                liveConnectionToken = { null },
            )
        store.dispatch(AgentActivityOverviewIntent.ObserveFreshness(Any(), serverId = "server-1"))
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        repository.calls[0].result.complete(page(turn("t1", 1_000)))
        runCurrent()

        // Foreground starts and hangs; NetworkRestored arrives mid-flight -> latched (single-flight).
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        runCurrent()
        assertEquals(2, repository.calls.size)

        clock += 1_500L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(2, repository.calls.size, "single-flight latches NetworkRestored while Foreground is running")

        // Hanging Foreground fails -> latched NetworkRestored fires immediately and recovers.
        clock += 500L
        repository.calls[1].result.complete(DataResult.Error(ScreenError("activity_timeout")))
        runCurrent()
        assertEquals(3, repository.calls.size, "latched NetworkRestored fires once failing Foreground finishes")
        assertEquals(listOf("t1"), keys(store), "existing turns remain shown during recovery")

        repository.calls[2].result.complete(page(turn("t2", 2_000), turn("t1", 1_000)))
        runCurrent()
        assertEquals(listOf("t2", "t1"), keys(store))
    }

    @Test
    fun unobservedOrHiddenStoreDoesNotTriggerSpuriousCatchUpRefreshes() = runTest {
        val repository = FakeRepository()
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        var clock = 1_000_000L
        val store =
            SharedAgentActivityOverviewStore(
                repository = repository,
                scope = backgroundScope,
                freshness = freshness,
                nowMillis = { clock },
                liveConnectionToken = { null },
            )
        // Loaded without any registered freshness observer -> events must be ignored.
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        repository.calls[0].result.complete(page(turn("t1", 1_000)))
        runCurrent()

        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-1", transportEpoch = 1L))
        runCurrent()
        assertEquals(1, repository.calls.size, "unsubscribed store must not trigger catch-up")

        // Observe then cover page (`SetPageVisible(false)`): no refresh while hidden, catches up once visible again.
        val observer = Any()
        store.dispatch(AgentActivityOverviewIntent.ObserveFreshness(observer, serverId = "server-1"))
        store.dispatch(AgentActivityOverviewIntent.SetPageVisible(false))
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        runCurrent()
        assertEquals(1, repository.calls.size, "covered page must not fetch while hidden")

        store.dispatch(AgentActivityOverviewIntent.SetPageVisible(true))
        runCurrent()
        assertEquals(2, repository.calls.size, "held freshness event fires once page becomes visible")
        repository.calls[1].result.complete(page(turn("t2", 2_000), turn("t1", 1_000)))
        runCurrent()
        assertEquals(listOf("t2", "t1"), keys(store))

        // Unsubscribe observer (switched to Settings tab) -> subsequent freshness events trigger nothing.
        store.dispatch(AgentActivityOverviewIntent.StopObservingFreshness(observer))
        clock += RAFT_FRESHNESS_COALESCE_MILLIS + 1L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-1", transportEpoch = 2L))
        runCurrent()
        assertEquals(2, repository.calls.size, "unsubscribed store must not trigger catch-up")
    }

    @Test
    fun failedInitialOverviewLoadRecoversOnFreshnessSignal() = runTest {
        val repository = FakeRepository()
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        val store =
            SharedAgentActivityOverviewStore(
                repository = repository,
                scope = backgroundScope,
                freshness = freshness,
                nowMillis = { 1_000_000L },
                liveConnectionToken = { null },
            )
        store.dispatch(AgentActivityOverviewIntent.ObserveFreshness(Any(), serverId = "server-1"))
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        repository.calls[0].result.complete(DataResult.Error(ScreenError("activity_offline")))
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Error("activity_offline"), store.state.value.status)

        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(2, repository.calls.size)
        assertEquals(AgentActivityOverviewStatus.Loading, store.state.value.status)
        repository.calls[1].result.complete(page(turn("t1", 1_000)))
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status)
        assertEquals(listOf("t1"), keys(store))
    }

    @Test
    fun snapshotCacheSeedsFrameZeroAndRefreshesSilentlyWithIsolationAndEviction() = runTest {
        val cache = AgentActivityOverviewSnapshotCache(maxEntries = 2)
        val keyFor: (String, String, MemberId) -> AgentActivityOverviewCacheKey? = { account, server, id ->
            agentActivityOverviewCacheKey("https://api.example", account, server, id)
        }
        val repo1 = FakeRepository()
        val r1 = MessageId("r1")
        repo1.replies[r1] = ArrayDeque(listOf(AgentActivityTurnReply.Loaded("warm reply", 10L)))
        repo1.operatorEventAnswers += events(operatorEvent("e1", 900))
        val first =
            SharedAgentActivityOverviewStore(
                repository = repo1,
                scope = backgroundScope,
                snapshotCache = cache,
                cacheKeyFor = { keyFor("user-1", "srv-1", it) },
                initialAgentId = agent,
            )
        assertEquals(AgentActivityOverviewStatus.Idle, first.state.value.status)
        first.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        repo1.calls[0].result.complete(page(turn("t1", 1_000), next = "1000:t1"))
        runCurrent()
        first.dispatch(AgentActivityOverviewIntent.LoadReply(r1))
        runCurrent()
        assertEquals(AgentActivityReplyState.Ready(AgentActivityTurnReply.Loaded("warm reply", 10L)), first.state.value.replies[r1])

        // Reopening the same agent on a fresh store renders warm turns, events, and replies on Frame 0.
        val repo2 = FakeRepository()
        val reopened =
            SharedAgentActivityOverviewStore(
                repository = repo2,
                scope = backgroundScope,
                snapshotCache = cache,
                cacheKeyFor = { keyFor("user-1", "srv-1", it) },
                initialAgentId = agent,
            )
        assertEquals(AgentActivityOverviewStatus.Loaded, reopened.state.value.status)
        assertEquals(listOf("t1"), keys(reopened))
        assertEquals(listOf("e1"), reopened.state.value.operatorEvents.map { it.id })
        assertEquals(AgentActivityReplyState.Ready(AgentActivityTurnReply.Loaded("warm reply", 10L)), reopened.state.value.replies[r1])

        // Open refreshes silently in the background without dropping warm turns or flipping refreshing.
        reopened.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        assertEquals(1, repo2.calls.size)
        assertEquals(DataRequestPriority.Background, repo2.calls[0].priority)
        assertFalse(reopened.state.value.refreshing)
        assertEquals(AgentActivityOverviewStatus.Loaded, reopened.state.value.status)
        repo2.calls[0].result.complete(page(turn("t2", 2_000), turn("t1", 1_000)))
        runCurrent()
        assertEquals(listOf("t2", "t1"), keys(reopened))

        // Different account, server, or agent stays strictly isolated.
        val otherAccount =
            SharedAgentActivityOverviewStore(
                repository = FakeRepository(),
                scope = backgroundScope,
                snapshotCache = cache,
                cacheKeyFor = { keyFor("user-2", "srv-1", it) },
                initialAgentId = agent,
            )
        assertEquals(AgentActivityOverviewStatus.Idle, otherAccount.state.value.status)
        val otherServer =
            SharedAgentActivityOverviewStore(
                repository = FakeRepository(),
                scope = backgroundScope,
                snapshotCache = cache,
                cacheKeyFor = { keyFor("user-1", "srv-2", it) },
                initialAgentId = agent,
            )
        assertEquals(AgentActivityOverviewStatus.Idle, otherServer.state.value.status)

        // NoPermission on refresh evicts the cached snapshot so the next open starts clean.
        reopened.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        repo2.calls[1].result.complete(DataResult.NoPermission(PermissionBlocker("forbidden")))
        runCurrent()
        assertNull(cache.peek(keyFor("user-1", "srv-1", agent)!!))

        // LRU cap, evictAgent, dropServer, and clear behave cleanly.
        val snap = AgentActivityOverviewCachedSnapshot(agent, listOf(turn("t1", 1_000)), null, null, emptyList(), emptyMap())
        val ka = keyFor("user-1", "srv-1", MemberId("a"))!!
        val kb = keyFor("user-1", "srv-1", MemberId("b"))!!
        val kc = keyFor("user-1", "srv-2", MemberId("c"))!!
        cache.put(ka, snap)
        cache.put(kb, snap)
        cache.put(kc, snap)
        assertNull(cache.peek(ka), "oldest LRU entry evicted at maxEntries=2")
        assertNotNull(cache.peek(kb))
        cache.evictAgent("srv-1", MemberId("b"))
        assertNull(cache.peek(kb))
        assertNotNull(cache.peek(kc))
        cache.dropServer("srv-2")
        assertNull(cache.peek(kc))
        cache.put(ka, snap)
        cache.clear()
        assertNull(cache.peek(ka))
    }

    @Test
    fun loadMoreLoadReplyAndOperatorEventsExceptionsSettleCleanlyWithoutStickingInLoading() = runTest {
        val repository = FakeRepository()
        repository.operatorEventAnswers += events(operatorEvent("e1", 500))
        val store = openLoaded(repository, page(turn("t1", 2_000), next = "2000:t1"))

        // 1. loadMore exception must reset loadingMore = false so retry works instead of sticking forever.
        var failLoadMore = true
        repository.loadTurnsThrows = {
            if (failLoadMore) {
                failLoadMore = false
                IllegalStateException("boom loadMore")
            } else {
                null
            }
        }
        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        assertFalse(store.state.value.loadingMore, "loadMore must settle to false when repository throws")
        assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status)
        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        assertTrue(store.state.value.loadingMore)
        repository.calls.last().result.complete(page(turn("t2", 1_000)))
        runCurrent()
        assertFalse(store.state.value.loadingMore)
        assertEquals(listOf("t1", "t2"), keys(store))

        // 2. loadReply exception must settle to Failed (never stuck in Loading) and allow retry.
        val msgId = MessageId("reply-boom")
        var failReply = true
        repository.loadReplyThrows = { id ->
            if (id == msgId && failReply) {
                failReply = false
                RuntimeException("boom reply")
            } else {
                null
            }
        }
        repository.replies[msgId] = ArrayDeque(listOf(AgentActivityTurnReply.Loaded("recovered", 42L)))
        store.dispatch(AgentActivityOverviewIntent.LoadReply(msgId))
        runCurrent()
        assertEquals(AgentActivityReplyState.Failed, store.state.value.replies[msgId])

        store.dispatch(AgentActivityOverviewIntent.LoadReply(msgId))
        runCurrent()
        assertEquals(AgentActivityReplyState.Ready(AgentActivityTurnReply.Loaded("recovered", 42L)), store.state.value.replies[msgId])

        // 3. loadOperatorEvents exception must not crash or drop previously shown events.
        repository.loadOperatorEventsThrows = { IllegalStateException("boom operator events") }
        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        repository.calls.last().result.complete(page(turn("t1", 2_000)))
        runCurrent()
        assertFalse(store.state.value.refreshing)
        assertEquals(listOf("e1"), store.state.value.operatorEvents.map { it.id })
    }
}
