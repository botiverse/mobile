package build.raft.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadInPlaceTextSelectionTest {
    private val body =
        """
        |First paragraph.
        |
        |Second paragraph.
        |
        |Third paragraph.
        |
        |- first item
        |- second item
        |- third item
        """.trimMargin()

    // What the Markdown renderer registers with SelectionContainer: one selectable per block.
    private val renderedNodes =
        listOf(
            "First paragraph.",
            "Second paragraph.",
            "Third paragraph.",
            "first item",
            "second item",
            "third item"
        )

    private fun message(text: String = body) =
        RaftThreadMessageItem(id = "m1", authorId = "u1", authorName = "User", body = text)

    @Test
    fun fullSelectionCopiesExactlyWhatTheSelectTextPageOffers() {
        val message = message()
        val copied =
            threadInPlaceSelectionCopyText(
                selectedTexts = renderedNodes,
                selectableTexts = renderedNodes,
                fullMessageText = threadMessageSelectionSourceText(message)
            )

        // The Select Text page renders threadMessageSelectionSourceText(message); the menu's
        // Copy action copies message.body. A whole-message in-place copy must be identical,
        // list markers included.
        assertEquals(threadMessageSelectionSourceText(message), copied)
        assertEquals(message.body, copied)
        assertTrue(copied.lines().any { it.startsWith("- ") })
    }

    @Test
    fun joinedSelectionKeepsOneLinePerParagraphAndListItem() {
        // No canonical full text (e.g. a translated body): nodes are joined, never run together.
        val copied =
            threadInPlaceSelectionCopyText(
                selectedTexts = renderedNodes,
                selectableTexts = renderedNodes,
                fullMessageText = null
            )

        val lines = copied.lines()
        assertTrue(lines.size >= 6, "expected >= 6 lines, got ${lines.size}: $copied")
        assertEquals(renderedNodes, lines)
    }

    @Test
    fun partialSelectionAcrossBlocksJoinsOnlyTheSelectedSlices() {
        val selected = listOf("", "paragraph.", "Third", "", "", "")
        val copied =
            threadInPlaceSelectionCopyText(
                selectedTexts = selected,
                selectableTexts = renderedNodes,
                fullMessageText = body
            )

        assertEquals("paragraph.\nThird", copied)
        assertEquals(2, copied.lines().size)
        assertFalse(threadInPlaceSelectionCoversAll(selected, renderedNodes))
    }

    @Test
    fun emptySelectionCopiesNothing() {
        assertEquals("", threadInPlaceSelectionCopyText(emptyList(), renderedNodes, body))
        assertEquals("", threadInPlaceSelectionCopyText(listOf("", ""), renderedNodes, body))
        assertFalse(threadInPlaceSelectionCoversAll(emptyList(), emptyList()))
    }

    @Test
    fun barOffersCopySelectAllAndMore() {
        assertEquals(
            listOf(
                ThreadInPlaceSelectionBarAction.Copy,
                ThreadInPlaceSelectionBarAction.SelectAll,
                ThreadInPlaceSelectionBarAction.More
            ),
            ThreadInPlaceSelectionBarActions
        )
    }

    @Test
    fun barSitsAboveTheSelectionWhenThereIsRoomAndBelowOtherwise() {
        val window = IntSize(1080, 2000)
        val popup = IntSize(300, 120)

        val above = threadInPlaceSelectionBarOffset(Rect(400f, 800f, 700f, 900f), window, popup, gapPx = 16, edgePx = 16)
        assertEquals(IntOffset(400, 800 - 16 - 120), above)

        val below = threadInPlaceSelectionBarOffset(Rect(400f, 60f, 700f, 160f), window, popup, gapPx = 16, edgePx = 16)
        assertEquals(IntOffset(400, 160 + 16), below)

        val clamped = threadInPlaceSelectionBarOffset(Rect(0f, 800f, 40f, 900f), window, popup, gapPx = 16, edgePx = 16)
        assertEquals(16, clamped.x)
    }

    @Test
    fun selectionHostIsOnlyEnabledByTheNewMessageMenuToggle() {
        assertFalse(ThreadInPlaceTextSelectionHost(enabled = false, copyText = {}).enabled)
        assertTrue(ThreadInPlaceTextSelectionHost(enabled = true, copyText = {}).enabled)
        // No active selection: a row press is a no-op.
        ThreadInPlaceTextSelectionHost(enabled = true, copyText = {}).onRowPointerDown("m1", insideBody = false)
    }

    private class FakeSelection(
        override val messageId: String,
        override var hasSelection: Boolean,
    ) : ThreadInPlaceSelectionTarget {
        var cleared = 0

        override fun clear() {
            cleared++
            hasSelection = false
        }
    }

    @Test
    fun toggleOffLongPressAlwaysRoutesToTheMessageMenu() {
        assertEquals(
            ThreadMessageLongPressRoute.MessageMenu,
            threadMessageLongPressRoute(inPlaceSelectionEnabled = false, pressOnSelectableBody = true)
        )
        assertEquals(
            ThreadMessageLongPressRoute.MessageMenu,
            threadMessageLongPressRoute(inPlaceSelectionEnabled = false, pressOnSelectableBody = false)
        )
        // Toggle on: only a press on the body goes to the selection; the rest of the row keeps the menu.
        assertEquals(
            ThreadMessageLongPressRoute.InPlaceSelection,
            threadMessageLongPressRoute(inPlaceSelectionEnabled = true, pressOnSelectableBody = true)
        )
        assertEquals(
            ThreadMessageLongPressRoute.MessageMenu,
            threadMessageLongPressRoute(inPlaceSelectionEnabled = true, pressOnSelectableBody = false)
        )
    }

    @Test
    fun aTapThatDismissesTheSelectionDoesNotAlsoOpenTheThread() {
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        val selection = FakeSelection(messageId = "m1", hasSelection = true)
        host.activate(selection)

        // DOWN on the same row outside the body: the selection is cleared at DOWN…
        val showingAtDown = host.onRowPointerDown("m1", insideBody = false)
        assertTrue(showingAtDown)
        assertEquals(1, selection.cleared)
        assertFalse(selection.hasSelection)
        // …so onTap must decide from the DOWN snapshot, not from the (now empty) selection.
        assertEquals(
            ThreadInPlaceSelectionTapDecision.DismissSelectionOnly,
            threadInPlaceSelectionTapDecision(showingAtDown)
        )

        // The next gesture starts with nothing selected and runs the row action again.
        val nextDown = host.onRowPointerDown("m1", insideBody = false)
        assertFalse(nextDown)
        assertEquals(ThreadInPlaceSelectionTapDecision.RunRowAction, threadInPlaceSelectionTapDecision(nextDown))
    }

    @Test
    fun aPressOnAnotherRowDismissesWithoutRunningThatRowsAction() {
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        val selection = FakeSelection(messageId = "m1", hasSelection = true)
        host.activate(selection)

        assertTrue(host.onRowPointerDown("m2", insideBody = true))
        assertEquals(1, selection.cleared)
    }

    @Test
    fun aPressInsideTheSelectedBodyIsLeftToTheSelectionButStillSuppressesTheTap() {
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        val selection = FakeSelection(messageId = "m1", hasSelection = true)
        host.activate(selection)

        assertTrue(host.onRowPointerDown("m1", insideBody = true))
        assertEquals(0, selection.cleared)
    }

    @Test
    fun startingASelectionElsewhereClearsThePreviousOne() {
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        val first = FakeSelection(messageId = "m1", hasSelection = true)
        val second = FakeSelection(messageId = "m2", hasSelection = true)
        host.activate(first)
        host.activate(second)

        assertEquals(1, first.cleared)
        assertEquals(0, second.cleared)
    }

    @Test
    fun backClearsTheSelectionOnlyWhenNoMenuIsOpenAboveIt() {
        assertEquals(
            ThreadInPlaceSelectionBackOwner.MessageMenu,
            threadInPlaceSelectionBackOwner(hasSelection = true, messageMenuOpen = true)
        )
        assertEquals(
            ThreadInPlaceSelectionBackOwner.Selection,
            threadInPlaceSelectionBackOwner(hasSelection = true, messageMenuOpen = false)
        )
        assertEquals(
            ThreadInPlaceSelectionBackOwner.None,
            threadInPlaceSelectionBackOwner(hasSelection = false, messageMenuOpen = false)
        )
    }

    @Test
    fun barHidesWhileTheSelectionIsScrolledOutOfTheVisibleBody() {
        val selection = Rect(100f, 500f, 400f, 560f)
        // Fully visible body: anchor follows the live selection rect.
        assertEquals(selection, threadInPlaceSelectionVisibleAnchor(selection, Rect(0f, 300f, 1080f, 900f)))
        // Body clipped so the selected lines are under the composer / above the top bar: hide.
        assertEquals(null, threadInPlaceSelectionVisibleAnchor(selection, Rect(0f, 600f, 1080f, 900f)))
        // Body scrolled fully out of the list viewport (clipped bounds are empty): hide.
        assertEquals(null, threadInPlaceSelectionVisibleAnchor(selection, Rect(0f, 900f, 1080f, 900f)))
        assertEquals(null, threadInPlaceSelectionVisibleAnchor(null, Rect(0f, 300f, 1080f, 900f)))
    }
}
