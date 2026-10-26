package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.network.sync.ReplyPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin 10-26: a message opened from Activity showed its row's cut preview ("…") until the page
 * loaded. A cut preview is now flagged on the seed message and shown without its "…" (the card ends
 * it with a loading indicator); a full copy this device already holds is used instead.
 */
class ThreadActivityOpenSeedTruncatedPreviewTest {
    @Test
    fun onlyTheServersTwoCutsCountAsTruncated() {
        val cases =
            listOf(
                // truncatePreview(…, 140): 140 UTF-16 units + "…".
                "a".repeat(140) + "…" to true,
                // historyLimitedPreview: 39 code points + "…" (an emoji is one code point, two units).
                "😀" + "b".repeat(38) + "…" to true,
                // Shorter or longer texts ending in "…" are the message itself.
                "hmm…" to false,
                "a".repeat(139) + "…" to false,
                "a".repeat(141) to false,
                "a".repeat(140) to false,
            )
        for ((preview, expected) in cases) {
            assertEquals(expected, activitySeedPreviewIsTruncated(preview), "length ${preview.length}: $preview")
        }
    }

    @Test
    fun aCutChannelPreviewSeedsAFlaggedMessageWithoutItsEllipsis() {
        val cut = "x".repeat(139) + " …"
        val seeded = seed(channelRow(preview = cut), channelRoute()).single()
        assertTrue(seeded.activityPreviewTruncated)
        assertEquals("x".repeat(139), seeded.body, "no trailing ellipsis or space")

        val full = seed(channelRow(preview = "the whole message"), channelRoute()).single()
        assertFalse(full.activityPreviewTruncated)
        assertEquals("the whole message", full.body)

        // A retracted message shows the placeholder state, never a loading indicator.
        val retracted = seed(channelRow(preview = cut).copy(latestRetracted = true), channelRoute()).single()
        assertFalse(retracted.activityPreviewTruncated)
    }

    @Test
    fun theThreadRowsFullReplyWinsOverItsCutLatestPreview() {
        val longReply = "r".repeat(300)
        val thread =
            InboxItem(
                key = "thread-row",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                threadChannelId = ChannelId("thread-1"),
                parentChannelId = ChannelId("channel-1"),
                parentMessageId = MessageId("p1"),
                latestActivityMessageId = MessageId("r1"),
                latestActivityPreview = longReply.take(140) + "…",
                latestActivityAt = "2026-10-26T10:01:00Z",
                latestActivitySenderId = "agent-1",
                latestActivitySenderKind = MemberKind.Agent,
                latestReplies =
                    listOf(
                        ReplyPreview(
                            messageId = "r1",
                            seq = 11,
                            content = longReply,
                            senderId = "agent-1",
                            senderType = "agent",
                            senderName = "Friday",
                            createdAt = "2026-10-26T10:01:00Z",
                        ),
                    ),
            )
        val seeded = seed(thread, replyRoute()).single()
        assertFalse(seeded.activityPreviewTruncated)
        assertEquals(longReply, seeded.body)
    }

    @Test
    fun aFullCopyThisDeviceHoldsReplacesTheCutSeed() {
        val cut = seed(channelRow(preview = "y".repeat(140) + "…"), channelRoute()).single()
        val other = cut.copy(id = "m-other", body = "another message", activityPreviewTruncated = false)
        val localFull = cut.copy(body = "y".repeat(400), activityPreviewTruncated = false, seq = 7)
        val localCut = cut.copy(body = "y".repeat(140))

        assertEquals(listOf(localFull), listOf(cut).withLocalFullMessages(listOf(other, localFull)))
        assertEquals(listOf(cut), listOf(cut).withLocalFullMessages(listOf(other, localCut)), "never another stand-in")
        assertEquals(listOf(cut), listOf(cut).withLocalFullMessages(emptyList()))

        // Seeded onto a loading frame, the full copy is what the first frame shows.
        val frame =
            RaftThreadScreenPayload(sourceThreadId = "channel-1", status = "loading")
                .withActivityOpenSeed(listOf(cut).withLocalFullMessages(listOf(localFull)))
        assertEquals(listOf("y".repeat(400) to false), frame.messages.map { it.body to it.activityPreviewTruncated })
    }

    private fun seed(item: InboxItem, route: RaftThreadScreenPayload) =
        threadActivityOpenSeedMessages(
            ThreadActivityOpenSeedSource(ownerServerId = "server-1", ownerUserId = "u-a", items = listOf(item)),
            route.resolveThreadRouteIdentity(),
            serverId = "server-1",
            principalId = "u-a",
        )

    private fun channelRow(preview: String) =
        InboxItem(
            key = "channel-1",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            lastMessageId = MessageId("m1"),
            lastMessagePreview = preview,
            lastMessageAt = "2026-10-26T10:00:00Z",
            lastMessageSenderId = "u1",
            lastMessageSenderName = "Artin",
            lastMessageSenderKind = MemberKind.Human,
        )

    private fun channelRoute() =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            sourceThreadId = "channel-1",
            status = "loading",
        )

    private fun replyRoute() =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-reply-thread-1",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-1",
            streamChannelId = "thread-1",
            parentChannelId = "channel-1",
            parentMessageId = "p1",
            status = "loading",
        )
}
