package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

        override suspend fun loadTurns(
            priority: DataRequestPriority,
            agentId: MemberId,
            before: String?,
            limit: Int,
        ): DataResult<AgentActivityTurnPage> {
            val call = Call(priority, agentId, before)
            calls += call
            return call.result.await()
        }

        override suspend fun loadReply(priority: DataRequestPriority, messageId: MessageId): AgentActivityTurnReply {
            replyCalls += priority to messageId
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
    fun opensOncePerAgentWhileLoading() = runTest {
        val repository = FakeRepository()
        val store = store(repository)
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        assertEquals(1, repository.calls.size, "a repeated Open while the first load runs must not refetch")
        assertEquals(AgentActivityOverviewStatus.Loading, store.state.value.status)
        assertEquals(DataRequestPriority.Visible, repository.calls[0].priority)
        assertEquals(null, repository.calls[0].before)
    }

    @Test
    fun repeatedOpenAfterTabRevisitReloadsSilentlyOnce() = runTest {
        val repository = FakeRepository()
        val store = openLoaded(repository, page(turn("t1", 300), next = "300:t1"))
        store.dispatch(AgentActivityOverviewIntent.LiveActivityObserved(agent, "working"))

        // The pager dropped the tab and composes it again: Open repeats, and the agent finished
        // meanwhile. Both ask for the same debounced reload.
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        store.dispatch(AgentActivityOverviewIntent.LiveActivityObserved(agent, "online"))
        runCurrent()
        assertEquals(1, repository.calls.size, "the revisit reload is debounced")
        passDebounce()
        assertEquals(2, repository.calls.size)
        assertEquals(DataRequestPriority.Background, repository.calls[1].priority, "content is on screen: silent")
        assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status, "no loading flash")

        repository.calls[1].result.complete(page(turn("t2", 400), turn("t1", 300), next = "300:t1"))
        passDebounce()
        assertEquals(listOf("t2", "t1"), keys(store))
        assertEquals(2, repository.calls.size)
    }

    @Test
    fun loadMorePagesWithTheCursorSingleFlight() = runTest {
        val repository = FakeRepository()
        val store = openLoaded(repository, page(turn("t1", 300), turn("t2", 200), next = "200:t2"))
        assertEquals("200:t2", store.state.value.next)

        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        assertEquals(2, repository.calls.size, "load more is single-flight")
        assertEquals("200:t2", repository.calls[1].before)
        assertEquals(true, store.state.value.loadingMore)

        // A duplicate row across the page boundary is kept once.
        repository.calls[1].result.complete(page(turn("t2", 200), turn("t3", 100)))
        runCurrent()
        assertEquals(listOf("t1", "t2", "t3"), keys(store))
        assertEquals(null, store.state.value.next)
        assertEquals(false, store.state.value.loadingMore)

        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        assertEquals(2, repository.calls.size, "no cursor, no request")
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
    fun leavingABusyStateReloadsOnceAndBusyToBusyNever() = runTest {
        val repository = FakeRepository()
        val store = openLoaded(repository, page(turn("t1", 300), next = "300:t1"))

        // The first observation is a baseline, not a change; working <-> thinking is the same turn.
        listOf("working", "thinking", "working", "thinking").forEach {
            store.dispatch(AgentActivityOverviewIntent.LiveActivityObserved(agent, it))
        }
        // Another agent's status is not this overview's.
        store.dispatch(AgentActivityOverviewIntent.LiveActivityObserved(MemberId("agent-2"), "online"))
        passDebounce()
        assertEquals(1, repository.calls.size, "no reload while the agent stays busy")

        store.dispatch(AgentActivityOverviewIntent.LiveActivityObserved(agent, "online"))
        advanceTimeBy(SharedAgentActivityOverviewStore.LIVE_RELOAD_DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals(1, repository.calls.size, "still inside the debounce window")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, repository.calls.size, "busy -> idle reloads")
        assertEquals(DataRequestPriority.Background, repository.calls[1].priority)

        // Idle -> idle (or a status detail change) is not a finished turn.
        store.dispatch(AgentActivityOverviewIntent.LiveActivityObserved(agent, "online"))
        store.dispatch(AgentActivityOverviewIntent.LiveActivityObserved(agent, "offline"))
        repository.calls[1].result.complete(page(turn("t0", 400), turn("t1", 300), next = "300:t1"))
        passDebounce()
        assertEquals(2, repository.calls.size, "exactly one reload per busy -> idle edge")
        assertEquals(listOf("t0", "t1"), keys(store))
    }

    @Test
    fun reloadsAskedDuringAReloadAreCoalescedAtTheMostUrgentPriority() = runTest {
        val repository = FakeRepository()
        val store = openLoaded(repository, page(turn("t1", 300)))
        store.dispatch(AgentActivityOverviewIntent.LiveActivityObserved(agent, "working"))
        store.dispatch(AgentActivityOverviewIntent.LiveActivityObserved(agent, "online"))
        passDebounce()
        assertEquals(DataRequestPriority.Background, repository.calls[1].priority)

        // While that reload is in flight, a Refresh is queued once and runs after it.
        store.dispatch(AgentActivityOverviewIntent.Refresh)
        store.dispatch(AgentActivityOverviewIntent.Refresh)
        runCurrent()
        assertEquals(2, repository.calls.size)
        repository.calls[1].result.complete(page(turn("t0", 400), turn("t1", 300)))
        runCurrent()
        assertEquals(3, repository.calls.size)
        assertEquals(DataRequestPriority.Visible, repository.calls[2].priority)
        repository.calls[2].result.complete(page(turn("t0", 400), turn("t1", 300)))
        passDebounce()
        assertEquals(listOf("t0", "t1"), keys(store))
        assertEquals(3, repository.calls.size)
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
}
