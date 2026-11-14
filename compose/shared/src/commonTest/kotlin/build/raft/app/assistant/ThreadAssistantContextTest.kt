package build.raft.app.assistant

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Web packages/web/src/assistant/AssistantContextChip.tsx and MessageInput withComposerReference
// (slock #12335; tests/assistantContextChip.behavior.test.tsx).
class ThreadAssistantContextTest {
    private val general = AssistantContextConversation.Channel("general")
    private val aliceAgent = AssistantContextConversation.DirectMessage(peerName = "alice", peerKind = MemberKind.Agent, displayName = "Alice")
    private val parentId = "1A2B3C4D-0000-4000-8000-000000000000"

    @Test
    fun aChannelIsItsNameAndItsToken() {
        assertEquals(
            AssistantContextRef(
                kind = AssistantContextKind.Channel,
                conversation = "#general",
                threadExcerpt = null,
                token = "#general",
                readable = true,
            ),
            assistantContextRef(general, thread = null, readsDms = false),
        )
    }

    @Test
    fun aThreadAddsTheParentsShortIdLowercasedAndItsFirstLine() {
        val ref = assistantContextRef(general, AssistantContextThread(parentId, "\n  Ship   the\tbuild  \nsecond line"), readsDms = false)!!
        assertEquals("#general:1a2b3c4d", ref.token)
        assertEquals("#general", ref.conversation)
        assertEquals("Ship the build", ref.threadExcerpt)
        // A parent with no text (or not loaded): the thread line without an excerpt.
        assertEquals("", assistantContextRef(general, AssistantContextThread(parentId, null), readsDms = false)!!.threadExcerpt)
    }

    @Test
    fun aDmCarriesThePeersKindAndIsReadableOnlyWhileTheOwnerLetsIt() {
        val ref = assistantContextRef(aliceAgent, thread = null, readsDms = false)!!
        assertEquals("dm:@alice~agent", ref.token)
        assertEquals("@Alice", ref.conversation)
        assertEquals(AssistantContextKind.DirectMessage, ref.kind)
        assertFalse(ref.readable)
        assertTrue(assistantContextRef(aliceAgent, thread = null, readsDms = true)!!.readable)
        val human = AssistantContextConversation.DirectMessage(peerName = "bob.lee", peerKind = MemberKind.Human, displayName = "")
        assertEquals("dm:@bob.lee~human:1a2b3c4d", assistantContextRef(human, AssistantContextThread(parentId, "hi"), readsDms = true)!!.token)
        assertEquals("@bob.lee", assistantContextRef(human, thread = null, readsDms = true)!!.conversation, "no display name: the handle")
        val unknownKind = AssistantContextConversation.DirectMessage(peerName = "carol", peerKind = null, displayName = "Carol")
        assertEquals("dm:@carol", assistantContextRef(unknownKind, thread = null, readsDms = true)!!.token)
    }

    @Test
    fun nothingTheReferenceGrammarCantCarry() {
        assertNull(assistantContextRef(AssistantContextConversation.Channel("with space"), thread = null, readsDms = true))
        assertNull(assistantContextRef(AssistantContextConversation.Channel(""), thread = null, readsDms = true))
        assertNull(
            assistantContextRef(
                AssistantContextConversation.DirectMessage(peerName = "李雷", peerKind = MemberKind.Human, displayName = "李雷"),
                thread = null,
                readsDms = true,
            ),
            "a DM peer name is ASCII word characters, '.' and '-'",
        )
        assertNull(assistantContextRef(general, AssistantContextThread("msg-local-1", "hi"), readsDms = true), "not a hex short id")
        // A channel name may be any letters or digits.
        assertEquals("#设计-team", assistantContextRef(AssistantContextConversation.Channel("设计-team"), thread = null, readsDms = true)!!.token)
    }

    @Test
    fun theExcerptIsCutAt48WithAnEllipsis() {
        val long = "a".repeat(47) + " " + "b".repeat(10)
        assertEquals("a".repeat(47) + "…", assistantContextExcerpt(long), "cut, then trailing space trimmed")
        assertEquals("x".repeat(48), assistantContextExcerpt("x".repeat(48)))
        assertEquals("", assistantContextExcerpt("  \n \n"))
    }

