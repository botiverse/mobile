package build.raft.app.data

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
        // Older servers: today's rule, now read through the origin.
        assertFalse(memberRemovableFromChannel(external, viewer))
        assertTrue(memberRemovableFromChannel(localFirstSourceElsewhere, viewer))
        assertTrue(memberRemovableFromChannel(person, viewer))
    }
}
