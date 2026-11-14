package build.raft.app

import build.raft.app.data.ThreadPreviewCharsQueryKey
import build.raft.app.data.isThreadPreviewCharsRequest
import build.raft.app.data.parseSharedInboxSnapshot
import build.raft.app.data.withThreadPreviewChars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Asked with `?threadPreviewChars=200` (botiverse/slock #14921), the server cuts a thread summary's
 * `latestReplies[].preview` to at most 200 characters ending in "…" and flags it `truncated: true`. A reply seeded from that preview while a thread
 * opens from Activity is a cut stand-in: it loads its full text (plain text, loading indicator), is
 * never copied or quoted, and never wins over a whole copy of the same message.
 */
class ThreadActivityOpenSeedReplyPreviewTruncationTest {
    private val cut = "x".repeat(199) + "…"

    @Test
    fun onlyTheServersFlagMarksACut() {
        val seeded = seedFromJson(
            reply("r-1", 11, cut, truncated = "true"),
            // A whole reply that really ends in "…" stays whole, flagged false or (as the server
            // sends uncut previews) unflagged.
            reply("r-2", 12, "y".repeat(199) + "…", truncated = "false"),
            reply("r-3", 13, "z".repeat(150) + "…"),
            reply("r-4", 14, "the whole reply"),
            reply("r-5", 15, "w".repeat(250)),
        )
        assertEquals(
            listOf("r-1" to true, "r-2" to false, "r-3" to false, "r-4" to false, "r-5" to false, "r-6" to false),
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
    fun aTruncatedReplyNeverRendersAsABodyOrCopiesAndAFailedLoadSaysSo() {
        val seeded = seedFromJson(reply("r-1", 11, cut, truncated = "true"), latestPreview = "ok").single { it.id == "r-1" }
        assertTrue(seeded.hasActivityCutBody())
        assertFalse(threadMessageRendersRichBody(seeded, richContentEnabled = true), "plain text, no Markdown/links/mentions")
        assertTrue(threadMessageRendersRichBody(seeded.copy(activityPreviewTruncated = false), richContentEnabled = true))

        val failed = seeded.withActivityPreviewSettled()
        assertFalse(threadMessageRendersRichBody(failed, richContentEnabled = true))
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

    @Test
    fun theThreadSummaryReadsAskForCutPreviews() {
        val asked =
            listOf(
                "messages/channel/channel-1",
                "/messages/channel/channel-1/by-activity",
                "messages/context/m-1",
                "channels/channel-1/threads",
            )
        for (path in asked) {
            assertTrue(isThreadPreviewCharsRequest("GET", path), path)
            assertEquals(
                listOf("limit" to "30", ThreadPreviewCharsQueryKey to "200"),
                listOf("limit" to "30").withThreadPreviewChars("GET", path),
                path,
            )
        }
        val notAsked =
            listOf(
                "GET" to "messages/channel/channel-1/human-outline",
                "GET" to "channels/threads/followed",
                "GET" to "channels/inbox",
                "GET" to "messages/sync",
                "POST" to "channels/channel-1/threads",
            )
        for ((method, path) in notAsked) {
            assertFalse(isThreadPreviewCharsRequest(method, path), "$method $path")
            assertEquals(listOf("limit" to "30"), listOf("limit" to "30").withThreadPreviewChars(method, path))
        }
        val explicit = listOf(ThreadPreviewCharsQueryKey to "80")
        assertEquals(explicit, explicit.withThreadPreviewChars("GET", "messages/channel/channel-1"), "never asked twice")
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
