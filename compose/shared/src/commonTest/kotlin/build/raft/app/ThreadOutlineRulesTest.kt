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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import build.raft.app.data.RaftThreadOutlineApi
import build.raft.app.data.ThreadOutlinePage

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

    @Test
    fun theOutlinePagesBackOnlyWhenTheTopMessageIsOlderThanWhatItHolds() {
        assertEquals(5L, threadOutlinePageBackTarget(hasMore = true, oldestLoadedSeq = 10, topIsLeading = false, topSeq = 5))
        assertNull(threadOutlinePageBackTarget(hasMore = true, oldestLoadedSeq = 10, topIsLeading = false, topSeq = 12))
        assertEquals(0L, threadOutlinePageBackTarget(hasMore = true, oldestLoadedSeq = 10, topIsLeading = true, topSeq = null))
        assertNull(threadOutlinePageBackTarget(hasMore = false, oldestLoadedSeq = 10, topIsLeading = true, topSeq = 5))
    }

    @Test
    fun aJumpToTheFirstMessageLoadsTheThreadsStartThenPinsItsRow() {
        fun step(canLoadMoreBefore: Boolean, status: String = "idle", shown: Boolean = true, request: Int = 1, consumed: Int = 0) =
            threadOutlineOriginJumpStep(request, consumed, canLoadMoreBefore, status, shown)
        assertEquals(ThreadOutlineOriginJumpStep.Idle, step(canLoadMoreBefore = true, request = 0))
        assertEquals(ThreadOutlineOriginJumpStep.Idle, step(canLoadMoreBefore = false, request = 2, consumed = 2))
        assertEquals(ThreadOutlineOriginJumpStep.LoadOlder, step(canLoadMoreBefore = true))
        assertEquals(ThreadOutlineOriginJumpStep.Wait, step(canLoadMoreBefore = true, status = "loading"))
        assertEquals(ThreadOutlineOriginJumpStep.GiveUp, step(canLoadMoreBefore = true, status = "error"))
        assertEquals(ThreadOutlineOriginJumpStep.Pin, step(canLoadMoreBefore = false))
        assertEquals(ThreadOutlineOriginJumpStep.Pin, step(canLoadMoreBefore = false, status = "error"))
        assertEquals(ThreadOutlineOriginJumpStep.Wait, step(canLoadMoreBefore = false, shown = false))
    }

    @Test
    fun theMenuOffersTimelineOnlyWithAnOutlineAndNoRail() {
        assertTrue(threadOutlineMenuOffered(hasOutline = true, railShown = false))
        assertFalse(threadOutlineMenuOffered(hasOutline = true, railShown = true))
        assertFalse(threadOutlineMenuOffered(hasOutline = false, railShown = false))
    }

    /** Pages of 2 rows below seq 100; an older read waits on [gate] until the test releases it. */
    private class PagedApi : RaftThreadOutlineApi {
        val olderReads = mutableListOf<Long>()
        var gate = CompletableDeferred<Unit>()

        override suspend fun page(channelId: String, beforeSeq: Long?): ThreadOutlinePage {
            val top = beforeSeq ?: 100L
            if (beforeSeq != null) {
                olderReads += beforeSeq
                gate.await()
            }
            return ThreadOutlinePage(items = listOf(item("s${top - 1}", top - 1), item("s${top - 2}", top - 2)), hasMore = true)
        }

        private fun item(id: String, seq: Long) =
            ThreadOutlineItem(id = id, seq = seq, createdAt = "", senderId = "u", senderName = "", preview = id)
    }

    @Test
    fun olderReadsGoOneAtATime() = runTest {
        val api = PagedApi()
        val state = ThreadOutlineState(api, "thread-1")
        state.loadNewest()
        val first = async { state.loadOlder() }
        val second = async { state.loadOlder() }
        testScheduler.runCurrent()
        assertEquals(listOf(98L), api.olderReads, "a second caller waits for the read on its way")
        api.gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf(98L), api.olderReads)
        assertEquals(listOf(96L, 97L, 98L, 99L), state.serverItems.map { it.seq })
    }

    @Test
    fun theMenusTimelineFollowsTheRouteParentBeforeTheFirstMessageLoads() = runTest {
        val state = ThreadOutlineState(PagedApi(), "thread-1")
        assertNull(state.openTimelineAction(leadingMessageId = "s99", origin = null, messages = emptyList()))
        state.loadNewest() // s99, s98: the parent plus one reply
        assertNull(state.openTimelineAction(leadingMessageId = "s99", origin = null, messages = emptyList()))
        val replies = (101L..103L).map { message("m$it", it) }
        val open = state.openTimelineAction(leadingMessageId = "s99", origin = null, messages = replies)!!
        open()
        assertTrue(state.listOpen)
    }
}
