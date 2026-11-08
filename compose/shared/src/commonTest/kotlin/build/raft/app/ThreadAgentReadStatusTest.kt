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
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.ThreadRepliesReadModel
import build.raft.app.ThreadAgentReadState.Muted
import build.raft.app.ThreadAgentReadState.StoppedFollowing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent read progress, ported from botiverse/slock staging 61eaa22 (#13549, #13513)
 * `packages/web/src/store/agentReadProgressDomain.ts` (`placeAgentReads`, `projectAgentReadStatus`,
 * `formatAgentReadStatus`, `relevantReadAgentIds`, `ownLatestMessageId`) and its tests.
 */
class ThreadAgentReadStatusTest {
    private val en =
        ThreadAgentReadStatusStrings(
            stoppedFollowing = "Stopped following: %1\$s",
            muted = "Muted: %1\$s",
            stoppedFollowingHint = "Mention them to reach them",
            more = "+%1\$d",
            label = "Agents on this message: %1\$s",
            listTwo = "%1\$s and %2\$s",
            listStart = "%1\$s, %2\$s",
            listEnd = "%1\$s, and %2\$s",
            agentCountOne = "%1\$d agent",
            agentCountOther = "%1\$d agents",
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

    // --- the line: only stopped following or muted ---

    @Test fun noLineWhenEveryAgentIsReachedEvenIfSomeHaveNotReadYet() {
        // Replied, working, read and not yet are the faces' to say: no line for any of them.
        val messages = listOf(agent("a-niko", 1), agent("a-ray", 2), agent("a-cindy", 3), mine(4), agent("a-ray", 5))
        val positions = mapOf("a-niko" to 1L, "a-ray" to 5L, "a-cindy" to 4L, "a-tenny" to 0L)
        val relevant = threadRelevantReadAgentIds(messages, positions, listOf("a-tenny"))
        assertEquals(listOf("a-tenny", "a-ray", "a-cindy", "a-niko"), relevant)
        assertNull(projectThreadAgentReadStatus(messages, "m-4", positions, relevant, workingAgentIds = listOf("a-tenny")))
    }

    @Test fun stoppedFollowingInAThreadAndMutedInAChannelFlagOnlyAgentsThatHaveNotGotTheMessage() {
        val messages = listOf(agent("a-niko", 1), agent("a-ray", 2), agent("a-cindy", 3), agent("a-tenny", 4), mine(5))
        val positions = mapOf("a-niko" to 1L, "a-ray" to 5L, "a-cindy" to 2L, "a-tenny" to 3L)
        val relevant = threadRelevantReadAgentIds(messages, positions, emptyList())
        // Ray read it before he stopped: no flag. Tenny is working on it: no flag. Cindy is not stopped.
        assertEquals(
            entries("a-niko" to StoppedFollowing),
            projectThreadAgentReadStatus(messages, "m-5", positions, relevant, listOf("a-tenny"), stoppedFollowingAgentIds = setOf("a-niko", "a-ray", "a-tenny")),
        )
        // A channel: muted, in relevance order; stopped following sorts first when both appear.
        assertEquals(
            entries("a-niko" to StoppedFollowing, "a-tenny" to Muted, "a-cindy" to Muted),
            projectThreadAgentReadStatus(messages, "m-5", positions, relevant, emptyList(), setOf("a-niko"), mutedAgentIds = setOf("a-cindy", "a-tenny")),
        )
        // A reply after the message, or one in its own thread (a channel's inline replies), means it has it.
        val withReply = messages + agent("a-cindy", 6)
        assertNull(projectThreadAgentReadStatus(withReply, "m-5", positions, relevant, emptyList(), mutedAgentIds = setOf("a-cindy")))
        val threaded =
            messages.dropLast(1) +
                mine(5).copy(
                    inlineReplies = ThreadRepliesReadModel(listOf(ReplyPreview("r-1", 9, "ok", "a-cindy", "agent", "Cindy", createdAt = "2026-11-08T00:00:00Z")), 1, 9),
                )
        assertNull(projectThreadAgentReadStatus(threaded, "m-5", positions, relevant, emptyList(), mutedAgentIds = setOf("a-cindy")))
    }

    @Test fun aMentionReachesTheAgentSoTheMessageFlagsNoOne() {
        val positions = mapOf("a-niko" to 1L, "a-ray" to 1L)
        val mentioned = listOf(agent("a-niko", 1), agent("a-ray", 2), mine(3, "a-ray"))
        val relevant = threadRelevantReadAgentIds(mentioned, positions, emptyList())
        assertNull(projectThreadAgentReadStatus(mentioned, "m-3", positions, relevant, emptyList(), setOf("a-niko", "a-ray")))
        // A mention of an agent that cannot read here does not count.
        val unknown = listOf(agent("a-niko", 1), mine(3, "a-unknown"))
        assertEquals(
            entries("a-niko" to Muted),
            projectThreadAgentReadStatus(unknown, "m-3", positions, listOf("a-niko"), emptyList(), mutedAgentIds = setOf("a-niko")),
        )
        // Nothing without a seq (a send on its way), the message, or anyone stopped or muted.
        assertNull(projectThreadAgentReadStatus(listOf(agent("a-niko", 1), mine(null, id = "local")), "local", positions, listOf("a-niko"), emptyList(), setOf("a-niko")))
        assertNull(projectThreadAgentReadStatus(listOf(mine(2)), "m-9", positions, listOf("a-niko"), emptyList(), setOf("a-niko")))
        assertNull(projectThreadAgentReadStatus(listOf(mine(2)), null, positions, listOf("a-niko"), emptyList(), setOf("a-niko")))
        assertNull(projectThreadAgentReadStatus(listOf(agent("a-niko", 1), mine(2)), "m-2", positions, listOf("a-niko"), emptyList()))
    }

    @Test fun mutedAndStoppedFollowingComeFromTheDetailAndUnknownIsNeither() {
        val scope =
            parsePeerReadReceiptHydrate(
                """{"id":"c-1","type":"channel","peerReadStates":[
                    {"peerKind":"agent","peerId":"a-muted","maxReadSeq":2,"mutedChannel":true},
                    {"peerKind":"agent","peerId":"a-stopped","maxReadSeq":2,"stoppedFollowing":true},
                    {"peerKind":"agent","peerId":"a-odd","maxReadSeq":2,"mutedChannel":"true"},
                    {"peerKind":"human","peerId":"u-ann","maxReadSeq":2,"mutedChannel":true}]}""",
            )
        val peers = assertIs<PeerReadReceiptScope.Peers>(scope)
        assertEquals(setOf("a-muted"), threadAgentsMutedChannel(peers))
        assertEquals(setOf("a-stopped"), threadAgentsStoppedFollowing(peers))
        assertEquals(PeerReadState(ReadReceiptPeerKind.Agent, "a-odd", 2), peers.peers[2])
        assertEquals(emptySet(), threadAgentsMutedChannel(PeerReadReceiptScope.Summary(PeerReadSummary(60, emptyList()))))
        assertEquals(emptySet(), threadAgentsMutedChannel(null))
    }

    // --- the line's words ---

    @Test fun eachSegmentKeepsItsVerbAndAtLeastOneNameAndCountsItsOwnRest() {
        assertEquals("Stopped following: Kirby", format(entries("a-kirby" to StoppedFollowing)))
        assertEquals("Muted: Niko and Ray", format(entries("a-niko" to Muted, "a-ray" to Muted)))
        val five = entries("a-niko" to StoppedFollowing, "a-ray" to StoppedFollowing, "a-cindy" to StoppedFollowing, "a-tenny" to StoppedFollowing, "a-kirby" to StoppedFollowing)
        assertEquals("Stopped following: Niko, Ray, Cindy +2", format(five))
        // Two segments share the three names; the muted one keeps a name and its own count.
        val mixed = entries("a-niko" to StoppedFollowing, "a-ray" to StoppedFollowing, "a-cindy" to StoppedFollowing, "a-tenny" to Muted, "a-kirby" to Muted)
        assertEquals("Stopped following: Niko, Ray +1 · Muted: Tenny +1", format(mixed))
        // Every name, then the hint: the accessible sentence.
        assertEquals(
            "Stopped following: Niko, Ray, and Cindy · Muted: Tenny and Kirby · Mention them to reach them",
            format(mixed, mixed.size, en.stoppedFollowingHint),
        )
        assertEquals("", format(emptyList()))
    }

    @Test fun runsKeepTheCountApartFromTheNamesAndKnowTheirTotal() {
        val four = entries("a-niko" to Muted, "a-ray" to Muted, "a-cindy" to Muted, "a-tenny" to Muted)
        assertEquals(listOf(ThreadAgentReadRun(Muted, "Niko, Ray, Cindy", 1, 4)), threadAgentReadStatusRuns(four, { names[it]!! }, en))
        val mixed = entries("a-niko" to StoppedFollowing, "a-ray" to StoppedFollowing, "a-cindy" to StoppedFollowing, "a-tenny" to Muted, "a-kirby" to Muted)
        assertEquals(
            listOf(ThreadAgentReadRun(StoppedFollowing, "Niko, Ray", 1, 3), ThreadAgentReadRun(Muted, "Tenny", 1, 2)),
            threadAgentReadStatusRuns(mixed, { names[it]!! }, en),
        )
        assertEquals("Muted: 4 agents", en.count(Muted, 4))
        assertEquals("Muted: 1 agent", en.count(Muted, 1))
        assertEquals("Stopped following: 3 agents", en.count(StoppedFollowing, 3))
        val zh = en.copy(muted = "已静音：%1\$s", agentCountOne = "%1\$d 个 Agent", agentCountOther = "%1\$d 个 Agent")
        assertEquals("已静音：4 个 Agent", zh.count(Muted, 4))
    }

    // --- the narrow-width rule (web #13554, Kirby's review) ---

    private val muted4 = ThreadAgentReadSegmentWidths(verb = 50f, names = 150f, minNames = 12f, more = 20f, count = 70f)

    private fun plan(available: Float, vararg segments: ThreadAgentReadSegmentWidths) =
        planThreadAgentReadLine(available, lead = 16f, separator = 10f, segments = segments.toList(), hint = 170f)

    @Test fun withRoomNothingChanges() {
        assertEquals(ThreadAgentReadLinePlan(listOf(150f), 170f), plan(500f, muted4))
        assertEquals(ThreadAgentReadLinePlan(listOf(150f), 170f), plan(416f, muted4))
    }

    @Test fun theNamesTakeOnlyTheRoomLeftAndGoFirst() {
        // 350 - (16 + 10 + 50 + 20) - 170 = 84 for the names; verb, +N and hint keep their width.
        assertEquals(ThreadAgentReadLinePlan(listOf(84f), 170f), plan(350f, muted4))
        // Still a letter and the ellipsis: a cut name, not the count.
        assertEquals(ThreadAgentReadLinePlan(listOf(12f), 170f), plan(278f, muted4))
    }

    @Test fun whenNotEvenOneNameFitsTheSegmentIsItsCountAndOnlyThenTheHintIsCut() {
        // 4 px for the names: written as "Muted: 4 agents" (70) with the hint whole.
        assertEquals(ThreadAgentReadLinePlan(listOf(null), 170f), plan(270f, muted4))
        // Narrower: the hint loses its tail, and only it.
        assertEquals(ThreadAgentReadLinePlan(listOf(null), 104f), plan(200f, muted4))
        assertEquals(ThreadAgentReadLinePlan(listOf(null), 0f), plan(50f, muted4))
    }

    @Test fun segmentsShareTheRoomAndTheNarrowestGoesFirst() {
        val stopped = ThreadAgentReadSegmentWidths(verb = 120f, names = 60f, minNames = 10f, more = 0f, count = 160f)
        // 24 left: 12 each, both still show a letter.
        assertEquals(ThreadAgentReadLinePlan(listOf(12f, 12f), 170f), plan(420f, stopped, muted4))
        // A short name takes only its own width; the rest goes to the other.
        val short = stopped.copy(names = 8f, minNames = 8f)
        assertEquals(ThreadAgentReadLinePlan(listOf(8f, 26f), 170f), plan(430f, short, muted4))
        // 14 left: neither shows a letter; one becomes its count, then the other, then the hint is cut.
        assertEquals(ThreadAgentReadLinePlan(listOf(null, null), 144f), plan(410f, stopped, muted4))
    }

    @Test fun theIconFollowsTheFirstSegment() {
        val ui = ThreadAgentReadStatusUi("m-1", entries("a-niko" to StoppedFollowing, "a-ray" to Muted), names)
        assertEquals(RaftLucideIconName.UnfollowThread, ui.iconName())
        assertEquals(RaftLucideIconName.BellOff, ui.copy(entries = entries("a-ray" to Muted)).iconName())
    }

    @Test fun localeListPatternsAndPlaceholderLikeNames() {
        val ja = en.copy(stoppedFollowing = "フォロー解除済み：%1\$s", listTwo = "%1\$s、%2\$s", listStart = "%1\$s、%2\$s", listEnd = "%1\$s、%2\$s")
        assertEquals(
            "フォロー解除済み：A、B、C",
            formatThreadAgentReadStatus(entries("a" to StoppedFollowing, "b" to StoppedFollowing, "c" to StoppedFollowing), { it.uppercase() }, ja),
        )
        assertEquals("Muted: %2\$s", formatThreadAgentReadStatus(entries("x" to Muted), { "%2\$s" }, en))
    }

    // --- the faces: where each agent read up to ---

    @Test fun eachFaceSitsOnTheLastLoadedMessageAtOrBelowItsReadPosition() {
        val messages = listOf(agent("a-niko", 1), other(3), mine(5), system(6), agent("a-ray", 8))
        val positions = mapOf("a-niko" to 4L, "a-ray" to 8L, "a-cindy" to 6L, "a-tenny" to 100L, "a-kirby" to 0L)
        val placed = placeThreadAgentReads(messages, positions, listOf("a-ray", "a-tenny", "a-niko", "a-cindy", "a-kirby", "a-none"))
        // Between rows: the row below. Past the end: the last row. Older than every row: no face.
        assertEquals(mapOf("m-8" to listOf("a-ray", "a-tenny"), "m-3" to listOf("a-niko"), "m-6" to listOf("a-cindy")), placed)
        // Out-of-order rows and rows without a seq (a send on its way) are handled.
        val shuffled = listOf(mine(5), agent("a-niko", 1), mine(null, id = "local"), other(3))
        assertEquals(mapOf("m-3" to listOf("a-niko")), placeThreadAgentReads(shuffled, positions, listOf("a-niko")))
        // A context window: an agent past its last row stopped somewhere not shown.
        assertEquals(mapOf("m-6" to listOf("a-cindy")), placeThreadAgentReads(messages.take(4), positions, listOf("a-tenny", "a-cindy"), hasNewer = true))
    }

    @Test fun facesAreForRelevantAgentsAtTheLiveEdgeWorkingFirst() {
        val scope =
            PeerReadReceiptScope.Peers(
                listOf(
                    PeerReadState(ReadReceiptPeerKind.Agent, "a-niko", 3),
                    PeerReadState(ReadReceiptPeerKind.Agent, "a-ray", 3),
                    PeerReadState(ReadReceiptPeerKind.Agent, "a-idle", 3),
                    PeerReadState(ReadReceiptPeerKind.Human, "u-ann", 3),
                ),
            )
        val messages = listOf(agent("a-niko", 1), agent("a-ray", 2), mine(3))
        val receipts = ThreadPeerReadReceiptsUi(scope, dmSurface = false, agentReadProgressEnabled = true, agentNames = names, serverId = "srv-1")
        val progress = receipts.threadAgentReadProgress(messages, hasNewer = false, workingAgentIds = listOf("a-niko"))!!
        // a-idle neither spoke nor was mentioned: no face. Working Niko first, with the busy light.
        assertEquals(
            mapOf("m-3" to listOf(ThreadAgentReadFace("a-niko", "Niko", working = true), ThreadAgentReadFace("a-ray", "Ray"))),
            progress.facesByMessageId,
        )
        assertEquals("srv-1", progress.serverId)
        assertNull(progress.status)
        assertTrue(receipts.threadAgentReadProgress(messages, hasNewer = true, workingAgentIds = emptyList())!!.facesByMessageId.isEmpty())
        assertNull(receipts.copy(agentReadProgressEnabled = false).threadAgentReadProgress(messages, false, emptyList()))
        assertNull(receipts.copy(dmSurface = true).threadAgentReadProgress(messages, false, emptyList()))
        assertNull(receipts.copy(scope = PeerReadReceiptScope.Summary(PeerReadSummary(60, emptyList()))).threadAgentReadProgress(messages, false, emptyList()))
    }

    @Test fun theLineComesWithTheFacesFromTheDetail() {
        val scope =
            PeerReadReceiptScope.Peers(
                listOf(
                    PeerReadState(ReadReceiptPeerKind.Agent, "a-niko", 1, mutedChannel = true),
                    PeerReadState(ReadReceiptPeerKind.Agent, "a-ray", 3),
                ),
            )
        val messages = listOf(agent("a-niko", 1), agent("a-ray", 2), mine(3))
        val receipts = ThreadPeerReadReceiptsUi(scope, dmSurface = false, agentReadProgressEnabled = true, agentNames = names)
        val progress = receipts.threadAgentReadProgress(messages, hasNewer = false, workingAgentIds = emptyList())!!
        assertEquals(ThreadAgentReadStatusUi("m-3", entries("a-niko" to Muted), names), progress.status)
        assertEquals(mapOf("m-1" to listOf("a-niko"), "m-3" to listOf("a-ray")), progress.facesByMessageId.mapValues { (_, f) -> f.map { it.agentId } })
    }

    @Test fun phoneWidthsShowTwoFacesWiderOnesThree() {
        assertEquals(2, threadAgentReadMaxFaces(ThreadAgentReadWideMinWidth - ThreadAgentReadWideMinWidth / 2))
        assertEquals(3, threadAgentReadMaxFaces(ThreadAgentReadWideMinWidth))
    }

    // --- which message, and when ---

    @Test fun theLineGoesUnderTheViewersLatestOwnMessageAtTheLiveEdgeOnly() {
        val messages = listOf(mine(1), agent("a-niko", 2), mine(3), other(4), system(5), agent("a-niko", 6))
        assertEquals("m-3", threadOwnLatestMessageId(messages, hasNewer = false))
        assertNull(threadOwnLatestMessageId(messages, hasNewer = true))
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

    @Test fun aCachedRowWithoutResolvedMentionsFallsBackToItsBodyHandles() {
        val directory = mapOf("niko" to MessageMention("agent", "a-niko", "niko"), "ann" to MessageMention("user", "u-ann", "ann"))
        val cached = mine(3).copy(body = "@niko and @ann, see above")
        assertEquals(listOf("a-niko"), cached.threadAgentMentionIds(directory))
        assertNull(projectThreadAgentReadStatus(listOf(cached), "m-3", mapOf("a-niko" to 1L), listOf("a-niko"), emptyList(), setOf("a-niko"), directory = directory))
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
        val move = ScopeReadUpdated("th-1", ReadReceiptPeerKind.Agent, "a-niko", 4)
        assertEquals(ThreadPeerReadReceiptRealtimeAction.ApplyReadMove, action(move, "srv-1", false))
        assertEquals(ThreadPeerReadReceiptRealtimeAction.None, action(move.copy(scopeId = "th-2"), "srv-1", true))
    }
}
