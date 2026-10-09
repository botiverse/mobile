package build.raft.app

import build.raft.app.model.ExternalMessageAuthor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadReplyHeaderPreviewTitleTest {
    private fun origin(
        body: String = "",
        authorName: String = "Alice",
        authorHandle: String = "alice",
        attachments: List<KuiklyThreadAttachmentItem> = emptyList(),
        externalAuthor: ExternalMessageAuthor? = null
    ) = KuiklyThreadMessageItem(
        id = "msg-1",
        authorId = "user-1",
        authorName = authorName,
        authorHandle = authorHandle,
        externalAuthor = externalAuthor,
        body = body,
        attachments = attachments
    )

    private fun title(origin: KuiklyThreadMessageItem?) =
        replyThreadHeaderPreviewTitle(origin, imageLabel = "[图片]", fileLabel = "[文件]")

    @Test
    fun unloadedRootKeepsGenericTitle() {
        assertNull(title(null))
    }

    @Test
    fun loadedRootReadsAuthorAndSingleLineBody() {
        assertEquals("@Alice: 第一行 第二行", title(origin(body = "第一行\n\n  第二行  ")))
    }

    @Test
    fun markdownSyntaxIsNotReadAsContent() {
        assertEquals(
            "@Alice: 标题 加粗 code 链接 引用 条目",
            title(origin(body = "## 标题\n**加粗** `code` [链接](https://x.y)\n> 引用\n- 条目"))
        )
    }

    @Test
    fun attachmentOnlyRootUsesKindLabel() {
        val image = KuiklyThreadAttachmentItem(id = "a1", name = "p.png", mimeType = "image/png")
        val file = KuiklyThreadAttachmentItem(id = "a2", name = "r.pdf", mimeType = "application/pdf")
        assertEquals("@Alice: [图片]", title(origin(attachments = listOf(image))))
        assertEquals("@Alice: [文件]", title(origin(attachments = listOf(file))))
        assertEquals("@Alice: 看这个", title(origin(body = "看这个", attachments = listOf(image))))
    }

    @Test
    fun authorFallsBackToHandleThenExternalNameWins() {
        assertEquals("@alice: hi", title(origin(body = "hi", authorName = "")))
        assertEquals(
            "@Bob (Slack): hi",
            title(
                origin(
                    body = "hi",
                    externalAuthor = ExternalMessageAuthor(displayName = "Bob (Slack)", provider = "slack", actorKind = "human")
                )
            )
        )
    }

    @Test
    fun idShapedOrMissingAuthorKeepsGenericTitle() {
        assertNull(title(origin(body = "hi", authorName = "user-1", authorHandle = "")))
        assertNull(title(origin(body = "hi", authorName = "", authorHandle = "")))
    }

    @Test
    fun emptyRootKeepsGenericTitle() {
        assertNull(title(origin(body = "   ")))
    }

    @Test
    fun replyCountJoinsSubtitleLine() {
        assertEquals("#反馈 · 12 条回复", replyThreadHeaderSubtitle("#反馈", "12 条回复"))
        assertEquals("12 条回复", replyThreadHeaderSubtitle(null, "12 条回复"))
        assertEquals("#反馈", replyThreadHeaderSubtitle("#反馈", null))
        assertNull(replyThreadHeaderSubtitle(" ", null))
    }

    @Test
    fun headerReplyCountSurvivesTheRenderProjection() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "t1143",
                title = ThreadReplyPageTitle,
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                originMessage = origin(body = "root").copy(replyCount = 12)
            )
        // Same three projections SharedThreadState.toRenderSlice() hands the route.
        val renderPayload = payload.withoutThreadTimelineContent()
        val timelineSlice = payload.toThreadTimelineSlice()
        val replyMetadata = payload.toThreadReplyMetadataMap()

        // Neither object the header can see carries the count itself (the PR #2879 defect).
        assertNull(renderPayload.originMessage)
        assertEquals(0, timelineSlice.originMessage?.replyCount)

        assertEquals(12, threadHeaderOriginReplyCount(timelineSlice, replyMetadata))
        assertEquals("@Alice: root", title(timelineSlice.originMessage))
    }

    @Test
    fun headerReplyCountIsZeroWithoutLoadedOrigin() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "t1143-empty",
                title = ThreadReplyPageTitle,
                status = "loading",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread
            )
        assertEquals(0, threadHeaderOriginReplyCount(payload.toThreadTimelineSlice(), payload.toThreadReplyMetadataMap()))
    }
}
