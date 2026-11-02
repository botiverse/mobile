package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * artin 2026-11-02 (Mac app): a mouse press that starts on a link and drags selects text from the
 * press point, as in a browser; a click still opens the link. Policy behind
 * RaftMarkdownLinkDragSelect.
 */
class RaftMarkdownLinkDragSelectTest {
    @Test
    fun aPrimaryMousePressOnALinkBecomesASelectionOnlyPastTheTouchSlop() {
        assertFalse(raftMarkdownLinkPressBecomesSelection(true, distanceFromPress = 0f, touchSlop = 8f), "a click")
        assertFalse(raftMarkdownLinkPressBecomesSelection(true, distanceFromPress = 8f, touchSlop = 8f), "within the slop")
        assertTrue(raftMarkdownLinkPressBecomesSelection(true, distanceFromPress = 8.5f, touchSlop = 8f), "a drag")
    }

    @Test
    fun aFingerOrAPressOffTheLinksNeverBecomesALinkDrag() {
        assertFalse(raftMarkdownLinkPressBecomesSelection(false, distanceFromPress = 100f, touchSlop = 8f))
    }

    @Test
    fun aTextStartsAfterTheSelectablesBeforeIt() {
        assertEquals(0, raftMarkdownSelectableStart(listOf(5, 7, 3), 0))
        assertEquals(12, raftMarkdownSelectableStart(listOf(5, 7, 3), 2))
    }

    @Test
    fun aTextIsFoundAmongTheSelectablesBySameInstanceFirst() {
        val first = AnnotatedString("same")
        val second = AnnotatedString("same")
        val selectables = listOf(AnnotatedString("other"), first, second)
        assertEquals(2, raftMarkdownSelectableIndexOf(selectables, second))
        assertEquals(1, raftMarkdownSelectableIndexOf(selectables, AnnotatedString("same")))
        assertNull(raftMarkdownSelectableIndexOf(selectables, AnnotatedString("missing")))
    }

    @Test
    fun theDragMapsToTheTextUnderThePointerElseTheNearest() {
        // The pointer's y in each text's local px: inside the second.
        assertEquals(
            1,
            raftMarkdownLinkDragNearest(
                listOf(RaftMarkdownTextExtent(height = 20f, pointerY = 30f), RaftMarkdownTextExtent(height = 20f, pointerY = 5f))
            )
        )
        // Between two paragraphs: the closer one.
        assertEquals(
            0,
            raftMarkdownLinkDragNearest(
                listOf(RaftMarkdownTextExtent(height = 20f, pointerY = 22f), RaftMarkdownTextExtent(height = 20f, pointerY = -10f))
            )
        )
        // Below the last text: the last.
        assertEquals(
            1,
            raftMarkdownLinkDragNearest(
                listOf(RaftMarkdownTextExtent(height = 20f, pointerY = 90f), RaftMarkdownTextExtent(height = 20f, pointerY = 40f))
            )
        )
        assertNull(raftMarkdownLinkDragNearest(emptyList()))
    }
}
