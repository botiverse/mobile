package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadInPlaceSelectionBackOwner
import build.raft.app.thread.ThreadInPlaceSelectionTapDecision
import build.raft.app.thread.ThreadInPlaceSelectionTarget
import build.raft.app.thread.ThreadInPlaceSelectionToolbarKind
import build.raft.app.thread.ThreadInPlaceTextSelectionHost
import build.raft.app.thread.ThreadMessageLongPressRoute
import build.raft.app.thread.ThreadNewMessageMenuActionAvailability
import build.raft.app.thread.ThreadNewMessageMenuTool
import build.raft.app.thread.compactLabelResource
import build.raft.app.thread.threadInPlaceSelectionBackOwner
import build.raft.app.thread.threadInPlaceSelectionBarOffset
import build.raft.app.thread.threadInPlaceSelectionCopyText
import build.raft.app.thread.threadInPlaceSelectionCoversAll
import build.raft.app.thread.threadInPlaceSelectionMenuAvailability
import build.raft.app.thread.threadInPlaceSelectionTapDecision
import build.raft.app.thread.threadInPlaceSelectionToolbarKind
import build.raft.app.thread.threadInPlaceSelectionVisibleAnchor
import build.raft.app.thread.threadInPlaceSelectionWidensToWholeMessage
import build.raft.app.thread.threadMessageLongPressRoute
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
    fun onlyAFingersSelectionWidensToTheWholeMessage() {
        assertTrue(
            threadInPlaceSelectionWidensToWholeMessage(startedByTouch = true, coversAll = false),
            "a long press starts on a word; the default is the whole message"
        )
        assertFalse(threadInPlaceSelectionWidensToWholeMessage(startedByTouch = true, coversAll = true))
        // artin, 2026-10-29: a mouse drag started mid-message was widened on its first move and
        // then kept extending from the message start.
        assertFalse(
            threadInPlaceSelectionWidensToWholeMessage(startedByTouch = false, coversAll = false),
            "a mouse drag or a double-clicked word stays what was picked"
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


    // Phone-ish window: top bar 0..200, list viewport 200..1600, composer 1600..2000.
    private val viewport = Rect(0f, 200f, 1080f, 1600f)
    private val popup = IntSize(300, 120)

    private fun offset(visibleSelection: Rect) =
        threadInPlaceSelectionBarOffset(visibleSelection, viewport, popup, gapPx = 16, edgePx = 16)

    private fun assertInsideViewport(offset: IntOffset) {
        assertTrue(offset.y >= viewport.top + 16, "bar over the top bar: $offset")
        assertTrue(offset.y + popup.height <= viewport.bottom - 16, "bar over the composer: $offset")
        assertTrue(offset.x >= viewport.left + 16 && offset.x + popup.width <= viewport.right - 16)
    }

    @Test
    fun selectAllOnAMessageTallerThanTheScreenKeepsTheBarInsideTheViewport() {
        // Body spans -3000..5000; everything selected; visible part == the whole viewport.
        val anchor =
            threadInPlaceSelectionVisibleAnchor(
                selectionInWindow = Rect(16f, -3000f, 1064f, 5000f),
                visibleBodyInWindow = Rect(16f, 200f, 1064f, 1600f),
                viewportInWindow = viewport
            )!!
        assertEquals(Rect(16f, 200f, 1064f, 1600f), anchor.visibleSelection)
        val bar = offset(anchor.visibleSelection)
        assertInsideViewport(bar)
        assertEquals(216, bar.y) // pinned to the top of the list, not the window bottom
    }

    private val messageMenu = ThreadNewMessageMenuActionAvailability()
    private val partialSelectionMenu = threadInPlaceSelectionMenuAvailability(messageMenu, coversAll = false)

    @Test
    fun partialSelectionKeepsOnlySelectAllCopyAndReply() {
        assertEquals(
            listOf(ThreadNewMessageMenuTool.SelectAll, ThreadNewMessageMenuTool.Copy, ThreadNewMessageMenuTool.Reply),
            partialSelectionMenu.primaryTools()
        )
        assertFalse(partialSelectionMenu.showsReactionRow())
        assertFalse(partialSelectionMenu.reactions)
        assertEquals(
            Res.string.thread_text_selection_select_all,
            ThreadNewMessageMenuTool.SelectAll.compactLabelResource(partialSelectionMenu)
        )
        // Whole-message tools never reach a partial selection, not even on the second row.
        val busy =
            threadInPlaceSelectionMenuAvailability(
                messageMenu.copy(copyLink = true, addReaction = true, followThread = true, convertToTask = true),
                coversAll = false
            )
        assertEquals(emptyList(), busy.secondaryTools())
        // A message that cannot be replied to keeps Copy and Select All only.
        val noReply = threadInPlaceSelectionMenuAvailability(messageMenu.copy(reply = false), coversAll = false)
        assertEquals(listOf(ThreadNewMessageMenuTool.SelectAll, ThreadNewMessageMenuTool.Copy), noReply.primaryTools())
    }

    @Test
    fun copyInTheSelectionMenuCopiesTheSelectionAndFallsBackToTheWholeMessage() {
        assertEquals(
            "paragraph.",
            threadInPlaceSelectionCopyText(listOf("", "paragraph."), renderedNodes, body, emptySelectionFallback = body)
        )
        assertEquals(body, threadInPlaceSelectionCopyText(emptyList(), renderedNodes, body, emptySelectionFallback = body))
    }

    @Test
    fun aMouseSelectionGetsTheSelectionToolbarAndAFingerSelectionTheFullMenu() {
        // Mouse: Compose never asks for its toolbar; the release shows Quote | Copy.
        assertEquals(
            ThreadInPlaceSelectionToolbarKind.SelectionToolbar,
            threadInPlaceSelectionToolbarKind(
                pageActive = true,
                hasSelection = true,
                mouseSelectionEnded = true,
                platformToolbarRequested = false
            )
        )
        // Finger (long press): Compose's toolbar request keeps the full Compact menu.
        assertEquals(
            ThreadInPlaceSelectionToolbarKind.MessageMenu,
            threadInPlaceSelectionToolbarKind(
                pageActive = true,
                hasSelection = true,
                mouseSelectionEnded = false,
                platformToolbarRequested = true
            )
        )
        // Mid-drag (button still down), no selection, or an inactive page: nothing.
        assertEquals(
            ThreadInPlaceSelectionToolbarKind.None,
            threadInPlaceSelectionToolbarKind(pageActive = true, hasSelection = true, mouseSelectionEnded = false, platformToolbarRequested = false)
        )
        assertEquals(
            ThreadInPlaceSelectionToolbarKind.None,
            threadInPlaceSelectionToolbarKind(pageActive = true, hasSelection = false, mouseSelectionEnded = true, platformToolbarRequested = true)
        )
        assertEquals(
            ThreadInPlaceSelectionToolbarKind.None,
            threadInPlaceSelectionToolbarKind(pageActive = false, hasSelection = true, mouseSelectionEnded = true, platformToolbarRequested = false)
        )
    }
}
