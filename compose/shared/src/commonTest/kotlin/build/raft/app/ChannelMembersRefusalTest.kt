package build.raft.app

import build.raft.app.data.ErrorPresentationKey
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.thread.ThreadMemberRemovalConfirmation
import build.raft.app.thread.ThreadMemberRemovalFailure
import build.raft.app.thread.ThreadMemberRemovalGateIntent
import build.raft.app.thread.ThreadMemberRemovalGateState
import build.raft.app.thread.ThreadUserFacingErrorLabels
import build.raft.app.thread.ThreadUserFacingErrorReason
import build.raft.app.thread.reduceThreadMemberRemovalGate
import build.raft.app.thread.threadMemberRemovalFailureMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelMembersRefusalTest {
    private val errorStrings = ErrorPresentationStrings(ErrorPresentationKey.entries.associateWith { it.name })
    private val threadLabels = ThreadUserFacingErrorLabels(ThreadUserFacingErrorReason.entries.associateWith { it.name })

    @Test
    fun addMemberFailureMessageSurfacesServerRefusalAndScrubsInfra() {
        assertEquals(
            "This channel has reached its member limit.",
            threadAddMemberFailureMessage(
                canAddMembers = true,
                addMemberStatus = "failed",
                errorCode = "http_400",
                errorServerCode = "channel_full",
                errorMessage = "This channel has reached its member limit.",
                addFailedLabel = "Failed to add member",
                noPermissionLabel = "No permission",
                genericErrorLabel = "Error",
                userFacingErrorLabels = threadLabels,
                errorStrings = errorStrings,
            ),
        )
        assertEquals(
            "Guests cannot be added to this channel.",
            threadAddMemberFailureMessage(
                canAddMembers = true,
                addMemberStatus = "no_permission",
                errorCode = "server_forbidden",
                errorServerCode = "guest_other_channel",
                errorMessage = "Guests cannot be added to this channel.",
                addFailedLabel = "Failed to add member",
                noPermissionLabel = "No permission",
                genericErrorLabel = "Error",
                userFacingErrorLabels = threadLabels,
                errorStrings = errorStrings,
            ),
        )
        assertEquals(
            "Failed to add member: ${ThreadUserFacingErrorReason.ServerUnavailable.name}",
            threadAddMemberFailureMessage(
                canAddMembers = true,
                addMemberStatus = "failed",
                errorCode = "http_500",
                errorServerCode = "internal_error",
                errorMessage = "SQLSTATE crash",
                addFailedLabel = "Failed to add member",
                noPermissionLabel = "No permission",
                genericErrorLabel = "Error",
                userFacingErrorLabels = threadLabels,
                errorStrings = errorStrings,
            ),
        )
        assertEquals(
            ErrorPresentationKey.EdgeBlocked.name,
            threadAddMemberFailureMessage(
                canAddMembers = true,
                addMemberStatus = "no_permission",
                errorCode = "http_403",
                errorServerCode = "edge_blocked",
                errorMessage = "edge_blocked",
                addFailedLabel = "Failed to add member",
                noPermissionLabel = "No permission",
                genericErrorLabel = "Error",
                userFacingErrorLabels = threadLabels,
                errorStrings = errorStrings,
            ),
        )
        assertEquals(
            "No permission",
            threadAddMemberFailureMessage(
                canAddMembers = false,
                addMemberStatus = "idle",
                errorCode = null,
                errorMessage = "should be ignored when capability is false",
                addFailedLabel = "Failed to add member",
                noPermissionLabel = "No permission",
                genericErrorLabel = "Error",
                userFacingErrorLabels = threadLabels,
                errorStrings = errorStrings,
            ),
        )
    }

    @Test
    fun memberRemovalGatePreservesClearsAndResolvesRefusalReasonAndTraceId() {
        val confirmation =
            ThreadMemberRemovalConfirmation(
                serverId = ServerId("server-1"),
                memberRowId = "user-2",
                memberLabel = "Ada",
                channelName = "general",
                request = RemoveChannelMemberRequest(ChannelId("channel-1"), MemberId("user-2"), MemberKind.Human),
            )
        val requested = reduceThreadMemberRemovalGate(ThreadMemberRemovalGateState(), ThreadMemberRemovalGateIntent.Request(confirmation))
        val confirmed = reduceThreadMemberRemovalGate(requested.state, ThreadMemberRemovalGateIntent.Confirm(ServerId("server-1"), ChannelId("channel-1")))
        val failed =
            reduceThreadMemberRemovalGate(
                confirmed.state,
                ThreadMemberRemovalGateIntent.CompleteFailure(
                    confirmation = confirmation,
                    failure = ThreadMemberRemovalFailure.NoPermission,
                    errorCode = "server_forbidden",
                    serverMessage = "Cannot remove the last channel admin.",
                    serverCode = "last_channel_admin",
                    traceId = "trace-rem-403",
                ),
            )

        assertEquals("Cannot remove the last channel admin.", failed.state.serverMessage)
        assertEquals("last_channel_admin", failed.state.serverCode)
        assertEquals("trace-rem-403", failed.state.traceId)
        assertEquals(
            "Cannot remove the last channel admin.",
            threadMemberRemovalFailureMessage(
                state = failed.state,
                noPermissionFallback = "Remove no permission",
                genericFallback = "Remove failed",
                strings = errorStrings,
            ),
        )

        val retried = reduceThreadMemberRemovalGate(failed.state, ThreadMemberRemovalGateIntent.Confirm(ServerId("server-1"), ChannelId("channel-1")))
        assertNull(retried.state.serverMessage)
        assertNull(retried.state.serverCode)
        assertNull(retried.state.traceId)

        val server500 =
            ThreadMemberRemovalGateState(
                failure = ThreadMemberRemovalFailure.Failed,
                errorCode = "http_500",
                serverMessage = "<html>500</html>",
                serverCode = "internal_error",
                traceId = "trace-rem-500",
            )
        assertEquals(
            ErrorPresentationKey.ServerUnavailable.name,
            threadMemberRemovalFailureMessage(server500, "Remove no permission", "Remove failed", errorStrings),
        )
    }

    @Test
    fun memberRoleChangeFailureMessageSurfacesServerRefusalPreservesFallbackAndScrubsInfra() {
        assertEquals(
            "Update role failed",
            channelMemberRoleFailureMessage(
                ChannelMemberRoleChangeState(failed = true),
                fallback = "Update role failed",
                strings = errorStrings,
            ),
        )
        assertEquals(
            "Cannot demote the last channel admin.",
            channelMemberRoleFailureMessage(
                ChannelMemberRoleChangeState(
                    failed = true,
                    failureCode = "http_409",
                    failureServerCode = "last_channel_admin",
                    failureServerMessage = "Cannot demote the last channel admin.",
                    failureTraceId = "trace-role-409",
                ),
                fallback = "Update role failed",
                strings = errorStrings,
            ),
        )
        assertEquals(
            "Only server admins can promote channel admins.",
            channelMemberRoleFailureMessage(
                ChannelMemberRoleChangeState(
                    failed = true,
                    failureCode = "server_forbidden",
                    failureServerCode = "forbidden",
                    failureServerMessage = "Only server admins can promote channel admins.",
                    failureIsNoPermission = true,
                    failureTraceId = "trace-role-403",
                ),
                fallback = "Update role failed",
                strings = errorStrings,
            ),
        )
        assertEquals(
            ErrorPresentationKey.SessionExpired.name,
            channelMemberRoleFailureMessage(
                ChannelMemberRoleChangeState(
                    failed = true,
                    failureCode = "auth_required",
                    failureServerMessage = "jwt expired",
                    failureIsNoPermission = true,
                ),
                fallback = "Update role failed",
                strings = errorStrings,
            ),
        )
        assertEquals(
            "Update role failed",
            channelMemberRoleFailureMessage(
                ChannelMemberRoleChangeState(
                    failed = true,
                    failureCode = "internal_error",
                    failureServerCode = "internal_error",
                    failureServerMessage = "panic trace",
                ),
                fallback = "Update role failed",
                strings = errorStrings,
            ),
        )
    }
}
