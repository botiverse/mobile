package build.raft.app

import build.raft.app.model.ExternalMessageAuthor
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderOriginal
import build.raft.app.thread.ThreadReplyPageTitle
import build.raft.app.thread.resolveThreadHeaderOriginal
import build.raft.app.thread.threadBeginningReplyCount
import build.raft.app.thread.threadHeaderOriginalTitleText
import build.raft.app.thread.threadHeaderReplyCount
import build.raft.app.thread.threadOlderMessagesStatusSpec
import build.raft.app.thread.toThreadReplyMetadataMap
import build.raft.app.thread.toThreadTimelineSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadReplyHeaderPreviewTitleTest {
    private fun origin(
        body: String = "",
        authorName: String = "Alice",
        authorHandle: String = "alice",
        attachments: List<RaftThreadAttachmentItem> = emptyList(),
        externalAuthor: ExternalMessageAuthor? = null
    ) = RaftThreadMessageItem(
        id = "msg-1",
        authorId = "user-1",
        authorName = authorName,
        authorHandle = authorHandle,
        externalAuthor = externalAuthor,
        body = body,
        attachments = attachments
    )

    private fun title(origin: RaftThreadMessageItem?): String? =
        (resolveThreadHeaderOriginal("loaded", null, origin, imageLabel = "图片", fileLabel = "文件") as? ThreadHeaderOriginal.Shown)
            ?.let { threadHeaderOriginalTitleText(it, "：") }

    @Test
    fun externalAuthorShownNameFollowsTheFlagNotTheText() {
        val fallback =
            ExternalMessageAuthor(displayName = "External user", provider = "", actorKind = "unknown", displayNameIsFallback = true)
        val named = ExternalMessageAuthor(displayName = "External user", provider = "slack", actorKind = "human")
        assertEquals("外部ユーザー", externalAuthorShownName(fallback, fallback.displayName, "外部ユーザー"))
        assertEquals("External user", externalAuthorShownName(named, named.displayName, "外部ユーザー"))
        assertEquals("Ada", externalAuthorShownName(null, "Ada", "外部ユーザー"))
    }

    /** Web ThreadPanelHeader: max(loaded replies + the replies behind the history window, the summary). */
    @Test
    fun headerReplyCountCountsLoadedAndHiddenRepliesWhenTheSummaryLags() {
        val replies = (1..3).map { origin(body = "r$it").copy(id = "reply-$it") }
        // The iOS 1.23 case: the origin's summary still says 0 while its replies are on screen.
        val payload = replyThread(status = "content", origin = origin(body = "root"), messages = replies)
        val slice = payload.toThreadTimelineSlice()
        val metadata = payload.toThreadReplyMetadataMap()
        assertEquals(3, threadHeaderReplyCount(slice, metadata))
        assertEquals(10, threadHeaderReplyCount(slice, metadata, hiddenHistoryCount = 7))
        val summarized = replyThread(status = "content", origin = origin(body = "root").copy(replyCount = 40), messages = replies)
        assertEquals(40, threadHeaderReplyCount(summarized.toThreadTimelineSlice(), summarized.toThreadReplyMetadataMap()))
    }

    /**
     * Hands 75dc2b46: a thread opened from Activity kept its seeded origin (summary 0) while its 19
     * replies loaded, and "Beginning of conversation" said "0 replies" above them. The row now reads
     * the header's count, so the two never disagree.
     */
    @Test
    fun beginningOfConversationCountMatchesTheHeaderWhenTheSummaryLags() {
        val replies = (1..2).map { origin(body = "r$it").copy(id = "reply-$it") }
        val cases =
            listOf(
                replyThread(status = "content", origin = origin(body = "root"), messages = replies) to 2,
                replyThread(status = "content", origin = origin(body = "root").copy(replyCount = 40), messages = replies) to 40,
                replyThread(status = "empty", origin = origin(body = "root")) to 0,
                replyThread(status = "loading", origin = origin(body = "root")) to null,
                replyThread(status = "content", origin = origin(body = "root"), mainLoadFailed = true) to null,
            )
        cases.forEach { (payload, expected) ->
            val slice = payload.toThreadTimelineSlice()
            val header = threadHeaderReplyCount(slice, payload.toThreadReplyMetadataMap())
            assertEquals(expected, header, payload.status)
            assertEquals(header, threadOlderMessagesStatusSpec(slice)?.secondaryReplyCount, payload.status)
        }
        // Not a reply thread (no origin): no count under the row.
        assertNull(replyThread(status = "content", messages = replies).threadBeginningReplyCount())
    }

    private fun replyThread(
        status: String,
        origin: RaftThreadMessageItem? = null,
        messages: List<RaftThreadMessageItem> = emptyList(),
        mainLoadFailed: Boolean = false,
    ) = RaftThreadScreenPayload(
        instanceId = "t-header-count",
        title = ThreadReplyPageTitle,
        status = status,
        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
        originMessage = origin,
        messages = messages,
        mainLoadFailed = mainLoadFailed,
    )
}
