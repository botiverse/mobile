package build.raft.app

import build.raft.app.data.SharedAgentDetailState
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.matches
import build.raft.app.memberDetailIdentity
import build.raft.app.members.MEMBER_DETAIL_PAGE
import build.raft.app.members.MemberDetailRouteState
import build.raft.app.members.MembersAgentProfileRequestFence
import build.raft.app.members.MembersHumanDeparture
import build.raft.app.members.MembersHumanRoleBadge
import build.raft.app.members.MembersJoinedDate
import build.raft.app.members.RaftMemberDetailPayload
import build.raft.app.members.RaftMembersPayload
import build.raft.app.members.memberDetailAppliesLoadedRoute
import build.raft.app.members.memberDetailInitialRouteState
import build.raft.app.members.membersHumanCanEditDescription
import build.raft.app.members.membersHumanDeparture
import build.raft.app.members.membersHumanIsSelf
import build.raft.app.members.membersHumanRoleBadge
import build.raft.app.members.membersHumanShowsServerFacts
import build.raft.app.members.membersJoinedDateOrNull
import build.raft.app.members.toCreatedAgentDetailPageData
import build.raft.app.members.toMemberDetailPageData
import build.raft.app.members.toRaftMemberDetailPayload
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.settings.homeSearchMemberDetailPageData
import build.raft.app.settings.matches
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.thread.toThreadMemberDetailPageData
import build.raft.designsystem.components.RaftBadgeVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersHumanProfileTest {
    private val server = ServerSummary(id = ServerId("server-1"), name = "One")

    /** The signed-in person as their server's roster lists them (its `joinedAt` reads as active). */
    private val viewer =
        MemberProfile(
            id = MemberId("user-1"),
            displayName = "RC",
            handle = "stdrc",
            kind = MemberKind.Human,
            serverId = server.id,
            membershipStatus = "active"
        )

    private fun rosterOf(member: MemberProfile): SharedServerMembersState =
        SharedServerMembersState(
            entriesByServerId =
                mapOf(
                    server.id.value to
                        SharedServerMembersEntry(serverId = server.id.value, server = server, members = listOf(member))
                )
        )

    /**
     * Every way to your own profile, the reported one first (the DM with yourself: its header and
     * your own message in it), hands the profile page only your identity. Its first frame, the
     * roster seed, knows you from the signed-in account: "you" and Edit description.
     */
    @Test
    fun ownProfileKnowsTheViewerFromEveryOpener() {
        val selfDm =
            RaftThreadScreenPayload(
                instanceId = "thread-dm-self",
                sourceThreadId = "dm-self",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm"
            )
        val local = MemberDetailIdentity(viewer.id, MemberKind.Human, server.id)
        val openers =
            mapOf(
                "self_dm_header" to
                    memberDetailIdentityFromWire("user-1", "human", source = MemberDetailIdentitySource.DirectoryDmPeer)
                        ?.let { selfDm.toThreadMemberDetailPageData(it) },
                "self_dm_own_message" to
                    RaftThreadMessageItem(id = "message-1", authorId = "user-1", authorName = "RC", authorKind = "human")
                        .memberDetailIdentity()
                        ?.let { selfDm.toThreadMemberDetailPageData(it) },
                "members_list" to RaftMembersPayload(instanceId = server.id.value).toMemberDetailPageData(local),
                "search" to homeSearchMemberDetailPageData(local, timeContext = null)
            )

        for ((opener, pageData) in openers) {
            val payload = assertNotNull(pageData, opener).toRaftMemberDetailPayload()
            assertEquals(viewer.id, payload.identity?.memberId, opener)
            // None of them reads the profile in a channel (a DM is none).
            assertNull(payload.profileChannelId, opener)
            val route =
                assertIs<MemberDetailRouteState.Human>(
                    memberDetailInitialRouteState(
                        identity = payload.identity,
                        activeServerId = server.id.value,
                        agentDetailState = SharedAgentDetailState(),
                        serverMembersState = rosterOf(viewer),
                        currentUserId = viewer.id.value
                    ),
                    opener
                )
            assertTrue(membersHumanIsSelf(route.member, route.currentUserId), opener)
            assertTrue(membersHumanCanEditDescription(route.member, route.currentUserId), opener)
        }
    }

    /**
     * Both states the profile page shows a person in, the roster seed and then the profile read,
     * offer Edit description on your own active profile only ("you" shows on yours either way).
     * The read lands although the agent request fence the page builds for every open never matches
     * a person: gated on it, the page kept the seed, without the viewer, for good.
     */
    @Test
    fun seedAndProfileReadOfferEditOnlyOnTheViewersActiveProfile() {
        // shown member, "you", Edit description
        val cases =
            listOf(
                Triple(viewer, true, true),
                Triple(viewer.copy(id = MemberId("user-2"), handle = "other"), false, false),
                Triple(viewer.copy(membershipStatus = "left"), true, false)
            )
        for ((member, isSelf, canEdit) in cases) {
            val label = "${member.id.value}/${member.membershipStatus}"
            val identity = MemberDetailIdentity(member.id, MemberKind.Human, server.id)
            val seed =
                assertIs<MemberDetailRouteState.Human>(
                    memberDetailInitialRouteState(
                        identity = identity,
                        activeServerId = server.id.value,
                        agentDetailState = SharedAgentDetailState(),
                        serverMembersState = rosterOf(member),
                        currentUserId = viewer.id.value
                    ),
                    label
                )
            val read = MemberDetailRouteState.Human(server, currentUserId = viewer.id.value, member = member)
            val agentFence = MembersAgentProfileRequestFence(identity.memberId, server.id, generation = 1L)
            assertTrue(
                memberDetailAppliesLoadedRoute(
                    identity = identity,
                    shown = seed,
                    loaded = read,
                    agentRequestCurrent = agentFence.matches(identity, server.id.value, currentGeneration = 1L)
                ),
                label
            )
            for (route in listOf(seed, read)) {
                assertEquals(isSelf, membersHumanIsSelf(route.member, route.currentUserId), label)
                assertEquals(canEdit, membersHumanCanEditDescription(route.member, route.currentUserId), label)
            }
        }
    }

    /**
     * A failed profile read keeps the person's roster seed on screen; a cold open shows the
     * failure. An agent's answer still lands only while its request is current.
     */
    @Test
    fun failedReadKeepsThePersonsSeedAndAgentAnswersStayFenced() {
        val person = MemberDetailIdentity(viewer.id, MemberKind.Human, server.id)
        val seed = MemberDetailRouteState.Human(server, currentUserId = viewer.id.value, member = viewer)
        val failed = MemberDetailRouteState.Error("network_parse_failed")
        assertFalse(memberDetailAppliesLoadedRoute(person, shown = seed, loaded = failed, agentRequestCurrent = false))
        assertTrue(
            memberDetailAppliesLoadedRoute(
                person,
                shown = MemberDetailRouteState.Loading,
                loaded = failed,
                agentRequestCurrent = false
            )
        )

        val agent = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent, server.id)
        val agentRoute = MemberDetailRouteState.Agent(server, currentUserId = viewer.id.value)
        assertTrue(
            memberDetailAppliesLoadedRoute(
                agent,
                shown = MemberDetailRouteState.Loading,
                loaded = agentRoute,
                agentRequestCurrent = true
            )
        )
        assertFalse(
            memberDetailAppliesLoadedRoute(
                agent,
                shown = MemberDetailRouteState.Loading,
                loaded = agentRoute,
                agentRequestCurrent = false
            )
        )
    }

    @Test
    fun departureFollowsWebMembershipStatuses() {
        assertEquals(MembersHumanDeparture.Left, membersHumanDeparture("left"))
        assertEquals(MembersHumanDeparture.Removed, membersHumanDeparture(" Removed "))
        assertNull(membersHumanDeparture("active"))
        assertNull(membersHumanDeparture(null))
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
    fun selfDescriptionEditingRequiresSignedInActiveHuman() {
        val human = MemberProfile(id = MemberId("user-1"), displayName = "RC", handle = "stdrc", kind = MemberKind.Human)
        assertTrue(membersHumanCanEditDescription(human.copy(membershipStatus = "active"), "user-1"))
        assertFalse(membersHumanCanEditDescription(human.copy(membershipStatus = null), "user-1"))
        assertFalse(membersHumanCanEditDescription(human.copy(membershipStatus = "left"), "user-1"))
        assertFalse(membersHumanCanEditDescription(human.copy(membershipStatus = "removed"), "user-1"))
        assertFalse(membersHumanCanEditDescription(human.copy(membershipStatus = "active"), "user-2"))
        assertFalse(membersHumanCanEditDescription(human.copy(membershipStatus = "active"), null))
        assertFalse(membersHumanCanEditDescription(human.copy(kind = MemberKind.Agent, membershipStatus = "active"), "user-1"))
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
