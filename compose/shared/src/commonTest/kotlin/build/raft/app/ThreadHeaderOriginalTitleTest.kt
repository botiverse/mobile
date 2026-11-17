package build.raft.app

import build.raft.app.model.ExternalMessageAuthor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** raft-design conversation.md "Threads" / web botiverse/slock #15285: the thread head's title. */
class ThreadHeaderOriginalTitleTest {
    private fun origin(
        body: String = "",
        authorName: String = "Juno",
        authorHandle: String = "juno",
        attachments: List<RaftThreadAttachmentItem> = emptyList(),
        externalAuthor: ExternalMessageAuthor? = null,
        deletedAt: String? = null,
    ) = RaftThreadMessageItem(
        id = "msg-1",
        authorId = "user-1",
        authorName = authorName,
        authorHandle = authorHandle,
        externalAuthor = externalAuthor,
        deletedAt = deletedAt,
        body = body,
        attachments = attachments,
    )

    private fun resolve(
        origin: RaftThreadMessageItem?,
        originStatus: String = "loaded",
        originErrorCode: String? = null,
        externalFallbackName: String = "",
    ) = resolveThreadHeaderOriginal(originStatus, originErrorCode, origin, "Image", "File", externalFallbackName)

    private fun title(origin: RaftThreadMessageItem?, originStatus: String = "loaded"): String? =
        (resolve(origin, originStatus) as? ThreadHeaderOriginal.Shown)?.let { threadHeaderOriginalTitleText(it, ": ") }

    private val image = RaftThreadAttachmentItem(id = "a1", name = "p.png", mimeType = "image/png")
    private val image2 = RaftThreadAttachmentItem(id = "a2", name = "q.JPG", mimeType = "IMAGE/jpeg")
    private val pdf = RaftThreadAttachmentItem(id = "a3", name = "r.pdf", mimeType = "application/pdf")

    @Test
    fun shownOriginalIsSenderAndFirstNonEmptyPlainLine() {
        assertEquals(
            ThreadHeaderOriginal.Shown(messageId = "msg-1", sender = "Juno", text = "Can we ship today?"),
            resolve(origin(body = "\n  Can we ship today?  \nsecond line"))
        )
        // Markdown syntax is not read as words; no "@" before the sender and no Agent tag in the text.
        assertEquals("Juno: Title", title(origin(body = "## Title\n**bold** `code`")))
        assertEquals("Juno: bold code link", title(origin(body = "**bold** `code` [link](https://x.y)")))
    }

    @Test
    fun textlessOriginalIsImageWhenEveryFileIsAnImageElseFile() {
        for ((files, expected) in
            listOf(
                listOf(image) to "Juno: Image",
                listOf(image, image2) to "Juno: Image",
                listOf(pdf) to "Juno: File",
                listOf(image, pdf) to "Juno: File",
            )
        ) {
            assertEquals(expected, title(origin(attachments = files)), files.joinToString { it.mimeType })
        }
        // Words win over files.
        assertEquals("Juno: look", title(origin(body = "look", attachments = listOf(image))))
        // Neither words nor files: the title stays Thread.
        assertNull(title(origin(body = "   ")))
    }

    @Test
    fun deletedOriginalKeepsThreadTitleAndLocatesNothing() {
        assertEquals(ThreadHeaderOriginal.Deleted, resolve(origin(body = "hi", deletedAt = "2026-11-01T00:00:00Z")))
        assertEquals(ThreadHeaderOriginal.Deleted, resolve(null, originStatus = "missing"))
    }

    @Test
    fun onlyAPermissionRefusalMakesTheParentUnavailable() {
        assertEquals(ThreadHeaderOriginal.Unavailable, resolve(null, originStatus = "error", originErrorCode = "server_forbidden"))
        // A parent behind the plan's history window (404) or a network miss is not "can't view":
        // the title stays Thread and the place name still opens the conversation.
        assertNull(resolve(null, originStatus = "error", originErrorCode = "http_404"))
        assertNull(resolve(null, originStatus = "error", originErrorCode = "thread_parent_context_network"))
    }

    @Test
    fun unloadedOrUnnamedOriginalKeepsThreadTitle() {
        assertNull(resolve(null, originStatus = "loading"))
        assertNull(resolve(null))
        assertNull(title(origin(body = "hi", authorName = "user-1", authorHandle = "")))
        assertNull(title(origin(body = "hi", authorName = "", authorHandle = "")))
        assertEquals("juno: hi", title(origin(body = "hi", authorName = "")))
    }

    @Test
    fun externalSenderReadsItsShownName() {
        val named = ExternalMessageAuthor(displayName = "Bob (Slack)", provider = "slack", actorKind = "human")
        assertEquals("Bob (Slack): hi", title(origin(body = "hi", externalAuthor = named)))
        val fallback =
            ExternalMessageAuthor(displayName = "External user", provider = "", actorKind = "unknown", displayNameIsFallback = true)
        assertEquals(
            "外部用户",
            (resolve(origin(body = "hi", authorName = "External user", externalAuthor = fallback), externalFallbackName = "外部用户")
                as ThreadHeaderOriginal.Shown).sender
        )
    }

    @Test
    fun titleSwapsOnlyForAShownOriginalOutOfView() {
        val shown = ThreadHeaderOriginal.Shown("msg-1", "Juno", "Can we ship?")
        data class Case(val original: ThreadHeaderOriginal?, val outOfView: Boolean, val swapped: Boolean, val reachable: Boolean, val locate: String?)
        for (case in
            listOf(
                Case(shown, outOfView = false, swapped = false, reachable = true, locate = "msg-1"),
                Case(shown, outOfView = true, swapped = true, reachable = true, locate = "msg-1"),
                Case(ThreadHeaderOriginal.Deleted, outOfView = true, swapped = false, reachable = true, locate = null),
                Case(ThreadHeaderOriginal.Unavailable, outOfView = true, swapped = false, reachable = false, locate = null),
                // Behind the history window / not loaded: opens the conversation, where the paywall stops it.
                Case(null, outOfView = true, swapped = false, reachable = true, locate = null),
            )
        ) {
            val state = ThreadHeaderOriginalState().apply {
                original = case.original
                originOutOfView = case.outOfView
            }
            assertEquals(case.swapped, state.swapped, case.toString())
            assertEquals(case.reachable, state.parentReachable, case.toString())
            assertEquals(case.locate, state.locateMessageId(), case.toString())
        }
    }

    @Test
    fun originRowIsOutOfViewOnlyOnceItLeavesTheViewport() {
        // Nothing laid out yet: not out of view.
        assertFalse(threadOriginRowOutOfView(anyRowLaidOut = false, rowOffset = null, rowSize = 0, viewportStart = 0, viewportEnd = 800))
        // The list no longer lays the row out (scrolled far away).
        assertTrue(threadOriginRowOutOfView(anyRowLaidOut = true, rowOffset = null, rowSize = 0, viewportStart = 0, viewportEnd = 800))
        // Fully in view, partly in view (still "visible"), just gone above, below the viewport.
        assertFalse(threadOriginRowOutOfView(true, rowOffset = 0, rowSize = 200, viewportStart = 0, viewportEnd = 800))
        assertFalse(threadOriginRowOutOfView(true, rowOffset = -199, rowSize = 200, viewportStart = 0, viewportEnd = 800))
        assertTrue(threadOriginRowOutOfView(true, rowOffset = -200, rowSize = 200, viewportStart = 0, viewportEnd = 800))
        assertTrue(threadOriginRowOutOfView(true, rowOffset = 800, rowSize = 200, viewportStart = 0, viewportEnd = 800))
    }
}
