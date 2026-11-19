package build.raft.app

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

/**
 * artin (#proj-mobile #201): saving or unsaving a message made the thread jump for two frames.
 * The Saved indicator sits in the row's footer, so the row grows or shrinks at its bottom. The
 * thread list is a `LazyColumn(reverseLayout = true)`, which keeps the row *below* still, so
 * everything above the saved row moved by the indicator's height; the viewport controller's
 * HeightChange replay then put it back a frame later.
 *
 * Direct user action (docs/programmatic-scroll-ownership-contract.md, like the long-message
 * toggle): the rows above the saved message keep their place and the change grows/shrinks
 * downward, as on the web timeline. The raw position is requested from the parent composition of
 * the frame whose render payload first carries the new saved state, so the LazyColumn measure
 * that lays out the new footer already applies it: no frame shows the jump.
 *
 * Fences, in [ThreadSavedToggleTopKeeper.take] and [threadKeepRowTopForSavedToggle]: one armed
 * toggle at a time, applied once, only when the payload shows the state the user asked for; a
 * user scroll since the tap retires it; an active scroll, a row that is not laid out, and a list
 * following the latest row (the bottom stays put there) leave the list alone.
 */
internal class ThreadSavedToggleTopKeeper {
    private data class Armed(val messageId: String, val saved: Boolean, val userScrollGeneration: Int)

    private var armed: Armed? = null

    /** The user asked to save ([saved] true) or unsave [messageId]; [userScrollGeneration] is the list's now. */
    fun arm(messageId: String, saved: Boolean, userScrollGeneration: Int) {
        armed = Armed(messageId, saved, userScrollGeneration)
    }

    /**
     * The armed message id when the composed payload ([savedStateOf]) first shows the requested
     * state; it is then disarmed. A user scroll since [arm] (a newer [userScrollGeneration]) or a
     * message no longer in the payload disarms it without an id.
     */
    fun take(savedStateOf: (String) -> Boolean?, userScrollGeneration: Int): String? {
        val current = armed ?: return null
        if (current.userScrollGeneration != userScrollGeneration) {
            armed = null
            return null
        }
        val composedSaved = savedStateOf(current.messageId)
        if (composedSaved == null) {
            armed = null
            return null
        }
        if (composedSaved != current.saved) return null
        armed = null
        return current.messageId
    }
}

/** A raw `requestScrollToItem` position. */
internal data class ThreadRowTopKeepRequest(val rawIndex: Int, val scrollOffset: Int)

/**
 * The raw position of a reversed list that keeps everything above raw row [rawIndex] where it is
 * while that row changes height: the row above it (raw [rawIndex] + 1) keeps its distance from the
 * bottom edge, which is this row's offset plus its size (plus item spacing). Null when the row is
 * not laid out or nothing lies above it (the list's first row: nothing above can move).
 */
internal fun threadReversedRowTopKeepRequest(rawInfo: LazyListLayoutInfo, rawIndex: Int): ThreadRowTopKeepRequest? {
    val above = rawIndex + 1
    if (rawIndex < 0 || above >= rawInfo.totalItemsCount) return null
    val item = rawInfo.visibleItemsInfo.firstOrNull { it.index == rawIndex } ?: return null
    val aboveOffset = item.offset + item.size + rawInfo.mainAxisItemSpacing
    return ThreadRowTopKeepRequest(rawIndex = above, scrollOffset = -aboveOffset)
}

/**
 * Requests the position that keeps the rows above forward row [forwardIndex] in place for the next
 * measure, when [reverse] (a forward list already keeps its first visible row) and no scroll is in
 * progress. Returns whether a position was requested.
 */
internal fun threadKeepRowTopForSavedToggle(raw: LazyListState, reverse: Boolean, forwardIndex: Int): Boolean {
    if (!reverse || forwardIndex < 0 || raw.isScrollInProgress) return false
    val info = raw.layoutInfo
    val rawIndex = reversedThreadListIndex(forwardIndex, info.totalItemsCount)
    val request = threadReversedRowTopKeepRequest(info, rawIndex) ?: return false
    raw.requestScrollToItem(request.rawIndex, request.scrollOffset)
    return true
}

/**
 * Applies an armed save/unsave toggle of [keeper] from the parent composition's apply phase (before
 * the list's measure in the same frame): when [savedStateOf] first shows the requested state, and
 * the list is not [followingLatest], the rows above the message keep their place
 * ([threadKeepRowTopForSavedToggle]); [onKept] gets the message id and the row's forward top the
 * user saw, for the viewport controller's anchor.
 */
@Composable
internal fun ThreadSavedToggleTopKeepEffect(
    keeper: ThreadSavedToggleTopKeeper,
    listState: ThreadListViewport,
    savedStateOf: (String) -> Boolean?,
    listItemIndexOf: (String) -> Int,
    userScrollGeneration: Int,
    followingLatest: () -> Boolean,
    onKept: (messageId: String, forwardTop: Int) -> Unit,
) {
    SideEffect {
        val messageId = keeper.take(savedStateOf, userScrollGeneration) ?: return@SideEffect
        if (followingLatest()) return@SideEffect
        val forwardIndex = listItemIndexOf(messageId)
        // Read before the request: the layout the user saw.
        val forwardTop = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == forwardIndex }?.offset
        if (forwardTop == null || !threadKeepRowTopForSavedToggle(listState.raw, listState.reverse, forwardIndex)) {
            return@SideEffect
        }
        onKept(messageId, forwardTop)
    }
}
