package build.raft.app

import build.raft.app.data.PeerReadCountAtSeq
import build.raft.app.data.PeerReadReceiptScope
import build.raft.app.data.PeerReadState
import build.raft.app.data.PeerReadSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageMention
import build.raft.app.model.ReadReceiptPeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Web task #693 rules (MentionLink / MessageItem / ChatPanel), slock staging dac70954. */
class ThreadPeerReadReceiptsTest {
    private val peers =
        PeerReadReceiptScope.Peers(
            listOf(
                PeerReadState(ReadReceiptPeerKind.Agent, "agent-alpha", 12),
                PeerReadState(ReadReceiptPeerKind.Agent, "agent-beta", 9),
            ),
        )
    private val directory =
        mapOf(
            "alpha" to MessageMention("agent", "agent-alpha", "alpha"),
            "beta" to MessageMention("agent", "agent-beta", "beta"),
            "carol" to MessageMention("user", "user-carol", "carol"),
        )

    private fun ui(scope: PeerReadReceiptScope = peers, dm: Boolean = false, managed: Set<String> = emptySet()) =
        ThreadPeerReadReceiptsUi(scope = scope, dmSurface = dm, directoryMentions = directory, managedAgentIds = managed)

    private fun message(
        id: String = "m-10",
        seq: Long? = 10,
        own: Boolean = true,
        kind: String = "human",
        mentions: List<MessageMention> = emptyList(),
    ) = RaftThreadMessageItem(
        id = id, authorId = "user-1", authorName = "Me", authorKind = kind, isOwn = own, seq = seq, mentions = mentions,
    )

    @Test fun mentionedAgentsReadStateOnlyOnTheViewersOwnSeqMessage() {
        val states = ui().mentionReadStates(message())!!
        assertEquals(true, states.readStateFor("@alpha"))
        assertEquals(false, states.readStateFor("@beta"))
        // Web MentionEntry keys are exact handles: no case folding, no display-name match.
        assertNull(states.readStateFor("@Beta"))
        // A human or an unknown mention is unknown, never "unread".
        assertNull(states.readStateFor("@carol"))
        assertNull(states.readStateFor("@dave"))
        assertNull(ui().mentionReadStates(message(own = false)))
        assertNull(ui().mentionReadStates(message(seq = null)))
        assertNull(ui().mentionReadStates(message(kind = "agent")))
    }

    @Test fun messageMentionIdsWinOverTheDirectoryLikeWebStructuredMentionMap() {
        // The server resolved @beta on this message to agent-alpha (e.g. renamed since): the id on
        // the message decides, not the page's current name directory.
        val resolved = message(mentions = listOf(MessageMention("agent", "agent-alpha", "beta")))
        assertEquals(true, ui().mentionReadStates(resolved)!!.readStateFor("@beta"))
        // A structured `user` mention never gets an agent badge even if an agent has that handle.
        val human = message(mentions = listOf(MessageMention("user", "user-x", "alpha")))
        assertNull(ui().mentionReadStates(human)!!.readStateFor("@alpha"))
        // A structured mention of an agent that is not a read peer stays unknown.
        val stranger = message(mentions = listOf(MessageMention("agent", "agent-zed", "zed")))
        assertNull(ui().mentionReadStates(stranger)!!.readStateFor("@zed"))
    }

    @Test fun appManagedBotsNeverCarryABadge() {
        val states = ui(managed = setOf("agent-alpha")).mentionReadStates(message())!!
        assertNull(states.readStateFor("@alpha"))
        assertEquals(false, states.readStateFor("@beta"))
    }

    @Test fun directoryFollowsWebBuildMentionMap() {
        val server =
            listOf(
                MemberProfile(id = MemberId("agent-alpha"), displayName = "Alpha Bot", handle = "alpha", kind = MemberKind.Agent,
                    managedByAppInstallId = "install-1"),
                MemberProfile(id = MemberId("agent-shared"), displayName = "Shared", handle = "shared", kind = MemberKind.Agent),
                MemberProfile(id = MemberId("user-shared"), displayName = "Shared Human", handle = "shared", kind = MemberKind.Human),
            )
        val channel = listOf(RaftThreadMemberItem(id = "agent-beta", displayName = "Beta", handle = "@beta", kind = "agent"))
        val map = threadMentionDirectory(server, channel)
        assertEquals("agent-alpha", map["alpha"]?.id)
        assertEquals("agent-beta", map["beta"]?.id)
        // Display names are not mention keys; humans win a shared handle, as on web.
        assertNull(map["Alpha Bot"])
        assertEquals(MessageMention("user", "user-shared", "shared"), map["shared"])
        assertEquals(setOf("agent-alpha"), threadManagedAgentIds(server))
    }

    @Test fun summaryScopesShowNoMentionBadges() {
        val summary = PeerReadReceiptScope.Summary(PeerReadSummary(60, listOf(PeerReadCountAtSeq(20, 3))))
        assertNull(ui(summary).mentionReadStates(message()))
    }

    @Test fun dmReadLineIsOnlyForTheOwnLastMessageAtTheLiveEdgeOnceThePeerRead() {
        val dm = ui(PeerReadReceiptScope.Peers(listOf(PeerReadState(ReadReceiptPeerKind.Agent, "agent-alpha", 10))), dm = true)
        assertTrue(dm.showsDmReadStatus(message()))
        assertFalse(dm.showsDmReadStatus(message(seq = 11)))
        assertFalse(dm.showsDmReadStatus(message(own = false)))
        // Channels never show an aggregate chip, even when an agent has read the message.
        assertFalse(ui().showsDmReadStatus(message()))

        val slice = RaftThreadScreenPayload(messages = listOf(message("m-9", 9), message("m-10", 10))).toThreadTimelineSlice()
        assertEquals("m-10", slice.dmReadStatusMessageId(dm))
        assertNull(slice.copy(canLoadMoreAfter = true).dmReadStatusMessageId(dm))
        assertNull(slice.dmReadStatusMessageId(ui()))
        assertNull(slice.dmReadStatusMessageId(null))
    }

    @Test fun scopeEligibilityFollowsTheWeb() {
        val channel = RaftThreadScreenPayload(title = "#design", streamChannelId = "ch-1")
        assertEquals("ch-1", channel.peerReadReceiptScopeId())
        assertNull(channel.copy(title = "#all").peerReadReceiptScopeId())
        assertNull(channel.copy(channelKind = JointChannelKindWireValue).peerReadReceiptScopeId())
        // A thread is its own scope; the server decides from its parent (a thread under #all hydrates off).
        val thread = RaftThreadScreenPayload(title = "#all", surfaceKind = RaftThreadSurfaceKind.ReplyThread, streamChannelId = "th-1")
        assertEquals("th-1", thread.peerReadReceiptScopeId())
        assertTrue(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage).isPeerReadReceiptDmSurface())
        assertTrue(thread.copy(parentChannelKind = "dm").isPeerReadReceiptDmSurface())
        assertFalse(thread.isPeerReadReceiptDmSurface())
    }
}
