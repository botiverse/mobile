package build.raft.app

import build.raft.app.resources.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun aRightClickMenusCopyTakesTheMessagesSelection() {
        // artin, 2026-10-29 (Mac): right-click opens the message menu; selected text adds "Copy".
        assertNull(
            threadContextMenuSelectionCopyText(emptyList(), listOf("Hello"), fullMessageText = "Hello"),
            "nothing selected: no Copy item (Copy Markdown is already in the menu)"
        )
        assertNull(threadContextMenuSelectionCopyText(listOf("", ""), listOf("a", "b"), fullMessageText = "a\n\nb"))
        assertEquals(
            "second para",
            threadContextMenuSelectionCopyText(listOf("", "second para"), listOf("first", "second paragraph"), fullMessageText = null)
        )
        assertEquals(
            "**first**\n\nsecond",
            threadContextMenuSelectionCopyText(listOf("first", "second"), listOf("first", "second"), fullMessageText = "**first**\n\nsecond"),
            "the whole message selected copies it as the selection menu does"
        )
    }

    @Test
    fun onlyTheMenuTheRightClickOpenedOffersItsCopy() {
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        val rightClick = RaftPopoverAnchor.forRootOffset(Offset(40f, 60f))
        host.contextMenuSelectionCopy = ThreadContextMenuSelectionCopy(messageId = "m1", anchor = rightClick, text = "picked")

        assertEquals("picked", host.contextMenuCopyFor("m1", rightClick))
        assertNull(host.contextMenuCopyFor("m2", rightClick), "another message's menu")
        assertNull(
            host.contextMenuCopyFor("m1", RaftPopoverAnchor.forRootOffset(Offset(40f, 60f))),
            "a later long press or reaction chip opens with its own anchor"
        )
        host.contextMenuSelectionCopy = null
        assertNull(host.contextMenuCopyFor("m1", rightClick), "cleared when the menu closes")
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
    private val fullSelectionMenu = threadInPlaceSelectionMenuAvailability(messageMenu, coversAll = true)
    private val partialSelectionMenu = threadInPlaceSelectionMenuAvailability(messageMenu, coversAll = false)

    @Test
    fun copyReadsCopySelectionOnlyForAPartialSelection() {
        assertEquals(
            Res.string.thread_action_copy_markdown,
            ThreadNewMessageMenuTool.Copy.compactLabelResource(messageMenu)
        )
        assertEquals(
            Res.string.thread_action_copy_markdown,
            ThreadNewMessageMenuTool.Copy.compactLabelResource(fullSelectionMenu)
        )
        assertEquals(
            Res.string.thread_text_selection_copy_selection,
            ThreadNewMessageMenuTool.Copy.compactLabelResource(partialSelectionMenu)
        )
    }

    @Test
    fun selectToolIsPresentWithoutASelectionAndAbsentWithOne() {
        assertTrue(ThreadNewMessageMenuTool.Select in messageMenu.primaryTools())
        assertTrue(messageMenu.enabled(ThreadNewMessageMenuTool.Select))

        for (menu in listOf(fullSelectionMenu, partialSelectionMenu)) {
            assertFalse(ThreadNewMessageMenuTool.Select in menu.primaryTools())
            assertFalse(ThreadNewMessageMenuTool.Select in menu.secondaryTools())
            assertFalse(menu.enabled(ThreadNewMessageMenuTool.Select))
        }
    }

    @Test
    fun wholeMessageSelectionShowsTheFullMenuWithReactions() {
        assertEquals(
            listOf(
                ThreadNewMessageMenuTool.Copy,
                ThreadNewMessageMenuTool.Reply,
                ThreadNewMessageMenuTool.Forward,
                ThreadNewMessageMenuTool.Save,
                ThreadNewMessageMenuTool.Translate
            ),
            fullSelectionMenu.primaryTools()
        )
        assertTrue(fullSelectionMenu.showsReactionRow())
        assertTrue(fullSelectionMenu.reactions)
        assertFalse(fullSelectionMenu.enabled(ThreadNewMessageMenuTool.SelectAll))
        assertFalse(fullSelectionMenu.more)
        // Secondary tools that apply to the message stay available.
        val withLink = threadInPlaceSelectionMenuAvailability(messageMenu.copy(copyLink = true), coversAll = true)
        assertTrue(ThreadNewMessageMenuTool.CopyLink in withLink.secondaryTools())
        // Outside a selection the long-press menu is unchanged.
        assertEquals(ThreadNewMessageMenuToolOrder, messageMenu.primaryTools())
        assertTrue(messageMenu.showsReactionRow())
        assertFalse(messageMenu.enabled(ThreadNewMessageMenuTool.SelectAll))
    }

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
    fun partialSelectionToolsRouteToTheSelectionCallbacks() {
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
        partialSelectionMenu.primaryTools().forEach { it.dispatch(callbacks) }
        assertEquals(listOf("select_all", "copy_selection", "reply"), routed)
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
    fun selectionMenuReferenceStaysStableWhenTheListRepublishesIt() {
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        val before = host.selectionMenu.menu

        // Each list recomposition publishes a fresh lambda (its captures changed)…
        host.selectionMenu.publish { _ -> }
        host.selectionMenu.publish { _ -> }

        // …but what rows and the popup hold is the same reference, so rows are not invalidated.
        assertTrue(before === host.selectionMenu.menu)
    }
}
