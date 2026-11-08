package build.raft.app

import build.raft.app.data.PeerReadReceiptScope
import build.raft.app.data.PeerReadState
import build.raft.app.data.PeerReadSummary
import build.raft.app.data.parsePeerReadReceiptHydrate
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageMention
import build.raft.app.model.ReadReceiptPeerKind
import build.raft.app.model.ScopeReadUpdated
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.ThreadAgentReadState.Read
import build.raft.app.ThreadAgentReadState.Replied
import build.raft.app.ThreadAgentReadState.StoppedFollowing
import build.raft.app.ThreadAgentReadState.Unread
import build.raft.app.ThreadAgentReadState.Working
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The thread's agent read status line, ported from botiverse/slock staging ecc7b14
 * `packages/web/src/store/agentReadProgressDomain.ts` (`projectAgentReadStatus`,
 * `formatAgentReadStatus`, `relevantReadAgentIds`, `ownLatestMessageId`) and its tests.
 */
class ThreadAgentReadStatusTest {
    private val en =
        ThreadAgentReadStatusStrings(
            replied = "%1\$s replied",
            working = "%1\$s working",
            read = "%1\$s read",
            unread = "%1\$s not yet",
            stoppedFollowing = "Stopped following: %1\$s",
            stoppedFollowingHint = "Mention them to reach them",
            allReplied = "All replied",
            noneRead = "No one has read it yet",
            more = "+%1\$d",
            label = "Agents on this message: %1\$s",
            listTwo = "%1\$s and %2\$s",
            listStart = "%1\$s, %2\$s",
            listEnd = "%1\$s, and %2\$s",
        )
    private val names = mapOf("a-niko" to "Niko", "a-ray" to "Ray", "a-cindy" to "Cindy", "a-tenny" to "Tenny", "a-kirby" to "Kirby", "a-ottie" to "Ottie")

    private fun agent(id: String, seq: Long, body: String = "on it") =
        RaftThreadMessageItem(id = "m-$seq", authorId = id, authorName = names[id] ?: id, authorKind = "agent", seq = seq, body = body)

    private fun mine(seq: Long?, vararg mentioned: String, id: String = "m-$seq") =
        RaftThreadMessageItem(
            id = id, authorId = "u-me", authorName = "Me", authorKind = "human", isOwn = true, seq = seq,
            mentions = mentioned.map { MessageMention("agent", it, names[it] ?: it) },
        )

    private fun other(seq: Long) = RaftThreadMessageItem(id = "m-$seq", authorId = "u-other", authorName = "Ann", authorKind = "human", seq = seq)

    private fun system(seq: Long) = RaftThreadMessageItem(id = "m-$seq", authorId = "", authorName = "", messageType = "System", seq = seq)

    private fun entries(vararg pairs: Pair<String, ThreadAgentReadState>) = pairs.map { (id, state) -> ThreadAgentReadEntry(id, state) }

    private fun format(entries: List<ThreadAgentReadEntry>, maxNames: Int = 3, hint: String? = null) =
        formatThreadAgentReadStatus(entries, { names[it] ?: it }, en, maxNames, hint)

    // --- projectAgentReadStatus: every state ---

    @Test fun eachRelevantAgentIsWorkingRepliedReadOrNotYetAndSortedForDisplay() {
        val messages = listOf(agent("a-niko", 1), agent("a-ray", 2), agent("a-cindy", 3), agent("a-tenny", 4), mine(5), agent("a-ray", 6))
        val positions = mapOf("a-niko" to 2L, "a-ray" to 6L, "a-cindy" to 5L, "a-tenny" to 4L)
        val relevant = threadRelevantReadAgentIds(messages, positions, listOf("a-tenny"))
        // Working first, then by how recently they spoke.
        assertEquals(listOf("a-tenny", "a-ray", "a-cindy", "a-niko"), relevant)
        val status = projectThreadAgentReadStatus(messages, "m-5", positions, relevant, workingAgentIds = listOf("a-tenny"))
        assertEquals(entries("a-ray" to Replied, "a-tenny" to Working, "a-cindy" to Read, "a-niko" to Unread), status)
        assertEquals("Ray replied · Tenny working · Cindy read · +1", format(status!!))
    }

