package build.raft.app

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.launch

/**
 * The reading anchor (artin #proj-mobile #201; docs/programmatic-scroll-ownership-contract.md,
 * "Thread reading anchor").
 *
 * The thread list is a `LazyColumn(reverseLayout = true)`: Compose keeps the *bottom* visible row
 * where it is, so a visible row that changes height in place (the Saved marker, a first reaction,
 * an edit, a link card or image resolving, expand/collapse) moves every row above it. That is right
 * while the list follows the latest message (the bottom stays pinned) and wrong for a reader
 * scrolled into the history: the row they are reading moved for a frame, and the viewport
 * controller's HeightChange replay moved it back a frame later.
 *
 * This keeper is the one owner of that case. After every layout pass of the list (from the rows'
 * and the list's position callbacks, before the frame is drawn) it compares the anchor row with the
 * previous pass; when only content changed, it puts the anchor back with a synchronous
 * `scrollToItem` (a forced remeasure, so the corrected layout is the one drawn).
 *
 * - **Anchor**: the viewport controller's anchored message when it is laid out (the row the user
 *   settled on, a focused message, or the row whose control the user just tapped:
 *   `keepReadingAnchorFor` in ThreadMessagesList re-anchors the controller on it), otherwise the
 *   topmost row whose top is in view.
 * - **Owner fence**: only while not following the latest row, with no scroll in progress, on a live
 *   list ([ThreadReadingAnchorOwnership]). Otherwise the anchor is just re-read.
 * - **User-override / other owners**: a correction is made only when the list's own scroll
 *   position (bottom row key + offset), viewport size and paddings are unchanged since the last
 *   pass, i.e. nothing scrolled. Any scroll — a drag, a fling, a jump, a restore, a keyboard
 *   resize — re-reads the anchor instead, so the keeper never undoes another owner's move.
 */
internal class ThreadReadingAnchorKeeper {
    private data class Frame(
        val rawFirstKey: Any?,
        val rawFirstOffset: Int,
        val viewportSize: Int,
        val beforePadding: Int,
        val afterPadding: Int,
    )

    private var frame: Frame? = null
    private var rows: List<ThreadReadingAnchorRow> = emptyList()
    private var anchorKey: Any? = null
    private var anchorTop: Int = 0

    /**
     * The row showing [messageId] is the anchor from now on, at its top in the last layout pass
     * (a tapped row whose content is about to change in place). False when it is not laid out.
     */
    fun anchorOn(messageId: String): Boolean {
        val row = rows.firstOrNull { it.messageId == messageId } ?: return false
        anchorKey = row.key
        anchorTop = row.top
        return true
    }

    /** The anchor row's key (tests and diagnostics). */
    val currentAnchorKey: Any?
        get() = anchorKey

    /** Corrections made so far (tests and diagnostics). */
    var corrections: Int = 0
        private set

    /**
     * Called after a layout pass. Returns the raw position that puts the anchor back, or null.
     * [preferredMessageId] is the viewport controller's anchored message, when it has one.
     */
    fun afterLayout(
        layout: ThreadReadingAnchorLayout,
        owns: Boolean,
        preferredMessageId: String?,
    ): ThreadRowTopKeepRequest? {
        val next =
            Frame(
                rawFirstKey = layout.rawFirstKey,
                rawFirstOffset = layout.rawFirstOffset,
                viewportSize = layout.viewportSize,
                beforePadding = layout.beforePadding,
                afterPadding = layout.afterPadding,
            )
        val unscrolled = frame == next
        frame = next
        rows = layout.rows
        val anchor = anchorKey?.let { key -> layout.rows.firstOrNull { it.key == key } }
        if (owns && unscrolled && anchor != null && anchor.top != anchorTop) {
            corrections += 1
            // The anchor's raw offset (from the bottom) that puts its top back at anchorTop.
            val rawOffset = anchor.rawOffset + (anchor.top - anchorTop)
            return ThreadRowTopKeepRequest(rawIndex = anchor.rawIndex, scrollOffset = -rawOffset)
        }
        val chosen =
            preferredMessageId?.let { id -> layout.rows.firstOrNull { it.messageId == id } }
                ?: layout.rows.firstOrNull { it.top >= 0 }
                ?: layout.rows.firstOrNull()
        anchorKey = chosen?.key
        anchorTop = chosen?.top ?: 0
        return null
    }
}

/** One laid-out row, in forward terms ([top] from the viewport top) plus its raw position. */
internal data class ThreadReadingAnchorRow(
    val key: Any,
    val messageId: String?,
    val rawIndex: Int,
    val rawOffset: Int,
    val top: Int,
)

/** What [ThreadReadingAnchorKeeper] reads from one layout pass. */
internal data class ThreadReadingAnchorLayout(
    val rawFirstKey: Any?,
    val rawFirstOffset: Int,
    val viewportSize: Int,
    val beforePadding: Int,
    val afterPadding: Int,
    /** Visible rows, top to bottom. */
    val rows: List<ThreadReadingAnchorRow>,
)

/** A raw `scrollToItem` position. */
internal data class ThreadRowTopKeepRequest(val rawIndex: Int, val scrollOffset: Int)

