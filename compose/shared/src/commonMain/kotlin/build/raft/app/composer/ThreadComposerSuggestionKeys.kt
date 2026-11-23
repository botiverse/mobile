package build.raft.app

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.first

// The composer's @ / # suggestion list on a hardware keyboard and a mouse, as on the web
// (botiverse/slock packages/web/src/hooks/useAutocomplete.ts, used by MessageInput.tsx for both
// lists): ↑ / ↓ move a highlight, Enter or Tab inserts the highlighted row, Esc closes the list,
// and a click on a row inserts it without taking the focus from the editor.

/** What a key-down does to the composer's open @ / # list ([threadComposerSuggestionKeyAction]). */
internal enum class ThreadComposerSuggestionKeyAction {
    /** Not the list's: the editor gets the key (caret, newline, send, focus traversal). */
    None,
    MoveUp,
    MoveDown,

    /** Insert the highlighted row, as a click on it does. */
    Pick,

    /** Close the list; the text and the caret stay. */
    Dismiss,
}

/**
 * The web's keys for the list (useAutocomplete.ts `handleKeyDown`): while the list shows rows, ↓
 * and ↑ move the highlight, Tab or Enter (not Shift+Enter, the newline) inserts the highlighted
 * row, Esc closes the list. With no rows, or while an input method is composing (web
 * `isImeComposing`: Enter and the arrows choose the input method's candidates), every key is the
 * editor's. [rowCount] is the rows [panelState] draws: 0 for a status row (loading, no match, an
 * error), which the web never shows; Esc closes that one too, since it is on screen.
 */
internal fun threadComposerSuggestionKeyAction(
    key: Key,
    shift: Boolean,
    panelState: ThreadComposerSuggestionPanelState,
    rowCount: Int,
    composing: Boolean,
): ThreadComposerSuggestionKeyAction {
    if (composing || panelState == ThreadComposerSuggestionPanelState.Hidden) {
        return ThreadComposerSuggestionKeyAction.None
    }
    if (key == Key.Escape) return ThreadComposerSuggestionKeyAction.Dismiss
    val showsRows =
        rowCount > 0 &&
            (
                panelState == ThreadComposerSuggestionPanelState.MemberItems ||
                    panelState == ThreadComposerSuggestionPanelState.ChannelItems
            )
    if (!showsRows) return ThreadComposerSuggestionKeyAction.None
    return when (key) {
        Key.DirectionDown -> ThreadComposerSuggestionKeyAction.MoveDown
        Key.DirectionUp -> ThreadComposerSuggestionKeyAction.MoveUp
        Key.Tab -> ThreadComposerSuggestionKeyAction.Pick
        Key.Enter, Key.NumPadEnter ->
            if (shift) ThreadComposerSuggestionKeyAction.None else ThreadComposerSuggestionKeyAction.Pick
        else -> ThreadComposerSuggestionKeyAction.None
    }
}

/**
 * The open list's keyboard highlight (web useAutocomplete `index`): the row it is on, for the
 * query ([trigger]) it was moved under. It starts on the first row and goes back there whenever
 * the query changes (`detect` sets 0 on every edit); a list that changes under the same query
 * keeps the row number, read clamped to the rows there are (`clampIndex`).
 */
internal data class ThreadComposerSuggestionHighlight(
    val trigger: RaftComposerSuggestionTrigger = RaftComposerSuggestionTrigger.None,
    val index: Int = 0,
) {
    /** The highlighted row of the list open under [current]; callers clamp it to the rows drawn. */
    fun indexFor(current: RaftComposerSuggestionTrigger): Int = if (current == trigger) index else 0

    /** One row up or down in the [rowCount] rows open under [current], wrapping at both ends. */
    fun moved(
        current: RaftComposerSuggestionTrigger,
        rowCount: Int,
        move: ThreadComposerSuggestionSelectionMove,
    ): ThreadComposerSuggestionHighlight =
        ThreadComposerSuggestionHighlight(
            trigger = current,
            index = threadComposerSuggestionSelectedIndexAfterMove(indexFor(current), rowCount, move),
        )
}

