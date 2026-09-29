package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedThreadAgentFollowersStoreTest {
    @Test fun lastRemovalKeepsUndoAndRestoresTheExactAgentAndToken() = runTest {
        val repository = FollowerRepositoryFake()
        val store = SharedThreadAgentFollowersStore(repository, backgroundScope) { testScheduler.currentTime }
        store.dispatch(ThreadAgentFollowersIntent.Open)
        runCurrent()
        store.dispatch(ThreadAgentFollowersIntent.Remove("agent"))
        store.dispatch(ThreadAgentFollowersIntent.Remove("agent"))
        runCurrent()
        assertEquals(1, repository.removals)
        assertTrue(store.state.value.roster!!.agents.isEmpty())
        assertNotNull(store.state.value.undo)
        assertEquals(ThreadFollowersNoticeKind.Removed, store.state.value.notice?.kind)
        assertEquals("Agent", store.state.value.notice?.agentLabel)
        store.dispatch(ThreadAgentFollowersIntent.Close)
        assertNotNull(store.state.value.undo)
        advanceTimeBy(4_999)
        store.dispatch(ThreadAgentFollowersIntent.Undo)
        store.dispatch(ThreadAgentFollowersIntent.Undo)
        runCurrent()
        assertEquals(listOf("agent" to "removal-token"), repository.restores)
        assertEquals(listOf("agent"), store.state.value.roster!!.agents.map { it.id })
        assertNull(store.state.value.undo)
        assertEquals(ThreadFollowersNoticeKind.Restored, store.state.value.notice?.kind)
        advanceTimeBy(1)
        runCurrent()
        // The old removal expiry must not dismiss the newer restore notification.
        assertEquals(ThreadFollowersNoticeKind.Restored, store.state.value.notice?.kind)
        advanceTimeBy(4_999)
        runCurrent()
        assertNull(store.state.value.notice)
    }

    @Test fun undoExpiresAtFiveSecondsEvenBeforeTheTimerRuns() = runTest {
        val repository = FollowerRepositoryFake()
        val store = SharedThreadAgentFollowersStore(repository, backgroundScope) { testScheduler.currentTime }
        store.dispatch(ThreadAgentFollowersIntent.Refresh)
        runCurrent()
        store.dispatch(ThreadAgentFollowersIntent.Remove("agent"))
        runCurrent()
        advanceTimeBy(5_000)
        store.dispatch(ThreadAgentFollowersIntent.Undo)
        runCurrent()
        assertNull(store.state.value.notice)
        assertTrue(repository.restores.isEmpty())
        assertNull(store.state.value.undo)
    }

    @Test fun loadFailureKeepsThePreviousRosterAndAllowsExplicitRetry() = runTest {
        val repository = FollowerRepositoryFake()
        val store = SharedThreadAgentFollowersStore(repository, backgroundScope)
        store.dispatch(ThreadAgentFollowersIntent.Open)
        runCurrent()
        repository.nextLoad = CompletableDeferred(DataResult.Error(ScreenError("http_404")))
        store.dispatch(ThreadAgentFollowersIntent.Refresh)
        runCurrent()
        assertEquals(ThreadFollowersError.Load, store.state.value.error)
        assertEquals(1, store.state.value.roster!!.agents.size)
        store.dispatch(ThreadAgentFollowersIntent.Refresh)
        runCurrent()
        assertEquals(3, repository.loads)
        assertNull(store.state.value.error)
    }

    @Test fun foreignAgentAndMissingPermissionNeverIssueWrites() = runTest {
        for (agent in listOf(follower().copy(isCurrentServer = false), follower().copy(canRemove = false))) {
            val repository = FollowerRepositoryFake().apply { roster = roster.copy(agents = listOf(agent)) }
            val store = SharedThreadAgentFollowersStore(repository, backgroundScope)
            store.dispatch(ThreadAgentFollowersIntent.Refresh)
            runCurrent()
            store.dispatch(ThreadAgentFollowersIntent.Remove(agent.id))
            runCurrent()
            assertEquals(0, repository.removals)
            store.close()
        }
        val repository = FollowerRepositoryFake().apply { roster = roster.copy(canManage = false) }
        val store = SharedThreadAgentFollowersStore(repository, backgroundScope)
        store.dispatch(ThreadAgentFollowersIntent.Refresh)
        runCurrent()
        store.dispatch(ThreadAgentFollowersIntent.Remove("agent"))
        runCurrent()
        assertEquals(0, repository.removals)
    }

    @Test fun refreshCoalescesAndCannotResurrectAnAgentDuringRemoval() = runTest {
        val repository = FollowerRepositoryFake()
        val store = SharedThreadAgentFollowersStore(repository, backgroundScope)
        store.dispatch(ThreadAgentFollowersIntent.Refresh)
        runCurrent()
        val staleRead = CompletableDeferred<DataResult<ThreadAgentFollowerRoster>>()
        repository.nextLoad = staleRead
        repeat(4) { store.dispatch(ThreadAgentFollowersIntent.Refresh) }
        runCurrent()
        assertEquals(2, repository.loads)
        store.dispatch(ThreadAgentFollowersIntent.Remove("agent"))
        runCurrent()
        assertTrue(store.state.value.roster!!.agents.isEmpty())
        staleRead.complete(DataResult.Content(ThreadAgentFollowerRoster("thread", true, listOf(follower()))))
        runCurrent()
        assertTrue(store.state.value.roster!!.agents.isEmpty())
        assertEquals(3, repository.loads)
        assertNotNull(store.state.value.undo)
    }

    @Test fun failuresKeepRosterAndActionErrorSurvivesReconcile() = runTest {
        val repository = FollowerRepositoryFake()
        val store = SharedThreadAgentFollowersStore(repository, backgroundScope)
        store.dispatch(ThreadAgentFollowersIntent.Open)
        runCurrent()
        repository.removeResult = DataResult.Error(ScreenError("http_403"))
        store.dispatch(ThreadAgentFollowersIntent.Remove("agent"))
        runCurrent()
        assertEquals(listOf("agent"), store.state.value.roster!!.agents.map { it.id })
        assertEquals(ThreadFollowersError.Remove, store.state.value.error)
        assertEquals(ThreadFollowersNoticeKind.RemoveFailed, store.state.value.notice?.kind)
        assertNull(store.state.value.undo)
        repository.removeResult = null
        store.dispatch(ThreadAgentFollowersIntent.Remove("agent"))
        runCurrent()
        repository.restoreSucceeds = false
        store.dispatch(ThreadAgentFollowersIntent.Undo)
        runCurrent()
        assertEquals(ThreadFollowersError.Restore, store.state.value.error)
        assertEquals(ThreadFollowersNoticeKind.RestoreFailed, store.state.value.notice?.kind)
        assertTrue(store.state.value.roster!!.agents.isEmpty())
    }

    @Test fun disposedRouteIgnoresAnOldResponseAndFutureIntents() = runTest {
        val repository = FollowerRepositoryFake()
        val pending = CompletableDeferred<DataResult<ThreadAgentFollowerRoster>>()
        repository.nextLoad = pending
        val store = SharedThreadAgentFollowersStore(repository, backgroundScope)
        store.dispatch(ThreadAgentFollowersIntent.Refresh)
        runCurrent()
        store.close()
        pending.complete(DataResult.Content(repository.roster))
        store.dispatch(ThreadAgentFollowersIntent.Open)
        runCurrent()
        assertNull(store.state.value.roster)
        assertEquals(1, repository.loads)
    }
}

