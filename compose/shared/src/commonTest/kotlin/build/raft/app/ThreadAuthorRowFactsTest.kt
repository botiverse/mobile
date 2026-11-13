package build.raft.app

import build.raft.app.data.parseSharedSettingsAgentsAsMembers
import build.raft.app.model.ExternalMessageAuthor
import build.raft.app.model.Message
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The message author row (dm:@artin:#120; web MessageItem, botiverse/slock #14629 b3e6366a8;
 * raft-design #1241 message-item.md): name, model (this server's agents, not a personal
 * assistant), Guest (this server's), then the description, directory first. Never a role.
 */
class ThreadAuthorRowFactsTest {
    private val server = ServerId("server-1")
    private val agent =
        MemberProfile(
            id = MemberId("agent-1"),
            displayName = "Mira",
            handle = "mira",
            kind = MemberKind.Agent,
            roleDescription = "claude",
            model = "sonnet",
            serverId = server,
        )
    private val person =
        MemberProfile(id = MemberId("user-2"), displayName = "Ada", handle = "ada", kind = MemberKind.Human, serverId = server)

    @Test
    fun modelOnlyForThisServersAgentsAndNeverAPersonalAssistants() {
        assertEquals("Claude Sonnet", agent.authorRowModelLabel(server))
        assertEquals("Claude Sonnet", agent.copy(serverId = null).authorRowModelLabel(server))
        assertNull(agent.copy(assistantOwnerUserId = "user-9").authorRowModelLabel(server))
        assertNull(agent.copy(serverId = ServerId("server-peer")).authorRowModelLabel(server))
    }

    @Test
    fun aJointRosterDecidesAnotherServerByInViewerServerNotTheFirstSource() {
        // #4656 memberOrigin: a computed joint roster's `serverId` is only the first source.
        val peer = ServerId("server-peer")
        // One of this server's members whose first source is the peer: model and Guest stay.
        assertEquals("Claude Sonnet", agent.copy(serverId = peer, inViewerServer = true).authorRowModelLabel(server))
        assertTrue(person.copy(serverRole = "guest", serverId = peer, inViewerServer = true).authorFacts(server).viewerServerGuest)
        // Not in the viewed server, whatever `serverId` says: no model, no Guest.
        assertNull(agent.copy(serverId = server, inViewerServer = false).authorRowModelLabel(server))
        assertFalse(person.copy(serverRole = "guest", serverId = server, inViewerServer = false).authorFacts(server).viewerServerGuest)
    }

    @Test
    fun aSenderFromAnotherServerCarriesItsServersForTheAvatarCorner() {
        // web #14535 useSenderOrigin / AvatarOriginCorner: the directory's (or joint roster's) origin.
        val peer = build.raft.app.model.MemberServerRef("server-peer", "Peer Labs", "peer-labs")
        val acme = build.raft.app.model.MemberServerRef("server-acme", "Acme", "acme")
        val external = person.copy(serverId = ServerId(peer.id), inViewerServer = false, servers = listOf(peer, acme))
        assertEquals(listOf(peer, acme), external.authorFacts(server).originServers)
        assertEquals(listOf(peer.copy(slug = "")), person.copy(serverId = ServerId(peer.id), serverName = peer.name).authorFacts(server).originServers)
        // This server's own, and one of them whose first source is the peer: no corner.
        assertEquals(emptyList(), person.authorFacts(server).originServers)
        assertEquals(emptyList(), external.copy(inViewerServer = true).authorFacts(server).originServers)
        // An external sender whose server was not named has no tile to draw.
        assertEquals(emptyList(), person.copy(serverId = ServerId(peer.id)).authorFacts(server).originServers)

        val facts = external.authorFacts(server)
        val row = RaftThreadMessageItem(id = "m-2", authorId = "user-2", authorName = "Ada", authorKind = "human")
        assertEquals(listOf(peer, acme), threadAuthorRowOriginServers(row, facts))
        // A bridged (Slack) sender's provider is not a server; a system row has no sender.
        assertEquals(emptyList(), threadAuthorRowOriginServers(row.copy(authorKind = "external", externalAuthor = ExternalMessageAuthor(displayName = "Al", provider = "slack", actorKind = "user")), facts))
        assertEquals(emptyList(), threadAuthorRowOriginServers(row.copy(authorKind = "system"), facts))
    }

    @Test
    fun assistantOwnerIsReadFromTheAgentDirectory() {
        // botiverse/slock ec13decd3 routes/agents.ts GET /agents: the agent row's `assistantOwnerUserId`.
        val members =
            parseSharedSettingsAgentsAsMembers(
                """{"agents":[{"id":"a-1","name":"pa","status":"active","assistantOwnerUserId":"user-9"},{"id":"a-2","name":"plain","status":"active"}]}"""
            )!!.associateBy { it.id.value }
        assertEquals("user-9", members.getValue("a-1").assistantOwnerUserId)
        assertNull(members.getValue("a-2").assistantOwnerUserId)
    }

    @Test
    fun guestIsAFactOfTheViewedServersPeopleOnly() {
        val cases =
            listOf(
                person.copy(serverRole = "guest") to true,
                // A guest viewer's roster sends only the effective channel role.
                person.copy(effectiveChannelRole = "guest") to true,
                person.copy(serverRole = "admin") to false,
                person.copy(serverRole = "guest", serverId = ServerId("server-peer")) to false,
                agent.copy(serverRole = "guest") to false,
            )
        cases.forEach { (member, guest) -> assertEquals(guest, member.authorFacts(server).viewerServerGuest, "$member") }
    }

    @Test
    fun descriptionComesFromTheDirectoryFirstThenTheMessage() {
        val live = RaftThreadMessageItem(id = "m-1", authorId = "agent-1", authorName = "Mira", authorKind = "agent")
        val facts = agent.copy(description = "  Ships the builds ").authorFacts(server)

        // A live `message:new` carries no senderDescription: the directory's shows at once.
        assertEquals("Ships the builds", threadAuthorRowDescription(live, facts))
        assertEquals("Ships the builds", threadAuthorRowDescription(live.copy(authorDescription = "Old"), facts))
        // Nothing in the directory (another server's agent): the message's own.
        assertEquals("Old", threadAuthorRowDescription(live.copy(authorDescription = " Old "), null))
        assertEquals("Old", threadAuthorRowDescription(live.copy(authorDescription = "Old"), agent.authorFacts(server)))
        assertNull(threadAuthorRowDescription(live.copy(authorDescription = "   "), RaftMemberAuthorFacts(description = null)))

        // A bridged sender keeps its provider.
        val slack = live.copy(authorKind = "external", authorDescription = "Slack", externalAuthor = ExternalMessageAuthor(displayName = "Al", provider = "slack", actorKind = "user"))
        assertEquals("Slack", threadAuthorRowDescription(slack, facts))

        // Settings > Appearance "Show member descriptions" off (web #14653): none for an agent or
        // a person, from the directory or the message; a bridged sender's provider stays.
        assertNull(threadAuthorRowDescription(live.copy(authorDescription = "Old"), facts, showDescription = false))
        val human = live.copy(authorId = "user-2", authorKind = "human", authorDescription = "Designs things")
        assertNull(threadAuthorRowDescription(human, person.copy(description = "Designs").authorFacts(server), showDescription = false))
        assertNull(threadAuthorRowDescription(human, null, showDescription = false))
        assertEquals("Slack", threadAuthorRowDescription(slack, facts, showDescription = false))
        assertFalse(threadAuthorRowShowsGuest(slack, RaftMemberAuthorFacts(viewerServerGuest = true)))
        assertTrue(threadAuthorRowShowsGuest(live.copy(authorKind = "human"), RaftMemberAuthorFacts(viewerServerGuest = true)))
        assertFalse(threadAuthorRowShowsGuest(live.copy(authorKind = "human"), null))
    }

    @Test
    fun theRowNeverFallsBackToTheRole() {
        val message =
            Message(
                id = MessageId("m-1"),
                threadId = ThreadId("channel-1"),
                author = person.copy(description = null, roleDescription = "Owner"),
                timestampLabel = "2026-11-12T10:00:00Z",
                body = "hi",
            )
        assertNull(message.toRaftThreadMessageItem(currentUserId = null).authorDescription)
        assertEquals(
            "Designs things",
            message.copy(author = person.copy(description = "Designs things", roleDescription = "Owner"))
                .toRaftThreadMessageItem(currentUserId = null)
                .authorDescription,
        )
    }
}