/**
 * The keys whose key-down the list took ([threadComposerSuggestionKeys]): their key-up is the
 * list's too, so the hardware-Enter send ([RaftHardwareEnterRouter]) and the editor never see half
 * a press. Every key-down decides afresh, so a release lost to a focus change never swallows a
 * later press.
 */
internal class ThreadComposerSuggestionKeyPresses {
    private val taken = mutableSetOf<Key>()

    fun down(key: Key, took: Boolean) {
        if (took) taken += key else taken -= key
    }

    /** Whether the press [key] ends was the list's. */
    fun up(key: Key): Boolean = taken.remove(key)
}

/**
 * The editor's keys for its open list: [onKeyDown] handles a key-down and says whether the list
 * took it. Chain it on the editor before the app's editor behaviour ([raftAppEditorBehavior] goes
 * after the caller's modifier), so the list hears ↑ ↓ Enter Tab Esc ahead of the caret, the
 * hardware-Enter send and Tab focus traversal, and only takes them while it is on screen.
 */
internal fun Modifier.threadComposerSuggestionKeys(
    presses: ThreadComposerSuggestionKeyPresses,
    onKeyDown: (key: Key, shift: Boolean) -> Boolean,
): Modifier =
    onPreviewKeyEvent { event ->
        when (event.type) {
            KeyEventType.KeyDown -> {
                val took = onKeyDown(event.key, event.isShiftPressed)
                presses.down(event.key, took)
                took
            }
            KeyEventType.KeyUp -> presses.up(event.key)
            else -> false
        }
    }

/**
 * Web `preventOptionMouseDown` (MentionSuggestionList.tsx, ChannelAutocompleteList.tsx): pressing
 * on the list keeps the focus in the editor, so a pick inserts at its caret. Compose Desktop's
 * `clickable` takes the focus on press (Compose Multiplatform `isRequestFocusOnClickEnabled()` is
 * true on desktop, false on iOS; Android's never does): the editor's blur then closed the list
 * under the pointer before the release, so a mouse click inserted nothing. Chain it right before
 * each `clickable` of the list: focus properties reach only the next focus target.
 */
internal fun Modifier.threadComposerSuggestionKeepsEditorFocus(): Modifier = focusProperties { canFocus = false }

/**
 * Web `scrollIntoView({ block: "nearest" })` for the highlighted row (useAutocomplete.ts): how far
 * to scroll so a row at [itemOffset] of [itemSize] lies inside [viewportStart]..[viewportEnd]: 0
 * when it already does, positive for a row past the end, negative for a row before the start.
 */
internal fun threadComposerSuggestionRevealDelta(
    itemOffset: Int,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int,
): Int =
    when {
        itemOffset < viewportStart -> itemOffset - viewportStart
        itemOffset + itemSize > viewportEnd -> minOf(itemOffset + itemSize - viewportEnd, itemOffset - viewportStart)
        else -> 0
    }

/**
 * Keeps the highlighted row ([listIndex], a lazy index of [listState]) in view, as the web's list
 * does when the highlight moves. Viewport intent owner: the key press that moved the highlight (or
 * the edit that put it back on the first row). Fence: it runs once per highlighted row, never for a
 * list or data change alone, so a wheel or drag scroll of the list stays until the next key; one
 * instant scroll, only when the row is not wholly in view.
 */
@Composable
internal fun ThreadComposerSuggestionRevealEffect(listState: LazyListState, listIndex: Int?) {
    LaunchedEffect(listState, listIndex) {
        if (listIndex == null) return@LaunchedEffect
        // A list that has just opened is laid out after this starts.
        val layout = snapshotFlow { listState.layoutInfo }.first { it.totalItemsCount > 0 }
        if (listIndex >= layout.totalItemsCount) return@LaunchedEffect
        val row = layout.visibleItemsInfo.firstOrNull { it.index == listIndex }
        if (row == null) {
            listState.scrollToItem(listIndex)
        } else {
            val delta =
                threadComposerSuggestionRevealDelta(
                    itemOffset = row.offset,
                    itemSize = row.size,
                    viewportStart = layout.viewportStartOffset,
                    viewportEnd = layout.viewportEndOffset,
                )
            if (delta != 0) listState.scrollBy(delta.toFloat())
        }
    }
}
