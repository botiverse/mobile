package build.raft.app

import build.raft.app.data.HomeSnapshot
import build.raft.app.data.toHomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.ThreadMemberRemovalConfirmation
import build.raft.app.thread.ThreadMemberRemovalFailure
import build.raft.app.thread.ThreadMemberRemovalGateIntent
import build.raft.app.thread.ThreadMemberRemovalGateState
import build.raft.app.thread.reduceThreadMemberRemovalGate
import build.raft.app.thread.threadMemberRemovalConfirmation
import build.raft.app.thread.threadMembersRemovalChannelName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadMemberRemovalConfirmationTest {
    @Test
    fun confirmationFreezesCompleteTypedIdentityAndCanonicalChannelName() {
        val snapshot = homeSnapshot(channelName = "#proj-stamp")
        val channelName =
            threadMembersRemovalChannelName(
                snapshot = snapshot,
                serverId = ServerA,
                channelId = ChannelA
            )
        val confirmation = confirmation(channelName = channelName)

        assertEquals(ServerA, confirmation.serverId)
        assertEquals(ChannelA, confirmation.request.channelId)
        assertEquals(MemberId("agent-cx-suni"), confirmation.request.memberId)
        assertEquals(MemberKind.Agent, confirmation.request.kind)
        assertEquals("agent-cx-suni", confirmation.memberRowId)
        assertEquals("Cx-Suni", confirmation.memberLabel)
        assertEquals("proj-stamp", confirmation.channelName)
    }

    @Test
    fun gateDismissesCrossRouteCommandAndExecutesMatchingCommandExactlyOnce() {
        val confirmation = confirmation()
        val pending =
            reduceThreadMemberRemovalGate(
                ThreadMemberRemovalGateState(),
                ThreadMemberRemovalGateIntent.Request(confirmation)
            )
        val wrongRoute =
            reduceThreadMemberRemovalGate(
                pending.state,
                ThreadMemberRemovalGateIntent.Confirm(ServerB, ChannelB)
            )

        assertNull(wrongRoute.execute)
        assertNull(wrongRoute.state.pending)

        val requestedAgain =
            reduceThreadMemberRemovalGate(
                wrongRoute.state,
                ThreadMemberRemovalGateIntent.Request(confirmation)
            )
        val firstConfirm =
            reduceThreadMemberRemovalGate(
                requestedAgain.state,
                ThreadMemberRemovalGateIntent.Confirm(ServerA, ChannelA)
            )
        val duplicateConfirm =
            reduceThreadMemberRemovalGate(
                firstConfirm.state,
                ThreadMemberRemovalGateIntent.Confirm(ServerA, ChannelA)
            )

        assertEquals(confirmation, firstConfirm.execute)
        assertTrue(firstConfirm.state.inFlight)
        assertNull(duplicateConfirm.execute)
        assertEquals(firstConfirm.state, duplicateConfirm.state)

        val completed =
            reduceThreadMemberRemovalGate(
                firstConfirm.state,
                ThreadMemberRemovalGateIntent.CompleteSuccess(confirmation)
            )
        assertNull(completed.state.pending)
        assertFalse(completed.state.inFlight)
    }

    @Test
    fun gateDismissHasNoExecuteAndFailuresRetryTheSameFrozenCommand() {
        val confirmation = confirmation()
        val pending =
            reduceThreadMemberRemovalGate(
                ThreadMemberRemovalGateState(),
                ThreadMemberRemovalGateIntent.Request(confirmation)
            )
        val dismissed =
            reduceThreadMemberRemovalGate(
                pending.state,
                ThreadMemberRemovalGateIntent.Dismiss
            )

        assertNull(dismissed.execute)
        assertNull(dismissed.state.pending)

        ThreadMemberRemovalFailure.values().forEach { failure ->
            val confirmed =
                reduceThreadMemberRemovalGate(
                    pending.state,
                    ThreadMemberRemovalGateIntent.Confirm(ServerA, ChannelA)
                )
            val failed =
                reduceThreadMemberRemovalGate(
                    confirmed.state,
                    ThreadMemberRemovalGateIntent.CompleteFailure(
                        confirmation = confirmation,
                        failure = failure,
                        errorCode = failure.name.lowercase()
                    )
                )
            val retry =
                reduceThreadMemberRemovalGate(
                    failed.state,
                    ThreadMemberRemovalGateIntent.Confirm(ServerA, ChannelA)
                )

            assertEquals(confirmation, failed.state.pending)
            assertFalse(failed.state.inFlight)
            assertEquals(failure, failed.state.failure)
            assertEquals(confirmation, retry.execute)
            assertTrue(retry.state.inFlight)
        }
    }

    private fun confirmation(channelName: String = "channel-a"): ThreadMemberRemovalConfirmation =
        checkNotNull(
            threadMemberRemovalConfirmation(
                member =
                    RaftThreadMemberItem(
                        id = "agent-cx-suni",
                        displayName = "Cx-Suni",
                        handle = "cx-suni",
                        kind = "agent",
                        canRemove = true
                    ),
                serverId = ServerA,
                channelId = ChannelA,
                channelName = channelName
            )
        )

    private fun homeSnapshot(channelName: String): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerA, name = "Server A"),
            currentMember =
                MemberProfile(
                    id = MemberId("human-owner"),
                    displayName = "Owner",
                    handle = "owner",
                    kind = MemberKind.Human
                ),
            channels =
                listOf(
                    ConversationSummary(
                        threadId = ThreadId(ChannelA.value),
                        channelId = ChannelA,
                        kind = ConversationKind.Channel,
                        name = channelName
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private companion object {
        val ServerA = ServerId("server-a")
        val ServerB = ServerId("server-b")
        val ChannelA = ChannelId("channel-a")
        val ChannelB = ChannelId("channel-b")
    }
}
