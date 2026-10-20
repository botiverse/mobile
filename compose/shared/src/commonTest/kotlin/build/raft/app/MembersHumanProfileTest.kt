package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftBadgeVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersHumanProfileTest {
    @Test
    fun departureFollowsWebMembershipStatuses() {
        assertEquals(MembersHumanDeparture.Left, membersHumanDeparture("left"))
        assertEquals(MembersHumanDeparture.Removed, membersHumanDeparture(" Removed "))
        assertNull(membersHumanDeparture("active"))
        assertNull(membersHumanDeparture(null))
    }

    @Test
    fun roleBadgeIsNeutralForServerRolesAndInformationForGuests() {
        assertEquals(MembersHumanRoleBadge.Owner, membersHumanRoleBadge("owner", null))
        assertEquals(RaftBadgeVariant.Default, MembersHumanRoleBadge.Owner.badgeVariant)
        assertEquals(RaftBadgeVariant.Default, MembersHumanRoleBadge.Admin.badgeVariant)
        assertEquals(RaftBadgeVariant.Default, MembersHumanRoleBadge.Member.badgeVariant)
        assertEquals(MembersHumanRoleBadge.Guest, membersHumanRoleBadge("guest", null))
        assertEquals(RaftBadgeVariant.Information, MembersHumanRoleBadge.Guest.badgeVariant)
    }

    @Test
    fun roleBadgeFallsBackToRoleDescriptionThenUnknown() {
        assertEquals(MembersHumanRoleBadge.Admin, membersHumanRoleBadge(null, "Admin"))
        assertEquals(MembersHumanRoleBadge.Unknown, membersHumanRoleBadge(null, null))
        assertEquals(MembersHumanRoleBadge.Unknown, membersHumanRoleBadge("superuser", "Designer"))
        assertEquals(RaftBadgeVariant.Muted, MembersHumanRoleBadge.Unknown.badgeVariant)
    }

    @Test
    fun selfOnlyMatchesTheSignedInHuman() {
        val human = MemberProfile(id = MemberId("user-1"), displayName = "RC", handle = "stdrc", kind = MemberKind.Human)
        assertTrue(membersHumanIsSelf(human, "user-1"))
        assertFalse(membersHumanIsSelf(human, "user-2"))
        assertFalse(membersHumanIsSelf(human, null))
        assertFalse(membersHumanIsSelf(human.copy(kind = MemberKind.Agent), "user-1"))
    }

    @Test
    fun remoteProfilesHideServerFacts() {
        assertTrue(membersHumanShowsServerFacts(MemberDetailAccessMode.LocalFull))
        assertFalse(membersHumanShowsServerFacts(MemberDetailAccessMode.RemoteProfileOnly))
    }

    @Test
    fun joinedDateUsesTheLocalCalendarDay() {
        val utc = RaftTimeContext(localOffsetMinutes = 0)
        assertEquals(MembersJoinedDate(2026, 9, 11), "2026-09-11T23:30:00.000Z".membersJoinedDateOrNull(utc))
        // UTC+8: late on Sep 11 UTC is already Sep 12 locally.
        val shanghai = RaftTimeContext(localOffsetMinutes = 480)
        assertEquals(MembersJoinedDate(2026, 9, 12), "2026-09-11T23:30:00.000Z".membersJoinedDateOrNull(shanghai))
        assertNull("".membersJoinedDateOrNull(utc))
        assertNull(null.membersJoinedDateOrNull(utc))
        assertNull("not a date".membersJoinedDateOrNull(utc))
    }

    @Test
    fun joinedMonthLabelReadsTheLocalizedList() {
        val english = "Jan,Feb,Mar,Apr,May,Jun,Jul,Aug,Sep,Oct,Nov,Dec"
        assertEquals("Sep", membersJoinedMonthLabel(english, 9))
        assertEquals("9", membersJoinedMonthLabel("1,2,3,4,5,6,7,8,9,10,11,12", 9))
        assertEquals("13", membersJoinedMonthLabel(english, 13))
    }

    @Test
    fun createdAgentRouteOpensTheAgentProfileOnTheHumansServer() {
        val payload =
            RaftMemberDetailPayload(
                instanceId = "members-detail-x-user-1",
                identity = MemberDetailIdentity(MemberId("user-1"), MemberKind.Human, ServerId("server-a")),
                localOffsetMinutes = 480,
                agentPanelTabOrder = listOf("profile", "activity")
            )

        val pageData = assertNotNull(payload.toCreatedAgentDetailPageData(MemberId("agent-7"), ServerId("server-b")))

        assertEquals(MEMBER_DETAIL_PAGE, pageData.optString("pageName"))
        assertEquals("agent-7", pageData.optString("memberId"))
        assertEquals("agent", pageData.optString("memberKind"))
        assertEquals("server-b", pageData.optString("sourceServerId"))
        assertEquals("480", pageData.optString("localOffsetMinutes"))
        val route = pageData.toRaftMemberDetailPayload()
        assertEquals(MemberKind.Agent, route.identity?.kind)
        assertEquals(listOf("profile", "activity"), route.agentPanelTabOrder)

        val fallback = assertNotNull(payload.toCreatedAgentDetailPageData(MemberId("agent-7"), null))
        assertEquals("server-a", fallback.optString("sourceServerId"))
        assertNull(payload.toCreatedAgentDetailPageData(MemberId(" "), null))
    }
}
