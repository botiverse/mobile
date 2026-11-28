package build.raft.app

import build.raft.app.thread.ThreadMessageSheetSettle
import build.raft.app.thread.threadMessageSheetSettle
import build.raft.app.thread.threadMessageUsesBottomSheet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadMessageActionSheetTest {

    @Test
    fun settleStepsAcrossThreeDetentsAndDismiss() {
        val compact = 480f
        val expanded = 720f
        val full = 940f
        val fling = 400f

        // Fling up from Compact -> Expanded, from Expanded -> Full, at Full -> stays Full.
        assertEquals(
            ThreadMessageSheetSettle.Expanded,
            threadMessageSheetSettle(compact, velocityDown = -600f, compact, expanded, full, fling),
        )
        assertEquals(
            ThreadMessageSheetSettle.Full,
            threadMessageSheetSettle(expanded, velocityDown = -600f, compact, expanded, full, fling),
        )
        assertEquals(
            ThreadMessageSheetSettle.Full,
            threadMessageSheetSettle(full, velocityDown = -600f, compact, expanded, full, fling),
        )

        // Fling down steps down one stop at a time: Full -> Expanded -> Compact -> Dismiss.
        assertEquals(
            ThreadMessageSheetSettle.Expanded,
            threadMessageSheetSettle(full, velocityDown = 600f, compact, expanded, full, fling),
        )
        assertEquals(
            ThreadMessageSheetSettle.Compact,
            threadMessageSheetSettle(expanded, velocityDown = 600f, compact, expanded, full, fling),
        )
        assertEquals(
            ThreadMessageSheetSettle.Dismiss,
            threadMessageSheetSettle(compact, velocityDown = 600f, compact, expanded, full, fling),
        )

        // Slow release snaps to nearest stop.
        assertEquals(
            ThreadMessageSheetSettle.Dismiss,
            threadMessageSheetSettle(200f, velocityDown = 0f, compact, expanded, full, fling),
        )
        assertEquals(
            ThreadMessageSheetSettle.Compact,
            threadMessageSheetSettle(400f, velocityDown = 0f, compact, expanded, full, fling),
        )
        assertEquals(
            ThreadMessageSheetSettle.Expanded,
            threadMessageSheetSettle(650f, velocityDown = 0f, compact, expanded, full, fling),
        )
        assertEquals(
            ThreadMessageSheetSettle.Full,
            threadMessageSheetSettle(880f, velocityDown = 0f, compact, expanded, full, fling),
        )
    }

    @Test
    fun routingUsesBottomSheetOnPhoneTouchAndPopoverOnWideDesktopMouse() {
        assertTrue(threadMessageUsesBottomSheet(wide = false, coarsePointer = true, fromLongPress = true))
        assertTrue(threadMessageUsesBottomSheet(wide = false, coarsePointer = false, fromLongPress = false))
        assertTrue(threadMessageUsesBottomSheet(wide = true, coarsePointer = true, fromLongPress = false))
        assertTrue(threadMessageUsesBottomSheet(wide = true, coarsePointer = false, fromLongPress = true))
        assertFalse(threadMessageUsesBottomSheet(wide = true, coarsePointer = false, fromLongPress = false))
    }

    @Test
    fun nativeTextSelectionStateUpdatesReactivelyAndFallsBackToReader() {
        val selection = RaftNativeTextSelection()
        assertEquals("", selection.selectedTextState)
        assertEquals("", selection.selectedText())

        selection.updateSelectedText("hello world")
        assertEquals("hello world", selection.selectedTextState)
        assertEquals("hello world", selection.selectedText())

        selection.reader = { "live slice" }
        assertEquals("live slice", selection.selectedText())

        selection.updateSelectedText("")
        assertEquals("", selection.selectedTextState)
    }
}
