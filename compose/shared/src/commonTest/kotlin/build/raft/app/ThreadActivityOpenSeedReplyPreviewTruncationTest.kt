package build.raft.app

import build.raft.app.data.parseSharedInboxSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server cuts a thread summary's `latestReplies[].preview` to at most 200 characters ending in "…"
 * and may say so in an additive `truncated` field. A reply seeded from that preview while a thread
 * opens from Activity is a cut stand-in: it loads its full text (plain text, loading indicator), is
 * never copied or quoted, and never wins over a whole copy of the same message.
 */
class ThreadActivityOpenSeedReplyPreviewTruncationTest {
    private val cut = "x".repeat(199) + "…"

    @Test
    fun theServersFlagDecidesAndItsAbsenceReadsTheCut() {
        val seeded = seedFromJson(
            reply("r-1", 11, cut, truncated = "true"),
            // The flag says whole: a reply that really ends in "…" stays whole.
            reply("r-2", 12, "y".repeat(199) + "…", truncated = "false"),
            // No flag (older servers): a preview ending in "…" within the cut's length is cut...
            reply("r-3", 13, "z".repeat(150) + "…"),
            // ...and one without "…", or longer than the cut, is whole.
            reply("r-4", 14, "the whole reply"),
            reply("r-5", 15, "w".repeat(250) + "…"),
        )
        assertEquals(
            listOf("r-1" to true, "r-2" to false, "r-3" to true, "r-4" to false, "r-5" to false, "r-6" to false),
            seeded.map { it.id to it.activityPreviewTruncated }.sortedBy { it.first },
        )
        assertEquals(cut, seeded.single { it.id == "r-1" }.body, "the cut text keeps its \"…\"")
    }

    @Test
    fun aCutReplyNeverBeatsAWholeCopyAndKeepsTheSeqOrderingBetweenEquals() {
        val whole = RaftThreadMessageItem(id = "m", authorId = "a", authorName = "A", body = "whole", timestampLabel = "t")
        val cutWithSeq = whole.copy(body = cut, seq = 9, activityPreviewTruncated = true)
        val cutNoSeq = whole.copy(body = "c".repeat(140) + "…", activityPreviewTruncated = true)

        // Whole wins in either order, and takes the cut copy's seq.
        assertEquals(whole.copy(seq = 9), activitySeedPreferred(whole, cutWithSeq))
        assertEquals(whole.copy(seq = 9), activitySeedPreferred(cutWithSeq, whole))
        // Both cut: the reply window entry (with seq) wins.
        assertEquals(cutWithSeq, activitySeedPreferred(cutNoSeq, cutWithSeq))
        assertEquals(cutWithSeq, activitySeedPreferred(cutWithSeq, cutNoSeq))
    }

    @Test
    fun aRowsWholeLatestPreviewWinsOverAFlaggedReplyWindowEntry() {
        // The reply window entry is flagged cut; the row's own latest preview of the same message is
        // whole. The whole text shows, ordered by the reply window's seq.
        val seeded =
            seedFromJson(
                reply("r-6", 16, "short and whole", truncated = "true"),
                latestPreview = "short and whole",
            )
        val r6 = seeded.single { it.id == "r-6" }
        assertFalse(r6.activityPreviewTruncated)
        assertEquals("short and whole" to 16L, r6.body to r6.seq)
    }

    @Test
    fun aCutBodyIsPlainLoadingTextWithNoCopyOrQuoteAndAFailedLoadSaysSo() {
        val seeded = seedFromJson(reply("r-1", 11, cut, truncated = "true"), latestPreview = "ok").single { it.id == "r-1" }
        assertTrue(seeded.hasActivityCutBody())

        val failed = seeded.withActivityPreviewSettled()
        assertFalse(failed.activityPreviewTruncated, "no indicator that spins forever")
        assertTrue(failed.activityPreviewLoadFailed, "says the full message couldn't load")
        assertTrue(failed.hasActivityCutBody(), "still not rendered or copied as the message")

        val full = failed.mergeRealtimeUpdate(failed.copy(body = "x".repeat(400), activityPreviewLoadFailed = false))
        assertFalse(full.hasActivityCutBody(), "a realtime body is the full text")

        val availability = ThreadNewMessageMenuActionAvailability(copy = false, selectText = false)
        assertFalse(availability.enabled(ThreadNewMessageMenuTool.Copy))
        assertFalse(availability.enabled(ThreadNewMessageMenuTool.SelectText))
        assertTrue(ThreadNewMessageMenuActionAvailability().enabled(ThreadNewMessageMenuTool.SelectText))
    }

    private fun reply(id: String, seq: Long, preview: String, truncated: String? = null): String {
        val flag = truncated?.let { ""","truncated":$it""" }.orEmpty()
        return """{"messageId":"$id","seq":$seq,"preview":"$preview","senderId":"agent-1","senderType":"agent",""" +
            """"senderName":"Friday","senderDisplayName":null,"senderAvatarUrl":null,""" +
            """"createdAt":"2026-11-14T10:00:${seq.toString().padStart(2, '0')}.000Z","updatedAt":null$flag}"""
    }

    private fun seedFromJson(vararg replies: String, latestPreview: String = "the latest reply"): List<RaftThreadMessageItem> {
        val threadRow =
            """
            {"kind":"thread","threadChannelId":"thread-1","parentMessageId":"p-1","parentChannelId":"channel-1",
             "parentChannelName":"general","parentChannelType":"channel","parentMessagePreview":"parent",
             "parentMessageSenderType":"user","parentMessageSenderId":"u-a","latestActivityPreview":"$latestPreview",
             "latestActivitySenderType":"agent","latestActivitySenderId":"agent-1","latestActivitySenderName":"Friday",
             "latestActivityMessageId":"r-6","lastActivityAt":"2026-11-14T10:00:16.000Z","replyCount":${replies.size},
             "latestReplies":[${replies.joinToString(",")}],
             "unreadCount":0,"hasMention":false,"latestDelegatedBy":null,"latestRetracted":false,"parentRetracted":false}
            """.trimIndent()
        val inbox =
            parseSharedInboxSnapshot("""{"items":[$threadRow],"hasMore":false,"totalCount":1,"totalUnreadCount":0}""")
                ?: error("inbox did not parse")
        val route =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-reply-thread-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-1",
                streamChannelId = "thread-1",
                parentChannelId = "channel-1",
                parentMessageId = "p-1",
                status = "loading",
            )
        return threadActivityOpenSeedMessages(
            ThreadActivityOpenSeedSource(ownerServerId = "server-1", ownerUserId = "u-a", items = inbox.items),
            route.resolveThreadRouteIdentity(),
            serverId = "server-1",
            principalId = "u-a",
        )
    }
}
