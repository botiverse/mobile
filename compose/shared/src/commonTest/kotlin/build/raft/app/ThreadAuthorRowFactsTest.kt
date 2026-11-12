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
