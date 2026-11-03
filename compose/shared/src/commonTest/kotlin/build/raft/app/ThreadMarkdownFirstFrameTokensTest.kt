package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.markdown.MarkdownInlineTokenKind
import build.raft.app.markdown.MarkdownInlineTokenPlan
import build.raft.app.markdown.RaftMarkdownInlineTokenPlanner
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * artin 11-03 (Compose 版, 「at 渲染成真实 at 这些也会影响」): in the recording a task conversation's
 * first content frame painted `@artin` as plain text, and a few frames later as a mention chip,
 * re-laying the rows out. The member directory was keyed on the session's server, which the page
 * captures a few frames after composing, so the first frames had no members. Here the inline
 * token plan a body gets on its first frame (directory already in memory, rows on screen, Home
 * snapshot) must be the plan it keeps once the canonical member/channel data arrives, so a chip
 * never pops in and its label never changes size.
 */
class ThreadMarkdownFirstFrameTokensTest {
    private val serverId = ServerId("srv-1")
    private val artin =
        MemberProfile(id = MemberId("u-artin"), displayName = "artin", handle = "artin", kind = MemberKind.Human, presence = Presence.Active)
    private val body = "@artin 好，按 1 做 #proj-raft-ui"
    private val cachedTimelineRow =
        RaftThreadMessageItem(id = "m1", authorId = "u-artin", authorName = "artin", authorHandle = "artin", body = "推荐")

    @Test
    fun aMentionIsAChipWithItsNameOnTheFirstFrameFromTheDirectoryInMemory() {
        // First frame: the route payload has no members yet; the server directory is in memory.
        val firstFrame =
            RaftThreadScreenPayload(status = "loading").projected(
                SharedServerMembersEntry(serverId = serverId.value, members = listOf(artin))
            )
        // Later: the canonical refresh brings the channel's members.
        val resolved =
            RaftThreadScreenPayload(
                status = "content",
                members = listOf(RaftThreadMemberItem(id = "u-artin", displayName = "artin", handle = "artin"))
            ).projected(SharedServerMembersEntry(serverId = serverId.value, members = listOf(artin)))

        val first = plan(firstFrame)
        assertEquals(1, first.tokens.count { it.kind == MarkdownInlineTokenKind.UserMention }, "the mention is a chip on frame 1")
        assertEquals(first.tokens, plan(resolved).tokens)
    }

    @Test
    fun theRowsOnScreenMakeTheirAuthorsKnownBeforeThePayloadCarriesThem() {
        val payload = RaftThreadScreenPayload(status = "loading")
        assertEquals(setOf<String>(), payload.toKnownMarkdownMentionNameSet())
        assertEquals(
            payload.copy(messages = listOf(cachedTimelineRow)).toKnownMarkdownMentionNameSet(),
            payload.toKnownMarkdownMentionNameSet(timelineMessages = listOf(cachedTimelineRow))
        )
    }

    @Test
    fun channelRefsUseTheHomeSnapshotUntilTheStoreAddsTheNames() {
        val home = home()
        val firstFrame = RaftThreadScreenPayload().withFirstFrameKnownMarkdownChannelNames(home)
        assertEquals(listOf("proj-raft-ui"), firstFrame.knownMarkdownChannelNames)
        // The store's own names always win; no Home snapshot keeps the payload as it is.
        val stored = RaftThreadScreenPayload(knownMarkdownChannelNames = listOf("general"))
        assertEquals(stored, stored.withFirstFrameKnownMarkdownChannelNames(home))
        assertEquals(RaftThreadScreenPayload(), RaftThreadScreenPayload().withFirstFrameKnownMarkdownChannelNames(null))
        // ...and the chip plan is the one the store's names give later.
        assertEquals(
            plan(firstFrame).tokens,
            plan(RaftThreadScreenPayload(knownMarkdownChannelNames = home.knownMarkdownChannelNames())).tokens
        )
    }

    private fun RaftThreadScreenPayload.projected(entry: SharedServerMembersEntry): RaftThreadScreenPayload =
        withThreadMembersProjection(
            threadMembersProjection(
                basePayload = this,
                channelMembersSlice = SharedServerChannelMembersSlice(serverId = serverId.value, channelId = "c1"),
                serverMembersEntry = entry,
                serverId = serverId
            )
        )

    /** The plan ThreadPage gives [body] for [payload]'s known names and labels. */
    private fun plan(payload: RaftThreadScreenPayload): MarkdownInlineTokenPlan {
        val knownChannels = payload.knownMarkdownChannelNames.toKnownMarkdownChannelNameSet()
        val knownMentions = payload.toKnownMarkdownMentionNameSet()
        val knownPeers = payload.toKnownMarkdownDirectMessagePeerNameSet()
        val labels = threadMarkdownMentionDisplayLabels(payload.members, payload.memberCandidates)
        return RaftMarkdownInlineTokenPlanner().plan(
            content = body,
            isTagValid = { tag -> tag.isKnownThreadMarkdownTag(knownChannels, knownMentions, knownPeers) },
            resolveTagLabel = { tag -> resolveThreadMarkdownTagDisplayLabel(tag, labels) }
        )
    }

    private fun home(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = serverId, name = "Raft"),
            currentMember = artin,
            channels = listOf(HomeChannelRow(threadId = ThreadId("c1"), channelId = ChannelId("c1"), name = "proj-raft-ui")),
            directMessages = emptyList(),
            unreadTotal = 0
        )
}