/** The keeper's owner fence (contract: owner + user-override fence). */
internal fun threadReadingAnchorOwns(
    reverseLayout: Boolean,
    followingLatest: Boolean,
    scrollInProgress: Boolean,
    ownerLive: Boolean,
    placementSettled: Boolean,
): Boolean = reverseLayout && !followingLatest && !scrollInProgress && ownerLive && placementSettled

/**
 * The reading-anchor view of the reversed [raw] list's last layout. [messageIdAt] names the message
 * of a forward list index (null for structural rows).
 */
internal fun LazyListState.threadReadingAnchorLayout(messageIdAt: (Int) -> String?): ThreadReadingAnchorLayout {
    val info = layoutInfo
    val total = info.totalItemsCount
    val layoutSize = info.viewportEndOffset - info.viewportStartOffset
    val firstIndex = firstVisibleItemIndex
    return ThreadReadingAnchorLayout(
        rawFirstKey = info.visibleItemsInfo.firstOrNull { it.index == firstIndex }?.key,
        rawFirstOffset = firstVisibleItemScrollOffset,
        viewportSize = layoutSize,
        beforePadding = info.beforeContentPadding,
        afterPadding = info.afterContentPadding,
        rows =
            info.visibleItemsInfo
                .map { item ->
                    val forwardIndex = reversedThreadListIndex(item.index, total)
                    ThreadReadingAnchorRow(
                        key = item.key,
                        messageId = messageIdAt(forwardIndex),
                        rawIndex = item.index,
                        rawOffset = item.offset,
                        top =
                            reversedThreadListItemOffset(
                                rawOffset = item.offset,
                                size = item.size,
                                layoutSize = layoutSize,
                                rawBeforeContentPadding = info.beforeContentPadding,
                                rawAfterContentPadding = info.afterContentPadding,
                            ),
                    )
                }
                .sortedBy { it.top },
    )
}

/**
 * The reading anchor a settled drag hands the viewport controller: the topmost message whose top is
 * in view (the row being read), else the first visible message (one tall message filling the view).
 * Offsets are forward, from the content start after the top padding.
 */
internal fun threadReadingAnchorItem(
    items: List<androidx.compose.foundation.lazy.LazyListItemInfo>,
    isMessage: (Int) -> Boolean,
): androidx.compose.foundation.lazy.LazyListItemInfo? {
    val messages = items.filter { isMessage(it.index) }.sortedBy { it.index }
    return messages.firstOrNull { it.offset >= 0 } ?: messages.firstOrNull()
}

/** Row actions that change the tapped row in place: that row becomes the reading anchor. */
internal val ThreadReadingAnchorRowActions: Set<String> = setOf("toggleMessageSaved", "toggleReaction")

/** The message shown by forward list item [index] (the origin row or a message row), else null. */
internal fun ThreadTimelineSlice.threadMessageIdAtListItemIndex(index: Int): String? {
    if (index == 0 && hasThreadOriginItem()) return originMessage?.id
    return messages.getOrNull(index - firstMessageListItemIndex())?.id
}

/**
 * Production wiring of [ThreadReadingAnchorKeeper] on one reversed list: call [afterLayout] from the
 * position callbacks of the list and its rows (once per layout pass is processed); a correction is
 * a [ThreadListViewport.scrollRawToItem] started undispatched, which forces the remeasure before the
 * frame is drawn.
 */
internal class ThreadReadingAnchorOwner(
    private val listState: ThreadListViewport,
    private val scope: kotlinx.coroutines.CoroutineScope,
    val keeper: ThreadReadingAnchorKeeper = ThreadReadingAnchorKeeper(),
) {
    private var processedLayout: Any? = null

    fun afterLayout(owns: () -> Boolean, preferredMessageId: () -> String?, messageIdAt: (Int) -> String?) {
        if (!listState.reverse || listState.isVirtual) return
        val info = listState.raw.layoutInfo
        if (info === processedLayout) return
        processedLayout = info
        val request =
            keeper.afterLayout(
                layout = listState.raw.threadReadingAnchorLayout(messageIdAt),
                owns = owns(),
                preferredMessageId = preferredMessageId(),
            ) ?: return
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            listState.scrollRawToItem(request.rawIndex, request.scrollOffset)
        }
    }
}

/**
 * Calls [onUserScroll] when the user starts dragging [state] (its `DragInteraction.Start`); a
 * programmatic scroll — a reading-anchor correction, a jump, a restore — does not count. For
 * transient UI that should close on the user's scroll only.
 */
@androidx.compose.runtime.Composable
internal fun ThreadUserScrollEffect(state: LazyListState, onUserScroll: () -> Unit) {
    ThreadUserScrollEffect(state.interactionSource, onUserScroll)
}

@androidx.compose.runtime.Composable
internal fun ThreadUserScrollEffect(viewport: ThreadListViewport, onUserScroll: () -> Unit) {
    ThreadUserScrollEffect(viewport.interactionSource, onUserScroll)
}

@androidx.compose.runtime.Composable
private fun ThreadUserScrollEffect(
    interactionSource: androidx.compose.foundation.interaction.InteractionSource,
    onUserScroll: () -> Unit,
) {
    val current = androidx.compose.runtime.rememberUpdatedState(onUserScroll)
    androidx.compose.runtime.LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) current.value()
        }
    }
}
