package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.data.ThreadOutlineItem
import build.raft.app.data.parseSharedThreadOutlinePage
import build.raft.designsystem.theme.RaftScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The thread timeline's rules, as the web's (botiverse/slock #13534, a9bf79d66). */
class ThreadOutlineRulesTest {
    private fun item(id: String, seq: Long, sender: String = "u1") =
        ThreadOutlineItem(id = id, seq = seq, createdAt = "2026-11-08T06:00:00.000Z", senderId = sender, senderName = "Ann", preview = "text $id")

    private fun message(
        id: String,
        seq: Long?,
        kind: String = "human",
        type: String = "Chat",
        delegated: Boolean = false,
        deletedAt: String? = null,
    ) = RaftThreadMessageItem(
        id = id,
        authorId = "u1",
        authorName = "Ann",
        authorKind = kind,
        messageType = type,
        delegatedBy = if (delegated) build.raft.app.model.MessageDelegatedBy(id = "a1", name = "Otter") else null,
        deletedAt = deletedAt,
        seq = seq,
        body = "**hello**\n  world",
        timestampLabel = "2026-11-08T06:00:00.000Z",
    )

    @Test
    fun parsesTheServersPageNewestFirstAndDropsRowsWithoutIdOrSeq() {
        // Shape of humanOutline.api.test.ts (slock a9bf79d66).
        val page =
            parseSharedThreadOutlinePage(
                """{"items":[{"id":"m2","seq":12,"createdAt":"2026-11-08T06:00:00.000Z","senderId":"u1","senderName":"Ann","preview":"second","attachmentCount":0},
                {"id":"m1","seq":11,"createdAt":"2026-11-08T05:00:00.000Z","senderId":"u2","senderName":"Bo","preview":"","attachmentCount":2},
                {"id":"","seq":3}],"hasMore":true}"""
            )!!
        assertEquals(listOf("m2", "m1"), page.items.map { it.id })
        assertEquals(2, page.items[1].attachmentCount)
        assertTrue(page.hasMore)
        assertNull(parseSharedThreadOutlinePage("not json"))
    }

    @Test
    fun onlyAPersonsOwnSentLiveMessageIsListed() {
        assertTrue(message("a", 1).isThreadOutlineHumanMessage())
        listOf(
            message("agent", 1, kind = "agent"),
            message("system", 1, type = "System"),
            message("assistant", 1, delegated = true),
            message("retracted", 1, deletedAt = "2026-11-08T06:00:00.000Z"),
            message("unsent", null),
        ).forEach { assertFalse(it.isThreadOutlineHumanMessage(), it.id) }
    }

    @Test
    fun aLivePreviewIsTheMarkdownsPlainTextOnOneLine() {
        assertEquals("hello world", message("a", 1).toThreadOutlineItem().preview)
        assertEquals(400, threadOutlinePreview("x".repeat(900)).length)
    }

    @Test
    fun itemsKeepTheFirstMessageFirstAddNewerLiveRowsOnceAndDropRetractions() {
        val server = listOf(item("lead", 900), item("r1", 10), item("r2", 11))
        val loaded =
            listOf(
                message("r2", 11), // already listed
                message("r3", 13), // live, newer
                message("old", 5), // older than the page: the server's to list
                message("r1", 10, deletedAt = "x"), // retracted since
                message("agent", 14, kind = "agent"),
            )
        val items = threadOutlineItems(server, serverLoaded = true, leadingMessageId = "lead", origin = null, loadedMessages = loaded)
        assertEquals(listOf("lead", "r2", "r3"), items.map { it.id })
        assertTrue(threadOutlineItems(server, serverLoaded = false, "lead", null, loaded).isEmpty())
    }

    @Test
    fun aThreadHasAnOutlineAtFourMessagesFromPeopleNotCountingItsFirst() {
        val three = listOf(item("lead", 900), item("a", 1), item("b", 2), item("c", 3))
        assertFalse(threadOutlineHasOutline(three, "lead"))
        assertTrue(threadOutlineHasOutline(three + item("d", 4), "lead"))
        assertTrue(threadOutlineHasOutline(three, leadingMessageId = null))
    }

    @Test
    fun theRailShowsOnlyInAWideWindowWithRoomBesideTheList() {
        assertTrue(threadOutlineRailShown(RaftScale.Wide, 400.dp, hasOutline = true))
        assertFalse(threadOutlineRailShown(RaftScale.Compact, 400.dp, hasOutline = true))
        assertFalse(threadOutlineRailShown(RaftScale.Wide, 359.dp, hasOutline = true))
        assertFalse(threadOutlineRailShown(RaftScale.Wide, 800.dp, hasOutline = false))
    }

    @Test
    fun theCurrentItemIsTheJumpTheFirstMessageOrTheLastAtOrBeforeTheTopMessage() {
        val items = listOf(item("lead", 900), item("a", 10), item("b", 20), item("c", 30))
        assertEquals(2, threadOutlineCurrentIndex(items, "lead", topMessageId = "x", topSeq = 25, jumpedId = null))
        assertEquals(3, threadOutlineCurrentIndex(items, "lead", topMessageId = "x", topSeq = 25, jumpedId = "c"))
        assertEquals(0, threadOutlineCurrentIndex(items, "lead", topMessageId = "lead", topSeq = null, jumpedId = null))
        assertEquals(0, threadOutlineCurrentIndex(items, "lead", topMessageId = "x", topSeq = 5, jumpedId = null))
        assertEquals(-1, threadOutlineCurrentIndex(items.drop(1), "lead", topMessageId = "x", topSeq = 5, jumpedId = null))
    }

    @Test
    fun aJumpStaysCurrentUntilItHasBeenInViewAndLeftIt() {
        val jump = ThreadOutlineJump("b")
        assertEquals(jump, jump.afterView(listOf("a")))
        val landed = jump.afterView(listOf("a", "b"))!!
        assertTrue(landed.landed)
        assertEquals(landed, landed.afterView(listOf("b", "c")))
        assertNull(landed.afterView(listOf("c")))
    }
}
