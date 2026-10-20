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
}
