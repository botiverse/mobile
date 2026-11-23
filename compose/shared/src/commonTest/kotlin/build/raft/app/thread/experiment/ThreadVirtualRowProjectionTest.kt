package build.raft.app.thread.experiment

import build.raft.app.LazyViewportAnchorTarget
import build.raft.app.RaftThreadMessageItem
import build.raft.app.RaftThreadScreenPayload
import build.raft.app.ThreadBottomScrollPaddingListItemKey
import build.raft.app.ThreadOlderMessagesListItemKey
import build.raft.app.ThreadOriginListItemKey
import build.raft.app.threadDuplicateRandomIds
import build.raft.app.threadRepliesFocusedItemScrollOffset
import build.raft.app.threadSystemMessageFolds
import build.raft.app.toThreadTimelineSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadVirtualRowProjectionTest {
    private fun chatMessage(seq: Int, body: String = "Chat message $seq") =
        RaftThreadMessageItem(
            id = "m$seq",
            authorId = "u1",
            authorName = "Artin",
            seq = seq.toLong(),
            body = body,
            timestampLabel = "2026-07-30T02:00:${(seq % 60).toString().padStart(2, '0')}Z",
            messageType = "chat",
        )

    private fun systemTaskMessage(seq: Int) =
        RaftThreadMessageItem(
            id = "sys$seq",
            authorId = "sys",
            authorName = "System",
            seq = seq.toLong(),
            body = "✅ Otter moved #$seq \"Task $seq\" to Done",
            timestampLabel = "2026-07-30T02:01:${(seq % 60).toString().padStart(2, '0')}Z",
            messageType = "system",
        )

    @Test
    fun collapses100ConsecutiveSystemMessagesAndPreservesAllLookupsAndReadTail() {
        val messages =
            buildList {
                for (i in 0 until 15) add(chatMessage(i))
                for (i in 0 until 100) add(systemTaskMessage(15 + i))
                for (i in 115 until 130) add(chatMessage(i))
            }
        val origin = chatMessage(0, "Origin parent message").copy(id = "origin-1")
        val slice =
            RaftThreadScreenPayload(
                instanceId = "proj-test",
                status = "content",
                originStatus = "ready",
                originMessage = origin,
                messages = messages,
                canLoadMoreBefore = true,
                canLoadMoreAfter = true,
            ).toThreadTimelineSlice()

        val folds = threadSystemMessageFolds(slice.messages, slice.timelineGaps)
        val duplicateRandomIds = slice.messages.threadDuplicateRandomIds()
        val collapsed =
            buildThreadVirtualRowProjection(
                renderPayload = slice,
                duplicateMessageRandomIds = duplicateRandomIds,
                systemMessageFolds = folds,
                expandedSystemMessageGroups = emptySet(),
                showReplyEmptyState = false,
                showReplyLoadingSkeleton = false,
            )

        // Origin + HistoryBoundary + 15 chat + 1 summary + 15 chat + NewerBoundary + BottomSpacer = 35
        assertEquals(0, collapsed.originRowIndex)
        assertEquals(1, collapsed.historyBoundaryRowIndex)
        assertEquals(2, collapsed.firstMessageRowIndex)
        assertEquals(32, collapsed.latestMessageRowIndex)
        assertEquals(33, collapsed.newerBoundaryRowIndex)
        assertEquals(34, collapsed.bottomSpacerRowIndex)
        assertEquals(35, collapsed.totalRowCount)
        assertEquals(99, collapsed.collapsedPlaceholderRowsEliminated)

        // Every folded member maps O(1) to the summary row index & key.
        val summaryRowIndex = 2 + 15
        val summaryRow = collapsed.rows[summaryRowIndex] as ThreadVirtualRow.Message
        assertEquals("sys15", summaryRow.messageId)
        assertEquals(15, summaryRow.messageIndex)
        assertEquals(114, summaryRow.coveredLastMessageIndex)
        assertEquals(99, summaryRow.foldedMemberCount)
        assertEquals(summaryRowIndex, collapsed.rowIndexForMessageId("sys15"))
        assertEquals(summaryRowIndex, collapsed.rowIndexForMessageId("sys64"))
        assertEquals(summaryRowIndex, collapsed.rowIndexForMessageId("sys114"))
        assertEquals(summaryRow.key, collapsed.rowKeyForMessageId("sys64"))

        // Origin & boundary viewport targets resolve cleanly.
        assertEquals(0, collapsed.rowIndexForViewportTarget(LazyViewportAnchorTarget.StableItem(ThreadOriginListItemKey)))
        assertEquals(1, collapsed.rowIndexForViewportTarget(LazyViewportAnchorTarget.StableItem(ThreadOlderMessagesListItemKey)))
        assertEquals(34, collapsed.rowIndexForViewportTarget(LazyViewportAnchorTarget.StableItem(ThreadBottomScrollPaddingListItemKey)))
        assertEquals(0, collapsed.rowIndexForMessageId("origin-1"))
        assertEquals(ThreadOriginListItemKey, collapsed.rowKeyForMessageId("origin-1"))
        assertEquals(ThreadOlderMessagesListItemKey, collapsed.rows[1].key)
        assertEquals(ThreadBottomScrollPaddingListItemKey, collapsed.rows[34].key)

        // Read-tail through a visible collapsed summary covers the full run up to message index 114.
        assertEquals(114, collapsed.visibleReadTailMessageIndex(listOf(15, 16, summaryRowIndex)))
        // When the trailing chat message row is visible, read-tail reaches 129.
        assertEquals(129, collapsed.visibleReadTailMessageIndex(listOf(summaryRowIndex, 32, 34)))

        // Visible message index range spans the full collapsed run when summary row is visible.
        val layoutInfo =
            VirtualTimelineLayoutInfo(
                visibleItemsInfo =
                    listOf(
                        VirtualTimelineLazyItemInfo(index = 16, key = collapsed.rows[16].key, offset = -20, size = 60, contentType = "message"),
                        VirtualTimelineLazyItemInfo(index = summaryRowIndex, key = summaryRow.key, offset = 40, size = 36, contentType = "system-summary"),
                        VirtualTimelineLazyItemInfo(index = summaryRowIndex + 1, key = collapsed.rows[summaryRowIndex + 1].key, offset = 76, size = 60, contentType = "message"),
                    ),
                viewportStartOffset = 0,
                viewportEndOffset = 700,
                totalItemsCount = collapsed.totalRowCount,
                viewportSize = androidx.compose.ui.unit.IntSize(400, 700),
                beforeContentPadding = 0,
                afterContentPadding = 0,
            )
        assertEquals(14..115, collapsed.visibleMessageIndexRange(layoutInfo))

        // Expanding the group restores all 100 individual message rows with zero eliminated placeholders.
        val groupId = assertNotNull(folds[15]?.groupId)
        val expanded =
            buildThreadVirtualRowProjection(
                renderPayload = slice,
                duplicateMessageRandomIds = duplicateRandomIds,
                systemMessageFolds = folds,
                expandedSystemMessageGroups = setOf(groupId),
                showReplyEmptyState = false,
                showReplyLoadingSkeleton = false,
            )
        assertEquals(134, expanded.totalRowCount)
        assertEquals(0, expanded.collapsedPlaceholderRowsEliminated)
        assertEquals(2 + 64, expanded.rowIndexForMessageId("sys79"))
        assertEquals(131, expanded.latestMessageRowIndex)
    }

    @Test
    fun desiredViewportTopMatchesProductionRepliesFocusFormulaAndPinClearsFollowBottom() {
        val viewportEnd = 800
        val minReserve = 192
        for (itemSize in listOf(80, 220, 460, 720, 960)) {
            val expectedTop = -threadRepliesFocusedItemScrollOffset(itemSize, viewportEnd, minReserve)
            val actualTop =
                VirtualMessageTimelineState.desiredViewportTopPx(
                    itemHeightPx = itemSize,
                    viewportHeightPx = viewportEnd,
                    align = VirtualTimelineAlign.FocusedThreadReplies,
                    reserveBottomPx = minReserve,
                )
            assertEquals(expectedTop, actualTop, "FocusedThreadReplies mismatch for itemSize=$itemSize")
        }

        val state = VirtualMessageTimelineState(initialFollowBottom = true)
        assertTrue(state.followBottom)
        assertTrue(state.isJumpSettling)
        assertFalse(state.isAutoLoadSuppressed)

        state.pinReadingAnchorKey("msg:m20")
        assertFalse(state.followBottom, "pinning a reading anchor must disable followBottom atomically")
        assertEquals("msg:m20", state.pinnedReadingAnchorKey)

        val focusState = VirtualMessageTimelineState(initialFollowBottom = false, initialJumpKey = "msg:m42")
        assertFalse(focusState.followBottom)
        assertTrue(focusState.isJumpSettling)
        assertTrue(focusState.isAutoLoadSuppressed)
        assertNull(focusState.pinnedReadingAnchorKey)
    }
}
