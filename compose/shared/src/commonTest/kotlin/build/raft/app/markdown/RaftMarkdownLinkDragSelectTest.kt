package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
