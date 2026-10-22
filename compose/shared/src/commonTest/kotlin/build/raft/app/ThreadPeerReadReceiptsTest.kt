package build.raft.app

import build.raft.app.data.PeerReadCountAtSeq
import build.raft.app.data.PeerReadReceiptScope
import build.raft.app.data.PeerReadState
import build.raft.app.data.PeerReadSummary
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
    private val mentionKeys = mapOf("alpha" to "agent-alpha", "beta" to "agent-beta")

    private fun ui(scope: PeerReadReceiptScope = peers, dm: Boolean = false) =
        ThreadPeerReadReceiptsUi(scope = scope, dmSurface = dm, agentIdsByMentionKey = mentionKeys)

    private fun message(id: String = "m-10", seq: Long? = 10, own: Boolean = true, kind: String = "human") =
        RaftThreadMessageItem(id = id, authorId = "user-1", authorName = "Me", authorKind = kind, isOwn = own, seq = seq)

    @Test fun mentionedAgentsReadStateOnlyOnTheViewersOwnSeqMessage() {
        val states = ui().mentionReadStates(message())!!
        assertEquals(true, states.readStateFor("@alpha"))
        assertEquals(false, states.readStateFor("@Beta"))
        // A human or an unknown mention is unknown, never "unread".
        assertNull(states.readStateFor("@carol"))
        assertNull(ui().mentionReadStates(message(own = false)))
        assertNull(ui().mentionReadStates(message(seq = null)))
        assertNull(ui().mentionReadStates(message(kind = "agent")))
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
