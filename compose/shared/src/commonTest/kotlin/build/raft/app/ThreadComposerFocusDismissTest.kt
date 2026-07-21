package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadComposerFocusDismissTest {
    @Test
    fun timelinePointerDownDismissesComposerWithoutWaitingForScrollOrPointerUp() {
        assertTrue(shouldDismissInputFocusOnScrollableChrome(TouchType.Down))
        assertFalse(shouldDismissInputFocusOnScrollableChrome(TouchType.Move))
        assertFalse(shouldDismissInputFocusOnScrollableChrome(TouchType.Up))
    }
}
