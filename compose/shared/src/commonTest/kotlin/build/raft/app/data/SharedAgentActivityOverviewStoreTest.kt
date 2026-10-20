package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
        val replies = mutableMapOf<MessageId, AgentActivityTurnReply>()

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
            return replies[messageId] ?: AgentActivityTurnReply.Unavailable
        }
    }

    private fun TestScope.store(repository: FakeRepository) =
        SharedAgentActivityOverviewStore(repository, backgroundScope)

    private fun page(vararg turns: AgentActivityTurn, next: String? = null) =
        DataResult.Content(AgentActivityTurnPage(turns.toList(), next))

    @Test
    fun opensOncePerAgentAndPagesWithTheCursor() = runTest {
        val repository = FakeRepository()
        val store = store(repository)
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        assertEquals(1, repository.calls.size, "a repeated Open for the same agent must not refetch")
        assertEquals(AgentActivityOverviewStatus.Loading, store.state.value.status)
        assertEquals(DataRequestPriority.Visible, repository.calls[0].priority)
        assertEquals(null, repository.calls[0].before)

        repository.calls[0].result.complete(page(turn("t1", 300), turn("t2", 200), next = "200:t2"))
        runCurrent()
        assertEquals(AgentActivityOverviewStatus.Loaded, store.state.value.status)
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
        assertEquals(listOf("t1", "t2", "t3"), store.state.value.turns.map { it.key })
        assertEquals(null, store.state.value.next)
        assertEquals(false, store.state.value.loadingMore)

        store.dispatch(AgentActivityOverviewIntent.LoadMore)
        runCurrent()
        assertEquals(2, repository.calls.size, "no cursor, no request")
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
    fun liveActivityReloadsAreDebouncedCoalescedAndSilent() = runTest {
        val repository = FakeRepository()
        val store = store(repository)
        store.dispatch(AgentActivityOverviewIntent.Open(agent))
        runCurrent()
        repository.calls[0].result.complete(page(turn("t1", 300)))
        runCurrent()

        repeat(3) { store.dispatch(AgentActivityOverviewIntent.LiveActivityChanged) }
        advanceTimeBy(SharedAgentActivityOverviewStore.LIVE_RELOAD_DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals(1, repository.calls.size, "still inside the debounce window")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, repository.calls.size)
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
        advanceUntilIdle()
        assertEquals(listOf("t0", "t1"), store.state.value.turns.map { it.key })
        assertEquals(3, repository.calls.size)
    }

    @Test
    fun switchingAgentsDropsLateAnswersAndRepliesLoadOnce() = runTest {
        val repository = FakeRepository()
        repository.replies[MessageId("r1")] = AgentActivityTurnReply.Loaded("done", 5L)
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
}
