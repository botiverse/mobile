package build.raft.app.thread.experiment

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.LazyViewportAnchorTarget
import build.raft.app.ThreadListViewport
import build.raft.app.ThreadBottomScrollPaddingListItemKey
import build.raft.app.ThreadNewerMessagesListItemKey
import build.raft.app.ThreadOlderMessagesListItemKey
import build.raft.app.ThreadOriginListItemKey
import build.raft.app.ThreadReplyEmptyListItemKey
import build.raft.app.ThreadReplyLoadingListItemKey
import build.raft.app.ThreadSystemMessageFold
import build.raft.app.ThreadTimelineSlice
import build.raft.app.ThreadVisibleMessageAnchor
import build.raft.app.hasThreadHistoryBoundaryItem
import build.raft.app.hasThreadOriginItem
import build.raft.app.messageListItemKey
import build.raft.app.threadReadingAnchorItem

/**
 * Forward-ordered virtual row emitted into [VirtualMessageTimeline] by the real `ThreadMessagesList`
 * when `LocalThreadUseVirtualTimeline` is enabled (`#proj-mobile:#268`).
 *
 * Unlike `LazyColumn` (which keeps 1 item slot per folded `ThreadSystemMessageFold.Member` as a
 * zero-height `Box`), collapsed system-message members are completely omitted from [rows] while
 * [ThreadVirtualRowProjection] preserves exact message-ID -> row-index, folded-group, and
 * read-tail index mappings.
 */
/** Observe-only experiment hook (`#proj-mobile:#268`) exposing live virtual timeline state & projection to E2E tests. */
internal val LocalThreadVirtualTimelineObserver =
    staticCompositionLocalOf<((VirtualMessageTimelineState, ThreadVirtualRowProjection) -> Unit)?> { null }

/** Observe-only experiment hook (`#proj-mobile:#268`) exposing the unified [ThreadListViewport] for both backends in E2E tests. */
internal val LocalThreadViewportObserver =
    staticCompositionLocalOf<((ThreadListViewport) -> Unit)?> { null }

internal sealed interface ThreadVirtualRow {
    val key: String
    val contentType: String

    data object Origin : ThreadVirtualRow {
        override val key: String = ThreadOriginListItemKey
        override val contentType: String = "origin"
    }

    data object HistoryBoundary : ThreadVirtualRow {
        override val key: String = ThreadOlderMessagesListItemKey
        override val contentType: String = "history-boundary"
    }

    data object ReplyEmpty : ThreadVirtualRow {
        override val key: String = ThreadReplyEmptyListItemKey
        override val contentType: String = "reply-empty"
    }

    data object ReplyLoading : ThreadVirtualRow {
        override val key: String = ThreadReplyLoadingListItemKey
        override val contentType: String = "reply-loading"
    }

    data class Message(
        val messageIndex: Int,
        val messageId: String,
        override val key: String,
        /** Last message index covered by this row (equals `messageIndex` unless this is a collapsed summary). */
        val coveredLastMessageIndex: Int,
        val foldedMemberCount: Int = 0,
    ) : ThreadVirtualRow {
        override val contentType: String = if (foldedMemberCount > 0) "system-summary" else "message"
    }

    data object NewerBoundary : ThreadVirtualRow {
        override val key: String = ThreadNewerMessagesListItemKey
        override val contentType: String = "newer-boundary"
    }

    data object BottomSpacer : ThreadVirtualRow {
        override val key: String = ThreadBottomScrollPaddingListItemKey
        override val contentType: String = "bottom-spacer"
    }
}