    @Test fun workingWinsOverRepliedWhichWinsOverRead() {
        val messages = listOf(agent("a-niko", 1), mine(2), agent("a-niko", 3))
        val positions = mapOf("a-niko" to 3L)
        assertEquals(
            entries("a-niko" to Working),
            projectThreadAgentReadStatus(messages, "m-2", positions, listOf("a-niko"), workingAgentIds = listOf("a-niko")),
        )
        assertEquals(entries("a-niko" to Replied), projectThreadAgentReadStatus(messages, "m-2", positions, listOf("a-niko"), emptyList()))
    }

    @Test fun mentionsPickTheTargetsOtherwiseTheRelevantAgents() {
        val messages = listOf(agent("a-niko", 1), agent("a-ray", 2), mine(3, "a-cindy", "a-cindy", "a-unknown"))
        val positions = mapOf("a-niko" to 3L, "a-ray" to 3L, "a-cindy" to 1L)
        val relevant = threadRelevantReadAgentIds(messages, positions, emptyList())
        // @Cindy only (once); an agent with no read position here is not counted.
        assertEquals(entries("a-cindy" to Unread), projectThreadAgentReadStatus(messages, "m-3", positions, relevant, emptyList()))
        val unmentioned = listOf(agent("a-niko", 1), agent("a-ray", 2), mine(3))
        assertEquals(
            entries("a-ray" to Read, "a-niko" to Read),
            projectThreadAgentReadStatus(unmentioned, "m-3", positions, threadRelevantReadAgentIds(unmentioned, positions, emptyList()), emptyList()),
        )
        // Nothing without a seq (a send on its way), anyone to count, or the message.
        assertNull(projectThreadAgentReadStatus(listOf(agent("a-niko", 1), mine(null, id = "local")), "local", positions, listOf("a-niko"), emptyList()))
        assertNull(projectThreadAgentReadStatus(listOf(mine(2)), "m-2", positions, emptyList(), emptyList()))
        assertNull(projectThreadAgentReadStatus(listOf(mine(2)), "m-9", positions, listOf("a-niko"), emptyList()))
        assertNull(projectThreadAgentReadStatus(listOf(mine(2)), null, positions, listOf("a-niko"), emptyList()))
    }

    // --- stopped following ---

    @Test fun stoppedFollowingReplacesNotYetOnlyWhenTheReplyMentionedNoOne() {
        val messages = listOf(agent("a-niko", 1), agent("a-ray", 2), mine(3))
        val positions = mapOf("a-niko" to 1L, "a-ray" to 3L)
        val relevant = threadRelevantReadAgentIds(messages, positions, emptyList())
        val status = projectThreadAgentReadStatus(messages, "m-3", positions, relevant, emptyList(), setOf("a-niko", "a-ray"))
        // Ray read it before he stopped: read stays read. Niko would have been "not yet".
        assertEquals(entries("a-niko" to StoppedFollowing, "a-ray" to Read), status)
        assertEquals("Stopped following: Niko · Ray read", format(status!!))
        assertEquals("Stopped following: Niko · Mention them to reach them · Ray read", format(status, 2, en.stoppedFollowingHint))

        // @Niko reaches him (the send reactivates the follow): the usual not yet -> read path.
        val mentioned = listOf(agent("a-niko", 1), mine(3, "a-niko"))
        assertEquals(entries("a-niko" to Unread), projectThreadAgentReadStatus(mentioned, "m-3", positions, emptyList(), emptyList(), setOf("a-niko")))
        assertEquals(
            entries("a-niko" to Read),
            projectThreadAgentReadStatus(mentioned, "m-3", mapOf("a-niko" to 3L), emptyList(), emptyList(), setOf("a-niko")),
        )
        // A reply after it, or typing now, is still a reply or work.
        val replied = listOf(mine(3), agent("a-niko", 4))
        assertEquals(entries("a-niko" to Replied), projectThreadAgentReadStatus(replied, "m-3", positions, listOf("a-niko"), emptyList(), setOf("a-niko")))
        assertEquals(entries("a-niko" to Working), projectThreadAgentReadStatus(messages, "m-3", positions, listOf("a-niko"), listOf("a-niko"), setOf("a-niko")))
    }