    @Test
    fun theTokenGoesOnALineOfItsOwnAfterTheText() {
        assertEquals("look at this\n\n#general", withAssistantContextReference("look at this  \n", "#general"))
        assertEquals("look at this", withAssistantContextReference("look at this", null))
    }

    @Test
    fun onlyAMessageWithTextCarriesTheToken() {
        val ref = assistantContextRef(general, thread = null, readsDms = true)
        assertEquals("#general", assistantContextTokenForSend("hi", ref))
        assertNull(assistantContextTokenForSend("   ", ref), "attachments only: never sent alone")
        assertNull(assistantContextTokenForSend("hi", null), "no chip")
    }

    @Test
    fun eachOpenAttachesAndRemoveOrSendDetaches() {
        val attachment = ThreadAssistantContextAttachment()
        attachment.origin = assistantContextRef(general, thread = null, readsDms = true)
        assertNull(attachment.context, "not opened yet")
        attachment.attach()
        assertEquals("#general", attachment.context?.token)
        attachment.detach()
        assertNull(attachment.context)
        // The next open attaches it afresh.
        attachment.attach()
        assertEquals("#general", attachment.context?.token)
        // Opened from somewhere with no conversation: nothing to attach.
        attachment.origin = null
        assertNull(attachment.context)
    }

    @Test
    fun theConversationComesFromHomeAndTheRoster() {
        val home =
            SharedHomeState(
                content =
                    SharedHomeContentState.Content(
                        snapshot =
                            HomeSnapshot(
                                server = ServerSummary(id = ServerId("s1"), name = "One"),
                                currentMember = MemberProfile(id = MemberId("u1"), displayName = "Viewer", handle = "viewer", kind = MemberKind.Human),
                                channels =
                                    listOf(
                                        HomeChannelRow(threadId = ThreadId("c1"), channelId = ChannelId("c1"), name = "general"),
                                        HomeChannelRow(threadId = ThreadId("j1"), channelId = ChannelId("j1"), kind = ConversationKind.Joint, name = "partners"),
                                        HomeChannelRow(threadId = ThreadId("l1"), channelId = ChannelId("l1"), name = "lounge", loungeAgentId = "a9"),
                                    ),
                                directMessages =
                                    listOf(
                                        HomeDirectMessageRow(
                                            threadId = ThreadId("dm1"),
                                            channelId = ChannelId("dm1"),
                                            name = "Ada Lovelace",
                                            directMessagePeerType = "user",
                                            presenceMemberId = MemberId("m1"),
                                        ),
                                        HomeDirectMessageRow(threadId = ThreadId("dm2"), channelId = ChannelId("dm2"), name = "Ghost", presenceMemberId = MemberId("m404")),
                                    ),
                                unreadTotal = 0,
                            ),
                        session = StoredAuthSession(user = StoredAuthUser(id = "u1", email = "u1@raft.test", displayName = "Viewer"), accessToken = "a", refreshToken = "r", selectedServerId = "s1"),
                    )
            )
        val roster = mapOf("m1" to MemberProfile(id = MemberId("m1"), displayName = "Ada Lovelace", handle = "ada", kind = MemberKind.Agent))
        fun lookup(channelId: String, serverId: String = "s1") = home.assistantContextConversation(serverId, channelId) { roster[it] }

        assertEquals(AssistantContextConversation.Channel("general"), lookup("c1"))
        assertEquals(AssistantContextConversation.Channel("partners"), lookup("j1"))
        assertNull(lookup("l1"), "a Lounge has no token")
        assertEquals(AssistantContextConversation.DirectMessage(peerName = "ada", peerKind = MemberKind.Agent, displayName = "Ada Lovelace"), lookup("dm1"))
        assertNull(lookup("dm2"), "the peer's handle unknown: no token to send")
        assertNull(lookup("nope"))
        assertNull(lookup("c1", serverId = "s2"), "Home holds another server")
        assertNull(SharedHomeState().assistantContextConversation("s1", "c1") { null }, "Home not loaded")
    }
}
