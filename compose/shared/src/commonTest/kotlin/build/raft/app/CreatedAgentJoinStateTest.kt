package build.raft.app

import build.raft.app.data.AddChannelMembersBatchResult
import build.raft.app.data.DataResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/**
 * #proj-mobile:#130: creating an agent from a channel showed "created, but couldn't join this
 * channel" while the join was still in flight, then closed once the join succeeded. The failure
 * face belongs only to a join that really failed, and a created agent is never forgotten.
 */
class CreatedAgentJoinStateTest {
    private val agentId = "a1"
    private val traceId = "0123456789abcdef0123456789abcdef"

    private fun joined(alreadyMember: Boolean): DataResult<AddChannelMembersBatchResult> =
        DataResult.Content(
            AddChannelMembersBatchResult(
                addedUserIds = emptyList(),
                addedAgentIds = if (alreadyMember) emptyList() else listOf(agentId),
                alreadyMemberUserIds = emptyList(),
                alreadyMemberAgentIds = if (alreadyMember) listOf(agentId) else emptyList(),
            )
        )

    private fun failedJoin(): DataResult<AddChannelMembersBatchResult> =
        DataResult.Error(ScreenError(code = "http_500", traceId = traceId))

    @Test
    fun noFailureShowsWhileTheJoinIsInFlightNorAfterItSucceedsOrFindsTheAgentAlreadyThere() = runTest {
        for (alreadyMember in listOf(false, true)) {
            val join = CompletableDeferred<DataResult<AddChannelMembersBatchResult>>()
            val states = mutableListOf<CreatedAgentJoinState>()
            val outcome = async {
                createThenJoinAgent(
                    state = CreatedAgentJoinState(),
                    create = { DataResult.Content(agentId) },
                    join = { join.await() },
                    isCurrent = { true },
                    publish = { states += it },
                )
            }
            testScheduler.advanceUntilIdle()
            assertEquals(agentId, states.last().createdAgentId)
            assertFalse(states.last().showsJoinRetry, "the join is still running; nothing has failed")
            join.complete(joined(alreadyMember))
            assertEquals(CreateThenJoinOutcome.Joined(agentId), outcome.await(), "alreadyMember=$alreadyMember")
            assertFalse(states.last().showsJoinRetry, "alreadyMember=$alreadyMember")
        }
    }

    @Test
    fun aFailedJoinShowsTheRetryWithItsTraceIdAndKeepsTheCreatedAgent() = runTest {
        var state = CreatedAgentJoinState()
        val outcome =
            createThenJoinAgent(state, create = { DataResult.Content(agentId) }, join = { failedJoin() },
                isCurrent = { true }, publish = { state = it })
        assertEquals(CreateThenJoinOutcome.JoinFailed, outcome)
        assertTrue(state.showsJoinRetry)
        assertEquals(traceId, state.joinFailureTraceId)
        assertEquals(agentId, state.createdAgentId, "a retry joins the same agent, it does not create another")
    }

    @Test
    fun aRetryJoinsTheSameAgentWithoutCreatingAndKeepsTheRetryFaceUntilItSucceeds() = runTest {
        var state = CreatedAgentJoinState().created(agentId).joinFinished(failedJoin())
        val join = CompletableDeferred<DataResult<AddChannelMembersBatchResult>>()
        val joinedIds = mutableListOf<String>()
        val outcome = async {
            createThenJoinAgent(state, create = null, join = { id -> joinedIds += id; join.await() },
                isCurrent = { true }, publish = { state = it })
        }
        testScheduler.advanceUntilIdle()
        assertTrue(state.showsJoinRetry, "the retry face stays (Retry disabled) while the retry runs")
        join.complete(joined(alreadyMember = false))
        assertEquals(CreateThenJoinOutcome.Joined(agentId), outcome.await())
        assertEquals(listOf(agentId), joinedIds)
        assertFalse(state.showsJoinRetry)
        assertNull(state.joinFailureTraceId)
    }

    @Test
    fun anAuthorityChangeDuringTheJoinIsAJoinFailureNotTheCreateFormAgain() = runTest {
        var state = CreatedAgentJoinState()
        var current = true
        val outcome =
            createThenJoinAgent(state, create = { DataResult.Content(agentId) },
                join = { current = false; joined(alreadyMember = false) },
                isCurrent = { current }, publish = { state = it })
        assertEquals(CreateThenJoinOutcome.JoinFailed, outcome)
        assertTrue(state.showsJoinRetry)
        assertEquals(agentId, state.createdAgentId)
        assertNull(state.joinFailureTraceId, "a local authority change has no request to trace")
    }

    @Test
    fun anAuthorityChangeRightAfterCreatingKeepsTheAgentForRetry() = runTest {
        var state = CreatedAgentJoinState()
        var joins = 0
        val outcome =
            createThenJoinAgent(state, create = { DataResult.Content(agentId) },
                join = { joins++; joined(alreadyMember = false) },
                isCurrent = { false }, publish = { state = it })
        assertEquals(CreateThenJoinOutcome.JoinFailed, outcome)
        assertEquals(0, joins)
        assertTrue(state.showsJoinRetry)
        assertEquals(agentId, state.createdAgentId)
    }

    @Test
    fun aFailedCreateLeavesNothingCreated() = runTest {
        var state = CreatedAgentJoinState()
        val failure = DataResult.Error(ScreenError(code = "http_400", traceId = traceId))
        val outcome =
            createThenJoinAgent(state, create = { failure }, join = { error("no join without an agent") },
                isCurrent = { true }, publish = { state = it })
        assertIs<CreateThenJoinOutcome.CreateFailed>(outcome)
        assertEquals(failure, outcome.result)
        assertNull(state.createdAgentId)
        assertFalse(state.showsJoinRetry)
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
}