private fun follower() = ThreadAgentFollower(
    "agent", "agent-name", "Agent", "idle", null, "server", "Our server", null, true, true,
)
private class FollowerRepositoryFake : ThreadAgentFollowersRepository {
    var roster = ThreadAgentFollowerRoster("thread", true, listOf(follower()))
    var loads = 0
    var removals = 0
    val restores = mutableListOf<Pair<String, String>>()
    var nextLoad: CompletableDeferred<DataResult<ThreadAgentFollowerRoster>>? = null
    var removeResult: DataResult<ThreadAgentFollowerRemoval>? = null
    var restoreSucceeds = true
    override suspend fun load(): DataResult<ThreadAgentFollowerRoster> {
        loads++
        val pending = nextLoad
        nextLoad = null
        return pending?.await() ?: DataResult.Content(roster)
    }
    override suspend fun remove(agentId: String): DataResult<ThreadAgentFollowerRemoval> {
        removals++
        removeResult?.let { return it }
        roster = roster.copy(agents = roster.agents.filterNot { it.id == agentId })
        return DataResult.Content(ThreadAgentFollowerRemoval(true, true, "removal-token"))
    }
    override suspend fun restore(agentId: String, undoToken: String): DataResult<Boolean> {
        restores += agentId to undoToken
        if (restoreSucceeds) roster = roster.copy(agents = listOf(follower()))
        return DataResult.Content(restoreSucceeds)
    }
}