    @Test fun stoppedFollowingComesFromTheDetailAndUnknownIsNeverStopped() {
        // A joint thread: the server marks an agent only when no server's projection still follows it,
        // so the client trusts the flag per agent, whichever server it is on.
        val scope =
            parsePeerReadReceiptHydrate(
                """{"id":"th-1","type":"thread","peerReadStates":[
                    {"peerKind":"agent","peerId":"a-local","maxReadSeq":2,"stoppedFollowing":true},
                    {"peerKind":"agent","peerId":"a-remote","maxReadSeq":2,"stoppedFollowing":true},
                    {"peerKind":"agent","peerId":"a-refollowed","maxReadSeq":2},
                    {"peerKind":"agent","peerId":"a-odd","maxReadSeq":2,"stoppedFollowing":"true"},
                    {"peerKind":"human","peerId":"u-ann","maxReadSeq":2,"stoppedFollowing":true}]}""",
            )
        val peers = assertIs<PeerReadReceiptScope.Peers>(scope)
        assertEquals(setOf("a-local", "a-remote"), threadAgentsStoppedFollowing(peers))
        assertEquals(PeerReadState(ReadReceiptPeerKind.Agent, "a-refollowed", 2), peers.peers[2])
        assertEquals(emptySet(), threadAgentsStoppedFollowing(PeerReadReceiptScope.Summary(PeerReadSummary(60, emptyList()))))
        assertEquals(emptySet(), threadAgentsStoppedFollowing(null))
    }

    // --- the line's words ---

    @Test fun atMostThreeNamesThenPlusN() {
        val five = entries("a-niko" to Replied, "a-ray" to Working, "a-cindy" to Read, "a-tenny" to Read, "a-kirby" to Unread)
        assertEquals("Niko replied · Ray working · Cindy read · +2", format(five))
        assertEquals("Niko and Ray read", format(entries("a-niko" to Read, "a-ray" to Read)))
        assertEquals("Niko, Ray, and Cindy not yet · +1", format(entries("a-niko" to Unread, "a-ray" to Unread, "a-cindy" to Unread, "a-tenny" to Read)))
        assertEquals("All replied", format(entries("a-niko" to Replied, "a-ray" to Replied)))
        assertEquals("No one has read it yet", format(entries("a-niko" to Unread, "a-ray" to Unread)))
        assertEquals("Niko not yet", format(entries("a-niko" to Unread)))
    }

    @Test fun stoppedFollowingNamesComeFirstAndAreNeverFoldedIntoTheLinePlusN() {
        val status =
            entries(
                "a-niko" to StoppedFollowing, "a-ray" to StoppedFollowing, "a-cindy" to StoppedFollowing,
                "a-tenny" to StoppedFollowing, "a-kirby" to StoppedFollowing, "a-ottie" to Read,
            )
        assertEquals("Stopped following: Niko, Ray, Cindy +2 · +1", format(status))
        // Tapped: every name, then the hint right after its segment.
        assertEquals(
            "Stopped following: Niko, Ray, Cindy, Tenny, and Kirby · Mention them to reach them · Ottie read",
            format(status, status.size, en.stoppedFollowingHint),
        )
        val ui = ThreadAgentReadStatusUi("m-1", status, names)
        assertEquals(RaftLucideIconName.UnfollowThread, ui.iconName())
        assertEquals(RaftLucideIconName.CheckCheck, ui.copy(entries = entries("a-niko" to Read, "a-ray" to Replied)).iconName())
        assertEquals(RaftLucideIconName.Check, ui.copy(entries = entries("a-niko" to Read, "a-ray" to Unread)).iconName())
    }

    @Test fun localeListPatternsAndPlaceholderLikeNames() {
        val ja = en.copy(read = "%1\$s 既読", listTwo = "%1\$s、%2\$s", listStart = "%1\$s、%2\$s", listEnd = "%1\$s、%2\$s")
        assertEquals("A、B、C 既読", formatThreadAgentReadStatus(entries("a" to Read, "b" to Read, "c" to Read), { it.uppercase() }, ja))
        assertEquals("%2\$s read", formatThreadAgentReadStatus(entries("x" to Read), { "%2\$s" }, en))
    }

    // --- which message, and when ---

    @Test fun theLineGoesUnderTheViewersLatestOwnReplyAtTheLiveEdgeOnly() {
        val messages = listOf(mine(1), agent("a-niko", 2), mine(3), other(4), system(5), agent("a-niko", 6))
        assertEquals("m-3", threadOwnLatestMessageId(messages, hasNewer = false))
        assertNull(threadOwnLatestMessageId(messages, hasNewer = true))
        // Past the relevance window: none.
        assertNull(threadOwnLatestMessageId(listOf(mine(1)) + (2L..31L).map { agent("a-niko", it) }, hasNewer = false))
    }

