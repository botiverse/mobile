package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadComposerFocusDismissTest {
    @Test
    fun timelinePointerDownDismissesComposerWithoutWaitingForScrollOrPointerUp() {
        assertTrue(shouldDismissThreadComposerOnTimelineTouch(TouchType.Down))
        assertFalse(shouldDismissThreadComposerOnTimelineTouch(TouchType.Move))
        assertFalse(shouldDismissThreadComposerOnTimelineTouch(TouchType.Up))
    }
}
