package build.raft.app

import build.raft.app.data.PeerReadReceiptHydrateResult
import build.raft.app.data.PeerReadReceiptIntent
import build.raft.app.data.PeerReadReceiptOwner
import build.raft.app.data.PeerReadReceiptScope
import build.raft.app.data.PeerReadState
import build.raft.app.data.SharedPeerReadReceiptStore
import build.raft.app.model.MessageMention
import build.raft.app.model.ReadReceiptPeerKind
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadPeerReadReceiptsUi
import build.raft.app.thread.bindThreadPeerReadReceiptsOwner
import build.raft.app.thread.dispatch
import build.raft.app.thread.dmReadStatusMessageId
import build.raft.app.thread.mentionReadStates
import build.raft.app.thread.showsDmReadStatus
import build.raft.app.thread.toThreadTimelineSlice
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext
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

    @Test fun unresolvedNullOwnerDoesNotWipeShellPeerReadReceipts() {
        val owner = PeerReadReceiptOwner("https://api.raft.test", "user-1", "srv-1")
        val otherOwner = owner.copy(serverId = "srv-2")
        val store =
            SharedPeerReadReceiptStore(
                scope = CoroutineScope(EmptyCoroutineContext),
                fetchHydrate = { _, _ -> PeerReadReceiptHydrateResult.Loaded(null) },
            )
        bindThreadPeerReadReceiptsOwner(store, owner)
        store.dispatch(PeerReadReceiptIntent.HydrateLoaded(owner, "ch-1", peers))
        assertEquals(peers, store.state.value.scopes["ch-1"])

        // Opening another conversation before its session loads passes owner=null: keep existing scopes.
        bindThreadPeerReadReceiptsOwner(store, null)
        assertEquals(owner, store.state.value.owner)
        assertEquals(peers, store.state.value.scopes["ch-1"])

        // Switching to a different resolved owner still clears the previous owner's scopes.
        bindThreadPeerReadReceiptsOwner(store, otherOwner)
        assertEquals(otherOwner, store.state.value.owner)
        assertTrue(store.state.value.scopes.isEmpty())
    }
}
