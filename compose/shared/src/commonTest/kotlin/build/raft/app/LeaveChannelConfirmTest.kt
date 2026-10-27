package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Web botiverse/slock #11289 (sidebar Leave) and #11292 (`LeaveChannelConfirmDialog` variants). */
class LeaveChannelConfirmTest {
    @Test
    fun variantFollowsChannelTypeAndLastMemberOfAPrivateChannel() {
        data class Case(val isJoint: Boolean, val isPrivate: Boolean, val memberCount: Int?, val expected: LeaveChannelConfirmVariant)
        listOf(
            Case(isJoint = false, isPrivate = false, memberCount = null, LeaveChannelConfirmVariant.Public),
            Case(isJoint = false, isPrivate = false, memberCount = 1, LeaveChannelConfirmVariant.Public),
            Case(isJoint = false, isPrivate = true, memberCount = 2, LeaveChannelConfirmVariant.Private),
            Case(isJoint = false, isPrivate = true, memberCount = 1, LeaveChannelConfirmVariant.LastMember),
            Case(isJoint = false, isPrivate = true, memberCount = 0, LeaveChannelConfirmVariant.LastMember),
            // Roster loading or failed: web warns that it deletes.
            Case(isJoint = false, isPrivate = true, memberCount = null, LeaveChannelConfirmVariant.LastMember),
            // A joint channel is never deleted by a leave, whatever its roster says.
            Case(isJoint = true, isPrivate = true, memberCount = 1, LeaveChannelConfirmVariant.Joint),
            Case(isJoint = true, isPrivate = false, memberCount = null, LeaveChannelConfirmVariant.Joint),
        ).forEach { case ->
            assertEquals(case.expected, leaveChannelConfirmVariant(case.isJoint, case.isPrivate, case.memberCount), case.toString())
        }
    }

    @Test
    fun settingsVariantCountsOnlyALoadedRosterOfThatChannel() {
        val private = channel("c-private", channelType = "private")
        assertEquals(LeaveChannelConfirmVariant.Private, leaveChannelConfirmVariant(private, roster("c-private", 2)))
        assertEquals(LeaveChannelConfirmVariant.LastMember, leaveChannelConfirmVariant(private, roster("c-private", 1)))
        assertEquals(
            LeaveChannelConfirmVariant.LastMember,
            leaveChannelConfirmVariant(private, roster("c-private", 3, SharedServerMembersLoadStatus.InFlight)),
        )
        assertEquals(
            LeaveChannelConfirmVariant.LastMember,
            leaveChannelConfirmVariant(private, roster("c-private", 3, SharedServerMembersLoadStatus.Error)),
        )
        assertEquals(LeaveChannelConfirmVariant.LastMember, leaveChannelConfirmVariant(private, roster("c-other", 3)))
        assertEquals(LeaveChannelConfirmVariant.Public, leaveChannelConfirmVariant(channel("c-public", "channel"), null))
        assertEquals(
            LeaveChannelConfirmVariant.Joint,
            leaveChannelConfirmVariant(channel("c-joint", kind = ConversationKind.Joint), null),
        )
    }

    @Test
    fun homeMenuOffersLeaveOnJoinedChannelsButNotDmsAllOrArchived() {
        assertTrue(homeConversationCanLeave(joined = true, archived = false, isDirectMessage = false, name = "dev"))
        assertTrue(homeConversationCanLeave(joined = true, archived = false, isDirectMessage = false, name = "#all-hands"))
        assertFalse(homeConversationCanLeave(joined = false, archived = false, isDirectMessage = false, name = "dev"))
        assertFalse(homeConversationCanLeave(joined = true, archived = true, isDirectMessage = false, name = "dev"))
        assertFalse(homeConversationCanLeave(joined = true, archived = false, isDirectMessage = true, name = "Cindy"))
        assertFalse(homeConversationCanLeave(joined = true, archived = false, isDirectMessage = false, name = "all"))
        assertFalse(homeConversationCanLeave(joined = true, archived = false, isDirectMessage = false, name = "#All"))
    }

    @Test
    fun homeMenuPutsLeaveUnderArchiveSharingOneDivider() {
        val conversation = RaftHomeConversationItem(id = "c-1", name = "dev")
        val labels = RaftHomeShellLabels(leaveChannel = "离开频道")

        val withArchive =
            raftHomeConversationMenuItems(
                conversation = conversation,
                labels = labels,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = true,
                activeCanLeave = true,
            )
        assertEquals(listOf("archive-channel", "leave-channel"), withArchive.takeLast(2).map { it.id })
        val leave = withArchive.last()
        assertEquals("离开频道", leave.label)
        assertEquals(RaftLucideIconName.LogOut, leave.icon)
        assertFalse(leave.dividerBefore)

        val leaveOnly =
            raftHomeConversationMenuItems(
                conversation = conversation,
                labels = labels,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false,
                activeCanLeave = true,
            )
        assertTrue(leaveOnly.last().id == "leave-channel" && leaveOnly.last().dividerBefore)

        val directMessage =
            raftHomeConversationMenuItems(
                conversation = conversation,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = true,
                activeCanArchive = false,
                activeCanLeave = true,
            )
        assertTrue(directMessage.none { it.id == "leave-channel" })
    }

    @Test
    fun confirmedHomeLeaveDispatchesTheLeaveIntentForThatRow() {
        val target = channel("c-dev", "channel")
        assertEquals(SharedHomeIntent.LeaveChannel("c-dev"), homeLeaveChannelIntent(target))
        // Channel settings' Leave confirms through the same dialog and its own Leave intent.
        assertEquals<ChannelSettingsIntent>(ChannelSettingsIntent.Leave, ChannelSettingsConfirm.Leave.intent)
    }

    private fun channel(
        id: String,
        channelType: String? = null,
        kind: ConversationKind = ConversationKind.Channel,
    ) = HomeChannelRow(threadId = ThreadId(id), channelId = ChannelId(id), channelType = channelType, kind = kind, name = id)

    private fun roster(
        channelId: String,
        count: Int,
        status: SharedServerMembersLoadStatus = SharedServerMembersLoadStatus.Loaded,
    ) = SharedServerChannelMembersSlice(
        channelId = channelId,
        members =
            (1..count).map { index ->
                MemberProfile(
                    id = MemberId("m-$index"),
                    displayName = "Member $index",
                    handle = "m$index",
                    kind = if (index % 2 == 0) MemberKind.Agent else MemberKind.Human,
                    presence = Presence.Active,
                )
            },
        status = status,
    )
}