internal class ThreadVirtualRowProjection(
    val rows: List<ThreadVirtualRow>,
    val originRowIndex: Int,
    val historyBoundaryRowIndex: Int,
    val firstMessageRowIndex: Int,
    val latestMessageRowIndex: Int,
    val newerBoundaryRowIndex: Int,
    val bottomSpacerRowIndex: Int,
    val collapsedPlaceholderRowsEliminated: Int,
    private val messageIdToRowIndex: Map<String, Int>,
    private val messageIdToRowKey: Map<String, String>,
) {
    val totalRowCount: Int
        get() = rows.size

    fun rowIndexForMessageId(messageId: String): Int = messageIdToRowIndex[messageId] ?: -1

    fun rowKeyForMessageId(messageId: String): String? = messageIdToRowKey[messageId]

    fun rowIndexForViewportTarget(target: LazyViewportAnchorTarget): Int =
        when (target) {
            is LazyViewportAnchorTarget.Message -> rowIndexForMessageId(target.messageId)
            is LazyViewportAnchorTarget.StableItem ->
                when (target.key) {
                    ThreadOriginListItemKey -> originRowIndex
                    ThreadOlderMessagesListItemKey -> historyBoundaryRowIndex
                    ThreadNewerMessagesListItemKey -> newerBoundaryRowIndex
                    ThreadBottomScrollPaddingListItemKey -> bottomSpacerRowIndex
                    else -> rows.indexOfFirst { it.key == target.key }
                }
        }

    fun messageIdAtRowIndex(rowIndex: Int): String? =
        when (val row = rows.getOrNull(rowIndex)) {
            is ThreadVirtualRow.Message -> row.messageId
            else -> null
        }

    fun firstVisibleMessageAnchor(
        layoutInfo: LazyListLayoutInfo,
        userScrollGeneration: Int,
    ): ThreadVisibleMessageAnchor? {
        for (item in layoutInfo.visibleItemsInfo) {
            val row = rows.getOrNull(item.index) as? ThreadVirtualRow.Message ?: continue
            return ThreadVisibleMessageAnchor(
                messageId = row.messageId,
                offset = item.offset,
                userScrollGeneration = userScrollGeneration,
            )
        }
        return null
    }

    fun readingAnchorMessage(
        layoutInfo: LazyListLayoutInfo,
        userScrollGeneration: Int,
    ): ThreadVisibleMessageAnchor? {
        val item =
            threadReadingAnchorItem(layoutInfo.visibleItemsInfo) { index ->
                rows.getOrNull(index) is ThreadVirtualRow.Message
            } ?: return null
        val row = rows.getOrNull(item.index) as? ThreadVirtualRow.Message ?: return null
        return ThreadVisibleMessageAnchor(
            messageId = row.messageId,
            offset = item.offset,
            userScrollGeneration = userScrollGeneration,
        )
    }

    /** Highest message index represented among [visibleRowIndexes], accounting for collapsed summary runs. */
    fun visibleReadTailMessageIndex(visibleRowIndexes: List<Int>): Int? {
        var maxIndex = -1
        for (rowIndex in visibleRowIndexes) {
            val row = rows.getOrNull(rowIndex) as? ThreadVirtualRow.Message ?: continue
            if (row.coveredLastMessageIndex > maxIndex) {
                maxIndex = row.coveredLastMessageIndex
            }
        }
        return if (maxIndex >= 0) maxIndex else null
    }

    /** Inclusive source-message index range visible inside `layoutInfo`'s viewport (for unread-thread jump pills). */
    fun visibleMessageIndexRange(layoutInfo: LazyListLayoutInfo): IntRange? {
        val viewportStart = layoutInfo.viewportStartOffset
        val viewportEnd = layoutInfo.viewportEndOffset
        var first = Int.MAX_VALUE
        var last = -1
        for (item in layoutInfo.visibleItemsInfo) {
            if (item.offset + item.size <= viewportStart || item.offset >= viewportEnd) continue
            val row = rows.getOrNull(item.index) as? ThreadVirtualRow.Message ?: continue
            if (row.messageIndex < first) first = row.messageIndex
            if (row.coveredLastMessageIndex > last) last = row.coveredLastMessageIndex
        }
        return if (last >= first && first != Int.MAX_VALUE) first..last else null
    }
}

