package build.raft.app

import build.raft.app.data.SharedMembersMemberSuggestion
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberServerRef
import build.raft.app.model.ServerId
import build.raft.app.thread.toRaftComposerMemberItem
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

        // slock #15817: row's own `servers[].avatarUrl` wins, else channel `jointServers[].serverAvatarUrl`,
        // else viewer's joined server logo, else null (dot/initial).
        val joined = raftViewerServerLogos(listOf(acme.id to "/joined-acme.webp", peer.id to "/joined-peer.webp"))
        val channelLogo = "/channel-acme.webp"
        val rowLogo = "/row-acme.webp"
        val combined =
            raftOriginServerLogos(
                viewerServers = listOf(acme.id to "/joined-acme.webp", peer.id to "/joined-peer.webp"),
                channelJointServers = listOf(acme.copy(avatarUrl = channelLogo), peer.copy(avatarUrl = null)),
            )
        assertEquals(channelLogo, combined[acme.id])
        // Null/absent channel logo preserves the viewer's joined server logo.
        assertEquals("/joined-peer.webp", combined[peer.id])
        assertEquals(rowLogo, raftOriginServerLogo(acme.copy(avatarUrl = rowLogo), combined))
        assertEquals(channelLogo, raftOriginServerLogo(acme, combined))
        assertEquals("/joined-acme.webp", raftOriginServerLogo(acme, joined))
        assertNull(raftOriginServerLogo(MemberServerRef("server-unknown", "Unknown"), combined))
        assertNull(raftOriginServerLogo(null, combined))
    }

    @Test
    fun theMentionListMarksOnlyANamedCandidateFromAnotherServer() {
        val candidate = RaftComposerMemberItem(displayName = "Ada", handle = "ada", sourceServerId = peer.id, isLocalServer = false, sourceServerLabel = "Peer Labs +1")
        assertEquals(listOf(MemberServerRef(peer.id, "Peer Labs +1")), candidate.mentionOriginServers())
        // Candidate carrying `originServers` keeps `servers[].avatarUrl` for the @ badge.
        val peerWithLogo = peer.copy(avatarUrl = "/peer-logo.webp")
        assertEquals(
            listOf(peerWithLogo),
            candidate.copy(originServers = listOf(peerWithLogo)).mentionOriginServers(),
        )
        // One of the viewed server's own, even when they are also in another server.
        assertEquals(emptyList(), candidate.copy(isLocalServer = true, originServers = listOf(peerWithLogo)).mentionOriginServers())
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

        // slock #15817: bare-serverId external agent resolves name + logo from channel jointServers.
        val peerWithLogo = peer.copy(avatarUrl = "/peer-joint-logo.webp")
        val bareAgent =
            MemberProfile(
                id = MemberId("a-ext-bare"),
                displayName = "Volta",
                handle = "volta",
                kind = MemberKind.Agent,
                serverId = ServerId(peer.id),
            )
        val resolvedBare =
            raftProfilePreviewCardModel(
                bareAgent,
                viewerServerId = viewer,
                knownServers = listOf(peerWithLogo),
            )
        assertTrue(resolvedBare.external)
        assertEquals(listOf(peerWithLogo), resolvedBare.originServers)
    }

    @Test
    fun mentionSuggestionResolvesBareExternalAgentFromChannelJointServers() {
        val peerWithLogo = peer.copy(avatarUrl = "/peer-joint-logo.webp")
        val bareSuggestion =
            SharedMembersMemberSuggestion(
                displayName = "Volta",
                handle = "volta",
                inMentionChannel = true,
                sourceServerId = peer.id,
                isLocalServer = false,
                sourceServerLabel = null,
                kind = MemberKind.Agent,
                memberId = "agent-volta",
                originServers = emptyList(),
            )
        val item = bareSuggestion.toRaftComposerMemberItem(inThread = false, knownServers = listOf(peerWithLogo))
        assertEquals("Peer Labs", item.sourceServerLabel)
        assertEquals(listOf(peerWithLogo), item.mentionOriginServers())

        // Row's own servers[].avatarUrl wins over channel jointServers[].serverAvatarUrl.
        val rowLogoPeer = peer.copy(avatarUrl = "/peer-row-logo.webp")
        val richSuggestion = bareSuggestion.copy(originServers = listOf(rowLogoPeer), sourceServerLabel = "Peer Labs")
        val richItem = richSuggestion.toRaftComposerMemberItem(inThread = true, knownServers = listOf(peerWithLogo))
        assertEquals(listOf(rowLogoPeer), richItem.mentionOriginServers())

        // Local member never receives an external badge even if jointServers has entries,
        // while preserving any extra server label (e.g. a local member who also shares another server).
        val localItem =
            bareSuggestion
                .copy(
                    sourceServerId = viewer,
                    isLocalServer = true,
                    sourceServerLabel = "Peer Labs",
                    originServers = listOf(rowLogoPeer),
                )
                .toRaftComposerMemberItem(inThread = false, knownServers = listOf(peerWithLogo))
        assertEquals("Peer Labs", localItem.sourceServerLabel)
        assertEquals(emptyList(), localItem.mentionOriginServers())
    }
}
