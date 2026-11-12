package build.raft.app

import build.raft.app.data.AddChannelMembersBatchResult
import build.raft.app.data.DataResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * #proj-mobile:#130: creating an agent from a channel showed "created, but couldn't join this
 * channel" while the join was still in flight, then closed once the join succeeded. The failure
 * face belongs only to a join that really failed.
 */
class CreatedAgentJoinStateTest {
    private val agentId = "a1"
    private val traceId = "0123456789abcdef0123456789abcdef"

    private fun joined(alreadyMember: Boolean = false): DataResult<AddChannelMembersBatchResult> =
        DataResult.Content(
            AddChannelMembersBatchResult(
                addedUserIds = emptyList(),
                addedAgentIds = if (alreadyMember) emptyList() else listOf(agentId),
                alreadyMemberUserIds = emptyList(),
                alreadyMemberAgentIds = if (alreadyMember) listOf(agentId) else emptyList(),
            )
        )

    /** The dialog's create → join sequence, with the join held open until [join] completes. */
    private fun kotlinx.coroutines.CoroutineScope.runCreateThenJoin(
        join: CompletableDeferred<DataResult<AddChannelMembersBatchResult>>,
        states: MutableList<CreatedAgentJoinState>,
    ) = launch {
        var state = CreatedAgentJoinState().created(agentId)
        states += state
        state = state.joinFinished(join.await())
        states += state
    }

    @Test
    fun noFailureIsShownWhileTheJoinIsInFlightAndNoneAfterItSucceeds() = runTest {
        val join = CompletableDeferred<DataResult<AddChannelMembersBatchResult>>()
        val states = mutableListOf<CreatedAgentJoinState>()
        runCreateThenJoin(join, states)
        testScheduler.advanceUntilIdle()
        assertEquals(1, states.size)
        assertEquals(agentId, states.last().createdAgentId)
        assertFalse(states.last().showsJoinRetry, "the join is still running; nothing has failed")
        join.complete(joined())
        testScheduler.advanceUntilIdle()
        assertFalse(states.last().showsJoinRetry)
    }

    @Test
    fun anAgentTheServerAlreadyListsAsAMemberIsAJoin() = runTest {
        val join = CompletableDeferred<DataResult<AddChannelMembersBatchResult>>()
        val states = mutableListOf<CreatedAgentJoinState>()
        runCreateThenJoin(join, states)
        join.complete(joined(alreadyMember = true))
        testScheduler.advanceUntilIdle()
        assertFalse(states.last().showsJoinRetry)
    }

    @Test
    fun aFailedJoinShowsTheRetryWithItsTraceIdAndKeepsTheCreatedAgent() = runTest {
        val join = CompletableDeferred<DataResult<AddChannelMembersBatchResult>>()
        val states = mutableListOf<CreatedAgentJoinState>()
        runCreateThenJoin(join, states)
        join.complete(DataResult.Error(ScreenError(code = "http_500", traceId = traceId)))
        testScheduler.advanceUntilIdle()
        val failed = states.last()
        assertTrue(failed.showsJoinRetry)
        assertEquals(traceId, failed.joinFailureTraceId)
        assertEquals(agentId, failed.createdAgentId, "a retry joins the same agent, it does not create another")
    }

    @Test
    fun aRefusedJoinCarriesTheRefusalsTraceId() {
        val refused =
            CreatedAgentJoinState().created(agentId).joinFinished(
                DataResult.NoPermission(PermissionBlocker(code = "channel_member_add_not_allowed", traceId = traceId))
            )
        assertTrue(refused.showsJoinRetry)
        assertEquals(traceId, refused.joinFailureTraceId)
    }

    @Test
    fun aRetryKeepsTheRetryFaceUntilItSucceeds() {
        val failed =
            CreatedAgentJoinState().created(agentId)
                .joinFinished(DataResult.Error(ScreenError(code = "http_500", traceId = traceId)))
        // While the retry runs the state is unchanged: the retry face stays, with its button disabled.
        assertTrue(failed.showsJoinRetry)
        val retried = failed.joinFinished(joined())
        assertFalse(retried.showsJoinRetry)
        assertNull(retried.joinFailureTraceId)
        assertEquals(agentId, retried.createdAgentId)
    }

    @Test
    fun nothingCreatedShowsTheCreateForm() {
        assertFalse(CreatedAgentJoinState().showsJoinRetry)
    }
}