internal fun buildThreadVirtualRowProjection(
    renderPayload: ThreadTimelineSlice,
    duplicateMessageRandomIds: Set<String>,
    systemMessageFolds: List<ThreadSystemMessageFold?>,
    expandedSystemMessageGroups: Set<String>,
    showReplyEmptyState: Boolean,
    showReplyLoadingSkeleton: Boolean,
): ThreadVirtualRowProjection {
    val rows = ArrayList<ThreadVirtualRow>(renderPayload.messages.size + 4)
    val idToRowIndex = HashMap<String, Int>(renderPayload.messages.size + 1)
    val idToRowKey = HashMap<String, String>(renderPayload.messages.size + 1)

    var originRowIndex = -1
    var historyBoundaryRowIndex = -1
    var firstMessageRowIndex = -1
    var latestMessageRowIndex = -1
    var newerBoundaryRowIndex = -1
    var eliminated = 0

    if (renderPayload.hasThreadOriginItem()) {
        originRowIndex = rows.size
        rows.add(ThreadVirtualRow.Origin)
        renderPayload.originMessage?.id?.let { originId ->
            idToRowIndex[originId] = originRowIndex
            idToRowKey[originId] = ThreadOriginListItemKey
        }
    }
    if (renderPayload.hasThreadHistoryBoundaryItem()) {
        historyBoundaryRowIndex = rows.size
        rows.add(ThreadVirtualRow.HistoryBoundary)
    }
    if (showReplyEmptyState) {
        rows.add(ThreadVirtualRow.ReplyEmpty)
    }
    if (showReplyLoadingSkeleton) {
        rows.add(ThreadVirtualRow.ReplyLoading)
    }

    val messages = renderPayload.messages
    var i = 0
    while (i < messages.size) {
        val message = messages[i]
        val fold = systemMessageFolds.getOrNull(i)
        val groupExpanded = fold != null && fold.groupId in expandedSystemMessageGroups

        if (fold is ThreadSystemMessageFold.Member && !groupExpanded) {
            // Skip folded member — its messageId was already mapped to its Summary row below.
            eliminated += 1
            i += 1
            continue
        }

        val rowIndex = rows.size
        if (firstMessageRowIndex < 0) firstMessageRowIndex = rowIndex
        latestMessageRowIndex = rowIndex

        val listKey = message.messageListItemKey(duplicateMessageRandomIds)
        if (fold is ThreadSystemMessageFold.Summary && !groupExpanded) {
            var lastCovered = i
            var foldedCount = 0
            var j = i + 1
            while (j < messages.size) {
                val nextFold = systemMessageFolds.getOrNull(j)
                if (nextFold is ThreadSystemMessageFold.Member && nextFold.groupId == fold.groupId) {
                    lastCovered = j
                    foldedCount += 1
                    idToRowIndex[messages[j].id] = rowIndex
                    idToRowKey[messages[j].id] = listKey
                    j += 1
                } else {
                    break
                }
            }
            idToRowIndex[message.id] = rowIndex
            idToRowKey[message.id] = listKey
            rows.add(
                ThreadVirtualRow.Message(
                    messageIndex = i,
                    messageId = message.id,
                    key = listKey,
                    coveredLastMessageIndex = lastCovered,
                    foldedMemberCount = foldedCount,
                )
            )
            eliminated += foldedCount
            i = j
        } else {
            idToRowIndex[message.id] = rowIndex
            idToRowKey[message.id] = listKey
            rows.add(
                ThreadVirtualRow.Message(
                    messageIndex = i,
                    messageId = message.id,
                    key = listKey,
                    coveredLastMessageIndex = i,
                    foldedMemberCount = 0,
                )
            )
            i += 1
        }
    }

    if (renderPayload.canLoadMoreAfter) {
        newerBoundaryRowIndex = rows.size
        rows.add(ThreadVirtualRow.NewerBoundary)
    }
    val bottomSpacerRowIndex = rows.size
    rows.add(ThreadVirtualRow.BottomSpacer)

    return ThreadVirtualRowProjection(
        rows = rows,
        originRowIndex = originRowIndex,
        historyBoundaryRowIndex = historyBoundaryRowIndex,
        firstMessageRowIndex = firstMessageRowIndex,
        latestMessageRowIndex = latestMessageRowIndex,
        newerBoundaryRowIndex = newerBoundaryRowIndex,
        bottomSpacerRowIndex = bottomSpacerRowIndex,
        collapsedPlaceholderRowsEliminated = eliminated,
        messageIdToRowIndex = idToRowIndex,
        messageIdToRowKey = idToRowKey,
    )
}
