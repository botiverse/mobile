package build.raft.app

import androidx.compose.ui.input.key.Key
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The composer's @ / # list on a hardware keyboard follows the web's `useAutocomplete`
 * (botiverse/slock packages/web/src/hooks/useAutocomplete.ts): ↑ / ↓ move a highlight that wraps
 * at both ends and starts over on the first row when the query changes, Enter and Tab pick,
 * Shift+Enter stays the editor's newline, Esc closes; while the list has no rows or an input
 * method is composing, every key is the editor's.
 */
class ThreadComposerSuggestionKeysTest {
    private fun action(
        key: Key,
        shift: Boolean = false,
        panelState: ThreadComposerSuggestionPanelState = ThreadComposerSuggestionPanelState.MemberItems,
        rowCount: Int = 3,
        composing: Boolean = false,
    ): ThreadComposerSuggestionKeyAction =
        threadComposerSuggestionKeyAction(key, shift, panelState, rowCount, composing)

    @Test
    fun arrowsMoveEnterAndTabPickEscCloses() {
        assertEquals(ThreadComposerSuggestionKeyAction.MoveDown, action(Key.DirectionDown))
        assertEquals(ThreadComposerSuggestionKeyAction.MoveUp, action(Key.DirectionUp))
        assertEquals(ThreadComposerSuggestionKeyAction.Pick, action(Key.Enter))
        assertEquals(
            ThreadComposerSuggestionKeyAction.Pick,
            action(Key.Tab, panelState = ThreadComposerSuggestionPanelState.ChannelItems),
        )
        assertEquals(ThreadComposerSuggestionKeyAction.None, action(Key.Enter, shift = true), "Shift+Enter is a newline")
        assertEquals(ThreadComposerSuggestionKeyAction.Dismiss, action(Key.Escape))
        assertEquals(ThreadComposerSuggestionKeyAction.None, action(Key.A), "typing goes on into the query")
    }

    @Test
    fun withoutRowsOrWhileComposingTheKeysStayTheEditors() {
        // A status row (loading, no match, an error): the caret, the newline, the send and Tab
        // are the editor's; Esc still closes what is on screen.
        listOf(Key.DirectionDown, Key.DirectionUp, Key.Enter, Key.Tab).forEach { key ->
            assertEquals(
                ThreadComposerSuggestionKeyAction.None,
                action(key, panelState = ThreadComposerSuggestionPanelState.MemberEmpty, rowCount = 0),
                "$key with no rows",
            )
        }
        assertEquals(
            ThreadComposerSuggestionKeyAction.Dismiss,
            action(Key.Escape, panelState = ThreadComposerSuggestionPanelState.MemberEmpty, rowCount = 0),
        )
        // No list: nothing is taken, Esc included (it goes back / closes what is behind).
        assertEquals(
            ThreadComposerSuggestionKeyAction.None,
            action(Key.Escape, panelState = ThreadComposerSuggestionPanelState.Hidden, rowCount = 0),
        )
        // An input method composing (web isImeComposing): Enter, Esc and the arrows are its own.
        assertEquals(ThreadComposerSuggestionKeyAction.None, action(Key.Enter, composing = true))
        assertEquals(ThreadComposerSuggestionKeyAction.None, action(Key.Escape, composing = true))
    }

    @Test
    fun theHighlightWrapsAtBothEndsAndGoesBackToTheFirstRowWhenTheQueryChanges() {
        val ad = RaftComposerSuggestionTrigger.MemberTrigger(query = "ad", triggerStart = 0)
        val opened = ThreadComposerSuggestionHighlight()
        assertEquals(0, opened.indexFor(ad), "the list opens on its first row")

        val last = opened.moved(ad, rowCount = 3, move = ThreadComposerSuggestionSelectionMove.Up)
        assertEquals(2, last.indexFor(ad), "↑ on the first row goes to the last")
        assertEquals(
            0,
            last.moved(ad, rowCount = 3, move = ThreadComposerSuggestionSelectionMove.Down).indexFor(ad),
            "↓ on the last row goes to the first",
        )
        assertEquals(
            0,
            last.indexFor(RaftComposerSuggestionTrigger.MemberTrigger(query = "ada", triggerStart = 0)),
            "another letter typed: back to the first row",
        )
        // The same query with fewer rows: the highlight reads as the last row there is.
        assertEquals(1, threadComposerSuggestionSelectedIndex(last.indexFor(ad), itemCount = 2))
        assertEquals(
            0,
            opened.moved(ad, rowCount = 0, move = ThreadComposerSuggestionSelectionMove.Down).indexFor(ad),
            "no rows: nothing to move to",
        )
    }

    @Test
    fun theHighlightedRowScrollsIntoViewOnlyWhenItIsOutOfView() {
        // A 240px list of 36px rows (web scrollIntoView with block "nearest").
        assertEquals(0, threadComposerSuggestionRevealDelta(itemOffset = 100, itemSize = 36, viewportStart = 0, viewportEnd = 240))
        assertEquals(16, threadComposerSuggestionRevealDelta(itemOffset = 220, itemSize = 36, viewportStart = 0, viewportEnd = 240))
        assertEquals(-20, threadComposerSuggestionRevealDelta(itemOffset = -20, itemSize = 36, viewportStart = 0, viewportEnd = 240))
    }
}
