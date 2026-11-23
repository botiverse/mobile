package build.raft.app

import build.raft.app.data.AdaptiveParentFocusRequest
import build.raft.app.data.MessageContextHiddenTarget
import build.raft.app.data.ScreenError
import build.raft.app.model.ExternalMessageAuthor
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun aQuoteReplyOriginalReadsAsItsReplyLoadedOrBehindTheHistoryWindow() {
        // web threadHeaderTitle.ts:24,67 (botiverse/slock #16158, tests/quoteReply.behavior.test.tsx:982):
        // the first line of `notificationPlainText`, which leads a quote reply with its reply.
        val quoteReply = "> Status page banner goes live at 9:00 PT\n> msg=c54e1234\n\nCan we move it to **8:30** so it's up before the flags flip?"
        assertEquals("Juno: Can we move it to 8:30 so it's up before the flags flip?", title(origin(body = quoteReply)))
        assertEquals(
            ThreadHeaderOriginal.Shown("msg-1", "Juno", "Can we move it to 8:30 so it's up before the flags flip?", behindHistory = true),
            threadHeaderHiddenOriginal(hidden.copy(preview = quoteReply), unknownSenderLabel = ""),
        )
    }

    @Test
    fun onlyAPermissionRefusalMakesTheParentUnavailable() {
        assertEquals(ThreadHeaderOriginal.Unavailable, resolve(null, originStatus = "error", originErrorCode = "server_forbidden"))
        // A plain 404 (not found, or not readable: the context route answers both alike) or a network
        // miss is not "can't view": the title stays Thread and the place name still opens the conversation.
        assertNull(resolve(null, originStatus = "error", originErrorCode = "http_404"))
        assertNull(resolve(null, originStatus = "error", originErrorCode = "thread_parent_context_network"))
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

    private val hidden =
        MessageContextHiddenTarget(
            messageId = "msg-1",
            senderType = "user",
            senderId = "user-1",
            senderName = "Juno",
            preview = "Can we ship **today**?",
        )

    @Test
    fun parentContextRefusalReadsOnlyTheParentsHiddenTargetAndReportsTheRest() {
        val lines = mutableListOf<String>()
        fun read(error: ScreenError) = error.threadParentHiddenTargetOrNull("msg-1") { lines += it }
        val limited = ScreenError(code = "http_404", retryable = false, serverCode = "history_limited", historyLimitedTarget = hidden, traceId = null)
        assertEquals(hidden, read(limited))
        assertTrue(lines.isEmpty())
        // A plain not found and other failures carry nothing and report nothing.
        assertNull(read(ScreenError(code = "http_404", retryable = false, traceId = null)))
        assertNull(read(ScreenError(code = "server_unavailable", serverCode = "history_limited", traceId = null)))
        assertTrue(lines.isEmpty())
        // Another message's target, an unreadable one, an unknown code: read as not found, never silently.
        assertNull(read(limited.copy(historyLimitedTarget = hidden.copy(messageId = "msg-2"))))
        assertNull(read(limited.copy(historyLimitedTarget = null)))
        assertNull(read(limited.copy(serverCode = "thread_gone")))
        assertEquals(3, lines.size, lines.toString())
        assertTrue(lines.all { it.startsWith("projection_value_unknown source=thread_parent_context") }, lines.toString())
        assertTrue(lines.last().contains("value=thread_gone"), lines.last())
    }

    @Test
    fun theConversationBesideTheThreadFocusesTheOriginalOrItsHistoryStart() {
        val target = RaftAdaptiveDetailCloseRequest("shell", "channel:c1", 3)
        assertEquals(
            SharedThreadIntent.FocusMessage(messageId = "msg-1", switchToTab = RaftThreadTab.Chat.wireValue),
            AdaptiveParentFocusRequest(target, "msg-1").toThreadFocusIntent()
        )
        assertEquals(
            SharedThreadIntent.FocusHistoryStart(switchToTab = RaftThreadTab.Chat.wireValue),
            AdaptiveParentFocusRequest(target, "msg-1", historyStart = true).toThreadFocusIntent()
        )
        // The page carries the request to its list, which jumps to the start like the outline's jump.
        val channel = RaftThreadScreenPayload(historyStartFocusRequest = 2)
        assertEquals(2, channel.toThreadTimelineSlice().historyStartFocusRequest)
    }
}
