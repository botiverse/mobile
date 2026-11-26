package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberServerRef
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where someone is from (#wg-rbac:#46): web `utils/memberOrigin.ts` `memberOrigin` and
 * `utils/channelLocalMembership.ts` `memberServerBadgeLabels`, botiverse/slock ec13decd3; the
 * roster facts are `channels.ts` `buildChannelMembersView` / `channelService.getChannelMembers`.
 */
class MemberOriginTest {
    private val viewer = "server-1"
    private val peer = MemberServerRef("server-peer", "Peer Labs", "peer-labs")
    private val acme = MemberServerRef("server-acme", "", "acme")
    private val person =
        MemberProfile(id = MemberId("user-2"), displayName = "Ada", handle = "ada", kind = MemberKind.Human)

    @Test
    fun inViewerServerDecidesOverTheFirstSourceServerId() {
        // A computed joint row: `serverId` is only the person's first source.
        val firstSourceElsewhere = person.copy(serverId = ServerId(peer.id), serverName = peer.name, servers = listOf(peer))
        assertEquals(MemberOrigin.Local, memberOrigin(firstSourceElsewhere.copy(inViewerServer = true), viewer))
        assertEquals(
            MemberOrigin(external = true, servers = listOf(peer)),
            memberOrigin(firstSourceElsewhere.copy(inViewerServer = false), viewer),
        )
        // The server said "not ours" though the first source reads as ours.
        assertTrue(memberOrigin(person.copy(serverId = ServerId(viewer), inViewerServer = false), viewer).external)
    }

    @Test
    fun withoutInViewerServerTheServerIdDecidesAsBefore() {
        val cases =
            listOf(
                Triple(person, viewer, false),
                Triple(person.copy(serverId = ServerId(viewer)), viewer, false),
                Triple(person.copy(serverId = ServerId(peer.id), serverName = peer.name), viewer, true),
                // No viewed server known: nobody is marked.
                Triple(person.copy(serverId = ServerId(peer.id), serverName = peer.name), " ", false),
            )
        cases.forEach { (member, viewerServerId, external) ->
            assertEquals(external, memberOrigin(member, viewerServerId).external, "$member @ $viewerServerId")
        }
        assertEquals(listOf(MemberServerRef(peer.id, peer.name, "")), memberOrigin(cases[2].first, viewer).servers)
    }

    @Test
    fun originServersLeaveOutTheViewedServerDuplicatesAndNamelessOnes() {
        val member =
            person.copy(
                serverId = ServerId(peer.id),
                inViewerServer = false,
                servers = listOf(MemberServerRef(viewer, "Slock", "slock"), peer, peer, acme, MemberServerRef("server-x", "", "")),
            )
        assertEquals(listOf(peer, acme), memberOrigin(member, viewer).servers)
    }

    @Test
    fun rowLabelNamesEveryServerAsFirstAndHowManyMore() {
        assertNull(memberServerNamesLabel(emptyList()))
        assertEquals("Peer Labs", memberServerNamesLabel(listOf("Peer Labs")))
        assertEquals("Peer Labs +2", memberServerNamesLabel(listOf("Peer Labs", "acme", "Lumen")))

        // Another server's person: the servers they are from (a slug stands in for a blank name).
        assertEquals(
            "Peer Labs +1",
            memberRowServerLabel(person.copy(serverId = ServerId(peer.id), inViewerServer = false, servers = listOf(peer, acme)), viewer),
        )
        // The viewed server's person who is also in another keeps that server's tag (web JointServerBadges).
        assertEquals(
            "Peer Labs",
            memberRowServerLabel(person.copy(serverId = ServerId(peer.id), inViewerServer = true, servers = listOf(peer)), viewer),
        )
        assertNull(memberRowServerLabel(person.copy(serverId = ServerId(viewer), inViewerServer = true, servers = emptyList()), viewer))
        assertNull(memberRowServerLabel(person.copy(serverId = ServerId(viewer)), viewer))
    }

    @Test
    fun removeFollowsTheServersPerRowAnswerElseOnlyTheViewedServersMembers() {
        val external = person.copy(serverId = ServerId(peer.id), inViewerServer = false)
        val localFirstSourceElsewhere = person.copy(serverId = ServerId(peer.id), inViewerServer = true)
        // A host admin may remove another server's people (joint authority).
        assertTrue(memberRemovableFromChannel(external.copy(canRemoveFromChannel = true), viewer))
        assertFalse(memberRemovableFromChannel(person.copy(serverId = ServerId(viewer), canRemoveFromChannel = false), viewer))
        // Joint participant admin: server sets channelCapabilities.removeChannelMembers = false
        // (humans cannot be removed) while still marking the participant's own agents
        // canRemoveFromChannel = true (#proj-mobile:#400, web ChannelMembers.tsx `??` parity).
        val ownAgent = person.copy(id = MemberId("agent-1"), kind = MemberKind.Agent, serverId = ServerId(viewer))
        assertTrue(memberRemovableFromChannel(ownAgent.copy(canRemoveFromChannel = true), viewer, canRemoveChannelMembers = false))
        assertFalse(memberRemovableFromChannel(person.copy(serverId = ServerId(viewer), canRemoveFromChannel = false), viewer, canRemoveChannelMembers = false))
        assertFalse(memberRemovableFromChannel(external.copy(canRemoveFromChannel = false), viewer, canRemoveChannelMembers = false))
        // Older servers / non-joint channels (canRemoveFromChannel == null): channel capability gates local members.
        assertFalse(memberRemovableFromChannel(external, viewer, canRemoveChannelMembers = true))
        assertTrue(memberRemovableFromChannel(localFirstSourceElsewhere, viewer, canRemoveChannelMembers = true))
        assertTrue(memberRemovableFromChannel(person, viewer, canRemoveChannelMembers = true))
        assertFalse(memberRemovableFromChannel(person, viewer, canRemoveChannelMembers = false))
    }

    @Test
    fun originLogoCarriesServersAvatarUrlAndFallsBackToJointChannelServers() {
        // slock #15809/#15817: a bare home server id (such as an external agent) takes its name
        // and logo from the joint channel's `jointServers[]`, while the row's own `servers[].avatarUrl`
        // wins over the channel's logo.
        val acmeLogo = "/api/avatars/server-acme/aaaa.webp"
        val channelOtherLogo = "/api/avatars/server-acme/other.webp"
        val channelAcme = MemberServerRef("server-acme", "Acme Corp", "acme", avatarUrl = acmeLogo)
        val pendingLumen = MemberServerRef("server-lumen", "Lumen", "lumen", avatarUrl = null)

        val bareAgent =
            MemberProfile(
                id = MemberId("agent-2"),
                displayName = "Bot",
                handle = "bot",
                kind = MemberKind.Agent,
                serverId = ServerId(channelAcme.id),
            )
        val fromChannel = memberOrigin(bareAgent, viewer, knownServers = listOf(channelAcme, pendingLumen))
        assertEquals(listOf(channelAcme), fromChannel.servers)

        val pendingAgent = bareAgent.copy(serverId = ServerId(pendingLumen.id))
        val fromPending = memberOrigin(pendingAgent, viewer, knownServers = listOf(channelAcme, pendingLumen))
        assertEquals(listOf(pendingLumen), fromPending.servers)

        val personWithOwnLogo =
            person.copy(
                serverId = ServerId(channelAcme.id),
                inViewerServer = false,
                servers = listOf(MemberServerRef(channelAcme.id, "Acme Corp", "acme", avatarUrl = acmeLogo)),
            )
        val ownWins =
            memberOrigin(
                personWithOwnLogo,
                viewer,
                knownServers = listOf(channelAcme.copy(avatarUrl = channelOtherLogo)),
            )
        assertEquals(acmeLogo, ownWins.servers.single().avatarUrl)
    }

    @Test
    fun wireParsersPreserveServersAvatarUrlAndChannelJointServersAvatarUrl() {
        val alphaLogo = "/api/avatars/server-alpha/1234.webp"
        val channelMembersBody =
            """{"humans":[{"id":"u-1","name":"pat","displayName":"Pat","serverId":"srv-alpha","inViewerServer":false,"servers":[{"id":"srv-alpha","name":"Alpha","slug":"alpha","avatarUrl":"$alphaLogo"}]}],"agents":[]}"""
        val parsedMembers = parseSharedChannelMemberSnapshot(channelMembersBody, ChannelId("ch-1"))
        assertEquals(alphaLogo, parsedMembers?.members?.single()?.servers?.single()?.avatarUrl)

        val memberProfileBody =
            """{"userId":"u-1","name":"pat","displayName":"Pat","serverId":"srv-alpha","servers":[{"id":"srv-alpha","name":"Alpha","slug":"alpha","avatarUrl":"$alphaLogo"}]}"""
        assertEquals(alphaLogo, parseSharedMemberProfile(memberProfileBody)?.servers?.single()?.avatarUrl)

        val channelDetailBody =
            """{"id":"ch-1","name":"acme-launch","type":"joint","jointServers":[{"serverId":"srv-alpha","serverName":"Alpha","serverSlug":"alpha","serverAvatarUrl":"$alphaLogo","role":"participant","status":"active"},{"serverId":"srv-pending","serverName":"Pending","serverSlug":"pending","serverAvatarUrl":null,"role":null,"status":"pending"}]}"""
        val parsedChannel = parseRaftChannelDetail(channelDetailBody)
        assertEquals(
            listOf(
                MemberServerRef("srv-alpha", "Alpha", "alpha", avatarUrl = alphaLogo),
                MemberServerRef("srv-pending", "Pending", "pending", avatarUrl = null),
            ),
            parsedChannel?.jointServers,
        )
    }
}