    @Test fun relevanceLooksAtTheLastThirtyMessagesAndSkipsSystemLines() {
        val old = agent("a-ray", 1)
        val recent = (2L..31L).map { agent("a-niko", it) }
        val positions = mapOf("a-ray" to 1L, "a-niko" to 31L, "a-cindy" to 0L)
        assertEquals(listOf("a-niko"), threadRelevantReadAgentIds(listOf(old) + recent, positions, emptyList()))
        val mentionedInSystem = system(32).copy(mentions = listOf(MessageMention("agent", "a-cindy", "Cindy")))
        assertEquals(listOf("a-niko"), threadRelevantReadAgentIds(recent + mentionedInSystem, positions, emptyList()))
    }

    @Test fun theThreadLineIsGatedAndUsesDetailPositionsStopsAndNames() {
        val scope =
            PeerReadReceiptScope.Peers(
                listOf(
                    PeerReadState(ReadReceiptPeerKind.Agent, "a-niko", 1, stoppedFollowing = true),
                    PeerReadState(ReadReceiptPeerKind.Agent, "a-ray", 3),
                    PeerReadState(ReadReceiptPeerKind.Human, "u-ann", 3),
                ),
            )
        val messages = listOf(agent("a-niko", 1), agent("a-ray", 2), mine(3))
        val receipts = ThreadPeerReadReceiptsUi(scope, dmSurface = false, agentReadStatusEnabled = true, agentNames = names)
        val line = receipts.threadAgentReadStatus(messages, hasNewer = false, workingAgentIds = emptyList())
        assertEquals(ThreadAgentReadStatusUi("m-3", entries("a-niko" to StoppedFollowing, "a-ray" to Read), names), line)
        assertNull(receipts.copy(agentReadStatusEnabled = false).threadAgentReadStatus(messages, false, emptyList()))
        assertNull(receipts.copy(dmSurface = true).threadAgentReadStatus(messages, false, emptyList()))
        assertNull(receipts.threadAgentReadStatus(messages, hasNewer = true, workingAgentIds = emptyList()))
        assertNull(receipts.copy(scope = PeerReadReceiptScope.Summary(PeerReadSummary(60, emptyList()))).threadAgentReadStatus(messages, false, emptyList()))
    }

    @Test fun aCachedRowWithoutResolvedMentionsFallsBackToItsBodyHandles() {
        val directory = mapOf("niko" to MessageMention("agent", "a-niko", "niko"), "ann" to MessageMention("user", "u-ann", "ann"))
        val cached = mine(3).copy(body = "@niko and @ann, see above")
        assertEquals(listOf("a-niko"), cached.threadAgentMentionIds(directory))
        assertEquals(
            entries("a-niko" to Unread),
            projectThreadAgentReadStatus(listOf(cached), "m-3", mapOf("a-niko" to 1L), emptyList(), emptyList(), setOf("a-niko"), directory),
        )
    }

    // --- thread:followers-updated ---

    @Test fun followersUpdatedForThisThreadRefreshesTheDetail() {
        val action = { event: build.raft.app.model.RealtimeEvent, server: String, enabled: Boolean ->
            threadPeerReadReceiptRealtimeAction(event, server, "srv-1", "th-1", enabled)
        }
        assertEquals(ThreadPeerReadReceiptRealtimeAction.RefreshFollowState, action(ThreadFollowersUpdated(ChannelId("th-1")), "srv-1", true))
        assertEquals(ThreadPeerReadReceiptRealtimeAction.None, action(ThreadFollowersUpdated(ChannelId("th-2")), "srv-1", true))
        assertEquals(ThreadPeerReadReceiptRealtimeAction.None, action(ThreadFollowersUpdated(ChannelId("th-1")), "srv-2", true))
        assertEquals(ThreadPeerReadReceiptRealtimeAction.None, action(ThreadFollowersUpdated(ChannelId("th-1")), "srv-1", false))
        // Read moves still apply as before.
        val move = ScopeReadUpdated("th-1", ReadReceiptPeerKind.Agent, "a-niko", 4)
        assertEquals(ThreadPeerReadReceiptRealtimeAction.ApplyReadMove, action(move, "srv-1", false))
        assertEquals(ThreadPeerReadReceiptRealtimeAction.None, action(move.copy(scopeId = "th-2"), "srv-1", true))
    }
}
