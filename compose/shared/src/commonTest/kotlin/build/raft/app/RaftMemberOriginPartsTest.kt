package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberServerRef
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Member labels for people from another server (web botiverse/slock #14535 `MemberLabelParts.tsx`,
 * `memberOrigin.ts`): the avatar corner's geometry, the names it is read as, the logos it may show,
 * the @ list's dot and the profile card's ribbon facts.
 */
class RaftMemberOriginPartsTest {
    private val viewer = "server-1"
    private val peer = MemberServerRef("server-peer", "Peer Labs", "peer-labs")
    private val acme = MemberServerRef("server-acme", "Acme", "acme")

    @Test
    fun theCornerTakesAShareOfTheFaceAndIsADotOnSmallFaces() {
        // web: `size-[clamp(7px,42cqw,19px)]`, `-top/-left-[clamp(3px,9cqw,5px)]`, initial `clamp(8px,23cqw,11px)`.
        val message = raftAvatarOriginCornerGeometry(35.dp)
        assertEquals(14.7f, message.tile.value, 0.01f)
        assertEquals(3.15f, message.overhang.value, 0.01f)
        assertEquals(8.05f, message.initialSize.value, 0.01f)
        assertFalse(message.dot)

        val card = raftAvatarOriginCornerGeometry(51.dp)
        assertEquals(19.dp, card.tile)
        assertEquals(4.59f, card.overhang.value, 0.01f)
        assertEquals(11.sp, card.initialSize)

        // `@max-[26px]/origin`: a plain dot under a 26dp face; the @ list's xs faces always.
        val small = raftAvatarOriginCornerGeometry(19.dp)
        assertTrue(small.dot)
        assertEquals(7.98f, small.tile.value, 0.01f)
        assertEquals(3.dp, small.overhang)
        assertTrue(raftAvatarOriginCornerGeometry(35.dp, forceDot = true).dot)
    }

    @Test
    fun serversReadAsTheirNamesAndOnlyTheViewersOwnServersBringALogo() {
        assertEquals("Peer Labs, Acme", raftOriginServerNames(listOf(peer, acme)))
        // A server named only by its slug is read by its slug.
        assertEquals("acme", raftOriginServerNames(listOf(acme.copy(name = " "))))
        assertEquals("", raftOriginServerNames(emptyList()))
        assertEquals("P", peer.originInitial())

        assertEquals(
            mapOf("server-acme" to "https://cdn.example/acme.png"),
            raftViewerServerLogos(listOf(" server-acme " to "https://cdn.example/acme.png", "server-1" to null, "server-2" to " ")),
        )
    }

    @Test
    fun theMentionListMarksOnlyANamedCandidateFromAnotherServer() {
        val candidate = RaftComposerMemberItem(displayName = "Ada", handle = "ada", sourceServerId = peer.id, isLocalServer = false, sourceServerLabel = "Peer Labs +1")
        assertEquals(listOf(MemberServerRef(peer.id, "Peer Labs +1")), candidate.mentionOriginServers())
        // One of the viewed server's own, even when they are also in another server.
        assertEquals(emptyList(), candidate.copy(isLocalServer = true).mentionOriginServers())
        // From another server, but its server was not named: nothing to mark (web: no first server).
        assertEquals(emptyList(), candidate.copy(sourceServerLabel = null).mentionOriginServers())
    }

    @Test
    fun theProfileCardOfSomeoneFromAnotherServerCarriesTheRibbonAndNoneOfThisServersFacts() {
        val agent =
            MemberProfile(
                id = MemberId("a1"),
                displayName = "Bugen",
                handle = "bugen",
                kind = MemberKind.Agent,
                roleDescription = "claude",
                model = "claude-sonnet-4-6",
                serverId = ServerId(peer.id),
                inViewerServer = false,
                servers = listOf(peer, acme),
            )
        val external = raftProfilePreviewCardModel(agent, viewerServerId = viewer)
        assertTrue(external.external)
        assertEquals(listOf(peer, acme), external.originServers)
        // web `hideAgentServerFacts`: no Computer, Runtime or Model rows for another server's agent.
        assertNull(external.computer)
        assertNull(external.runtimeLabel)
        assertNull(external.modelLabel)

        // The same agent seen from its own server: no ribbon, every row.
        val local = raftProfilePreviewCardModel(agent.copy(serverId = ServerId(viewer), inViewerServer = null, servers = null), viewerServerId = viewer)
        assertFalse(local.external)
        assertEquals(emptyList(), local.originServers)
        assertNotNull(local.runtimeLabel)
        assertNotNull(local.computer)

        // A person from another server whose server was not named still reads External.
        val person = MemberProfile(id = MemberId("u1"), displayName = "Ada", handle = "ada", kind = MemberKind.Human, serverId = ServerId(peer.id))
        val unnamed = raftProfilePreviewCardModel(person, viewerServerId = viewer)
        assertTrue(unnamed.external)
        assertEquals(emptyList(), unnamed.originServers)
        // Without a viewed server nothing is decided.
        assertFalse(raftProfilePreviewCardModel(person).external)
    }
}
