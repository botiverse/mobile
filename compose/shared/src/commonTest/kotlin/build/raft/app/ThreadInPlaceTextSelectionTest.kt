package build.raft.app

import androidx.compose.ui.geometry.Rect
import build.raft.app.resources.MR
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

    @Test
    fun selectingTheFirstVisibleLineDoesNotPutTheBarOverTheTopBar() {
        val anchor =
            threadInPlaceSelectionVisibleAnchor(
                selectionInWindow = Rect(100f, 210f, 600f, 260f),
                visibleBodyInWindow = Rect(16f, 200f, 1064f, 900f),
                viewportInWindow = viewport
            )!!
        val bar = offset(anchor.visibleSelection)
        assertInsideViewport(bar)
        assertEquals(260 + 16, bar.y) // no room above inside the list -> below the line
    }

    @Test
    fun selectingTheLastVisibleLineKeepsTheBarOffTheComposer() {
        val bar = offset(Rect(100f, 1540f, 600f, 1590f))
        assertInsideViewport(bar)
        assertEquals(1540 - 16 - 120, bar.y)
    }

    @Test
    fun partiallyScrolledSelectionAnchorsToItsVisiblePart() {
        // Selection 1400..1900 but the list ends at 1600 (composer below): only 1400..1600 counts.
        val anchor =
            threadInPlaceSelectionVisibleAnchor(
                selectionInWindow = Rect(100f, 1400f, 600f, 1900f),
                visibleBodyInWindow = Rect(16f, 1000f, 1064f, 1600f),
                viewportInWindow = viewport
            )!!
        assertEquals(Rect(100f, 1400f, 600f, 1600f), anchor.visibleSelection)
        val bar = offset(anchor.visibleSelection)
        assertInsideViewport(bar)
        assertEquals(1400 - 16 - 120, bar.y)
    }

    @Test
    fun barHidesOnlyWhenNoPartOfTheSelectionIsVisible() {
        val selection = Rect(100f, 500f, 400f, 560f)
        // Selected lines scrolled under the composer (body clipped above them): hide.
        assertEquals(null, threadInPlaceSelectionVisibleAnchor(selection, Rect(0f, 600f, 1080f, 1600f), viewport))
        // Body scrolled fully out of the list (clipped bounds empty): hide.
        assertEquals(null, threadInPlaceSelectionVisibleAnchor(selection, Rect(0f, 1600f, 1080f, 1600f), viewport))
        // Selection above the list viewport (under the top bar): hide.
        assertEquals(
            null,
            threadInPlaceSelectionVisibleAnchor(Rect(100f, 100f, 400f, 180f), Rect(0f, 0f, 1080f, 900f), viewport)
        )
        assertEquals(null, threadInPlaceSelectionVisibleAnchor(null, Rect(0f, 300f, 1080f, 900f), viewport))
        // Fully visible: anchor is the selection itself.
        assertEquals(selection, threadInPlaceSelectionVisibleAnchor(selection, Rect(0f, 300f, 1080f, 900f), viewport)?.visibleSelection)
    }

    @Test
    fun narrowSelectionNearTheEdgeIsClampedHorizontallyInsideTheViewport() {
        val bar = offset(Rect(0f, 800f, 40f, 900f))
        assertEquals(16, bar.x)
        assertInsideViewport(bar)
    }

    private val messageMenu = ThreadNewMessageMenuActionAvailability()
    private val selectionMenu = threadInPlaceSelectionMenuAvailability(messageMenu)

    @Test
    fun copyToolKeySwitchesToCopySelectionOnlyWithASelection() {
        assertEquals(
            MR.strings.thread_action_copy_markdown,
            ThreadNewMessageMenuTool.Copy.compactLabelResource(messageMenu)
        )
        assertEquals(
            MR.strings.thread_text_selection_copy_selection,
            ThreadNewMessageMenuTool.Copy.compactLabelResource(selectionMenu)
        )
    }

    @Test
    fun selectToolIsPresentWithoutASelectionAndAbsentWithOne() {
        assertTrue(ThreadNewMessageMenuTool.Select in messageMenu.primaryTools())
        assertTrue(messageMenu.enabled(ThreadNewMessageMenuTool.Select))

        assertFalse(ThreadNewMessageMenuTool.Select in selectionMenu.primaryTools())
        assertFalse(ThreadNewMessageMenuTool.Select in selectionMenu.secondaryTools())
        assertFalse(selectionMenu.enabled(ThreadNewMessageMenuTool.Select))
    }

    @Test
    fun selectionMenuLeadsWithSelectAllAndKeepsTheMessageTools() {
        assertEquals(
            listOf(
                ThreadNewMessageMenuTool.SelectAll,
                ThreadNewMessageMenuTool.Copy,
                ThreadNewMessageMenuTool.Reply,
                ThreadNewMessageMenuTool.Forward,
                ThreadNewMessageMenuTool.Save,
                ThreadNewMessageMenuTool.Translate
            ),
            selectionMenu.primaryTools()
        )
        assertTrue(selectionMenu.enabled(ThreadNewMessageMenuTool.SelectAll))
        assertEquals(
            MR.strings.thread_text_selection_select_all,
            ThreadNewMessageMenuTool.SelectAll.compactLabelResource(selectionMenu)
        )
        assertFalse(selectionMenu.more)
        assertTrue(selectionMenu.copy)
        // Outside a selection the long-press menu is unchanged.
        assertEquals(ThreadNewMessageMenuToolOrder, messageMenu.primaryTools())
        assertFalse(messageMenu.enabled(ThreadNewMessageMenuTool.SelectAll))
        // Secondary tools that apply to the message stay available in selection context.
        val withLink = threadInPlaceSelectionMenuAvailability(messageMenu.copy(copyLink = true))
        assertTrue(ThreadNewMessageMenuTool.CopyLink in withLink.secondaryTools())
    }

    @Test
    fun selectAllAndCopyRouteToTheSelectionCallbacks() {
        val routed = mutableListOf<String>()
        val callbacks =
            ThreadNewMessageMenuToolCallbacks(
                onCopy = { routed += "copy_selection" },
                onReply = { routed += "reply" },
                onForward = { routed += "forward" },
                onSave = { routed += "save" },
                onTranslate = { routed += "translate" },
                onSelect = { routed += "select" },
                onSelectAll = { routed += "select_all" }
            )
        selectionMenu.primaryTools().forEach { it.dispatch(callbacks) }
        assertEquals(listOf("select_all", "copy_selection", "reply", "forward", "save", "translate"), routed)
    }

    @Test
    fun copyInTheSelectionMenuCopiesTheSelectionAndFallsBackToTheWholeMessage() {
        assertEquals(
            "paragraph.",
            threadInPlaceSelectionCopyText(listOf("", "paragraph."), renderedNodes, body, emptySelectionFallback = body)
        )
        assertEquals(body, threadInPlaceSelectionCopyText(emptyList(), renderedNodes, body, emptySelectionFallback = body))
    }
}
